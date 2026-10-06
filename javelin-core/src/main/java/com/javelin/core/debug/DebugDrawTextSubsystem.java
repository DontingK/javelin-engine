package com.javelin.core.debug;

import com.javelin.core.JavelinEngine;
import com.javelin.core.subsystem.AbstractSubsystem;
import com.javelin.rhi.Buffer;
import com.javelin.rhi.CommandBuffer;
import com.javelin.rhi.DescriptorSet;
import com.javelin.rhi.DescriptorSetLayout;
import com.javelin.rhi.DescriptorUpdate;
import com.javelin.rhi.Device;
import com.javelin.rhi.GraphicsPipeline;
import com.javelin.rhi.RenderGraphBuilder;
import com.javelin.rhi.Sampler;
import com.javelin.rhi.Texture;
import com.javelin.rhi.VertexLayout;
import com.javelin.rhi.data.RHILoadOp;
import com.javelin.rhi.data.RHIScissor;
import com.javelin.rhi.data.RHIViewport;
import com.javelin.rhi.desc.BufferDesc;
import com.javelin.rhi.desc.DescriptorSetLayoutDesc;
import com.javelin.rhi.desc.GraphicsPipelineDesc;
import com.javelin.rhi.desc.MemoryPreference;
import com.javelin.rhi.desc.SamplerDesc;
import com.javelin.rhi.desc.ShaderDesc;
import com.javelin.rhi.desc.TextureDesc;
import com.javelin.rhi.UploadData;
import com.javelin.rhi.types.BlendFactor;
import com.javelin.rhi.types.BlendOp;
import com.javelin.rhi.types.BufferUsage;
import com.javelin.rhi.types.CompareOp;
import com.javelin.rhi.types.CullMode;
import com.javelin.rhi.types.DescriptorType;
import com.javelin.rhi.types.DynamicState;
import com.javelin.rhi.types.Format;
import com.javelin.rhi.types.FrontFace;
import com.javelin.rhi.types.PolygonMode;
import com.javelin.rhi.types.PrimitiveTopology;
import com.javelin.rhi.types.SampleCount;
import com.javelin.rhi.types.ShaderStage;
import com.javelin.rhi.types.TextureUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;

/**
 * DebugDrawText 子系统（doc/Engine_DebugText_Design.md）：bgfx dbgText 式
 * 全屏调试文本，含汉字。
 *
 * <p>职责划分：本子系统持 GPU 资源（字库图集纹理/采样器 set/自建 PSO/双帧槽顶点
 * buffer）与文本网格；渲染贡献走 {@link com.javelin.core.subsystem.Subsystem#appendOverlay}
 * 帧组装级钩子（SubsystemManager.appendOverlays 统一遍历——overlay 总在最后、
 * 写最终 backbuffer、loadOp=LOAD 叠加，无相机也出字）。</p>
 *
 * <pre>
 *   网格（格子 = 半角 8px × 行距 18px（字高 16 + 行间 2），全角占 2 格）
 *     → appendOverlay 每帧扫描非空格子拼顶点：pos vec2(NDC) + uv vec2 + fg/bg RGBA8（24B/顶点）
 *     → 双帧槽 buffer.update → bind PSO + 字体 set0 → 一次性 draw
 * </pre>
 *
 * <p>顶点数据直接 NDC（无 UBO1/set2/push，自建 PSO 完全绕开材质管线）；
 * fs 按字形覆盖度 mix(bg, fg)，无混合、无深度。PSO/图集纹理每帧成本 =
 * 全网格扫描 + 非空格子拼顶点，与 bgfx 同款成本模型。</p>
 *
 * <p>生命周期注意：GPU 资源不在 {@link #shutdown}（早于帧循环 waitIdle）销毁，
 * 走 {@link #closeGpu} 钩子（引擎在 frameLoop.close() 之后经
 * SubsystemManager.closeGpuAll 统一遍历——对齐 {@code RenderingSystem.close()} 先例）。</p>
 */
public class DebugDrawTextSubsystem extends AbstractSubsystem {

    private static final Logger log = LoggerFactory.getLogger(DebugDrawTextSubsystem.class);

    /** 顶点字节数：pos vec2 + uv vec2 + fg RGBA8 + bg RGBA8 */
    private static final int VERTEX_STRIDE = 24;
    /** 顶点帧槽（无 fence 防护下按帧轮转，UBO1 同款取舍） */
    private static final int FRAME_SLOTS = 2;
    /** bin/spv 资源路径（javelin-tool DebugTextAssets 产物，提交进 core resources） */
    private static final String FONT_RESOURCE = "/debug/debug_font16.bin";
    private static final String VERT_SPV_RESOURCE = "/debug/debugtext.vert.spv";
    private static final String FRAG_SPV_RESOURCE = "/debug/debugtext.frag.spv";

    /** 顶点布局：loc0 pos(NDC) / loc1 uv / loc2 fg / loc3 bg */
    private static final VertexLayout VERTEX_LAYOUT = VertexLayout.of(List.of(
            new VertexLayout.Attribute(0, Format.R32G32_SFLOAT, 0),
            new VertexLayout.Attribute(1, Format.R32G32_SFLOAT, 8),
            new VertexLayout.Attribute(2, Format.R8G8B8A8_UNORM, 16),
            new VertexLayout.Attribute(3, Format.R8G8B8A8_UNORM, 20)), VERTEX_STRIDE);

    private DebugFont16 font;
    private Texture fontTexture;
    private Sampler fontSampler;
    private DescriptorSetLayout fontSetLayout;
    private DescriptorSet fontSet;
    private GraphicsPipeline pipeline;

    private final Buffer[] vertexSlots = new Buffer[FRAME_SLOTS];
    /** CPU 侧顶点暂存（预分配 + clear 复用，热路径规矩） */
    private ByteBuffer cpuVerts;
    private DebugTextGrid grid;
    private int surfaceWidth;
    private int surfaceHeight;
    private boolean ready;

    // ═════════════════ 生命周期 ═════════════════

    @Override
    public void init(JavelinEngine engine) {
        super.init(engine);
        DebugText.bind(this);

        Device device = engine.device();
        font = DebugFont16.load(FONT_RESOURCE);

        // 字库图集：1bpp → R8 每像素一字节展开，一次上传
        byte[] atlas = font.atlasPixels();
        ByteBuffer pixels = ByteBuffer.allocateDirect(atlas.length).put(atlas);
        pixels.flip();
        fontTexture = device.createTexture(
                TextureDesc.texture2D(Format.R8_UNORM, font.atlasWidth(), font.atlasHeight(),
                        Set.of(TextureUsage.TRANSFER_DST, TextureUsage.SAMPLED)),
                UploadData.of(pixels));
        fontSampler = device.createSampler(SamplerDesc.nearest());
        DescriptorSetLayoutDesc fontSetLayoutDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER,
                        1, ShaderStage.FRAGMENT)));
        fontSetLayout = device.createDescriptorSetLayout(fontSetLayoutDesc);
        fontSet = device.createDescriptorSet(fontSetLayout);
        fontSet.update(DescriptorUpdate.sampledTexture(0, fontTexture, fontSampler));

        // 自建 PSO：无深度、无混合（fs 内 mix），颜色附件 = 交换链格式（pipelineFor 同款取法）
        Format colorFormat = engine.frameLoop().swapChain().image(0).desc().format();
        pipeline = device.createGraphicsPipeline(new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, readSpv(VERT_SPV_RESOURCE)),
                new ShaderDesc(ShaderStage.FRAGMENT, readSpv(FRAG_SPV_RESOURCE)),
                VERTEX_LAYOUT, PrimitiveTopology.TRIANGLE_LIST,
                CullMode.NONE, FrontFace.COUNTER_CLOCKWISE, PolygonMode.FILL, 1.0f,
                false, false, CompareOp.ALWAYS,
                false,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                null,
                List.of(colorFormat), null,
                List.of(fontSetLayoutDesc), 0,
                // overlay 恒 1x（直写 backbuffer，不随场景 MSAA 变档）
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1));

        resize(engine.frameLoop().swapChain().width(), engine.frameLoop().swapChain().height());
        ready = true;
        log.info("DebugText initialized: {} 字形, 图集 {}x{}, 网格 {}x{} 格",
                font.glyphCount(), font.atlasWidth(), font.atlasHeight(), grid.cols(), grid.rows());
    }

    /** 逻辑卸载（早于帧循环 waitIdle）：只解门面放网格，GPU 资源留给 {@link #closeGpu()} */
    @Override
    public void shutdown() {
        ready = false;
        DebugText.unbind();
        grid = null;
        cpuVerts = null;
    }

    /**
     * GPU 资源销毁钩子：引擎在 frameLoop.close()（waitIdle）之后经
     * SubsystemManager.closeGpuAll 统一遍历调用，RenderingSystem.close() 同层。
     * 此时无在帧命令，显式 dispose 安全。
     */
    @Override
    public void closeGpu() {
        if (fontTexture == null) {
            return;     // 未初始化（init 抛异常被剔除）或已关账
        }
        pipeline.dispose();
        fontSet.dispose();
        fontSetLayout.dispose();
        fontSampler.dispose();
        fontTexture.dispose();
        for (int i = 0; i < FRAME_SLOTS; i++) {
            if (vertexSlots[i] != null) {
                vertexSlots[i].dispose();
                vertexSlots[i] = null;
            }
        }
        fontTexture = null;
        pipeline = null;
        log.info("DebugText GPU resources disposed");
    }

    // ═════════════════ 门面转发（包内） ═════════════════

    void clear() {
        grid.clear();
    }

    /** 门面转发：清空单行（该行内容与追加游标归位；自动行指针不动——各走各的流） */
    void clear(int row) {
        grid.clearRow(row);
    }

    /** 门面转发：固定行追加（自动接在该行现有文字后） */
    void printfRow(int row, String txt, int attr) {
        grid.append(row, attr, txt);
    }

    /** 门面转发：自动叠行（写当前自动行，写完换下一行）；无 attr 变体默认白色前景 */
    void printfLine(String txt) {
        printfLine(txt, DebugTextColor.WHITE);
    }

    /** 门面转发：自动叠行带色（用户 2026-09-27 拍板补——彩色日志解锁） */
    void printfLine(String txt, int attr) {
        grid.appendLine(attr, txt);
    }

    // ═════════════════ overlay（引擎 buildFrameGraph 视口循环后调用） ═════════════════

    /**
     * 追加调试文本 overlay：总在所有相机视口合成之后、全屏写当前 backbuffer
     * （loadOp=LOAD），无相机（ClearOnly 兜底）也照常出字——无场景时最需要调试信息。
     *
     * <p>图结构：启用/禁用翻转图哈希各一次；网格空 → Pass 照进图但回调零 draw
     * （文本出现/消失不扰动图缓存）。</p>
     *
     * @param frameIndex 帧号（frameLoop.frameCounter()，驱动顶点双帧槽轮转）
     */
    @Override
    public void appendOverlay(RenderGraphBuilder graph, Texture backbuffer, long frameIndex) {
        if (!ready) {
            return;
        }
        int width = backbuffer.desc().width();
        int height = backbuffer.desc().height();
        if (width != surfaceWidth || height != surfaceHeight) {
            resize(width, height);
        }
        int vertexCount = buildVertices(frameIndex);
        Buffer slot = vertexSlots[(int) (frameIndex % FRAME_SLOTS)];
        Texture fontTex = fontTexture;
        DescriptorSet set = fontSet;
        GraphicsPipeline pso = pipeline;

        graph.addGraphicsPass("DebugText", pass -> {
            pass.addColorOutput(backbuffer, RHILoadOp.LOAD, null);
            pass.addTextureRead(fontTex);
            pass.setViewport(new RHIViewport(0, 0, width, height, 0f, 1f));
            pass.setScissor(new RHIScissor(0, 0, width, height));
            pass.setPipeline(pso);
            pass.setRenderCallback(cmd -> draw(cmd, set, slot, vertexCount));
        });
    }

    /** 渲染回调：零顶点直接返回（Pass 留在图内保结构哈希稳定）；PSO 已在声明期绑定（执行器自动 bindPipeline，RenderGraphImpl 346） */
    private void draw(CommandBuffer cmd, DescriptorSet set, Buffer slot, int vertexCount) {
        if (vertexCount == 0) {
            return;
        }
        cmd.bindDescriptorSet(0, set, 0, new int[0]);
        cmd.bindVertexBuffer(slot, 0);
        cmd.draw(vertexCount, 1);
    }

    // ═════════════════ 内部 ═════════════════

    /** 表面尺寸变化：网格清空重建 + 顶点 buffer 按上限重分配（bgfx 网格清空同款） */
    private void resize(int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        grid = new DebugTextGrid(width / DebugFont16.HALF_WIDTH, height / DebugFont16.ROW_PITCH);
        int maxVertices = grid.cols() * grid.rows() * 6;
        cpuVerts = ByteBuffer.allocateDirect(maxVertices * VERTEX_STRIDE).order(ByteOrder.LITTLE_ENDIAN);

        Device device = engine.device();
        for (int i = 0; i < FRAME_SLOTS; i++) {
            // 运行期 resize 重分配：旧 buffer 不显式 dispose（可能在帧内被 GPU 用），
            // 断引用交 CleanerRegistry 两帧规则延迟销毁
            vertexSlots[i] = device.createBuffer(
                    new BufferDesc((long) maxVertices * VERTEX_STRIDE,
                            Set.of(BufferUsage.VERTEX), MemoryPreference.hostVisibleCoherent()),
                    null);
        }
        log.info("DebugText 网格重建: {}x{} → {}x{} 格", width, height, grid.cols(), grid.rows());
    }

    /**
     * 全网格扫描拼顶点 + 写入本帧槽。顶点 6/格（TRIANGLE_LIST 两三角），
     * 全角以首格为准画 32px 宽四边形并跳过 colspan=2。
     *
     * @return 顶点数（0 = 网格空）
     */
    private int buildVertices(long frameIndex) {
        if (!grid.hasContent()) {
            return 0;
        }
        cpuVerts.clear();
        float invW = 2f / surfaceWidth;
        float invH = 2f / surfaceHeight;
        int atlasW = font.atlasWidth();
        int atlasH = font.atlasHeight();
        int cols = grid.cols();

        for (int row = 0; row < grid.rows(); row++) {
            float y0 = -1f + (row * DebugFont16.ROW_PITCH) * invH;
            float y1 = -1f + (row * DebugFont16.ROW_PITCH + DebugFont16.GLYPH_HEIGHT) * invH;
            for (int col = 0; col < cols; ) {
                int cp = grid.codepointAt(col, row);
                if (cp == 0) {
                    col++;
                    continue;
                }
                boolean half = DebugFont16.isHalfwidth(cp);
                int glyph = font.glyphIndex(cp);
                int px = col * DebugFont16.HALF_WIDTH;
                int quadWidth = half ? DebugFont16.HALF_WIDTH : DebugFont16.HALF_WIDTH * 2;
                float x0 = -1f + px * invW;
                float x1 = -1f + (px + quadWidth) * invW;

                // 图集 µv：半角字形只占 16px 图集格的左半
                int gx = font.atlasGlyphX(glyph);
                int gy = font.atlasGlyphY(glyph);
                float u0 = gx / (float) atlasW;
                float u1 = (gx + quadWidth) / (float) atlasW;
                float v0 = gy / (float) atlasH;
                float v1 = (gy + DebugFont16.GLYPH_HEIGHT) / (float) atlasH;

                int attr = grid.attrAt(col, row);
                byte[] fg = DebugTextColor.PALETTE[attr & 0xF];
                byte[] bg = DebugTextColor.PALETTE[(attr >> 4) & 0xF];

                // 容量保护（q数由设计不超 maxVertices，此处兜底）
                if (cpuVerts.remaining() < 6 * VERTEX_STRIDE) {
                    break;
                }
                putVertex(cpuVerts, x0, y0, u0, v0, fg, bg);
                putVertex(cpuVerts, x1, y0, u1, v0, fg, bg);
                putVertex(cpuVerts, x1, y1, u1, v1, fg, bg);
                putVertex(cpuVerts, x0, y0, u0, v0, fg, bg);
                putVertex(cpuVerts, x1, y1, u1, v1, fg, bg);
                putVertex(cpuVerts, x0, y1, u0, v1, fg, bg);

                col += half ? 1 : 2;
            }
        }

        cpuVerts.flip();
        int vertexCount = cpuVerts.remaining() / VERTEX_STRIDE;
        if (vertexCount > 0) {
            vertexSlots[(int) (frameIndex % FRAME_SLOTS)].update(0, cpuVerts);
        }
        return vertexCount;
    }

    private static void putVertex(ByteBuffer out, float x, float y, float u, float v,
                                  byte[] fg, byte[] bg) {
        out.putFloat(x).putFloat(y).putFloat(u).putFloat(v);
        out.put(fg[0]).put(fg[1]).put(fg[2]).put(fg[3]);
        out.put(bg[0]).put(bg[1]).put(bg[2]).put(bg[3]);
    }

    /** 读 classpath .spv 二进制资源 */
    private static byte[] readSpv(String resource) {
        try (InputStream stream = DebugDrawTextSubsystem.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("SPIR-V 资源不存在: " + resource + "（先跑 javelin-tool DebugTextAssets 生成）");
            }
            try (InputStream in = new BufferedInputStream(stream)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new IllegalStateException("DebugText SPIR-V 加载失败: " + e.getMessage(), e);
        }
    }
}

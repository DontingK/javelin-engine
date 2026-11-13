package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.core.render.pipeline.PostProcessNode;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.CommandBuffer;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Sampler;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.UploadData;
import cn.donting.javelin.rhi.VertexLayout;
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.desc.GraphicsPipelineDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.SamplerDesc;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.types.BlendFactor;
import cn.donting.javelin.rhi.types.BlendOp;
import cn.donting.javelin.rhi.types.BufferUsage;
import cn.donting.javelin.rhi.types.ColorComponentFlags;
import cn.donting.javelin.rhi.types.CompareOp;
import cn.donting.javelin.rhi.types.CullMode;
import cn.donting.javelin.rhi.types.DescriptorType;
import cn.donting.javelin.rhi.types.DynamicState;
import cn.donting.javelin.rhi.types.Filter;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.FrontFace;
import cn.donting.javelin.rhi.types.PolygonMode;
import cn.donting.javelin.rhi.types.PrimitiveTopology;
import cn.donting.javelin.rhi.types.SampleCount;
import cn.donting.javelin.rhi.types.SamplerAddressMode;
import cn.donting.javelin.rhi.types.SamplerMipmapMode;
import cn.donting.javelin.rhi.types.ShaderStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后处理链尾 Tonemap Pass（Engine_PostProcess_Design §3.5）：HDR 场景色 → 高光肩部 → 显示目标。
 *
 * <p><b>覆盖制</b>（LightingPass/SkyboxPass 同款先例）：不消费材质 Pass 声明，着色器走资产
 * 导入链（内置 {@code builtin-asset-core/postprocess/tonemap.vert/.frag} 经 SpvShaderImporter
 * 编译）。整屏大三角形按本视口 viewport/scissor 限定范围。</p>
 *
 * <p><b>清屏职责接管</b>：本 Pass 恒写显示终点（colorTarget），承担原场景 Pass 的
 * CLEAR + isRegionClear + scissor 语义。HDR 下 stacking 相机（DEPTH_ONLY/DONT_CLEAR）
 * 亦按整视口 CLEAR 独立输出——每视口 HDR 缓冲不承载跨相机颜色叠加（§4.3 v1 边界）。</p>
 *
 * <p><b>描述符</b>：set0 为本 Pass 专属单纹理布局（binding0 组合采样器，链输入）；无相机块
 * （tonemap 不需要相机数据）、push 常量 vec4 params（x = KNEE 肩部起点，运行期由「项目设置」调）。</p>
 */
public class TonemapPass implements PostProcessNode {

    private static final Logger log = LoggerFactory.getLogger(TonemapPass.class);

    /** 内置 tonemap 着色器 loadAsset 路径（builtin-asset-core 释放后的相对路径） */
    private static final String VERT_SHADER_PATH = "core/postprocess/tonemap.vert";
    private static final String FRAG_SHADER_PATH = "core/postprocess/tonemap.frag";

    /** push 常量：vec4 params（x = KNEE 肩部起点），16B */
    private static final int PUSH_SIZE = 16;

    /** 全屏大三角形 NDC 顶点（(-1,-1)/(3,-1)/(-1,3)，vec2、stride 8B） */
    private static final float[] TRIANGLE_VERTICES = {
            -1.0f, -1.0f,
            3.0f, -1.0f,
            -1.0f, 3.0f,
    };

    private final Device device;
    private final byte[] vertSpv;
    private final byte[] fragSpv;
    private final Buffer vertexBuffer;
    private final VertexLayout triangleLayout;
    private final Sampler inputSampler;
    private DescriptorSetLayout inputSetLayout;
    /** 覆盖制 PSO 缓存：key = 显示目标格式（HDR 下链尾写 backbuffer/targetOverride） */
    private final Map<Format, GraphicsPipeline> pipelines = new HashMap<>();
    /** 每视口链输入描述符集（input 纹理随 resize/换绑变化，按视口缓存身份） */
    private final Map<String, InputSlot> inputSets = new HashMap<>();
    private RHIClearColor clearColor = new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);
    /** 每帧复用的 push 打包缓冲（录制串行，回调内填后即推） */
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    /** Tonemap 肩部起点（运行期由「项目设置」下发；起步常量同 §3.5） */
    private float knee = 0.8f;
    private boolean drawLogged;

    /**
     * @param device       RHI 设备（PSO/VB/描述符集创建）
     * @param assetManager 资产管理器（装载内置 tonemap.vert/.frag 的 SpvShader 产物）
     */
    public TonemapPass(Device device, AssetManager assetManager) {
        this.device = device;
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH, "tonemap.vert");
        this.fragSpv = loadSpv(assetManager, FRAG_SHADER_PATH, "tonemap.frag");
        this.triangleLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32_SFLOAT, 0)), 8);
        this.vertexBuffer = createVertexBuffer();
        // 全屏 1:1 采样：NEAREST + CLAMP 最省且与输入像素一一对应（LightingPass 同口径）
        this.inputSampler = device.createSampler(new SamplerDesc(
                Filter.NEAREST, Filter.NEAREST, SamplerMipmapMode.NEAREST,
                SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE,
                SamplerAddressMode.CLAMP_TO_EDGE, 1.0f, 1.0f));
    }

    /** 清屏色（管线全局默认色；相机自带 clearColor 优先） */
    public void setClearColor(RHIClearColor clearColor) {
        this.clearColor = clearColor;
    }

    /** 肩部起点下发（运行期由组合根 PostProcessSetup 调，录制回调按当前值 push） */
    public void setKnee(float knee) {
        this.knee = knee;
    }

    /** push 块：vec4 params（x = knee），16B */
    private void pushParams(CommandBuffer cmd) {
        pushData.clear();
        pushData.putFloat(knee);
        pushData.putFloat(0.0f);
        pushData.putFloat(0.0f);
        pushData.putFloat(0.0f);
        pushData.position(0);
        cmd.pushConstants(0, pushData);
    }

    @Override
    public String name() {
        return "Tonemap";
    }

    /** 身份标识（覆盖制不查材质 shader 的 Pass；与用户 shader 自定义 Pass 名无消费关系） */
    @Override
    public String shaderPassName() {
        return "Tonemap";
    }

    /** 终节点：Tonemap 恒置链尾（Bloom/SSAO 等须读未 tonemap 的 HDR 缓冲，§3.5） */
    @Override
    public boolean isTerminal() {
        return true;
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx, Texture input, Texture output) {
        RenderView view = ctx.view();
        Format colorFormat = output.desc().format();
        RHIClearColor clear = view.clearColor() != null ? view.clearColor() : clearColor;
        boolean regionClear = AbstractForwardPass.isRegionClear(view, output);
        graph.addGraphicsPass(view.name() + "_Tonemap", pass -> {
            // 恒清屏：HDR 生效时每视口独立输出（stacking 的跨相机 LOAD 叠加不成立，§4.3）
            if (regionClear) {
                pass.addColorOutput(output, RHILoadOp.CLEAR, clear, view.scissor());
            } else {
                pass.addColorOutput(output, RHILoadOp.CLEAR, clear);
            }
            // 链输入读声明：建色 Pass → Tonemap 依赖边 + 布局转 SHADER_READ_ONLY
            pass.addTextureRead(input);
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            pass.setRenderCallback(cmd -> {
                if (!drawLogged) {
                    drawLogged = true;
                    log.info("首帧 Tonemap 回调执行: 视口[{}] outputFormat={}", view.name(), colorFormat);
                }
                cmd.bindPipeline(pipelineFor(colorFormat));
                pushParams(cmd);
                cmd.bindDescriptorSet(0, inputSetFor(view.name(), input), 0, null);
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    /** 释放自建资源（须 device waitIdle 后；VB/PSO/采样器/描述符集） */
    public void close() {
        pipelines.values().forEach(GraphicsPipeline::dispose);
        pipelines.clear();
        inputSets.values().forEach(slot -> slot.set.dispose());
        inputSets.clear();
        if (inputSetLayout != null) {
            inputSetLayout.dispose();
        }
        inputSampler.dispose();
        vertexBuffer.dispose();
    }

    /** 链输入描述符集：纹理身份不变复用，换绑（resize）重建 */
    private DescriptorSet inputSetFor(String viewName, Texture input) {
        InputSlot slot = inputSets.get(viewName);
        if (slot != null && slot.texture == input) {
            return slot.set;
        }
        if (inputSetLayout == null) {
            inputSetLayout = device.createDescriptorSetLayout(inputLayoutDesc());
        }
        DescriptorSet set = device.createDescriptorSet(inputSetLayout);
        set.update(DescriptorUpdate.sampledTexture(0, input, inputSampler));
        if (slot != null) {
            slot.set.dispose();
        }
        inputSets.put(viewName, new InputSlot(set, input));
        return set;
    }

    /** set0 专属布局：binding0 组合采样器（fs 读链输入） */
    private static DescriptorSetLayoutDesc inputLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }

    private GraphicsPipeline pipelineFor(Format colorFormat) {
        GraphicsPipeline cached = pipelines.get(colorFormat);
        if (cached != null) {
            return cached;
        }
        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, vertSpv),
                new ShaderDesc(ShaderStage.FRAGMENT, fragSpv),
                triangleLayout,
                PrimitiveTopology.TRIANGLE_LIST,
                CullMode.NONE,                            // 全屏三角形绕序无关
                FrontFace.COUNTER_CLOCKWISE,
                PolygonMode.FILL,
                1.0f,
                false, false, CompareOp.LESS,             // 无深度附件，深度态关
                false,                                    // blend Off：链尾整块覆盖
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                        ColorComponentFlags.B, ColorComponentFlags.A),
                List.of(colorFormat), null,               // 无深度附件
                // set0 = 本 Pass 专属输入采样布局（无相机块）；push = vec4 params（肩部）
                List.of(inputLayoutDesc()), PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(colorFormat, created);
        log.info("tonemap PSO created: colorFormat={}", colorFormat);
        return created;
    }

    private Buffer createVertexBuffer() {
        ByteBuffer data = ByteBuffer.allocateDirect(TRIANGLE_VERTICES.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : TRIANGLE_VERTICES) {
            data.putFloat(v);
        }
        data.flip();
        return device.createBuffer(new BufferDesc(data.remaining(),
                Set.of(BufferUsage.VERTEX), MemoryPreference.hostVisibleCoherent()),
                UploadData.of(data));
    }

    /**
     * 装载内置 SPIR-V 着色器资产（红线 15——缺失/坏产物显性报错不静默兜底）。
     */
    private static byte[] loadSpv(AssetManager assetManager, String path, String label) {
        SpvShader spv = assetManager.loadAsset(path, SpvShader.class);
        if (spv == null) {
            throw new IllegalStateException("后处理着色器资产未找到: " + path
                    + "（内置资产应随引擎启动释放进 .javelin/assets 并经导入链编译——"
                    + "检查 builtin-asset-core/postprocess/" + label + " 与伴生 .meta）");
        }
        if (!spv.isValid()) {
            throw new IllegalStateException("后处理着色器为坏产物（编译失败 stub）: " + path
                    + "——请 reimport 修复");
        }
        return spv.getSpvData();
    }

    /** 每视口链输入描述符集槽（纹理换代即重建） */
    private static final class InputSlot {
        final DescriptorSet set;
        final Texture texture;

        InputSlot(DescriptorSet set, Texture texture) {
            this.set = set;
            this.texture = texture;
        }
    }
}

package cn.donting.javelin.core;

import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.CommandBuffer;
import cn.donting.javelin.rhi.ComputePipeline;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Fence;
import cn.donting.javelin.rhi.GPUResource;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Sampler;
import cn.donting.javelin.rhi.SubmitInfo;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.TextureRegion;
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.data.RHIScissor;
import cn.donting.javelin.rhi.data.RHIViewport;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.ComputePipelineDesc;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.desc.GraphicsPipelineDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.SamplerDesc;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.desc.TextureDesc;
import cn.donting.javelin.rhi.types.BlendFactor;
import cn.donting.javelin.rhi.types.BlendOp;
import cn.donting.javelin.rhi.types.BufferUsage;
import cn.donting.javelin.rhi.types.CompareOp;
import cn.donting.javelin.rhi.types.CullMode;
import cn.donting.javelin.rhi.types.DescriptorType;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.FrontFace;
import cn.donting.javelin.rhi.types.PolygonMode;
import cn.donting.javelin.rhi.types.PrimitiveTopology;
import cn.donting.javelin.rhi.types.SampleCount;
import cn.donting.javelin.rhi.types.ShaderStage;
import cn.donting.javelin.rhi.types.TextureLayout;
import cn.donting.javelin.rhi.types.TextureUsage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;

/**
 * 纹理种类底座验证（RHI_Texture_Types_Design §5）：真实 GPU 跑图，两条链路同时验。
 *
 * <p>① <b>3D 纹理 + compute 写 storage image</b>：compute Pass 以 {@code image3D} 写
 * 16³ R8 体数据（图案 = {@code (x ^ y ^ z) & 7}，纯整数位运算，CPU 侧同式复算）→ 图形 Pass
 * 用 {@code sampler3D} 取 z=0.5 切片显示（验证 GENERAL → SHADER_READ_ONLY 跨 Pass 屏障 +
 * 3D 默认视图绑定 + 3D 采样）→ 帧 30 用一次性命令缓冲 {@code copyImageToBuffer} 读回，
 * 逐体素比对（验证 storage image 写入与 GENERAL 布局）。</p>
 *
 * <p>② <b>2D 纹理数组上传整层</b>：{@code TextureDesc.texture2DArray}（4×4×3 层 RGBA8）
 * 经 {@code UploadData} 上传已知字节 → 读回 whole2DArray 区域逐字节比对（验证按层整拷的
 * 上传路径，此前非 CUBE 一律按 whole2D 只拷 1 层）。</p>
 *
 * <p>着色器 = test resources 源码经 tool shaderc 编译（纯 RHI 层自建布局，不经引擎 set1 材质链）。</p>
 *
 * <p>运行：{@code gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.TextureTypesTest}
 * 验收：画面 = 体数据 z 中切片的灰度 XOR 图案稳定显示 + 控制台两条「读回比对通过」+ ESC 退出不崩。</p>
 */
public final class TextureTypesTest extends RhiTestBase {

    /** 3D 体数据边长（16³，local_size 8 → dispatch 2×2×2） */
    private static final int VOXEL = 16;

    /** 2D 数组边长 / 层数（4×4 RGBA8 × 3 层） */
    private static final int ARRAY_SIZE = 4;
    private static final int ARRAY_LAYERS = 3;

    /** 读回比对帧（帧 30 时前序帧 fence 已 await，写入对 CPU 可见） */
    private static final int VERIFY_FRAME = 30;

    /** 3D 读回缓冲字节数 */
    private static final int VOXEL_BYTES = VOXEL * VOXEL * VOXEL;
    /** 数组读回缓冲字节数（每像素 RGBA8） */
    private static final int ARRAY_BYTES = ARRAY_SIZE * ARRAY_SIZE * ARRAY_LAYERS * 4;

    private Texture voxelTexture;
    private Texture arrayTexture;
    private ComputePipeline voxelPipeline;
    private DescriptorSet voxelSet;
    private GraphicsPipeline quadPipeline;
    private DescriptorSet fragSet;
    private Sampler voxelSampler;
    private Buffer voxelReadback;
    private Buffer arrayReadback;

    private final ByteBuffer readback = ByteBuffer.allocateDirect(
            Math.max(VOXEL_BYTES, ARRAY_BYTES)).order(ByteOrder.LITTLE_ENDIAN);

    public static void main(String[] args) {
        new TextureTypesTest().run();
    }

    @Override
    protected String testName() {
        return "TextureTypesTest";
    }

    @Override
    protected RHIClearColor clearColor() {
        return new RHIClearColor(0.02f, 0.02f, 0.05f, 1.0f);
    }

    @Override
    protected void onInit() {
        log.info("[init] 创建 3D 体数据纹理 {}³ R8_UNORM（STORAGE/SAMPLED/TRANSFER_SRC）", VOXEL);
        voxelTexture = device.createTexture(TextureDesc.texture3D(
                Format.R8_UNORM, VOXEL, VOXEL, VOXEL,
                Set.of(TextureUsage.STORAGE, TextureUsage.SAMPLED,
                        TextureUsage.TRANSFER_SRC, TextureUsage.TRANSFER_DST)), null);

        log.info("[init] 创建 2D 数组纹理 {}×{}×{} 层 RGBA8 + 上传已知字节",
                ARRAY_SIZE, ARRAY_SIZE, ARRAY_LAYERS);
        arrayTexture = device.createTexture(TextureDesc.texture2DArray(
                Format.R8G8B8A8_UNORM, ARRAY_SIZE, ARRAY_SIZE, ARRAY_LAYERS,
                Set.of(TextureUsage.SAMPLED, TextureUsage.TRANSFER_SRC, TextureUsage.TRANSFER_DST)),
                cn.donting.javelin.rhi.UploadData.of(arrayPattern(), 1));

        DescriptorSetLayoutDesc computeLayout = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.STORAGE_IMAGE, 1,
                        ShaderStage.COMPUTE)));
        log.info("[init] 创建 compute 管线（image3D 写）");
        voxelPipeline = device.createComputePipeline(new ComputePipelineDesc(
                new ShaderDesc(ShaderStage.COMPUTE,
                        compileShaderResource("compute/texture3d-write.compute",
                                cn.donting.javelin.tool.shaderc.ShaderStage.COMPUTE)),
                List.of(computeLayout), 0));

        voxelSet = device.createDescriptorSet(device.createDescriptorSetLayout(computeLayout));
        voxelSet.update(DescriptorUpdate.storageTexture(0, voxelTexture));

        DescriptorSetLayoutDesc fragLayout = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
        voxelSampler = device.createSampler(SamplerDesc.linear());
        fragSet = device.createDescriptorSet(device.createDescriptorSetLayout(fragLayout));
        fragSet.update(DescriptorUpdate.sampledTexture(0, voxelTexture, voxelSampler));

        log.info("[init] 创建图形管线（全屏 quad，fs 采样 sampler3D）");
        quadPipeline = device.createGraphicsPipeline(new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX,
                        compileShaderResource("shader/texture3d-read.vert",
                                cn.donting.javelin.tool.shaderc.ShaderStage.VERTEX)),
                new ShaderDesc(ShaderStage.FRAGMENT,
                        compileShaderResource("shader/texture3d-read.frag",
                                cn.donting.javelin.tool.shaderc.ShaderStage.FRAGMENT)),
                null,   // 无顶点缓冲：vs 以 gl_VertexIndex 内联全屏 quad
                PrimitiveTopology.TRIANGLE_LIST,
                CullMode.NONE, FrontFace.COUNTER_CLOCKWISE, PolygonMode.FILL, 1.0f,
                false, false, CompareOp.ALWAYS,
                false,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                null,
                List.of(frameLoop.swapChain().image(0).desc().format()), Format.D32_SFLOAT,
                List.of(fragLayout), 0,
                Set.of(cn.donting.javelin.rhi.types.DynamicState.VIEWPORT,
                        cn.donting.javelin.rhi.types.DynamicState.SCISSOR),
                SampleCount.COUNT_1));

        // 读回缓冲：TRANSFER_DST + hostVisibleCoherent（copyImageToBuffer 目标，CPU 直接读回）
        voxelReadback = device.createBuffer(new BufferDesc(VOXEL_BYTES,
                Set.of(BufferUsage.TRANSFER_DST), MemoryPreference.hostVisibleCoherent()), null);
        arrayReadback = device.createBuffer(new BufferDesc(ARRAY_BYTES,
                Set.of(BufferUsage.TRANSFER_DST), MemoryPreference.hostVisibleCoherent()), null);

        log.info("TextureTypesTest 初始化完成: 3D {}³ + 2D 数组 {}×{}×{} 层",
                VOXEL, ARRAY_SIZE, ARRAY_SIZE, ARRAY_LAYERS);
    }

    @Override
    protected void buildPasses(RenderGraphBuilder graph, Texture backbuffer) {
        // compute 段：写 3D storage image（GENERAL）
        graph.addComputePass("VoxelCompute", pass -> {
            pass.setPipeline(voxelPipeline);
            pass.addTextureStorageWrite(voxelTexture);
            pass.setRenderCallback(cmd -> {
                cmd.bindDescriptorSet(0, voxelSet, 0, null);
                cmd.dispatch(VOXEL / 8, VOXEL / 8, VOXEL / 8);
            });
        });
        // 图形段：sampler3D 采样（GENERAL → SHADER_READ_ONLY 屏障由执行器生成）
        graph.addGraphicsPass(testName(), pass -> {
            pass.addColorOutput(backbuffer, RHILoadOp.CLEAR, clearColor());
            pass.addTextureRead(voxelTexture);
            pass.setPipeline(quadPipeline);
            pass.setViewport(new RHIViewport(0, 0, window.width(), window.height(), 0.0f, 1.0f));
            pass.setScissor(new RHIScissor(0, 0, window.width(), window.height()));
            pass.setRenderCallback(cmd -> {
                cmd.bindDescriptorSet(0, fragSet, 0, null);
                cmd.draw(6, 1);
            });
        });

        if (frameLoop.frameCounter() == VERIFY_FRAME) {
            verifyReadback();
        }
    }

    /**
     * 两张纹理各走一次性命令缓冲读回（{@code Texture.readback()} 仍 P2 未实现）：
     * 屏障 SHADER_READ_ONLY → TRANSFER_SRC → copyImageToBuffer → 转回，fence 等待后 CPU 读。
     */
    private void verifyReadback() {
        copyTextureToBuffer(voxelTexture, voxelReadback,
                TextureRegion.whole3D(VOXEL, VOXEL, VOXEL));
        copyTextureToBuffer(arrayTexture, arrayReadback,
                TextureRegion.whole2DArray(ARRAY_SIZE, ARRAY_SIZE, ARRAY_LAYERS));

        verifyVoxelPattern();
        verifyArrayUploadPattern();
    }

    private void copyTextureToBuffer(Texture texture, Buffer dst, TextureRegion region) {
        CommandBuffer cmd = device.allocateCommandBuffer(false);
        cmd.begin();
        cmd.pipelineBarrier(texture, TextureLayout.SHADER_READ_ONLY, TextureLayout.TRANSFER_SRC);
        cmd.copyImageToBuffer(texture, dst, region);
        cmd.pipelineBarrier(texture, TextureLayout.TRANSFER_SRC, TextureLayout.SHADER_READ_ONLY);
        cmd.end();

        Fence fence = device.createFence();
        device.graphicsQueue().submit(cmd, SubmitInfo.of(fence));
        fence.await();
        fence.dispose();
        cmd.dispose();
    }

    /** 3D 逐体素比对（索引 = x + y·N + z·N²，UNORM8 量化差 ≤ 1 码值） */
    private void verifyVoxelPattern() {
        readback.clear();
        voxelReadback.read(0, VOXEL_BYTES, readback);
        int mismatches = 0;
        for (int z = 0; z < VOXEL; z++) {
            for (int y = 0; y < VOXEL; y++) {
                for (int x = 0; x < VOXEL; x++) {
                    float expected = ((x ^ y ^ z) & 7) / 7.0f;
                    byte gotByte = readback.get(x + y * VOXEL + z * VOXEL * VOXEL);
                    float got = (gotByte & 0xFF) / 255.0f;
                    if (Math.abs(expected - got) > 1.5f / 255.0f) {
                        if (mismatches < 5) {
                            log.error("3D 读回不一致: ({},{},{}) expected={} got={}",
                                    x, y, z, expected, got);
                        }
                        mismatches++;
                    }
                }
            }
        }
        if (mismatches > 0) {
            throw new IllegalStateException("3D storage image 读回比对失败: " + mismatches
                    + "/" + VOXEL_BYTES + " 体素不一致（storage 绑定 / GENERAL 布局 / 3D 视图链路断）");
        }
        log.info("3D 读回比对通过：{}³ = {} 体素全一致（compute 写 storage image 3D 全链正确）",
                VOXEL, VOXEL_BYTES);
    }

    /** 2D 数组逐字节比对（层顺序紧密打包：index = (x + y·N + layer·N²)·4） */
    private void verifyArrayUploadPattern() {
        readback.clear();
        arrayReadback.read(0, ARRAY_BYTES, readback);
        ByteBuffer expected = arrayPattern();
        int mismatches = 0;
        for (int i = 0; i < ARRAY_BYTES; i++) {
            if (readback.get(i) != expected.get(i)) {
                if (mismatches < 5) {
                    log.error("数组读回不一致: i={} expected={} got={}",
                            i, expected.get(i), readback.get(i));
                }
                mismatches++;
            }
        }
        if (mismatches > 0) {
            throw new IllegalStateException("2D 数组上传读回比对失败: " + mismatches
                    + "/" + ARRAY_BYTES + " 字节不一致（上传区域按层整拷路径断）");
        }
        log.info("2D 数组上传比对通过：{} 字节全一致（whole2DArray 按层整拷正确）", ARRAY_BYTES);
    }

    /** 数组上传图案：逐像素 RGBA 同值 = x + y·N + layer·N²（3 层各段可辨） */
    private static ByteBuffer arrayPattern() {
        ByteBuffer buf = ByteBuffer.allocateDirect(ARRAY_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int layer = 0; layer < ARRAY_LAYERS; layer++) {
            for (int y = 0; y < ARRAY_SIZE; y++) {
                for (int x = 0; x < ARRAY_SIZE; x++) {
                    byte v = (byte) (x + y * ARRAY_SIZE + layer * ARRAY_SIZE * ARRAY_SIZE);
                    buf.put(v).put(v).put(v).put((byte) 0xFF);
                }
            }
        }
        buf.flip();
        return buf;
    }

    @Override
    protected void onDispose() {
        dispose(arrayReadback);
        dispose(voxelReadback);
        dispose(fragSet);
        dispose(voxelSet);
        dispose(quadPipeline);
        dispose(voxelPipeline);
        dispose(voxelSampler);
        dispose(arrayTexture);
        dispose(voxelTexture);
    }

    private static void dispose(GPUResource r) {
        if (r != null) {
            r.dispose();
        }
    }
}

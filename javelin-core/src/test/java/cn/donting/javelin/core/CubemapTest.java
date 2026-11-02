package cn.donting.javelin.core;

import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.CommandBuffer;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Fence;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.PassDef;
import cn.donting.javelin.rhi.Sampler;
import cn.donting.javelin.rhi.SubmitInfo;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.TextureRegion;
import cn.donting.javelin.rhi.UploadData;
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHIScissor;
import cn.donting.javelin.rhi.data.RHIViewport;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.desc.GraphicsPipelineDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.SamplerDesc;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.desc.TextureDesc;
import cn.donting.javelin.rhi.types.BlendFactor;
import cn.donting.javelin.rhi.types.BlendOp;
import cn.donting.javelin.rhi.types.BufferUsage;
import cn.donting.javelin.rhi.types.ColorComponentFlags;
import cn.donting.javelin.rhi.types.CompareOp;
import cn.donting.javelin.rhi.types.CullMode;
import cn.donting.javelin.rhi.types.DescriptorType;
import cn.donting.javelin.rhi.types.DynamicState;
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
 * 三期批次⑤ cubemap 底座验收（Engine_Skybox_Design §7 第三条：六面上传+采样读回，
 * TextureTest 的 cubemap 兄弟件）。
 *
 * <p>覆盖：CUBE_COMPATIBLE 旗标（无此位 CUBE 视图 validation 报错）→ textureCube
 * 便捷构造 → createTexture 上传（六面渐变紧密打包，单命令 layerCount=6 整拷）→ CUBE 视图采样
 * （samplerCube + 方向向量，vs push 旋转矩阵驱动）→ 六面读回 CPU 比对
 * （一次性手动 staging：屏障→单命令六层整拷→fence await，uploadTexture 镜像——
 * TextureReadbackRing 未收 layerCount 语义，测试本地收口）。</p>
 *
 * <p>验收画面：全屏颜色随视角方向变化（看向 +X 红 / -X 品红 / +Y 绿 / -Y 青 /
 * +Z 蓝 / -Z 黄），旋转连续过渡；帧 60 控制台输出六面角点读回比对结果。</p>
 *
 * <p>运行：gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.CubemapTest
 * 验收：六向颜色正确 + 旋转平滑 + 「cubemap 六面读回比对通过」+ ESC 退出不崩。</p>
 */
public final class CubemapTest extends RhiTestBase {

    /** 单面边长（小图：读回数据量 4KB/面） */
    private static final int FACE_SIZE = 32;
    /** 读回比对帧（画面先跑一会儿再读，肉眼与数据双验收） */
    private static final int VERIFY_FRAME = 60;

    /** 六面基色（Vulkan cubemap layer 序：+X/-X/+Y/-Y/+Z/-Z） */
    private static final float[][] FACE_COLORS = {
            {1.0f, 0.0f, 0.0f},   // +X 红
            {1.0f, 0.0f, 1.0f},   // -X 品红
            {0.0f, 1.0f, 0.0f},   // +Y 绿
            {0.0f, 1.0f, 1.0f},   // -Y 青
            {0.0f, 0.0f, 1.0f},   // +Z 蓝
            {1.0f, 1.0f, 0.0f},   // -Z 黄
    };

    private static final String VS_SRC = """
            #version 450
            // 全屏三角形（gl_VertexIndex 内联，无顶点缓冲）+ push 旋转矩阵 → 世界方向
            layout(push_constant) uniform PushBlock {
                mat4 rotation;
            } pc;
            layout(location = 0) out vec3 vDir;
            void main() {
                float x = float((gl_VertexIndex << 1) & 2) * 2.0 - 1.0;
                float y = float(gl_VertexIndex & 2) * 2.0 - 1.0;
                gl_Position = vec4(x, y, 0.0, 1.0);
                vDir = mat3(pc.rotation) * vec3(x, y, 0.5);
            }
            """;

    private static final String FS_SRC = """
            #version 450
            layout(set = 0, binding = 0) uniform samplerCube uCube;
            layout(location = 0) in vec3 vDir;
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = vec4(texture(uCube, normalize(vDir)).rgb, 1.0);
            }
            """;

    /** push 布局：旋转矩阵 64B（单 vs stage） */
    private static final int PUSH_SIZE = 64;

    private Texture cubemap;
    private Sampler sampler;
    private DescriptorSetLayout setLayout;
    private DescriptorSet set;
    private GraphicsPipeline pipeline;
    /** 每帧复用 push 打包（小端） */
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final float[] rotationMatrix = new float[16];
    private float angle;
    private boolean verified;

    public static void main(String[] args) {
        new CubemapTest().run();
    }

    @Override
    protected String testName() {
        return "CubemapTest";
    }

    @Override
    protected RHIClearColor clearColor() {
        return new RHIClearColor(0.02f, 0.02f, 0.05f, 1.0f);
    }

    @Override
    protected void onInit() {
        cubemap = createGradientCubemap();
        sampler = device.createSampler(SamplerDesc.linear());

        setLayout = device.createDescriptorSetLayout(new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT))));
        set = device.createDescriptorSet(setLayout);
        set.update(DescriptorUpdate.sampledTexture(0, cubemap, sampler));

        pipeline = createPipeline();
    }

    @Override
    protected void onPass(PassDef pass) {
        pass.addTextureRead(cubemap);
        int w = window.width();
        int h = window.height();
        // 手动 Y 翻转：负高度视口（无投影，同 TextureTest 约定）
        pass.setViewport(new RHIViewport(0, h, w, -h, 0.0f, 1.0f));
        pass.setScissor(new RHIScissor(0, 0, w, h));
        pass.setPipeline(pipeline);
        // 帧计数：渲染若干帧后做一次性读回比对（画面已稳定）
        long frame = frameLoop.frameCounter();
        pass.setRenderCallback(cmd -> {
            angle += 0.01f;
            pushData.clear();
            putRotationY(angle);
            cmd.pushConstants(0, pushData);
            cmd.bindDescriptorSet(0, set, 0, null);
            cmd.draw(3, 1);
            if (!verified && frame >= VERIFY_FRAME) {
                verified = true;
                verifyFaces();
            }
        });
    }

    @Override
    protected void onDispose() {
        if (set != null) {
            set.dispose();
        }
        if (setLayout != null) {
            setLayout.dispose();
        }
        if (sampler != null) {
            sampler.dispose();
        }
        if (cubemap != null) {
            cubemap.dispose();
        }
        if (pipeline != null) {
            pipeline.dispose();
        }
    }

    /** 六面渐变紧密打包（每面 32×32 RGBA：base 色沿对角线渐变到 base+0.25）→ textureCube 上传 */
    private Texture createGradientCubemap() {
        int facePixels = FACE_SIZE * FACE_SIZE;
        ByteBuffer pixels = ByteBuffer.allocateDirect(facePixels * 6 * 4);
        for (int face = 0; face < 6; face++) {
            float[] base = FACE_COLORS[face];
            for (int y = 0; y < FACE_SIZE; y++) {
                for (int x = 0; x < FACE_SIZE; x++) {
                    float t = (x + y) / (float) (2 * (FACE_SIZE - 1));   // 0~0.5 对角渐变
                    pixels.put((byte) (clamp01(base[0] + t) * 255));
                    pixels.put((byte) (clamp01(base[1] + t) * 255));
                    pixels.put((byte) (clamp01(base[2] + t) * 255));
                    pixels.put((byte) 0xFF);
                }
            }
        }
        pixels.flip();

        TextureDesc desc = TextureDesc.textureCube(Format.R8G8B8A8_UNORM, FACE_SIZE,
                Set.of(TextureUsage.TRANSFER_DST, TextureUsage.SAMPLED));
        return device.createTexture(desc, UploadData.of(pixels));
    }

    /** 一次性六面读回比对：手动 staging 单命令整拷（cubeFaces layerCount=6），uploadTexture 镜像 */
    private void verifyFaces() {
        int faceBytes = FACE_SIZE * FACE_SIZE * 4;
        // staging 双用途：GPU copyImageToBuffer 目标 + CPU vkMapMemory 读出（hostVisibleCoherent）
        Buffer staging = device.createBuffer(new BufferDesc((long) faceBytes * 6,
                Set.of(BufferUsage.TRANSFER_DST), MemoryPreference.hostVisibleCoherent()), null);
        ByteBuffer result = ByteBuffer.allocateDirect(faceBytes * 6).order(ByteOrder.nativeOrder());

        // 读回时真实物理布局 = SHADER_READ_ONLY（上传停在 TRANSFER_DST，帧内图屏障
        // UNDEFINED→SHADER_READ_ONLY 转过去、之后每帧无屏障维持）——读回屏障从真实
        // 布局出发（oldLayout=UNDEFINED 语义上允许弃内容，不能用）；读后停在
        // TRANSFER_SRC，后续帧图内屏障照常从 UNDEFINED 兜起（无条件转换合法）
        CommandBuffer cmd = device.allocateCommandBuffer(false);
        cmd.begin();
        cmd.pipelineBarrier(cubemap, TextureLayout.SHADER_READ_ONLY, TextureLayout.TRANSFER_SRC);
        cmd.copyImageToBuffer(cubemap, staging, TextureRegion.cubeFaces(FACE_SIZE));
        cmd.end();
        Fence fence = device.createFence();
        device.graphicsQueue().submit(cmd, SubmitInfo.of(fence));
        fence.await();
        fence.dispose();
        cmd.dispose();
        staging.read(0, faceBytes * 6, result);
        staging.dispose();
        result.flip();

        // 比对：每面取四角+中心 5 个采样点（避开面边缘可能的双线性混色）
        boolean allMatch = true;
        for (int face = 0; face < 6; face++) {
            float[] base = FACE_COLORS[face];
            for (int[] p : new int[][]{{0, 0}, {FACE_SIZE - 1, 0}, {0, FACE_SIZE - 1},
                    {FACE_SIZE - 1, FACE_SIZE - 1}, {FACE_SIZE / 2, FACE_SIZE / 2}}) {
                int idx = face * faceBytes + (p[1] * FACE_SIZE + p[0]) * 4;
                float t = (p[0] + p[1]) / (float) (2 * (FACE_SIZE - 1));
                // 渐变生成用 byte 截断，期望值对齐同一量化：比对按期望 byte 值
                int expR = (int) (byte) (clamp01(base[0] + t) * 255) & 0xFF;
                int expG = (int) (byte) (clamp01(base[1] + t) * 255) & 0xFF;
                int expB = (int) (byte) (clamp01(base[2] + t) * 255) & 0xFF;
                int gotR = result.get(idx) & 0xFF;
                int gotG = result.get(idx + 1) & 0xFF;
                int gotB = result.get(idx + 2) & 0xFF;
                if (gotR != expR || gotG != expG || gotB != expB) {
                    allMatch = false;
                    log.error("face[{}] ({},{}) 期望 ({},{},{}) 实得 ({},{},{})",
                            face, p[0], p[1], expR, expG, expB,
                            gotR, gotG, gotB);
                }
            }
        }
        if (allMatch) {
            log.info("cubemap 六面读回比对通过（{}×{} 六面 × 5 采样点）", FACE_SIZE, FACE_SIZE);
        } else {
            log.error("cubemap 六面读回比对失败——分层上传/读回链路断（错误显性化，红线 15）");
        }
    }

    /** 绕 Y 旋转矩阵（列主序写入 push 缓冲） */
    private void putRotationY(float a) {
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        for (int i = 0; i < 16; i++) {
            rotationMatrix[i] = 0.0f;
        }
        rotationMatrix[0] = c;   rotationMatrix[2] = -s;
        rotationMatrix[5] = 1.0f;
        rotationMatrix[8] = s;   rotationMatrix[10] = c;
        rotationMatrix[15] = 1.0f;
        for (float v : rotationMatrix) {
            pushData.putFloat(v);
        }
        pushData.flip();
    }

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    private GraphicsPipeline createPipeline() {
        byte[] vsSpirv = compileShader(VS_SRC, cn.donting.javelin.tool.shaderc.ShaderStage.VERTEX, "cubemap.vert");
        byte[] fsSpirv = compileShader(FS_SRC, cn.donting.javelin.tool.shaderc.ShaderStage.FRAGMENT, "cubemap.frag");
        log.info("shaders compiled: vs={}B, fs={}B SPIR-V", vsSpirv.length, fsSpirv.length);

        Format backbufferFormat = frameLoop.swapChain().image(0).desc().format();

        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, vsSpirv),
                new ShaderDesc(ShaderStage.FRAGMENT, fsSpirv),
                null,   // 无顶点缓冲：全屏三角形 vs 内联
                PrimitiveTopology.TRIANGLE_LIST,
                CullMode.NONE,
                FrontFace.COUNTER_CLOCKWISE,
                PolygonMode.FILL,
                1.0f,
                false, false, CompareOp.ALWAYS,
                false,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                        ColorComponentFlags.B, ColorComponentFlags.A),
                List.of(backbufferFormat), null,
                List.of(setLayoutDesc()), PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1);
        return device.createGraphicsPipeline(desc);
    }

    /** set0：fs samplerCube 组合采样器 */
    private static DescriptorSetLayoutDesc setLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }
}

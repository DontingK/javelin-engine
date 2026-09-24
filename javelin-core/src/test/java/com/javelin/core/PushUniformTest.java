package com.javelin.core;

import com.javelin.rhi.Buffer;
import com.javelin.rhi.DescriptorSet;
import com.javelin.rhi.DescriptorSetLayout;
import com.javelin.rhi.DescriptorUpdate;
import com.javelin.rhi.GraphicsPipeline;
import com.javelin.rhi.PassDef;
import com.javelin.rhi.Sampler;
import com.javelin.rhi.Texture;
import com.javelin.rhi.UploadData;
import com.javelin.rhi.VertexLayout;
import com.javelin.rhi.data.RHIClearColor;
import com.javelin.rhi.data.RHIScissor;
import com.javelin.rhi.data.RHIViewport;
import com.javelin.rhi.desc.BufferDesc;
import com.javelin.rhi.desc.DescriptorSetLayoutDesc;
import com.javelin.rhi.desc.GraphicsPipelineDesc;
import com.javelin.rhi.desc.MemoryPreference;
import com.javelin.rhi.desc.SamplerDesc;
import com.javelin.rhi.desc.ShaderDesc;
import com.javelin.rhi.desc.TextureDesc;
import com.javelin.rhi.exception.RHIException;
import com.javelin.rhi.types.BlendFactor;
import com.javelin.rhi.types.BlendOp;
import com.javelin.rhi.types.BufferUsage;
import com.javelin.rhi.types.ColorComponentFlags;
import com.javelin.rhi.types.CompareOp;
import com.javelin.rhi.types.CullMode;
import com.javelin.rhi.types.DescriptorType;
import com.javelin.rhi.types.DynamicState;
import com.javelin.rhi.types.Format;
import com.javelin.rhi.types.FrontFace;
import com.javelin.rhi.types.PolygonMode;
import com.javelin.rhi.types.PrimitiveTopology;
import com.javelin.rhi.types.ShaderStage;
import com.javelin.rhi.types.TextureUsage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 引擎 uniform 架构验证：UBO1（相机，set0 动态偏移帧环形）+ push constants（model/tpf）
 * + 材质贴图（set1 组合采样器），两个绕 Y 轴旋转的贴图四边形。
 *
 * <p>用户拍板的通道划分（2026-09-24）：push constants 传 model、tpf 等每物件/每帧高频小量；
 * UBO1 传所有材质共享的引擎内置属性（本测试=相机投影矩阵）；UBO2 传各材质自有参数（材质阶段再验）。
 * set 编号说明：设计文档 15.3 三级划分中材质=set2，但 push 替代 set1 后 set1 出现空档——
 * Vulkan 管线布局不允许 set 断档，故本测试材质贴图挂 set1，set 编号是否重排待材质阶段拍板。
 *
 * <p>本测试首次引入投影矩阵，按用户拍板 Y 翻转入投影（m[5] 取负）、视口恢复正高度，
 * frontFace=CCW 结论不变（翻转镜像等价，见 TriangleTest 类注释）。
 *
 * <p>UBO1 帧环形：单块 hostVisible 缓冲分 2 槽（= FrameLoop maxFramesInFlight），
 * 每帧写当前槽（槽号 = frameCounter % 2，与 FrameState 环形同奇偶，槽位复用前有 fence 闸门），
 * 绑定时用动态偏移切换——2~3 帧 in-flight 下不会撕裂。
 *
 * <p>运行：gradlew :javelin-core:runTest -DmainClass=com.javelin.core.PushUniformTest
 * 验收：两个贴图四边形左右分布、持续绕 Y 轴旋转（速度不同），ESC 退出，resize 不崩溃。
 */
public final class PushUniformTest extends RhiTestBase {

    private static final String VS_SRC = """
            #version 450
            layout(set = 0, binding = 0) uniform CameraBlock { mat4 viewProj; } cam;
            layout(push_constant) uniform PushBlock {
                mat4 model;
                float tpf;
            } pc;
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 0) out vec2 vUV;
            void main() {
                gl_Position = cam.viewProj * pc.model * vec4(aPos, 0.0, 1.0);
                vUV = aUV;
            }
            """;

    private static final String FS_SRC = """
            #version 450
            layout(set = 1, binding = 0) uniform sampler2D uTexture;
            layout(location = 0) in vec2 vUV;
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = texture(uTexture, vUV);
            }
            """;

    /** 单位四边形（Y 向上，CCW 绕序）：position(vec2) + uv(vec2)，stride = 16 字节 */
    private static final float[] VERTICES = {
            // 三角形 1：左上 → 左下 → 右下
            -0.6f,  0.6f,   0.0f, 0.0f,
            -0.6f, -0.6f,   0.0f, 1.0f,
             0.6f, -0.6f,   1.0f, 1.0f,
            // 三角形 2：左上 → 右下 → 右上
            -0.6f,  0.6f,   0.0f, 0.0f,
             0.6f, -0.6f,   1.0f, 1.0f,
             0.6f,  0.6f,   1.0f, 0.0f,
    };

    /** UBO1 帧环形槽数（= FrameLoop maxFramesInFlight）与槽大小（256B 对齐） */
    private static final int FRAME_SLOTS = 2;
    private static final int SLOT_SIZE = 256;

    /** push constants 声明范围：mat4(64B) + float(4B) = 68，按 4 对齐取 80 */
    private static final int PUSH_SIZE = 80;
    private static final int PUSH_TPF_OFFSET = 64;

    private static final float NEAR = 0.1f;
    private static final float FAR = 100.0f;
    private static final float FOV_Y = (float) Math.toRadians(60.0);

    /** 两个旋转物体：x 偏移 / z 深度 / 角速度（弧度每秒）/ 累积角 */
    private static final float[][] OBJECTS = {
            {-0.9f, 0.0f, -2.5f, 1.0f, 0.0f},
            { 0.9f, 0.0f, -3.5f, 1.6f, 0.0f},
    };

    private Texture texture;
    private Sampler sampler;
    private Buffer cameraBuffer;
    private DescriptorSetLayout frameSetLayout;
    private DescriptorSet frameSet;
    private DescriptorSetLayout materialSetLayout;
    private DescriptorSet materialSet;
    private GraphicsPipeline pipeline;
    private Buffer vertexBuffer;

    /** 每帧复用的 push constants 打包缓冲（小端，80B：mat4 + float） */
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);

    private long lastFrameNanos;
    private float tpf;

    public static void main(String[] args) {
        new PushUniformTest().run();
    }

    @Override
    protected String testName() {
        return "PushUniformTest";
    }

    @Override
    protected RHIClearColor clearColor() {
        return new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);
    }

    @Override
    protected void onInit() {
        texture = createTextureFromImage();
        sampler = device.createSampler(SamplerDesc.linear());

        // UBO1（set0，帧级共享）：DYNAMIC_UNIFORM_BUFFER，2 槽环形，绑定用动态偏移切槽
        cameraBuffer = device.createBuffer(
                new BufferDesc(FRAME_SLOTS * SLOT_SIZE,
                        Set.of(BufferUsage.UNIFORM), MemoryPreference.hostVisibleCoherent()),
                null);
        frameSetLayout = device.createDescriptorSetLayout(new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.UNIFORM_BUFFER_DYNAMIC, 1,
                        ShaderStage.VERTEX))));
        frameSet = device.createDescriptorSet(frameSetLayout);
        frameSet.update(DescriptorUpdate.dynamicUniformBuffer(0, cameraBuffer, 0, SLOT_SIZE));

        // 材质贴图（set1）：组合采样器——UBO2 材质参数属材质阶段，本测试只验贴图通道
        materialSetLayout = device.createDescriptorSetLayout(new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT))));
        materialSet = device.createDescriptorSet(materialSetLayout);
        materialSet.update(DescriptorUpdate.sampledTexture(0, texture, sampler));

        pipeline = createPipeline();
        vertexBuffer = createVertexBuffer();
    }

    @Override
    protected void onPass(PassDef pass) {
        pass.addTextureRead(texture);
        int w = window.width();
        int h = window.height();
        // 投影矩阵已接管 Y 翻转：视口恢复正高度
        pass.setViewport(new RHIViewport(0, 0, w, h, 0.0f, 1.0f));
        pass.setScissor(new RHIScissor(0, 0, w, h));
        pass.setPipeline(pipeline);

        // 帧级数据：槽位推进（与 FrameState 环形同奇偶，复用槽前有 fence 闸门）+ tpf 推进
        long now = System.nanoTime();
        tpf = lastFrameNanos == 0L ? 0.0f : (now - lastFrameNanos) / 1_000_000_000.0f;
        lastFrameNanos = now;
        int slot = (int) (frameLoop.frameCounter() % FRAME_SLOTS);
        float[] viewProj = perspective(FOV_Y, (float) w / h, NEAR, FAR);
        cameraBuffer.writeAligned(slot * SLOT_SIZE, FloatBuffer.wrap(viewProj));
        int frameOffset = slot * SLOT_SIZE;

        pass.setRenderCallback(cmd -> {
            cmd.bindDescriptorSet(0, frameSet, 1, new int[]{frameOffset});
            cmd.bindDescriptorSet(1, materialSet, 0, null);
            cmd.bindVertexBuffer(vertexBuffer, 0);
            for (float[] obj : OBJECTS) {
                pushData.clear();
                putMat4(pushData, modelMatrix(obj));
                pushData.putFloat(PUSH_TPF_OFFSET, tpf);
                pushData.limit(PUSH_TPF_OFFSET + Float.BYTES);
                pushData.position(0);
                cmd.pushConstants(0, pushData);
                cmd.draw(6, 1);
            }
        });
    }

    @Override
    protected void onDispose() {
        if (frameSet != null) {
            frameSet.dispose();
        }
        if (frameSetLayout != null) {
            frameSetLayout.dispose();
        }
        if (materialSet != null) {
            materialSet.dispose();
        }
        if (materialSetLayout != null) {
            materialSetLayout.dispose();
        }
        if (cameraBuffer != null) {
            cameraBuffer.dispose();
        }
        if (sampler != null) {
            sampler.dispose();
        }
        if (texture != null) {
            texture.dispose();
        }
        if (vertexBuffer != null) {
            vertexBuffer.dispose();
        }
        if (pipeline != null) {
            pipeline.dispose();
        }
    }

    private float[] modelMatrix(float[] obj) {
        obj[4] += obj[3] * tpf;
        float angle = obj[4];
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        // 列主序：T * R_y——先在原地绕自身 Y 轴旋转，再平移到摆放位
        float[] rotY = {
                c, 0, -s, 0,
                0, 1, 0, 0,
                s, 0, c, 0,
                0, 0, 0, 1,
        };
        return multiply(translation(obj[0], obj[1], obj[2]), rotY);
    }

    private static void putMat4(ByteBuffer buf, float[] m) {
        for (float v : m) {
            buf.putFloat(v);
        }
    }

    /** 透视投影（列主序，Vulkan NDC：z∈[0,1]、Y 向下）；m[5] 取负 = Y 翻转入投影 */
    private static float[] perspective(float fovY, float aspect, float near, float far) {
        float f = 1.0f / (float) Math.tan(fovY / 2.0f);
        float[] m = new float[16];
        m[0] = f / aspect;
        m[5] = -f;
        m[10] = far / (near - far);
        m[11] = -1.0f;
        m[14] = near * far / (near - far);
        return m;
    }

    private static float[] translation(float x, float y, float z) {
        float[] m = new float[16];
        m[0] = 1.0f;
        m[5] = 1.0f;
        m[10] = 1.0f;
        m[12] = x;
        m[13] = y;
        m[14] = z;
        m[15] = 1.0f;
        return m;
    }

    /** 列主序 a * b */
    private static float[] multiply(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[c * 4 + k];
                }
                r[c * 4 + row] = sum;
            }
        }
        return r;
    }

    private GraphicsPipeline createPipeline() {
        byte[] vsSpirv = compileShader(VS_SRC, com.javelin.tool.shaderc.ShaderStage.VERTEX, "push_uniform.vert");
        byte[] fsSpirv = compileShader(FS_SRC, com.javelin.tool.shaderc.ShaderStage.FRAGMENT, "push_uniform.frag");
        log.info("shaders compiled: vs={}B, fs={}B SPIR-V", vsSpirv.length, fsSpirv.length);

        VertexLayout vertexLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32_SFLOAT, 0),
                new VertexLayout.Attribute(1, Format.R32G32_SFLOAT, 8)), 16);

        Format backbufferFormat = frameLoop.swapChain().image(0).desc().format();
        log.info("creating PSO: backbufferFormat={}", backbufferFormat);

        DescriptorSetLayoutDesc frameLayoutDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.UNIFORM_BUFFER_DYNAMIC, 1,
                        ShaderStage.VERTEX)));
        DescriptorSetLayoutDesc materialLayoutDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));

        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, vsSpirv),
                new ShaderDesc(ShaderStage.FRAGMENT, fsSpirv),
                vertexLayout,
                PrimitiveTopology.TRIANGLE_LIST,
                CullMode.BACK,
                FrontFace.COUNTER_CLOCKWISE,
                PolygonMode.FILL,
                1.0f,
                false, false, CompareOp.LESS,
                false,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                        ColorComponentFlags.B, ColorComponentFlags.A),
                List.of(backbufferFormat), null,
                List.of(frameLayoutDesc, materialLayoutDesc), PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR));
        return device.createGraphicsPipeline(desc);
    }

    private Buffer createVertexBuffer() {
        ByteBuffer data = ByteBuffer.allocateDirect(VERTICES.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : VERTICES) {
            data.putFloat(v);
        }
        data.flip();
        BufferDesc desc = new BufferDesc(data.remaining(),
                Set.of(BufferUsage.VERTEX), MemoryPreference.hostVisibleCoherent());
        return device.createBuffer(desc, UploadData.of(data));
    }

    /** ImageIO 解码 JPEG → 紧密打包 RGBA → R8G8B8A8_UNORM 纹理（staging 上传） */
    private Texture createTextureFromImage() {
        Path path = resolveImagePath();
        log.info("loading texture image: {}", path.toAbsolutePath());

        BufferedImage image;
        try {
            image = ImageIO.read(path.toFile());
        } catch (IOException e) {
            throw new RHIException("读取图片失败: " + path, e);
        }
        if (image == null) {
            throw new RHIException("无法解码图片（ImageIO 无匹配解码器）: " + path);
        }

        int width = image.getWidth();
        int height = image.getHeight();
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                pixels.put((byte) (argb >> 16));
                pixels.put((byte) (argb >> 8));
                pixels.put((byte) argb);
                pixels.put((byte) 0xFF);
            }
        }
        pixels.flip();
        log.info("image decoded: {}x{}, {} bytes RGBA", width, height, pixels.remaining());

        TextureDesc desc = TextureDesc.texture2D(Format.R8G8B8A8_UNORM, width, height,
                Set.of(TextureUsage.TRANSFER_DST, TextureUsage.SAMPLED));
        return device.createTexture(desc, UploadData.of(pixels));
    }

    /** -Dtexture.path 优先；否则从工作目录逐级向上找 resources/texture.jpeg */
    private static Path resolveImagePath() {
        String override = System.getProperty("texture.path");
        if (override != null && !override.isEmpty()) {
            return Path.of(override);
        }
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("resources/texture.jpeg");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new RHIException("未找到 resources/texture.jpeg（可用 -Dtexture.path= 指定绝对路径）");
    }
}

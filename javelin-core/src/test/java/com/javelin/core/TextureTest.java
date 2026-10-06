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
import com.javelin.rhi.types.SampleCount;
import com.javelin.rhi.types.ShaderStage;
import com.javelin.rhi.types.TextureUsage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 纹理采样验证：JPEG 解码 → R8G8B8A8 纹理上传 → 描述符集绑定 → 四边形采样渲染。
 *
 * <p>覆盖链路：ImageIO 解码（JDK 内置，无新依赖）→ createTexture + staging 上传（25 节）
 * → DescriptorSetLayout / DescriptorSet / Sampler（15 节，set0 binding0 组合采样器）
 * → PSO 挂描述符集布局 → RenderGraph addTextureRead 屏障（SHADER_READ_ONLY）
 * → 回调内 bindDescriptorSet 绘制。图片默认取 resources/texture.jpeg。
 *
 * <p>Y 翻转约定同 {@link TriangleTest}：暂无投影矩阵，用负高度视口手动翻转；
 * UV 按 v=0 在图片顶部书写，翻转后图片正立显示。
 *
 * <p>运行：gradlew :javelin-core:runTest -DmainClass=com.javelin.core.TextureTest
 * （runTest 工作目录为 javelin-core/，本类逐级向上查找 resources/texture.jpeg；
 *   可用 -Dtexture.path=绝对路径 指定）
 * 验收：窗口中央显示该 JPEG 图片，ESC 退出，拖拽 resize 画面跟随且不崩溃。
 */
public final class TextureTest extends RhiTestBase {

    private static final String VS_SRC = """
            #version 450
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 0) out vec2 vUV;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUV = aUV;
            }
            """;

    private static final String FS_SRC = """
            #version 450
            layout(set = 0, binding = 0) uniform sampler2D uTexture;
            layout(location = 0) in vec2 vUV;
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = texture(uTexture, vUV);
            }
            """;

    /** 中央矩形四边形（Y 向上，CCW 绕序）：position(vec2) + uv(vec2)，stride = 16 字节 */
    private static final float[] VERTICES = {
            // 三角形 1：左上 → 左下 → 右下
            -0.8f,  0.8f,   0.0f, 0.0f,
            -0.8f, -0.8f,   0.0f, 1.0f,
             0.8f, -0.8f,   1.0f, 1.0f,
            // 三角形 2：左上 → 右下 → 右上
            -0.8f,  0.8f,   0.0f, 0.0f,
             0.8f, -0.8f,   1.0f, 1.0f,
             0.8f,  0.8f,   1.0f, 0.0f,
    };

    private Texture texture;
    private Sampler sampler;
    private DescriptorSetLayout descriptorSetLayout;
    private DescriptorSet descriptorSet;
    private GraphicsPipeline pipeline;
    private Buffer vertexBuffer;

    public static void main(String[] args) {
        new TextureTest().run();
    }

    @Override
    protected String testName() {
        return "TextureTest";
    }

    @Override
    protected RHIClearColor clearColor() {
        return new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);
    }

    @Override
    protected void onInit() {
        texture = createTextureFromImage();
        sampler = device.createSampler(SamplerDesc.linear());

        descriptorSetLayout = device.createDescriptorSetLayout(new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT))));
        descriptorSet = device.createDescriptorSet(descriptorSetLayout);
        descriptorSet.update(DescriptorUpdate.sampledTexture(0, texture, sampler));

        pipeline = createPipeline();
        vertexBuffer = createVertexBuffer();
    }

    @Override
    protected void onPass(PassDef pass) {
        // 采样读声明：执行器插入 SHADER_READ_ONLY 屏障（22.3 ResourceUsage 语义）
        pass.addTextureRead(texture);
        int w = window.width();
        int h = window.height();
        // 手动 Y 翻转：负高度视口。接入投影矩阵后删除（同 TriangleTest 约定）
        pass.setViewport(new RHIViewport(0, h, w, -h, 0.0f, 1.0f));
        pass.setScissor(new RHIScissor(0, 0, w, h));
        pass.setPipeline(pipeline);
        pass.setRenderCallback(cmd -> {
            cmd.bindDescriptorSet(0, descriptorSet, 0, null);
            cmd.bindVertexBuffer(vertexBuffer, 0);
            cmd.draw(6, 1);
        });
    }

    @Override
    protected void onDispose() {
        if (descriptorSet != null) {
            descriptorSet.dispose();
        }
        if (descriptorSetLayout != null) {
            descriptorSetLayout.dispose();
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

    /** ImageIO 解码 JPEG → 紧密打包 RGBA 字节 → R8G8B8A8_UNORM 纹理（staging 上传） */
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
        // JPEG 无 alpha：getRGB 得 ARGB int，展开为 RGBA 字节并补 alpha=255
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

    private GraphicsPipeline createPipeline() {
        byte[] vsSpirv = compileShader(VS_SRC, com.javelin.tool.shaderc.ShaderStage.VERTEX, "texture.vert");
        byte[] fsSpirv = compileShader(FS_SRC, com.javelin.tool.shaderc.ShaderStage.FRAGMENT, "texture.frag");
        log.info("shaders compiled: vs={}B, fs={}B SPIR-V", vsSpirv.length, fsSpirv.length);

        VertexLayout vertexLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32_SFLOAT, 0),
                new VertexLayout.Attribute(1, Format.R32G32_SFLOAT, 8)), 16);

        Format backbufferFormat = frameLoop.swapChain().image(0).desc().format();
        log.info("creating PSO: backbufferFormat={}", backbufferFormat);

        // set0：片元着色器组合采样器（与 FS_SRC 的 set=0/binding=0 对应）
        DescriptorSetLayoutDesc setLayoutDesc = new DescriptorSetLayoutDesc(List.of(
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
                List.of(setLayoutDesc), 0,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1);
        return device.createGraphicsPipeline(desc);
    }

    private Buffer createVertexBuffer() {
        // GPU 期望小端字节序；ByteBuffer 默认大端，必须显式设置（否则顶点数据字节错位）
        ByteBuffer data = ByteBuffer.allocateDirect(VERTICES.length * Float.BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float v : VERTICES) {
            data.putFloat(v);
        }
        data.flip();
        BufferDesc desc = new BufferDesc(data.remaining(),
                Set.of(BufferUsage.VERTEX), MemoryPreference.hostVisibleCoherent());
        return device.createBuffer(desc, UploadData.of(data));
    }
}

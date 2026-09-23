package com.javelin.core;

import com.javelin.rhi.Buffer;
import com.javelin.rhi.GraphicsPipeline;
import com.javelin.rhi.PassDef;
import com.javelin.rhi.UploadData;
import com.javelin.rhi.VertexLayout;
import com.javelin.rhi.data.RHIClearColor;
import com.javelin.rhi.data.RHIScissor;
import com.javelin.rhi.data.RHIViewport;
import com.javelin.rhi.desc.BufferDesc;
import com.javelin.rhi.desc.GraphicsPipelineDesc;
import com.javelin.rhi.desc.MemoryPreference;
import com.javelin.rhi.desc.ShaderDesc;
import com.javelin.rhi.types.BlendFactor;
import com.javelin.rhi.types.BlendOp;
import com.javelin.rhi.types.BufferUsage;
import com.javelin.rhi.types.ColorComponentFlags;
import com.javelin.rhi.types.CompareOp;
import com.javelin.rhi.types.CullMode;
import com.javelin.rhi.types.DynamicState;
import com.javelin.rhi.types.Format;
import com.javelin.rhi.types.FrontFace;
import com.javelin.rhi.types.PolygonMode;
import com.javelin.rhi.types.PrimitiveTopology;
import com.javelin.rhi.types.ShaderStage;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;

/**
 * 阶段⑥三角形验证：SPIR-V 着色器 + PSO + 顶点缓冲 + RenderGraph 声明绘制。
 *
 * <p>着色器来源：GLSL 源码内嵌本类，运行时经 javelin-tool 的 ShaderCompiler 编译为 SPIR-V。
 *
 * <p>Y 翻转约定（实测结论，2026-09-23）：本测试还没有投影矩阵（顶点直接写 clip 空间），
 * 暂用负高度视口翻转，与投影矩阵翻转对绕序的影响等价；接入相机后翻转移入投影矩阵，
 * 视口恢复正高度。PSO 配 frontFace=CCW + cullMode=BACK，顶点按 CCW 资产惯例书写（Y 向上）——
 * 翻转后 CCW 资产在 framebuffer 为 CCW 绕序（RHI_Design_Core.md 161-168 节，原"翻转+CW"配对已修正）。
 *
 * 运行：gradlew :javelin-core:runTest -DmainClass=com.javelin.core.TriangleTest
 * 验收：窗口中央顶点朝上的红绿蓝渐变三角形，ESC 退出，拖拽 resize 画面跟随且不崩溃。
 */
public final class TriangleTest extends RhiTestBase {

    private static final String VS_SRC = """
            #version 450
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aColor;
            layout(location = 0) out vec3 vColor;
            void main() {
                gl_Position = vec4(aPos, 1.0);
                vColor = aColor;
            }
            """;

    private static final String FS_SRC = """
            #version 450
            layout(location = 0) in vec3 vColor;
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = vec4(vColor, 1.0);
            }
            """;

    /** CCW（Y 向上）三角形：position(vec3) + color(vec3)，stride = 24 字节 */
    private static final float[] VERTICES = {
            -0.5f, -0.5f, 0.0f,   1.0f, 0.0f, 0.0f,
             0.5f, -0.5f, 0.0f,   0.0f, 1.0f, 0.0f,
             0.0f,  0.5f, 0.0f,   0.0f, 0.0f, 1.0f,
    };

    private GraphicsPipeline pipeline;
    private Buffer vertexBuffer;

    public static void main(String[] args) {
        new TriangleTest().run();
    }

    @Override
    protected String testName() {
        return "TriangleTest";
    }

    @Override
    protected RHIClearColor clearColor() {
        return new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);
    }

    @Override
    protected void onInit() {
        pipeline = createPipeline();
        vertexBuffer = createVertexBuffer();
    }

    @Override
    protected void onPass(PassDef pass) {
        int w = window.width();
        int h = window.height();
        // 手动 Y 翻转：负高度视口。接入投影矩阵后删除（见类注释）
        pass.setViewport(new RHIViewport(0, h, w, -h, 0.0f, 1.0f));
        pass.setScissor(new RHIScissor(0, 0, w, h));
        pass.setPipeline(pipeline);
        pass.setRenderCallback(cmd -> {
            cmd.bindVertexBuffer(vertexBuffer, 0);
            cmd.draw(3, 1);
        });
    }

    @Override
    protected void onDispose() {
        if (vertexBuffer != null) {
            vertexBuffer.dispose();
        }
        if (pipeline != null) {
            pipeline.dispose();
        }
    }

    private GraphicsPipeline createPipeline() {
        byte[] vsSpirv = compileShader(VS_SRC, com.javelin.tool.shaderc.ShaderStage.VERTEX, "triangle.vert");
        byte[] fsSpirv = compileShader(FS_SRC, com.javelin.tool.shaderc.ShaderStage.FRAGMENT, "triangle.frag");
        log.info("shaders compiled: vs={}B, fs={}B SPIR-V", vsSpirv.length, fsSpirv.length);

        VertexLayout vertexLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32B32_SFLOAT, 0),
                new VertexLayout.Attribute(1, Format.R32G32B32_SFLOAT, 12)), 24);

        Format backbufferFormat = frameLoop.swapChain().image(0).desc().format();
        log.info("creating PSO: backbufferFormat={}", backbufferFormat);

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
                List.of(), 0,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR));
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

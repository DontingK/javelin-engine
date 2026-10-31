package cn.donting.javelin.core;

import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.ComputePipeline;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.GPUResource;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHIScissor;
import cn.donting.javelin.rhi.data.RHIViewport;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.ComputePipelineDesc;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.desc.GraphicsPipelineDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.ShaderDesc;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 三期批次① SSBO + compute 全环验证（Engine_Lighting_Design §9 批次①验收口，
 * RhiTestBase 家族——真实 GPU 跑图，肉眼画面 + 数据读回双验收）。
 *
 * <p>双段 compute 链：段 A 写 bufferA → 段 B 读 A 写 bufferB（验证 compute→compute
 * 的 buffer RAW 同步——依赖边 + VkBufferMemoryBarrier 由 RenderGraph 按声明生成）→
 * 图形 Pass 全屏三角形 fs 读 bufferB 写颜色附件（验证 compute→图形跨 Pass 同步）。
 * 画面 = 灰度渐变条带；帧 60 起读回 bufferB 与 CPU 期望值逐元素比对（确定性图案，
 * 数据错即屏障/链路断——错误显性化，红线 15）。</p>
 *
 * <p>着色器 = test resources 源码（拍板 2026-10-31：流程验证件不进编辑器、test 读源码
 * 调 tool 编译）：.compute 两份经 tool shaderc COMPUTE 编译（导入器链路由 editor 侧
 * ComputeShaderImporterTest 覆盖，core 不依赖 editor）；图形 vs/fs 直读文本编译。
 * 纯 RHI 层自建布局，不经引擎 set1 材质链。</p>
 *
 * <p>运行：gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.ComputeSsboTest
 * 验收：全屏灰度渐变条带稳定显示 + 控制台「SSBO 读回比对通过」+ ESC 退出不崩。</p>
 */
public final class ComputeSsboTest extends RhiTestBase {

    /** SSBO 元素数（.compute 侧数组定长一致：16384 组 × 64 线程 × 4 元素/线程） */
    private static final int ELEMENT_COUNT = 65536;
    private static final int BUFFER_BYTES = ELEMENT_COUNT * 16;
    /** dispatch 组数（local_size = 64，每线程 4 元素） */
    private static final int GROUPS = ELEMENT_COUNT / 64 / 4;
    /** 读回比对帧（帧 60 时 FrameLoop 槽 fence 已多次 await，compute 写入对 CPU 可见） */
    private static final int VERIFY_FRAME = 60;

    private Buffer bufferA;
    private Buffer bufferB;
    private DescriptorSet computeSetA;
    private DescriptorSet computeSetB;
    private ComputePipeline pipelineA;
    private ComputePipeline pipelineB;
    private GraphicsPipeline quadPipeline;
    private DescriptorSet fragSet;

    /** 读回暂存（小端 float 视图） */
    private final ByteBuffer readback = ByteBuffer.allocateDirect(BUFFER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);

    public static void main(String[] args) {
        new ComputeSsboTest().run();
    }

    @Override
    protected String testName() {
        return "ComputeSsboTest";
    }

    @Override
    protected RHIClearColor clearColor() {
        return new RHIClearColor(0.02f, 0.02f, 0.05f, 1.0f);
    }

    /** compute 布局：set0 binding0/1 SSBO（A 段 binding0 写 / B 段 binding0 读 + binding1 写） */
    private static DescriptorSetLayoutDesc computeLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.STORAGE_BUFFER, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.STORAGE_BUFFER, 1,
                        ShaderStage.COMPUTE)));
    }

    /** 图形布局：set0 binding0 SSBO（fs 读 bufferB） */
    private static DescriptorSetLayoutDesc fragLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.STORAGE_BUFFER, 1,
                        ShaderStage.FRAGMENT)));
    }

    @Override
    protected void onInit() {
        // SSBO：STORAGE + hostVisibleCoherent（读回走 vkMapMemory，须 HOST_VISIBLE；
        // GPU 写经 fence await 后对 CPU 可见）
        log.info("[init] 创建 SSBO bufferA/bufferB（{} KB）", BUFFER_BYTES / 1024);
        bufferA = device.createBuffer(
                new BufferDesc(BUFFER_BYTES, Set.of(BufferUsage.STORAGE),
                        MemoryPreference.hostVisibleCoherent()), null);
        bufferB = device.createBuffer(
                new BufferDesc(BUFFER_BYTES, Set.of(BufferUsage.STORAGE),
                        MemoryPreference.hostVisibleCoherent()), null);

        DescriptorSetLayoutDesc computeLayout = computeLayoutDesc();
        log.info("[init] 创建 compute 管线 A（含布局装配+vkCreateComputePipelines）");
        pipelineA = device.createComputePipeline(new ComputePipelineDesc(
                new ShaderDesc(ShaderStage.COMPUTE,
                        compileShaderResource("compute/ssbo-write-a.compute", cn.donting.javelin.tool.shaderc.ShaderStage.COMPUTE)),
                List.of(computeLayout), 0));
        log.info("[init] 创建 compute 管线 B");
        pipelineB = device.createComputePipeline(new ComputePipelineDesc(
                new ShaderDesc(ShaderStage.COMPUTE,
                        compileShaderResource("compute/ssbo-write-b.compute", cn.donting.javelin.tool.shaderc.ShaderStage.COMPUTE)),
                List.of(computeLayout), 0));

        log.info("[init] 创建 compute 描述符集（storageBuffer 更新）");
        computeSetA = device.createDescriptorSet(device.createDescriptorSetLayout(computeLayout));
        computeSetA.update(DescriptorUpdate.storageBuffer(0, bufferA));
        computeSetB = device.createDescriptorSet(device.createDescriptorSetLayout(computeLayout));
        computeSetB.update(
                DescriptorUpdate.storageBuffer(0, bufferA),
                DescriptorUpdate.storageBuffer(1, bufferB));

        DescriptorSetLayoutDesc fragLayout = fragLayoutDesc();
        log.info("[init] 创建图形描述符集");
        fragSet = device.createDescriptorSet(device.createDescriptorSetLayout(fragLayout));
        fragSet.update(DescriptorUpdate.storageBuffer(0, bufferB));

        log.info("[init] 创建图形管线（全屏三角形，无顶点输入）");
        quadPipeline = device.createGraphicsPipeline(new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX,
                        compileShaderResource("shader/ssbo-read.vert", cn.donting.javelin.tool.shaderc.ShaderStage.VERTEX)),
                new ShaderDesc(ShaderStage.FRAGMENT,
                        compileShaderResource("shader/ssbo-read.frag", cn.donting.javelin.tool.shaderc.ShaderStage.FRAGMENT)),
                null,   // 无顶点缓冲：vs 以 gl_VertexIndex 内联全屏三角形
                PrimitiveTopology.TRIANGLE_LIST,
                CullMode.NONE, FrontFace.COUNTER_CLOCKWISE, PolygonMode.FILL, 1.0f,
                false, false, CompareOp.ALWAYS,
                false,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                null,
                List.of(frameLoop.swapChain().image(0).desc().format()), Format.D32_SFLOAT,
                List.of(fragLayout), 0,
                // 动态 viewport/scissor 必须声明：图执行器每 Pass 恒调 cmd.setViewport/setScissor，
                // 非动态下调用属 VUID 违规（validation 缺席时驱动 UB）
                Set.of(cn.donting.javelin.rhi.types.DynamicState.VIEWPORT,
                        cn.donting.javelin.rhi.types.DynamicState.SCISSOR),
                SampleCount.COUNT_1));

        log.info("ComputeSsboTest 初始化完成: 双 SSBO {} 元素, dispatch {} 组", ELEMENT_COUNT, GROUPS);
    }

    @Override
    protected void buildPasses(RenderGraphBuilder graph, Texture backbuffer) {
        // 段 A：写 bufferA（首帧首用，无帧内先行者不产屏障）
        graph.addComputePass("SsboComputeA", pass -> {
            pass.setPipeline(pipelineA);
            pass.addBufferWrite(bufferA);
            pass.setRenderCallback(cmd -> {
                cmd.bindDescriptorSet(0, computeSetA, 0, null);
                cmd.dispatch(GROUPS, 1, 1);
            });
        });
        // 段 B：读 bufferA 写 bufferB（A→B 依赖边 + 内存屏障由此生成）
        graph.addComputePass("SsboComputeB", pass -> {
            pass.setPipeline(pipelineB);
            pass.addBufferRead(bufferA);
            pass.addBufferWrite(bufferB);
            pass.setRenderCallback(cmd -> {
                cmd.bindDescriptorSet(0, computeSetB, 0, null);
                cmd.dispatch(GROUPS, 1, 1);
            });
        });
        // 图形段：全屏三角形 fs 读 bufferB（B→图形依赖边 + 内存屏障）
        graph.addGraphicsPass(testName(), pass -> {
            pass.addColorOutput(backbuffer, RHILoadOp.CLEAR, clearColor());
            pass.setPipeline(quadPipeline);
            pass.setViewport(new RHIViewport(0, 0, window.width(), window.height(), 0.0f, 1.0f));
            pass.setScissor(new RHIScissor(0, 0, window.width(), window.height()));
            pass.setRenderCallback(cmd -> {
                cmd.bindDescriptorSet(0, fragSet, 0, null);
                cmd.draw(3, 1);
            });
        });

        if (frameLoop.frameCounter() == VERIFY_FRAME) {
            verifyReadback();
        }
    }

    /**
     * 读回 bufferB 与 CPU 期望值逐元素比对（段 A 图案 → 段 B 灰度映射，公式与 .compute 侧镜像）。
     * 图案 = 纯整数位运算/小整数除法：两侧同为 IEEE 基本运算，差异 ≤ 数 ULP（≈1e-7），
     * 容差 1e-4 之内的不一致只能是链路/屏障问题（超越函数图案协和 CPU 与 GPU 之于
     * 大自变量区间归约有实现差，首版 sin 图案 4931 个超容差——排障实录见 Progress 档）。
     */
    private void verifyReadback() {
        readback.clear();
        bufferB.read(0, BUFFER_BYTES, readback);
        readback.flip();
        int mismatches = 0;
        float maxDiff = 0.0f;
        for (int i = 0; i < ELEMENT_COUNT; i++) {
            float r = (i & 63) / 64.0f;            // 低 6 位：竖向细条纹
            float g = ((i >> 6) & 255) / 255.0f;   // 中 8 位：横向梯度
            float b = ((i >> 14) & 3) / 4.0f;      // 高 2 位：象限块
            float expected = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            float got = readback.getFloat(i * 16);
            float diff = Math.abs(expected - got);
            if (diff > maxDiff) {
                maxDiff = diff;
            }
            if (diff > 1e-4f) {
                if (mismatches < 5) {
                    log.error("SSBO 读回不一致: i={} expected={} got={}", i, expected, got);
                }
                mismatches++;
            }
        }
        if (mismatches == 0) {
            log.info("SSBO 读回比对通过：{} 元素全一致（compute→compute→读回 全链正确，maxDiff={}）",
                    ELEMENT_COUNT, maxDiff);
        } else {
            throw new IllegalStateException("SSBO 读回比对失败: " + mismatches + "/" + ELEMENT_COUNT
                    + " 元素不一致, maxDiff=" + maxDiff + "（数据链或屏障断）");
        }
    }

    @Override
    protected void onDispose() {
        // RhiTestBase 逆序骨架：frameLoop.close() 已 waitIdle，显式 dispose 安全
        dispose(fragSet);
        dispose(quadPipeline);
        dispose(computeSetA);
        dispose(computeSetB);
        dispose(pipelineA);
        dispose(pipelineB);
        dispose(bufferA);
        dispose(bufferB);
    }

    private static void dispose(GPUResource r) {
        if (r != null) {
            r.dispose();
        }
    }
}

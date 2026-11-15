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
 * 链尾锐化 Pass（Engine_TAA_Design §5.3 / P4-11）：LDR 中间 RT → RCAS → 显示目标。
 *
 * <p>位置照 Babylon `DefaultRenderingPipeline`（imageProcessing 之后、FXAA 之前）：本 Pass 恒为
 * 终节点，接在 Tonemap 之后做 LDR 空间锐化。锐化是抗锯齿的配套（Unity HDRP 口径）：任一相机
 * 启用 TAA 才挂链，本视口未启用时强度 0 = 直出；全部相机都关时它不在链上，链尾自动回落
 * Tonemap 直出（展开期按「链内最后一个节点写 colorTarget」判定，不依赖注册顺序）。</p>
 *
 * <p><b>清屏职责接管</b>：终节点恒写显示终点（colorTarget），承担链尾的 CLEAR + isRegionClear
 * + scissor 语义（Tonemap 降为普通节点后不再写显示目标）。</p>
 *
 * <p><b>描述符</b>：set0 为本 Pass 专属单纹理布局（binding0 组合采样器，链输入）；push 常量
 * vec4 params（x = 锐化强度，按视口取相机级设置；相机未启用抗锯齿时 0 = 直出）。</p>
 */
public class SharpenPass implements PostProcessNode {

    private static final Logger log = LoggerFactory.getLogger(SharpenPass.class);

    /** 内置锐化着色器 loadAsset 路径（builtin-asset-core 释放后的相对路径） */
    private static final String VERT_SHADER_PATH = "core/postprocess/sharpen.vert";
    private static final String FRAG_SHADER_PATH = "core/postprocess/sharpen.frag";

    /** push 常量：vec4 params（x = 强度），16B */
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
    /** 覆盖制 PSO 缓存：key = 显示目标格式 */
    private final Map<Format, GraphicsPipeline> pipelines = new HashMap<>();
    /** 每视口链输入描述符集（input 纹理随 resize 换代，按视口缓存身份） */
    private final Map<String, InputSlot> inputSets = new HashMap<>();
    private RHIClearColor clearColor = new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);
    /** 每帧复用的 push 打包缓冲（录制串行，回调内填后即推） */
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    /** 锐化强度（本视口；相机未启用抗锯齿时 0 = 直出） */
    private boolean drawLogged;

    /**
     * @param device       RHI 设备（PSO/VB/描述符集创建）
     * @param assetManager 资产管理器（装载内置 sharpen.vert/.frag 的 SpvShader 产物）
     */
    public SharpenPass(Device device, AssetManager assetManager) {
        this.device = device;
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH, "sharpen.vert");
        this.fragSpv = loadSpv(assetManager, FRAG_SHADER_PATH, "sharpen.frag");
        this.triangleLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32_SFLOAT, 0)), 8);
        this.vertexBuffer = createVertexBuffer();
        // 锐化按整像素 texelFetch 取邻域（1:1），故 NEAREST + CLAMP 与采样点一一对应
        this.inputSampler = device.createSampler(new SamplerDesc(
                Filter.NEAREST, Filter.NEAREST, SamplerMipmapMode.NEAREST,
                SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE,
                SamplerAddressMode.CLAMP_TO_EDGE, 1.0f, 1.0f));
    }

    /** 清屏色（管线全局默认色；相机自带 clearColor 优先） */
    public void setClearColor(RHIClearColor clearColor) {
        this.clearColor = clearColor;
    }

    /** push 块：vec4 params（x = strength），16B */
    private void pushParams(CommandBuffer cmd, float strength) {
        pushData.clear();
        pushData.putFloat(strength);
        pushData.putFloat(0.0f);
        pushData.putFloat(0.0f);
        pushData.putFloat(0.0f);
        pushData.position(0);
        cmd.pushConstants(0, pushData);
    }

    @Override
    public String name() {
        return "Sharpen";
    }

    /** 身份标识（覆盖制不查材质 shader 的 Pass） */
    @Override
    public String shaderPassName() {
        return "Sharpen";
    }

    /** 终节点：锐化恒为链尾（Tonemap 之前的所有节点须读未压高光的 HDR/LDR 缓冲） */
    @Override
    public boolean isTerminal() {
        return true;
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx, Texture input, Texture output) {
        RenderView view = ctx.view();
        // 强度按视口取（相机级设置；相机未启用抗锯齿 → 0 = 直出）
        float strength = ctx.renderingSystem().sharpenStrengthFor(view.name());
        RHIClearColor clear = view.clearColor() != null ? view.clearColor() : clearColor;
        Format colorFormat = output.desc().format();
        boolean regionClear = AbstractForwardPass.isRegionClear(view, output);
        graph.addGraphicsPass(view.name() + "_Sharpen", pass -> {
            // 恒清屏：本 Pass 是显示目标的唯一写者（stacking/区域清屏语义同原 Tonemap）
            if (regionClear) {
                pass.addColorOutput(output, RHILoadOp.CLEAR, clear, view.scissor());
            } else {
                pass.addColorOutput(output, RHILoadOp.CLEAR, clear);
            }
            // 链输入读声明：建色 Pass → 锐化依赖边 + 布局转 SHADER_READ_ONLY
            pass.addTextureRead(input);
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            pass.setRenderCallback(cmd -> {
                if (!drawLogged) {
                    drawLogged = true;
                    log.info("首帧 Sharpen 回调执行: 视口[{}] outputFormat={} strength={}",
                            view.name(), colorFormat, strength);
                }
                cmd.bindPipeline(pipelineFor(colorFormat));
                pushParams(cmd, strength);
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

    /** 链输入描述符集：纹理身份不变复用，换代（resize）重建 */
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
                // set0 = 本 Pass 专属输入采样布局；push = vec4 params（强度）
                List.of(inputLayoutDesc()), PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(colorFormat, created);
        log.info("sharpen PSO created: colorFormat={}", colorFormat);
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

package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.render.ClearFlags;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
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
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.FrontFace;
import cn.donting.javelin.rhi.types.PolygonMode;
import cn.donting.javelin.rhi.types.PrimitiveTopology;
import cn.donting.javelin.rhi.types.SampleCount;
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
 * 视口天空盒 Pass（Engine_Skybox_Design §4，批次⑤步骤二——管线 3.5 位：
 * Opaque 后 / Transparent 前）。
 *
 * <p><b>覆盖制</b>（ShadowPass 同款先例）：不消费材质 shader 的 Pass 声明，着色器
 * 走资产导入链（内置 {@code builtin-asset-core/skybox/skybox.vert/.frag} 经
 * SpvShaderImporter 编译，本 Pass 经 {@link AssetManager#loadAsset} 按路径装载）。
 * 单位立方体内嵌 36 顶点 VB（拍板：不走 box.smesh 资产链）；view 去平移
 * （mat4(mat3(cam.view))，立方体恒以相机为中心）；z = w 推远平面 + ZTest LEqual +
 * ZWrite Off（拍板：免调深度上限、不污染深度缓冲——被不透明几何遮挡部分由深度测试
 * 挡掉，透明物体画在天空盒之后）。</p>
 *
 * <p><b>挂载判定</b>：本视口 ClearFlags.SKYBOX 且 RenderingSystem 挂有 skybox 才
 * addToGraph（无 skybox 时不挂——Opaque 正常清屏，正常域空状态不弹错，红线 15
 * 边界）。v0.x 边界：SKYBOX 视口 MSAA 降级 1x（isMsaaPath 判 SOLID_COLOR，
 * 与 stacking/预览/分屏降级同口径）。MSAA 与 SkyboxPass 正交接线归后置。</p>
 *
 * <p><b>描述符</b>：set0 复用引擎帧布局（CameraBlock UBO 动态偏移，与材质 PSO 的
 * set0 同一 DescriptorSetLayoutDesc——绑定兼容）；set1 为本 Pass 专属 cubemap 采样
 * 布局（binding0），skybox 纹理换代即重建（纹理句柄进 structureHash 经
 * addTextureRead 失效图缓存）。零 push。</p>
 */
public class SkyboxPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(SkyboxPass.class);

    /** 内置天空盒着色器 loadAsset 路径（builtin-asset-core 释放后的相对路径） */
    private static final String VERT_SHADER_PATH = "core/skybox/skybox.vert";
    private static final String FRAG_SHADER_PATH = "core/skybox/skybox.frag";

    /** 单位立方体 36 顶点（12 三角形，position vec3、stride 12B；CullMode.NONE 绕序无关） */
    private static final float[] CUBE_VERTICES = {
            // +X
            1.0f, -1.0f, -1.0f,   1.0f, 1.0f, -1.0f,   1.0f, 1.0f, 1.0f,
            1.0f, -1.0f, -1.0f,   1.0f, 1.0f, 1.0f,    1.0f, -1.0f, 1.0f,
            // -X
            -1.0f, -1.0f, -1.0f,  -1.0f, -1.0f, 1.0f,  -1.0f, 1.0f, 1.0f,
            -1.0f, -1.0f, -1.0f,  -1.0f, 1.0f, 1.0f,   -1.0f, 1.0f, -1.0f,
            // +Y
            -1.0f, 1.0f, -1.0f,   -1.0f, 1.0f, 1.0f,   1.0f, 1.0f, 1.0f,
            -1.0f, 1.0f, -1.0f,   1.0f, 1.0f, 1.0f,    1.0f, 1.0f, -1.0f,
            // -Y
            -1.0f, -1.0f, -1.0f,  1.0f, -1.0f, -1.0f,  1.0f, -1.0f, 1.0f,
            -1.0f, -1.0f, -1.0f,  1.0f, -1.0f, 1.0f,   -1.0f, -1.0f, 1.0f,
            // +Z
            -1.0f, -1.0f, 1.0f,   1.0f, -1.0f, 1.0f,   1.0f, 1.0f, 1.0f,
            -1.0f, -1.0f, 1.0f,   1.0f, 1.0f, 1.0f,    -1.0f, 1.0f, 1.0f,
            // -Z
            -1.0f, -1.0f, -1.0f,  -1.0f, 1.0f, -1.0f,  1.0f, 1.0f, -1.0f,
            -1.0f, -1.0f, -1.0f,  1.0f, 1.0f, -1.0f,   1.0f, -1.0f, -1.0f,
    };

    private final Device device;
    private final byte[] vertSpv;
    private final byte[] fragSpv;
    private final Buffer vertexBuffer;
    private final VertexLayout cubeLayout;
    private final Sampler skyboxSampler;
    /** 覆盖制 PSO 缓存：key = 颜色附件格式（换帧缓冲格式即新建；附件恒 1x——SKYBOX 视口 MSAA 降级） */
    private final Map<Format, GraphicsPipeline> pipelines = new HashMap<>();
    /** skybox 采样描述符集（纹理换代重建） */
    private DescriptorSetLayout skyboxSetLayout;
    private DescriptorSet skyboxSet;
    private Texture boundSkybox;
    private boolean drawLogged;

    /**
     * @param device       RHI 设备（PSO/VB/描述符集创建）
     * @param assetManager 资产管理器（装载内置 skybox.vert/.frag 的 SpvShader 产物）
     */
    public SkyboxPass(Device device, AssetManager assetManager) {
        this.device = device;
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH, "skybox.vert");
        this.fragSpv = loadSpv(assetManager, FRAG_SHADER_PATH, "skybox.frag");
        this.cubeLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32B32_SFLOAT, 0)), 12);
        this.vertexBuffer = createVertexBuffer();
        // CLAMP_TO_EDGE 寻址（cubeLinear）：REPEAT 会卷绕面边缘 texel 毁掉跨面插值，
        // 天空盒 12 条棱显形（2026-11-06 实测排障）
        this.skyboxSampler = device.createSampler(SamplerDesc.cubeLinear());
    }

    @Override
    public String name() {
        return "Skybox";
    }

    /** 身份标识（覆盖制不查材质 shader 的 Pass；与用户 shader 自定义 Pass 名无消费关系） */
    @Override
    public String shaderPassName() {
        return "Skybox";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        RenderView view = ctx.view();
        RenderingSystem rs = ctx.renderingSystem();
        Texture skybox = rs.skybox();
        // 挂载判定：本视口 SKYBOX 标志 + 场景挂有 skybox（无 skybox 时不挂——Opaque
        // 正常清屏兜住背景，图结构随挂载态变化 = structureHash 正常失效）
        if (view.clearFlags() != ClearFlags.SKYBOX || skybox == null) {
            return;
        }
        // 依赖声明：skybox 纹理句柄进 structureHash（纹理换代失效图缓存）+ SHADER_READ_ONLY 屏障
        graph.addGraphicsPass(view.name() + "_Skybox", pass -> {
            pass.addColorOutput(ctx.colorTarget(), RHILoadOp.LOAD, null);
            pass.addDepthOutput(ctx.depth(), RHILoadOp.LOAD, null);
            pass.addTextureRead(skybox);
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            pass.setRenderCallback(cmd -> {
                if (!drawLogged) {
                    drawLogged = true;
                    log.info("首帧 Skybox 回调执行: 视口[{}] skybox={}",
                            view.name(), skybox.desc());
                }
                cmd.bindPipeline(pipelineFor(rs, ctx.colorTarget().desc().format()));
                // set0 相机块（帧布局同材质 PSO——动态偏移选本视口相机）
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{view.uboOffset()});
                // set1 skybox cubemap（本 Pass 专属布局，纹理换代重建）
                cmd.bindDescriptorSet(1, skyboxSetFor(skybox), 0, null);
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(36, 1);
            });
        });
    }

    /** 释放自建资源（须 device waitIdle 后；VB/PSO/采样器/描述符集） */
    public void close() {
        pipelines.values().forEach(GraphicsPipeline::dispose);
        pipelines.clear();
        if (skyboxSet != null) {
            skyboxSet.dispose();
        }
        if (skyboxSetLayout != null) {
            skyboxSetLayout.dispose();
        }
        skyboxSampler.dispose();
        vertexBuffer.dispose();
    }

    /** skybox 采样描述符集：纹理身份不变复用，换代重建（旧集显式释放） */
    private DescriptorSet skyboxSetFor(Texture skybox) {
        if (skyboxSet != null && boundSkybox == skybox) {
            return skyboxSet;
        }
        if (skyboxSetLayout == null) {
            skyboxSetLayout = device.createDescriptorSetLayout(skyboxLayoutDesc());
        }
        if (skyboxSet != null) {
            skyboxSet.dispose();
        }
        skyboxSet = device.createDescriptorSet(skyboxSetLayout);
        skyboxSet.update(DescriptorUpdate.sampledTexture(0, skybox, skyboxSampler));
        boundSkybox = skybox;
        return skyboxSet;
    }

    /** set1 skybox 专属布局：binding0 组合采样器（fs） */
    private static DescriptorSetLayoutDesc skyboxLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }

    private GraphicsPipeline pipelineFor(RenderingSystem rs, Format colorFormat) {
        GraphicsPipeline cached = pipelines.get(colorFormat);
        if (cached != null) {
            return cached;
        }
        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, vertSpv),
                new ShaderDesc(ShaderStage.FRAGMENT, fragSpv),
                cubeLayout,
                PrimitiveTopology.TRIANGLE_LIST,
                CullMode.NONE,                    // 由立方体内部观察：双面都要画
                FrontFace.COUNTER_CLOCKWISE,
                PolygonMode.FILL,
                1.0f,
                true, false, CompareOp.LESS_OR_EQUAL,     // 深度测试 ON（LEqual：z=w=1.0 过清屏深度 1.0）、ZWrite Off 不污染深度
                false,                                    // blend Off：不透明整块覆盖
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                        ColorComponentFlags.B, ColorComponentFlags.A),
                // 深度格式必须声明（渲染通道带深度附件、LEqual 靠它；漏声明 = 深度态被忽略）
                List.of(colorFormat), Format.D32_SFLOAT,
                // set0 = 复用引擎帧布局契约 rs.frameSetLayoutDesc()（与材质/拾取/光照 PSO 逐字段
                // 同构；天空盒只用 vs 读相机块，但**布局兼容要求相同而非子集**——自建 VERTEX 版
                // 会与本 Pass 绑定的 ALL_GRAPHICS 描述符集不兼容，VVL 00358/08600）；set1 = skybox 专属
                List.of(rs.frameSetLayoutDesc(), skyboxLayoutDesc()), 0,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(colorFormat, created);
        log.info("skybox PSO created: colorFormat={}", colorFormat);
        return created;
    }

    private Buffer createVertexBuffer() {
        // GPU 期望小端（顶点数据字节序约定同 TextureTest）
        ByteBuffer data = ByteBuffer.allocateDirect(CUBE_VERTICES.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : CUBE_VERTICES) {
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
            throw new IllegalStateException("天空盒着色器资产未找到: " + path
                    + "（内置资产应随引擎启动释放进 .javelin/assets 并经导入链编译——"
                    + "检查 builtin-asset-core/skybox/" + label + " 与伴生 .meta）");
        }
        if (!spv.isValid()) {
            throw new IllegalStateException("天空盒着色器为坏产物（编译失败 stub）: " + path
                    + "——请 reimport 修复");
        }
        return spv.getSpvData();
    }
}

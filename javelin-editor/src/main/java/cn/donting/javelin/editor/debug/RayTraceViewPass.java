package cn.donting.javelin.editor.debug;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.render.RenderCamera;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.compute.ComputeShader;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.core.render.rt.RayTracingScene;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.ComputePipeline;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Sampler;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.Tlas;
import cn.donting.javelin.rhi.UploadData;
import cn.donting.javelin.rhi.VertexLayout;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.desc.ComputePipelineDesc;
import cn.donting.javelin.rhi.desc.BufferDesc;
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
import cn.donting.javelin.rhi.types.TextureUsage;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 光追求交直显 Pass（P4-06 B3，Engine_RayTracing_Plan §2 B3）。
 *
 * <p><b>两段</b>：①compute 半分辨率每像素发一条相机主射线，ray query 对 TLAS 求最近命中，
 * 把「命中距离 / farZ」（miss = -1）写进 r32f 存储图；②全屏大三角形把该图铺回视口，
 * 近蓝远橙渐变、miss 深蓝。目的是先验证几何与射线一致（偏移/自交/漏检），不是最终消费形态。</p>
 *
 * <p><b>每帧顺序</b>：compute 回调里先 {@code RayTracingScene.build}（TLAS 重建）再绑描述符
 * 派发——实例缓冲单份不双缓冲，依赖「上一帧 fence 已等」的时序约定（见方案档 §3.3）。</p>
 *
 * <p><b>后置</b>：主光方向求交（需 G-Buffer 世界位置，与 shadow map 逐像素对照）、替换/混合
 * CSM 的决策、半分辨率上采样优化。</p>
 */
public class RayTraceViewPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(RayTraceViewPass.class);

    private static final String VERT_SHADER_PATH = "editor/debug/gbufferview.vert";
    private static final String FRAG_SHADER_PATH = "editor/debug/raytraceview.frag";
    private static final String COMPUTE_SHADER_PATH = "editor/debug/raytrace.compute";

    /** compute push：mat4 invViewProj + vec4 camPos + vec4 params + vec4 lightDir（112B） */
    private static final int COMPUTE_PUSH_BYTES = 16 * Float.BYTES + 3 * 4 * Float.BYTES;
    /** 视图 push：ivec2 targetSize（8B） */
    private static final int VIEW_PUSH_BYTES = Integer.BYTES * 2;
    private static final int WORKGROUP = 8;

    /** 射线来源模式（与着色器常量一致） */
    public static final int MODE_CAMERA = 1;
    public static final int MODE_LIGHT = 2;

    private static final float[] TRIANGLE_VERTICES = {
            -1.0f, -1.0f,
            3.0f, -1.0f,
            -1.0f, 3.0f,
    };

    private final Device device;
    private final RenderingSystem renderingSystem;
    private final String targetView;
    private final byte[] vertSpv;
    private final byte[] fragSpv;
    private final byte[] computeSpv;
    private final Buffer vertexBuffer;
    private final VertexLayout triangleLayout;
    private final DescriptorSetLayoutDesc computeSetDesc;
    private final DescriptorSetLayoutDesc viewSetDesc;
    private DescriptorSetLayout computeSetLayout;
    private DescriptorSet computeSet;
    private Texture computeSetTexture;
    private Texture computeSetWorldPos;
    private Tlas boundTlas;
    private DescriptorSetLayout viewSetLayout;
    private DescriptorSet viewSet;
    private Texture viewSetTexture;
    private Sampler nearestSampler;
    private ComputePipeline computePipeline;
    private Texture distanceTexture;
    private final Map<Format, GraphicsPipeline> pipelines = new HashMap<>();
    private final ByteBuffer computePush = ByteBuffer.allocateDirect(COMPUTE_PUSH_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer viewPush = ByteBuffer.allocateDirect(VIEW_PUSH_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    private volatile boolean enabled;
    /** 当前射线来源：1 = 相机主射线 / 2 = 主光方向（G-Buffer 世界位置起点） */
    private volatile int mode = MODE_CAMERA;

    public RayTraceViewPass(Device device, AssetManager assetManager,
                            RenderingSystem renderingSystem, String targetView) {
        this.device = device;
        this.renderingSystem = renderingSystem;
        this.targetView = targetView;
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH);
        this.fragSpv = loadSpv(assetManager, FRAG_SHADER_PATH);
        this.computeSpv = loadComputeSpv(assetManager, COMPUTE_SHADER_PATH);
        this.triangleLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32_SFLOAT, 0)), 8);
        this.vertexBuffer = createVertexBuffer();
        this.computeSetDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.ACCELERATION_STRUCTURE, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.STORAGE_IMAGE, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(2, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.COMPUTE)));
        this.viewSetDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 射线来源：1 = 相机主射线 / 2 = 主光方向 */
    public void setMode(int mode) {
        this.mode = (mode == MODE_LIGHT) ? MODE_LIGHT : MODE_CAMERA;
    }

    @Override
    public String name() {
        return "RayTraceView";
    }

    @Override
    public String shaderPassName() {
        return "RayTraceView";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        if (!enabled) {
            return;
        }
        RenderView view = ctx.view();
        if (!targetView.equals(view.name())) {
            return;
        }
        RayTracingScene scene = renderingSystem.rayTracingScene();
        RenderCamera camera = findCamera(view.name());
        if (scene == null || camera == null) {
            return;  // 无光追能力 / 相机未就绪：AppState 侧写提示行
        }
        boolean lightMode = mode == MODE_LIGHT;
        Vector3f light = lightMode ? renderingSystem.sunDirectionOrNull() : null;
        if (lightMode && light == null) {
            return;  // 无主光：AppState 侧写提示行
        }
        final Vector3f rayDir = light == null ? null : new Vector3f(light).negate().normalize();
        final Texture worldPosTexture = lightMode
                ? renderingSystem.gbufferWorldPosFor(view.name()) : null;
        final Sampler worldPosTextureSampler = lightMode ? renderingSystem.gbufferSampler() : null;
        if (lightMode && (worldPosTexture == null || worldPosTextureSampler == null)) {
            return;  // FORWARD 档没有 G-Buffer：模式 2 不可用
        }
        int width = Math.max(1, (int) view.viewport().width() / 2);
        int height = Math.max(1, (int) view.viewport().height() / 2);
        ensureDistanceTexture(width, height);
        Texture distance = distanceTexture;
        Texture target = ctx.colorTarget();
        Format colorFormat = target.desc().format();

        Matrix4f invViewProj = new Matrix4f(camera.viewProj()).invert();
        Vector3f position = new Vector3f(camera.position());
        float maxDistance = Math.max(1.0f, camera.farZ());
        float bias = lightMode ? 0.02f : 0f;
        List<cn.donting.javelin.core.render.RenderObject> renderers = renderingSystem.renderers();

        graph.addComputePass(view.name() + "_RayTrace", pass -> {
            pass.setPipeline(ensureComputePipeline());
            pass.addTextureStorageWrite(distance);
            if (worldPosTexture != null) {
                pass.addTextureRead(worldPosTexture);
            }
            pass.setRenderCallback(cmd -> {
                scene.build(cmd, renderers);
                cmd.bindPipeline(computePipeline);
                cmd.bindDescriptorSet(0,
                        computeSetFor(scene, distance, worldPosTexture, worldPosTextureSampler), 0, null);
                computePush.clear();
                putMatrix(computePush, invViewProj);
                computePush.putFloat(position.x).putFloat(position.y).putFloat(position.z).putFloat(1.0f);
                computePush.putFloat(maxDistance).putFloat((float) mode).putFloat(bias).putFloat(0f);
                if (rayDir != null) {
                    computePush.putFloat(rayDir.x).putFloat(rayDir.y).putFloat(rayDir.z).putFloat(0f);
                } else {
                    computePush.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
                }
                computePush.flip();
                cmd.pushConstants(0, computePush);
                cmd.dispatch((width + WORKGROUP - 1) / WORKGROUP,
                        (height + WORKGROUP - 1) / WORKGROUP, 1);
            });
        });

        graph.addGraphicsPass(view.name() + "_RayTraceView", pass -> {
            pass.addColorOutput(target, RHILoadOp.LOAD, null);
            pass.addTextureRead(distance);
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            pass.setRenderCallback(cmd -> {
                cmd.bindPipeline(pipelineFor(colorFormat));
                cmd.bindDescriptorSet(0, viewSetFor(distance), 0, null);
                viewPush.clear();
                viewPush.putInt((int) view.viewport().width());
                viewPush.putInt((int) view.viewport().height());
                viewPush.flip();
                cmd.pushConstants(0, viewPush);
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    private RenderCamera findCamera(String name) {
        for (RenderCamera camera : renderingSystem.cameras()) {
            if (name.equals(camera.name())) {
                return camera;
            }
        }
        return null;
    }

    /** 半分辨率距离图：尺寸变即换代（旧图交给 RenderingSystem 延迟释放） */
    private void ensureDistanceTexture(int width, int height) {
        if (distanceTexture != null
                && distanceTexture.desc().width() == width
                && distanceTexture.desc().height() == height) {
            return;
        }
        if (distanceTexture != null) {
            Texture old = distanceTexture;
            renderingSystem.retireResource(old::dispose);
        }
        distanceTexture = device.createTexture(TextureDesc.texture2D(Format.R32_SFLOAT, width, height,
                Set.of(TextureUsage.STORAGE, TextureUsage.SAMPLED)), null);
        if (computeSet != null) {
            computeSet.dispose();
            computeSet = null;
        }
        if (viewSet != null) {
            viewSet.dispose();
            viewSet = null;
        }
        log.info("光追求交距离图: {}x{}", width, height);
    }

    private ComputePipeline ensureComputePipeline() {
        if (computePipeline == null) {
            computePipeline = device.createComputePipeline(new ComputePipelineDesc(
                    new ShaderDesc(ShaderStage.COMPUTE, computeSpv),
                    List.of(computeSetDesc), COMPUTE_PUSH_BYTES));
        }
        return computePipeline;
    }

    /**
     * compute 集（binding0 加速结构 / binding1 距离图 / binding2 世界位置）。
     *
     * <p>模式 1 的世界位置绑定依旧要合法（着色器只声明不采样），故传占位：无 G-Buffer 时
     * 用距离图自身充数——它同时具备 STORAGE + SAMPLED，格式差异不会触发校验错误。</p>
     */
    private DescriptorSet computeSetFor(RayTracingScene scene, Texture distance,
                                        Texture worldPos, Sampler worldPosSampler) {
        Tlas tlas = scene.tlas();
        Texture boundWorldPos = worldPos != null ? worldPos : distance;
        if (computeSet != null && computeSetTexture == distance
                && computeSetWorldPos == boundWorldPos && boundTlas == tlas) {
            return computeSet;
        }
        if (computeSetLayout == null) {
            computeSetLayout = device.createDescriptorSetLayout(computeSetDesc);
        }
        if (computeSet == null || computeSetTexture != distance) {
            if (computeSet != null) {
                computeSet.dispose();
            }
            computeSet = device.createDescriptorSet(computeSetLayout);
        }
        Sampler sampler = worldPosSampler != null
                ? worldPosSampler : nearestSampler();
        computeSet.update(DescriptorUpdate.accelerationStructure(0, tlas),
                DescriptorUpdate.storageTexture(1, distance),
                DescriptorUpdate.sampledTexture(2, boundWorldPos, sampler));
        computeSetTexture = distance;
        computeSetWorldPos = boundWorldPos;
        boundTlas = tlas;
        return computeSet;
    }

    private Sampler nearestSampler() {
        if (nearestSampler == null) {
            nearestSampler = device.createSampler(SamplerDesc.nearest());
        }
        return nearestSampler;
    }

    private DescriptorSet viewSetFor(Texture distance) {
        if (viewSet != null && viewSetTexture == distance) {
            return viewSet;
        }
        if (viewSetLayout == null) {
            viewSetLayout = device.createDescriptorSetLayout(viewSetDesc);
        }
        if (viewSet != null) {
            viewSet.dispose();
        }
        if (nearestSampler == null) {
            nearestSampler = device.createSampler(SamplerDesc.nearest());
        }
        viewSet = device.createDescriptorSet(viewSetLayout);
        viewSet.update(DescriptorUpdate.sampledTexture(0, distance, nearestSampler));
        viewSetTexture = distance;
        return viewSet;
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
                CullMode.NONE,
                FrontFace.COUNTER_CLOCKWISE,
                PolygonMode.FILL,
                1.0f,
                false, false, CompareOp.LESS,
                false,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                        ColorComponentFlags.B, ColorComponentFlags.A),
                List.of(colorFormat), null,
                List.of(viewSetDesc), VIEW_PUSH_BYTES,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(colorFormat, created);
        log.info("光追求交视图 PSO created: colorFormat={}", colorFormat);
        return created;
    }

    private static void putMatrix(ByteBuffer out, Matrix4fc m) {
        out.putFloat(m.m00()).putFloat(m.m01()).putFloat(m.m02()).putFloat(m.m03());
        out.putFloat(m.m10()).putFloat(m.m11()).putFloat(m.m12()).putFloat(m.m13());
        out.putFloat(m.m20()).putFloat(m.m21()).putFloat(m.m22()).putFloat(m.m23());
        out.putFloat(m.m30()).putFloat(m.m31()).putFloat(m.m32()).putFloat(m.m33());
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

    private static byte[] loadSpv(AssetManager assetManager, String path) {
        SpvShader spv = assetManager.loadAsset(path, SpvShader.class);
        if (spv == null || !spv.isValid()) {
            throw new IllegalStateException(EditorLang.text("rayTraceView.assetMissing", path));
        }
        return spv.getSpvData();
    }

    private static byte[] loadComputeSpv(AssetManager assetManager, String path) {
        ComputeShader shader = assetManager.loadAsset(path, ComputeShader.class);
        if (shader == null || !shader.isValid()) {
            throw new IllegalStateException(EditorLang.text("rayTraceView.assetMissing", path));
        }
        return shader.getSpirv();
    }
}

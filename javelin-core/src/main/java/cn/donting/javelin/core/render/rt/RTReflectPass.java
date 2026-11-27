package cn.donting.javelin.core.render.rt;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.compute.ComputeShader;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.rhi.ComputePipeline;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.Tlas;
import cn.donting.javelin.rhi.desc.ComputePipelineDesc;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.types.DescriptorType;
import cn.donting.javelin.rhi.types.ShaderStage;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * 光追镜面反射 Pass（P4-06 B9）：半分辨率逐像素沿反射方向打 1 条射线，命中点投影回屏幕取
 * G-Buffer 反照率，用「环境 + 主光 ndl」近似其辐射亮度，写 {@code rtreflectFor(cameraName)}
 * 的 RGBA16F 图；PBR 注入段按 Fresnel 份额把它乘进环境镜面项。
 *
 * <p><b>为什么单条射线就够</b>：反射方向由「相机位置 → 表面位置 → 法线镜像」唯一确定，
 * 不是 GI 那种半球积分。粗糙度只把方向摊成宽 lobe，因此这里用 1 spp + 角度抖动近似，
 * 光滑面（rough ≤ 0.1）的抖动角近乎为零 = 精确镜面；粗糙面靠 B8 时域累积收敛。</p>
 *
 * <p><b>push 144B</b>：viewProj 64 + params/camPos/sunRad/ambientRad/lightDir 各 16 —— 比
 * B5/B7 多的 16B 是相机世界位置（反射方向必须知道眼睛在哪）。Vulkan 保证的下限是 128B，
 * 桌面 GPU 普遍 256B；设备若报 push constant 超限，这里也是唯一需要检查的 Pass。</p>
 *
 * <p><b>时序</b>：与 {@link RTShadowPass} / {@link RTGIPass} 同锚点 after="GBuffer"，三者
 * 互不依赖（都只读 G-Buffer、各写自己的图）；LightingPass 与两个 Forward Pass 通过
 * {@code addTextureRead} 建跨 Pass 依赖。</p>
 */
public final class RTReflectPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(RTReflectPass.class);

    private static final String SHADER = "core/rt/rtreflect.compute";
    private static final String PASS_NAME = "RTReflect";
    /** 降噪用途子键（历史/中转图族内区分 GI 与反射） */
    private static final String DENOISE_USAGE = "reflect";
    /** push：mat4 viewProj(64) + params/camPos/sunRad/ambientRad/lightDir（5×16）= 144B */
    private static final int PUSH_BYTES = 64 + 5 * 4 * Float.BYTES;
    private static final int WORKGROUP = 8;
    /** 起点沿世界法线抬升（世界单位）：防自交（比 GI 略大——反射起点常落在极光滑面） */
    private static final float NORMAL_BIAS = 0.05f;
    /** 粗糙度 lobe 上限（1 = 允许 90° 摊开；下降即让粗糙面更接近镜面，调试用） */
    private static final float LOBE_LIMIT = 1.0f;

    private final Device device;
    private final RenderingSystem renderingSystem;
    private final AssetManager assetManager;
    /** 时域累积 + ATrous 共享降噪链（P4-06 B8；与 RTGIPass 共用同一实例） */
    private final RTDenoiser denoiser;

    private DescriptorSetLayoutDesc setDesc;
    private DescriptorSetLayout setLayout;
    private DescriptorSet set;
    private Tlas boundTlas;
    private Texture boundReflect;
    private Texture boundWorldPos;
    private Texture boundNormal;
    private Texture boundMaterial;
    private Texture boundAlbedo;
    private ComputePipeline pipeline;
    private boolean assetsWarned;
    private boolean emptyTlasWarned;
    private boolean noCameraWarned;
    /** 帧计数器（抖动相位错开） */
    private int frameCounter;
    /** 最近一次出图的视口名（denoise 历史代选择的取键源；每视口 addToGraph 时刷新） */
    private String currentViewName;
    /** 历史代取键缓存（usage+代 → 视口名；ping-pong 读代跨帧稳定） */
    private final java.util.Map<String, String> lastViewNames = new java.util.HashMap<>();
    private final Matrix4f viewProjCopy = new Matrix4f();
    private final float[] viewProjPack = new float[16];
    private final ByteBuffer push = ByteBuffer.allocateDirect(PUSH_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);

    public RTReflectPass(Device device, RenderingSystem renderingSystem, AssetManager assetManager,
                         RTDenoiser denoiser) {
        if (device == null || renderingSystem == null) {
            throw new IllegalArgumentException("device / renderingSystem must not be null");
        }
        this.device = device;
        this.renderingSystem = renderingSystem;
        this.assetManager = assetManager;
        this.denoiser = denoiser;
    }

    @Override
    public String name() {
        return PASS_NAME;
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        // 先按「本帧未出图」记账：空反射图按档位叠上去会直接把表面压黑。派发成功才回填档位。
        renderingSystem.markRtReflectSkipped();
        if (renderingSystem.rtReflectMode() == 0) {
            return;   // 关档：环境镜面整段不进
        }
        RayTracingScene scene = renderingSystem.rayTracingScene();
        if (scene == null) {
            return;
        }
        String viewName = ctx.view().name();
        Matrix4f viewProj = renderingSystem.cameraViewProjFor(viewName);
        Vector3f camPos = renderingSystem.cameraPositionFor(viewName);
        if (viewProj == null || camPos == null) {
            if (!noCameraWarned) {
                noCameraWarned = true;
                log.warn("光追反射: 视口 {} 本帧未收集相机（首帧/无该视口），暂不出图", viewName);
            }
            return;
        }
        if (!ensurePipeline()) {
            return;
        }
        Texture reflect = renderingSystem.rtreflectFor(viewName);
        Texture worldPos = renderingSystem.gbufferWorldPosFor(viewName);
        Texture normal = renderingSystem.gbufferNormalFor(viewName);
        Texture material = renderingSystem.gbufferMaterialFor(viewName);
        Texture albedo = renderingSystem.gbufferAlbedoFor(viewName);
        if (reflect == null || worldPos == null || normal == null || material == null
                || albedo == null) {
            return;
        }
        viewProjCopy.set(viewProj);
        int width = reflect.desc().width();
        int height = reflect.desc().height();
        int frame = frameCounter++;
        currentViewName = viewName;

        Vector3f sun = renderingSystem.sunDirectionOrNull();
        Vector3f sunRadiance = renderingSystem.sunRadiance();
        Vector3f ambient = renderingSystem.ambientRadiance();
        Vector3f toSun = sun == null ? new Vector3f(0.0f, 1.0f, 0.0f)
                : new Vector3f(sun).negate().normalize();
        boolean sunOn = sun != null
                && (sunRadiance.x + sunRadiance.y + sunRadiance.z) > 1e-6f;

        // B8 降噪链（就绪时，与 RTGI 同构）：反射图只作 raw；temporal 写另一代历史，
        // ATrous 两迭代回写本帧反射图（着色端继续只认 rtreflectFor）
        boolean denoise = denoiser != null && denoiser.ready();
        Matrix4f prevViewProj = null;
        boolean historyValid = false;
        if (denoise) {
            prevViewProj = renderingSystem.rtPrevViewProjFor(viewName);
            historyValid = prevViewProj != null
                    && !renderingSystem.rtCameraMoved(viewName)
                    && renderingSystem.rtPrevFrameFor(viewName) == ctx.frame().frameIndex() - 1;
        }

        graph.addComputePass(viewName + ":" + PASS_NAME, pass -> {
            pass.setPipeline(pipeline);
            pass.addTextureRead(worldPos);
            pass.addTextureRead(normal);
            pass.addTextureRead(material);
            pass.addTextureRead(albedo);
            pass.addTextureStorageWrite(reflect);
            pass.setRenderCallback(cmd -> {
                int instances = scene.build(cmd, renderingSystem.renderers());
                if (instances == 0) {
                    if (!emptyTlasWarned) {
                        emptyTlasWarned = true;
                        log.warn("光追反射: TLAS 无实例，本帧不派发（反射图保持上一帧/空——"
                                + "检查网格是否已上传/进 renderers）");
                    }
                    return;
                }
                cmd.bindPipeline(pipeline);
                cmd.bindDescriptorSet(0, setFor(scene, reflect, worldPos, normal, material, albedo),
                        0, null);
                push.clear();
                viewProjCopy.get(viewProjPack);
                for (int i = 0; i < 16; i++) {
                    push.putFloat(viewProjPack[i]);
                }
                push.putFloat(RenderingSystem.RT_REFLECT_MAX_DISTANCE)
                        .putFloat((float) frame)
                        .putFloat(LOBE_LIMIT)
                        .putFloat(NORMAL_BIAS);
                push.putFloat(camPos.x).putFloat(camPos.y).putFloat(camPos.z).putFloat(1.0f);
                push.putFloat(sunRadiance.x).putFloat(sunRadiance.y).putFloat(sunRadiance.z)
                        .putFloat(sunOn ? 1.0f : 0.0f);
                push.putFloat(ambient.x).putFloat(ambient.y).putFloat(ambient.z).putFloat(0.0f);
                push.putFloat(toSun.x).putFloat(toSun.y).putFloat(toSun.z).putFloat(0.0f);
                push.flip();
                cmd.pushConstants(0, push);
                cmd.dispatch((width + WORKGROUP - 1) / WORKGROUP,
                        (height + WORKGROUP - 1) / WORKGROUP, 1);
                renderingSystem.markRtReflectApplied(renderingSystem.rtReflectMode());
            });
        });
        if (!denoise) {
            return;   // 降噪链未就绪（着色器缺失）：反射图即最终图，直出 raw
        }
        Texture histRead = denoiseHistFor(0);
        Texture histWrite = renderingSystem.rtDenoiseHistOtherFor(histRead);
        if (histWrite == null) {
            return;   // ping-pong 另一代缺失（不应发生——创建即两代）：退直出
        }
        denoiser.addTemporalPass(viewName + "#refT", graph,
                viewName + ":" + PASS_NAME + ":Temporal",
                reflect, histRead, worldPos, prevViewProj, historyValid);
        Texture scratch = renderingSystem.rtDenoiseScratchFor(viewName, DENOISE_USAGE);
        denoiser.addAtrousPass(viewName + "#refA1", graph,
                viewName + ":" + PASS_NAME + ":Atrous1",
                histWrite, scratch, worldPos, normal, 1);
        denoiser.addAtrousPass(viewName + "#refA2", graph,
                viewName + ":" + PASS_NAME + ":Atrous2",
                scratch, reflect, worldPos, normal, 2);
    }

    /** 本视口反射降噪历史（ping-pong 代；读代跨帧稳定，写走另一代反查） */
    private Texture denoiseHistFor(int index) {
        return renderingSystem.rtDenoiseHistFor(
                lastViewNames.computeIfAbsent(DENOISE_USAGE + index, n -> currentViewName),
                DENOISE_USAGE, index);
    }

    private boolean ensurePipeline() {
        if (pipeline != null) {
            return true;
        }
        if (assetManager == null) {
            return false;
        }
        ComputeShader shader = assetManager.loadAsset(SHADER, ComputeShader.class);
        if (shader == null || !shader.isValid()) {
            if (!assetsWarned) {
                assetsWarned = true;
                log.error("光追反射着色器缺失或无效: {}（反射档将不出图，环境镜面回落为 0）", SHADER);
            }
            return false;
        }
        setDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.ACCELERATION_STRUCTURE, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.STORAGE_IMAGE, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(2, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(3, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(4, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(5, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.COMPUTE)));
        setLayout = device.createDescriptorSetLayout(setDesc);
        pipeline = device.createComputePipeline(new ComputePipelineDesc(
                new ShaderDesc(ShaderStage.COMPUTE, shader.getSpirv()), List.of(setDesc), PUSH_BYTES));
        log.info("光追反射 Pass 就绪: {}（半分辨率 1 spp + 粗糙度抖动），push {}B", SHADER, PUSH_BYTES);
        return true;
    }

    /** set0：0 加速结构 / 1 反射图（storage） / 2 世界位置 / 3 法线 / 4 材质 / 5 反照率 */
    private DescriptorSet setFor(RayTracingScene scene, Texture reflect, Texture worldPos,
                                 Texture normal, Texture material, Texture albedo) {
        Tlas tlas = scene.tlas();
        if (set != null && boundTlas == tlas && boundReflect == reflect
                && boundWorldPos == worldPos && boundNormal == normal
                && boundMaterial == material && boundAlbedo == albedo) {
            return set;
        }
        if (setLayout == null) {
            setLayout = device.createDescriptorSetLayout(setDesc);
        }
        if (set != null) {
            DescriptorSet old = set;
            renderingSystem.retireResource(old::dispose);
        }
        set = device.createDescriptorSet(setLayout);
        set.update(DescriptorUpdate.accelerationStructure(0, tlas),
                DescriptorUpdate.storageTexture(1, reflect),
                DescriptorUpdate.sampledTexture(2, worldPos, renderingSystem.gbufferSampler()),
                DescriptorUpdate.sampledTexture(3, normal, renderingSystem.gbufferSampler()),
                DescriptorUpdate.sampledTexture(4, material, renderingSystem.gbufferSampler()),
                DescriptorUpdate.sampledTexture(5, albedo, renderingSystem.gbufferSampler()));
        boundTlas = tlas;
        boundReflect = reflect;
        boundWorldPos = worldPos;
        boundNormal = normal;
        boundMaterial = material;
        boundAlbedo = albedo;
        return set;
    }
}

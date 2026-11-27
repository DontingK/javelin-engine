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
 * 光追漫反射 GI Pass（P4-06 B7）：半分辨率逐像素在世界位置上半球余弦采样 N 条射线，
 * 命中后反查屏幕 G-Buffer 取反照率/法线，估算 1-bounce 间接辐照度，写
 * {@code rtgiFor(cameraName)} 的 RGBA16F 图供主渲染 shader 在环境项消费。
 *
 * <p><b>为什么命中点要反查屏幕</b>：ray query 不给命中点材质（没有 hit shader 与 SBT），
 * 只能把命中点投影回本帧屏幕、取该像素 G-Buffer 的反照率与法线；屏外/背面回退常量 0.5。
 * 这是零额外显存的做法，代价是间接光偏灰、完全屏外的入射方向丢失。</p>
 *
 * <p><b>不含二次阴影</b>：命中点是否被主光照亮只用它的法线 ndl 近似（不再发第二条射线），
 * 会有少量漏光——1 spp/px 量级下可接受，B8 接时域累积后估计会更稳。</p>
 *
 * <p><b>时序</b>：必须在 GBuffer 之后（读世界位置/法线/反照率）、Lighting 之前（被采样）；
 * 管线装配点按 after="GBuffer" 插入，与 {@link RTShadowPass} 同锚点、互不依赖。</p>
 */
public final class RTGIPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(RTGIPass.class);

    private static final String SHADER = "core/gi/rtgi.compute";
    private static final String PASS_NAME = "RTGI";
    /** 降噪用途子键（历史/中转图族内区分 GI 与反射） */
    private static final String DENOISE_USAGE = "gi";
    /** push：mat4 viewProj(64) + params + sunRad + ambientRad + lightDir（4×16）= 128B */
    private static final int PUSH_BYTES = 64 + 4 * 4 * Float.BYTES;
    private static final int WORKGROUP = 8;
    /** 起点沿世界法线抬升（世界单位）：防自交 */
    private static final float NORMAL_BIAS = 0.02f;
    /** 每像素半球采样数（半分辨率 4 spp：单帧只保证方向正确，噪点靠 B8 时域累积收敛） */
    private static final int SPP = 4;

    private final Device device;
    private final RenderingSystem renderingSystem;
    private final AssetManager assetManager;
    /** 时域累积 + ATrous 共享降噪链（P4-06 B8；与 RTReflectPass 共用同一实例） */
    private final RTDenoiser denoiser;

    private DescriptorSetLayoutDesc setDesc;
    private DescriptorSetLayout setLayout;
    private DescriptorSet set;
    private Tlas boundTlas;
    private Texture boundGi;
    private Texture boundWorldPos;
    private Texture boundNormal;
    private Texture boundAlbedo;
    private ComputePipeline pipeline;
    private boolean assetsWarned;
    private boolean emptyTlasWarned;
    /** 帧计数器（Hammersley 相位错开；本 Pass 每帧 addToGraph 一次，可用作帧序号） */
    private int frameCounter;
    /** 最近一次出图的视口名（denoise 历史代选择的取键源；每视口 addToGraph 时刷新） */
    private String currentViewName;
    /** 历史代取键缓存（usage+代 → 视口名；ping-pong 读代跨帧稳定） */
    private final java.util.Map<String, String> lastViewNames = new java.util.HashMap<>();
    private final Matrix4f viewProjCopy = new Matrix4f();
    private final float[] viewProjPack = new float[16];
    private final ByteBuffer push = ByteBuffer.allocateDirect(PUSH_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);

    public RTGIPass(Device device, RenderingSystem renderingSystem, AssetManager assetManager,
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
        // 先按「本帧未出图」记账：环境项不能被空 GI 图整个替换掉（空图全 0 → 选什么
        // 环境光模式都黑，实测踩过）。执行段真正派发后才回填档位。
        renderingSystem.markRtgiSkipped();
        if (renderingSystem.rtgiMode() == 0) {
            return;   // 关档：环境项走常量
        }
        RayTracingScene scene = renderingSystem.rayTracingScene();
        if (scene == null) {
            return;
        }
        String viewName = ctx.view().name();
        Matrix4f viewProj = renderingSystem.cameraViewProjFor(viewName);
        if (viewProj == null) {
            return;   // 相机本帧未收集（首帧/无该视口）：没有投影就没法做屏幕反查
        }
        if (!ensurePipeline()) {
            return;
        }
        Texture gi = renderingSystem.rtgiFor(viewName);
        Texture worldPos = renderingSystem.gbufferWorldPosFor(viewName);
        Texture normal = renderingSystem.gbufferNormalFor(viewName);
        Texture albedo = renderingSystem.gbufferAlbedoFor(viewName);
        if (gi == null || worldPos == null || normal == null || albedo == null) {
            return;
        }
        viewProjCopy.set(viewProj);
        int width = gi.desc().width();
        int height = gi.desc().height();
        int frame = frameCounter++;
        currentViewName = viewName;

        Vector3f sun = renderingSystem.sunDirectionOrNull();
        Vector3f sunRadiance = renderingSystem.sunRadiance();
        Vector3f ambient = renderingSystem.ambientRadiance();
        // 指向光源方向（worldDir 是行进方向）
        Vector3f toSun = sun == null ? new Vector3f(0.0f, 1.0f, 0.0f)
                : new Vector3f(sun).negate().normalize();
        boolean sunOn = sun != null
                && (sunRadiance.x + sunRadiance.y + sunRadiance.z) > 1e-6f;

        // B8 降噪链（就绪时）：GI 图只作 raw；temporal 写另一代历史，
        // ATrous 两迭代回写本帧 GI 图（着色端继续只认 rtgiFor）
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
            pass.addTextureRead(albedo);
            pass.addTextureStorageWrite(gi);
            pass.setRenderCallback(cmd -> {
                int instances = scene.build(cmd, renderingSystem.renderers());
                if (instances == 0) {
                    if (!emptyTlasWarned) {
                        emptyTlasWarned = true;
                        log.warn("光追 GI: TLAS 无实例，本帧不派发（环境项仍按回退值，画面偏亮——"
                                + "检查网格是否已上传/进 renderers）");
                    }
                    return;
                }
                cmd.bindPipeline(pipeline);
                cmd.bindDescriptorSet(0, setFor(scene, gi, worldPos, normal, albedo), 0, null);
                push.clear();
                viewProjCopy.get(viewProjPack);
                for (int i = 0; i < 16; i++) {
                    push.putFloat(viewProjPack[i]);
                }
                push.putFloat((float) SPP)
                        .putFloat(RenderingSystem.RTGI_MAX_DISTANCE)
                        .putFloat((float) frame)
                        .putFloat(NORMAL_BIAS);
                push.putFloat(sunRadiance.x).putFloat(sunRadiance.y).putFloat(sunRadiance.z)
                        .putFloat(sunOn ? 1.0f : 0.0f);
                push.putFloat(ambient.x).putFloat(ambient.y).putFloat(ambient.z).putFloat(0.0f);
                push.putFloat(toSun.x).putFloat(toSun.y).putFloat(toSun.z).putFloat(0.0f);
                push.flip();
                cmd.pushConstants(0, push);
                cmd.dispatch((width + WORKGROUP - 1) / WORKGROUP,
                        (height + WORKGROUP - 1) / WORKGROUP, 1);
                renderingSystem.markRtgiApplied(renderingSystem.rtgiMode());
            });
        });
        if (!denoise) {
            return;   // 降噪链未就绪（着色器缺失）：GI 图即最终图，直出 raw
        }
        // temporal：raw + 上帧历史 → 新历史（写另一代；失效判据在 push 的 params.y）
        Texture histRead = denoiseHistFor(0);
        Texture histWrite = renderingSystem.rtDenoiseHistOtherFor(histRead);
        if (histWrite == null) {
            return;   // ping-pong 另一代缺失（不应发生——创建即两代）：退直出
        }
        denoiser.addTemporalPass(viewName + "#giT", graph,
                viewName + ":" + PASS_NAME + ":Temporal",
                gi, histRead, worldPos, prevViewProj, historyValid);
        // ATrous 两迭代：新历史 → scratch（step 1）→ GI 图（step 2，着色端消费点）
        Texture scratch = renderingSystem.rtDenoiseScratchFor(viewName, DENOISE_USAGE);
        denoiser.addAtrousPass(viewName + "#giA1", graph,
                viewName + ":" + PASS_NAME + ":Atrous1",
                histWrite, scratch, worldPos, normal, 1);
        denoiser.addAtrousPass(viewName + "#giA2", graph,
                viewName + ":" + PASS_NAME + ":Atrous2",
                scratch, gi, worldPos, normal, 2);
    }

    /** 本视口 GI 降噪历史（ping-pong 代；读哪一代与帧奇偶一致——写走另一代反查） */
    private Texture denoiseHistFor(int index) {
        return renderingSystem.rtDenoiseHistFor(
                lastViewNameFor(DENOISE_USAGE, index), DENOISE_USAGE, index);
    }

    /** temporal 读代选择：本 Pass 每帧单一视口场景下记录最近视口名（多视口各自 addToGraph 互不干扰） */
    private String lastViewNameFor(String usage, int index) {
        return lastViewNames.computeIfAbsent(usage + index, n -> currentViewName);
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
                log.error("光追 GI 着色器缺失或无效: {}（RTGI 档将不出图，环境项回落）", SHADER);
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
                        ShaderStage.COMPUTE)));
        setLayout = device.createDescriptorSetLayout(setDesc);
        pipeline = device.createComputePipeline(new ComputePipelineDesc(
                new ShaderDesc(ShaderStage.COMPUTE, shader.getSpirv()), List.of(setDesc), PUSH_BYTES));
        log.info("光追 GI Pass 就绪: {}（半分辨率 {} spp），push {}B", SHADER, SPP, PUSH_BYTES);
        return true;
    }

    /** set0：0 加速结构 / 1 GI 图（storage） / 2 世界位置 / 3 法线 / 4 反照率；任一身份变化即换新集 */
    private DescriptorSet setFor(RayTracingScene scene, Texture gi, Texture worldPos,
                                 Texture normal, Texture albedo) {
        Tlas tlas = scene.tlas();
        if (set != null && boundTlas == tlas && boundGi == gi && boundWorldPos == worldPos
                && boundNormal == normal && boundAlbedo == albedo) {
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
                DescriptorUpdate.storageTexture(1, gi),
                DescriptorUpdate.sampledTexture(2, worldPos, renderingSystem.gbufferSampler()),
                DescriptorUpdate.sampledTexture(3, normal, renderingSystem.gbufferSampler()),
                DescriptorUpdate.sampledTexture(4, albedo, renderingSystem.gbufferSampler()));
        boundTlas = tlas;
        boundGi = gi;
        boundWorldPos = worldPos;
        boundNormal = normal;
        boundAlbedo = albedo;
        return set;
    }
}

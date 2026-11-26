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
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * 光追阴影可见性图 Pass（P4-06 B5）：半分辨率逐像素从 G-Buffer 世界位置沿主光方向求交，
 * 产出 R8 可见性图（1 = 受光 / 0 = 遮挡），供主渲染 shader 的 set1 binding12 采样。
 *
 * <p><b>为什么用独立 compute 而不是把 rayQuery 塞进材质 shader</b>：材质 shader 保持 450，
 * 不必为光追整体升 460 / 加 extension；求交集中在 compute 里，半分辨率 + 后续降噪都有余量。
 * 主渲染侧只多一个纹理采样与一次开关判断（{@code lighting.rtParams.x}）。</p>
 *
 * <p><b>与 CSM 的关系</b>：{@code rtShadowMode = 0} 时本 Pass 不进图、着色端走 CSM；
 * 置 1 时着色端直接采可见性图（全视口覆盖，不受级联盒与 shadowDistance 限制）。
 * 两者是对同一主光的两种遮挡求值，不叠加。</p>
 *
 * <p><b>时序</b>：必须在 GBuffer 之后（要世界位置）、Lighting 之前（被采样）；
 * 管线装配点按 after="GBuffer" 插入。TLAS 每帧在本 Pass 的 compute 回调内重建
 * （实例缓冲单份，依赖「上一帧 fence 已等」的既有约定）。</p>
 */
public final class RTShadowPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(RTShadowPass.class);

    private static final String SHADER = "core/shadow/rtshadow.compute";
    private static final String PASS_NAME = "RTShadow";
    /** push：vec4 lightDir + vec4 params（x=tMax / y=法线偏移 / z=光方向偏移） = 32B */
    private static final int PUSH_BYTES = 2 * 4 * Float.BYTES;
    private static final int WORKGROUP = 8;
    /** 起点沿世界法线抬升（世界单位）：防薄面/掠射自交 */
    private static final float NORMAL_BIAS = 0.05f;
    /** 起点沿光方向抬升（世界单位）：补法线背光侧 */
    private static final float DIRECTION_BIAS = 0.01f;

    private final Device device;
    private final RenderingSystem renderingSystem;
    private final AssetManager assetManager;

    private DescriptorSetLayoutDesc setDesc;
    private DescriptorSetLayout setLayout;
    private DescriptorSet set;
    private Tlas boundTlas;
    private Texture boundVisibility;
    private Texture boundWorldPos;
    private Texture boundNormal;
    private ComputePipeline pipeline;
    private boolean assetsWarned;
    private boolean emptyTlasWarned;
    /** 诊断日志节流计数器（每 120 次 build 打一条） */
    private int diagCounter;
    private final ByteBuffer push = ByteBuffer.allocateDirect(PUSH_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);

    public RTShadowPass(Device device, RenderingSystem renderingSystem, AssetManager assetManager) {
        if (device == null || renderingSystem == null) {
            throw new IllegalArgumentException("device / renderingSystem must not be null");
        }
        this.device = device;
        this.renderingSystem = renderingSystem;
        this.assetManager = assetManager;
    }

    @Override
    public String name() {
        return PASS_NAME;
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        // 先按「本帧未出图」记账：任何早退路径（关档 / 无光追 / 无主光 / 管线缺失）都不该让
        // 着色端继续按档位采样空图（空图全 0 = 主光被乘成 0，实测全场景纯黑且关掉
        // castShadow 才恢复）。执行段真正派发后才回填档位。
        renderingSystem.markRtShadowSkipped();
        if (renderingSystem.rtShadowMode() == 0) {
            return;   // 关档：不建图，主渲染走 CSM
        }
        RayTracingScene scene = renderingSystem.rayTracingScene();
        if (scene == null) {
            return;   // 设备无光追能力（RenderingSystem 侧此时 mode 也恒 0）
        }
        Vector3f sun = renderingSystem.sunDirectionOrNull();
        if (sun == null) {
            return;   // 无主光：没有可求交的方向
        }
        if (!ensurePipeline()) {
            return;
        }
        String viewName = ctx.view().name();
        Texture visibility = renderingSystem.rtShadowFor(viewName);
        Texture worldPos = renderingSystem.gbufferWorldPosFor(viewName);
        Texture normal = renderingSystem.gbufferNormalFor(viewName);
        if (visibility == null || worldPos == null || normal == null) {
            return;
        }
        // 主光行进方向取负 = 指向光源（与 lighting.directional 同源）
        Vector3f dir = new Vector3f(sun).negate().normalize();
        int width = visibility.desc().width();
        int height = visibility.desc().height();

        graph.addComputePass(viewName + ":" + PASS_NAME, pass -> {
            pass.setPipeline(pipeline);
            pass.addTextureRead(worldPos);
            pass.addTextureRead(normal);
            pass.addTextureStorageWrite(visibility);
            pass.setRenderCallback(cmd -> {
                int instances = scene.build(cmd, renderingSystem.renderers());
                if (diagCounter++ % 120 == 0) {
                    // 低频诊断（约 2s 一条）：TLAS 实例数与档位一起打，用来区分「Pass 没派发」
                    // 与「派发了但求交结果全遮挡」——两者都会让 rtShadowMap 读成 0
                    log.info("光追阴影诊断: TLAS 实例={}、可见性图 {}x{}、档位 {}%、renderers={}",
                            instances, width, height, renderingSystem.rtShadowMode(),
                            renderingSystem.renderers().size());
                }
                if (instances == 0) {
                    if (!emptyTlasWarned) {
                        emptyTlasWarned = true;
                        log.warn("光追阴影: TLAS 无实例，本帧不派发（主渲染仍按 RT 档采样，画面会全亮——"
                                + "检查网格是否已上传/进 renderers）");
                    }
                    return;
                }
                cmd.bindPipeline(pipeline);
                cmd.bindDescriptorSet(0, setFor(scene, visibility, worldPos, normal), 0, null);
                push.clear();
                push.putFloat(dir.x).putFloat(dir.y).putFloat(dir.z).putFloat(0.0f);
                push.putFloat(RenderingSystem.RT_SHADOW_MAX_DISTANCE)
                        .putFloat(NORMAL_BIAS).putFloat(DIRECTION_BIAS).putFloat(0.0f);
                push.flip();
                cmd.pushConstants(0, push);
                cmd.dispatch((width + WORKGROUP - 1) / WORKGROUP,
                        (height + WORKGROUP - 1) / WORKGROUP, 1);
                // 真的派发了才算本帧出图（下一帧着色端才按档位混合）
                renderingSystem.markRtShadowApplied(renderingSystem.rtShadowMode());
            });
        });
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
                log.error("光追阴影着色器缺失或无效: {}（RT 档将不出图）", SHADER);
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
                        ShaderStage.COMPUTE)));
        setLayout = device.createDescriptorSetLayout(setDesc);
        pipeline = device.createComputePipeline(new ComputePipelineDesc(
                new ShaderDesc(ShaderStage.COMPUTE, shader.getSpirv()), List.of(setDesc), PUSH_BYTES));
        log.info("光追阴影 Pass 就绪: {}（半分辨率），push {}B", SHADER, PUSH_BYTES);
        return true;
    }

    /** set0：0 加速结构 / 1 可见性图（storage） / 2 世界位置 / 3 世界法线；任一身份变化即换新集 */
    private DescriptorSet setFor(RayTracingScene scene, Texture visibility, Texture worldPos,
                                 Texture normal) {
        Tlas tlas = scene.tlas();
        if (set != null && boundTlas == tlas && boundVisibility == visibility
                && boundWorldPos == worldPos && boundNormal == normal) {
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
                DescriptorUpdate.storageTexture(1, visibility),
                DescriptorUpdate.sampledTexture(2, worldPos, renderingSystem.gbufferSampler()),
                DescriptorUpdate.sampledTexture(3, normal, renderingSystem.gbufferSampler()));
        boundTlas = tlas;
        boundVisibility = visibility;
        boundWorldPos = worldPos;
        boundNormal = normal;
        return set;
    }
}

package cn.donting.javelin.core.render.rt;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.compute.ComputeShader;
import cn.donting.javelin.rhi.ComputePipeline;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.types.DescriptorType;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 光追降噪共享执行器（P4-06 B8）：{@link RTGIPass} / {@link RTReflectPass} 共用的
 * 时域累积 + ATrous 链段（着色器 {@code core/rt/rtdenoise.compute}，push mode 分派）。
 *
 * <p>链形态（每视口）：raw → temporal（重投影累积，写新历史）→ atrous 步长 1（历史 →
 * scratch）→ atrous 步长 2（scratch → 最终图）。历史 ping-pong 两代由
 * {@link RenderingSystem#rtDenoiseHistFor} 持有；scratch 单张
 * {@link RenderingSystem#rtDenoiseScratchFor}。每段各占一个 RenderGraph compute Pass
 * （storage 写 → 采样读的布局转换与屏障由图执行器按声明自动插入，22.4 迁移纪律）。</p>
 *
 * <p>历史失效判据（直出当前帧、年龄归 1）：断帧 / 纹理换代（resize）/ 相机移动 /
 * prevViewProj 缺失——来源 {@code TaaHistory}，口径与 TaaPass 一致。</p>
 */
public final class RTDenoiser {

    private static final Logger log = LoggerFactory.getLogger(RTDenoiser.class);

    private static final String SHADER = "core/rt/rtdenoise.compute";
    /** push：mat4 prevViewProj(64) + vec4 params = 80B */
    private static final int PUSH_BYTES = 64 + 16;
    private static final int WORKGROUP = 8;
    static final int MODE_TEMPORAL = 0;
    static final int MODE_ATROUS = 1;

    /**
     * 排障旁路开关（2026-11-28）：{@code true} = 降噪链整段不进图，raw 直出（GI/反射图
     * 即最终图，temporal + atrous 全跳）。命令行 {@code --rtDenoiseBypass} 开启，逐项
     * 排查「乱反射/重影来自降噪链还是求交本身」用。
     */
    public static final boolean BYPASS = cn.donting.javelin.core.LaunchArgs
            .getBoolean("rtDenoiseBypass", false);

    static {
        if (BYPASS) {
            log.warn("RT 降噪链旁路开启（--rtDenoiseBypass）：raw 直出，temporal/atrous 不进图");
        }
    }

    private final Device device;
    private final RenderingSystem renderingSystem;
    private final AssetManager assetManager;

    private ComputeShader shader;
    private DescriptorSetLayoutDesc setDesc;
    private DescriptorSetLayout setLayout;
    private ComputePipeline pipeline;
    private boolean assetsWarned;
    /** 段描述符集缓存：key = 段标识（passKey+mode），值按纹理身份换代重建 */
    private final Map<String, DescriptorSet> sets = new HashMap<>();
    private final Matrix4f identityMatrix = new Matrix4f();
    private final float[] matrixPack = new float[16];
    private final ByteBuffer push = ByteBuffer.allocateDirect(PUSH_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);

    public RTDenoiser(Device device, RenderingSystem renderingSystem, AssetManager assetManager) {
        this.device = device;
        this.renderingSystem = renderingSystem;
        this.assetManager = assetManager;
    }

    /** 就绪判定（着色器缺失/坏产物时 false，调用方整段降噪不进图、直出 raw） */
    boolean ready() {
        return ensurePipeline();
    }

    /**
     * temporal 段：raw + 上帧历史 → 新历史（重投影累积，年龄自适应 α）。
     *
     * @param passKey    段标识（调用方拼：视口名 + 用途），描述符集缓存键
     * @param graph      帧图（本段以独立 compute Pass 追加）
     * @param graphName  图内 Pass 名（唯一）
     * @param raw        本帧原始输出（采样读）
     * @param history    上帧历史（采样读；本段写出口为「另一代」历史）
     * @param worldPos   G-Buffer 世界位置（采样读，重投影与离散度判据）
     * @param prevViewProj 上帧抖动后 viewProj；null = 历史判无效（identity 占位）
     * @param historyValid 失效判据结果（false 时着色器直出当前帧）
     */
    void addTemporalPass(String passKey, RenderGraphBuilder graph, String graphName,
                         Texture raw, Texture history, Texture worldPos,
                         Matrix4f prevViewProj, boolean historyValid) {
        if (!ensurePipeline()) {
            return;
        }
        Texture histOut = otherGeneration(history, raw);
        graph.addComputePass(graphName, pass -> {
            pass.setPipeline(pipeline);
            pass.addTextureRead(raw);
            pass.addTextureRead(history);
            pass.addTextureRead(worldPos);
            pass.addTextureStorageWrite(histOut);
            pass.setRenderCallback(cmd -> dispatchDenoise(cmd, passKey, MODE_TEMPORAL,
                    histOut, raw, history, worldPos, prevViewProj, historyValid, 0));
        });
    }

    /**
     * atrous 段：输入 → 输出（5×5 十字保边核，step = 步长纹素）。
     * 两迭代由调用方各发一段（step 1 → scratch、step 2 → 最终图）。
     */
    void addAtrousPass(String passKey, RenderGraphBuilder graph, String graphName,
                       Texture in, Texture out, Texture worldPos, Texture normal, int step) {
        if (!ensurePipeline()) {
            return;
        }
        graph.addComputePass(graphName, pass -> {
            pass.setPipeline(pipeline);
            pass.addTextureRead(in);
            pass.addTextureRead(worldPos);
            pass.addTextureRead(normal);
            pass.addTextureStorageWrite(out);
            pass.setRenderCallback(cmd -> dispatchDenoise(cmd, passKey, MODE_ATROUS,
                    out, in, null, worldPos, null, false, step));
        });
    }

    /** 释放（须 device waitIdle 后；段描述符集与布局随本执行器生命周期） */
    public void close() {
        sets.values().forEach(DescriptorSet::dispose);
        sets.clear();
        if (setLayout != null) {
            setLayout.dispose();
            setLayout = null;
        }
        if (pipeline != null) {
            pipeline.dispose();
            pipeline = null;
        }
    }

    /** temporal 写出口 = 上一代历史（ping-pong：读 a 写 b；拿不到另一代时退 raw 自己） */
    private Texture otherGeneration(Texture history, Texture raw) {
        Texture alt = renderingSystem.rtDenoiseHistOtherFor(history);
        return alt != null && alt != raw ? alt : history;
    }

    private void dispatchDenoise(cn.donting.javelin.rhi.CommandBuffer cmd, String passKey,
                                 int mode, Texture out, Texture in, Texture history,
                                 Texture worldPos, Matrix4f prevViewProj,
                                 boolean historyValid, int step) {
        cmd.bindPipeline(pipeline);
        cmd.bindDescriptorSet(0, setFor(passKey, out, in, history, worldPos), 0, null);
        push.clear();
        (prevViewProj != null ? prevViewProj : identityMatrix).get(matrixPack);
        for (int i = 0; i < 16; i++) {
            push.putFloat(matrixPack[i]);
        }
        push.putFloat((float) mode)
                .putFloat(mode == MODE_TEMPORAL && historyValid ? 1.0f : 0.0f)
                .putFloat((float) Math.max(1, step))
                .putFloat(0.0f);
        push.flip();
        cmd.pushConstants(0, push);
        int w = out.desc().width();
        int h = out.desc().height();
        cmd.dispatch((w + WORKGROUP - 1) / WORKGROUP, (h + WORKGROUP - 1) / WORKGROUP, 1);
    }

    private boolean ensurePipeline() {
        if (pipeline != null) {
            return true;
        }
        if (assetManager == null) {
            return false;
        }
        if (shader == null) {
            shader = assetManager.loadAsset(SHADER, ComputeShader.class);
        }
        if (shader == null || !shader.isValid()) {
            if (!assetsWarned) {
                assetsWarned = true;
                log.error("光追降噪着色器缺失或无效: {}（GI/反射降噪段不进图，直出单帧原始图）", SHADER);
            }
            return false;
        }
        setDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.STORAGE_IMAGE, 1,
                        cn.donting.javelin.rhi.types.ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        cn.donting.javelin.rhi.types.ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(2, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        cn.donting.javelin.rhi.types.ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(3, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        cn.donting.javelin.rhi.types.ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(4, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        cn.donting.javelin.rhi.types.ShaderStage.COMPUTE)));
        setLayout = device.createDescriptorSetLayout(setDesc);
        pipeline = device.createComputePipeline(new cn.donting.javelin.rhi.desc.ComputePipelineDesc(
                new cn.donting.javelin.rhi.desc.ShaderDesc(
                        cn.donting.javelin.rhi.types.ShaderStage.COMPUTE, shader.getSpirv()),
                List.of(setDesc), PUSH_BYTES));
        log.info("光追降噪 Pass 就绪: {}（temporal + atrous 两模式），push {}B", SHADER, PUSH_BYTES);
        return true;
    }

    /**
     * 段描述符集：按「passKey + 纹理身份组」变体缓存。temporal 段的 history 随帧奇偶
     * 交替（ping-pong），单集缓存会逐帧退役重建（描述符池 churn + Cleaner 日志洪水，
     * 2026-11-28 修）——改为每个 passKey 保留最多 2 个变体轮流复用；出现第 3 个身份组
     * （纹理换代/resize）时整组退役重建。
     */
    private DescriptorSet setFor(String passKey, Texture out, Texture in, Texture history,
                                 Texture worldPos) {
        String variant = passKey + "#" + Integer.toHexString(System.identityHashCode(out))
                + "#" + Integer.toHexString(System.identityHashCode(in))
                + "#" + Integer.toHexString(System.identityHashCode(history))
                + "#" + Integer.toHexString(System.identityHashCode(worldPos));
        DescriptorSet cached = sets.get(variant);
        if (cached != null) {
            return cached;
        }
        if (setLayout == null) {
            setLayout = device.createDescriptorSetLayout(setDesc);
        }
        // 同 passKey 变体超 2 个 = 纹理换代：旧变体整组退役（retireResource 两帧后销毁）
        long samePassVariants = sets.keySet().stream()
                .filter(k -> k.startsWith(passKey + "#")).count();
        if (samePassVariants >= 2) {
            List<String> stale = sets.keySet().stream()
                    .filter(k -> k.startsWith(passKey + "#")).toList();
            for (String k : stale) {
                DescriptorSet retiring = sets.remove(k);
                renderingSystem.retireResource(retiring::dispose);
            }
        }
        DescriptorSet created = device.createDescriptorSet(setLayout);
        // 历史/法线槽不用的段以重复绑定占位（atrous 的 history 复用 in；法线槽由调用方
        // 绑定集声明保证不空——temporal 段绑 normal 位传 worldPos，见各 Pass 装配）
        Texture histBind = history != null ? history : in;
        created.update(DescriptorUpdate.storageTexture(0, out),
                DescriptorUpdate.sampledTexture(1, in, renderingSystem.rtgiSampler()),
                DescriptorUpdate.sampledTexture(2, histBind, renderingSystem.rtgiSampler()),
                DescriptorUpdate.sampledTexture(3, worldPos, renderingSystem.gbufferSampler()),
                DescriptorUpdate.sampledTexture(4, worldPos, renderingSystem.gbufferSampler()));
        sets.put(variant, created);
        return created;
    }
}

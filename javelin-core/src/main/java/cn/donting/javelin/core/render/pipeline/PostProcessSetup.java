package cn.donting.javelin.core.render.pipeline;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.render.pipeline.pass.BloomPass;
import cn.donting.javelin.core.render.pipeline.pass.TonemapPass;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.data.RHIClearColor;

/**
 * 后处理节点装配（组合根，Engine_PostProcess_Design §3.4/§9）：持有节点实例 + 按 id 增删链。
 *
 * <p><b>为什么独立成类</b>：管线（{@link AbstractRenderPipeline}）只承载「链」与「展开点」，
 * 不认识任何具体后处理类；节点的创建、默认组成与运行期开关归本类（组合根）。这样新增后期
 * 效果（SSAO/TAA…）只需在本类与组合根登记，不再改管线基类（2026-11-14 解耦，零行为变化）。</p>
 *
 * <p>节点惰性创建；{@link #isEnabled} 不触发创建（菜单 checked 高频求值不得装载着色器）。</p>
 */
public final class PostProcessSetup {

    /** 效果 id（{@link #node}/{@link #setEnabled}/{@link #isEnabled} 用） */
    public static final String TONEMAP = "tonemap";
    public static final String BLOOM = "bloom";

    private final Device device;
    private final AssetManager assetManager;
    private RHIClearColor clearColor = new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);
    /** 后处理参数（缺省与 PostProcessSettings 一致；启动期由 JavelinEngine 下发覆盖） */
    private float bloomThreshold = 0.8f;
    private float bloomKnee = 0.4f;
    private float bloomIntensity = 0.5f;
    private float tonemapKnee = 0.8f;
    /** 已创建即常驻；null = 尚未创建（必然不在链上） */
    private TonemapPass tonemap;
    private BloomPass bloom;

    public PostProcessSetup(Device device, AssetManager assetManager) {
        this.device = device;
        this.assetManager = assetManager;
    }

    /** 取节点实例（惰性创建；创建时同步当前清屏色） */
    public PostProcessNode node(String id) {
        if (TONEMAP.equals(id)) {
            if (tonemap == null) {
                tonemap = new TonemapPass(device, assetManager);
                tonemap.setClearColor(clearColor);
                tonemap.setKnee(tonemapKnee);
            }
            return tonemap;
        }
        if (BLOOM.equals(id)) {
            if (bloom == null) {
                bloom = new BloomPass(device, assetManager);
                bloom.setClearColor(clearColor);
                bloom.setParams(bloomThreshold, bloomKnee, bloomIntensity);
            }
            return bloom;
        }
        throw new IllegalArgumentException("未知后处理节点 id: " + id);
    }

    /** 开关链挂载（按节点名去重；开 = 未挂则挂，关 = 按名摘） */
    public void setEnabled(PostProcessChain chain, String id, boolean enabled) {
        PostProcessNode node = node(id);
        if (enabled) {
            boolean present = chain.nodes().stream().anyMatch(n -> n.name().equals(node.name()));
            if (!present) {
                chain.addLast(node);
            }
        } else {
            chain.remove(node.name());
        }
    }

    /** 链上是否已挂该节点（**不触发创建**——未创建必然不在链上） */
    public boolean isEnabled(PostProcessChain chain, String id) {
        PostProcessNode node = TONEMAP.equals(id) ? tonemap : BLOOM.equals(id) ? bloom : null;
        return node != null && chain.nodes().contains(node);
    }

    /** 清屏色下发（已创建节点立即同步；后续创建的节点在 {@link #node} 时补下发） */
    public void setClearColor(RHIClearColor clearColor) {
        if (clearColor == null) {
            return;
        }
        this.clearColor = clearColor;
        if (tonemap != null) {
            tonemap.setClearColor(clearColor);
        }
        if (bloom != null) {
            bloom.setClearColor(clearColor);
        }
    }

    /** Bloom 参数下发（阈值/软膝/强度；已创建节点立即生效，后创建节点在 {@link #node} 时补发） */
    public void setBloomParams(float threshold, float knee, float intensity) {
        this.bloomThreshold = threshold;
        this.bloomKnee = knee;
        this.bloomIntensity = intensity;
        if (bloom != null) {
            bloom.setParams(threshold, knee, intensity);
        }
    }

    /** Tonemap 肩部下发（同上：已创建即时、后创建补发） */
    public void setTonemapKnee(float knee) {
        this.tonemapKnee = knee;
        if (tonemap != null) {
            tonemap.setKnee(knee);
        }
    }
}

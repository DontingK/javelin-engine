package cn.donting.javelin.core.render.material;

import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.assets.AssetManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认材质门面（静态门面，对齐 Time / Input 先例）：坏 shader / 缺材质渲染回退的
 * 机制层（2026-10-29 由 DefaultShader 改名——门面身份 = 共享默认材质，非 shader）。
 * Shader 无有效 SPIR-V 二进制（编译失败 stub 产物 / 坏产物，判据
 * {@link Shader#isValid()}）或子网格材质缺失位 → 渲染时用默认材质渲染（拍板
 * 2026-10-27 / 2026-10-29）。默认 shader = 内置资产 {@code core/shader/default.shader}
 * （固定 guid，经 BuiltinAssetReleaser 释放进 Library，路径式加载）。
 *
 * <p>接线：JavelinEngine.initialize 期 {@link #init(AssetManager)} 注入；未初始化
 * / Library 无产物 → resolve 原样返回（降级为现状行为，不炸）。整 renderer 的
 * material null 不渲染（missing-material 语义）；多材质子网格缺失位走
 * {@link #resolveOrDefault} 默认材质兜底（拍板 2026-10-29）。默认材质为
 * <b>共享单例</b>——懒建一次全引擎复用，identity 稳定，渲染链逐帧比较不抖动
 * （热重载换代除外，{@link #onShaderReloaded} 置空下帧重建）。</p>
 */
public final class DefaultMaterial {

    private static final Logger log = LoggerFactory.getLogger(DefaultMaterial.class);

    /** 内置默认 shader 资产路径（CreateMaterialMenuCommand 同款路径式加载） */
    public static final String DEFAULT_SHADER_PATH = "core/shader/default.shader";

    /** 引擎启动期注入（initialize）；null = 未初始化（resolve 原样降级） */
    private static AssetManager manager;
    /** 默认材质单例（默认 shader + 空默认值，懒建一次共享；identity 稳定）；volatile：DCL 读写可见性 */
    private static volatile Material material;
    private static boolean warned;

    private DefaultMaterial() {
        // 静态门面禁止实例化
    }

    /**
     * 引擎启动期注入资产管理器（JavelinEngine.initialize 接线）。
     */
    public static void init(AssetManager assetManager) {
        manager = assetManager;
    }

    /**
     * 渲染回退解析：material null → null（missing-material 语义不变）；shader null
     * 或 {@link Shader#isValid()} false → 默认材质单例；默认 shader 不可用（未初始化
     * /Library 无产物）→ 原样返回（降级，渲染侧保持现状行为）。全程不抛。
     */
    public static Material resolve(Material material) {
        if (material == null) {
            return null;
        }
        if (material.getShader() == null || !material.getShader().isValid()) {
            return material();
        }
        return material;
    }

    /**
     * 缺失兜底解析（多材质子网格用，拍板 2026-10-29）：material null（未赋子网格
     * 位）或 shader 无效 → 默认材质单例（Unity missing→error shader 口径，
     * 原「null material 不渲染」口径作废）；默认 shader 不可用（未初始化 /
     * Library 无产物）→ null（该子网格不渲染降级）。全程不抛。
     */
    public static Material resolveOrDefault(Material material) {
        Material resolved = resolve(material);
        return resolved != null ? resolved : material();
    }

    /**
     * 默认材质单例 peek（对账活集用）：已建返回实例，未建返回 null 不触发创建。
     */
    public static Material current() {
        return material;
    }

    /**
     * 坏 shader 热重载换代（重载协调器调用）：default.shader 本体被重导换代 →
     * 单例置空，下帧 resolve 按 loadGuid 新实例重建。identity 比对，无 guid 逻辑。
     */
    public static void onShaderReloaded(Asset fresh) {
        if (material != null && material.getShader() == fresh) {
            material = null;
            log.info("默认 shader 已换代，下帧重建默认材质");
        }
    }

    /** 共享单例懒建（双重检查锁：已建走无锁快路径，仅首次建进同步块） */
    private static Material material() {
        Material local = material;
        if (local != null) {
            return local;
        }
        synchronized (DefaultMaterial.class) {
            if (material != null) {
                return material;
            }
            if (manager == null) {
                return null;
            }
            Shader shader;
            try {
                shader = manager.loadAsset(DEFAULT_SHADER_PATH, Shader.class);
            } catch (Exception e) {
                shader = null;
            }
            if (shader == null || !shader.isValid()) {
                if (!warned) {
                    warned = true;
                    log.warn("默认 shader 不可用（{} 缺失或损坏），坏 shader 回退停用", DEFAULT_SHADER_PATH);
                }
                return null;
            }
            material = new Material(shader);
            return material;
        }
    }
}

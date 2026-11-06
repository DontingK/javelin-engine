package cn.donting.javelin.core.render;

import cn.donting.javelin.core.inspector.InsEnumLabel;

/**
 * 相机清屏模式（对照 Unity HDRP Camera Clear Mode / Built-in clearFlags，用户拍板
 * 2026-09-26 落地、文案对齐 HDRP 2026-11-06）。
 *
 * <p>决定本视口渲染前对颜色/深度附件的清屏策略（loadOp）。
 * SKYBOX（批次⑤，2026-11-02 入列）：画场景级天空盒资产（Scene.skybox，HDRP
 * HDRI Sky 的 cubemap 槽同构）——Opaque 正常清屏（背景色+深度），SkyboxPass
 * 3.5 位画在不透明后；无 skybox 引用时回退 SOLID_COLOR 语义（正常域空状态不弹错）。</p>
 * 实现 {@link InsEnumLabel} 为 Inspector 下拉提供显示名——口径=自述清屏行为
 * （用户拍板 2026-10-11：SOLID_COLOR 不沿用 Unity 直译「纯色」，作为清屏策略
 * 名不自述行为；Skybox 入列后集合=天空盒/颜色+深度/仅深度/不清除）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public enum ClearFlags implements InsEnumLabel {
    /** 清颜色（相机背景色/管线默认色）+ 清深度——常规视口（默认） */
    SOLID_COLOR("ins.enum.clearFlags.solidColor"),
    /** 只清深度，颜色保留已有画面——叠加相机合成（全屏 UI/Gizmo 层，配合 depth 排序） */
    DEPTH_ONLY("ins.enum.clearFlags.depthOnly"),
    /** 颜色与深度都保留（LOAD）——残影类特效；附件从未被清过时内容未定义，慎用 */
    DONT_CLEAR("ins.enum.clearFlags.dontClear"),
    /** 天空盒：画场景级 skybox（SkyboxPass 画在不透明后、深度 LEqual 被遮挡）——无天空盒资产时回退 SOLID_COLOR（批次⑤） */
    SKYBOX("ins.enum.clearFlags.skybox");

    /** Inspector 下拉显示名（自述清屏行为，见类注释） */
    private final String label;

    ClearFlags(String label) {
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }
}

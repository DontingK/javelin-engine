package cn.donting.javelin.editor.menu.top;

import cn.donting.javelin.editor.debug.ShadowMapDebugAppState;

/**
 * 阴影图视图切换项（工具菜单「阴影图视图」子菜单，DBG-03，
 * Engine_Shadow_Quality_Design §4.7）。
 *
 * <p>三个实例 = 关闭 / 原始深度 / 级联分色，单选勾选；执行只写意图，实际挂/卸 Pass 由
 * {@link ShadowMapDebugAppState} 在引擎帧消费。</p>
 */
public final class ShadowMapViewMenuCommand extends TopMenuCommand {

    private final String id;
    private final int mode;

    public ShadowMapViewMenuCommand(String id, int mode) {
        this.id = id;
        this.mode = mode;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Boolean checked() {
        return ShadowMapDebugAppState.getMode() == mode;
    }

    /** 两视图项需该视口阴影资源已建；「关闭」恒可用 */
    @Override
    public Boolean enabled() {
        if (mode == ShadowMapDebugAppState.MODE_OFF) {
            return null;
        }
        return ShadowMapDebugAppState.isReady();
    }

    @Override
    public void execute() {
        ShadowMapDebugAppState.requestMode(mode);
    }
}

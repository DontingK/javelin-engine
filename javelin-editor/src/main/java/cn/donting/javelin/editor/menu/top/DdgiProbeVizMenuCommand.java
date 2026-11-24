package cn.donting.javelin.editor.menu.top;

import cn.donting.javelin.editor.debug.DdgiProbeVizAppState;

/**
 * DDGI 探针视图切换项（工具菜单「DDGI 探针视图」子菜单，dbg-3，
 * Engine_DDGI_ProbeViz_Design §3）。
 *
 * <p>四个实例 = 关闭 / 探针网格 / 辐照度 atlas / 深度 atlas，单选勾选（{@link #checked()}
 * 比对 {@link DdgiProbeVizAppState#getMode()}）。执行只写意图，实际挂/卸 Pass 由 AppState
 * 在引擎帧消费。</p>
 *
 * <p><b>置灰口径</b>：网格项需探针网格已建、两张 atlas 项需 atlas 已建（引擎帧刷新就绪标记）；
 * 「关闭」恒可用。DDGI 未启用时三项恒灰。</p>
 */
public final class DdgiProbeVizMenuCommand extends TopMenuCommand {

    private final String id;
    private final int mode;

    /**
     * @param id   命令 id（同时是菜单文案键，如 {@code tools.ddgiProbeView.grid}）
     * @param mode 目标模式（{@link DdgiProbeVizAppState} 的 MODE_*）
     */
    public DdgiProbeVizMenuCommand(String id, int mode) {
        this.id = id;
        this.mode = mode;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Boolean checked() {
        return DdgiProbeVizAppState.getMode() == mode;
    }

    @Override
    public Boolean enabled() {
        if (mode == DdgiProbeVizAppState.MODE_OFF) {
            return null;
        }
        if (mode == DdgiProbeVizAppState.MODE_GRID) {
            return DdgiProbeVizAppState.isGridReady();
        }
        return DdgiProbeVizAppState.isAtlasReady();
    }

    @Override
    public void execute() {
        DdgiProbeVizAppState.requestMode(mode);
    }
}

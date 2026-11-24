package cn.donting.javelin.editor.menu.top;

import cn.donting.javelin.editor.debug.LightGizmoAppState;

/**
 * 光照 gizmo 切换项（工具菜单「光照 gizmo」子菜单，REND-13）。
 *
 * <p>两个实例 = 关闭 / 显示，单选勾选；执行只写意图，实际逐帧线框由
 * {@link LightGizmoAppState} 在 {@code DebugLineAppState} 的 debug 线提交阶段产出。</p>
 */
public final class LightGizmoMenuCommand extends TopMenuCommand {

    private final String id;
    private final int mode;

    public LightGizmoMenuCommand(String id, int mode) {
        this.id = id;
        this.mode = mode;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Boolean checked() {
        return LightGizmoAppState.getMode() == mode;
    }

    @Override
    public void execute() {
        LightGizmoAppState.requestMode(mode);
    }
}

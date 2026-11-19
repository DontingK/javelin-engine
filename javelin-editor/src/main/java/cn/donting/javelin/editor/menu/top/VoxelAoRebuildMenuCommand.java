package cn.donting.javelin.editor.menu.top;

import cn.donting.javelin.editor.JavelinEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 体素 AO 重建命令（工具域，动作型无勾选）：请求下一次图构建重新收集场景网格并
 * 重算占用图 → AO → 模糊图（Engine_VoxelAO_Design §4.1）。
 *
 * <p>用于静止物体移动/缩放后 AO 不跟随的场景（首版只在开关、分辨率、射线数、
 * 距离与渲染对象数量变化时自动重建）。总开关关闭时不动作，引擎侧记日志（合法
 * 空状态，不弹框）。</p>
 *
 * @author javelin
 * @create 2026/11/19
 */
public final class VoxelAoRebuildMenuCommand extends TopMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoxelAoRebuildMenuCommand.class);

    @Override
    public String id() {
        return "tools.voxelAoRebuild";
    }

    /**
     * 总开关关闭时置灰（菜单态求值即时靠项目设置窗打开开关后恢复）——
     * 命令本身在关闭态是合法空操作，不弹框。
     */
    @Override
    public Boolean enabled() {
        JavelinEditor editor = JavelinEditor.getInstance();
        return editor != null
                && editor.setting().getGraphSettings().getVoxelAoSettings() != null
                && editor.setting().getGraphSettings().getVoxelAoSettings().isEnabled();
    }

    @Override
    public void execute() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器未就绪，体素 AO 重建不可用");
            return;
        }
        // 只立标志：重建在图构建期由引擎线程执行（命令线程不碰 GPU 资源）
        editor.requestVoxelRebuild();
    }
}

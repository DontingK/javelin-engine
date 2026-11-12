package cn.donting.javelin.editor.menu.top;

import cn.donting.javelin.core.render.pipeline.DefaultHybridRenderPipeline;
import cn.donting.javelin.core.render.pipeline.PipelineProfile;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.debug.GBufferDebugAppState;

/**
 * G-Buffer 可视化视图切换项（工具菜单「G-Buffer 视图」子菜单，dbg-2，
 * Engine_DebugTools_Plan §3）。
 *
 * <p>四个实例 = 关闭 / 基色 / 世界法线 / 世界位置，单选勾选（{@link #checked()} 比对
 * {@link GBufferDebugAppState#getMode()}）。执行只写意图
 * （{@link GBufferDebugAppState#requestMode(int)}），实际挂/卸 Pass 由 AppState 在引擎
 * 帧消费（Gizmo 意图镜像同口径）。</p>
 *
 * <p><b>置灰口径</b>：三个视图项仅 HYBRID 档可用（FORWARD 档 G-Buffer 不写），
 * 「关闭」恒可用。</p>
 */
public final class GBufferViewMenuCommand extends TopMenuCommand {

    private final String id;
    private final int mode;

    /**
     * @param id   命令 id（同时是菜单文案键，如 {@code tools.gbufferView.albedo}）
     * @param mode 目标模式（{@link GBufferDebugAppState} 的 MODE_*）
     */
    public GBufferViewMenuCommand(String id, int mode) {
        this.id = id;
        this.mode = mode;
    }

    @Override
    public String id() {
        return id;
    }

    /** 单选勾选：当前意图模式 == 本项模式 */
    @Override
    public Boolean checked() {
        return GBufferDebugAppState.getMode() == mode;
    }

    /** 三个视图项仅 HYBRID 档可用；「关闭」恒可用（null = 恒启用） */
    @Override
    public Boolean enabled() {
        if (mode == GBufferDebugAppState.MODE_OFF) {
            return null;
        }
        DefaultHybridRenderPipeline pipeline = hybridPipeline();
        return pipeline != null && pipeline.profile() == PipelineProfile.HYBRID;
    }

    @Override
    public void execute() {
        GBufferDebugAppState.requestMode(mode);
    }

    private static DefaultHybridRenderPipeline hybridPipeline() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return null;
        }
        return editor.renderPipeline() instanceof DefaultHybridRenderPipeline p ? p : null;
    }
}

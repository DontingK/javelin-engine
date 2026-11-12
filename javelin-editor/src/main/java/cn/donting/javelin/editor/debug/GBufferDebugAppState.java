package cn.donting.javelin.editor.debug;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.appstate.AppStateManager;
import cn.donting.javelin.core.debug.DebugText;
import cn.donting.javelin.core.debug.DebugTextColor;
import cn.donting.javelin.core.input.Input;
import cn.donting.javelin.core.input.KeyCode;
import cn.donting.javelin.core.render.pipeline.PipelineProfile;
import cn.donting.javelin.core.render.pipeline.RenderPipeline;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.editor.appstate.AbsEditorAppState;
import cn.donting.javelin.editor.plugin.EditorLang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * G-Buffer 可视化调试视图的装配与驱动（编辑器排障面，dbg-2，Engine_DebugTools_Plan §3）。
 *
 * <p><b>切换入口 = 顶部菜单</b>「工具 | G-Buffer 视图」四项（关闭 / 基色 / 世界法线 / 世界位置，
 * 单选勾选）——菜单在 EDT 写静态意图 {@link #requestMode(int)}，本状态在引擎帧
 * {@code update} 消费（Gizmo 意图镜像同口径）；另保留 {@code F4} 边沿循环作为快捷键。
 * 意图 → 实际：非关闭态**惰性创建** {@link GBufferViewPass} 并 {@code addPass} 尾追加，
 * 关闭态 {@code removePass}。</p>
 *
 * <p><b>档位边界</b>：视图仅 HYBRID 档有消费意义（FORWARD 档 G-Buffer 不写）——FORWARD
 * 档按 F4 不切换、写 DebugText 行（{@link #HINT_ROW}）提示；运行期 HYBRID 切 FORWARD
 * 自动收起。菜单项在 FORWARD 档置灰（见 {@code GBufferViewMenuCommand.enabled()}）。
 * 目标视口 = 编辑器相机视口（Scene 视图），GAME 视口不进图。</p>
 */
public class GBufferDebugAppState extends AbsEditorAppState {

    private static final Logger log = LoggerFactory.getLogger(GBufferDebugAppState.class);

    public static final int MODE_OFF = 0;
    public static final int MODE_ALBEDO = 1;
    public static final int MODE_NORMAL = 2;
    public static final int MODE_WORLD_POS = 3;

    /** 提示行：RenderStatsAppState 占 0~9，本状态的行提示落 10 */
    private static final int HINT_ROW = 10;

    /**
     * 意图模式（菜单 EDT 与 F4 引擎线程写、update 消费）。静态 volatile——菜单命令
     * 无需持有 AppState 实例（与 {@code TransformGizmoAppState} 意图镜像同口径）。
     */
    private static volatile int desiredMode = MODE_OFF;

    /** 意图模式（供菜单 checked() 判定勾选） */
    public static int getMode() {
        return desiredMode;
    }

    /** 写入意图模式（越界一律按关闭） */
    public static void requestMode(int mode) {
        desiredMode = (mode < MODE_OFF || mode > MODE_WORLD_POS) ? MODE_OFF : mode;
    }

    /** 目标视口（编辑器相机视口名；onAttach 解析，null = 相机未就绪跳过） */
    private String targetView;
    /** 惰性创建的覆盖 Pass（首次切到非关闭态时装载着色器并创建） */
    private GBufferViewPass pass;
    private boolean inPipeline;
    /** 已生效模式（与 desiredMode 比对，变化才挂/卸 Pass） */
    private int appliedMode = MODE_OFF;

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        super.onAttach(stateManager, engine);
        Camera editorCamera = javelinEditor.getEditorCamera();
        if (editorCamera == null || editorCamera.getNode() == null) {
            log.warn("编辑器相机未就绪，跳过 G-Buffer 调试视图装配（GBufferDebugAppState）");
            return;
        }
        targetView = editorCamera.getNode().getName();
    }

    @Override
    public void update(float deltaTime) {
        RenderPipeline pipeline = engine.renderPipeline();
        if (pipeline == null || targetView == null) {
            return; // 非编辑器环境 / 相机未就绪
        }
        boolean hybrid = pipeline.profile() == PipelineProfile.HYBRID;
        if (Input.wasPressed(KeyCode.F4)) {
            if (hybrid) {
                requestMode(nextMode(desiredMode));
            } else {
                showHint(EditorLang.text("tools.gbufferView.forwardOnly"));
            }
        }
        // 运行期切到 FORWARD：G-Buffer 不再写，自动收起（无需用户再操作）
        if (!hybrid && desiredMode != MODE_OFF) {
            requestMode(MODE_OFF);
        }
        reconcile(pipeline);
    }

    /** 意图 → 实际：变化才挂/卸 Pass（addPass/removePass 在图构建前、引擎线程） */
    private void reconcile(RenderPipeline pipeline) {
        int desired = desiredMode;
        if (desired == appliedMode) {
            return;
        }
        if (desired == MODE_OFF) {
            if (pass != null) {
                pass.setMode(MODE_OFF);
            }
            detachPass(pipeline);
            clearHint();
        } else {
            if (!ensurePass()) {
                // 着色器装载失败已由 ensurePass 报错 + 写提示行并回退意图，此处只对齐已生效态
                appliedMode = MODE_OFF;
                return;
            }
            pass.setMode(desired);
            if (!inPipeline) {
                pipeline.addPass(pass);
                inPipeline = true;
            }
            clearHint();
        }
        appliedMode = desired;
        log.info("G-Buffer 调试视图: {}", label(desired));
    }

    /** 惰性创建 Pass；着色器装载失败 → 记日志 + 提示行 + 回退意图（不让单键把帧循环带崩） */
    private boolean ensurePass() {
        if (pass != null) {
            return true;
        }
        try {
            pass = new GBufferViewPass(engine.device(), engine.getAssetManager(),
                    engine.renderingSystem(), targetView);
            return true;
        } catch (RuntimeException e) {
            log.error("G-Buffer 调试视图装配失败（GBufferViewPass 创建/着色器装载）", e);
            showHint(EditorLang.text("tools.gbufferView.loadFailed"));
            requestMode(MODE_OFF);
            return false;
        }
    }

    private void detachPass(RenderPipeline pipeline) {
        if (inPipeline) {
            pipeline.removePass(pass);
            inPipeline = false;
        }
    }

    /** F4 循环：关闭 → 基色 → 世界法线 → 世界位置 → 关闭 */
    private static int nextMode(int mode) {
        return mode >= MODE_WORLD_POS ? MODE_OFF : mode + 1;
    }

    private static String label(int mode) {
        return switch (mode) {
            case MODE_ALBEDO -> "albedo";
            case MODE_NORMAL -> "worldNormal";
            case MODE_WORLD_POS -> "worldPos";
            default -> "off";
        };
    }

    private static void showHint(String text) {
        DebugText.clear(HINT_ROW);
        DebugText.printf(HINT_ROW, text,
                DebugTextColor.attr(DebugTextColor.YELLOW, DebugTextColor.BLACK));
    }

    private static void clearHint() {
        DebugText.clear(HINT_ROW);
    }

    @Override
    public void onDetach(AppStateManager stateManager) {
        RenderPipeline pipeline = engine != null ? engine.renderPipeline() : null;
        if (inPipeline && pipeline != null) {
            pipeline.removePass(pass);
            inPipeline = false;
        }
        super.onDetach(stateManager);
    }
}

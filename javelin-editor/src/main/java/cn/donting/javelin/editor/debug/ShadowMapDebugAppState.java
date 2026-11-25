package cn.donting.javelin.editor.debug;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.appstate.AppStateManager;
import cn.donting.javelin.core.debug.DebugText;
import cn.donting.javelin.core.debug.DebugTextColor;
import cn.donting.javelin.core.input.Input;
import cn.donting.javelin.core.input.KeyCode;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.pipeline.RenderPipeline;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.editor.appstate.AbsEditorAppState;
import cn.donting.javelin.editor.plugin.EditorLang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阴影图（CSM atlas）可视化的装配与驱动（编辑器排障面 DBG-03，
 * Engine_Shadow_Quality_Design §4.7）。
 *
 * <p><b>两视图</b>：①原始深度灰度（远=白、遮挡体=暗块，看纹素吸附/漏光/裁切）；
 * ②级联分色（2×2 象限各一色，看级联覆盖与接缝）。入口 = 菜单「工具 | 阴影图视图」
 * 三项单选 + F6 边沿循环；数值行落 {@link #HINT_ROW}（级联数 / atlas 版面 / 边长）。</p>
 *
 * <p>该视口没有阴影资源（关阴影 / 无主光 / 未建）时视图不进图、写「未就绪」、菜单置灰。</p>
 */
public class ShadowMapDebugAppState extends AbsEditorAppState {

    private static final Logger log = LoggerFactory.getLogger(ShadowMapDebugAppState.class);

    public static final int MODE_OFF = 0;
    public static final int MODE_DEPTH = 1;
    public static final int MODE_CASCADE = 2;

    /** 提示行：RenderStats 0~9、G-Buffer 视图 10、DDGI 探针视图 11，本视图落 12 */
    private static final int HINT_ROW = 12;

    private static volatile int desiredMode = MODE_OFF;
    /** 阴影资源就绪标记（引擎帧刷新，供菜单 enabled() 在 EDT 判灰） */
    private static volatile boolean ready;

    public static int getMode() {
        return desiredMode;
    }

    public static void requestMode(int mode) {
        desiredMode = (mode < MODE_OFF || mode > MODE_CASCADE) ? MODE_OFF : mode;
    }

    public static boolean isReady() {
        return ready;
    }

    private String targetView;
    private ShadowMapViewPass pass;
    private boolean inPipeline;
    private int appliedMode = MODE_OFF;

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        super.onAttach(stateManager, engine);
        Camera editorCamera = javelinEditor.getEditorCamera();
        if (editorCamera == null || editorCamera.getNode() == null) {
            log.warn("编辑器相机未就绪，跳过阴影图视图装配（ShadowMapDebugAppState）");
            return;
        }
        targetView = editorCamera.getNode().getName();
    }

    @Override
    public void update(float deltaTime) {
        RenderPipeline pipeline = engine.renderPipeline();
        if (pipeline == null || targetView == null) {
            return;
        }
        if (Input.wasPressed(KeyCode.F6)) {
            log.info("F6");
            requestMode(nextMode(desiredMode));
        }
        RenderingSystem rs = engine.renderingSystem();
        ready = rs != null && rs.shadowMapOrNull(targetView) != null;
        reconcile(pipeline);
        refreshStatusRow(rs);
    }

    private void reconcile(RenderPipeline pipeline) {
        int desired = desiredMode;
        if (desired == MODE_OFF) {
            if (pass != null) {
                pass.setMode(MODE_OFF);
            }
            detachPass(pipeline);
            return;
        }
        if (!ready && appliedMode == MODE_OFF) {
            detachPass(pipeline);
            return;
        }
        if (desired == appliedMode) {
            return;
        }
        if (!ensurePass()) {
            appliedMode = MODE_OFF;
            return;
        }
        pass.setMode(desired);
        if (!inPipeline) {
            pipeline.addPass(pass);
            inPipeline = true;
        }
        appliedMode = desired;
        log.info("阴影图视图: {}", desired == MODE_CASCADE ? "cascadeTint" : "rawDepth");
    }

    private boolean ensurePass() {
        if (pass != null) {
            return true;
        }
        try {
            pass = new ShadowMapViewPass(engine.device(), engine.getAssetManager(),
                    engine.renderingSystem(), targetView);
            return true;
        } catch (RuntimeException e) {
            log.error("阴影图视图装配失败（ShadowMapViewPass 创建/着色器装载）", e);
            showHint(EditorLang.text("shadowView.loadFailed"));
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

    /** 状态行：级联数 / atlas 版面（grid × 象限边长）/ 主光是否投影 */
    private void refreshStatusRow(RenderingSystem rs) {
        if (desiredMode == MODE_OFF) {
            DebugText.clear(HINT_ROW);
            return;
        }
        if (rs == null || rs.shadowMapOrNull(targetView) == null) {
            showHint(EditorLang.text("tools.shadowView.notReady"));
            return;
        }
        int cascades = rs.shadowCascadeCount(targetView);
        int grid = Math.max(1, rs.shadowAtlasGrid(targetView));
        int atlasSize = rs.shadowMapOrNull(targetView).desc().width();
        showHint(EditorLang.text("tools.shadowView.status",
                cascades, grid + "x" + grid, atlasSize,
                rs.shadowSunActive() ? EditorLang.text("tools.shadowView.sunOn")
                        : EditorLang.text("tools.shadowView.sunOff")));
    }

    private static int nextMode(int mode) {
        return mode >= MODE_CASCADE ? MODE_OFF : mode + 1;
    }

    private static void showHint(String text) {
        DebugText.clear(HINT_ROW);
        DebugText.printf(HINT_ROW, text,
                DebugTextColor.attr(DebugTextColor.YELLOW, DebugTextColor.BLACK));
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

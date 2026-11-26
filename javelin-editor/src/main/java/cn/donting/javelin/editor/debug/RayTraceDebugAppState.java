package cn.donting.javelin.editor.debug;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.appstate.AppStateManager;
import cn.donting.javelin.core.debug.DebugText;
import cn.donting.javelin.core.debug.DebugTextColor;
import cn.donting.javelin.core.input.Input;
import cn.donting.javelin.core.input.KeyCode;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.pipeline.RenderPipeline;
import cn.donting.javelin.core.render.rt.RayTracingScene;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.editor.appstate.AbsEditorAppState;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.rhi.Tlas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 光追求交可视化装配与驱动（P4-06 B3）。
 *
 * <p>入口 = F9 开关（菜单项后置）；开启后把 {@link RayTraceViewPass} 追加进渲染管线，
 * 数值行（第 14 行）报「实例数 / 距离图尺寸 / 求交是否就绪」。设备未启用 ray query 或
 * 场景未建时写「未就绪」，本视图不进图。</p>
 *
 * <p>F10 = 光追阴影混合档循环（0 / 25 / 50 / 75 / 100 %）：0 走纯 CSM、100 全替换，
 * 中间值与 CSM 按比例混合（P4-06 B6）。F11 = 光追漫反射 GI 混合档（0 = 常量/DDGI 环境项，
 * 100 = 全替换），B7。F12 = 镜面反射强度档（0 = 无环境镜面，100 = 全量），B9——反射是 PBR
 * 特有的消费端，Lambert/Unlit 材质不受该档影响。</p>
 */
public class RayTraceDebugAppState extends AbsEditorAppState {

    private static final Logger log = LoggerFactory.getLogger(RayTraceDebugAppState.class);

    public static final int MODE_OFF = 0;
    /** 相机主射线（几何/自交对照） */
    public static final int MODE_CAMERA = 1;
    /** 主光方向射线（与 CSM 阴影轮廓对照） */
    public static final int MODE_LIGHT = 2;
    /** CSM 对照（P4-06 B4）：RT 遮挡 vs CSM 遮挡 → 红绿差异图 */
    public static final int MODE_COMPARE = 3;

    /** 提示行：RenderStats 0~9、G-Buffer 10、DDGI 11、阴影图 12、光照 gizmo 13，本视图 14 */
    private static final int HINT_ROW = 14;

    private static volatile int desiredMode = MODE_OFF;
    /** 光追能力就绪（设备启用 ray query；供菜单 enabled() 在 EDT 判灰） */
    private static volatile boolean ready;

    public static int getMode() {
        return desiredMode;
    }

    public static void requestMode(int mode) {
        desiredMode = (mode < MODE_OFF || mode > MODE_COMPARE) ? MODE_OFF : mode;
    }

    public static boolean isReady() {
        return ready;
    }

    private String targetView;
    private RayTraceViewPass pass;
    private boolean inPipeline;
    private int appliedMode = MODE_OFF;

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        super.onAttach(stateManager, engine);
        Camera editorCamera = javelinEditor.getEditorCamera();
        if (editorCamera == null || editorCamera.getNode() == null) {
            log.warn("编辑器相机未就绪，跳过光追求交视图装配（RayTraceDebugAppState）");
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
        if (Input.wasPressed(KeyCode.F9)) {
            requestMode(nextMode(desiredMode));
        }
        RenderingSystem rs = engine.renderingSystem();
        // F10：光追阴影混合档循环 0 → 25 → 50 → 75 → 100（%）。0 = 纯 CSM，100 = 全替换，
        // 中间值即两路混合——用来在不改代码的前提下做 A/B
        if (Input.wasPressed(KeyCode.F10) && rs != null) {
            int next = switch (rs.rtShadowMode()) {
                case 0 -> 25;
                case 25 -> 50;
                case 50 -> 75;
                case 75 -> 100;
                default -> 0;
            };
            rs.setRtShadowMode(next);
            log.info("光追阴影混合档: {}%（0 = 纯 CSM，100 = 全替换）", next);
        }
        // F11：光追 GI 混合档循环（同上口径）。0 = 环境项走常量/DDGI，100 = 全替换
        if (Input.wasPressed(KeyCode.F11) && rs != null) {
            int next = switch (rs.rtgiMode()) {
                case 0 -> 25;
                case 25 -> 50;
                case 50 -> 75;
                case 75 -> 100;
                default -> 0;
            };
            rs.setRtgiMode(next);
            log.info("光追 GI 混合档: {}%（0 = 常量/DDGI 环境项，100 = 全替换）", next);
        }
        // F12：光追反射强度循环（0 = 无环境镜面，100 = 全量；PBR 直接光高光不受影响）
        if (Input.wasPressed(KeyCode.F12) && rs != null) {
            int next = switch (rs.rtReflectMode()) {
                case 0 -> 25;
                case 25 -> 50;
                case 50 -> 75;
                case 75 -> 100;
                default -> 0;
            };
            rs.setRtReflectMode(next);
            log.info("光追反射档: {}%（0 = 无环境镜面，100 = 全量）", next);
        }
        ready = engine.device().rayQueryEnabled() && rs != null && rs.rayTracingScene() != null;
        reconcile(pipeline);
        refreshStatusRow(rs);
    }

    private void reconcile(RenderPipeline pipeline) {
        int desired = desiredMode;
        if (desired == MODE_OFF) {
            if (pass != null) {
                pass.setEnabled(false);
            }
            detachPass(pipeline);
            appliedMode = MODE_OFF;
            return;
        }
        if (!ready) {
            detachPass(pipeline);
            appliedMode = MODE_OFF;
            return;
        }
        if (desired == appliedMode) {
            return;
        }
        if (!ensurePass()) {
            appliedMode = MODE_OFF;
            return;
        }
        pass.setEnabled(true);
        pass.setMode(switch (desired) {
            case MODE_LIGHT -> RayTraceViewPass.MODE_LIGHT;
            case MODE_COMPARE -> RayTraceViewPass.MODE_COMPARE;
            default -> RayTraceViewPass.MODE_CAMERA;
        });
        if (!inPipeline) {
            pipeline.addPass(pass);
            inPipeline = true;
        }
        appliedMode = desired;
        log.info("光追求交视图: {}", switch (desired) {
            case MODE_LIGHT -> "lightRay";
            case MODE_COMPARE -> "csmCompare";
            default -> "cameraRay";
        });
    }

    private boolean ensurePass() {
        if (pass != null) {
            return true;
        }
        try {
            pass = new RayTraceViewPass(engine.device(), engine.getAssetManager(),
                    engine.renderingSystem(), targetView);
            return true;
        } catch (RuntimeException e) {
            log.error("光追求交视图装配失败（RayTraceViewPass 创建/着色器装载）", e);
            showHint(EditorLang.text("rayTraceView.loadFailed"));
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

    private void refreshStatusRow(RenderingSystem rs) {
        if (desiredMode == MODE_OFF) {
            DebugText.clear(HINT_ROW);
            return;
        }
        if (!ready || rs == null || rs.rayTracingScene() == null) {
            showHint(EditorLang.text("tools.rayTrace.notReady"));
            return;
        }
        RayTracingScene scene = rs.rayTracingScene();
        Tlas tlas = scene.tlas();
        showHint(EditorLang.text("tools.rayTrace.status",
                scene.instanceCount(),
                tlas == null ? 0 : tlas.capacity()));
    }

    private static int nextMode(int mode) {
        return mode >= MODE_COMPARE ? MODE_OFF : mode + 1;
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

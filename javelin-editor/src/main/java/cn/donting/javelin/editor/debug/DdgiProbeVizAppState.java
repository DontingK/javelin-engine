package cn.donting.javelin.editor.debug;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.appstate.AppStateManager;
import cn.donting.javelin.core.debug.DebugText;
import cn.donting.javelin.core.debug.DebugTextColor;
import cn.donting.javelin.core.input.Input;
import cn.donting.javelin.core.input.KeyCode;
import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.physics.collision.Aabb;
import cn.donting.javelin.core.render.ddgi.DdgiProbeGrid;
import cn.donting.javelin.core.render.ddgi.DdgiProbePass;
import cn.donting.javelin.core.render.pipeline.RenderPipeline;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.settings.DdgiSettings;
import cn.donting.javelin.editor.appstate.AbsEditorAppState;
import cn.donting.javelin.editor.plugin.EditorLang;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DDGI 探针可视化的装配与驱动（编辑器排障面 dbg-3，Engine_DDGI_ProbeViz_Design §3）。
 *
 * <p><b>三视图</b>：①网格盒 + 探针十字（世界空间，走 {@link DebugDrawLine}，由
 * {@link DebugLineAppState} 每帧 clear 后调 {@link #emitGridLines(JavelinEngine)} 统一提交
 * ——本状态不自行 clear，避免清掉选中绿框）；②atlas 直显（{@link DdgiProbeViewPass}，
 * 覆盖制贴屏）；③数值行（{@link #HINT_ROW}）。</p>
 *
 * <p><b>入口</b>：顶部菜单「工具 | DDGI 探针视图」四项单选（菜单在 EDT 写静态意图
 * {@link #requestMode(int)}，本状态引擎帧消费）+ F5 边沿循环快捷键。</p>
 *
 * <p><b>未就绪</b>：DDGI 关 / 体素场未建 / atlas 未建时视图不进图、数值行写「未就绪」，
 * 菜单对应项置灰（{@link #isGridReady()} / {@link #isAtlasReady()}，引擎帧刷新）。</p>
 */
public class DdgiProbeVizAppState extends AbsEditorAppState {

    private static final Logger log = LoggerFactory.getLogger(DdgiProbeVizAppState.class);

    public static final int MODE_OFF = 0;
    public static final int MODE_GRID = 1;
    public static final int MODE_IRRADIANCE = 2;
    public static final int MODE_DEPTH = 3;

    /** 提示行：RenderStats 占 0~9，GBuffer 视图占 10，本视图落 11 */
    private static final int HINT_ROW = 11;

    /** 网格盒颜色（探针十字同色；与选中门控的绿/黄区分） */
    private static final Color GRID_COLOR = Color.fromBytes(0, 220, 255, 255);

    /** 探针十字线数预算（DebugLineBatch 8192 线，留一半给选中 drawers） */
    private static final int CROSS_BUDGET = 2000;

    /** 意图模式（菜单 EDT / F5 写、update 消费；与 GBufferDebugAppState 同口径） */
    private static volatile int desiredMode = MODE_OFF;
    /** 数据面就绪标记（引擎帧刷新，供菜单 enabled() 在 EDT 判灰） */
    private static volatile boolean gridReady;
    private static volatile boolean atlasReady;

    private static final Vector3f FROM = new Vector3f();
    private static final Vector3f TO = new Vector3f();

    public static int getMode() {
        return desiredMode;
    }

    public static void requestMode(int mode) {
        desiredMode = (mode < MODE_OFF || mode > MODE_DEPTH) ? MODE_OFF : mode;
    }

    /** 网格视图是否就绪（探针网格已建立） */
    public static boolean isGridReady() {
        return gridReady;
    }

    /** atlas 视图是否就绪（两张 atlas 已建立） */
    public static boolean isAtlasReady() {
        return atlasReady;
    }

    /** 目标视口（编辑器相机视口名） */
    private String targetView;
    private DdgiProbeViewPass pass;
    private boolean inPipeline;
    private int appliedMode = MODE_OFF;

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        super.onAttach(stateManager, engine);
        Camera editorCamera = javelinEditor.getEditorCamera();
        if (editorCamera == null || editorCamera.getNode() == null) {
            log.warn("编辑器相机未就绪，跳过 DDGI 探针视图装配（DdgiProbeVizAppState）");
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
        if (Input.wasPressed(KeyCode.F5)) {
            requestMode(nextMode(desiredMode));
        }
        refreshReady();
        reconcile(pipeline);
        refreshStatusRow();
    }

    /** 就绪态刷新（每帧一次，供菜单 enabled() 与 reconcile 用） */
    private void refreshReady() {
        DdgiProbePass probe = engine.ddgiProbePass();
        gridReady = probe != null && probe.grid() != null;
        atlasReady = probe != null && probe.atlases() != null;
    }

    /**
     * 意图 → 实际：变化才挂/卸 Pass。DDGI 未就绪时不挂（保持意图，写「未就绪」行；
     * 数据面就绪后下一帧自动生效）。
     */
    private void reconcile(RenderPipeline pipeline) {
        int desired = desiredMode;
        if (desired == MODE_OFF) {
            if (pass != null) {
                pass.setMode(MODE_OFF);
            }
            detachPass(pipeline);
            return;
        }
        boolean ready = desired == MODE_GRID ? gridReady : atlasReady;
        if (!ready && appliedMode == MODE_OFF) {
            // 只在「还没挂过」时门槛拦一下；已挂的 Pass 在 atlas 换代空窗期单帧跳过（addToGraph
            // 自带 null 判定），不反复摘挂——否则网格换代那几帧会 churn
            if (pass != null) {
                pass.setMode(MODE_OFF);
            }
            detachPass(pipeline);
            return;
        }
        if (desired == appliedMode) {
            return;
        }
        if (desired != MODE_GRID) {
            if (!ensurePass()) {
                appliedMode = MODE_OFF;
                return;
            }
            pass.setMode(desired);
            if (!inPipeline) {
                pipeline.addPass(pass);
                inPipeline = true;
            }
        } else if (pass != null) {
            pass.setMode(MODE_OFF);   // 网格视图只用线框：直显 Pass 收起
            detachPass(pipeline);
        }
        appliedMode = desired;
        log.info("DDGI 探针视图: {}", label(desired));
    }

    private boolean ensurePass() {
        if (pass != null) {
            return true;
        }
        try {
            pass = new DdgiProbeViewPass(engine.device(), engine.getAssetManager(), engine,
                    targetView);
            return true;
        } catch (RuntimeException e) {
            log.error("DDGI 探针视图装配失败（DdgiProbeViewPass 创建/着色器装载）", e);
            showHint(EditorLang.text("ddgiProbeView.loadFailed"));
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

    /** 状态行：网格 / atlas 尺寸 / 首圈 / 十字降采样（每帧重写本行） */
    private void refreshStatusRow() {
        if (desiredMode == MODE_OFF) {
            DebugText.clear(HINT_ROW);
            return;
        }
        DdgiProbePass probe = engine.ddgiProbePass();
        DdgiProbeGrid grid = probe == null ? null : probe.grid();
        if (grid == null) {
            showHint(EditorLang.text("tools.ddgiProbeView.notReadyGrid"));
            return;
        }
        if (desiredMode != MODE_GRID && (probe.atlases() == null)) {
            showHint(EditorLang.text("tools.ddgiProbeView.notReadyAtlas"));
            return;
        }
        DdgiSettings settings = engine.renderingSystem().ddgiSettings();
        String text = EditorLang.text("tools.ddgiProbeView.status",
                grid.countX() + "x" + grid.countY() + "x" + grid.countZ(),
                String.format(java.util.Locale.ROOT, "%.2f", grid.spacing()),
                String.valueOf(grid.probeCount()),
                probe.atlases() == null ? "-" : (grid.irradianceAtlasWidth()
                        + "x" + grid.irradianceAtlasHeight()),
                probe.atlases() == null ? "-" : (grid.depthAtlasWidth()
                        + "x" + grid.depthAtlasHeight()),
                probe.completedSweeps() > 0 ? EditorLang.text("tools.ddgiProbeView.swept")
                        : EditorLang.text("tools.ddgiProbeView.sweeping"),
                downStep(grid),
                settings == null ? 0 : settings.getProbesPerFrame());
        showHint(text);
    }

    /** 十字降采样步长：探针数超过预算时按整数步长抽取 */
    private static int downStep(DdgiProbeGrid grid) {
        int total = grid.probeCount();
        int step = 1;
        while (total / step > CROSS_BUDGET) {
            step++;
        }
        return step;
    }

    /**
     * V1 网格线框提交（世界空间）：网格盒 12 边 + 探针三轴十字。
     *
     * <p>调用方是 {@link DebugLineAppState}——它在每帧 {@code DebugDrawLine.clear()} 之后、
     * 提交选中 drawers 之前调本方法（**本方法不得自行 clear**，否则会清掉选中绿框）。
     * 探针数超 {@link #CROSS_BUDGET} 时按整数步长抽取并在状态行标注 1/N。</p>
     */
    public static void emitGridLines(JavelinEngine engine) {
        if (desiredMode != MODE_GRID || engine == null) {
            return;
        }
        DdgiProbePass probe = engine.ddgiProbePass();
        DdgiProbeGrid grid = probe == null ? null : probe.grid();
        if (grid == null) {
            return;
        }
        Vector3f min = new Vector3f(grid.originX(), grid.originY(), grid.originZ());
        Vector3f max = new Vector3f(min).add(grid.extentX(), grid.extentY(), grid.extentZ());
        DebugDrawLine.aabb(new Aabb(min, max), GRID_COLOR);

        int step = downStep(grid);
        float arm = grid.spacing() * 0.1f;
        float[] p = new float[3];
        for (int i = 0; i < grid.probeCount(); i += step) {
            grid.probePosition(i, p);
            FROM.set(p[0], p[1], p[2]);
            TO.set(p[0] + arm, p[1], p[2]);
            DebugDrawLine.line(FROM, TO, GRID_COLOR);
            TO.set(p[0], p[1] + arm, p[2]);
            DebugDrawLine.line(FROM, TO, GRID_COLOR);
            TO.set(p[0], p[1], p[2] + arm);
            DebugDrawLine.line(FROM, TO, GRID_COLOR);
        }
    }

    private static int nextMode(int mode) {
        return mode >= MODE_DEPTH ? MODE_OFF : mode + 1;
    }

    private static String label(int mode) {
        return switch (mode) {
            case MODE_GRID -> "grid";
            case MODE_IRRADIANCE -> "irradianceAtlas";
            case MODE_DEPTH -> "depthAtlas";
            default -> "off";
        };
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

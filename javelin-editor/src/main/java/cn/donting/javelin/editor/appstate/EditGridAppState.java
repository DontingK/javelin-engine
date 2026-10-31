package cn.donting.javelin.editor.appstate;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.appstate.AppStateManager;
import cn.donting.javelin.core.render.material.Material;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.render.material.SurfaceType;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.Scene;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.MeshRenderer;
import cn.donting.javelin.editor.mesh.GridMesh;
import cn.donting.javelin.editor.scene.EditorScene;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 编辑器网格辅助线 AppState（engine2 EditGridAppState 移植，2026-09-26）。
 *
 * <p>三层网格（step = 1 / 10 / 100，同尺寸不同分割数）按编辑器相机高度交叉淡入淡出
 * ——相机越低显示越细的网格，拉高后细网格淡出、粗网格淡入；同时各层的径向淡出范围
 * （以相机为中心的雾式渐隐）随相机高度同步缩放，防止远处细网格产生摩尔纹。</p>
 *
 * <p>engine5 适配：着色器拿不到相机世界位置（注入的相机块仅含 viewProj），
 * 相机位置与淡出半径经材质数值属性每帧下发（用户拍板 2026-09-26：材质属性方案，
 * 不动核心 UBO1 布局）——渲染侧每帧重打包材质数值块，运行期 setColor/setFloat 即时生效。</p>
 *
 * <p>编辑器场景无相机时 update 安全跳过（编辑器相机独立任务，用户拍板 2026-09-26）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class EditGridAppState extends AbsEditorAppState {

    private static final Logger log = LoggerFactory.getLogger(EditGridAppState.class);

    /**
     * 内置网格着色器 loadAsset 路径（builtin-asset-editor/shader/editor-grid.shader
     * 释放到 .javelin/assets 后的相对路径；用户拍板 2026-09-26：内置资产按路径加载，不按 GUID）
     */
    private static final String GRID_SHADER_PATH = "editor/shader/editor-grid.shader";

    /** 网格层数（step = 1 / 10 / 100） */
    private static final int LAYER_COUNT = 3;

    /** 网格总尺寸（三层共用；2026-09-27 用户实测反馈 2000 高度 400 露边 → 扩 4 倍） */
    private static final float GRID_SIZE = 8000.0f;

    /** 各层格子步长（日志/节点名用） */
    private static final int[] GRID_STEPS = {1, 10, 100};

    /** 三层网格分割数（线距 = GRID_SIZE / divisions） */
    private static final int[] DIVISIONS = {8000, 800, 80};

    /**
     * 层间过渡高度阈值（2026-09-27 屏上调参台实测定稿后固化，调参台已拆除）：
     * {1, 80, 600}——T0=1 为用户实测定稿（低空即进入层 1→2 交叉）；
     * 口径：淡出完成点 ≈ 8px、满显峰值屏内 6~9 格（FOV 60°、屏高 720 下
     * 1 世界单位 ≈ 624/h px）：1 米格 [T0,T1] 淡出，10 米格 T1 满显、
     * [T1,T2] 淡出，100 米格 T2 满显。
     */
    private static final float[] FADE_THRESHOLDS = {1.0f, 80.0f, 600.0f};

    /** 径向淡出起始系数（fadeStart = max(10, min(fadeEnd-100, cameraY × 本系数))） */
    private static final float FADE_START_FACTOR = 2.0f;

    /** 径向淡出结束系数（fadeEnd = max(50, min(FADE_END_CLIP, cameraY × 本系数))） */
    private static final float FADE_END_FACTOR = 6.0f;

    /** 径向淡出上限（网格半径 1000 内收 50：高空也不露网格硬边） */
    private static final float FADE_END_CLIP = GRID_SIZE * 0.5f - 50.0f;

    /** 径向淡出最小起始半径 */
    private static final float FADE_START_MIN = 10.0f;

    /** 径向淡出最小结束半径 */
    private static final float FADE_END_MIN = 50.0f;

    /** 网格颜色（灰，各层相同，透明度三层各异） */
    private static final float GRID_R = 0.5f;
    private static final float GRID_G = 0.5f;
    private static final float GRID_B = 0.5f;

    /** 三层网格节点（onDetach 时从编辑器场景摘除） */
    private final Node[] gridNodes = new Node[LAYER_COUNT];

    /** 三层网格材质（透明度/淡出参数每帧更新） */
    private final Material[] materials = new Material[LAYER_COUNT];

    /** 相机世界位置复用向量（每帧写入材质前暂存，避免分配） */
    private final Vector3f camWorldPos = new Vector3f();
    /** 首帧 update 一次性诊断（数据侧读数口，常驻） */
    private boolean updateLogged;

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        super.onAttach(stateManager, engine);
        GameScene editorGameScene = resolveEditorScene();
        if (editorGameScene == null) {
            log.warn("编辑器场景未就绪，跳过网格创建（EditGridAppState）");
            return;
        }
        Shader shader = javelinEditor.getAssetManager().loadAsset(GRID_SHADER_PATH, Shader.class);
        if (shader == null) {
            log.error("网格着色器加载失败 path={}（Library 缺产物或索引未建），编辑器网格不可用",
                    GRID_SHADER_PATH);
            return;
        }

        for (int i = 0; i < LAYER_COUNT; i++) {
            Node node = new Node();
            node.setName("EditorGrid-" + GRID_STEPS[i]);
            MeshRenderer renderer = node.addComponent(MeshRenderer.class);
            renderer.setMesh(GridMesh.build(GRID_SIZE, DIVISIONS[i]));
            renderer.setCastShadow(false);
            materials[i] = new Material(shader);
            // 网格淡出走 alpha 混合：面型显式声明 TRANSPARENT
            // （透明性由材质裁决，拍板 2026-10-17 方案 B，不再看 Shader 混合态）
            materials[i].setSurfaceType(SurfaceType.TRANSPARENT);
            renderer.setMaterial(materials[i]);
            gridNodes[i] = node;
            editorGameScene.add(node);
        }
        log.info("编辑器网格已创建（{} 层，尺寸 {}）", LAYER_COUNT, GRID_SIZE);
    }

    @Override
    public void onDetach(AppStateManager stateManager) {
        // 先摘节点再 super（super 置空 engine，之后无法访问场景）
        GameScene editorGameScene = resolveEditorScene();
        if (editorGameScene != null) {
            for (int i = 0; i < LAYER_COUNT; i++) {
                if (gridNodes[i] != null && gridNodes[i].getScene() == editorGameScene) {
                    editorGameScene.remove(gridNodes[i]);
                }
                gridNodes[i] = null;
                materials[i] = null;
            }
        }
        super.onDetach(stateManager);
    }

    @Override
    public void update(float tpf) {
        List<Camera> cameras = editorCameras();
        if (cameras == null || cameras.isEmpty()) {
            return;
        }
        // 编辑器网格取编辑器场景首相机（编辑器约定单视角 SCENE 模式）
        Camera camera = cameras.get(0);
        camera.getNode().getTransform().getWorldPosition(camWorldPos);
        float cameraY = camWorldPos.y;

        // 径向淡出范围随相机高度缩放：近处实线、远处渐隐；
        // end 夹紧在网格边缘内收 50（高空不露硬边），start 再夹在 end 前 100 以内
        // （高度较高后 y×起系数会追上夹紧的 end，须防 start ≥ end 倒挂）
        float fadeEnd = Math.max(FADE_END_MIN, Math.min(FADE_END_CLIP, cameraY * FADE_END_FACTOR));
        float fadeStart = Math.max(FADE_START_MIN, Math.min(fadeEnd - 100.0f, cameraY * FADE_START_FACTOR));

        // 三层 alpha：细网格随高度淡出，粗网格交叉淡入
        float t01 = clamp01((cameraY - FADE_THRESHOLDS[0]) / Math.max(1.0f, FADE_THRESHOLDS[1] - FADE_THRESHOLDS[0]));
        float t12 = clamp01((cameraY - FADE_THRESHOLDS[1]) / Math.max(1.0f, FADE_THRESHOLDS[2] - FADE_THRESHOLDS[1]));
        float[] alphas = {1.0f - t01, t01 * (1.0f - t12), t12};

        if (!updateLogged) {
            updateLogged = true;
            log.info("首帧网格 update: 相机位姿=({},{},{}) cameraY={} alpha=({},{},{}) fadeStart={} fadeEnd={}",
                    camWorldPos.x, camWorldPos.y, camWorldPos.z, cameraY,
                    alphas[0], alphas[1], alphas[2], fadeStart, fadeEnd);
        }

        for (int i = 0; i < LAYER_COUNT; i++) {
            if (materials[i] == null) {
                return;
            }
            materials[i].setColor("_GridColor", GRID_R, GRID_G, GRID_B, alphas[i]);
            materials[i].setFloat("_FadeStart", fadeStart);
            materials[i].setFloat("_FadeEnd", fadeEnd);
            materials[i].setColor("_CamPos", camWorldPos.x, camWorldPos.y, camWorldPos.z, 0.0f);
        }
    }

    /** 编辑器场景（EditorScene 内的编辑 GameScene）；场景未就绪返回 null */
    private GameScene resolveEditorScene() {
        if (engine == null) {
            return null;
        }
        Scene scene = engine.scene();
        if (scene instanceof EditorScene editorScene) {
            return editorScene.getEditorScene();
        }
        return null;
    }

    /** 编辑器场景相机注册表（无编辑器场景时返回 null，update 安全跳过用） */
    private List<Camera> editorCameras() {
        GameScene editorGameScene = resolveEditorScene();
        return editorGameScene != null ? editorGameScene.getCameras() : null;
    }

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }
}

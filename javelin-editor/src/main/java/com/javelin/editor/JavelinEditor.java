package com.javelin.editor;

import com.javelin.core.JavelinEngine;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.appstate.AppStateManager;
import com.javelin.core.math.Color;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.Scene;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.Transform;
import com.javelin.editor.appstate.EditGridAppState;
import com.javelin.editor.appstate.EditorCameraAppState;
import com.javelin.editor.appstate.SceneWatchAppState;
import com.javelin.editor.menu.EditorMenuSetup;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.window.Window;
import com.javelin.editor.asset.BuiltinAssetReleaser;
import com.javelin.editor.importer.AssetImportPipeline;
import com.javelin.editor.meta.MetaManager;
import com.javelin.editor.project.Project;
import com.javelin.editor.scene.EditorScene;
import com.javelin.editor.serializable.TextImporter;
import com.javelin.editor.settings.EditorSettings;
import com.javelin.editor.settings.ProjectSettings;
import com.javelin.editor.settings.SettingsManager;
import com.javelin.editor.window.cef.MainFrame;
import com.javelin.editor.window.cef.ProjectFrame;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class JavelinEditor extends JavelinEngine {

    private static final Logger log = LoggerFactory.getLogger(JavelinEditor.class);

    /** 当前编辑器实例（桥方法/命令等无状态 POJO 的实例入口，对齐 MainFrame.getInstance 先例） */
    private static JavelinEditor instance;

    private final JavelinSetting setting;
    private final MetaManager metaManager;
    private final AssetManager assetManager;
    private final AssetImportPipeline assetImportPipeline;
    private final BuiltinAssetReleaser builtinAssetReleaser;
    private final SettingsManager settingsManager;
    private final Project project;
    private final EditorScene editorScene;
    private Camera editorCamera;
    public JavelinEditor(Project project,
                         JavelinSetting setting,
                         MetaManager metaManager,
                         AssetManager assetManager,
                         AssetImportPipeline assetImportPipeline,
                         BuiltinAssetReleaser builtinAssetReleaser) {
        super(setting);
        instance = this;
        this.setting = setting;
        this.project = project;
        this.metaManager = metaManager;
        this.assetManager = assetManager;
        this.assetImportPipeline = assetImportPipeline;
        this.builtinAssetReleaser = builtinAssetReleaser;
        this.settingsManager = new SettingsManager(project);
        this.editorScene = new EditorScene();
    }

    @Override
    protected AssetManager createAssetManager() {
        return this.assetManager;
    }

    @Override
    protected Window createWindow() {
        return new MainFrame(setting);
    }

    @Override
    protected void onInitialize() {
        AppStateManager stateManager = appStateSubsystem.getStateManager();
        GameScene editorGameScene = getEditorGameScene();
        EditGridAppState gridState = null;
        if (editorGameScene == null) {
            // loadScene 失败（场景文件缺失/解析错）时编辑子场景未建：跳过编辑器装配，帧循环退化为清屏
            log.warn("编辑器场景未就绪（loadScene 失败），跳过编辑器相机与网格装配");
        } else {
            Node node = new Node();
            node.setName("Editor Camera Node");
            editorCamera = node.addComponent(Camera.class);
            // 编辑器观察网格 ±4000（GRID_SIZE 8000）：默认 far=1000 会把远端地面裁掉
            //（用户高空实测"看到边界"的真凶——非着色器淡出），拉到 10000 覆盖俯瞰距离
            editorCamera.setFar(10000.0f);
            // 斜俯视位姿：原点上方后侧 45° 低头看向原点（applyPose 走 transformDirection
            // 正向提取，rotationX 符号即标准右手直觉）
            Transform transform = node.getTransform();
            transform.setLocalPosition(new Vector3f(0, 30, -30));
            transform.setLocalRotation(new Quaternionf().rotationX((float) Math.toRadians(45)));
            editorGameScene.add(node);

            gridState = new EditGridAppState();
            stateManager.attach(gridState);
            // 编辑器相机控制（Unity Scene 视图导航口径，见 EditorCameraAppState 类注释）
            stateManager.attach(new EditorCameraAppState());
        }
        // 场景变更检测（快照 diff：写入方零通知义务，增删/挪动/改名自动发现推送）
        stateManager.attach(new SceneWatchAppState());
        // 菜单/命令装配（场景无关：DevTools 恒可用；场景未就绪时网格开关安全 no-op）
        EditorMenuSetup.setup(stateManager, gridState);
    }


    public MetaManager getMetaManager() {
        return metaManager;
    }

    public AssetManager getAssetManager() {
        return assetManager;
    }

    @Override
    protected Scene loadScene() {
        ProjectSettings projectSettings = settingsManager.getProjectSettings();
        String defaultOpenScene = projectSettings.getDefaultOpenScene();
        File file = new File(project.getAssetsDir(), defaultOpenScene);

        TextImporter textImporter = new TextImporter(file, assetManager);
        try {
            GameScene gameScene = textImporter.importData(GameScene.class);
            editorScene.setGameScene(gameScene, file);
            editorScene.setEditorScene(new GameScene("editor"));
            return editorScene;
        } catch (Exception ex) {
            log.warn(ex.getMessage(), ex);
            return null;
        }
    }

    public GameScene getEditorGameScene() {
        return editorScene.getEditorScene();
    }

    /**
     * 当前编辑器实例（null = 未创建，桥方法转「未就绪」语义用）。
     */
    public static JavelinEditor getInstance() {
        return instance;
    }

    /**
     * 编辑器场景容器（用户场景/编辑子场景/脏标记的持有者——场景树与编辑命令的入口）。
     */
    public EditorScene getEditorScene() {
        return editorScene;
    }

    public Camera getEditorCamera() {
        return editorCamera;
    }

    public static void main(String[] args) {
        ProjectFrame projectWindow = new ProjectFrame();
        projectWindow.setSize(1280, 720);
        projectWindow.setVisible(true);
    }
}

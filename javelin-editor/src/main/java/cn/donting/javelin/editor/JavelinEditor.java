package cn.donting.javelin.editor;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.appstate.AppStateManager;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.Scene;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.editor.appstate.EditGridAppState;
import cn.donting.javelin.editor.appstate.EditorCameraAppState;
import cn.donting.javelin.editor.appstate.SceneWatchAppState;
import cn.donting.javelin.editor.appstate.TransformWatchAppState;
import cn.donting.javelin.editor.appstate.ViewportPickAppState;
import cn.donting.javelin.editor.appstate.gizmo.TransformGizmoAppState;
import cn.donting.javelin.editor.debug.DebugLineAppState;
import cn.donting.javelin.core.render.picking.PickSystem;
import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.core.window.Window;
import cn.donting.javelin.editor.asset.BuiltinAssetReleaser;
import cn.donting.javelin.editor.asset.ProjectAssetLibManager;
import cn.donting.javelin.editor.asset.reload.AssetReloadService;
import cn.donting.javelin.editor.asset.reload.EditorObjectRegistry;
import cn.donting.javelin.editor.importer.AssetImportPipeline;
import cn.donting.javelin.editor.i18n.I18n;
import cn.donting.javelin.editor.meta.AssetMeta;
import cn.donting.javelin.editor.meta.MetaManager;
import cn.donting.javelin.editor.project.Project;
import cn.donting.javelin.editor.scene.EditorScene;
import cn.donting.javelin.editor.serializable.TextImporter;
import cn.donting.javelin.editor.settings.EditorSettings;
import cn.donting.javelin.editor.settings.ProjectSettings;
import cn.donting.javelin.editor.settings.SettingsManager;
import cn.donting.javelin.editor.swing.EditorTheme;
import cn.donting.javelin.editor.window.MainFrame;
import cn.donting.javelin.editor.window.ProjectFrame;
import javax.swing.SwingUtilities;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

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
    /** GPU ID-buffer 拾取系统（场景未就绪时为 null——ViewportPickAppState 同口径安全跳过） */
    private PickSystem pickSystem;
    /** 编辑器相机的渲染视口名（SceneRenderBridge 以相机节点名注册，拾取系统目标视口） */
    private static final String EDITOR_CAMERA_VIEW = "Editor Camera Node";
    /** 网格状态（场景未就绪时为 null——命令懒取的 null 安全 no-op 口径） */
    private EditGridAppState gridState;
    public JavelinEditor(Project project,
                         JavelinSetting setting,
                         MetaManager metaManager,
                         AssetManager assetManager,
                         AssetImportPipeline assetImportPipeline,
                         BuiltinAssetReleaser builtinAssetReleaser) throws IOException {
        super(setting);
        // 对象注册表（资产热重载全量扫描源，Engine_AssetReload_Design §4）：尽早装上，
        // 覆盖 loadScene/onInitialize 期间构造的场景对象与资产；退出由关闭钩子卸下
        EditorObjectRegistry.install();
        System.setProperty("core.runtime.dir", project.getRootDir().getCanonicalPath());
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
        if (editorGameScene == null) {
            // loadScene 失败（场景文件缺失/解析错）时编辑子场景未建：跳过编辑器装配，帧循环退化为清屏
            log.warn("编辑器场景未就绪（loadScene 失败），跳过编辑器相机与网格装配");
        } else {
            Node node = new Node();
            node.setName(EDITOR_CAMERA_VIEW);
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
            // 变换 Gizmo（移动工具三轴指示，跟随选中；缩放/旋转形态与拖拽后续扩展）
            TransformGizmoAppState transformGizmo = new TransformGizmoAppState();
            stateManager.attach(transformGizmo);
            // GPU ID-buffer 拾取（Engine_Picking_Design.md，2026-10-23 全线统一拍板）：
            // pass 本体在 core（PickSystem 挂管线，目标=编辑器相机视口）；编辑器消费
            // 挂在 gizmo 之后——点击门控消费其当帧 hover/拖拽态，hover 轴节点直写 gizmo
            pickSystem = new PickSystem(this, EDITOR_CAMERA_VIEW);
            pickSystem.enable();
            stateManager.attach(new ViewportPickAppState(transformGizmo));
            // DebugDrawLine 调试线（Editor_DebugLine_Design.md）：选中物体 bounds/
            // 相机视锥线框（组件绘制器查表分派），插管线 Transparent 后、Gizmo 前
            stateManager.attach(new DebugLineAppState());
        }
        // 场景变更检测（快照 diff：写入方零通知义务，增删/挪动/改名自动发现推送）
        stateManager.attach(new SceneWatchAppState());
        // Transform 变更检测（帧级快照 diff，只盯当前选中节点——消费方=Inspector 选中面板；
        // 写入方零通知义务，实变自动发现推送 scene.nodeTransformChanged + 置脏；挂载序在
        // TransformGizmoAppState 之后——拖拽 apply 当帧检出同帧通知）
        stateManager.attach(new TransformWatchAppState());
        // 资产热重载协调器（Engine_AssetReload_Design.md 批次①）：订阅导入管线事件 →
        // reload 新实例 → 反射 Walker 换场景指针 → 渲染侧对账（触发口=手动 reimport）
        if (assetManager instanceof ProjectAssetLibManager projectAssetManager) {
            new AssetReloadService(assetImportPipeline, projectAssetManager, renderingSystem);
        } else {
            log.warn("assetManager 非 ProjectAssetLibManager，资产热重载未接入");
        }
        // 主窗口菜单条装配（菜单命令已随插件装载期注册——早于建窗，MenuBarFactory 消费注册表）
        if (window instanceof MainFrame mainFrame) {
            mainFrame.installMenuBar();
        }
    }


    public MetaManager getMetaManager() {
        return metaManager;
    }

    public AssetManager getAssetManager() {
        return assetManager;
    }

    public AssetImportPipeline getAssetImportPipeline() {
        return assetImportPipeline;
    }

    public Project getProject() {
        return project;
    }

    public SettingsManager getSettingsManager() {
        return settingsManager;
    }

    @Override
    protected Scene loadScene() {
        ProjectSettings projectSettings = settingsManager.getProjectSettings();
        String defaultOpenScene = projectSettings.getDefaultOpenScene();
        File file = new File(project.getAssetsDir(), defaultOpenScene);

        TextImporter textImporter = new TextImporter(file, assetManager);
        try {
            GameScene gameScene = textImporter.importData(GameScene.class);
            // guid 同步（2026-10-01 拍板）：.meta 为管线真值源，同步进 gameScene
            // 供保存时内嵌自述（GameScene.write super.write）；旧文件无内嵌字段，
            // read 恢复 null——此处统一从伴生 .meta 补
            AssetMeta meta = metaManager.getMeta(file);
            if (meta != null && meta.getGuid() != null) {
                gameScene.setGuid(meta.getGuid());
            }
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

    /** GPU 拾取系统（场景未就绪为 null——消费方同口径安全跳过） */
    public PickSystem getPickSystem() {
        return pickSystem;
    }

    /** 状态管理器（命令懒取入口，网格开关挂卸用） */
    public AppStateManager getStateManager() {
        return appStateSubsystem.getStateManager();
    }

    /** 网格状态（场景未就绪为 null） */
    public EditGridAppState getGridState() {
        return gridState;
    }

    /**
     * 视口（游戏画布）逻辑宽度——AWT 逻辑坐标口径，与 Input.mouseX/Y 同一空间
     * （屏幕点 → 世界射线的归一化换算用；逻辑/物理等比，纵横比不受 dpi 影响）。
     * 画布未创建返回 0。
     */
    public int viewportWidth() {
        if (window instanceof MainFrame mainFrame && mainFrame.getCanvas() != null) {
            return mainFrame.getCanvas().getWidth();
        }
        return 0;
    }

    /** 视口（游戏画布）逻辑高度（口径同 {@link #viewportWidth()}）；画布未创建返回 0 */
    public int viewportHeight() {
        if (window instanceof MainFrame mainFrame && mainFrame.getCanvas() != null) {
            return mainFrame.getCanvas().getHeight();
        }
        return 0;
    }

    public static void main(String[] args) {
        // FlatLaf——必须在任何 Swing 组件创建之前安装（Editor_Swing_Design §3）
        EditorTheme.setup();
        // 界面语言（2026-10-29 拍板默认 en_US）：编辑器设置已存值优先；未设置（首启）
        // 按系统语言（语言_国家）解析——不支持的语言由查表逐键回退默认 en_US
        // 默认 en_US（须早于插件语言包注册，I18n.setLanguage 冻结语义）
        EditorSettings startSettings = SettingsManager.loadStandaloneEditorSettings();
        if (startSettings.getLanguage() != null) {
            I18n.setLanguage(startSettings.getLanguage());
        } else {
            Locale locale = Locale.getDefault();
            I18n.setLanguage(locale.getLanguage().toLowerCase() + "_" + locale.getCountry().toUpperCase());
        }
        SwingUtilities.invokeLater(() -> {
            ProjectFrame projectWindow = new ProjectFrame();
            projectWindow.setSize(1280, 720);
            projectWindow.setLocationRelativeTo(null);
            projectWindow.setVisible(true);
        });
    }
}

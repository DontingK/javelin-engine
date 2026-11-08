package cn.donting.javelin.editor.scene;

import cn.donting.javelin.core.render.Renderable;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Scene;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.Collider;
import cn.donting.javelin.core.scene.component.Environment;
import cn.donting.javelin.core.scene.component.Light;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.editor.event.EditorEventBus;
import cn.donting.javelin.editor.event.SceneDirtyEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class EditorScene implements Scene {
    private GameScene gameScene;
    private File gameSceneFile;
    private GameScene editorScene;

    /** 场景脏标记（结构编辑命令置位；保存功能落地后经 clearDirty 复位，volatile：桥线程读/引擎线程写） */
    private volatile boolean dirty;

    /** 视图模式（桥线程 /scene/setModel 直写、引擎线程渲染 pull 读；volatile 口径同 dirty） */
    private volatile SceneModel sceneModel = SceneModel.SCENE;

    /** SCENE 模式合并结果复用列表：getRenderables 被 SceneRenderBridge 每帧 pull，预分配+clear 复用，勿包装返回 */
    private final ArrayList<Renderable> mergedRenderables = new ArrayList<>();

    public void setGameScene(GameScene gameScene, File file) {
        this.gameScene = gameScene;
        this.gameSceneFile = file;
    }

    /**
     * 用户场景本体（场景树/编辑命令的作用对象；编辑器辅助对象在 editorScene，不外溢给场景树）。
     */
    public GameScene getGameScene() {
        return gameScene;
    }

    /**
     * 场景脏标记（前端标题 {@code *} 显示源，经 /scene/tree 拉取）。
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * 置脏（引擎线程调用：结构命令经 SceneWatch 检出 / Inspector 回写 / 变换检出）。
     * 由净变脏的翻转沿发布 scene.dirty——置脏是所有写入路径的必经点，翻转即星标
     * 事件（用户拍板 2026-10-11「修改任何属性都应加 *」）：前端本地镜像置星、
     * <b>不重拉树</b>（树内容未变，scene.changed 专司树刷新，两事件分工）；
     * 已脏重复置脏不重发（每脏周期至多一响，滑条连续回写不发事件风暴）。
     * 熄灭归保存路径 clearDirty + scene.changed(false) 重拉。
     */
    public void markDirty() {
        if (!dirty) {
            dirty = true;
            EditorEventBus.publish(new SceneDirtyEvent());
        }
    }

    /**
     * 清脏（保存成功后调用，保存功能未落地前无调用方）。
     */
    public void clearDirty() {
        dirty = false;
    }

    public GameScene getEditorScene() {
        return editorScene;
    }

    public void setEditorScene(GameScene editorScene) {
        this.editorScene = editorScene;
    }

    /**
     * 视图模式切换：GAME = 相机/物体只拉用户场景（编辑器相机/网格/Gizmo 不进画面）、
     * SCENE = 合并编辑子场景辅助对象。桥线程 volatile 直写与渲染 pull 并发——
     * 翻转至多一帧混态，下帧自收敛。
     */
    public void setSceneModel(SceneModel sceneModel) {
        this.sceneModel = sceneModel;
    }

    public SceneModel getSceneModel() {
        return sceneModel;
    }

    public File getGameSceneFile() {
        return gameSceneFile;
    }

    @Override
    public String getName() {
        return gameScene.getName();
    }

    /** 环境设置 = 用户场景口径（背景视口共享，无编辑器辅助对象参与语义） */
    @Override
    public Environment getEnvironment() {
        return gameScene.getEnvironment();
    }

    @Override
    public List<Renderable> getRenderables() {
        if (sceneModel == SceneModel.GAME) {
            return gameScene.getRenderables();
        }
        mergedRenderables.clear();
        mergedRenderables.addAll(gameScene.getRenderables());
        mergedRenderables.addAll(editorScene.getRenderables());
        return mergedRenderables;
    }

    @Override
    public List<Camera> getCameras() {
        if (sceneModel == SceneModel.GAME) {
            return gameScene.getCameras();
        }
        return editorScene.getCameras();
    }

    /**
     * 物理查询只打用户场景——编辑器辅助对象（网格/相机等）不参与，
     * GAME/SCENE 模式同口径（渲染合并仅渲染事，物理查询无编辑器对象语义）。
     */
    @Override
    public List<Collider> getColliders() {
        return gameScene.getColliders();
    }

    /**
     * 灯光：GAME 模式 = 用户场景；SCENE 模式合并编辑器辅助场景（照 getRenderables
     * 口径——SCENE 视口光照预览用；当前编辑器辅助无灯对象，合并结果即用户场景灯）。
     */
    @Override
    public List<Light> getLights() {
        if (sceneModel == SceneModel.GAME) {
            return gameScene.getLights();
        }
        ArrayList<Light> merged = new ArrayList<>(gameScene.getLights());
        merged.addAll(editorScene.getLights());
        return merged;
    }

    /**
     * 主光 = 用户场景口径（与 getMainCamera 同：运行时语义只打用户场景，
     * 编辑器辅助对象不产生主光）。
     */
    @Override
    public Light getSun() {
        return gameScene.getSun();
    }

    /**
     * 主相机 = 用户场景口径（与 getColliders 同——运行时语义只打用户场景；
     * 编辑器相机从不置 isMain，同 Unity SceneView 相机非 Camera.main）。
     */
    @Override
    public Camera getMainCamera() {
        return gameScene.getMainCamera();
    }

    @Override
    public void update(float tpf) {

    }

    @Override
    public void write(OutputCapsule output) {

    }

    @Override
    public void read(InputCapsule input) {

    }

    @Override
    public String getGuid() {
        return "";
    }

    @Override
    public long getLastModified() {
        return 0;
    }
}

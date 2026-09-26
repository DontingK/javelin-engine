package com.javelin.editor.scene;

import com.javelin.core.render.Renderable;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Scene;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class EditorScene implements Scene {
    private GameScene gameScene;
    private File gameSceneFile;
    private GameScene editorScene;

    private SceneModel sceneModel = SceneModel.SCENE;

    /** SCENE 模式合并结果复用列表：getRenderables 被 SceneRenderBridge 每帧 pull，预分配+clear 复用，勿包装返回 */
    private final ArrayList<Renderable> mergedRenderables = new ArrayList<>();

    public void setGameScene(GameScene gameScene, File file) {
        this.gameScene = gameScene;
        this.gameSceneFile = file;
    }

    public GameScene getEditorScene() {
        return editorScene;
    }

    public void setEditorScene(GameScene editorScene) {
        this.editorScene = editorScene;
    }

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

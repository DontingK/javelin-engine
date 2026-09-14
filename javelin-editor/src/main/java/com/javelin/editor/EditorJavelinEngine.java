package com.javelin.editor;

import com.javelin.core.JavelinEngine;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.window.Window;
import com.javelin.editor.asset.ProjectAssetLibManager;
import com.javelin.editor.importer.AssetImportPipeline;
import com.javelin.editor.meta.MetaManager;
import com.javelin.editor.project.Project;
import com.javelin.editor.window.SwingWindow;

public class EditorJavelinEngine extends JavelinEngine {

    private final Project project;

    private final MetaManager metaManager;
    private final ProjectAssetLibManager assetManager;
    private final AssetImportPipeline assetImportPipeline;

    public EditorJavelinEngine(JavelinSetting setting,
                               Project project,
                               MetaManager metaManager,
                               ProjectAssetLibManager assetManager,
                               AssetImportPipeline assetImportPipeline) {
        super(setting);
        this.project = project;
        this.metaManager = metaManager;
        this.assetManager = assetManager;
        this.assetImportPipeline = assetImportPipeline;
    }

    public AssetImportPipeline getAssetImportPipeline() {
        return assetImportPipeline;
    }

    public Project getProject() {
        return project;
    }

    @Override
    protected Window createWindow() {
        return new SwingWindow(setting,this);
    }

    @Override
    protected AssetManager createAssetManager() {
        return assetManager;
    }

    public MetaManager getMetaManager() {
        return metaManager;
    }


    @Override
    protected void initialize() {
        Shader shader = assetManager.loadAsset("core/shader/default.shader", Shader.class);
        Mesh mesh = assetManager.loadAsset("core/mesh/plane.smesh", Mesh.class);

        GameScene scene = sceneManager.createScene("scene");
        sceneManager.setActiveScene(scene);

        // ---- 相机 ----
        Node cameraNode = new Node("Camera");
        cameraNode.transform().setLocalPosition(0, 1, 0);
        // 默认朝向 -Z，看向原点
        Camera camera = new Camera();
        camera.setClearColor(0.3f,0.3f,0.3f,1);
        camera.setPerspective(60f, 0.1f, 1000f);
        cameraNode.addComponent(camera);
        scene.addRoot(cameraNode);

        // ---- 地面 Mesh ----
        Node meshNode = new Node("PlaneMesh");
        MeshRenderer meshRenderer = new MeshRenderer();
        meshRenderer.setMesh(mesh);
        Material material = new Material(shader);
        meshRenderer.setMaterial(material);
        meshNode.addComponent(meshRenderer);
        scene.addRoot(meshNode);
    }
}

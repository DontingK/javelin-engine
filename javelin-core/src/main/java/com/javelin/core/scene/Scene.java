package com.javelin.core.scene;

import com.javelin.core.render.Renderable;
import com.javelin.core.scene.component.Camera;

import java.util.List;

public interface Scene {

    void addRoot(Node node);

    void update(float deltaTime);

    void collectRenderables(List<Renderable> out);

    void collectCameras(List<Camera> out);

    void dispose();
}

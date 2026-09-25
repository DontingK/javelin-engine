package com.javelin.core.scene;

import com.javelin.core.render.Renderable;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.serialize.Serializable;

import java.util.List;


public interface Scene extends Serializable {

    String getName();
    List<Renderable> getRenderables();
    List<Camera> getCameras();
    void update(float tpf);

}

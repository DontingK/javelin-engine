package cn.donting.javelin.core.scene;

import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.render.Renderable;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.Collider;
import cn.donting.javelin.core.serialize.Serializable;

import java.util.List;


public interface Scene extends Serializable, Asset {

    String getName();
    List<Renderable> getRenderables();
    List<Camera> getCameras();
    Camera getMainCamera();
    List<Collider> getColliders();
    void update(float tpf);

}

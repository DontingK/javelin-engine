package cn.donting.javelin.core.scene;

import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.render.Renderable;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.Collider;
import cn.donting.javelin.core.scene.component.Environment;
import cn.donting.javelin.core.scene.component.Light;
import cn.donting.javelin.core.serialize.Serializable;

import java.util.List;


public interface Scene extends Serializable, Asset {

    String getName();
    /** 环境设置组件（节点上的普通组件，全场景唯一；null = 未创建——天空盒无、SKYBOX 视口回退纯色清屏） */
    Environment getEnvironment();
    List<Renderable> getRenderables();
    List<Camera> getCameras();
    Camera getMainCamera();
    List<Collider> getColliders();
    List<Light> getLights();
    /** 主光：首个激活且 enabled 的 isMain 方向光，无则 null（与 getMainCamera 对称） */
    Light getSun();
    void update(float tpf);

}

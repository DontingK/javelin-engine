package com.javelin.core.scene;

import com.javelin.core.render.RenderCamera;
import com.javelin.core.render.RenderObject;
import com.javelin.core.render.Renderable;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.core.scene.component.Transform;
import com.javelin.rhi.Texture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景 → 渲染 pull 翻译层（Engine_Implementation_Plan 阶段②拍板 2026-09-25：
 * 引擎层每帧从 {@link Scene#getCameras()}/{@link Scene#getRenderables()} 取组件，
 * 翻译成 RenderCamera/RenderObject 喂 RenderingSystem 的同一套注册 API——
 * RenderingSystem 本类不感知 scene 包）。
 *
 * <p>组件与渲染侧包装按身份（IdentityHashMap）一一对应，每帧三阶段：
 * 注销消失的 → 注册新增的 → 同步存量（相机参数/位姿、物体世界矩阵）。
 * 相机位姿取自所在 Node 的 Transform（Unity 相机朝局部 +Z，向上 +Y）；
 * 多相机视口按声明序横向均分 backbuffer 宽高（N=1 全屏，resize 自适应）。
 * MeshRenderer 的 mesh/material 为 null 时不渲染（missing 语义），
 * mesh/material 身份变化 = 注销重注（v0.1）。
 *
 * <p>时序：JavelinEngine 在 collectFrame 之前调用 {@link #sync}；
 * 场景切换经 {@link #clear} 全注销。多线程化后本类整体移到逻辑线程（RHI 27.2）。
 */
public final class SceneRenderBridge {

    private final Map<Camera, RenderCamera> cameras = new IdentityHashMap<>();
    private final Map<MeshRenderer, RenderObject> renderers = new IdentityHashMap<>();
    private final Set<String> usedNames = new HashSet<>();

    /**
     * 每帧把场景组件同步进渲染系统注册表。
     *
     * @param scene      当前场景（null 等价于 clear 后空场景，由调用方保证非空）
     * @param rs         渲染系统
     * @param backbuffer 本帧终点（视口横向均分的尺寸来源）
     */
    public void sync(Scene scene, RenderingSystem rs, Texture backbuffer) {
        List<Camera> sceneCameras = scene.getCameras();
        List<Renderable> sceneRenderables = scene.getRenderables();

        // 注销：组件已不在场景注册表里（移除/场景树摘链）
        Iterator<Map.Entry<Camera, RenderCamera>> camIt = cameras.entrySet().iterator();
        while (camIt.hasNext()) {
            Map.Entry<Camera, RenderCamera> e = camIt.next();
            if (!sceneCameras.contains(e.getKey())) {
                rs.removeCamera(e.getValue());
                usedNames.remove(e.getValue().name());
                camIt.remove();
            }
        }
        Iterator<Map.Entry<MeshRenderer, RenderObject>> roIt = renderers.entrySet().iterator();
        while (roIt.hasNext()) {
            Map.Entry<MeshRenderer, RenderObject> e = roIt.next();
            if (!sceneRenderables.contains(e.getKey())) {
                rs.removeRenderer(e.getValue());
                roIt.remove();
            }
        }

        // 注册 + 同步相机
        for (Camera camera : sceneCameras) {
            RenderCamera rc = cameras.get(camera);
            if (rc == null) {
                rc = new RenderCamera(uniqueName(camera.getNode().getName()));
                rs.addCamera(rc);
                cameras.put(camera, rc);
                usedNames.add(rc.name());
            }
            rc.setFovY(camera.getFov());
            rc.setNearZ(camera.getNear());
            rc.setFarZ(camera.getFar());
            applyPose(rc, camera.getNode().getTransform());
        }
        layoutViewports(sceneCameras, backbuffer);

        // 注册 + 同步渲染对象
        for (Renderable renderable : sceneRenderables) {
            if (!(renderable instanceof MeshRenderer mr)) {
                continue;
            }
            RenderObject ro = renderers.get(mr);
            boolean identityChanged = ro != null
                    && (ro.mesh() != mr.getMesh() || ro.material() != mr.getMaterial());
            if (identityChanged) {
                rs.removeRenderer(ro);
                renderers.remove(mr);
                ro = null;
            }
            if (ro == null) {
                if (mr.getMesh() == null || mr.getMaterial() == null) {
                    continue;   // missing-mesh / missing-material：不渲染
                }
                ro = new RenderObject(mr.getMesh(), mr.getMaterial(), mr.isCastShadow());
                rs.addRenderer(ro);
                renderers.put(mr, ro);
            }
            // 世界矩阵原地写（不换引用，帧描述存活期约束由单线程退化保证）
            ro.world().set(mr.getNode().getTransform().getLocalToWorld());
        }
    }

    /** 场景切换：全部注销，下一次 sync 重新注册 */
    public void clear(RenderingSystem rs) {
        for (RenderCamera rc : cameras.values()) {
            rs.removeCamera(rc);
        }
        for (RenderObject ro : renderers.values()) {
            rs.removeRenderer(ro);
        }
        cameras.clear();
        renderers.clear();
        usedNames.clear();
    }

    /** 相机位姿：position = 世界平移，target = position + 前向（局部 +Z），up = 局部 +Y（Unity 约定） */
    private static void applyPose(RenderCamera rc, Transform transform) {
        Matrix4f m = transform.getLocalToWorld();
        Vector3f pos = m.getTranslation(new Vector3f());
        Vector3f forward = m.normalizedPositiveZ(new Vector3f());
        Vector3f up = m.normalizedPositiveY(new Vector3f());
        rc.position().set(pos);
        rc.target().set(pos).add(forward);
        rc.up().set(up);
    }

    /** 多相机视口横向均分（声明序左→右；N=1 全屏；每帧按 backbuffer 实际尺寸重算） */
    private void layoutViewports(List<Camera> sceneCameras, Texture backbuffer) {
        int w = backbuffer.desc().width();
        int h = backbuffer.desc().height();
        int n = sceneCameras.size();
        for (int i = 0; i < n; i++) {
            RenderCamera rc = cameras.get(sceneCameras.get(i));
            if (rc == null) {
                continue;
            }
            int x = i * w / n;
            int right = (i + 1) * w / n;
            rc.viewport(x, 0, right - x, h);
        }
    }

    /** 相机名去重（depthFor 以名为键）：节点名空缺或重名时追加序号 */
    private String uniqueName(String nodeName) {
        String base = (nodeName == null || nodeName.isBlank()) ? "Camera" : nodeName;
        String name = base;
        for (int i = 2; usedNames.contains(name); i++) {
            name = base + "#" + i;
        }
        return name;
    }
}

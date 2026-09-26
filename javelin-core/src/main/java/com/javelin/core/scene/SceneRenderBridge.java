package com.javelin.core.scene;

import com.javelin.core.render.RenderCamera;
import com.javelin.core.render.RenderObject;
import com.javelin.core.render.Renderable;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.math.Color;
import com.javelin.core.math.Rect;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.core.scene.component.Transform;
import com.javelin.rhi.data.RHIClearColor;
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
 * 视口 = viewportRect（Unity 口径归一化矩形）换算像素，
 * 未设置默认全屏（用户拍板 2026-09-26：无均分，多相机分屏必须手动设矩形）。
 * MeshRenderer 的 mesh/material 为 null 时不渲染（missing 语义），
 * mesh/material 身份变化 = 注销重注（v0.1）。
 *
 * <p>时序：JavelinEngine 在 collectFrame 之前调用 {@link #sync}；
 * 场景切换经 {@link #clear} 全注销。多线程化后本类整体移到逻辑线程（RHI 27.2）。
 */
public final class SceneRenderBridge {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(SceneRenderBridge.class);
    /** 首帧 sync 一次性诊断日志（渲染链读音入口，常驻保留） */
    private boolean syncLogged;
    /** 首帧相机位姿一次性读数（独立 flag——syncLogged 在 sync 开头已置位，相机循环内不得复用） */
    private boolean poseLogged;
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
        if (!syncLogged) {
            syncLogged = true;
            int meshRenderers = 0;
            for (Renderable renderable : sceneRenderables) {
                if (renderable instanceof MeshRenderer mr && mr.getMesh() != null && mr.getMaterial() != null) {
                    meshRenderers++;
                }
            }
            LOGGER.info("首帧 sync: 场景相机={} 可渲染注册={} 有效MeshRenderer={} backbuffer={}x{}",
                    sceneCameras.size(), sceneRenderables.size(), meshRenderers,
                    backbuffer.desc().width(), backbuffer.desc().height());
        }

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
            // Camera 组件以度暴露 fov，RenderCamera/JOML 收弧度（此处为单位换算唯一落点）
            rc.setProjectionType(camera.getProjectionType());
            rc.setFovY((float) Math.toRadians(camera.getFov()));
            rc.setOrthographicSize(camera.getOrthographicSize());
            rc.setNearZ(camera.getNear());
            rc.setFarZ(camera.getFar());
            rc.setCullingMask(camera.getCullingMask());
            rc.setDepth(camera.getDepth());
            rc.setClearFlags(camera.getClearFlags());
            if (camera.getBackgroundColor() != null) {
                Color background = camera.getBackgroundColor();
                rc.setClearColor(new RHIClearColor(background.r, background.g, background.b, background.a));
            } else {
                rc.setClearColor(null);
            }
            applyPose(rc, camera.getNode().getTransform());
            if (!poseLogged) {
                poseLogged = true;
                // 首帧 pose 读数（一次性）：与投影检验配套。期望 pos(0,30,-30)、
                // target = pos + 低头(0,-0.707,0.707) 即 (0,29.29,-29.29)，up(0,0.707,0.707)
                LOGGER.info("首帧相机位姿: pos=({},{},{}) target=({},{},{}) up=({},{},{})",
                        rc.position().x, rc.position().y, rc.position().z,
                        rc.target().x, rc.target().y, rc.target().z,
                        rc.up().x, rc.up().y, rc.up().z);
            }
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
                ro = new RenderObject(mr.getMesh(), mr.getMaterial(), mr.isCastShadow(),
                        mr.getNode().getLayer().getMask());
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
        // 局部轴的世界像必须走正变换（transformDirection = M·方向）。
        // 坑（2026-09-26 实勘实锤）：JOML normalizedPositiveZ/Y 是"view 逆矩阵"提取约定
        // （取转置基），用在正向 localToWorld 上旋转分量绕轴反号——identity 对称无症状、
        // 45° 俯仰实测抬头看天。此处禁用该族方法（含 Quaternionf 直接角度约定的影响）。
        Vector3f forward = m.transformDirection(new Vector3f(0, 0, 1)).normalize();
        Vector3f up = m.transformDirection(new Vector3f(0, 1, 0)).normalize();
        rc.position().set(pos);
        rc.target().set(pos).add(forward);
        rc.up().set(up);
    }

    /**
     * 视口布局：viewportRect（Unity 口径 [0,1] 归一化、原点左下 y 向上）→ 换算像素；
     * 未设置 → 默认全屏（无均分；多相机分屏/画中画须显式设置矩形）。
     * 每帧按 backbuffer 实际尺寸重算。
     */
    private void layoutViewports(List<Camera> sceneCameras, Texture backbuffer) {
        int w = backbuffer.desc().width();
        int h = backbuffer.desc().height();
        for (Camera camera : sceneCameras) {
            RenderCamera rc = cameras.get(camera);
            if (rc == null) {
                continue;
            }
            Rect rect = camera.getViewportRect();
            if (rect != null) {
                // Unity 口径（原点左下 y 向上）→ Vulkan 视口像素（原点左上）：
                // 顶部 y = 总高 - 底部偏移 - 视口高
                int px = Math.round(rect.x * w);
                int py = Math.round((1.0f - rect.y - rect.height) * h);
                rc.viewport(px, py, Math.round(rect.width * w), Math.round(rect.height * h));
            } else {
                // 未设置 = 默认全屏
                rc.viewport(0, 0, w, h);
            }
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

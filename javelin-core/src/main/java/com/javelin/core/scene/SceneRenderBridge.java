package com.javelin.core.scene;

import com.javelin.core.render.RenderCamera;
import com.javelin.core.render.RenderObject;
import com.javelin.core.render.Renderable;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.render.material.DefaultMaterial;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.math.Color;
import com.javelin.core.math.Rect;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.core.scene.component.Transform;
import com.javelin.rhi.data.RHIClearColor;
import com.javelin.rhi.Texture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
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
 * 组件 isActiveAndEnabled 过滤（节点层级激活 + 组件 enabled，Unity 口径）：禁用
 * 即注销，启用即重注（2026-10-17 组件头勾选实锤——仅过滤节点激活藏不住组件级禁用）。
 * MeshRenderer 的 mesh 为 null 时不渲染（missing-mesh 语义）；多材质（拍板
 * 2026-10-29）：每 (renderer, 子网格) 一个 RenderObject，materials[i] 对应子网格 i，
 * 缺失位/整列表空/shader 无效（null / SPIR-V 缺失——编译失败 stub 产物）→
 * DefaultMaterial 默认材质兜底渲染（原「material null 不渲染」口径作废），默认
 * shader 不可用 → 该子网格不渲染降级；mesh/材质身份变化 = 全注销重注。
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
    private final Map<MeshRenderer, List<RenderObject>> renderers = new IdentityHashMap<>();
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
                if (renderable instanceof MeshRenderer mr && mr.getMesh() != null) {
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
        Iterator<Map.Entry<MeshRenderer, List<RenderObject>>> roIt = renderers.entrySet().iterator();
        while (roIt.hasNext()) {
            Map.Entry<MeshRenderer, List<RenderObject>> e = roIt.next();
            if (!sceneRenderables.contains(e.getKey())) {
                removeRendererList(rs, e.getValue());
                roIt.remove();
            }
        }

        // 注册 + 同步相机
        for (Camera camera : sceneCameras) {
            // 相机组件禁用/节点不激活不渲染（与 MeshRenderer 同口径，含已注册注销防残留）
            if (!camera.isActiveAndEnabled()) {
                RenderCamera stale = cameras.remove(camera);
                if (stale != null) {
                    rs.removeCamera(stale);
                    usedNames.remove(stale.name());
                }
                continue;
            }
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
            // 节点层级不激活或组件 enabled=false 不渲染（Unity 口径，用户拍板
            // 2026-10-05 渲染 pull 侧统一过滤，2026-10-17 扩为 isActiveAndEnabled
            // ——组件级禁用同口径，编辑器取消勾选 MeshRenderer 头实锤）。已注册的
            // 即时注销防残留：仅跳过注册会让旧 RenderObject 帧帧续命，禁用/激活
            // 翻转必须走到 removeRenderer
            if (!mr.isActiveAndEnabled() || mr.getMesh() == null) {
                // mesh 置空即时注销（missing-mesh 语义；材质缺失不挡渲染——默认材质兜底，
                // 拍板 2026-10-29）
                removeRendererList(rs, renderers.remove(mr));
                continue;
            }
            Mesh mesh = mr.getMesh();
            int submeshCount = mesh.getSubmeshCount();
            List<RenderObject> ros = renderers.get(mr);
            boolean identityChanged = ros == null || ros.size() != submeshCount;
            if (!identityChanged) {
                for (int i = 0; i < submeshCount; i++) {
                    Material renderMaterial = submeshMaterial(mr, i);
                    RenderObject ro = ros.get(i);
                    // 占位 null（上帧默认 shader 不可用降级）与本次有/无材质不对称即换代
                    if (ro == null ? renderMaterial != null
                            : ro.mesh() != mesh || ro.material() != renderMaterial) {
                        identityChanged = true;
                        break;
                    }
                }
            }
            if (identityChanged) {
                removeRendererList(rs, ros);
                // 列表槽位 = 子网格槽位（占位 null = 默认 shader 不可用降级不渲染）
                ros = new ArrayList<>(Collections.nCopies(submeshCount, (RenderObject) null));
                for (int i = 0; i < submeshCount; i++) {
                    Material renderMaterial = submeshMaterial(mr, i);
                    if (renderMaterial == null) {
                        continue;   // resolveOrDefault 契约：默认 shader 不可用 → 降级不渲染
                    }
                    RenderObject ro = new RenderObject(mesh, renderMaterial, mr.isCastShadow(),
                            mr.getNode().getLayer().getMask(), mr, i);
                    rs.addRenderer(ro);
                    ros.set(i, ro);
                }
                renderers.put(mr, ros);
            }
            // 世界矩阵原地写（不换引用，帧描述存活期约束由单线程退化保证）
            for (RenderObject ro : ros) {
                if (ro != null) {
                    ro.world().set(mr.getNode().getTransform().getLocalToWorld());
                }
            }
        }
    }

    /**
     * 子网格 i 的渲染材质：缺失位（超出 materials 长度或 null）走 DefaultMaterial
     * 默认材质兜底（拍板 2026-10-29）；返回 null 仅当默认 shader 不可用（降级，
     * 该子网格不渲染）。
     */
    private static Material submeshMaterial(MeshRenderer mr, int submesh) {
        List<Material> materials = mr.getMaterials();
        return DefaultMaterial.resolveOrDefault(
                submesh < materials.size() ? materials.get(submesh) : null);
    }

    /** 按列表逐个注销（元素可为占位 null） */
    private void removeRendererList(RenderingSystem rs, List<RenderObject> ros) {
        if (ros == null) {
            return;
        }
        for (RenderObject ro : ros) {
            if (ro != null) {
                rs.removeRenderer(ro);
            }
        }
    }

    /** 场景切换：全部注销，下一次 sync 重新注册 */
    public void clear(RenderingSystem rs) {
        for (RenderCamera rc : cameras.values()) {
            rs.removeCamera(rc);
        }
        for (List<RenderObject> ros : renderers.values()) {
            removeRendererList(rs, ros);
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

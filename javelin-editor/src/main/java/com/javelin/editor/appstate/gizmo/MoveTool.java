package com.javelin.editor.appstate.gizmo;

import com.javelin.core.JavelinEngine;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.core.scene.component.Transform;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 移动工具（TransformTools 首个实现，2026-10-04）。
 *
 * <p>三轴移动指示：内置 axisY.smesh +Y 箭头（全长约 9.9）复用旋转出三轴
 * （用户拍板 2026-10-04：+X 绕 Z 轴 -90°、+Z 绕 X 轴 +90°，同一 Mesh 共享）；
 * 红/绿/蓝 = XYZ 惯例（同 EditorAxesAppState 诊断线）。材质走内置
 * gizmo.shader（unlit 纯色 + _Color 属性），每轴一个材质实例按轴下发颜色；
 * shader Cull Off 容忍外部工具导出的混合绕序。子树归编辑子场景口径（不进
 * 用户树、不落盘、GAME 模式自动不可见）。尺度不自持：由 TransformGizmoAppState
 * 距离自适应统一下发（× scaleFactor()），不随选中物体缩放（Unity 移动轴口径）。</p>
 *
 * <p>init 只做资源与子树构建；选中跟随/显隐暂由 TransformGizmoAppState
 * 驱动，工具管理器改造时迁入 update。</p>
 *
 * @author javelin
 * @create 2026/10/4
 */
public class MoveTool extends TransformTools {

    private static final Logger log = LoggerFactory.getLogger(MoveTool.class);

    /** 内置移动轴网格 loadAsset 路径（builtin-asset-editor/mesh/axisY.smesh 释放到 .javelin/assets 后的相对路径） */
    private static final String AXIS_MESH_PATH = "editor/mesh/axisY.smesh";

    /** 内置 Gizmo 着色器 loadAsset 路径（unlit 纯色，_Color 按轴下发；包内可见——TransformGizmoAppState 加载同实例传 GizmoPass 做材质过滤） */
    static final String GIZMO_SHADER_PATH = "editor/shader/gizmo.shader";

    @Override
    protected void init(AssetManager assetManager, JavelinEngine javelinEngine) {
        Mesh axisMesh = assetManager.loadAsset(AXIS_MESH_PATH, Mesh.class);
        if (axisMesh == null) {
            log.error("移动轴网格加载失败 path={}（Library 缺产物或索引未建），移动工具不可用", AXIS_MESH_PATH);
            return;
        }
        Shader shader = assetManager.loadAsset(GIZMO_SHADER_PATH, Shader.class);
        if (shader == null) {
            log.error("Gizmo 着色器加载失败 path={}（Library 缺产物或索引未建），移动工具不可用", GIZMO_SHADER_PATH);
            return;
        }
        toolNode = new Node();
        toolNode.setName("MoveTool");
        Transform rootTransform = toolNode.getTransform();
        // 尺度不在此设：由 TransformGizmoAppState 每帧距离自适应下发（scaleFactor 参与）

        // RGB = XYZ；+Y 本体、+X 绕 Z 轴 -90°、+Z 绕 X 轴 +90°（同一 axisY 箭头复用旋转）
        float[][] colors = {
                {1.0f, 0.0f, 0.0f},
                {0.0f, 1.0f, 0.0f},
                {0.0f, 0.0f, 1.0f},
        };
        String[] names = {"MoveToolAxis-X", "MoveToolAxis-Y", "MoveToolAxis-Z"};
        for (int i = 0; i < 3; i++) {
            Node node = new Node();
            node.setName(names[i]);
            Transform transform = node.getTransform();
            if (i == 0) {
                transform.setLocalRotation(new Quaternionf().rotationZ((float) Math.toRadians(-90.0)));
            } else if (i == 2) {
                transform.setLocalRotation(new Quaternionf().rotationX((float) Math.toRadians(90.0)));
            }
            transform.setParent(rootTransform);
            MeshRenderer renderer = node.addComponent(MeshRenderer.class);
            renderer.setMesh(axisMesh);
            Material material = new Material(shader);
            material.setColor("_Color", colors[i][0], colors[i][1], colors[i][2], 1.0f);
            renderer.setMaterial(material);
            renderer.setCastShadow(false);
        }
        log.info("移动工具已创建（三轴复用 axisY 箭头，红/绿/蓝 = X/Y/Z，尺度随驱动侧距离自适应）");
    }

    @Override
    protected void update() {
        // 选中解析/显隐/位置对齐暂由 TransformGizmoAppState 驱动，工具管理器改造时迁入本方法
    }

    @Override
    protected float scaleFactor() {
        return 1/9.8f;
    }

    @Override
    protected String getName() {
        return "Move";
    }
}

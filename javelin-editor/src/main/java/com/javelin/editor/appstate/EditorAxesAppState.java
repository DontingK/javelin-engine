package com.javelin.editor.appstate;

import com.javelin.core.JavelinEngine;
import com.javelin.core.appstate.AppStateManager;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.Scene;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.editor.scene.EditorScene;
import com.javelin.rhi.types.PrimitiveTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 编辑器坐标轴诊断线（2026-10-04，plane 朝向排查工具）。
 *
 * <p>原点出发三根正方向轴线：+X 红 / +Y 绿 / +Z 蓝（RGB=XYZ 惯例），长
 * {@value #AXIS_LEN}（plane 为 ±5，轴线略出头便于辨认）。复用
 * editor-grid.shader 线渲染——LINE_LIST + Cull Off，不受三角形绕序/
 * 剔除约定影响（绕序排查期间的可靠参照物）。材质 {@code _CamPos} 保持
 * shader 默认原点、轴线距原点 ≤ AXIS_LEN·√2 &lt; _FadeStart 默认 50，
 * 径向淡出恒为 1，无需每帧更新。节点进编辑器子场景（不进用户场景树、
 * 不落盘，同 EditGridAppState 归属口径）。</p>
 *
 * @author javelin
 * @create 2026/10/4
 */
public class EditorAxesAppState extends AbsEditorAppState {

    private static final Logger log = LoggerFactory.getLogger(EditorAxesAppState.class);

    /** 网格着色器路径（同 EditGridAppState：线渲染复用） */
    private static final String GRID_SHADER_PATH = "editor/shader/editor-grid.shader";

    /** 轴长（世界单位） */
    private static final float AXIS_LEN = 1.0f;

    /** 三根轴节点（onDetach 时从编辑器场景摘除） */
    private final Node[] axisNodes = new Node[3];

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        super.onAttach(stateManager, engine);
        GameScene editorGameScene = resolveEditorScene();
        if (editorGameScene == null) {
            log.warn("编辑器场景未就绪，跳过坐标轴创建（EditorAxesAppState）");
            return;
        }
        Shader shader = javelinEditor.getAssetManager().loadAsset(GRID_SHADER_PATH, Shader.class);
        if (shader == null) {
            log.error("坐标轴着色器加载失败 path={}（Library 缺产物或索引未建），坐标轴不可用",
                    GRID_SHADER_PATH);
            return;
        }

        // RGB = XYZ
        float[][] colors = {
                {1.0f, 0.0f, 0.0f},
                {0.0f, 1.0f, 0.0f},
                {0.0f, 0.0f, 1.0f},
        };
        float[][] dirs = {
                {AXIS_LEN, 0.0f, 0.0f},
                {0.0f, AXIS_LEN, 0.0f},
                {0.0f, 0.0f, AXIS_LEN},
        };
        String[] names = {"EditorAxis-X", "EditorAxis-Y", "EditorAxis-Z"};
        for (int i = 0; i < 3; i++) {
            Node node = new Node();
            node.setName(names[i]);
            MeshRenderer renderer = node.addComponent(MeshRenderer.class);
            renderer.setMesh(axisLine(dirs[i]));
            Material material = new Material(shader);
            material.setColor("_GridColor", colors[i][0], colors[i][1], colors[i][2], 1.0f);
            renderer.setMaterial(material);
            renderer.setCastShadow(false);
            axisNodes[i] = node;
            editorGameScene.add(node);
        }
        log.info("编辑器坐标轴已创建（+X 红 / +Y 绿 / +Z 蓝，长 {}）", AXIS_LEN);
    }

    @Override
    public void onDetach(AppStateManager stateManager) {
        // 先摘节点再 super（super 置空 engine，之后无法访问场景）
        GameScene editorGameScene = resolveEditorScene();
        if (editorGameScene != null) {
            for (int i = 0; i < 3; i++) {
                if (axisNodes[i] != null && axisNodes[i].getScene() == editorGameScene) {
                    editorGameScene.remove(axisNodes[i]);
                }
                axisNodes[i] = null;
            }
        }
        super.onDetach(stateManager);
    }

    /** 原点 → (dx,dy,dz) 单线段 Mesh（LINE_LIST + 2 顶点全序索引，同 GridMesh 口径） */
    private static Mesh axisLine(float[] dir) {
        Mesh mesh = new Mesh();
        mesh.setPositions(new float[]{0.0f, 0.0f, 0.0f, dir[0], dir[1], dir[2]});
        mesh.setIndices(new int[]{0, 1});
        mesh.setTopology(PrimitiveTopology.LINE_LIST);
        return mesh;
    }

    /** 编辑器场景（EditorScene 内的编辑 GameScene）；场景未就绪返回 null */
    private GameScene resolveEditorScene() {
        if (engine == null) {
            return null;
        }
        Scene scene = engine.scene();
        if (scene instanceof EditorScene editorScene) {
            return editorScene.getEditorScene();
        }
        return null;
    }
}

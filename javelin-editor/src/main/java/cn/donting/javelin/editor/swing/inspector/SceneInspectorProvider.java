package cn.donting.javelin.editor.swing.inspector;

import java.util.List;

/**
 * 场景检查器 provider（.scene 键，批次⑤步骤三 3c）：场景根行（层级根行 =
 * 场景文件选中）进检查器的首个人口——skybox 场景级字段编辑（Unity Lighting
 * 窗 Environment 槽的引擎侧挂点）。
 *
 * @author javelin
 * @create 2026/11/03
 */
public final class SceneInspectorProvider implements InspectorProvider {

    @Override
    public List<String> keys() {
        return List.of(".scene");
    }

    @Override
    public InspectorContent create() {
        return new SceneInspectorContent();
    }
}

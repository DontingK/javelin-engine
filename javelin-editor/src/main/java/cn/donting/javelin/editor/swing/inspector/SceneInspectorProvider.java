package cn.donting.javelin.editor.swing.inspector;

import java.util.List;

/**
 * 场景检查器 provider（.scene 键）：场景根行（层级根行 = 场景文件选中）进检查器的
 * 首个人口——场景分组（文件行）+ 环境设置组件节（{@code Environment}，场景域组件
 * 统一组件化渲染；2026-11-08 由原 skybox 场景级字段行改口）。
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

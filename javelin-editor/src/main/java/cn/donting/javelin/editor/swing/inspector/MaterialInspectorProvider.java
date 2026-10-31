package cn.donting.javelin.editor.swing.inspector;

import java.util.List;

/**
 * 材质检查器 provider（.mat 键）：材质只读内容工厂。
 *
 * @author javelin
 * @create 2026/10/21
 */
public final class MaterialInspectorProvider implements InspectorProvider {

    @Override
    public List<String> keys() {
        return List.of(".mat");
    }

    @Override
    public InspectorContent create() {
        return new MaterialInspectorContent();
    }
}

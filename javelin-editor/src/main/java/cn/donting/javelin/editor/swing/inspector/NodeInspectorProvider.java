package cn.donting.javelin.editor.swing.inspector;

import java.util.List;

/**
 * 节点检查器 provider（node 键）：节点组件清单内容工厂。
 *
 * @author javelin
 * @create 2026/10/21
 */
public final class NodeInspectorProvider implements InspectorProvider {

    @Override
    public List<String> keys() {
        return List.of(NODE_KEY);
    }

    @Override
    public InspectorContent create() {
        return new NodeInspectorContent();
    }
}

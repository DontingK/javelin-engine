package cn.donting.javelin.editor.swing.inspector;

import java.util.List;

/**
 * 着色器检查器 provider（.shader 键）：只读诊断台内容工厂。
 *
 * @author javelin
 * @create 2026/10/25
 */
public final class ShaderInspectorProvider implements InspectorProvider {

    @Override
    public List<String> keys() {
        return List.of(".shader");
    }

    @Override
    public InspectorContent create() {
        return new ShaderInspectorContent();
    }
}

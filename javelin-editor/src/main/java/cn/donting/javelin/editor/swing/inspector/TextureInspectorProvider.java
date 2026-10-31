package cn.donting.javelin.editor.swing.inspector;

import java.util.List;

/**
 * 纹理检查器 provider（.texture 键）：纹理编辑内容工厂。
 *
 * @author javelin
 * @create 2026/10/25
 */
public final class TextureInspectorProvider implements InspectorProvider {

    @Override
    public List<String> keys() {
        return List.of(".texture");
    }

    @Override
    public InspectorContent create() {
        return new TextureInspectorContent();
    }
}

package cn.donting.javelin.editor.swing.inspector;

import java.util.List;

/**
 * 图片检查器 provider（.png/.jpg/.jpeg 键）：图片展示内容工厂——三后缀公用同一
 * 内容件（对齐 FileTypeSetup「image」族同图标口径）。
 *
 * @author javelin
 * @create 2026/11/11
 */
public final class ImageInspectorProvider implements InspectorProvider {

    @Override
    public List<String> keys() {
        return List.of(".png", ".jpg", ".jpeg");
    }

    @Override
    public InspectorContent create() {
        return new ImageInspectorContent();
    }
}
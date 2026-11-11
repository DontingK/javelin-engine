package cn.donting.javelin.editor.swing.inspector;

import java.util.List;

/**
 * cubemap 检查器 provider（.hdr/.pano 键）：天空盒六面展示内容工厂——两后缀公用同一
 * 内容件（对齐 FileTypeSetup「cubemap」族同图标口径）。
 *
 * @author javelin
 * @create 2026/11/11
 */
public final class CubemapInspectorProvider implements InspectorProvider {

    @Override
    public List<String> keys() {
        return List.of(".hdr", ".pano");
    }

    @Override
    public InspectorContent create() {
        return new CubemapInspectorContent();
    }
}
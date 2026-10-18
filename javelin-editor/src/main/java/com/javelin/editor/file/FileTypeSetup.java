package com.javelin.editor.file;

/**
 * 文件类型集中显式注册（对齐 EditorMenuSetup/EditorPanelSetup 先例，不做
 * 注解扫描；幂等）。接线在 MainFrame.create——早于项目面板首帧绘制（图标
 * 裁决即查注册表）与菜单条安装（project.open 命令 enabled 求值也查表）。
 *
 * <p>首批注册（2026-10-18 拍板）：.scene（图标 + 打开器）、.java/.json
 * （纯图标）；.shader/.smesh 等要用时再补注册。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public final class FileTypeSetup {

    private static boolean setup;

    private FileTypeSetup() {
    }

    public static void setup() {
        if (setup) {
            return;
        }
        setup = true;
        FileTypeRegistry.register(new SceneFileType());
        FileTypeRegistry.register(new IconFileType("class", ".java"));
        FileTypeRegistry.register(new IconFileType("json", ".json"));
    }
}

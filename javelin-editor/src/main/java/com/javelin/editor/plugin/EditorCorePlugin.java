package com.javelin.editor.plugin;

import com.javelin.editor.file.FileTypeSetup;
import com.javelin.editor.i18n.I18nKey;
import com.javelin.editor.menu.EditorMenuSetup;
import com.javelin.editor.swing.inspector.InspectorSetup;
import com.javelin.editor.swing.panel.EditorPanelSetup;

/**
 * 编辑器本体插件（editor.core）：菜单/面板/文件类型注册整体收口——注册期
 * 早于建窗，引擎依赖（画布/网格状态）经 MainFrame/JavelinEditor 懒取。
 * 与第三方插件走同一条 ServiceLoader 装载链，无特权分支。
 *
 * @author javelin
 * @create 2026/10/20
 */
public final class EditorCorePlugin implements Plugin {

    @Override
    public String namespace() {
        return "editor.core";
    }

    @Override
    public I18nKey name() {
        return I18nKey.of(namespace(), "plugin.name");
    }

    @Override
    public I18nKey description() {
        return I18nKey.of(namespace(), "plugin.description");
    }

    @Override
    public String version() {
        return "0.1.0";
    }

    @Override
    public String author() {
        return "javelin";
    }

    @Override
    public void setup(PluginContext context) {
        EditorLang.init(context.i18n());
        // 语言注册制（2026-10-29 拍板）：支持语言 + 语言包由插件注册；「域+语言+路径」
        // 显式声明，键保持局部键。registerLanguagePack 顺序即域基线（缺键逐键回退）——
        // en_US 先注册 = editor.core 域默认语言；第三方插件同理，自己的基线自己定
        context.registerLanguage("en_US", "English");
        context.registerLanguage("zh_CN", "中文");
        context.registerLanguagePack(namespace(), "en_US", "lang/en_US.properties");
        context.registerLanguagePack(namespace(), "zh_CN", "lang/zh_CN.properties");
        FileTypeSetup.setup(context);
        InspectorSetup.setup(context);
        EditorPanelSetup.setup(context);
        EditorMenuSetup.setup(context);
    }
}

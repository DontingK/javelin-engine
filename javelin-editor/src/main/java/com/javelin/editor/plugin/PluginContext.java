package com.javelin.editor.plugin;

import com.javelin.editor.file.FileType;
import com.javelin.editor.file.FileTypeRegistry;
import com.javelin.editor.i18n.I18nScope;
import com.javelin.editor.menu.CommandService;
import com.javelin.editor.menu.MenuCommand;
import com.javelin.editor.swing.panel.PanelDescriptor;
import com.javelin.editor.swing.panel.PanelRegistry;
import com.javelin.editor.swing.inspector.InspectorProvider;
import com.javelin.editor.swing.inspector.InspectorRegistry;

/**
 * 插件注册门面（{@link Plugin#setup} 唯一入参）：注册经此收口，持有
 * namespace 作宿主归属记录锚点。当前直通各静态注册表（fail-fast 沿用各表）；
 * 贡献前缀化随后续批次接入。
 *
 * @author javelin
 * @create 2026/10/20
 */
public final class PluginContext {

    private final String namespace;
    private final ClassLoader classLoader;
    private final I18nScope i18n;

    /**
     * 宿主构造（每插件一实例）。
     *
     * @param namespace   插件命名空间
     * @param classLoader 插件 classloader（语言包等插件资源来源）
     */
    public PluginContext(String namespace, ClassLoader classLoader) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace 不能为空");
        }
        if (classLoader == null) {
            throw new IllegalArgumentException("classLoader 不能为空");
        }
        this.namespace = namespace;
        this.classLoader = classLoader;
        this.i18n = I18nScope.of(namespace);
    }

    /** 本上下文所属插件的命名空间 */
    public String namespace() {
        return namespace;
    }

    /** 文案 scope（自动带命名空间——插件门面注入用） */
    public I18nScope i18n() {
        return i18n;
    }

    /** 注册面板（转 {@link PanelRegistry}，id 重复 fail-fast） */
    public void registerPanel(PanelDescriptor descriptor) {
        PanelRegistry.register(descriptor);
    }

    /** 注册菜单命令（转 {@link CommandService}，id 重复 fail-fast） */
    public void registerCommand(MenuCommand command) {
        CommandService.register(command);
    }

    /** 注册文件类型（转 {@link FileTypeRegistry}，扩展名重复 fail-fast） */
    public void registerFileType(FileType type) {
        FileTypeRegistry.register(type);
    }

    /** 注册检查器 provider（转 {@link InspectorRegistry}，键重复 fail-fast） */
    public void registerInspector(InspectorProvider provider) {
        InspectorRegistry.register(provider);
    }

    /** 注册支持语言（转 {@link com.javelin.editor.i18n.I18n}，fileName 重复忽略先注册者优先——语言设置菜单选项源） */
    public void registerLanguage(String fileName, String displayName) {
        com.javelin.editor.i18n.I18n.registerLanguage(fileName, displayName);
    }

    /**
     * 注册语言包（转 {@link com.javelin.editor.i18n.I18n#registerPack}——「域 + 语言 +
     * 文件路径」显式声明，文件布局插件自定、一个文件对应一个域；集中式语言包指向
     * 其他插件域即可翻译对方，同键后注册覆盖先注册）。
     *
     * @param namespace 目标域（自己文案传 {@link #namespace()}）
     * @param language  语言文件名（语言注册表键）
     * @param path      语言包文件路径（本插件 classloader 资源路径）
     */
    public void registerLanguagePack(String namespace, String language, String path) {
        com.javelin.editor.i18n.I18n.registerPack(namespace, language, classLoader, path);
    }
}

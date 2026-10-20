package com.javelin.editor.i18n;

/**
 * 插件文案 scope（命名空间绑定）：key() 自动带 namespace 构造 {@link I18nKey}，
 * text() 查表解析。宿主从 PluginContext 构造，插件经静态门面（Lang 形态）持有，
 * 用点零 namespace 书写。
 *
 * @author javelin
 * @create 2026/10/20
 */
public final class I18nScope {

    private final String namespace;

    private I18nScope(String namespace) {
        this.namespace = namespace;
    }

    /**
     * 构造 scope。
     *
     * @param namespace 命名空间（非空）
     * @throws IllegalArgumentException namespace 为空
     */
    public static I18nScope of(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("命名空间不能为空");
        }
        return new I18nScope(namespace);
    }

    /** 命名空间 */
    public String namespace() {
        return namespace;
    }

    /** 构造文案键（自动带 namespace） */
    public I18nKey key(String localKey, Object... args) {
        return I18nKey.of(namespace, localKey, args);
    }

    /** 解析文案（缺失显示 "!key!" 兜底） */
    public String text(String localKey, Object... args) {
        return I18n.get(namespace, localKey, args);
    }
}

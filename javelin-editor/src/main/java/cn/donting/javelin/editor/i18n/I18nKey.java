package cn.donting.javelin.editor.i18n;

import java.util.Arrays;

/**
 * i18n 文案键（值对象）：命名空间 + 局部键 + {} 顺位插值参数。自持 namespace，
 * 跨上下文传递（异常/状态栏等解析方不知来源的场景）也可解析——按 namespace
 * 路由到对应插件语言包，key 为插件局部名。出口统一收本类型，缺失显示
 * "!key!" 兜底；便捷构造（自动带命名空间）见 PluginContext 的 i18n facade。
 *
 * @author javelin
 * @create 2026/10/20
 */
public final class I18nKey {

    private final String namespace;
    private final String key;
    private final Object[] args;

    private I18nKey(String namespace, String key, Object[] args) {
        this.namespace = namespace;
        this.key = key;
        this.args = args;
    }

    /**
     * 构造文案键。
     *
     * @param namespace 命名空间（插件 namespace，非空）
     * @param key       局部键（语言包内键名，非空）
     * @param args      插值参数（可空）
     * @throws IllegalArgumentException namespace 或 key 为空
     */
    public static I18nKey of(String namespace, String key, Object... args) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("i18n 命名空间不能为空");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("i18n key 不能为空");
        }
        return new I18nKey(namespace, key, args);
    }

    /** 命名空间（插件 namespace——语言包路由键） */
    public String namespace() {
        return namespace;
    }

    /** 局部键（语言包内键名） */
    public String key() {
        return key;
    }

    /** 全域名（namespace + "." + key——显示/采集/报告口径） */
    public String fullKey() {
        return namespace + "." + key;
    }

    /** 插值参数（null = 无参数消息） */
    public Object[] args() {
        return args;
    }

    /** 解析文案（显示点入口——缺失显示 "!全域名!" 兜底） */
    public String text() {
        return I18n.get(namespace, key, args);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof I18nKey)) {
            return false;
        }
        I18nKey other = (I18nKey) o;
        return namespace.equals(other.namespace) && key.equals(other.key)
                && Arrays.equals(args, other.args);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * namespace.hashCode() + key.hashCode()) + Arrays.hashCode(args);
    }

    @Override
    public String toString() {
        return "I18nKey[" + fullKey()
                + (args == null || args.length == 0 ? "" : ", args=" + Arrays.toString(args)) + "]";
    }
}

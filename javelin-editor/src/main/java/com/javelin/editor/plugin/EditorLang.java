package com.javelin.editor.plugin;

import com.javelin.editor.i18n.I18nKey;
import com.javelin.editor.i18n.I18nScope;

/**
 * editor.core 文案门面：scope 由 EditorCorePlugin.setup 注入一次，编辑器
 * 代码全域经 key/text 使用，零 namespace 书写。setup 前使用 fail-fast。
 *
 * @author javelin
 * @create 2026/10/20
 */
public final class EditorLang {

    private static volatile I18nScope scope;

    private EditorLang() {
        // 静态门面禁止实例化
    }

    /** 注入 scope（EditorCorePlugin.setup 调，一次） */
    static void init(I18nScope i18n) {
        scope = i18n;
    }

    /** 构造文案键 */
    public static I18nKey key(String localKey, Object... args) {
        return scope().key(localKey, args);
    }

    /** 解析文案（缺失显示 "!key!" 兜底） */
    public static String text(String localKey, Object... args) {
        return scope().text(localKey, args);
    }

    private static I18nScope scope() {
        I18nScope i18n = scope;
        if (i18n == null) {
            throw new IllegalStateException("EditorLang 未初始化（setup 前使用）");
        }
        return i18n;
    }
}

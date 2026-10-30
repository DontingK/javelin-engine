package com.javelin.editor.plugin;

import com.javelin.editor.i18n.I18n;
import com.javelin.editor.i18n.I18nScope;
import org.junit.jupiter.api.BeforeAll;

/**
 * EditorLang 测试基类：装载 zh_CN 语言包并 init 门面（编辑器测试进程内
 * EditorCorePlugin.setup 不跑，异常/文案经 EditorLang 解析须先有 scope）。
 * 语言定为 zh_CN——既有断言（ShaderParserTest 等）针对中文文案。
 * 幂等（setLanguage 启动期一次冻结；多测试类共享 JVM 时后到者跳过）。
 */
public abstract class EditorLangTestBase {

    @BeforeAll
    static void initEditorLang() {
        if (!"zh_CN".equals(I18n.language())) {
            I18n.setLanguage("zh_CN");
        }
        I18n.registerPack("editor.core", "zh_CN", EditorLangTestBase.class.getClassLoader(),
                "lang/zh_CN.properties");
        // 门面重复注入同一 scope 幂等（scope 无状态）
        EditorLang.init(I18nScope.of("editor.core"));
    }
}

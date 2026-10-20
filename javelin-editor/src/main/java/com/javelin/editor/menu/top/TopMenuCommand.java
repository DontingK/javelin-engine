package com.javelin.editor.menu.top;

import com.javelin.editor.i18n.I18nKey;
import com.javelin.editor.menu.MenuCommand;
import com.javelin.editor.plugin.EditorLang;

/**
 * 顶栏菜单命令基类（marker，设计档 Editor_Menu_Design §3.2）。
 *
 * <p>顶栏命令无上下文，本类无状态成员——价值是类型即域标注（命令归属单一域）
 * + label 默认（editor.core 约定 label key = 命令 id，经 {@link EditorLang}）。
 * 域分包：顶栏域基类与顶栏命令同包（menu.top）。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public abstract class TopMenuCommand implements MenuCommand {

    @Override
    public I18nKey label() {
        return EditorLang.key(id());
    }
}

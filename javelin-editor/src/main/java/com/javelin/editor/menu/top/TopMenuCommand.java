package com.javelin.editor.menu.top;

import com.javelin.editor.menu.MenuCommand;

/**
 * 顶栏菜单命令基类（marker，设计档 Editor_Menu_Design §3.2）。
 *
 * <p>顶栏命令无上下文，本类暂无成员——价值是类型即域标注（命令归属单一域）
 * + 留位将来顶栏公共（如出现）。域分包：顶栏域基类与顶栏命令同包（menu.top）。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public abstract class TopMenuCommand implements MenuCommand {
}

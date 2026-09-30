package com.javelin.editor.jcef.method;


import com.javelin.editor.jcef.bridge.BridgeMethod;
import com.javelin.editor.jcef.domain.par.CommandExecutePar;
import com.javelin.editor.menu.CommandService;

/**
 * 命令桥方法：/command/execute（web 侧触发命令，右键菜单/按钮共用，设计档
 * Editor_Menu_Design §6）。命令上下文不经桥参数——命令经域基类读 Java 侧
 * 全局选中状态（§3.2）。
 *
 * @author javelin
 * @create 2026/9/28
 **/
@BridgeMethod("/command")
public class CommandMethod {

    @BridgeMethod("/execute")
    public boolean execute(CommandExecutePar par) {
        if (par == null) {
            return false;
        }
        return CommandService.execute(par.id);
    }
}

package com.javelin.editor.jcef;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandlerAdapter;

/**
 * CEF 右键菜单抑制器：清空默认上下文菜单（Chromium 原生的刷新/后退/检查等），
 * 编辑器右键交互全部归自定义体系（面板右键走 web 自渲染，见 Editor_Menu_Design §5.2）。
 *
 * <p>onBeforeContextMenu 清空 model 即不弹菜单；web 侧 contextmenu DOM 事件
 * 仍正常派发，将来自定义 web 右键菜单不受影响。</p>
 *
 * @author javelin
 * @create 2026/9/30
 */
public class JcefContextMenuHandler extends CefContextMenuHandlerAdapter {

    @Override
    public void onBeforeContextMenu(CefBrowser browser, CefFrame frame,
                                    CefContextMenuParams params, CefMenuModel model) {
        model.clear();
    }
}

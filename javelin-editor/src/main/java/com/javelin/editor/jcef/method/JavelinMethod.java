package com.javelin.editor.jcef.method;


import com.javelin.editor.jcef.bridge.BridgeMethod;
import com.javelin.editor.jcef.domain.par.RectPar;
import com.javelin.editor.window.MainWindow;


/**
 * @create: 2026/7/6
 * @Version 1.0
 **/
@BridgeMethod("/javelin")
public class JavelinMethod {
    @BridgeMethod("/render/rce")
    public void init(RectPar rectPar) throws Exception {
        MainWindow.getInstance().rectChange(rectPar);
    }
}

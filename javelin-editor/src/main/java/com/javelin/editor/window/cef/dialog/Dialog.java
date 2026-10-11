package com.javelin.editor.window.cef.dialog;

import com.javelin.editor.jcef.JcefCreator;
import com.javelin.editor.window.cef.MainFrame;
import org.cef.browser.CefBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dialog.ModalityType;

/**
 * JDialog 版 CEF 容器：加载前端页面（dev server 的 /page/*.html）。
 *
 * <p>通用对话框容器——不持单例，调用方按需创建实例；关闭即 dispose（DISPOSE_ON_CLOSE），
 * CefBrowser 跟手销毁，重开须新建实例。owner 固定取 {@link MainFrame#getInstance()}
 * （未创建时为无主对话框、落屏中央）；模态 {@link ModalityType#APPLICATION_MODAL}，
 * 弹出期间阻塞主窗口输入。</p>
 *
 * <p>线程口径：创建与显示建议在 EDT；桥线程（js-java-bridge 线程池）弹窗须经
 * SwingUtilities.invokeLater——模态 setVisible 阻塞至关闭，invokeAndWait 会占死池线程。</p>
 *
 * <p>已知边界（JDK JDialog javadoc）：模态对话框内的弹出件（JComboBox/JPopupMenu 等）
 * 被强制轻量级，会被 CEF 重组件盖住——弹层交互归前端 HTML，Java 侧勿在此内摆 Swing 弹层。</p>
 *
 * @author javelin
 * @version 1.0
 * @create 2026/10/11
 */
public class Dialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(Dialog.class);

    /** 默认页：前端通用对话框占位页（与其余窗口同一 dev server） */
    private static final String DEFAULT_URL = "http://localhost:5173/page/dialog.html";

    private CefBrowser cefBrowser;

    /**
     * 创建模态 CEF 对话框并加载页面（构造即建浏览器、铺满内容区）。
     *
     * @param url 页面完整地址（可带查询参数，如 .../dialog.html?type=setting）；
     *            null/空白回退默认 dialog.html
     */
    public Dialog(String url) {
        super(MainFrame.getInstance(), ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        // 兜底尺寸与定位，调用方可 setVisible 前 setSize/setTitle 覆盖；无主时落屏中央
        setSize(480, 360);
        setLocationRelativeTo(getOwner());
        loadURL(url == null || url.isBlank() ? DEFAULT_URL : url);
    }

    /** 建浏览器铺满内容区（JS→Java 桥随 JcefCreator.createClient 自动挂载，无需手动接线） */
    private void loadURL(String url) {
        getContentPane().setLayout(new BorderLayout());
        cefBrowser = JcefCreator.createBrowser(url);
        getContentPane().add(cefBrowser.getUIComponent(), BorderLayout.CENTER);
    }

    /** 当前浏览器（Java→JS 推送等用；dispose 后已 close，勿再用） */
    public CefBrowser getCefBrowser() {
        return cefBrowser;
    }

    /** 打开 DevTools（浏览器已建时） */
    public void openDevTools() {
        if (cefBrowser != null) {
            cefBrowser.openDevTools();
        }
    }

    @Override
    public void dispose() {
        // null 防御：浏览器未建成（构造早期异常）即关窗时不 NPE
        if (cefBrowser != null) {
            logger.info("cefBrowser close:{}", getClass());
            cefBrowser.close(true);
            cefBrowser = null;
        }
        super.dispose();
    }
}

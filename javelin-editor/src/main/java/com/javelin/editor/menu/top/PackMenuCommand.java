package com.javelin.editor.menu.top;

import com.javelin.editor.window.cef.dialog.Dialog;

import javax.swing.SwingUtilities;

/**
 * 打包命令（构建域）：弹 CEF 打包对话框（独立前端页 page/pack.html），配置
 * 打包选项（启动场景/构建模式/窗口元信息）。
 *
 * <p>「开始打包」已接真构建（2026-10-11 接入 /build/jar → GradleProject.jarBuild，
 * 同步请求/响应、日志区展示构建输出）；表单选项暂不随构建下发（jarBuild 无参，
 * 选项消费随打包管线批次），关窗仍走对话框右上角 X（DISPOSE_ON_CLOSE）。动
 * CEF/Swing 跳 EDT（线程契约同 RefreshPageMenuCommand）。</p>
 *
 * @author javelin
 * @version 1.0
 * @create 2026/10/11
 */
public final class PackMenuCommand extends TopMenuCommand {

    /** 打包对话框前端页（dev server 独立页，vite 多页入口） */
    private static final String PACK_URL = "http://localhost:5173/page/pack.html";

    @Override
    public String id() {
        return "build.pack";
    }

    @Override
    public String label() {
        return "打包";
    }

    @Override
    public void execute() {
        // 动 CEF 跳 EDT（线程契约，设计档 §3.3）；模态 setVisible 阻塞至对话框关闭
        SwingUtilities.invokeLater(() -> {
            Dialog dialog = new Dialog(PACK_URL);
            dialog.setTitle("打包");
            // 日志区需要纵向空间（构建完成后占余下空间）
            dialog.setSize(560, 480);
            dialog.setVisible(true);
        });
    }
}

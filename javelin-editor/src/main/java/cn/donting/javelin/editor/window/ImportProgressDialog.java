package cn.donting.javelin.editor.window;

import cn.donting.javelin.editor.importer.AssetImportPipeline;
import cn.donting.javelin.editor.plugin.EditorLang;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.*;

/**
 * 导入进度模态小框（焦点扫描触发，Unity 导入窗口口径）：焦点扫描提交待导入任务后
 * 弹出，Swing Timer 轮询在途导入数——归零自动关（模态泵内 Timer 正常触发）。
 * 模态期间编辑器窗口不可交互，导入回调（热重载/渲染侧）走引擎线程不受阻塞。
 *
 * <p>独立窗口不受编辑器面板 2px 间距限（Editor_Swing_Design §2），按自身紧凑设计。</p>
 */
public final class ImportProgressDialog extends JDialog {

    /** 轮询间隔（毫秒） */
    private static final int POLL_INTERVAL_MS = 150;

    private final AssetImportPipeline pipeline;
    private final JLabel statusLabel;
    private final Timer timer;

    public ImportProgressDialog(Frame owner, AssetImportPipeline pipeline) {
        super(owner, EditorLang.text("asset.import.title"), true);
        this.pipeline = pipeline;
        // 不可手动关（关了焦点回落会重扫描重弹循环）；导入完成自动关
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        statusLabel = new JLabel(EditorLang.text("asset.import.progress"), SwingConstants.CENTER);
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        content.add(statusLabel, BorderLayout.NORTH);
        content.add(progressBar, BorderLayout.CENTER);
        setContentPane(content);
        setSize(320, 90);
        setLocationRelativeTo(owner);
        setResizable(false);
        timer = new Timer(POLL_INTERVAL_MS, e -> poll());
        timer.start();
    }

    /** 轮询：在途导入归零 → 自动关 */
    private void poll() {
        int remaining = pipeline.getImportingCount();
        if (remaining <= 0) {
            timer.stop();
            dispose();
            return;
        }
        statusLabel.setText(EditorLang.text("asset.import.progress.remaining", remaining));
    }
}

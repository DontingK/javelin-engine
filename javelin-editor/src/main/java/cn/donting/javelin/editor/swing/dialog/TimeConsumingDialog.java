package cn.donting.javelin.editor.swing.dialog;

import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.util.ThreadUtil;
import cn.donting.javelin.editor.window.MainFrame;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.util.HashMap;
import java.util.Map;

/**
 * 耗时操作提示框：调用侧用 {@link #start()}/{@link #end()}（或 {@link #wrap(Runnable)}）
 * 标记可能慢的后台操作，超阈值（1s）即弹「处理中」指示器，标记全清自动关——让用户知道
 * 编辑器是忙不是死了（导入管线的专用版见 ImportProgressDialog）。
 *
 * <p>契约：EDT 上的标记直接跳过（EDT 正卡在操作里画不出框）；start/end 必须配对，
 * 同线程嵌套按最外层起始时刻计时。</p>
 *
 * @create 2026/11/7
 */
public final class TimeConsumingDialog extends JDialog {

    /** 超过该时长（毫秒）才弹框——短操作不闪 */
    private static final long THRESHOLD_MS = 1000L;
    /** 监视线程轮询间隔（毫秒） */
    private static final long POLL_MS = 200L;

    /** 在跑标记（线程 → 起始时刻 + 嵌套层数），访问一律在 MONITOR_LOCK 内 */
    private static final Map<Thread, Track> TRACKS = new HashMap<>();
    /** 标记表与监视线程状态的互斥锁（判空/置空/登记同锁，避免丢唤醒） */
    private static final Object MONITOR_LOCK = new Object();
    /** 监视线程；null = 未运行（标记清零时置空，下次 start() 重启） */
    private static Thread monitorThread;
    /** 指示器单实例（仅 EDT 访问） */
    private static TimeConsumingDialog instance;

    private final JLabel messageLabel;

    private TimeConsumingDialog() {
        super(MainFrame.getInstance(), EditorLang.text("time.consuming.title"), false);
        // 无边框 + 不可关：纯「忙」指示器，随标记清零自动消失（留 X 会关掉又弹）
        setUndecorated(true);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        // 不抢焦点：展示期间用户照常操作编辑器（AWT 浮层/工具栏口径）
        setFocusableWindowState(false);
        messageLabel = new JLabel("", SwingConstants.CENTER);
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));
        content.add(messageLabel, BorderLayout.NORTH);
        content.add(progressBar, BorderLayout.CENTER);
        setContentPane(content);
        setSize(400, 90);
        setLocationRelativeTo(getOwner());
        setResizable(false);
    }

    /** 标记当前线程开始耗时操作（EDT 调用直接跳过；须与 {@link #end()} 配对） */
    public static void start() {
        if (SwingUtilities.isEventDispatchThread()) {
            // EDT 跳过：弹框的 invokeLater 也排在同一线程上，EDT 卡住时永远画不出来
            return;
        }
        Thread thread = Thread.currentThread();
        synchronized (MONITOR_LOCK) {
            Track track = TRACKS.get(thread);
            if (track == null) {
                TRACKS.put(thread, new Track(System.nanoTime()));
            } else {
                // 嵌套：沿用最外层起始时刻，只累加层数
                track.depth++;
            }
            if (monitorThread == null || !monitorThread.isAlive()) {
                monitorThread = new Thread(TimeConsumingDialog::monitorLoop, "time-consuming-monitor");
                monitorThread.setDaemon(true);
                monitorThread.start();
            }
        }
    }

    /** 结束当前线程的耗时标记（未标记或重复调用忽略） */
    public static void end() {
        Thread thread = Thread.currentThread();
        synchronized (MONITOR_LOCK) {
            Track track = TRACKS.get(thread);
            if (track == null) {
                return;
            }
            if (--track.depth <= 0) {
                TRACKS.remove(thread);
            }
        }
    }

    /** 包装任务：start/end 自动配对，异常路径也清标记 */
    public static Runnable wrap(Runnable task) {
        return () -> {
            start();
            try {
                task.run();
            } finally {
                end();
            }
        };
    }

    /** 监视线程主体：轮询标记表，超阈值投递展示/刷新；表空即退出并交还 monitorThread */
    private static void monitorLoop() {
        boolean postedShow = false;
        while (true) {
            String threadName = null;
            long elapsedMs = 0;
            boolean exiting;
            synchronized (MONITOR_LOCK) {
                exiting = TRACKS.isEmpty();
                if (exiting) {
                    // 判空与置空同锁：start() 的登记+启动也在同锁内，不存在启动漏网
                    monitorThread = null;
                } else {
                    long now = System.nanoTime();
                    for (Map.Entry<Thread, Track> entry : TRACKS.entrySet()) {
                        long ms = (now - entry.getValue().startNanos) / 1_000_000L;
                        if (threadName == null || ms > elapsedMs) {
                            threadName = entry.getKey().getName();
                            elapsedMs = ms;
                        }
                    }
                }
            }
            if (exiting) {
                if (postedShow) {
                    SwingUtilities.invokeLater(() -> applyDialog(false, null, 0));
                }
                return;
            }
            final String name = threadName;
            final long elapsed = elapsedMs;
            boolean show = elapsed >= THRESHOLD_MS;
            if (show || postedShow) {
                // 展示中每轮刷新已用时；状态翻转必投递一次
                SwingUtilities.invokeLater(() -> applyDialog(show, name, elapsed));
            }
            postedShow = show;
            ThreadUtil.sleep(POLL_MS);
        }
    }

    /** 展示/收起指示器（EDT 调用）：有标记超阈值才在场，标记清零即销毁 */
    private static void applyDialog(boolean show, String threadName, long elapsedMs) {
        if (!show) {
            if (instance != null) {
                instance.dispose();
                instance = null;
            }
            return;
        }
        if (instance == null || !instance.isDisplayable()) {
            instance = new TimeConsumingDialog();
        }
        instance.messageLabel.setText(EditorLang.text("time.consuming.message",
                threadName, elapsedMs / 1000L));
        if (!instance.isVisible()) {
            instance.setVisible(true);
        }
    }

    /** 单线程标记：最外层起始时刻 + 嵌套层数 */
    private static final class Track {
        private final long startNanos;
        private int depth = 1;

        private Track(long startNanos) {
            this.startNanos = startNanos;
        }
    }
}

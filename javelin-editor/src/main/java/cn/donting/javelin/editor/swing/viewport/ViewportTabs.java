package cn.donting.javelin.editor.swing.viewport;

import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.scene.SceneModel;
import cn.donting.javelin.editor.swing.dock.TabStrip;
import cn.donting.javelin.editor.swing.panel.EditorPanel;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.window.cef.CanvasSurface;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;

/**
 * 视口标签区（Editor_Swing_Design §6——TabNode 特化：单 Canvas 双视图）。
 *
 * <p>结构：视图标签条（场景|游戏）+ 随视图切换的工具栏（场景=Tools 组+局部/
 * 世界；游戏=同高空条占位，显示比例/Stats 族后置）+ 常驻 {@link ViewportHost}
 * （画布<b>不 reparent</b>）。标签切换 = 换工具栏 + {@code setSceneModel}
 * （SCENE/GAME，volatile 直写 EditorScene，语义对齐原 web onShow 上报）。</p>
 *
 * <p>以单面板 "viewport" 注册（不可关）——DockTabs 单面板不可关不显示外层
 * 标签条，无缝只剩本条（场景/游戏分离式停靠阻塞于多 swapchain，后置——
 * Editor_Swing_Design §6 开放项）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public final class ViewportTabs extends JComponent implements EditorPanel {

    /** 视图索引：0=场景（SCENE）1=游戏（GAME） */
    private int activeView;

    private final SceneViewToolbar sceneToolbar = new SceneViewToolbar();
    private final EmptyToolbar gameToolbar = new EmptyToolbar();
    private final JPanel body = new JPanel(new BorderLayout(0, 0));
    private final ViewportHost host;

    public ViewportTabs(CanvasSurface canvas) {
        this.host = new ViewportHost(canvas);
        setLayout(new BorderLayout(0, 0));
        TabStrip strip = new TabStrip(List.of(
                new TabStrip.TabItem(EditorLang.text("viewport.scene"), false),
                new TabStrip.TabItem(EditorLang.text("viewport.game"), false)), activeView, index -> {
            if (index == activeView) {
                return;
            }
            activeView = index;
            applyView();
        });
        add(strip, BorderLayout.NORTH);
        body.add(sceneToolbar, BorderLayout.NORTH);
        body.add(host, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
    }

    /** 标签切换：换工具栏 + 视图模式直写 */
    private void applyView() {
        body.remove(activeView == 0 ? gameToolbar : sceneToolbar);
        body.add(activeView == 0 ? sceneToolbar : gameToolbar, BorderLayout.NORTH);
        applySceneModel();
        body.revalidate();
        body.repaint();
    }

    /** 视图模式直写（volatile，引擎线程 update 帧内消费；引擎未就绪防御跳过） */
    private void applySceneModel() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return;
        }
        editor.getEditorScene().setSceneModel(activeView == 0 ? SceneModel.SCENE : SceneModel.GAME);
    }

    @Override
    public void onShow() {
        // 面板重新可见时恢复当前视图模式（M1 无隐藏路径，扩展位保语义）
        applySceneModel();
    }

    @Override
    public void onHide() {
        // noop（对齐原 web 视口面板 onHide）
    }

    /** 游戏视图工具栏空条占位（同高；内容留待 Game 视图工具设计——Unity 比例/Stats 族） */
    private static final class EmptyToolbar extends JComponent {
        @Override
        public java.awt.Dimension getPreferredSize() {
            return new java.awt.Dimension(super.getPreferredSize().width, 24);
        }
    }
}

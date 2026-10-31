package cn.donting.javelin.editor.swing;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;
import cn.donting.javelin.editor.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SVG 图标实现，支持从 classpath 的 {@code /icon/} 目录自动加载 SVG 资源；
 * 宿主未命中时按插件装载序依次查各插件 classloader（同名图标先装载者优先）。
 *
 * <p>实现 {@link Icon} 接口，可直接用于 {@link JLabel}、{@link JButton}、{@link JMenuItem} 等 Swing 组件。</p>
 *
 * <p>内部缓存 {@link SVGDocument}，避免重复解析 SVG 文件。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class SvgIcon implements Icon {

    private static final Logger LOGGER = LoggerFactory.getLogger(SvgIcon.class);
    private static final SVGLoader LOADER = new SVGLoader();
    private static final Map<String, SVGDocument> CACHE = new ConcurrentHashMap<>();

    private final String iconName;
    private final int width;
    private final int height;
    private BufferedImage renderedImage;

    /**
     * 创建 SVG 图标。
     *
     * @param iconName SVG 文件名（不含路径和扩展名，如 {@code "add"}）
     * @param width    渲染宽度（像素）
     * @param height   渲染高度（像素）
     */
    public SvgIcon(String iconName, int width, int height) {
        this.iconName = iconName;
        this.width = width;
        this.height = height;
    }

    /**
     * 创建等宽高 SVG 图标。
     *
     * @param iconName SVG 文件名（不含路径和扩展名）
     * @param size     渲染宽高（像素）
     * @return SVG 图标实例
     */
    public static SvgIcon of(String iconName, int size) {
        return new SvgIcon(iconName, size, size);
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        SVGDocument doc = loadDocument(iconName);
        if (doc == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            doc.render(null, g2, new ViewBox(0, 0, width, height));
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return width;
    }

    @Override
    public int getIconHeight() {
        return height;
    }

    /**
     * 判断 SVG 资源是否可用。
     *
     * @return 如果 SVG 文件存在且解析成功则返回 {@code true}
     */
    public boolean isAvailable() {
        return loadDocument(iconName) != null;
    }

    /**
     * 获取渲染后的图像，首次调用时解析并渲染 SVG。
     *
     * <p>根据系统 DPI 缩放因子创建高分辨率图像，避免在高 DPI 下变糊。</p>
     *
     * @return 渲染后的 {@link BufferedImage}，加载失败则返回 {@code null}
     */
    public BufferedImage toImage() {
        if (renderedImage != null) {
            return renderedImage;
        }
        SVGDocument doc = loadDocument(iconName);
        if (doc == null) {
            return null;
        }
        float scale = getSystemScale();
        int scaledWidth = Math.round(width * scale);
        int scaledHeight = Math.round(height * scale);
        renderedImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = renderedImage.createGraphics();
        try {
            g2.scale(scale, scale);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            doc.render(null, g2, new ViewBox(0, 0, width, height));
        } finally {
            g2.dispose();
        }
        return renderedImage;
    }

    /**
     * 获取主屏幕的 DPI 缩放因子。
     *
     * @return 缩放比例，如 1.0f、1.25f、1.5f、2.0f
     */
    private static float getSystemScale() {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        return (float) gc.getDefaultTransform().getScaleX();
    }

    /**
     * 加载 SVG 文档：宿主 classpath 先查，未命中按装载序依次查插件 classloader。
     *
     * @param name SVG 文件名（不含扩展名）
     * @return 解析后的 {@link SVGDocument}，失败返回 {@code null}
     */
    private static SVGDocument loadDocument(String name) {
        String path = "/icon/" + name + ".svg";
        return CACHE.computeIfAbsent(path, p -> {
            URL url = SvgIcon.class.getResource(p);
            if (url == null) {
                url = PluginManager.findResource(p);
            }
            if (url == null) {
                LOGGER.warn("SVG resource not found: {}", p);
                return null;
            }
            try {
                return LOADER.load(url);
            } catch (Exception e) {
                LOGGER.error("Failed to load SVG: {}", p, e);
                return null;
            }
        });
    }
}

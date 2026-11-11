package cn.donting.javelin.editor.swing.inspector.widget;

import cn.donting.javelin.core.render.texture.CubemapImage;
import cn.donting.javelin.core.render.texture.Image;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.swing.dialog.ImageViewerDialog;
import cn.donting.javelin.rhi.types.Format;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.function.Supplier;

/**
 * 图片预览件（纹理检查器 / 图片检查器 / cubemap 检查器共用）：像素资产 → 等比缩略图。
 *
 * <p>{@link #thumbnail} / {@link #cubemapCross} 是纯数据转换（可在后台线程调用）；
 * {@link #framed} 出 EDT 侧展示图件（1px 描边分辨透明区，色值取 UIManager）；
 * {@link #viewable} 在其上再加一个「查看」按钮（弹 {@link ImageViewerDialog} 可缩放
 * 查看窗，大图由 {@code Supplier} 后台产出）。仅支持
 * 8 位四通道 RGBA/BGRA（导入链固定产 {@code R8G8B8A8_UNORM}，sRGB 同字节序）；其余格式、
 * 无像素、空引用一律返回 null，调用方据此不出预览（正常域空态，不报错）。</p>
 *
 * @author javelin
 * @create 2026/11/11
 */
public final class ImagePreview {

    /** cubemap 十字预览布局：4 列 × 3 行（标准立方图十字） */
    private static final int CROSS_COLS = 4;
    private static final int CROSS_ROWS = 3;

    /** 8 位四通道字节序（导入链只产这两种） */
    private enum ChannelOrder {
        RGBA,
        BGRA
    }

    private ImagePreview() {
    }

    /**
     * Image 像素 → 缩略图（等比缩放进 maxBox 方框，限制维贴满——小图放大可见、大图降采样）。
     *
     * @param image  像素资产，可为 null
     * @param maxBox 方框边长（px）
     * @return 缩略图；空引用 / 无像素 / 非支持格式为 null
     */
    public static BufferedImage thumbnail(Image image, int maxBox) {
        BufferedImage full = full(image);
        return full == null ? null : scaleToBox(full, maxBox);
    }

    /**
     * Image 像素 → 原始尺寸 BufferedImage（查看窗用，不缩放）。
     *
     * @param image 像素资产，可为 null
     * @return 原图；空引用 / 无像素 / 非支持格式为 null
     */
    public static BufferedImage full(Image image) {
        if (image == null) {
            return null;
        }
        byte[] pixels = image.getPixels();
        int width = image.getWidth();
        int height = image.getHeight();
        if (pixels == null || width <= 0 || height <= 0 || pixels.length < width * height * 4) {
            return null;
        }
        ChannelOrder order = channelOrder(image.getFormat());
        if (order == null) {
            return null;
        }
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, width, height, argb(pixels, 0, width * height, order), 0, width);
        return img;
    }

    /**
     * CubemapImage 六面 → 十字拼图缩略图（标准立方图布局：4 列 × 3 行——+Y 上、
     * -X +Z +X -Z 横排、-Y 下）。
     *
     * @param cubemap 六面像素资产，可为 null
     * @param maxBox  十字图总宽上限（px；每面格 = maxBox/4）
     * @return 十字缩略图；空引用 / 无像素 / 非支持格式为 null
     */
    public static BufferedImage cubemapCross(CubemapImage cubemap, int maxBox) {
        if (cubemap == null) {
            return null;
        }
        byte[] pixels = cubemap.getPixels();
        int faceSize = cubemap.getFaceSize();
        if (pixels == null || faceSize <= 0
                || pixels.length < CubemapImage.FACE_COUNT * faceSize * faceSize * 4) {
            return null;
        }
        ChannelOrder order = channelOrder(cubemap.getFormat());
        if (order == null) {
            return null;
        }

        int cell = Math.max(1, maxBox / CROSS_COLS);
        BufferedImage out = new BufferedImage(cell * CROSS_COLS, cell * CROSS_ROWS,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // 十字槽位：{面索引（+X=0/-X=1/+Y=2/-Y=3/+Z=4/-Z=5）, 列, 行}
        int[][] slots = {
                {2, 1, 0}, // +Y
                {1, 0, 1}, // -X
                {4, 1, 1}, // +Z
                {0, 2, 1}, // +X
                {5, 3, 1}, // -Z
                {3, 1, 2}, // -Y
        };
        int facePixels = faceSize * faceSize;
        for (int[] slot : slots) {
            BufferedImage face = new BufferedImage(faceSize, faceSize, BufferedImage.TYPE_INT_ARGB);
            face.setRGB(0, 0, faceSize, faceSize,
                    argb(pixels, slot[0] * facePixels * 4, facePixels, order), 0, faceSize);
            g.drawImage(face, slot[1] * cell, slot[2] * cell, cell, cell, null);
        }
        g.dispose();
        return out;
    }

    /**
     * 预览图件（EDT）：缩略图包成带 1px 描边的 JLabel。
     *
     * @param preview {@link #thumbnail} / {@link #cubemapCross} 的结果，非 null
     */
    public static JComponent framed(BufferedImage preview) {
        JLabel label = new JLabel(new ImageIcon(preview));
        Color border = UIManager.getColor("Component.borderColor");
        if (border == null) {
            border = UIManager.getColor("Separator.foreground");
        }
        if (border != null) {
            label.setBorder(BorderFactory.createLineBorder(border));
        }
        return label;
    }

    /**
     * 预览 + 「查看」按钮组合件：点按钮在后台算出大图（{@code fullLoader}）后弹
     * {@link ImageViewerDialog} 可缩放查看窗。
     *
     * @param preview    已算好的缩略图（非 null）
     * @param fullLoader 大图产出器（后台线程调用；null 则按钮禁用）
     * @param title      查看窗标题（一般传文件名）
     */
    public static JComponent viewable(BufferedImage preview, Supplier<BufferedImage> fullLoader, String title) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.add(framed(preview), BorderLayout.CENTER);
        JButton view = new JButton(EditorLang.text("image.preview.view"));
        view.setMargin(new Insets(1, 2, 1, 2));
        if (fullLoader == null) {
            view.setEnabled(false);
        } else {
            view.addActionListener(e -> openViewer(view, fullLoader, title));
        }
        panel.add(view, BorderLayout.SOUTH);
        return panel;
    }

    /** 后台算大图 → EDT 弹查看窗（大图解码/缩放不进 EDT） */
    private static void openViewer(JComponent source, Supplier<BufferedImage> fullLoader, String title) {
        Window owner = SwingUtilities.getWindowAncestor(source);
        Thread loader = new Thread(() -> {
            BufferedImage full = fullLoader.get();
            SwingUtilities.invokeLater(() -> ImageViewerDialog.show(owner, title, full));
        }, "image-viewer-load");
        loader.setDaemon(true);
        loader.start();
    }

    private static BufferedImage scaleToBox(BufferedImage src, int max) {
        int width = src.getWidth();
        int height = src.getHeight();
        int targetW;
        int targetH;
        if (width >= height) {
            targetW = max;
            targetH = Math.max(1, Math.round((float) height * max / width));
        } else {
            targetH = max;
            targetW = Math.max(1, Math.round((float) width * max / height));
        }
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, targetW, targetH, null);
        g.dispose();
        return out;
    }

    /** 像素格式 → 字节序；非 8 位四通道返回 null（不预览） */
    private static ChannelOrder channelOrder(Format format) {
        if (format == Format.R8G8B8A8_UNORM || format == Format.R8G8B8A8_SRGB) {
            return ChannelOrder.RGBA;
        }
        if (format == Format.B8G8R8A8_UNORM || format == Format.B8G8R8A8_SRGB) {
            return ChannelOrder.BGRA;
        }
        return null;
    }

    /** 紧密打包 8 位四通道字节 → ARGB int[]（前导出 INT_ARGB 缓冲） */
    private static int[] argb(byte[] pixels, int offset, int pixelCount, ChannelOrder order) {
        int[] out = new int[pixelCount];
        boolean bgra = order == ChannelOrder.BGRA;
        for (int i = 0, p = offset; i < pixelCount; i++, p += 4) {
            int c0 = pixels[p] & 0xFF;
            int g = pixels[p + 1] & 0xFF;
            int c2 = pixels[p + 2] & 0xFF;
            int a = pixels[p + 3] & 0xFF;
            int r = bgra ? c2 : c0;
            int b = bgra ? c0 : c2;
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return out;
    }
}
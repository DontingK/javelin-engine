package com.javelin.editor.swing.panel;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.util.function.Supplier;

/**
 * 面板描述符（注册单元，Editor_Swing_Design §5.2）——布局树只引用 id。
 *
 * <p>构建走 Builder（对齐 Command.of 先例）；factory 即装即造（组件实例归
 * {@code DockRoot} 缓存，注册表无状态）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public final class PanelDescriptor {

    private final String id;
    private final String title;
    private final String icon;
    private final boolean closable;
    private final Supplier<JComponent> factory;
    /** 面板最小尺寸（Dock 区域最小宽/高的约束源；null = 不约束） */
    private final Dimension minimumSize;

    private PanelDescriptor(Builder builder) {
        this.id = builder.id;
        this.title = builder.title;
        this.icon = builder.icon;
        this.closable = builder.closable;
        this.factory = builder.factory;
        this.minimumSize = builder.minimumSize;
    }

    /**
     * 创建构建器。
     *
     * @param id    面板稳定标识（如 "hierarchy"）
     * @param title 标签标题
     * @return 构建器
     */
    public static Builder of(String id, String title) {
        return new Builder(id, title);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    /** 图标名（classpath /icon/{name}.svg；null = 无图标，M1 预留位） */
    public String getIcon() {
        return icon;
    }

    public boolean isClosable() {
        return closable;
    }

    /** 面板最小尺寸（null = 不约束；经 DockTabs 聚合上汇进分割条钳制） */
    public Dimension getMinimumSize() {
        return minimumSize;
    }

    /** 造面板组件（factory 首调；实例归 DockRoot 缓存） */
    JComponent createComponent() {
        return factory.get();
    }

    /**
     * 面板描述符构建器。
     */
    public static final class Builder {
        private final String id;
        private final String title;
        private String icon;
        private boolean closable;
        private Supplier<JComponent> factory;
        private Dimension minimumSize;

        private Builder(String id, String title) {
            if (id == null || id.isBlank() || title == null || title.isBlank()) {
                throw new IllegalArgumentException("面板 id 与 title 不能为空");
            }
            this.id = id;
            this.title = title;
        }

        public Builder icon(String icon) {
            this.icon = icon;
            return this;
        }

        public Builder closable(boolean closable) {
            this.closable = closable;
            return this;
        }

        /** 面板最小尺寸（区域最小宽/高约束源，0 维不约束传 0 由聚合取最大覆盖） */
        public Builder minimumSize(int width, int height) {
            this.minimumSize = new Dimension(Math.max(0, width), Math.max(0, height));
            return this;
        }

        public Builder factory(Supplier<JComponent> factory) {
            this.factory = factory;
            return this;
        }

        public PanelDescriptor build() {
            if (factory == null) {
                throw new IllegalArgumentException("面板缺少 factory: " + id);
            }
            return new PanelDescriptor(this);
        }
    }
}

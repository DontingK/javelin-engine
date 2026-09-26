package com.javelin.core.settings;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.math.Color;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

/**
 * Javelin引擎设置类
 */
@Reflective
public class JavelinSetting implements Serializable {

    private int width=1080;
    private int height=720;
    private String title="Javelin Engine 5";
    /**
     * 是否启用垂直同步。
     */
    private boolean vsync = true;
    /**
     * 默认背景颜色（引擎三处清屏的统一来源：无渲染系统清屏 / 无相机兜底 /
     * 前向 Opaque Pass 清屏，JavelinEngine.clearColor 消费）。
     */
    private Color backgroundColor = new Color(0.05f, 0.05f, 0.08f, 1f);

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isVsync() {
        return vsync;
    }

    public void setVsync(boolean vsync) {
        this.vsync = vsync;
    }

    public Color getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(Color backgroundColor) {
        if (backgroundColor == null) {
            throw new IllegalArgumentException("backgroundColor must not be null");
        }
        this.backgroundColor = backgroundColor;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("width",width);
        output.write("height",height);
        output.write("title",title);
        output.write("vsync",vsync);
        output.write("backgroundColor",backgroundColor);
    }

    @Override
    public void read(InputCapsule input) {
        width=input.readInt("width");
        height=input.readInt("height");
        title=input.readString("title");
        vsync=input.readBoolean("vsync");
        // 旧设置文件无此键时保留默认（readSerializable 缺键返回 null）
        Color bg=input.readSerializable("backgroundColor", Color.class);
        if (bg != null) {
            backgroundColor = bg;
        }
    }
}

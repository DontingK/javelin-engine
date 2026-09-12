package com.javelin.core.settings;

import com.javelin.ast.annotation.Reflective;
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

    @Override
    public void write(OutputCapsule output) {
        output.write("width",width);
        output.write("height",height);
        output.write("title",title);
    }

    @Override
    public void read(InputCapsule input) {
        width=input.readInt("width");
        height=input.readInt("height");
        title=input.readString("title");
    }
}

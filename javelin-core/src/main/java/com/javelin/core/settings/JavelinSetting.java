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

    /**
     * 抗锯齿模式（v0.1 仅 MSAA 档：离屏 MSAA 附件 + resolve 回 backbuffer，
     * overlay 恒 1x；引擎启动期消费，运行期切换后置）。默认关闭。
     */
    private AntialiasingMode antialiasing = AntialiasingMode.OFF;

    /**
     * 引擎帧率上限（mainLoop 帧末限帧）。0 = 不限制（默认）。
     *
     * <p>vsync（MAILBOX 呈现）下建议设为刷新率 × ~1.1：MAILBOX 生产端不限速、
     * 由本字段兜底；余量是承重的——限得恰好等于刷新率会偶发「vblank 无新帧→
     * 旧帧重复显示」（FIFO 同病灶，Vulkan_Pitfalls #21）。
     */
    private int maxFps = 144;

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

    public AntialiasingMode getAntialiasing() {
        return antialiasing;
    }

    public void setAntialiasing(AntialiasingMode antialiasing) {
        if (antialiasing == null) {
            throw new IllegalArgumentException("antialiasing must not be null");
        }
        this.antialiasing = antialiasing;
    }

    public int getMaxFps() {
        return maxFps;
    }

    public void setMaxFps(int maxFps) {
        this.maxFps = Math.max(0, maxFps);
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("width",width);
        output.write("height",height);
        output.write("title",title);
        output.write("vsync",vsync);
        output.write("backgroundColor",backgroundColor);
        output.write("antialiasing",antialiasing);
        output.write("maxFps",maxFps);
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
        // 旧设置文件无此键时保留默认（readEnum 缺键返回 null）
        AntialiasingMode aa=input.readEnum("antialiasing", AntialiasingMode.class);
        if (aa != null) {
            antialiasing = aa;
        }
        // 旧设置文件缺键 readInt=0，恰为本字段默认语义（0=不限制）——无迁移坑
        maxFps = Math.max(0, input.readInt("maxFps"));
    }
}

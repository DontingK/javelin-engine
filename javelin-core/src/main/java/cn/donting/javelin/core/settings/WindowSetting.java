package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 窗口设置：尺寸 / 标题 / 垂直同步 / 帧率上限。
 *
 * <p>引擎启动期消费（建窗、swapchain 呈现模式、帧末限帧）；编辑器启动链会覆盖 vsync 与
 * maxFps（恒关 vsync + maxFps 限帧，Vulkan_Pitfalls #21），故编辑器侧不是用户可改项——
 * 面板是否暴露待注解引擎落地时定（见类内注解）。</p>
 */
@Reflective
@Setting(label = "projectSettings.category.window", editorStartup = false)
public class WindowSetting implements Serializable {

    /** 窗口宽（逻辑尺寸，像素） */
    @InsField(label = "projectSettings.windowWidth")
    private int width = 1080;
    /** 窗口高（逻辑尺寸，像素） */
    @InsField(label = "projectSettings.windowHeight")
    private int height = 720;
    /** 窗口标题 */
    @InsField(label = "projectSettings.windowTitle")
    private String title = "Javelin Engine 5";
    /** 垂直同步：true → MAILBOX 呈现，false → IMMEDIATE（swapchain 描述来源） */
    @InsField(label = "projectSettings.windowVsync")
    @SettingRestart
    @SettingHint("projectSettings.windowVsyncHint")
    private boolean vsync = true;
    /**
     * 帧率上限（mainLoop 帧末限帧）。0 = 不限制。
     *
     * <p>vsync（MAILBOX 呈现）下建议设为刷新率 × ~1.1：MAILBOX 生产端不限速、
     * 由本字段兜底；余量是承重的——限得恰好等于刷新率会偶发「vblank 无新帧→
     * 旧帧重复显示」（FIFO 同病灶，Vulkan_Pitfalls #21）。
     */
    @InsField(label = "projectSettings.windowMaxFps")
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

    public int getMaxFps() {
        return maxFps;
    }

    public void setMaxFps(int maxFps) {
        this.maxFps = Math.max(0, maxFps);
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("width", width);
        output.write("height", height);
        output.write("title", title);
        output.write("vsync", vsync);
        output.write("maxFps", maxFps);
    }

    @Override
    public void read(InputCapsule input) {
        // 默认值 = 字段声明初值；负值钳制是取值合法性守卫（0 = 不限制是合法取值）
        width = input.readInt("width", 1080);
        height = input.readInt("height", 720);
        title = input.readString("title", "Javelin Engine 5");
        vsync = input.readBoolean("vsync", true);
        maxFps = Math.max(0, input.readInt("maxFps", 144));
    }
}

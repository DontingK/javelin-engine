package com.javelin.rhi.render;

/**
 * 平台枚举。
 * 决定 RHI 加载哪个 surface 扩展（设计文档第 23 节）。
 */
public enum Platform {
    WINDOWS("VK_KHR_win32_surface"),
    X11("VK_KHR_xlib_surface"),
    WAYLAND("VK_KHR_wayland_surface"),
    MACOS("VK_EXT_metal_surface");

    public final String surfaceExtension;

    Platform(String ext) {
        this.surfaceExtension = ext;
    }
}

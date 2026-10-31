package cn.donting.javelin.rhi.render;

/**
 * 平台枚举。
 * 决定 RHI 加载哪个 surface 扩展（设计文档第 23 节）。
 */
public enum Platform {
    /** Windows（Win32 surface） */
    WINDOWS("VK_KHR_win32_surface"),
    /** Linux X11（Xlib surface） */
    X11("VK_KHR_xlib_surface"),
    /** Linux Wayland（Wayland surface） */
    WAYLAND("VK_KHR_wayland_surface"),
    /** macOS（Metal surface） */
    MACOS("VK_EXT_metal_surface");

    public final String surfaceExtension;

    Platform(String ext) {
        this.surfaceExtension = ext;
    }
}

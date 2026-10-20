package com.javelin.editor.plugin;

/**
 * 插件装载状态：LOADED = 实例化与元数据校验通过（setup 归宿主管线）；
 * FAILED = 装载失败，仅第三方——editor.core 失败直接 fail-fast 不产出。
 *
 * @author javelin
 * @create 2026/10/20
 */
public enum PluginState {

    /** 装载成功 */
    LOADED,

    /** 装载失败（原因见 PluginInstance#error） */
    FAILED
}

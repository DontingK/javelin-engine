package cn.donting.javelin.rhi.data;

/**
 * 清屏值（密封接口）。
 * 仅允许 RHIClearColor 和 RHIClearDepthStencil。
 */
public sealed interface RHIClearValue
        permits RHIClearColor, RHIClearUintColor, RHIClearDepthStencil {
}

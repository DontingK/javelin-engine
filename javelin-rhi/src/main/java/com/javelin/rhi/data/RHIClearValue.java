package com.javelin.rhi.data;

/**
 * 清除值的密封接口，表示渲染附件清除时可使用的值类型。
 * 当前实现包括颜色清除值和深度/模板清除值。
 */
public sealed interface RHIClearValue permits RHIClearColor, RHIClearDepthStencil {
}

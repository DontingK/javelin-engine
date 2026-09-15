package com.javelin.rhi.data;

/**
 * 深度/模板清除值，用于定义深度模板附件清除时的深度值和模板值。
 *
 * @param depth   深度清除值，范围 [0, 1]
 * @param stencil 模板清除值
 */
public record RHIClearDepthStencil(float depth, int stencil) implements RHIClearValue {
}

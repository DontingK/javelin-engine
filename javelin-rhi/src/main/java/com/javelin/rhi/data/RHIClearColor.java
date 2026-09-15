package com.javelin.rhi.data;

/**
 * 颜色清除值，用于定义渲染目标清除时的颜色。
 *
 * @param r 红色分量，范围 [0, 1]
 * @param g 绿色分量，范围 [0, 1]
 * @param b 蓝色分量，范围 [0, 1]
 * @param a 透明度分量，范围 [0, 1]
 */
public record RHIClearColor(float r, float g, float b, float a) implements RHIClearValue {
}

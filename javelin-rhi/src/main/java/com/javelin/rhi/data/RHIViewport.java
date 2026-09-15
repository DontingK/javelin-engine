package com.javelin.rhi.data;

/**
 * 视口配置，定义渲染输出到屏幕的坐标变换区域。
 *
 * @param x        视口左下角 x 坐标
 * @param y        视口左下角 y 坐标
 * @param width    视口宽度
 * @param height   视口高度
 * @param minDepth 最小深度值，范围 [0, 1]
 * @param maxDepth 最大深度值，范围 [0, 1]
 */
public record RHIViewport(float x, float y, float width, float height, float minDepth, float maxDepth) {
}

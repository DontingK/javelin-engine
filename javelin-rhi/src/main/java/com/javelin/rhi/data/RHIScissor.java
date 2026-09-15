package com.javelin.rhi.data;

/**
 * 裁剪矩形，用于限制渲染输出的像素区域。
 *
 * @param x      裁剪矩形左上角 x 坐标
 * @param y      裁剪矩形左上角 y 坐标
 * @param width  裁剪矩形宽度
 * @param height 裁剪矩形高度
 */
public record RHIScissor(int x, int y, int width, int height) {
}

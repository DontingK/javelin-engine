package cn.donting.javelin.rhi.types;

/**
 * 多边形光栅化模式。
 * 对照 VkPolygonMode。
 */
public enum PolygonMode {
    /** 三角形填充绘制（常规渲染） */
    FILL,                   // VK_POLYGON_MODE_FILL
    /** 只绘制三角形边线（线框模式，需 fillModeNonSolid 特性） */
    LINE,                   // VK_POLYGON_MODE_LINE
    /** 只绘制三角形顶点（需 fillModeNonSolid 特性） */
    POINT;                  // VK_POLYGON_MODE_POINT
}

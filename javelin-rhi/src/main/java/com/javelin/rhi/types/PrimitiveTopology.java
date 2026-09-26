package com.javelin.rhi.types;

/**
 * 图元拓扑。
 * 对照 VkPrimitiveTopology。
 */
public enum PrimitiveTopology {
    /** 点列表：每顶点一个独立点 */
    POINT_LIST,             // VK_PRIMITIVE_TOPOLOGY_POINT_LIST
    /** 线列表：每 2 个顶点一条独立线段 */
    LINE_LIST,              // VK_PRIMITIVE_TOPOLOGY_LINE_LIST
    /** 线带：顶点序列连续折线（第 i 顶点与第 i+1 顶点相连） */
    LINE_STRIP,             // VK_PRIMITIVE_TOPOLOGY_LINE_STRIP
    /** 三角形列表：每 3 个顶点一个独立三角形（默认） */
    TRIANGLE_LIST,          // VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
    /** 三角形带：滑动窗口连续三角（顶点 i,i+1,i+2 组成第 i 个三角形，隔行翻转绕序） */
    TRIANGLE_STRIP,         // VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
    /** 三角形扇：共享首顶点的扇形（适合圆盘/圆锥帽） */
    TRIANGLE_FAN,           // VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN
    /** 线列表（邻接信息）：每条线段额外携带 2 个邻接顶点（几何着色器用） */
    LINE_LIST_ADJACENCY,    // VK_PRIMITIVE_TOPOLOGY_LINE_LIST_WITH_ADJACENCY
    /** 线带（邻接信息）：线带首尾额外各带 1 个邻接顶点（几何着色器用） */
    LINE_STRIP_ADJACENCY,   // VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY
    /** 三角形列表（邻接信息）：每三角形额外携带 3 个邻接顶点（几何着色器用） */
    TRIANGLE_LIST_ADJACENCY, // VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST_WITH_ADJACENCY
    /** 三角形带（邻接信息）：带首尾额外各带 2 个邻接顶点（几何着色器用） */
    TRIANGLE_STRIP_ADJACENCY, // VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY
    /** 面片列表：细分着色器控制点域（需配套 tessellation 状态） */
    PATCH_LIST;             // VK_PRIMITIVE_TOPOLOGY_PATCH_LIST
}

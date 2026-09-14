package com.javelin.core.render.mesh;

import com.javelin.rhi.types.VertexFormat;

/**
 * 顶点属性类型 —— 定义 Mesh 支持的顶点属性及其格式。
 *
 * <p>参考 engine3 设计，每种属性包含组件数、数据类型、是否归一化等信息，
 * 用于 {@link MeshBuilder} 自动构建 {@link com.javelin.rhi.desc.VertexLayout}。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public enum VertexAttribute {

    /** 位置 (xyz) —— float, 3 components */
    POSITION(VertexFormat.R32G32B32_SFLOAT, 3, false),

    /** 法线 (xyz) —— float, 3 components */
    NORMAL(VertexFormat.R32G32B32_SFLOAT, 3, false),

    /** 纹理坐标 (uv) —— float, 2 components */
    TEX_COORD0(VertexFormat.R32G32_SFLOAT, 2, false),

    /** 第二套纹理坐标 (uv) —— float, 2 components */
    TEX_COORD1(VertexFormat.R32G32_SFLOAT, 2, false),

    /** 颜色 (rgba) —— float, 4 components */
    COLOR(VertexFormat.R32G32B32A32_SFLOAT, 4, false),

    /** 切线 (xyzw) —— float, 4 components */
    TANGENT(VertexFormat.R32G32B32A32_SFLOAT, 4, false),

    /** 骨骼索引 (4个) —— int, 4 components */
    BONE_INDICES(VertexFormat.R32G32B32A32_SINT, 4, false),

    /** 骨骼权重 (4个) —— float, 4 components */
    BONE_WEIGHTS(VertexFormat.R32G32B32A32_SFLOAT, 4, false);

    /** 对应的 RHI VertexFormat */
    public final VertexFormat format;

    /** 组件数量 */
    public final int componentCount;

    /** 是否归一化 */
    public final boolean normalized;

    VertexAttribute(VertexFormat format, int componentCount, boolean normalized) {
        this.format = format;
        this.componentCount = componentCount;
        this.normalized = normalized;
    }

    /**
     * 计算单个属性的字节大小。
     */
    public int byteSize() {
        return format.size();
    }
}

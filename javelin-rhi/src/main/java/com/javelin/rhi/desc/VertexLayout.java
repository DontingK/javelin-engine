package com.javelin.rhi.desc;

import com.javelin.rhi.types.*;

/**
 * 顶点输入布局。
 */
public record VertexLayout(VertexAttribute[] attributes) {

    public int stride() {
        int stride = 0;
        for (VertexAttribute attr : attributes) {
            stride += attr.format().size();
        }
        return stride;
    }

    public int stride(int binding) {
        int stride = 0;
        for (VertexAttribute attr : attributes) {
            if (attr.binding() == binding) {
                stride += attr.format().size();
            }
        }
        return stride;
    }

    // ========== 内部类型 ==========

    public record VertexAttribute(
        int location,
        int binding,
        VertexFormat format,
        int offset,
        boolean normalized
    ) {
        public VertexAttribute(int location, int binding, VertexFormat format, int offset) {
            this(location, binding, format, offset, false);
        }
    }
}

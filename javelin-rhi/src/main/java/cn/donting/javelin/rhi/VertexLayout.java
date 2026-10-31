package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.types.Format;

import java.util.List;

/**
 * RHI 侧顶点输入布局。
 * 对照 VkPipelineVertexInputStateCreateInfo。
 *
 * 由引擎侧 VertexAttribute 自动排布后转换为本类（Engine_Assets_Design.md 2.4）。
 * 相同属性集合 → 唯一 layout → 管线复用（第 16 节管线缓存以 layout 为键）。
 */
public final class VertexLayout {

    /**
     * 单个顶点属性。
     */
    public static final class Attribute {

        private final int location;
        private final Format format;
        private final int offset;

        public Attribute(int location, Format format, int offset) {
            if (location < 0) {
                throw new IllegalArgumentException("location must be >= 0, got: " + location);
            }
            if (format == null) {
                throw new IllegalArgumentException("Format must not be null");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be >= 0, got: " + offset);
            }
            this.location = location;
            this.format = format;
            this.offset = offset;
        }

        public int location() { return location; }
        public Format format() { return format; }
        public int offset() { return offset; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Attribute that)) return false;
            return location == that.location && format == that.format && offset == that.offset;
        }

        @Override
        public int hashCode() {
            int result = location;
            result = 31 * result + format.hashCode();
            result = 31 * result + offset;
            return result;
        }

        @Override
        public String toString() {
            return "Attribute{loc=" + location + ", fmt=" + format + ", off=" + offset + "}";
        }
    }

    private final List<Attribute> attributes;
    private final int stride;
    private final int hash;

    private VertexLayout(List<Attribute> attributes, int stride) {
        this.attributes = List.copyOf(attributes);
        this.stride = stride;
        this.hash = computeHash();
    }

    public List<Attribute> attributes() {
        return attributes;
    }

    public int stride() {
        return stride;
    }

    /** 属性数量 */
    public int attributeCount() {
        return attributes.size();
    }

    /** 缓存去重：相同属性集合 + stride → 同一不可变实例 */
    public static VertexLayout of(List<Attribute> attributes, int stride) {
        if (attributes == null || attributes.isEmpty()) {
            throw new IllegalArgumentException("VertexLayout requires at least one attribute");
        }
        if (stride <= 0) {
            throw new IllegalArgumentException("stride must be positive, got: " + stride);
        }
        return new VertexLayout(attributes, stride);
    }

    private int computeHash() {
        int h = stride;
        for (Attribute a : attributes) {
            h = 31 * h + a.location();
            h = 31 * h + a.format().ordinal();
            h = 31 * h + a.offset();
        }
        return h;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof VertexLayout other)) return false;
        return stride == other.stride && attributes.equals(other.attributes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("VertexLayout{stride=").append(stride);
        for (Attribute a : attributes) {
            sb.append(" ").append(a);
        }
        return sb.append("}").toString();
    }
}

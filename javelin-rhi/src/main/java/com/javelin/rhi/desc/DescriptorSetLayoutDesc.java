package com.javelin.rhi.desc;

import com.javelin.rhi.types.DescriptorType;
import com.javelin.rhi.types.ShaderStage;

import java.util.List;

/**
 * 描述符集布局描述。
 * 对照 VkDescriptorSetLayoutCreateInfo。
 */
public final class DescriptorSetLayoutDesc {

    private final List<Binding> bindings;

    public DescriptorSetLayoutDesc(List<Binding> bindings) {
        if (bindings == null) {
            bindings = List.of();
        }
        this.bindings = List.copyOf(bindings);
    }

    public List<Binding> bindings() {
        return bindings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DescriptorSetLayoutDesc that)) return false;
        return bindings.equals(that.bindings);
    }

    @Override
    public int hashCode() {
        return bindings.hashCode();
    }

    @Override
    public String toString() {
        return "DescriptorSetLayoutDesc{bindings=" + bindings + "}";
    }

    /**
     * 单个绑定点。
     * 对照 VkDescriptorSetLayoutBinding。
     */
    public static final class Binding {

        private final int binding;
        private final DescriptorType type;
        private final int count;
        private final ShaderStage stageFlags;

        public Binding(int binding, DescriptorType type, int count, ShaderStage stageFlags) {
            if (binding < 0) {
                throw new IllegalArgumentException("binding must be >= 0, got: " + binding);
            }
            if (type == null) {
                throw new IllegalArgumentException("DescriptorType must not be null");
            }
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive, got: " + count);
            }
            if (stageFlags == null) {
                throw new IllegalArgumentException("stageFlags must not be null");
            }
            this.binding = binding;
            this.type = type;
            this.count = count;
            this.stageFlags = stageFlags;
        }

        public int binding() { return binding; }
        public DescriptorType type() { return type; }
        public int count() { return count; }
        public ShaderStage stageFlags() { return stageFlags; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Binding that)) return false;
            return binding == that.binding && type == that.type
                    && count == that.count && stageFlags == that.stageFlags;
        }

        @Override
        public int hashCode() {
            int result = binding;
            result = 31 * result + type.hashCode();
            result = 31 * result + count;
            result = 31 * result + stageFlags.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "Binding{binding=" + binding + ", type=" + type
                    + ", count=" + count + ", stage=" + stageFlags + "}";
        }
    }
}

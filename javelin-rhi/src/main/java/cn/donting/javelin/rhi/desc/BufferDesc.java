package cn.donting.javelin.rhi.desc;

import cn.donting.javelin.rhi.types.BufferUsage;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 缓冲描述。
 * 对照 VkBufferCreateInfo。
 */
public final class BufferDesc {

    private final long size;
    private final Set<BufferUsage> usage;
    private final MemoryPreference memory;

    public BufferDesc(long size, Set<BufferUsage> usage, MemoryPreference memory) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive, got: " + size);
        }
        if (usage == null || usage.isEmpty()) {
            throw new IllegalArgumentException("Buffer usage must not be empty");
        }
        if (memory == null) {
            throw new IllegalArgumentException("MemoryPreference must not be null");
        }
        this.size = size;
        this.usage = Collections.unmodifiableSet(EnumSet.copyOf(usage));
        this.memory = memory;
    }

    public long size() {
        return size;
    }

    public Set<BufferUsage> usage() {
        return usage;
    }

    public MemoryPreference memory() {
        return memory;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BufferDesc that)) return false;
        return size == that.size && usage.equals(that.usage) && memory.equals(that.memory);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(size);
        result = 31 * result + usage.hashCode();
        result = 31 * result + memory.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "BufferDesc{size=" + size + ", usage=" + usage + ", memory=" + memory + "}";
    }
}

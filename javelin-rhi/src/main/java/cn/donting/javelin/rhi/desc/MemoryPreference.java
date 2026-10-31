package cn.donting.javelin.rhi.desc;

import cn.donting.javelin.rhi.types.MemoryUsage;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 内存偏好（两级选择）。
 * 对照设计文档第 25.2 节。
 *
 * 匹配规则：候选内存类型先过滤 required，再在剩余中选 preferred 命中最多者。
 */
public final class MemoryPreference {

    private final Set<MemoryUsage> required;
    private final Set<MemoryUsage> preferred;

    public MemoryPreference(Set<MemoryUsage> required, Set<MemoryUsage> preferred) {
        if (required == null || required.isEmpty()) {
            throw new IllegalArgumentException("required must not be empty");
        }
        if (preferred == null) {
            preferred = Set.of();
        }
        this.required = Collections.unmodifiableSet(EnumSet.copyOf(required));
        this.preferred = Collections.unmodifiableSet(EnumSet.copyOf(preferred));
    }

    public Set<MemoryUsage> required() {
        return required;
    }

    public Set<MemoryUsage> preferred() {
        return preferred;
    }

    public static MemoryPreference deviceLocal() {
        return new MemoryPreference(
                EnumSet.of(MemoryUsage.DEVICE_LOCAL),
                EnumSet.noneOf(MemoryUsage.class)
        );
    }

    public static MemoryPreference hostVisibleCoherent() {
        return new MemoryPreference(
                EnumSet.of(MemoryUsage.HOST_VISIBLE, MemoryUsage.HOST_COHERENT),
                EnumSet.noneOf(MemoryUsage.class)
        );
    }

    public static MemoryPreference hostVisibleCached() {
        return new MemoryPreference(
                EnumSet.of(MemoryUsage.HOST_VISIBLE),
                EnumSet.of(MemoryUsage.HOST_CACHED)
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryPreference that)) return false;
        return required.equals(that.required) && preferred.equals(that.preferred);
    }

    @Override
    public int hashCode() {
        return 31 * required.hashCode() + preferred.hashCode();
    }

    @Override
    public String toString() {
        return "MemoryPreference{required=" + required + ", preferred=" + preferred + "}";
    }
}

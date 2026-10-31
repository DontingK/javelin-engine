package cn.donting.javelin.core.render.mesh;

import cn.donting.javelin.rhi.types.Format;

import java.util.Objects;

/**
 * 顶点属性描述对象（Engine_Assets_Design.md 2.3）：内置常量 + 用户自定义同机制。
 *
 * <p>属性不是封闭枚举——内置属性为预定义常量，用户可 {@code new} 自定义属性，
 * location 由 Mesh 按规范序分配（与填写顺序无关，2.4）。
 * 起步全 FLOAT 格式。
 */
public final class VertexAttribute {

    public static final VertexAttribute POSITION = new VertexAttribute("POSITION", 3, Format.R32G32B32_SFLOAT);
    public static final VertexAttribute NORMAL = new VertexAttribute("NORMAL", 3, Format.R32G32B32_SFLOAT);
    public static final VertexAttribute TANGENT = new VertexAttribute("TANGENT", 4, Format.R32G32B32A32_SFLOAT);
    public static final VertexAttribute UV0 = new VertexAttribute("UV0", 2, Format.R32G32_SFLOAT);
    public static final VertexAttribute COLOR = new VertexAttribute("COLOR", 4, Format.R32G32B32A32_SFLOAT);

    /** 规范序（固定）：location = 在已填属性集合中的规范序索引（2.4） */
    public static final VertexAttribute[] BUILTIN_ORDER = {
            POSITION, NORMAL, TANGENT, UV0, COLOR
    };

    private final String name;
    private final int components;
    private final Format format;

    public VertexAttribute(String name, int components, Format format) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("VertexAttribute name must not be empty");
        }
        if (components < 2 || components > 4) {
            throw new IllegalArgumentException("components must be 2..4, got: " + components);
        }
        this.name = name;
        this.components = components;
        this.format = Objects.requireNonNull(format, "format");
    }

    public String name() {
        return name;
    }

    public int components() {
        return components;
    }

    public Format format() {
        return format;
    }

    /** 单顶点字节数（FLOAT 系 4B/分量） */
    public int byteSize() {
        return components * Float.BYTES;
    }

    /** 是否内置属性（决定规范序排在内置段还是自定义段） */
    public boolean isBuiltin() {
        for (VertexAttribute a : BUILTIN_ORDER) {
            if (a.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VertexAttribute that)) {
            return false;
        }
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "VertexAttribute{" + name + ", " + components + "f}";
    }
}

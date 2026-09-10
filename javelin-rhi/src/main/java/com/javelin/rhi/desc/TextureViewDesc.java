package com.javelin.rhi.desc;

import com.javelin.rhi.api.TextureView;
import com.javelin.rhi.types.Format;

/**
 * 纹理视图创建描述符。
 */
public record TextureViewDesc(
    TextureView.TextureViewType viewType,
    Format format,
    TextureSubresourceRange subresourceRange,
    ComponentMapping components
) {
    public TextureViewDesc {
        if (components == null) components = new ComponentMapping();
    }

    public TextureViewDesc(TextureView.TextureViewType viewType, Format format, TextureSubresourceRange subresourceRange) {
        this(viewType, format, subresourceRange, new ComponentMapping());
    }

    // ========== 内部类型 ==========

    public record TextureSubresourceRange(
        AspectFlags aspectMask,
        int baseMipLevel,
        int levelCount,
        int baseArrayLayer,
        int layerCount
    ) {}

    public record ComponentMapping(
        ComponentSwizzle r,
        ComponentSwizzle g,
        ComponentSwizzle b,
        ComponentSwizzle a
    ) {
        public ComponentMapping() {
            this(ComponentSwizzle.IDENTITY, ComponentSwizzle.IDENTITY,
                 ComponentSwizzle.IDENTITY, ComponentSwizzle.IDENTITY);
        }
    }

    public enum ComponentSwizzle {
        IDENTITY,
        ZERO,
        ONE,
        R,
        G,
        B,
        A
    }

    public enum AspectFlags {
        COLOR(0x00000001),
        DEPTH(0x00000002),
        STENCIL(0x00000004),
        METADATA(0x00000008);

        private final int value;

        AspectFlags(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        public static int combine(AspectFlags... flags) {
            int result = 0;
            for (AspectFlags flag : flags) {
                result |= flag.value;
            }
            return result;
        }
    }
}

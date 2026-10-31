package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.types.TextureLayout;

import java.util.HashMap;
import java.util.Map;

/**
 * 布局跟踪表（24.3 LayoutTracker）：每张纹理当前布局，屏障冗余消除的依据。
 * 执行器每帧新建（帧内状态），backbuffer 起始为 PRESENT_SRC，其余 UNDEFINED。
 */
final class LayoutTracker {

    private final Map<Long, TextureLayout> layouts = new HashMap<>();

    TextureLayout get(long textureHandle) {
        return layouts.getOrDefault(textureHandle, TextureLayout.UNDEFINED);
    }

    void set(long textureHandle, TextureLayout layout) {
        layouts.put(textureHandle, layout);
    }
}

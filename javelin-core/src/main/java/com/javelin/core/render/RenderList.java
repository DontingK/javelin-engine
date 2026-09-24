package com.javelin.core.render;

import java.util.ArrayList;
import java.util.List;

/**
 * 渲染列表（Engine_Rendering_Design.md 4.3）：每视口一份，收集期生成。
 *
 * <p>opaque 按材质/贴图分桶排序；transparent 由远及近（视图空间 z 升序）；
 * shadowCasters 供阴影 Pass（实现后置，当前仅收集）。
 */
public final class RenderList {

    private final List<RenderObject> opaque = new ArrayList<>();
    private final List<RenderObject> transparent = new ArrayList<>();
    private final List<RenderObject> shadowCasters = new ArrayList<>();

    void addOpaque(RenderObject object) {
        opaque.add(object);
    }

    void addTransparent(RenderObject object) {
        transparent.add(object);
    }

    void addShadowCaster(RenderObject object) {
        shadowCasters.add(object);
    }

    public List<RenderObject> opaque() {
        return opaque;
    }

    public List<RenderObject> transparent() {
        return transparent;
    }

    public List<RenderObject> shadowCasters() {
        return shadowCasters;
    }
}

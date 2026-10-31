package cn.donting.javelin.core.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渲染列表（Engine_Rendering_Design.md 4.3）：每视口一份，收集期生成。
 *
 * <p>Pass 名即阶段归属（严格制，拍板 2026-10-17）："Forward" → 前向主通道
 * opaque/transparent 两个硬桶（排序语义特殊：不透明按材质分桶、透明视图空间
 * z 升序）；shader 声明的其他 Pass 名（Gizmo、将来 ShadowCaster…）各成一桶进
 * {@code passes}——管线 Pass 有则消费、无则忽略（声明了没人画 = 不画，
 * Unity LightMode 不匹配同口径）。shadowCasters 供阴影 Pass（实现后置，当前仅
 * 收集，ShadowCaster 声明制落地时并入 passes）。
 */
public final class RenderList {

    private final List<RenderObject> opaque = new ArrayList<>();
    private final List<RenderObject> transparent = new ArrayList<>();
    private final List<RenderObject> shadowCasters = new ArrayList<>();
    /** 自定义 Pass 桶（key = shader Pass 名，首见建桶，收集序——透明 z 序仅主通道） */
    private final Map<String, List<RenderObject>> passes = new LinkedHashMap<>();

    void addOpaque(RenderObject object) {
        opaque.add(object);
    }

    void addTransparent(RenderObject object) {
        transparent.add(object);
    }

    void addShadowCaster(RenderObject object) {
        shadowCasters.add(object);
    }

    /** 自定义 Pass 名入桶（收集侧分发，见 RenderingSystem.collectList） */
    void addPass(String passName, RenderObject object) {
        passes.computeIfAbsent(passName, k -> new ArrayList<>()).add(object);
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

    /** 自定义 Pass 桶取列表（未声明返回空表，管线 Pass 可直接遍历） */
    public List<RenderObject> passObjects(String passName) {
        return passes.getOrDefault(passName, List.of());
    }
}

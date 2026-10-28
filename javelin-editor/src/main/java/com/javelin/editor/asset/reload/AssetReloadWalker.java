package com.javelin.editor.asset.reload;

import com.javelin.core.assets.Asset;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.MaterialProperty;
import com.javelin.core.render.material.MaterialTextureEntry;
import com.javelin.core.render.material.MaterialValueEntry;
import com.javelin.core.render.material.Shader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * 资产热重载反射 Walker（Engine_AssetReload_Design.md §4.2/§4.3）：遍历场景对象图，
 * 找到持有目标 guid 旧实例的 Asset 字段反射换新实例指针——持有引用形态不动，图是全知的，
 * 遍历即协议。单一通用算法不认识任何具体类，唯二类知识 = Material 后处理（§4.3 拍板）
 * 与 setter 旁路口径（反射直写字段，不调 setter——当前 setter 均纯赋值等价）。
 *
 * <p>遍历规则：visited 防环（Node/Transform 往返引用实锤）；字段沿父类链，
 * static/transient/null/基本类型/包装类型/枚举跳过；字段类型是 Asset 子类 → guid 命中
 * 换指针，命中与否都进实例内部仅一跳（Material.shader 两跳可达所需，§4.3）；
 * List / 对象数组 → 元素同规则；其余 Serializable → 递归。深度终止于 Asset 实例内部
 * 不再进 Asset 字段（Mesh/Shader 内部无第二用例）。</p>
 *
 * <p>编辑器模块专用，不进 AOT 镜像（反射无 reflect-config 代价）。</p>
 */
public final class AssetReloadWalker {

    private static final Logger log = LoggerFactory.getLogger(AssetReloadWalker.class);

    private AssetReloadWalker() {
    }

    /**
     * 以 root 为根遍历对象图，把 guid 命中的 Asset 字段换成 newAsset。
     *
     * @return 换指针命中数（含 List/数组元素）
     */
    public static int walk(Object root, String guid, Asset newAsset) {
        if (root == null || guid == null || newAsset == null) {
            return 0;
        }
        return walkFields(root, guid, newAsset, false, new IdentityHashMap<>());
    }

    private static int walkFields(Object obj, String guid, Asset newAsset, boolean insideAsset,
                                  IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || visited.put(obj, Boolean.TRUE) != null) {
            return 0;
        }
        int hits = 0;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) {
                    continue;
                }
                Class<?> type = f.getType();
                if (type.isPrimitive() || type == String.class
                        || Enum.class.isAssignableFrom(type)
                        || Number.class.isAssignableFrom(type)
                        || type == Boolean.class || type == Character.class) {
                    continue;
                }
                f.setAccessible(true);
                Object v;
                try {
                    v = f.get(obj);
                } catch (IllegalAccessException e) {
                    log.warn("Walker 字段读取失败: {}.{}", c.getName(), f.getName(), e);
                    continue;
                }
                if (v == null) {
                    continue;
                }
                if (Asset.class.isAssignableFrom(type)) {
                    hits += visitAssetValue(obj, f, v, guid, newAsset, insideAsset, visited);
                } else if (type.isArray() && !type.getComponentType().isPrimitive()) {
                    hits += visitCollectionValue(v, guid, newAsset, insideAsset, visited);
                } else if (List.class.isAssignableFrom(type)
                        || Serializable.class.isAssignableFrom(type)) {
                    if (v instanceof List<?>) {
                        hits += visitCollectionValue(v, guid, newAsset, insideAsset, visited);
                    } else {
                        hits += walkFields(v, guid, newAsset, insideAsset, visited);
                    }
                }
            }
        }
        return hits;
    }

    /**
     * Asset 字段值：guid 命中 → 反射换指针（不调 setter，§7 旁路口径）+ Material 后处理；
     * 无论命中与否，进实例内部仅一跳（insideAsset 闸门，§4.3 Material.shader 可达所需）。
     */
    private static int visitAssetValue(Object owner, Field f, Object v, String guid, Asset newAsset,
                                       boolean insideAsset, IdentityHashMap<Object, Boolean> visited) {
        int hits = 0;
        if (guid.equals(((Asset) v).getGuid())) {
            try {
                f.set(owner, newAsset);
            } catch (IllegalAccessException e) {
                log.warn("Walker 换指针失败: {}.{}", owner.getClass().getName(), f.getName(), e);
                return 0;
            }
            hits++;
            if (owner instanceof Material material && newAsset instanceof Shader shader) {
                patchMaterialDefaults(material, shader);
            }
            v = newAsset;
        }
        if (!insideAsset) {
            hits += walkFields(v, guid, newAsset, true, visited);
        }
        return hits;
    }

    /** List / 对象数组元素：Asset 元素可换 + 一跳，Serializable 元素递归（材质条目等全靠此到达） */
    private static int visitCollectionValue(Object collection, String guid, Asset newAsset,
                                            boolean insideAsset, IdentityHashMap<Object, Boolean> visited) {
        int hits = 0;
        if (collection instanceof List<?> list) {
            // 元素换指针走 ListIterator.set（List 只读视图不出现——字段均为 ArrayList 直通，红线 10）
            @SuppressWarnings("unchecked")
            List<Object> mutable = (List<Object>) list;
            for (int i = 0; i < mutable.size(); i++) {
                Object element = mutable.get(i);
                if (element == null) {
                    continue;
                }
                if (element instanceof Asset asset) {
                    if (guid.equals(asset.getGuid())) {
                        mutable.set(i, newAsset);
                        hits++;
                        element = newAsset;
                    }
                    if (!insideAsset) {
                        hits += walkFields(element, guid, newAsset, true, visited);
                    }
                } else if (element instanceof Serializable) {
                    hits += walkFields(element, guid, newAsset, insideAsset, visited);
                }
            }
        } else if (collection.getClass().isArray()) {
            int len = Array.getLength(collection);
            for (int i = 0; i < len; i++) {
                Object element = Array.get(collection, i);
                if (element == null) {
                    continue;
                }
                if (element instanceof Asset asset) {
                    if (guid.equals(asset.getGuid())) {
                        Array.set(collection, i, newAsset);
                        hits++;
                        element = newAsset;
                    }
                    if (!insideAsset) {
                        hits += walkFields(element, guid, newAsset, true, visited);
                    }
                } else if (element instanceof Serializable) {
                    hits += walkFields(element, guid, newAsset, insideAsset, visited);
                }
            }
        }
        return hits;
    }

    /**
     * Material 后处理（§4.3，换 shader 后同遍历内）：反射直写 shader 字段已由通用规则完成，
     * 这里按新 shader properties 为缺失名字补默认值/贴图槽条目——同名用户值保留、已删属性
     * 旧条目残留无害（pack 按 shader 属性序取值，不遍历 values；缺失即 NPE 实锤）。
     * 预填循环逻辑复用 {@code Material.setShader}，经私有 values/textures 字段直写（红线 9 非 final）。
     */
    private static void patchMaterialDefaults(Material material, Shader shader) {
        try {
            ArrayList<MaterialValueEntry> values = entryList(material, "values");
            ArrayList<MaterialTextureEntry> textures = entryList(material, "textures");
            for (MaterialProperty property : shader.getProperties()) {
                String name = property.getName();
                if (property.getDefaultValues() != null) {
                    if (findValue(values, name) == null) {
                        values.add(new MaterialValueEntry(name, property.getDefaultValues().clone()));
                    }
                } else {
                    if (findTexture(textures, name) == null) {
                        textures.add(new MaterialTextureEntry(name, null));
                    }
                }
            }
        } catch (Exception e) {
            // 后处理失败不炸主循环：缺条目会在渲染侧 pack 显式 NPE 暴露，此处日志给排障口
            log.warn("Material 补默认值失败: shader={}", shader.getShaderName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ArrayList<T> entryList(Material material, String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = Material.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ArrayList<?> list = (ArrayList<?>) field.get(material);
        if (list == null) {
            list = new ArrayList<>();
            field.set(material, list);
        }
        return (ArrayList<T>) list;
    }

    private static MaterialValueEntry findValue(ArrayList<MaterialValueEntry> values, String name) {
        for (MaterialValueEntry entry : values) {
            if (entry.getName().equals(name)) {
                return entry;
            }
        }
        return null;
    }

    private static MaterialTextureEntry findTexture(ArrayList<MaterialTextureEntry> textures, String name) {
        for (MaterialTextureEntry entry : textures) {
            if (entry.getName().equals(name)) {
                return entry;
            }
        }
        return null;
    }
}

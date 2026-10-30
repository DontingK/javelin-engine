package com.javelin.editor.asset.reload;

import com.javelin.core.assets.Asset;
import com.javelin.core.obj.ObjectId;
import com.javelin.editor.util.ClassUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资产热重载实例替换器：遍历编辑器对象注册表的全量存活对象，把 guid 命中的 Asset 引用换新
 * 实例——可达性由注册表承担，不沿对象图递归。单对象只扫自己的字段：值是 Asset 且命中 →
 * 换指针（优先同类型 setter，无则反射直写）；值是 List/数组 → 替换其中命中的 Asset 元素。
 *
 * <p>只换 Java 指针不碰 GPU：PSO/描述符集/UBO 由 RenderingSystem 对账回收（§4.4）。编辑器模块专用。</p>
 */
public final class AssetReloadWalker {

    private static final Logger log = LoggerFactory.getLogger(AssetReloadWalker.class);

    /** setter 查找缓存（含"无 setter"缺席结果）：reload 批量触发时避免重复扫方法表 */
    private static final Map<Field, Optional<Method>> SETTER_CACHE = new ConcurrentHashMap<>();

    private AssetReloadWalker() {
    }

    /**
     * 扫描存活对象快照，把 guid 命中的 Asset 引用换成 newAsset。
     *
     * @return 换指针命中数（含 List/数组元素）
     */
    public static int walk(List<ObjectId> objects, String guid, Asset newAsset) {
        if (objects == null || guid == null || newAsset == null) {
            return 0;
        }
        int hits = 0;
        for (ObjectId object : objects) {
            if (object != null) {
                hits += scan(object, guid, newAsset);
            }
        }
        return hits;
    }

    /** 单对象字段扫描（含父类链；static/transient 跳过）。 */
    private static int scan(Object obj, String guid, Asset newAsset) {
        int hits = 0;
        for (Field field : ClassUtil.getAllFields(obj.getClass())) {
            int mod = field.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) {
                continue;
            }
            field.setAccessible(true);
            Object o;
            try {
                o = field.get(obj);
            } catch (IllegalAccessException e) {
                log.warn("Walker 字段读取失败: {}.{}", obj.getClass().getName(), field.getName(), e);
                continue;
            }
            if (o == null) {
                continue;
            }
            if (o instanceof List list) {
                hits += replaceList(list, guid, newAsset);
            } else if (o.getClass().isArray()) {
                hits += replaceArray(o, guid, newAsset);
            } else if (o instanceof Asset asset) {
                hits += replaceField(field, obj, asset, newAsset);
            }
        }
        return hits;
    }

    /** 对象数组元素换指针（基本类型数组的元素读出来是装箱值，不匹配 Asset，自然跳过）。 */
    private static int replaceArray(Object array, String guid, Asset newAsset) {
        int hit = 0;
        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            Object o = Array.get(array, i);
            if (o instanceof Asset asset && Objects.equals(guid, asset.getGuid())) {
                Array.set(array, i, newAsset);
                hit++;
            }
        }
        return hit;
    }

    /**
     * Asset 字段命中 → 换指针：优先走同类型单参 setter（"set + 字段名"，如 setShader/setMesh/
     * setImage——保留 setter 的语义与副作用），无 setter 才反射直写字段。setter 抛异常不回退
     * 反射（守卫语义如 Texture2D.setImage 的冻结校验不该被绕过）。
     */
    private static int replaceField(Field field, Object obj, Asset oldAsset, Asset newAsset) {
        if (!Objects.equals(oldAsset.getGuid(), newAsset.getGuid())) {
            return 0;
        }
        Method setter = setterFor(field);
        if (setter != null) {
            try {
                setter.invoke(obj, newAsset);
                return 1;
            } catch (ReflectiveOperationException e) {
                log.warn("Walker 调 setter 失败: {}.{}", obj.getClass().getName(), setter.getName(), e);
                return 0;
            }
        }
        try {
            field.set(obj, newAsset);
        } catch (IllegalAccessException e) {
            log.warn("Walker 换指针失败: {}.{}", obj.getClass().getName(), field.getName(), e);
            return 0;
        }
        return 1;
    }

    /** 找参数类型等于字段类型的公开单参 setter（"set + 字段名"）；结果含缺席缓存。 */
    private static Method setterFor(Field field) {
        return SETTER_CACHE.computeIfAbsent(field, f -> {
            String name = "set" + Character.toUpperCase(f.getName().charAt(0)) + f.getName().substring(1);
            for (Method m : f.getDeclaringClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == f.getType()) {
                    return Optional.of(m);
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    private static int replaceList(List list, String guid, Asset newAsset) {
        int hit = 0;
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o instanceof Asset asset && Objects.equals(guid, asset.getGuid())) {
                list.set(i, newAsset);
                hit++;
            }
        }
        return hit;
    }

}

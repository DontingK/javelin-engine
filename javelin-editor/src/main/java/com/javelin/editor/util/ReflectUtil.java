package com.javelin.editor.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射工具类。
 *
 * <p>提供字段获取与设置功能，缓存 Field 对象以提升性能。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class ReflectUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReflectUtil.class);
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private ReflectUtil() {
    }

    /**
     * 获取对象字段值。
     *
     * @param target   目标对象
     * @param fieldName 字段名
     * @return 字段值
     */
    public static Object getField(Object target, String fieldName) {
        try {
            Field field = getField(target.getClass(), fieldName);
            if (field == null) {
                throw new RuntimeException("字段不存在: " + fieldName);
            }
            field.setAccessible(true);
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("获取字段失败: " + fieldName, e);
        }
    }

    /**
     * 设置对象字段值。
     *
     * @param target    目标对象
     * @param fieldName 字段名
     * @param value     新值
     */
    public static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = getField(target.getClass(), fieldName);
            if (field == null) {
                throw new RuntimeException("字段不存在: " + fieldName);
            }
            field.setAccessible(true);
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("设置字段失败: " + fieldName, e);
        }
    }

    private static Field getField(Class<?> clazz, String fieldName) {
        Map<String, Field> classFields = FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> map = new ConcurrentHashMap<>();
            while (c != null) {
                for (Field field : c.getDeclaredFields()) {
                    map.putIfAbsent(field.getName(), field);
                }
                c = c.getSuperclass();
            }
            return map;
        });
        return classFields.get(fieldName);
    }
}

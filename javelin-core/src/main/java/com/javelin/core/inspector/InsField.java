package com.javelin.core.inspector;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inspector 展示闸门：标记的字段才进 Inspector 面板（未标记一律不展示），
 * 贴 TYPE 时为组件分组头显示名与图标。编辑器侧运行期反射读取。
 * 字段还须 getter/setter 齐备且类型可映射（不满足时 InspectorReflector 构建即抛错）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface InsField {

    /**
     * 显示名，空串沿用字段原名
     */
    String label() default "";

    /**
     * 图标路径，class path 里 icon/name/icon.png
     */
    String icon() default "";
}

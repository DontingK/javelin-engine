package com.javelin.core.inspector;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inspector 展示闸门：标记的字段才进 Inspector 面板（未标记一律不展示）。
 * 编辑器侧运行期反射读取。label 为 i18n 键（editor.core 语言包局部键，
 * 编辑器侧解析；空串兜底字段原名）。字段还须 getter/setter 齐备且类型可映射
 * （不满足时 InspectorReflector 构建即抛错）。组件分组头元数据归 {@link InsHeader}
 * （TYPE 职责已移交，本注解收窄回字段闸门）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InsField {

    /** 显示名 i18n 键（语言包局部键，编辑器侧解析；空串沿用字段原名） */
    String label() default "";

    /**
     * 图标路径，class path 里 icon/name/icon.png
     */
    String icon() default "";
}

package cn.donting.javelin.core.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 设置项显式选项（FIELD）：把 int 字段展示为下拉，而不是数字框。
 *
 * <p>用于「取值离散但非枚举、且非法值会被 setter 静默忽略」的字段（如级联数 1/2/4）——
 * 数字框会放进非法值后被忽略，下拉从源头堵住。回写仍走原 int setter。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SettingSelect {

    /** 可选值（值串 = {@code String.valueOf(value)}） */
    int[] values();

    /** 选项标签键前缀：选项标签 = {@code labelPrefix + "." + value} */
    String labelPrefix();
}

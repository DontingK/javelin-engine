package cn.donting.javelin.core.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 设置行说明（FIELD）：渲染为该行**下方**的一行提示文字（次要色）。
 *
 * <p>用于解释非直观的生效时机/静态态（如阴影 bias 改动下一帧重建管线、级联切分参数含义）。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SettingHint {

    /** 提示文案 i18n 键 */
    String value();
}

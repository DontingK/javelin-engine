package cn.donting.javelin.core.inspector;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 滑条控件标记：FLOAT 字段配 {@code @InsFloatRange} 时按「滑条 + 数字框」渲染
 * （Unity 的 Slider 行口径）；未配范围的 FLOAT 字段贴本注解即构建期抛错
 * （无范围可滑，显式意图不静默）。
 *
 * @author javelin
 * @create 2026/11/15
 **/
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InsSlider {
}

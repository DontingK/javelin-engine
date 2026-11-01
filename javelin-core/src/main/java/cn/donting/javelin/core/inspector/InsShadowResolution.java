package cn.donting.javelin.core.inspector;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * int 字段按「阴影分辨率」预设档下拉展示（EditorLang 解析侧消费）：
 * 256/512/1024/2048/4096 选中即提交，Unity ShadowResolution 口径
 * （2026-11-01 用户拍板不做 Custom 自定义）。仅作用于 int 字段。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InsShadowResolution {
}

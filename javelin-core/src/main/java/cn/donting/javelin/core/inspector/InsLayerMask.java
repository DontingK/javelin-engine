package cn.donting.javelin.core.inspector;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inspector int 字段按 Layer 位掩码语义展示：勾选式多选（LAYER_MASK 值类型，
 * 值=位掩码 int 串），常用于 Camera.cullingMask 类字段。未标记的 int 字段
 * 仍按普通整数（INT）展示。编辑器侧运行期反射读取。
 *
 * @author javelin
 * @create 2026/10/11
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InsLayerMask {
}

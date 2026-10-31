package cn.donting.javelin.core.inspector;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记字段为旋转，只能用在 Quaternionf 字段上（如 Transform.localRotation）；
 * 类型约束由消费方运行期检查。编辑器侧运行期反射读取。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InsRotation {
}

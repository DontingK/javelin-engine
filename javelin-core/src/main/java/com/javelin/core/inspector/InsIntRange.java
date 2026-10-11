package com.javelin.core.inspector;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inspector 整数字段取值范围，渲染为滑条；编辑器侧运行期反射读取。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InsIntRange {
    int max() default 10;
    int min() default 0;
    int step() default 1;
}

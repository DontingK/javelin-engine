package com.javelin.ast.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记类型需要被编译时注册到反射工厂中。
 *
 * <p>被标记的类型会在编译时由 {@link com.javelin.ast.processor.ReflectiveProcessor}
 * 自动生成工厂注册代码，替代运行时的反射创建。</p>
 *
 * <p>普通类必须有公开的无参构造器；接口和抽象类只能注册数组工厂，
 * 调用 {@code ReflectiveFactory.newInstance} 会抛出异常。</p>
 *
 * @author javelin
 * @since 1.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Reflective {
}

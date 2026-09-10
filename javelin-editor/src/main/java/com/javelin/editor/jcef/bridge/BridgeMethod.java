package com.javelin.editor.jcef.bridge;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author: javelin
 * @create: 2026/7/4
 * @Version 1.0
 **/
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface BridgeMethod {
    String value() default "";
}


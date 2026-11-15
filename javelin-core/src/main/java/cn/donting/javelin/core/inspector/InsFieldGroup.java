package cn.donting.javelin.core.inspector;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段值驱动的条件子组（Unity Inspector「显示条件 + 可折叠组」口径）：控制字段取值为
 * {@code keys[i]} 时，显示 {@code value[i]} 指定的同宿主字段（须为嵌套对象容器，其内部
 * {@code @InsField} 字段作为子行内联展示；空串 = 该取值下不显示）。
 *
 * <p>给了 {@link #label()} 即渲染一个可点击的折叠组头（Unity 那样点一下就收起/展开），
 * 留空则平铺无组头。keys 与 value 必须等长（不等即构建期抛错）；同一容器可被多个 key 触发。</p>
 *
 * @author javelin
 * @create 2026/11/15
 **/
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InsFieldGroup {

    /** 控制字段的取值（wire 值，枚举为常量名） */
    String[] keys();

    /** keys 对应显示的容器字段名（与 keys 等长；空串 = 不显示） */
    String[] value();

    /** 折叠组头文案（i18n 键；空串 = 不渲染组头、子行平铺） */
    String label() default "";

    /** 组头初始是否收起（仅 {@link #label()} 非空时有意义） */
    boolean collapsed() default false;
}

package cn.donting.javelin.core.inspector;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 组件分组头元数据：贴 TYPE（组件类），供 Inspector 折叠分组头取显示名与图标
 * （Unity 组件头口径）——头的 TYPE 职责由本注解独占，{@link InsField} 只管字段行。
 * label 为 i18n 键（editor.core 语言包局部键，编辑器侧解析），空串兜底类简单名；
 * icon 为 classpath /icon/ 下资源名（不含路径与
 * 扩展名，SvgIcon 口径），空串不渲染。编辑器侧运行期反射读取。
 *
 * <p>注解不经 @Inherited 传递（用户拍板 2026-10-31 灯族子类化）：组件子类族
 * 须各自显式贴注解且可各用专属键（如 Light 族 Directional/Point/Spot 三键
 * 报各自灯型）——贴基类对子类不生效，忘贴的子类会被 Inspector 组件闸门整体跳过。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface InsHeader {

    /** 显示名 i18n 键（语言包局部键，编辑器侧解析），空串兜底类简单名 */
    String label() default "";

    /** 图标名（classpath /icon/{name}.svg），空串不渲染 */
    String icon() default "";
}

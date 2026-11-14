package cn.donting.javelin.core.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 设置页声明（TYPE）：标了它的类即「项目设置」窗口的一页，字段按声明序展开成「分组 → 行」。
 *
 * <p>页面发现走设置根对象（{@link JavelinSetting}）里类型贴本注解的字段，声明序即导航序。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Setting {

    /** 页名 i18n 键（导航项标题） */
    String label();

    /**
     * 编辑器启动是否从项目文件读入本页（默认 true）。
     *
     * <p>false = 只写不读：本页值是编辑器运行环境（如窗口尺寸/标题），写进项目文件只供打包
     * 运行时消费，编辑器自身启动不跟随文件。</p>
     */
    boolean editorStartup() default true;
}

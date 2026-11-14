package cn.donting.javelin.core.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 设置项生效时机标记：**启动期定型**（改后重启才生效）。设置面板据此把控件置只读并给出
 * 提示行；不阻断持久化与写入链（值照常保存/加载）。未标记 = 即时生效。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SettingRestart {
}

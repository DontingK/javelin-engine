package cn.donting.javelin.core.settings;

import cn.donting.javelin.core.assets.Asset;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 资产引用设置项：贴在「资产 guid 串」字段上，项目设置窗口按资产选择器渲染该行。
 *
 * <p>字段类型必须是 {@code String}（存 guid，故设置文件在引擎创建前也能读）；面板选中的资产
 * 只回写其 guid。清空（None）= 字段置 null。</p>
 **/
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SettingAsset {

    /** 资产声明类型（选择器的后缀过滤与显示名反查） */
    Class<? extends Asset> value();
}

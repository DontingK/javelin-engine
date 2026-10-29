package com.javelin.editor.plugin;

import com.javelin.editor.i18n.I18nKey;

/**
 * 插件契约——编辑器宿主与全部功能模块（含本体 editor.core）的统一接入形态
 * （编辑器即插件，无特权分支）。发现 = ServiceLoader（META-INF/services
 * 声明，无参实例化）；语言包随 setup 注册（registerPack 显式域+语言+路径），
 * 宿主只驱动装载；任何插件报错 = 弹错误框显示信息、确认后关闭编辑器（错误
 * 显性化拍板 2026-10-29）。
 *
 * @author javelin
 * @create 2026/10/20
 */
public interface Plugin {

    /** 命名空间（点分小写，如 "editor.core"）——i18n key 与注册贡献的前缀锚点；宿主校验，撞名 fail-fast */
    String namespace();

    /** 显示名（i18n 文案键，查插件自身语言包） */
    I18nKey name();

    /** 描述（i18n 文案键，null = 无描述） */
    default I18nKey description() {
        return null;
    }

    /** 版本（语义化版本，如 "1.2.0"），宿主校验 */
    String version();

    /** 作者（可选，空 = 不展示） */
    default String author() {
        return "";
    }

    /** 主页/仓库链接（可选，空 = 不展示） */
    default String link() {
        return "";
    }

    /** 生命周期唯一钩子：扩展点贡献经 PluginContext 收口，早于面板首帧执行 */
    void setup(PluginContext context);
}

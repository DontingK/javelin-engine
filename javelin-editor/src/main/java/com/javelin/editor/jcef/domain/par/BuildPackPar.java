package com.javelin.editor.jcef.domain.par;

/**
 * 打包参数（打包对话框表单值，2026-10-13 起含构建形态）。
 *
 * <p>构建前持久化进项目 Game/ 设置——窗口元信息写 {@code javelinSettings.settings}
 *（load-改-save，空标题/非正宽高跳过不覆写）、启动场景写 {@code game.settings} 的
 * defaultSceneGuid（Assets 相对路径经伴生 .meta 解 guid，空跳过）；Game/ 与 Library/
 * 随构建原封复制进产物（两形态同口径）——写入即随复制生效。buildForm 分流构建链
 *（jar→jlink JRE 运行时链 / aot→GraalVM native-image 链）；buildMode 仅 AOT 形态
 * 消费（经 -PbuildMode 下发：debug→quickBuild 快速构建 / release→全优化）。</p>
 *
 * @author javelin
 * @version 1.1
 * @create 2026/10/13
 **/
public class BuildPackPar {
    /** 构建形态（"jar" / "aot"，空或未知值 = jar） */
    public String buildForm;
    /** 构建模式（"debug" / "release"，仅 AOT 形态消费，空 = release） */
    public String buildMode;
    /** 窗口标题（空 = 不覆写） */
    public String windowTitle;
    /** 窗口宽度（null/非正 = 不覆写） */
    public Integer windowWidth;
    /** 窗口高度（null/非正 = 不覆写） */
    public Integer windowHeight;
    /** 启动场景（Assets 相对路径，空 = 不覆写；写 GameSettings.defaultSceneGuid） */
    public String startupScene;
}

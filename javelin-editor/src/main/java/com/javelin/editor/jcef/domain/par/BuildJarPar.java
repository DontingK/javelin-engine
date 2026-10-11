package com.javelin.editor.jcef.domain.par;

/**
 * jar 打包参数（打包对话框表单值）。
 *
 * <p>构建前持久化进项目 Game/ 设置——窗口元信息写 {@code javelinSettings.settings}
 *（load-改-save，空标题/非正宽高跳过不覆写）、启动场景写 {@code game.settings} 的
 * defaultSceneGuid（Assets 相对路径经伴生 .meta 解 guid，空跳过）；构建模式暂不消费
 *（映射 nativeCompile 批次）。Game/ 与 Library/ 随构建原封复制进产物——写入即随复制生效。</p>
 *
 * @author javelin
 * @version 1.0
 * @create 2026/10/11
 **/
public class BuildJarPar {
    /** 窗口标题（空 = 不覆写） */
    public String windowTitle;
    /** 窗口宽度（null/非正 = 不覆写） */
    public Integer windowWidth;
    /** 窗口高度（null/非正 = 不覆写） */
    public Integer windowHeight;
    /** 启动场景（Assets 相对路径，空 = 不覆写；写 GameSettings.defaultSceneGuid） */
    public String startupScene;
}

package com.javelin.editor.jcef.method;

import com.javelin.editor.JavelinEditor;
import com.javelin.editor.console.ConsoleOut;
import com.javelin.editor.jcef.bridge.BridgeMethod;
import com.javelin.editor.jcef.bridge.JavaScriptBridge;
import com.javelin.editor.jcef.domain.par.BuildPackPar;
import com.javelin.editor.jcef.domain.vo.BuildVo;
import com.javelin.editor.jcef.domain.vo.SceneListVo;
import com.javelin.editor.meta.AssetMeta;
import com.javelin.editor.project.Project;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.serialize.binary.BinaryImporter;
import com.javelin.core.settings.GameSettings;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.settings.SettingManager;
import org.cef.browser.CefBrowser;

import java.io.File;
import java.util.List;
import javax.swing.SwingUtilities;

/**
 * 构建相关桥接方法。
 *
 * <p>提供打包能力（构建 > 打包对话框「开始打包」触发，构建形态 JAR/AOT 分流）。</p>
 *
 * <p>线程口径：本方法在桥线程池执行，Gradle 构建全程同步阻塞占一个池线程
 * （构建时长分钟级）。构建日志<b>实时推送</b>：ConsoleOut 回调行经
 * {@code JavaScriptBridge.send(调用者浏览器, "build.out.*", 行文本)} 下发（EDT hop，
 * 对齐 EventBridgeForwarder 先例），前端监听 build.out.info/warn/error 逐行追加
 * （console.* 前缀保留给控制台）；
 * 返回值为结束标记（响应到达即构建完成）。失败抛异常走桥错误协议（tinyfd 弹窗）。</p>
 *
 * @author javelin
 * @version 1.0
 * @create 2026/10/11
 */
@BridgeMethod("/build")
public class BuildMethod {

    /**
     * 项目场景列表（资产索引 .scene 枚举）：打包对话框「启动场景」下拉数据源。
     *
     * <p>路径口径与 {@code ProjectSettings.defaultOpenScene} 一致（相对 Assets/，
     * 如 {@code scenes/default.scene}）；默认场景随行返回供下拉默认选中。</p>
     *
     * @return 场景列表 + 默认场景
     */
    @BridgeMethod("/scenes")
    public SceneListVo listScenes() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            throw new IllegalStateException("编辑器未启动，无法获取场景列表");
        }
        List<String> scenes = editor.getAssetManager().listAssetPaths(".scene");
        String defaultScene = editor.getSettingsManager().getProjectSettings().getDefaultOpenScene();
        return new SceneListVo(scenes, defaultScene);
    }

    /**
     * 打包：按构建形态分流项目构建链。
     *
     * <p>JAR 形态走 {@code jarBuild}（dumpRuntimeJars → build → jlink 裁剪运行时 →
     * 产物组装到 Builds/jar）；AOT 形态走 {@code aotBuild}（nativeCompile——GraalVM
     * native-image，buildMode 经 -PbuildMode 下发：debug→quickBuild / release→全优化
     * → 产物组装到 Builds/aot：exe + 外置 lib/*.dll + Game/ + Library/ + bat）。表单
     * 参数先持久化进项目 Game/ 设置（窗口元信息→javelinSettings.settings、启动场景
     * →game.settings 的 defaultSceneGuid——两形态产物都复制 Game/，写入即随复制
     * 生效）。</p>
     *
     * @return 结束标记（日志已经 build.out.* 消息实时下发，返回值仅作完成信号）
     */
    @BridgeMethod("/pack")
    public BuildVo packBuild(CefBrowser browser, BuildPackPar par) {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            throw new IllegalStateException("编辑器未启动，无法打包");
        }
        pushLine(browser, "build.out.info", "开始打包......");
        applyPackSettings(editor, par, browser);
        ConsoleOut consoleOut = new ConsoleOut() {
            @Override
            public void info(String txt) {
                pushLine(browser, "build.out.info", txt);
            }

            @Override
            public void error(String txt) {
                pushLine(browser, "build.out.error", txt);
            }

            @Override
            public void warn(String txt) {
                pushLine(browser, "build.out.warn", txt);
            }
        };
        if (par != null && "aot".equals(par.buildForm)) {
            editor.getProject().aotBuild(consoleOut, par.buildMode);
        } else {
            editor.getProject().jarBuild(consoleOut);
        }
        return new BuildVo("====END====");
    }

    /** 实时推日志行：executeJavaScript 线程约束未证实，对齐 EventBridgeForwarder 先例 EDT hop */
    private static void pushLine(CefBrowser browser, String type, String line) {
        SwingUtilities.invokeLater(() -> JavaScriptBridge.send(browser, type, line));
    }

    /**
     * 表单参数持久化进项目 Game/ 设置（load-改-save；空值/非法值跳过不覆写）。
     *
     * <p>写入结果行经实时推送反馈到调用页日志区；产物按「Game/ 原封复制」取到新值。</p>
     */
    private static void applyPackSettings(JavelinEditor editor, BuildPackPar par, CefBrowser browser) {
        if (par == null) {
            return;
        }
        Project project = editor.getProject();
        try {
            File settingFile = new File(project.getGameDir(), SettingManager.JAVELIN_SETTINGS_FILE);
            JavelinSetting setting = settingFile.exists()
                    ? new BinaryImporter(settingFile).importData(JavelinSetting.class)
                    : new JavelinSetting();
            boolean changed = false;
            if (par.windowTitle != null && !par.windowTitle.isBlank()) {
                setting.setTitle(par.windowTitle);
                changed = true;
            }
            if (par.windowWidth != null && par.windowWidth > 0) {
                setting.setWidth(par.windowWidth);
                changed = true;
            }
            if (par.windowHeight != null && par.windowHeight > 0) {
                setting.setHeight(par.windowHeight);
                changed = true;
            }
            if (changed) {
                new BinaryExporter(settingFile).export(setting);
                pushLine(browser, "build.out.info", "窗口设置已写入 Game/" + SettingManager.JAVELIN_SETTINGS_FILE
                        + "（标题=" + setting.getTitle() + " 宽=" + setting.getWidth()
                        + " 高=" + setting.getHeight() + "）");
            }
        } catch (Exception e) {
            pushLine(browser, "build.out.error", "窗口设置写入失败: " + e.getMessage());
        }
        if (par.startupScene == null || par.startupScene.isBlank()) {
            return;
        }
        try {
            File sceneFile = new File(project.getAssetsDir(), par.startupScene);
            AssetMeta meta = editor.getMetaManager().getMeta(sceneFile);
            if (meta == null || meta.getGuid() == null) {
                pushLine(browser, "build.out.warn", "启动场景无元数据（未导入？），跳过写入: " + par.startupScene);
                return;
            }
            File gameFile = new File(project.getGameDir(), SettingManager.GAME_SETTINGS_FILE);
            GameSettings gameSettings = gameFile.exists()
                    ? new BinaryImporter(gameFile).importData(GameSettings.class)
                    : new GameSettings();
            gameSettings.setDefaultSceneGuid(meta.getGuid());
            new BinaryExporter(gameFile).export(gameSettings);
            pushLine(browser, "build.out.info", "启动场景已写入 Game/" + SettingManager.GAME_SETTINGS_FILE
                    + "（" + par.startupScene + " → guid=" + meta.getGuid() + "）");
        } catch (Exception e) {
            pushLine(browser, "build.out.error", "启动场景写入失败: " + e.getMessage());
        }
    }
}

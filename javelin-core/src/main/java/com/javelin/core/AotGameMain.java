package com.javelin.core;

import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.settings.SettingManager;

/**
 * AOT 形态游戏入口（GraalVM native-image 专用主类）。
 *
 * <p>与 {@link GameMain} 唯一差异：main 首行把 LWJGL 共享库查找目录钉到
 * {@code ./lib}（产物外置 DLL 目录）。native-image 下 LWJGL 无法走 JVM 形态的
 * classpath natives jar 自解压路径，DLL 由打包链 extractLwjglNatives 任务外置到
 * 产物 lib/——同时避免 natives 编译进 exe 导致体积膨胀（2026-10-13 拍板）。
 * 相对路径按进程工作目录解析：启动 bat 已 {@code cd /d %~dp0} 钉死到产物根，
 * 直双击 exe 时工作目录亦为 exe 所在目录，两路径下 ./lib 均成立。</p>
 *
 * <p>仅 AOT 构建链引用（project-template 的 graalvmNative.mainClass）；JAR 形态
 * 仍走 {@link GameMain}（LWJGL 从 classpath natives jar 自解压，无需此设置）。</p>
 *
 * @author javelin
 * @version 1.0
 * @create 2026/10/13
 */
public class AotGameMain {

    /** native-image 同样要求 main(String[]) 签名 */
    public static void main(String[] args) {
        System.setProperty("org.lwjgl.librarypath", "./lib");
        JavelinSetting javelinSetting = SettingManager.loadJavelinSettings();
        JavelinEngine javelinEngine = new JavelinEngine(javelinSetting);
        javelinEngine.start();
    }
}

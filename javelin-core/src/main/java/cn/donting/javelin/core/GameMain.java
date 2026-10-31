package cn.donting.javelin.core;

import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.core.settings.SettingManager;

public class GameMain {

    /** java 启动器要求 main(String[]) 签名（无参 main 不被识别，java -jar 会报 Main method not found） */
    public static void main(String[] args) {
        JavelinSetting javelinSetting = SettingManager.loadJavelinSettings();
        JavelinEngine javelinEngine = new JavelinEngine(javelinSetting);
        javelinEngine.start();
    }
}

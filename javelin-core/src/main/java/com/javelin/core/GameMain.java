package com.javelin.core;

import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.settings.SettingManager;

public class GameMain {

    public static void main() {
        JavelinSetting javelinSetting = SettingManager.loadJavelinSettings();
        JavelinEngine javelinEngine = new JavelinEngine(javelinSetting);
        javelinEngine.start();
    }
}

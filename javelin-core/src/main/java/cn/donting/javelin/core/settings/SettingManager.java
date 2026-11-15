package cn.donting.javelin.core.settings;

import cn.donting.javelin.core.Env;
import cn.donting.javelin.core.serialize.binary.BinaryImporter;

import java.io.File;

/**
 * 设置文件读取门面（{@code Game/} 目录）：只在引擎实例创建前用——启动链先读文件拿到
 * {@link JavelinSetting}，再交给引擎；之后就一律走 {@code JavelinEngine.setting()}。
 */
public class SettingManager {

    public static final String JAVELIN_SETTINGS_FILE = "javelinSettings.settings";

    public static JavelinSetting loadJavelinSettings() {
        try {
            File file = getSettingsFile(JAVELIN_SETTINGS_FILE);
            return new BinaryImporter(file).importData(JavelinSetting.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static File getSettingsFile(String fileName) {
        return new File(Env.getRunDir(), "Game" + File.separator + fileName);
    }

}

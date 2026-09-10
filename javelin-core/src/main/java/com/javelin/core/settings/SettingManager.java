package com.javelin.core.settings;


import com.javelin.core.Env;
import com.javelin.core.serialize.binary.BinaryImporter;
import org.slf4j.Logger;

import java.io.File;

/**
 * @create: 2026/6/30
 * @Version 1.0
 **/
public class SettingManager {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SettingManager.class);

    public static final String GAME_SETTINGS_FILE = "game.settings";
    public static final String JAVELIN_SETTINGS_FILE = "javelinSettings.settings";
    public static final String LAYER_SETTINGS_FILE = "layerSetting.settings";
    public static final String TAG_SETTINGS_FILE = "tagSetting.settings";
    public static GameSettings loadGameSettings() {
        try {
            File file = getSettingsFile("game.settings");
            BinaryImporter binaryImporter = new BinaryImporter(file);
            return binaryImporter.importData(GameSettings.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static JavelinSetting loadJavelinSettings() {
        try {
            File file = getSettingsFile("javelinSettings.settings");
            BinaryImporter binaryImporter = new BinaryImporter(file);
            return binaryImporter.importData(JavelinSetting.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static LayerSettings loadLayerSettings() {
        try {
            File file = getSettingsFile("layerSetting.settings");
            BinaryImporter binaryImporter = new BinaryImporter(file);
            return binaryImporter.importData(LayerSettings.class);
        } catch (Exception e) {
            LOGGER.warn("加载图层设置时出错: {}", e.getMessage());
            return new LayerSettings();
//            throw new RuntimeException(e);
        }
    }

    private static File getSettingsFile(String fileName) {
        return new File(Env.getRunDir(), "Settings" + File.separator + fileName);
    }

}

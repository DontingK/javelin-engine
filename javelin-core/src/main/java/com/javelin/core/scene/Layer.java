package com.javelin.core.scene;

import com.javelin.core.settings.LayerSettings;
import com.javelin.core.settings.SettingManager;

import java.util.Objects;

/**
 * 节点层级。
 *
 * <p>提供一组预定义层级，每个层级对应 0~31 的索引值，适合位掩码操作。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class Layer {

    private static final Layer[] layers = new Layer[31];
    /**
     * 默认层级。
     */
    public static final Layer DEFAULT;

    static {
        LayerSettings layerSettings = SettingManager.loadLayerSettings();
        String[] layerNames = layerSettings.getLayerNames();
        for (int i = 0; i < layerNames.length; i++) {
            layers[i] = new Layer(i, layerNames[i]);
        }
        DEFAULT = layers[0];
    }

    private final int value;
    private String name;

    private Layer(int value, String name) {
        this.value = value;
        this.name = name;
    }

    /**
     * 获取层级索引值（0~31）。
     *
     * @return 层级索引值
     */
    public int getValue() {
        return value;
    }

    /**
     * 获取层级名称。
     *
     * @return 层级名称
     */
    public String getName() {
        return name;
    }

    public static Layer of(String name) {
        for (Layer layer : layers) {
            if (Objects.equals(layer.name, name)) return layer;
        }
        throw new RuntimeException("layer not found:" + name);
    }

    public static Layer of(int index) {
        return layers[index];
    }
}

package cn.donting.javelin.core.scene;

import java.util.Objects;

/**
 * 节点层级：索引 0~30（共 {@link #COUNT} 个槽位），索引即序列化面。
 *
 * <p>层名归 {@link LayerAndTagManger} 从设置投影，改名只改写 {@code name}、不换实例——
 * 节点持有的是索引，名字随注册表刷新，无需重建。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class Layer {

    /** 槽位数（索引合法区间 [0, COUNT)） */
    public static final int COUNT = 31;

    /** 默认层级：索引 0（层名由设置投影，通常为 Default） */
    public static final Layer DEFAULT;

    /** 注册表（索引 → 实例；包内可见，{@link LayerAndTagManger} 投影层名用） */
    static final Layer[] layers = new Layer[COUNT];

    static {
        for (int i = 0; i < COUNT; i++) {
            layers[i] = new Layer(i);
        }
        DEFAULT = layers[0];
    }

    private final int value;

    /** 层名（空串 = 未命名槽位；设置投影原地改写，索引稳定） */
    String name = "";

    private Layer(int value) {
        this.value = value;
    }

    /**
     * 获取层级索引值（0~30）。
     *
     * @return 层级索引值
     */
    public int getValue() {
        return value;
    }

    /**
     * 层级位掩码（1 &lt;&lt; 索引）：cullingMask / LayerMask 世界的换算唯一落点。
     * <p>注意与 {@link #getValue()}（索引）区分——直接拿索引做按位运算是历史 bug 源
     * （曾在 LayerMask.of / SceneRenderBridge 把索引当掩码，导致全渲染对象被误过滤）。</p>
     *
     * @return 位掩码（bit index）
     */
    public int getMask() {
        return 1 << value;
    }

    /**
     * 获取层级名称。
     *
     * @return 层级名称（未命名槽位为空串）
     */
    public String getName() {
        return name;
    }

    /**
     * 按层名查找（空名槽位不参与匹配）。
     *
     * @param name 层名
     * @return 层级
     * @throws IllegalArgumentException 未命名该层
     */
    public static Layer of(String name) {
        for (Layer layer : layers) {
            if (name != null && !layer.name.isEmpty() && Objects.equals(layer.name, name)) {
                return layer;
            }
        }
        throw new IllegalArgumentException("layer not found: " + name);
    }

    /**
     * 按索引取层级。
     *
     * @param index 层号（0~{@link #COUNT}-1）
     * @return 层级
     * @throws IllegalArgumentException 索引越界
     */
    public static Layer of(int index) {
        if (index < 0 || index >= COUNT) {
            throw new IllegalArgumentException("layer index out of range: " + index);
        }
        return layers[index];
    }
}

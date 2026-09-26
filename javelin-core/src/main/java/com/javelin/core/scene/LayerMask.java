package com.javelin.core.scene;

/**
 * 层级掩码工具。
 *
 * <p>用于组合多个 {@link Layer} 并进行包含判断。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class LayerMask {

    private LayerMask() {
    }

    /**
     * 根据多个层级生成掩码。
     *
     * @param layers 层级数组
     * @return 层级掩码
     */
    public static int of(Layer... layers) {
        int mask = 0;
        for (Layer layer : layers) {
            if (layer != null) {
                mask |= layer.getMask();
            }
        }
        return mask;
    }

    /**
     * 判断掩码是否包含指定层级。
     *
     * @param mask 层级掩码
     * @param layer 层级
     * @return 是否包含
     */
    public static boolean contains(int mask, Layer layer) {
        return layer != null && (mask & layer.getMask()) != 0;
    }

    /**
     * 包含所有层级的掩码。
     *
     * @return 所有层级掩码
     */
    public static int everything() {
        return ~0;
    }

    /**
     * 不包含任何层级的掩码。
     *
     * @return 空层级掩码
     */
    public static int nothing() {
        return 0;
    }
}

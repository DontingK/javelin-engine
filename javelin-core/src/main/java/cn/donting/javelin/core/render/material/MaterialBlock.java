package cn.donting.javelin.core.render.material;

/**
 * MaterialBlock 打包器（RHI 21.4/21.5：MaterialBlock 的 std140 排布，CPU 侧同源规则）。
 *
 * <p>排布规则（引擎私有约定，与构建工具注入 GLSL 的生成规则同源，
 * 保证 GLSL / RHI / CPU 三方布局一致，RHI 15.7 坑点 1）：
 * 按 Shader properties 声明序，跳过 TEXTURE_2D（贴图走 set 2 的 binding 1..n）；
 * FLOAT/RANGE → 单个 float（4B 对齐），COLOR/VECTOR → vec4（16B 对齐）；
 * 总长向上取 16B 倍数，空块 = 16B 占位（UBO 非零尺寸约束）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class MaterialBlock {

    private MaterialBlock() {
    }

    /**
     * 按属性声明序打包材质数值参数（std140；贴图槽不参与本块）。
     *
     * @param material 材质实例（shader 非空，数值参数经 setShader 预填默认值）
     * @return 定长 float 数组（长度 = {@link #uboByteSize(Shader)} / 4）
     */
    public static float[] pack(Material material) {
        if (material == null || material.getShader() == null) {
            throw new IllegalArgumentException("material / material.shader must not be null");
        }
        Shader shader = material.getShader();
        float[] block = new float[uboByteSize(shader) / 4];
        int offset = 0;
        for (MaterialProperty property : shader.getProperties()) {
            switch (property.getType()) {
                case FLOAT, RANGE -> {
                    offset = align(offset, Float.BYTES);
                    block[offset / 4] = material.getFloat(property.getName());
                    offset += Float.BYTES;
                }
                case COLOR, VECTOR -> {
                    offset = align(offset, 16);
                    float[] value = material.getColor(property.getName());
                    System.arraycopy(value, 0, block, offset / 4, 4);
                    offset += 16;
                }
                case TEXTURE_2D -> {
                    // 贴图槽不占 UBO：set 2 binding 1..n（RenderingSystem.materialSet 解析）
                }
            }
        }
        return block;
    }

    /**
     * MaterialBlock UBO 字节数（16B 对齐；空块 16B 占位）。
     */
    public static int uboByteSize(Shader shader) {
        if (shader == null) {
            throw new IllegalArgumentException("shader must not be null");
        }
        int offset = 0;
        for (MaterialProperty property : shader.getProperties()) {
            switch (property.getType()) {
                case FLOAT, RANGE -> offset = align(offset, Float.BYTES) + Float.BYTES;
                case COLOR, VECTOR -> offset = align(offset, 16) + 16;
                case TEXTURE_2D -> {
                    // 不占 UBO
                }
            }
        }
        return Math.max(16, align(offset, 16));
    }

    private static int align(int offset, int alignment) {
        return (offset + alignment - 1) / alignment * alignment;
    }
}

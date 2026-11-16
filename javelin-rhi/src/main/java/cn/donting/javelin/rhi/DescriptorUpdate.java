package cn.donting.javelin.rhi;

import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;

/**
 * 描述符更新。
 * 对照 VkWriteDescriptorSet。
 */
public final class DescriptorUpdate {

    /** 更新类型 */
    public enum Type {
        UNIFORM_BUFFER,
        DYNAMIC_UNIFORM_BUFFER,
        STORAGE_BUFFER,
        SAMPLED_TEXTURE,
        STORAGE_TEXTURE,
        SAMPLER
    }

    private final Type type;
    private final int binding;
    private final int arrayElement;
    private final Buffer buffer;
    private final long offset;
    private final long range;
    private final Texture texture;
    private final Sampler sampler;

    private DescriptorUpdate(Type type, int binding, int arrayElement,
                             Buffer buffer, long offset, long range,
                             Texture texture, Sampler sampler) {
        this.type = type;
        this.binding = binding;
        this.arrayElement = arrayElement;
        this.buffer = buffer;
        this.offset = offset;
        this.range = range;
        this.texture = texture;
        this.sampler = sampler;
    }

    public static DescriptorUpdate uniformBuffer(int binding, Buffer buffer, long offset, long range) {
        return new DescriptorUpdate(Type.UNIFORM_BUFFER, binding, 0, buffer, offset, range, null, null);
    }

    public static DescriptorUpdate dynamicUniformBuffer(int binding, Buffer buffer, long offset, long range) {
        return new DescriptorUpdate(Type.DYNAMIC_UNIFORM_BUFFER, binding, 0, buffer, offset, range, null, null);
    }

    public static DescriptorUpdate sampledTexture(int binding, Texture texture, Sampler sampler) {
        return new DescriptorUpdate(Type.SAMPLED_TEXTURE, binding, 0, null, 0, 0, texture, sampler);
    }

    /**
     * 存储纹理绑定（storage image）：着色器侧 imageLoad/imageStore 读写。
     *
     * <p>布局恒按 GENERAL 写入描述符（{@code VulkanDescriptorSet} 同源映射），因此纹理须
     * 以 {@code TextureUsage.STORAGE} 创建，并在使用它的 Pass 里声明
     * {@code addTextureStorageRead/Write} 让执行器转 {@link cn.donting.javelin.rhi.types.TextureLayout#GENERAL}。</p>
     *
     * <p>3D 纹理 / 2D 数组纹理同样适用（默认全范围视图）：shader 侧对应
     * {@code image3D} / {@code image2DArray}，写入需 {@code layout(r32f)} 等格式限定符。</p>
     */
    public static DescriptorUpdate storageTexture(int binding, Texture texture) {
        if (texture == null) {
            throw new IllegalArgumentException("Storage texture must not be null");
        }
        return new DescriptorUpdate(Type.STORAGE_TEXTURE, binding, 0, null, 0, 0, texture, null);
    }

    /**
     * 整段 storage buffer 绑定（offset=0 / range=VK_WHOLE_SIZE）。
     * range 不可为 0：VkDescriptorBufferInfo 要求 range &gt; 0 或 VK_WHOLE_SIZE（VUID）。
     */
    public static DescriptorUpdate storageBuffer(int binding, Buffer buffer) {
        return storageBuffer(binding, buffer, 0, VK_WHOLE_SIZE);
    }

    /** 分段 storage buffer 绑定（offset 起 range 字节） */
    public static DescriptorUpdate storageBuffer(int binding, Buffer buffer, long offset, long range) {
        if (range <= 0 && range != VK_WHOLE_SIZE) {
            throw new IllegalArgumentException("Storage buffer range must be > 0 or VK_WHOLE_SIZE, got: " + range);
        }
        return new DescriptorUpdate(Type.STORAGE_BUFFER, binding, 0, buffer, offset, range, null, null);
    }

    public Type type() { return type; }
    public int binding() { return binding; }
    public int arrayElement() { return arrayElement; }
    public Buffer buffer() { return buffer; }
    public long offset() { return offset; }
    public long range() { return range; }
    public Texture texture() { return texture; }
    public Sampler sampler() { return sampler; }
}

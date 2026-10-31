package cn.donting.javelin.rhi;

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

    public static DescriptorUpdate storageBuffer(int binding, Buffer buffer) {
        return new DescriptorUpdate(Type.STORAGE_BUFFER, binding, 0, buffer, 0, 0, null, null);
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

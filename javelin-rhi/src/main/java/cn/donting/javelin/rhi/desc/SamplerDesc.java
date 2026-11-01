package cn.donting.javelin.rhi.desc;

import cn.donting.javelin.rhi.types.CompareOp;
import cn.donting.javelin.rhi.types.Filter;
import cn.donting.javelin.rhi.types.SamplerAddressMode;
import cn.donting.javelin.rhi.types.SamplerMipmapMode;

/**
 * 采样器描述。
 * 对照 VkSamplerCreateInfo。
 */
public final class SamplerDesc {

    private final Filter magFilter;
    private final Filter minFilter;
    private final SamplerMipmapMode mipmapMode;
    private final SamplerAddressMode addressModeU;
    private final SamplerAddressMode addressModeV;
    private final SamplerAddressMode addressModeW;
    private final float maxAnisotropy;
    private final float maxLod;
    private final boolean compareEnable;
    private final CompareOp compareOp;

    public SamplerDesc(Filter magFilter, Filter minFilter, SamplerMipmapMode mipmapMode,
                       SamplerAddressMode addressModeU,
                       SamplerAddressMode addressModeV,
                       SamplerAddressMode addressModeW,
                       float maxAnisotropy, float maxLod) {
        if (magFilter == null || minFilter == null || mipmapMode == null) {
            throw new IllegalArgumentException("Filter and mipmapMode must not be null");
        }
        if (addressModeU == null || addressModeV == null || addressModeW == null) {
            throw new IllegalArgumentException("AddressMode must not be null");
        }
        if (maxAnisotropy < 1.0f) {
            throw new IllegalArgumentException("maxAnisotropy must be >= 1.0, got: " + maxAnisotropy);
        }
        this.magFilter = magFilter;
        this.minFilter = minFilter;
        this.mipmapMode = mipmapMode;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.addressModeW = addressModeW;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
        this.compareEnable = false;
        this.compareOp = null;
    }

    /**
     * 深度比较采样器（shadowMap 采样）：compareEnable = true，采样结果 = 参考深度
     * 与纹素深度按 compareOp 的比较结果（0/1，写入 r 通道）。
     */
    public SamplerDesc(Filter magFilter, Filter minFilter, SamplerMipmapMode mipmapMode,
                       SamplerAddressMode addressModeU,
                       SamplerAddressMode addressModeV,
                       SamplerAddressMode addressModeW,
                       float maxAnisotropy, float maxLod,
                       CompareOp compareOp) {
        if (magFilter == null || minFilter == null || mipmapMode == null) {
            throw new IllegalArgumentException("Filter and mipmapMode must not be null");
        }
        if (addressModeU == null || addressModeV == null || addressModeW == null) {
            throw new IllegalArgumentException("AddressMode must not be null");
        }
        if (maxAnisotropy < 1.0f) {
            throw new IllegalArgumentException("maxAnisotropy must be >= 1.0, got: " + maxAnisotropy);
        }
        if (compareOp == null) {
            throw new IllegalArgumentException("compareOp must not be null");
        }
        this.magFilter = magFilter;
        this.minFilter = minFilter;
        this.mipmapMode = mipmapMode;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.addressModeW = addressModeW;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
        this.compareEnable = true;
        this.compareOp = compareOp;
    }

    public Filter magFilter() { return magFilter; }
    public Filter minFilter() { return minFilter; }
    public SamplerMipmapMode mipmapMode() { return mipmapMode; }
    public SamplerAddressMode addressModeU() { return addressModeU; }
    public SamplerAddressMode addressModeV() { return addressModeV; }
    public SamplerAddressMode addressModeW() { return addressModeW; }
    public float maxAnisotropy() { return maxAnisotropy; }
    public float maxLod() { return maxLod; }
    public boolean compareEnable() { return compareEnable; }
    public CompareOp compareOp() { return compareOp; }

    /** 默认线性采样 + 重复寻址 */
    public static SamplerDesc linear() {
        return new SamplerDesc(
                Filter.LINEAR, Filter.LINEAR, SamplerMipmapMode.LINEAR,
                SamplerAddressMode.REPEAT, SamplerAddressMode.REPEAT, SamplerAddressMode.REPEAT,
                1.0f, 1000.0f
        );
    }

    /** 默认就近采样 + 钳制寻址 */
    public static SamplerDesc nearest() {
        return new SamplerDesc(
                Filter.NEAREST, Filter.NEAREST, SamplerMipmapMode.NEAREST,
                SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE,
                1.0f, 1000.0f
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SamplerDesc that)) return false;
        return magFilter == that.magFilter && minFilter == that.minFilter
                && mipmapMode == that.mipmapMode
                && addressModeU == that.addressModeU
                && addressModeV == that.addressModeV
                && addressModeW == that.addressModeW
                && Float.compare(maxAnisotropy, that.maxAnisotropy) == 0
                && Float.compare(maxLod, that.maxLod) == 0
                && compareEnable == that.compareEnable
                && (!compareEnable || compareOp == that.compareOp);
    }

    @Override
    public int hashCode() {
        int result = magFilter.hashCode();
        result = 31 * result + minFilter.hashCode();
        result = 31 * result + mipmapMode.hashCode();
        result = 31 * result + addressModeU.hashCode();
        result = 31 * result + addressModeV.hashCode();
        result = 31 * result + addressModeW.hashCode();
        result = 31 * result + Float.hashCode(maxAnisotropy);
        result = 31 * result + Float.hashCode(maxLod);
        result = 31 * result + Boolean.hashCode(compareEnable);
        result = 31 * result + (compareOp != null ? compareOp.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "SamplerDesc{mag=" + magFilter + ", min=" + minFilter + ", mip=" + mipmapMode
                + ", addrU=" + addressModeU + ", addrV=" + addressModeV + ", addrW=" + addressModeW
                + ", aniso=" + maxAnisotropy + ", maxLod=" + maxLod
                + ", compare=" + compareEnable + (compareOp != null ? "/" + compareOp : "")
                + "}";
    }
}

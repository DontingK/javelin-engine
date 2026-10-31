package cn.donting.javelin.rhi;

/**
 * 设备偏好。
 * 用于物理设备选择评分。
 */
public final class RHIDevicePrefs {

    private final boolean preferDiscreteGPU;
    private final boolean requireSamplerAnisotropy;
    private final boolean requireDynamicRendering;

    public RHIDevicePrefs(boolean preferDiscreteGPU,
                          boolean requireSamplerAnisotropy,
                          boolean requireDynamicRendering) {
        this.preferDiscreteGPU = preferDiscreteGPU;
        this.requireSamplerAnisotropy = requireSamplerAnisotropy;
        this.requireDynamicRendering = requireDynamicRendering;
    }

    public boolean preferDiscreteGPU() { return preferDiscreteGPU; }
    public boolean requireSamplerAnisotropy() { return requireSamplerAnisotropy; }
    public boolean requireDynamicRendering() { return requireDynamicRendering; }

    public static RHIDevicePrefs defaults() {
        return new RHIDevicePrefs(true, true, true);
    }
}

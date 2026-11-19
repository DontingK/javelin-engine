package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsFloatRange;
import cn.donting.javelin.core.inspector.InsSlider;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 体素化 AO 设置（Engine_VoxelAO_Design §6，四期 P4-17）。
 *
 * <p>独立成组：占用网格 / AO 网格 / 射线数 / 距离 / 强度域内聚，设置面板按它展开成
 * 「体素化环境光遮蔽」分组。缺键回退由 {@link InputCapsule} 默认值重载负责
 * （默认值 = 字段声明初值）。</p>
 *
 * <p>生效口径：改分辨率 / 射线数 / 距离需要重建体素（一次性 compute 链）；
 * 开关与强度改后即时生效（强度只改采样侧乘子）。</p>
 */
@Reflective
public class VoxelAoSettings implements Serializable {

    /** 总开关（默认关；关时不建资源、不派发） */
    @InsField(label = "projectSettings.voxelAO.enabled")
    private boolean enabled = false;

    /** 占用网格分辨率（单边体素数；128³ = 8 MB R32_UINT） */
    @InsField(label = "projectSettings.voxelAO.voxelResolution")
    @SettingSelect(values = {64, 128, 256}, labelPrefix = "projectSettings.voxelAO.res")
    @SettingHint("projectSettings.voxelAO.resolutionHint")
    private int voxelResolution = 128;

    /** AO 网格分辨率（单边体素数；64³ = 0.25 MB R8_UNORM） */
    @InsField(label = "projectSettings.voxelAO.aoResolution")
    @SettingSelect(values = {32, 64, 128}, labelPrefix = "projectSettings.voxelAO.aoRes")
    private int aoResolution = 64;

    /** 半球射线数（越多越平滑，计算量线性增长） */
    @InsField(label = "projectSettings.voxelAO.rayCount")
    @SettingSelect(values = {8, 16, 32}, labelPrefix = "projectSettings.voxelAO.rays")
    private int rayCount = 16;

    /** 射线最大距离（世界单位；超出距离按可见处理） */
    @InsField(label = "projectSettings.voxelAO.maxDistance")
    @InsFloatRange(min = 1f, max = 50f, step = 0.5f)
    @InsSlider
    private float maxDistance = 10.0f;

    /** AO 强度（0 = 视觉等同关闭；1 = 全量遮蔽） */
    @InsField(label = "projectSettings.voxelAO.intensity")
    @InsFloatRange(min = 0f, max = 2f, step = 0.01f)
    @InsSlider
    private float intensity = 1.0f;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getVoxelResolution() {
        return voxelResolution;
    }

    public void setVoxelResolution(int voxelResolution) {
        this.voxelResolution = voxelResolution;
    }

    public int getAoResolution() {
        return aoResolution;
    }

    public void setAoResolution(int aoResolution) {
        this.aoResolution = aoResolution;
    }

    public int getRayCount() {
        return rayCount;
    }

    public void setRayCount(int rayCount) {
        this.rayCount = rayCount;
    }

    public float getMaxDistance() {
        return maxDistance;
    }

    public void setMaxDistance(float maxDistance) {
        this.maxDistance = maxDistance;
    }

    public float getIntensity() {
        return intensity;
    }

    public void setIntensity(float intensity) {
        this.intensity = intensity;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("enabled", enabled);
        output.write("voxelResolution", voxelResolution);
        output.write("aoResolution", aoResolution);
        output.write("rayCount", rayCount);
        output.write("maxDistance", maxDistance);
        output.write("intensity", intensity);
    }

    @Override
    public void read(InputCapsule input) {
        // 标量默认值 = 字段声明初值（缺键回退）
        enabled = input.readBoolean("enabled", false);
        voxelResolution = input.readInt("voxelResolution", 128);
        aoResolution = input.readInt("aoResolution", 64);
        rayCount = input.readInt("rayCount", 16);
        maxDistance = input.readFloat("maxDistance", 10.0f);
        intensity = input.readFloat("intensity", 1.0f);
    }
}

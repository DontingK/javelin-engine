package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsFloatRange;
import cn.donting.javelin.core.inspector.InsIntRange;
import cn.donting.javelin.core.inspector.InsSlider;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 探针实时 GI（DDGI）参数（Engine_DDGI_Design.md v0.2 §3/§4，P4-05 批次 B1）。
 *
 * <p><b>职责边界</b>：本类只承载「探针网格 / 采样密度 / 刷新节奏 / 混合权重」这些数值；
 * 探针网格的铺法与 atlas 排版是纯数学，在 {@code DdgiProbeGrid} 里算；体素化与追踪着色器
 * 在 B2 落。缺键回退口径同 {@link ShadowSettings}（默认值 = 字段声明初值）。</p>
 *
 * <p><b>面板</b>：项目设置「图形」页新增一组（{@code @SettingGroup}），字段按注解生成行。</p>
 */
@Reflective
public class DdgiSettings implements Serializable {

    /** 总开关（关 = 不建体素场、不建 atlas；重新打开走一次完整重建） */
    @InsField(label = "projectSettings.ddgiEnabled")
    private boolean enabled = true;
    /** 体素场单边体素数（32/64/128/256；越大越能表达细缝，显存与重建成本立方增长） */
    @InsField(label = "projectSettings.ddgiVoxelResolution")
    @InsIntRange(min = 32, max = 256, step = 16)
    private int voxelResolution = 128;
    /** 探针网格 X 向数量（段数；探针点 = 段数，跨度 = (count−1)×spacing） */
    @InsField(label = "projectSettings.ddgiProbesX")
    @InsIntRange(min = 2, max = 64, step = 1)
    private int probesX = 16;
    /** 探针网格 Y 向数量 */
    @InsField(label = "projectSettings.ddgiProbesY")
    @InsIntRange(min = 2, max = 32, step = 1)
    private int probesY = 4;
    /** 探针网格 Z 向数量 */
    @InsField(label = "projectSettings.ddgiProbesZ")
    @InsIntRange(min = 2, max = 64, step = 1)
    private int probesZ = 16;
    /** 探针间距（世界单位；1~2m 是画质/成本甜点，越大越糊） */
    @InsField(label = "projectSettings.ddgiSpacing")
    @InsFloatRange(min = 0.5f, max = 8f, step = 0.1f)
    @InsSlider
    private float spacing = 2.0f;
    /** 每探针射线数（八面体方向图分辨率的一半：方向图 = rays×rays/2 + 偏移填充） */
    @InsField(label = "projectSettings.ddgiRaysPerProbe")
    @InsIntRange(min = 16, max = 256, step = 8)
    private int raysPerProbe = 64;
    /** 每帧刷新探针数（预算；一圈帧数 = ceil(探针总数 / 该值)） */
    @InsField(label = "projectSettings.ddgiProbesPerFrame")
    @InsIntRange(min = 1, max = 256, step = 1)
    private int probesPerFrame = 64;
    /** 时域混合系数（0 = 只用本帧结果、0.9 = 历史占九成；越大越稳但越滞后） */
    @InsField(label = "projectSettings.ddgiHysteresis")
    @InsFloatRange(min = 0f, max = 0.98f, step = 0.01f)
    @InsSlider
    private float hysteresis = 0.85f;
    /** GI 强度（乘在采样结果上；0 = 视觉等同关 GI） */
    @InsField(label = "projectSettings.ddgiIntensity")
    @InsFloatRange(min = 0f, max = 4f, step = 0.05f)
    @InsSlider
    private float intensity = 1.0f;
    /** 法线偏置（探针采样起点沿法线外推的倍数 × spacing，抑制自遮蔽与薄墙光渗） */
    @InsField(label = "projectSettings.ddgiNormalBias")
    @InsFloatRange(min = 0f, max = 2f, step = 0.05f)
    @InsSlider
    private float normalBiasScale = 0.4f;

    public boolean isEnabled() { return enabled; }
    public int getVoxelResolution() { return voxelResolution; }
    public int getProbesX() { return probesX; }
    public int getProbesY() { return probesY; }
    public int getProbesZ() { return probesZ; }
    public float getSpacing() { return spacing; }
    public int getRaysPerProbe() { return raysPerProbe; }
    public int getProbesPerFrame() { return probesPerFrame; }
    public float getHysteresis() { return hysteresis; }
    public float getIntensity() { return intensity; }
    public float getNormalBiasScale() { return normalBiasScale; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setVoxelResolution(int voxelResolution) {
        this.voxelResolution = clampCount(voxelResolution, 32, 256);
    }
    public void setProbesX(int probesX) { this.probesX = clampCount(probesX, 2, 64); }
    public void setProbesY(int probesY) { this.probesY = clampCount(probesY, 2, 32); }
    public void setProbesZ(int probesZ) { this.probesZ = clampCount(probesZ, 2, 64); }
    public void setSpacing(float spacing) { this.spacing = clamp(spacing, 0.5f, 8f); }
    public void setRaysPerProbe(int raysPerProbe) { this.raysPerProbe = clampCount(raysPerProbe, 16, 256); }
    public void setProbesPerFrame(int probesPerFrame) { this.probesPerFrame = clampCount(probesPerFrame, 1, 256); }
    public void setHysteresis(float hysteresis) { this.hysteresis = clamp(hysteresis, 0f, 0.98f); }
    public void setIntensity(float intensity) { this.intensity = clamp(intensity, 0f, 4f); }
    public void setNormalBiasScale(float normalBiasScale) { this.normalBiasScale = clamp(normalBiasScale, 0f, 2f); }

    /** 探针总数（= counts 三向相乘；B1 验收读得到的就是它） */
    public int probeCount() {
        return probesX * probesY * probesZ;
    }

    /** 刷新一圈所需帧数 */
    public int framesPerSweep() {
        return Math.max(1, (probeCount() + probesPerFrame - 1) / probesPerFrame);
    }

    private static int clampCount(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private static float clamp(float value, float min, float max) {
        return Math.min(Math.max(value, min), max);
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("enabled", enabled);
        output.write("voxelResolution", voxelResolution);
        output.write("probesX", probesX);
        output.write("probesY", probesY);
        output.write("probesZ", probesZ);
        output.write("spacing", spacing);
        output.write("raysPerProbe", raysPerProbe);
        output.write("probesPerFrame", probesPerFrame);
        output.write("hysteresis", hysteresis);
        output.write("intensity", intensity);
        output.write("normalBiasScale", normalBiasScale);
    }

    @Override
    public void read(InputCapsule input) {
        enabled = input.readBoolean("enabled", true);
        voxelResolution = input.readInt("voxelResolution", 128);
        probesX = input.readInt("probesX", 16);
        probesY = input.readInt("probesY", 4);
        probesZ = input.readInt("probesZ", 16);
        spacing = input.readFloat("spacing", 2.0f);
        raysPerProbe = input.readInt("raysPerProbe", 64);
        probesPerFrame = input.readInt("probesPerFrame", 64);
        hysteresis = input.readFloat("hysteresis", 0.85f);
        intensity = input.readFloat("intensity", 1.0f);
        normalBiasScale = input.readFloat("normalBiasScale", 0.4f);
    }
}

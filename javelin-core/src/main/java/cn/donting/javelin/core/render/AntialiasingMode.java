package cn.donting.javelin.core.render;

import cn.donting.javelin.core.inspector.InsEnumLabel;
import cn.donting.javelin.core.scene.component.Camera;

/**
 * 相机抗锯齿模式（对齐 Unity HDRP Camera Frame Settings 的 Anti-aliasing 下拉：Off / TAA）。
 *
 * <p>抗锯齿是<b>相机级</b>设置：TAA 的抖动注入、历史累积与配套锐化都按本视口生效，
 * 不同相机可各自开关（Engine_TAA_Design §3.2）。带 TAA 的相机在 HYBRID 档生效，
 * FORWARD 档无 G-Buffer 世界位置，忽略并 warn 一次。</p>
 */
public enum AntialiasingMode implements InsEnumLabel {
    /**
     * 关闭（不抖动、不累积、不锐化）
     */
    OFF("ins.enum.antialiasing.off"),
    /**
     * 时域抗锯齿：Halton 抖动 + 重投影历史累积 + 配套 RCAS 锐化
     */
    TAA("ins.enum.antialiasing.taa");

    private final String label;

    AntialiasingMode(String label) {
        this.label = label;
    }


    @Override
    public String getLabel() {
        return label;
    }
}


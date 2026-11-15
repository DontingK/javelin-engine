#version 450
// 链尾锐化片元（Engine_TAA_Design §5.3 / P4-11）：Babylon.js FSR1 的 RCAS（Robust Contrast
// Adaptive Sharpening）端口——Shaders/fsr1Sharpen.fragment.fx 逐行照搬（连带
// ShadersInclude/ffxFunctions.fx 的浮点近似函数内联），只把 con 的来源换成 push 的强度系数。
// 为什么是 RCAS 而不是普通 unsharp：lobe 由环极值定向求取并限幅（FSR_RCAS_LIMIT），只会
// 往「本来就更锐的方向」推且受局部对比度约束——高对比边缘不振铃，暗部少放噪。
// set0 = 本 Pass 专属布局 binding0 组合采样器（读 Tonemap 输出的 LDR 中间 RT）。
layout(set = 0, binding = 0) uniform sampler2D srcColor;

// params.x = 锐化强度（0~1，等价 FSR 的 con = 2^(-sharpness stop)，1 = 最强）
layout(push_constant) uniform SharpenPush {
    vec4 params;
} pc;

layout(location = 0) out vec4 outColor;

// ── FidelityFX 便携函数（ffxFunctions.fx 逐字照搬）──
float AMax3F1(float x, float y, float z) {
    return max(x, max(y, z));
}
float AMin3F1(float x, float y, float z) {
    return min(x, min(y, z));
}
// 中精度 rcp 近似：位魔术数起猜 + 一次牛顿迭代（最终解析 lp 用，避免可见的色调台阶）
float APrxMedRcpF1(float a) {
    float b = uintBitsToFloat(0x7ef19fffu - floatBitsToUint(a));
    return b * (-b * a + 2.0);
}

// 再往上就会出「不自然的锐化痕迹」的上限（FSR 原文注释的取值）
#define FSR_RCAS_LIMIT (0.25-(1.0/16.0))

// texelFetch 忽略采样器 wrap 状态、越界未定义——3×3 邻域各自夹到纹理边界
vec3 rcasLoad(ivec2 p) {
    return texelFetch(srcColor, clamp(p, ivec2(0), textureSize(srcColor, 0) - ivec2(1)), 0).rgb;
}

void main() {
    // 算法用最小 3×3 像素邻域（十字）：
    //    b
    //  d e f
    //    h
    ivec2 sp = ivec2(gl_FragCoord.xy);
    vec3 b = rcasLoad(sp + ivec2( 0, -1));
    vec3 d = rcasLoad(sp + ivec2(-1,  0));
    vec3 e = rcasLoad(sp);
    vec3 f = rcasLoad(sp + ivec2( 1,  0));
    vec3 h = rcasLoad(sp + ivec2( 0,  1));
    // 拆分量：下面全是标量算术
    float bR = b.r; float bG = b.g; float bB = b.b;
    float dR = d.r; float dG = d.g; float dB = d.b;
    float eR = e.r; float eG = e.g; float eB = e.b;
    float fR = f.r; float fG = f.g; float fB = f.b;
    float hR = h.r; float hG = h.g; float hB = h.b;
    // 亮度 ×2（R0.5 + G1 + B0.5，等价 BT.709 权重）
    float bL = bB * 0.5 + (bR * 0.5 + bG);
    float dL = dB * 0.5 + (dR * 0.5 + dG);
    float eL = eB * 0.5 + (eR * 0.5 + eG);
    float fL = fB * 0.5 + (fR * 0.5 + fG);
    float hL = hB * 0.5 + (hR * 0.5 + hG);
    // 噪声检测：环均值偏离中心的程度 × 环内极差的倒数，夹到 0~1 再折成衰减系数
    float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;
    nz = clamp(abs(nz) * APrxMedRcpF1(AMax3F1(AMax3F1(bL, dL, eL), fL, hL) - AMin3F1(AMin3F1(bL, dL, eL), fL, hL)), 0.0, 1.0);
    nz = -0.5 * nz + 1.0;
    // 环（不含中心）的通道极值
    float mn4R = min(AMin3F1(bR, dR, fR), hR);
    float mn4G = min(AMin3F1(bG, dG, fG), hG);
    float mn4B = min(AMin3F1(bB, dB, fB), hB);
    float mx4R = max(AMax3F1(bR, dR, fR), hR);
    float mx4G = max(AMax3F1(bG, dG, fG), hG);
    float mx4B = max(AMax3F1(bB, dB, fB), hB);
    // 峰值范围的立即数（(1, -4)：命中上下限的线性约束）
    vec2 peakC = vec2(1.0, -1.0 * 4.0);
    // 限幅器：按「中心距环上下限还有多远」算本方向可推量（需高精度求倒数，故用 1/x 而非近似）
    float hitMinR = min(mn4R, eR) * (1.0 / (4.0 * mx4R));
    float hitMinG = min(mn4G, eG) * (1.0 / (4.0 * mx4G));
    float hitMinB = min(mn4B, eB) * (1.0 / (4.0 * mx4B));
    float hitMaxR = (peakC.x - max(mx4R, eR)) * (1.0 / (4.0 * mn4R + peakC.y));
    float hitMaxG = (peakC.x - max(mx4G, eG)) * (1.0 / (4.0 * mn4G + peakC.y));
    float hitMaxB = (peakC.x - max(mx4B, eB)) * (1.0 / (4.0 * mn4B + peakC.y));
    float lobeR = max(-hitMinR, hitMaxR);
    float lobeG = max(-hitMinG, hitMaxG);
    float lobeB = max(-hitMinB, hitMaxB);
    // 强度系数：只有负 lobe（= 锐化）算数，且夹在 [-FSR_RCAS_LIMIT, 0]
    float lobe = max(-FSR_RCAS_LIMIT, min(AMax3F1(lobeR, lobeG, lobeB), 0.0)) * pc.params.x;
    // 强度 0 = 本相机未启用抗锯齿配套锐化：直出中心像素（RCAS 在 lobe=0 时虽退化为 e，
    // 但 rcpL 是近似倒数，会有极小色偏——显式短路更干净且省算）
    if (pc.params.x <= 0.0) {
        outColor = vec4(e, 1.0);
        return;
    }
    // 解析：中心 + lobe × 四邻，权重和 1 + 4·lobe
    float rcpL = APrxMedRcpF1(4.0 * lobe + 1.0);
    float pixR = (lobe * bR + lobe * dR + lobe * hR + lobe * fR + eR) * rcpL;
    float pixG = (lobe * bG + lobe * dG + lobe * hG + lobe * fG + eG) * rcpL;
    float pixB = (lobe * bB + lobe * dB + lobe * hB + lobe * fB + eB) * rcpL;
    outColor = vec4(pixR, pixG, pixB, 1.0);
}

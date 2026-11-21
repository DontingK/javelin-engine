#version 450
// SSAO 双边模糊——**可分离两遍**（Engine_SSAO_Design §3.3，BUG-12 修法①）：
// 同一支着色器录两次（水平 / 竖直），各自沿单轴 9 tap，合起来支撑面 9×9（原单遍 5×5），
// 用更少的 tap 拿到更大的平滑半径，专治半球核的颗粒/斜视面上的条纹。
// **保边权用视空间 z 差而非世界位置距离**（实装修正，档 §9.2 第 3 条）：掠射面上的世界位置
// 逐像素跨度大，用世界距离会把同一表面判成不同面而拒绝平滑。
//
// BUG-12（2026-11-21）：深度容差 `sigmaZ` 改**自适应**——按本轴上相邻 AO 纹素的视 z 步进放大，
// 掠射面不再因固定 `radius×0.5` 过紧而把邻域全拒掉（噪声正是留在斜视地面/墙角）。
//
// set0 binding0 = 相机块（引擎 frameSet，动态偏移）；set1 binding0 = 上游 AO 值、binding1 = 世界位置。
layout(set = 0, binding = 0) uniform CameraBlock {
    mat4 viewProj;
    mat4 view;
} cam;
layout(set = 1, binding = 0) uniform sampler2D aoIn;
layout(set = 1, binding = 1) uniform sampler2D gbufferWorldPos;

// params：x = radius（同 SSAO 半径——容差下限按它定，两边口径一致）、y = axis（1 = 水平、0 = 竖直）
layout(push_constant) uniform SsaoPush {
    vec4 uvRect;
    vec4 params;
} pc;

layout(location = 0) in vec2 vUV;
layout(location = 0) out float outAO;

const int RADIUS_TAPS = 4;                 // 单轴 9 tap（两遍合起来 9×9 支撑）
const float SPATIAL_SIGMA = 2.0;           // 空间核 σ（像素）

/** 视空间 z（前向 = −Z） */
float viewZOf(vec3 worldPos) {
    return (cam.view * vec4(worldPos, 1.0)).z;
}

/** 把 uv 夹回本视口区域（分屏时不得读邻视口的纹理） */
vec2 clampRegion(vec2 uv) {
    vec2 s = (uv - pc.uvRect.xy) / pc.uvRect.zw;
    s = clamp(s, vec2(0.0), vec2(1.0));
    return pc.uvRect.xy + s * pc.uvRect.zw;
}

/** 邻域视 z（背景处回落到中心 z——轮廓外不参与容差估计） */
float neighborZ(vec2 uv, float fallbackZ) {
    vec4 wp = texture(gbufferWorldPos, clampRegion(uv));
    return wp.w < 0.5 ? fallbackZ : viewZOf(wp.xyz);
}

void main() {
    vec2 uv = pc.uvRect.xy + vUV * pc.uvRect.zw;
    vec4 cwp = texture(gbufferWorldPos, uv);
    if (cwp.w < 0.5) {
        outAO = 1.0;                       // 背景/天空：不模糊、不遮挡
        return;
    }
    // AO RT 是半分辨率：纹素步长取 aoIn 的尺寸（不是帧缓冲尺寸）
    vec2 texel = 1.0 / vec2(textureSize(aoIn, 0));
    vec2 dir = pc.params.y > 0.5 ? vec2(texel.x, 0.0) : vec2(0.0, texel.y);
    float cz = viewZOf(cwp.xyz);

    // 自适应深度容差：沿本轴一个 AO 纹素的视 z 步进（中心差分）×1.5，下限 = 半径的一半
    float slope = abs(neighborZ(uv + dir * 2.0, cz) - neighborZ(uv - dir * 2.0, cz)) * 0.25;
    float sigmaZ = max(pc.params.x * 0.5, 1.5 * slope);

    float sum = texture(aoIn, uv).r;
    float wsum = 1.0;
    for (int i = -RADIUS_TAPS; i <= RADIUS_TAPS; i++) {
        if (i == 0) {
            continue;
        }
        vec2 tuv = uv + dir * float(i);
        // 区域边界守卫：越出本视口区域即跳过（分屏时不得读邻视口的 AO）
        vec2 s = (tuv - pc.uvRect.xy) / pc.uvRect.zw;
        if (any(lessThan(s, vec2(0.0))) || any(greaterThan(s, vec2(1.0)))) {
            continue;
        }
        vec4 twp = texture(gbufferWorldPos, tuv);
        if (twp.w < 0.5) {
            continue;
        }
        float tz = viewZOf(twp.xyz);
        float wz = exp(-abs(tz - cz) / sigmaZ);                    // 保边（深度差指数衰减）
        float ws = exp(-float(i * i) / (2.0 * SPATIAL_SIGMA * SPATIAL_SIGMA));  // 空间核
        float w = wz * ws;
        sum += texture(aoIn, tuv).r * w;
        wsum += w;
    }
    outAO = sum / wsum;
}

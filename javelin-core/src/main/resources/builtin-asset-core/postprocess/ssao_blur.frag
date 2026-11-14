#version 450
// SSAO 双边模糊（Engine_SSAO_Design §3.3）：5×5 保边模糊消去半球核噪声（半分辨率，写 ssaoRtFor(view,1)）。
// **保边权用视空间 z 差而非世界位置距离**（实装修正，档 §9.2 第 3 条）：掠射面上的世界位置
// 逐像素跨度大，用世界距离会把同一表面判成不同面而拒绝平滑（噪声留在斜视地面/墙角）；
// 视空间 z 的逐像素梯度对流形无此问题。
// set0 binding0 = 相机块（引擎 frameSet，动态偏移）；set1 binding0 = AO 原始值、binding1 = 世界位置。
layout(set = 0, binding = 0) uniform CameraBlock {
    mat4 viewProj;
    mat4 view;
} cam;
layout(set = 1, binding = 0) uniform sampler2D aoRaw;
layout(set = 1, binding = 1) uniform sampler2D gbufferWorldPos;

// params：x = radius（同 SSAO 半径——深度容差按它的比例定，故两边口径一致）
layout(push_constant) uniform SsaoPush {
    vec4 uvRect;
    vec4 params;
} pc;

layout(location = 0) in vec2 vUV;
layout(location = 0) out float outAO;

const int RADIUS_TAPS = 2;                 // 5×5

void main() {
    vec2 uv = pc.uvRect.xy + vUV * pc.uvRect.zw;
    vec4 cwp = texture(gbufferWorldPos, uv);
    if (cwp.w < 0.5) {
        outAO = 1.0;                       // 背景/天空：不模糊、不遮挡
        return;
    }
    // AO RT 是半分辨率：纹素步长取 aoRaw 的尺寸（不是帧缓冲尺寸）
    vec2 texel = 1.0 / vec2(textureSize(aoRaw, 0));
    float cz = (cam.view * vec4(cwp.xyz, 1.0)).z;
    float sigmaZ = max(pc.params.x * 0.5, 1e-4);

    float sum = texture(aoRaw, uv).r;
    float wsum = 1.0;
    for (int y = -RADIUS_TAPS; y <= RADIUS_TAPS; y++) {
        for (int x = -RADIUS_TAPS; x <= RADIUS_TAPS; x++) {
            if (x == 0 && y == 0) {
                continue;
            }
            vec2 tuv = uv + vec2(float(x), float(y)) * texel;
            // 区域边界守卫：越出本视口区域即跳过（分屏时不得读邻视口的 AO）
            vec2 s = (tuv - pc.uvRect.xy) / pc.uvRect.zw;
            if (any(lessThan(s, vec2(0.0))) || any(greaterThan(s, vec2(1.0)))) {
                continue;
            }
            vec4 twp = texture(gbufferWorldPos, tuv);
            if (twp.w < 0.5) {
                continue;
            }
            float tz = (cam.view * vec4(twp.xyz, 1.0)).z;
            float wz = exp(-abs(tz - cz) / sigmaZ);              // 保边（深度差指数衰减）
            float ws = exp(-float(x * x + y * y) / 8.0);         // 空间核（σ ≈ 2 像素）
            float w = wz * ws;
            sum += texture(aoRaw, tuv).r * w;
            wsum += w;
        }
    }
    outAO = sum / wsum;
}

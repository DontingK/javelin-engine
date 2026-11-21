#version 450
// SSAO AO 计算（Engine_SSAO_Design §3.2/§3.3）：世界空间半球采样——读 G-Buffer 世界法线 +
// 世界位置（w = 有效标志：几何 1.0 / 清屏 0.0），用 set0 相机块（viewProj/view）做样本投影与
// 视空间 z 比较。**不读深度**（RHI 无深度采样路径，设计档 §2.1）。
// 覆盖制、零光照注入（注释里也不写注入标记字面量——会误触导入期注入致撞名）。
// set0 binding0 = 相机块（引擎 frameSet，动态偏移选本视口）；set1 binding0 = 世界法线、
// binding1 = 世界位置。半分辨率（视口 >>1），输出单通道 AO。
//
// BUG-12（2026-11-21）：深度偏移改**自适应**——按本像素邻域的视空间 z 逐纹素步进放大，
// 掠射面（地面/墙角斜看）不再因固定 0.02 偏小而产生自遮挡斑点；平面仍用用户设的基准偏移。
layout(set = 0, binding = 0) uniform CameraBlock {
    mat4 viewProj;
    mat4 view;
} cam;
layout(set = 1, binding = 0) uniform sampler2D gbufferNormal;
layout(set = 1, binding = 1) uniform sampler2D gbufferWorldPos;

// params：x = radius（世界单位）、y = bias（视空间 z 基准偏移）、z = biasSlopeScale（自适应倍率）
layout(push_constant) uniform SsaoPush {
    vec4 uvRect;
    vec4 params;
} pc;

layout(location = 0) in vec2 vUV;
layout(location = 0) out float outAO;

const int SAMPLE_COUNT = 16;
// 半球核：面积均匀分布（r = sqrt(u)）× 由内向外的长度梯度（近处样本密、远处样本稀）
// —— 长度已编码在向量里（0.1 ~ 1.0），样本位置直接按 radius 缩放。
const vec3 KERNEL[SAMPLE_COUNT] = vec3[16](
    vec3( 0.0178,  0.0000,  0.0993),
    vec3(-0.0244,  0.0223,  0.1027),
    vec3( 0.0042, -0.0480,  0.1120),
    vec3( 0.0407,  0.0531,  0.1265),
    vec3(-0.0894, -0.0158,  0.1451),
    vec3( 0.1021, -0.0649,  0.1672),
    vec3(-0.0411,  0.1530,  0.1915),
    vec3(-0.0940, -0.1809,  0.2170),
    vec3( 0.2424,  0.0885,  0.2424),
    vec3(-0.2972,  0.1227,  0.2660),
    vec3( 0.1674, -0.3578,  0.2859),
    vec3( 0.1433,  0.4570,  0.2996),
    vec3(-0.4966, -0.2878,  0.3037),
    vec3( 0.6645, -0.1461,  0.2928),
    vec3(-0.4594,  0.6535,  0.2569),
    vec3(-0.1195, -0.9220,  0.1670)
);

/** 视空间 z（前向 = −Z：数值越大越近相机） */
float viewZOf(vec3 worldPos) {
    return (cam.view * vec4(worldPos, 1.0)).z;
}

/** 把 uv 夹回本视口区域（分屏时不得读邻视口的 G-Buffer） */
vec2 clampRegion(vec2 uv) {
    vec2 s = (uv - pc.uvRect.xy) / pc.uvRect.zw;
    s = clamp(s, vec2(0.0), vec2(1.0));
    return pc.uvRect.xy + s * pc.uvRect.zw;
}

/** 邻域视 z（背景/无效处回落到中心 z，避免轮廓处把偏移放大成亮边） */
float neighborZ(vec2 uv, float fallbackZ) {
    vec4 wp = texture(gbufferWorldPos, clampRegion(uv));
    return wp.w < 0.5 ? fallbackZ : viewZOf(wp.xyz);
}

void main() {
    vec2 uv = pc.uvRect.xy + vUV * pc.uvRect.zw;
    vec4 wp = texture(gbufferWorldPos, uv);
    if (wp.w < 0.5) {
        // 背景/天空（清屏 w=0）：不参与遮挡，AO=1 保原样
        outAO = 1.0;
        return;
    }
    vec3 worldPos = wp.xyz;
    vec3 n = normalize(texture(gbufferNormal, uv).xyz);
    float radius = pc.params.x;
    float bias = pc.params.y;
    float biasSlopeScale = pc.params.z;

    // 自适应深度偏移：一个 AO 纹素（= 2 个全分辨率像素）内的视 z 步进量 × 倍率。
    // 掠射面上该步进可达 0.2~0.4m，固定 0.02 的偏移根本不够 → 曲面自遮挡斑点；平面上步进近 0，
    // 偏移退回用户设的基准值，不牺牲接触遮蔽。
    vec2 stepUV = 2.0 / vec2(textureSize(gbufferWorldPos, 0));
    float cz = viewZOf(worldPos);
    float slope = max(
        max(abs(neighborZ(uv + vec2(stepUV.x, 0.0), cz) - cz),
            abs(neighborZ(uv - vec2(stepUV.x, 0.0), cz) - cz)),
        max(abs(neighborZ(uv + vec2(0.0, stepUV.y), cz) - cz),
            abs(neighborZ(uv - vec2(0.0, stepUV.y), cz) - cz)));
    float biasEff = max(bias, biasSlopeScale * slope);

    // 切向基（退化防护：参考轴与法线近平行时换轴，避免 cross/normalize 出 NaN 拉黑像素）
    vec3 up = abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 t0 = normalize(cross(up, n));
    vec3 b0 = cross(n, t0);
    // 逐像素旋转核（着色器内哈希，不引噪声纹理；带状图案由后续两遍模糊 Pass 消除）
    float ang = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) * 6.2831853;
    vec3 t = t0 * cos(ang) + b0 * sin(ang);        // 绕法线旋转基（保正交）
    vec3 b = cross(n, t);

    float occ = 0.0;
    for (int i = 0; i < SAMPLE_COUNT; i++) {
        vec3 k = KERNEL[i];
        // 正交基旋转（保长度 → 保留核的由内向外梯度），半球定向到世界法线侧
        vec3 rotated = t * k.x + b * k.y + n * k.z;
        vec3 samplePos = worldPos + rotated * radius;
        // 样本投影：世界 → 剪辑 → 帧缓冲归一化（Y 翻转已入投影，与 uvRect 同左上原点口径）
        vec4 clip = cam.viewProj * vec4(samplePos, 1.0);
        if (clip.w <= 0.0) {
            continue;
        }
        vec2 snorm = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(snorm, vec2(0.0))) || any(greaterThan(snorm, vec2(1.0)))) {
            continue;                              // 样本出屏
        }
        vec4 swp = texture(gbufferWorldPos, pc.uvRect.xy + snorm * pc.uvRect.zw);
        if (swp.w < 0.5) {
            continue;                              // 样本落在背景上
        }
        float sceneZ = viewZOf(swp.xyz);
        float sampleZ = viewZOf(samplePos);
        // 视空间前向 = −Z：sceneZ 更大 = 该处表面更近 = 它挡住本样本
        float range = smoothstep(0.0, 1.0, radius / max(abs(sceneZ - sampleZ), 1e-4));
        occ += (sceneZ > sampleZ + biasEff ? 1.0 : 0.0) * range;
    }
    outAO = clamp(1.0 - occ / float(SAMPLE_COUNT), 0.0, 1.0);
}

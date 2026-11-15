#version 450
// TAA 重投影累积（Engine_TAA_Design §3.3/§3.5；口径对齐 Babylon thinTAA `taa.fragment.fx`）：
// 读本帧场景色 + 上帧历史色（Catmull-Rom 重采样）+ G-Buffer 世界位置，用「上帧抖动后 viewProj」
// 把本像素的世界位置投回上一帧屏幕坐标取历史，按历史权重混合，写历史 RT（ping-pong 的一面；
// 拷去链输出由 taa_copy.frag 负责）。
//
// 与 thinTAA 对齐的三处（2026-11-15，随「抖 / 糊」两轮实机反馈定型）：
//  ① **钳制改为可选、默认关**（`params.y <= 0` 跳过）：Babylon `clampHistory` 默认 false。
//     低对比区域 3×3 极差极小 → 历史被钳成当前帧 → 退化为直出，亚像素抖动即现形（画面抖）。
//  ② **相机移动帧直出**（extra.x < 0.5，判据在 CPU 侧 `taaHistoryValid`/`TaaHistory.cameraMoved`，
//     对应 `disableOnCameraMove`）：移动中混合历史 = 拖影糊，此时也停抖。
//  ③ **降权阈值收紧到 0.5~2 纹素**：位移稍大就偏信当前帧，压运动物体的拖影。
// 历史重采样用 Catmull-Rom 带负瓣核（对角线权重恒正，4 角落乘积一次取数）：纯双线性逐帧复合
// 等效 ~1 纹素低通会洗掉高频，负瓣把被平均掉的高频抵回来。
// 参数口径对齐 Unity HDRP「Camera → Frame Settings → Anti-aliasing → TAA」四项（2026-11-15）：
//   锐化强度 = 链尾 SharpenPass 的 RCAS strength（不进本参数块）；
//   历史锐化 = params.z（本文件，双线性与 CR 混合）；
//   防闪烁   = params.y（3×3 AABB 钳制强度，0 = 关）；
//   速度抑制 = params.w（位移降权起始阈值，纹素；0 = 不抑制）。
// 直出当前帧的情形：历史失效（首帧/resize/切场景/相机移动帧）、背景（世界位置 w < 0.5，
// 天空无 AA 也无残影）、重投影出视口、剪辑 w <= 0。
// set0 binding0 = 参数块（动态偏移选本视口帧槽），binding1 = 场景色，binding2 = 历史色，
// binding3 = 世界位置（G-Buffer，w = 有效标志）。
// 覆盖制、零光照注入（注释里也不写注入标记字面量——会误触导入期注入致撞名）。
layout(set = 0, binding = 0) uniform TaaParams {
    mat4 prevViewProj;
    vec4 uvRect;
    vec4 params;   // x = 历史权重、y = 钳制强度（防闪烁；<= 0 = 关闭钳制）
                   // z = 历史锐化（0 = 双线性，1 = 全 Catmull-Rom）、w = 速度降权起始阈值（纹素；0 = 不抑制）
    vec4 extra;    // x = 历史有效标志、y/z = 源纹理纹素 U/V
} tp;
layout(set = 0, binding = 1) uniform sampler2D sceneColor;
layout(set = 0, binding = 2) uniform sampler2D historyColor;
layout(set = 0, binding = 3) uniform sampler2D gbufferWorldPos;

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

// Catmull-Rom 9 双线性取数（历史重采样核从三角低通换带负瓣核，压时域累积糊——纯双线性逐帧
// 复合等效 ~1 纹素低通，纹理高频被洗掉；CR 负瓣把被平均掉的高频部分抵回来）。
// tap1+2 权重恒正、和 >=1，合并为一次双线性取数；4 个角落乘积一并取（非近似，对 16-tap
// 真值逐点数值验证一致）。位置换算用纹素单位（texel = 参数块 extra.yz，全帧纹理）。
vec3 sampleHistoryCR(vec2 uv) {
    vec2 texSize = 1.0 / tp.extra.yz;
    vec2 samplePos = uv * texSize;
    vec2 texPos1 = floor(samplePos - 0.5);
    vec2 f = samplePos - texPos1 - 0.5;
    vec2 f2 = f * f;
    vec2 f3 = f2 * f;
    vec2 w0 = f2 - 0.5 * (f3 + f);
    vec2 w1 = 1.5 * f3 - 2.5 * f2 + 1.0;
    vec2 w2 = -1.5 * f3 + 2.0 * f2 + 0.5 * f;
    vec2 w3 = 0.5 * (f3 - f2);
    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / w12;
    vec2 pos0 = (texPos1 - 0.5) / texSize;
    vec2 pos3 = (texPos1 + 2.5) / texSize;
    vec2 pos12 = (texPos1 + 0.5 + offset12) / texSize;
    vec3 result = vec3(0.0);
    result += texture(historyColor, vec2(pos12.x, pos0.y)).rgb * (w12.x * w0.y);
    result += texture(historyColor, vec2(pos0.x,  pos12.y)).rgb * (w0.x  * w12.y);
    result += texture(historyColor, vec2(pos12.x, pos12.y)).rgb * (w12.x * w12.y);
    result += texture(historyColor, vec2(pos3.x,  pos12.y)).rgb * (w3.x  * w12.y);
    result += texture(historyColor, vec2(pos12.x, pos3.y)).rgb * (w12.x * w3.y);
    result += texture(historyColor, vec2(pos0.x,  pos0.y)).rgb * (w0.x  * w0.y);
    result += texture(historyColor, vec2(pos3.x,  pos0.y)).rgb * (w3.x  * w0.y);
    result += texture(historyColor, vec2(pos0.x,  pos3.y)).rgb * (w0.x  * w3.y);
    result += texture(historyColor, vec2(pos3.x,  pos3.y)).rgb * (w3.x  * w3.y);
    return result;
}

void main() {
    vec2 uv = tp.uvRect.xy + vUV * tp.uvRect.zw;
    vec2 texel = tp.extra.yz;
    vec3 current = texture(sceneColor, uv).rgb;
    vec4 wp = texture(gbufferWorldPos, uv);
    if (tp.extra.x < 0.5 || wp.w < 0.5) {
        outColor = vec4(current, 1.0);
        return;
    }
    // 世界位置 → 上一帧剪辑空间 → 上一帧屏幕 UV（投影已含 Y 翻转，ndc→uv 不再翻转）
    vec4 prevClip = tp.prevViewProj * vec4(wp.xyz, 1.0);
    if (prevClip.w <= 0.0) {
        outColor = vec4(current, 1.0);
        return;
    }
    vec2 prevUV = prevClip.xy / prevClip.w * 0.5 + 0.5;
    vec2 lo = tp.uvRect.xy;
    vec2 hi = tp.uvRect.xy + tp.uvRect.zw;
    if (any(lessThan(prevUV, lo)) || any(greaterThan(prevUV, hi))) {
        // 重投影落在视口外（上一帧该处无内容）→ 无法取历史
        outColor = vec4(current, 1.0);
        return;
    }
    // 历史采样：双线性基线 + Catmull-Rom 负瓣核，按「历史锐化」（params.z）混合——
    // 0 = 纯双线性（更糊但更稳）、1 = 全 CR（抗时域累积低通，默认）。
    vec3 history = texture(historyColor, prevUV).rgb;
    if (tp.params.z > 0.0) {
        history = mix(history, sampleHistoryCR(prevUV), tp.params.z);
    }
    if (tp.params.y > 0.0) {
        // 3×3 邻域钳制（默认关；开时本帧场景色 AABB 中轴不动、半幅按 clampScale 缩放——拖影抑制）
        vec3 minColor = current;
        vec3 maxColor = current;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                vec3 c = texture(sceneColor, uv + vec2(float(dx), float(dy)) * texel).rgb;
                minColor = min(minColor, c);
                maxColor = max(maxColor, c);
            }
        }
        vec3 center = 0.5 * (minColor + maxColor);
        vec3 halfExtent = 0.5 * (maxColor - minColor) * tp.params.y;
        history = clamp(history, center - halfExtent, center + halfExtent);
    }
    // 速度抑制（相机级）：重投影位移超过起始阈值（params.w，纹素）后线性降权，
    // 阈值 ×4 处降到全信当前帧——无逐物体运动矢量时的拖影缓解手段；params.w = 0 不抑制。
    vec2 disp = abs(prevUV - uv) / max(texel, vec2(1e-6));
    float weight = tp.params.x;
    if (tp.params.w > 0.0) {
        weight *= 1.0 - smoothstep(tp.params.w, tp.params.w * 4.0, max(disp.x, disp.y));
    }
    outColor = vec4(mix(current, history, weight), 1.0);
}

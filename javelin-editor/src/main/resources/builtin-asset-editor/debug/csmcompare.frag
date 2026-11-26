#version 450
// 光追遮挡 vs CSM 遮挡逐像素对照（P4-06 B4，Engine_RayTracing_Plan §3.6）。
//
// 输入三张图：光追主光方向求交距离图（mode 2，>=0 = 被遮挡）、G-Buffer 世界位置、
// 阴影 atlas（比较采样器）。CSM 侧走注入的 javelinCsmCore（与实渲染 javelinCascadeShadow
// 同源：级联投影、盒外判定、半纹素外扩 + 用户偏移），只取单 tap 比较，不做 PCF/PCSS——
// 对照看的是「遮挡真值」，不是半影过渡。
//
// 配色：绿 = 两边一致（阴影）；白 = 两边一致（受光）；
//       红 = RT 挡、CSM 亮（CSM 漏影）；青绿 = CSM 挡、RT 亮（CSM 过度）；灰 = 级联外/背景。
//@javelinCsmCore

layout(set = 0, binding = 0) uniform sampler2D uHitDistance;
layout(set = 0, binding = 1) uniform sampler2D uWorldPos;
layout(set = 0, binding = 2) uniform sampler2DShadow uShadowMap;

// 与 RenderingSystem.packCsmCompareParams 同名同序（std140，304B）
layout(std140, set = 0, binding = 3) uniform CsmCompareBlock {
    mat4 sunViewProj[4];
    vec4 shadowParams;
    vec4 shadowParams2;
    vec4 cascadeInvZ;
} csm;

layout(push_constant) uniform PushBlock {
    ivec2 targetSize;
} pc;

layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = gl_FragCoord.xy / vec2(pc.targetSize);
    vec4 wp = texture(uWorldPos, uv);
    if (wp.w < 0.5) {
        outColor = vec4(0.05, 0.05, 0.05, 1.0);   // 背景：无几何
        return;
    }
    bool rtBlocked = texture(uHitDistance, uv).r >= 0.0;

    // 级联选择：逐级跑核心取第一个盒内有效（与实渲染的判定同源，免去另传 splits）
    bool inCascade = false;
    bool csmBlocked = false;
    for (int c = 0; c < 4; c++) {
        vec4 cs = javelinCsmCore(c, wp.xyz, csm.sunViewProj[c], csm.cascadeInvZ[c],
                csm.shadowParams, csm.shadowParams2);
        if (cs.w < 0.5) {
            continue;
        }
        inCascade = true;
        csmBlocked = texture(uShadowMap, vec3(cs.xy, cs.z)) < 0.5;
        break;
    }

    if (!inCascade) {
        outColor = vec4(0.35, 0.35, 0.35, 1.0);   // 四级联都盒外：无对照意义
    } else if (rtBlocked && csmBlocked) {
        outColor = vec4(0.10, 0.45, 0.15, 1.0);    // 一致：阴影
    } else if (rtBlocked) {
        outColor = vec4(0.90, 0.15, 0.10, 1.0);    // CSM 漏影
    } else if (csmBlocked) {
        outColor = vec4(0.15, 0.85, 0.55, 1.0);    // CSM 过度
    } else {
        outColor = vec4(0.80, 0.80, 0.80, 1.0);    // 一致：受光
    }
}

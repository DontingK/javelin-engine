#version 450
// DDGI 探针 atlas 直显片元着色器（编辑器排障面 dbg-3，Engine_DDGI_ProbeViz_Design §3 V2）。
//
// 把辐照度 / 深度 atlas 整张贴屏（按 atlas 像素 1:1 texelFetch），并叠加 tile 分隔线：
// 未扫过的 tile 边框画红、扫过的画暗灰 —— 「这一圈扫到第几个 tile」一眼可见。
// 辐照度存的是 E = ∫L·cosθ·dω（更新段已乘 π），直显按 E/π 出图（否则整体亮 π 倍）；
// 深度存两矩 E(d)/E(d²)，直显只取 E(d) 并按 MAX_RAY_DISTANCE 归一化（scale 由 Pass 传）。
//
// 独立于照明链路——只在本 Pass 自建 set0 声明一张采样器（debugtext / gbufferview 同款
// 自建 set0 先例）。注意：源内不得出现光照注入标记字面量（写在注释里也算命中），
// 故本注释只写标记的用途，不写其完整字面量。
layout(set = 0, binding = 0) uniform sampler2D ddgiAtlas;

layout(push_constant) uniform PushBlock {
    int mode;     // 2 = 辐照度 atlas / 3 = 深度 atlas
    float scale;  // 辐照度恒 1（内部除 π）；深度 = 1 / MAX_RAY_DISTANCE
} pc;

layout(location = 0) out vec4 outColor;

// tile 边长（与 DdgiProbeGrid.IRRADIANCE_TILE / DEPTH_TILE 同源：内区 8/16 + 两侧 1px 边框）
const int IRRADIANCE_TILE_PX = 10;
const int DEPTH_TILE_PX = 18;
const float PI_VALUE = 3.14159265;

void main() {
    ivec2 sizePx = textureSize(ddgiAtlas, 0);
    // 与 GBufferView 同款取 UV：帧缓冲与图同尺寸、按 gl_FragCoord 绝对坐标 1:1 取回本像素
    vec2 uv = gl_FragCoord.xy / vec2(sizePx);
    int tile = (pc.mode == 2) ? IRRADIANCE_TILE_PX : DEPTH_TILE_PX;
    ivec2 px = ivec2(uv * vec2(sizePx));
    ivec2 tileSize = ivec2(tile);
    ivec2 local = px % tileSize;   // GLSL 的 % 两侧必须同类型：int 广域化到 ivec2
    ivec2 tileOrigin = px - local;
    ivec2 tileCenter = tileOrigin + ivec2(tile / 2, tile / 2);

    vec3 value;
    bool unswept;
    if (pc.mode == 2) {
        vec3 irradiance = texelFetch(ddgiAtlas, px, 0).rgb;
        value = irradiance / PI_VALUE * pc.scale;
        // 内区中心 texel 全黑 ⇒ 这个 tile 还没被任何一刀扫到（纯黑探针会误判，属可接受的假阳）
        vec4 centerSample = texelFetch(ddgiAtlas, tileCenter, 0);
        float center = centerSample.r + centerSample.g + centerSample.b;
        unswept = center < 1e-5;
    } else {
        float meanDistance = texelFetch(ddgiAtlas, px, 0).r;
        value = vec3(meanDistance * pc.scale);
        unswept = texelFetch(ddgiAtlas, tileCenter, 0).r < 1e-6;
    }

    // tile 边框（1px）：未扫过 = 红，扫过 = 暗灰（压暗 50%，不盖内区方向图）
    bool border = local.x <= 0 || local.y <= 0 || local.x >= tile - 1 || local.y >= tile - 1;
    if (border) {
        outColor = unswept ? vec4(1.0, 0.15, 0.15, 1.0) : vec4(0.16, 0.16, 0.18, 1.0);
    } else if (max(max(value.r, value.g), value.b) > 1.0) {
        // 溢出标记：辐照度 E/π > 1（或深度 > MAX_RAY_DISTANCE）→ 洋红，避免截断看不出量级
        outColor = vec4(1.0, 0.0, 1.0, 1.0);
    } else {
        outColor = vec4(max(value, vec3(0.0)), 1.0);
    }
}

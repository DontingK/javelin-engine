#version 450
// DDGI 探针 atlas 直显片元着色器（编辑器排障面 dbg-3，Engine_DDGI_ProbeViz_Design §3 V2）。
//
// 把辐照度 / 深度 atlas 整张按**视口铺满**贴出（屏幕像素 → atlas texel 最近邻取样，即块状
// 放大），并叠加 tile 分隔线：未扫过的 tile 边框画红、扫过的画暗灰 —— 「这一圈扫到第几个
// tile」一眼可见。辐照度存的是 E = ∫L·cosθ·dω（更新段已乘 π），直显按 E/π 出图（否则整体
// 亮 π 倍）；深度存两矩 E(d)/E(d²)，直显只取 E(d) 并按 MAX_RAY_DISTANCE 归一化（scale 由
// Pass 传）。
//
// 独立于照明链路——只在本 Pass 自建 set0 声明一张采样器（debugtext / gbufferview 同款
// 自建 set0 先例）。注意：源内不得出现光照注入标记字面量（写在注释里也算命中），
// 故本注释只写标记的用途，不写其完整字面量。
layout(set = 0, binding = 0) uniform sampler2D ddgiAtlas;
// 「这一格扫过没」的**精确**标志：辐照度图更新段每个内区 texel 都写 alpha=1，初始为 0。
// 单独绑它是因为深度图是 RG16F 没有 alpha，而「命中距离 0」（贴地/埋进几何体的探针射线
// 一出发就命中）与「从没写过」在数值上不可区分——早期拿中心 texel 是否为零当判据，这类
// 退化探针会被永远标成未扫（2026-11-25 实机：固定几格红）。
layout(set = 0, binding = 1) uniform sampler2D sweepAtlas;

layout(push_constant) uniform PushBlock {
    int mode;      // 2 = 辐照度 atlas / 3 = 深度 atlas
    float scale;   // 辐照度恒 1（内部除 π）；深度 = 1 / MAX_RAY_DISTANCE
    vec2 viewport; // 视口像素尺寸（铺满用；atlas 只有 150px，按 1:1 取只会占左上角一小块）
} pc;

layout(location = 0) out vec4 outColor;

// tile 边长（与 DdgiProbeGrid.IRRADIANCE_TILE / DEPTH_TILE 同源：内区 8/16 + 两侧 1px 边框）
const int IRRADIANCE_TILE_PX = 10;
const int DEPTH_TILE_PX = 18;
const float PI_VALUE = 3.14159265;

void main() {
    ivec2 sizePx = textureSize(ddgiAtlas, 0);
    // 铺满视口：屏幕像素 → 归一化 uv → atlas 像素（texelFetch 取最近 texel，等效块状放大）。
    // 早期照抄 GBufferView 的「gl_FragCoord / atlas 边长」1:1 取法，但 atlas 仅 150px 而视口
    // 上千像素，结果整张图只缩在视口左上角一小块（2026-11-25 实机：左上角灰边 + 红蓝梯度）。
    vec2 uv = gl_FragCoord.xy / pc.viewport;
    ivec2 px = clamp(ivec2(uv * vec2(sizePx)), ivec2(0), sizePx - ivec2(1));
    // tile 边长按模式选：辐照度 10px（8 内区 + 两侧边框）/ 深度 18px（16 + 2）
    int tile = (pc.mode == 2) ? IRRADIANCE_TILE_PX : DEPTH_TILE_PX;
    ivec2 tileSize = ivec2(tile);
    ivec2 local = px % tileSize;   // GLSL 的 % 两侧必须同类型：int 广域化到 ivec2

    // 「扫过没」：读辐照度图对应 tile 中心的 alpha（写入即置 1，初始 0），与图里数值无关。
    // 深度档也用它——深度数值可能是合法的 0（探针贴地/埋进几何体，射线一出发就命中）。
    ivec2 tileIdx = ivec2(px.x / tile, px.y / tile);
    ivec2 sweepPx = tileIdx * IRRADIANCE_TILE_PX + ivec2(IRRADIANCE_TILE_PX / 2);
    vec4 sweepSample = texelFetch(sweepAtlas, sweepPx, 0);
    bool unswept = sweepSample.a < 0.5;

    vec3 value;
    if (pc.mode == 2) {
        vec3 irradiance = texelFetch(ddgiAtlas, px, 0).rgb;
        value = irradiance / PI_VALUE * pc.scale;
    } else {
        float meanDistance = texelFetch(ddgiAtlas, px, 0).r;
        // 深度显示窗口按 Pass 传的 scale（1/5m）归一，超出即纯白：按 1/60 归一的话
        // 0.6~2.8m 的实测命中距离只落到 0.01~0.05，内区一片纯黑看不出结构。
        value = vec3(clamp(meanDistance * pc.scale, 0.0, 1.0));
    }

    // tile 边框（1px）：未扫过 = 红，扫过 = 暗灰（压暗 50%，不盖内区方向图）
    bool border = local.x <= 0 || local.y <= 0 || local.x >= tile - 1 || local.y >= tile - 1;
    if (border) {
        outColor = unswept ? vec4(1.0, 0.15, 0.15, 1.0) : vec4(0.16, 0.16, 0.18, 1.0);
    } else if (pc.mode == 2 && max(max(value.r, value.g), value.b) > 1.0) {
        // 溢出标记：辐照度 E/π > 1 → 洋红，避免截断看不出量级（深度档已 clamp 到 [0,1]，不参与）
        outColor = vec4(1.0, 0.0, 1.0, 1.0);
    } else {
        outColor = vec4(max(value, vec3(0.0)), 1.0);
    }
}

#version 450
// 阴影图（CSM atlas）直显片元着色器（编辑器排障面 DBG-03，Engine_Shadow_Quality_Design §4.7）。
//
// 把该视口的 CSM 深度 atlas 整张贴屏：mode 1 = 原始深度灰度（远=白、遮挡体=暗块），
// mode 2 = 按 2×2 象限给级联分色（看级联覆盖与接缝）。深度只作"深度值"直读，不做
// 比较采样（比较采样会把结果变成 0/1 可见性，丢掉深度分布，看不出纹素吸附/漏光）。
//
// 独立于照明链路——只在本 Pass 自建 set0 声明一张采样器。注意：源内不得出现光照注入
// 标记字面量（写在注释里也算命中），故本注释只写用途不写标记本体。
layout(set = 0, binding = 0) uniform sampler2D shadowAtlas;

layout(push_constant) uniform PushBlock {
    int mode;       // 1 = 原始深度 / 2 = 级联分色
    int grid;       // atlas 每轴象限数（1 = 单张 / 2 = 2×2）
    // 视口矩形（像素，与 gl_FragCoord 同空间）：atlas 尺寸与视口无关，须按视口归一化。
    // 用四个标量而非 vec4——vec4 在 push constant 里要求 16 字节对齐，会与 int×2 之间
    // 插入 8 字节填充，与 Java 侧紧凑写入错位（表现为读到的视口全 0 → uv 越界 → 全黑）
    float vpX;
    float vpY;
    float vpW;
    float vpH;
} pc;

layout(location = 0) out vec4 outColor;

// 级联调色（cascade 0..3；顺序 = atlas 象限：左上 / 右上 / 左下 / 右下）
vec3 cascadeTint(int c) {
    if (c == 0) return vec3(1.00, 0.62, 0.30);
    if (c == 1) return vec3(0.35, 0.85, 0.40);
    if (c == 2) return vec3(0.35, 0.60, 1.00);
    return vec3(0.90, 0.40, 0.95);
}

void main() {
    ivec2 sizePx = textureSize(shadowAtlas, 0);
    // 视口局部坐标归一化 → 铺满**整张** atlas。原版按 gl_FragCoord/atlas 边长 1:1 取，
    // 视口小于 atlas 时只会显示左上角一块（原始深度全白、分色全是级联 0 的橙）
    vec2 uv = (gl_FragCoord.xy - vec2(pc.vpX, pc.vpY))
            / max(vec2(pc.vpW, pc.vpH), vec2(1.0));
    ivec2 px = clamp(ivec2(uv * vec2(sizePx)), ivec2(0), sizePx - ivec2(1));
    float depth = texelFetch(shadowAtlas, px, 0).r;

    // 象限分隔线（1px 暗灰）：看清版面与各象限边界（按 grid 通用，非方 atlas 也对）
    bool seam = false;
    if (pc.grid > 1) {
        ivec2 quad = sizePx / pc.grid;
        seam = (px.x % quad.x == 0) || (px.y % quad.y == 0);
    }

    vec3 base;
    if (pc.mode == 2) {
        int col = px.x * pc.grid / sizePx.x;
        int row = px.y * pc.grid / sizePx.y;
        int cascade = row * pc.grid + col;
        // 遮挡体（深度小）压暗、空白（深度 1）保持原色，级联色一眼可辨
        base = cascadeTint(cascade) * (0.2 + 0.8 * depth);
    } else {
        base = vec3(depth);
    }
    outColor = seam ? vec4(0.12, 0.12, 0.14, 1.0) : vec4(base, 1.0);
}

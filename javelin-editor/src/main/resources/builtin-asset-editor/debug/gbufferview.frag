#version 450
// G-Buffer 可视化片元着色器（编辑器排障面 dbg-2，Engine_DebugTools_Plan §3）：按 push 常量
// mode 三选一采样 G-Buffer 附件（albedo / 世界法线 / 世界位置）整张贴屏，肉眼比对中间产物。
//
// 独立于照明链路——不声明 cam / 光照段 / 材质块（本 Pass 自建 set0 = 三张采样器，非引擎
// 默认 set0 相机块；debugtext.frag 同款自建 set0 先例）。
// 注意：源内不得出现光照注入标记字面量（即便写在注释里也算命中），否则导入器会注入
// 相机块 + 光照段 + G-Buffer 三采样声明，与本文件的三采样器重名报 redefinition——
// 故本注释刻意只写标记的标识名，不写其完整字面量。
layout(set = 0, binding = 0) uniform sampler2D gbufferAlbedo;
layout(set = 0, binding = 1) uniform sampler2D gbufferNormal;
layout(set = 0, binding = 2) uniform sampler2D gbufferWorldPos;

layout(push_constant) uniform PushBlock {
    int mode;   // 1=albedo / 2=世界法线 / 3=世界位置
} pc;

layout(location = 0) out vec4 outColor;

// 世界位置视图的可见半程（世界单位）：±WORLD_POS_RANGE 线性映射到 [0,1]，0=中灰、超出饱和。
// 原始 xyz 直传 UNORM 缓冲会把负值钳成 0（负坐标几何全黑看不见），故必做此带符号重映射。
// 场景尺度超出 ±10 时调大此常量即可。
const float WORLD_POS_RANGE = 10.0;

void main() {
    // 与 LightingPass 同款：G-Buffer 与帧缓冲同尺寸、按同一 viewport/scissor 栅格化，
    // 按 gl_FragCoord 绝对坐标换算 UV，1:1 取回本像素（分屏时各视口各取自己的区域）
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(gbufferWorldPos, 0));
    if (pc.mode == 1) {
        outColor = vec4(texture(gbufferAlbedo, uv).rgb, 1.0);
    } else if (pc.mode == 2) {
        // 世界法线 [-1,1] → [0,1] 可见域（+Y 上表面偏绿）
        outColor = vec4(texture(gbufferNormal, uv).xyz * 0.5 + 0.5, 1.0);
    } else {
        // 世界位置：带符号线性重映射——世界原点=中灰，+xyz 变亮、-xyz 变暗，±RANGE 外饱和
        // （越界值仍一眼可见；负坐标与正值同法可见，不再钳黑）
        vec3 p = texture(gbufferWorldPos, uv).xyz;
        outColor = vec4(p / (2.0 * WORLD_POS_RANGE) + 0.5, 1.0);
    }
}

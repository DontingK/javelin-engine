package cn.donting.javelin.editor.importer.shader;

import cn.donting.javelin.core.render.material.LightingModel;
import cn.donting.javelin.core.render.material.MaterialProperty;
import cn.donting.javelin.core.render.material.MaterialPropertyType;
import cn.donting.javelin.editor.plugin.EditorLang;

import java.util.ArrayList;
import java.util.List;

/**
 * 声明注入器（RHI 21.5 节，v0.13）：把引擎内置块与材质声明注入到 vs/fs 块的
 * {@code #version} 行之后（无标记，位置固定）。
 *
 * <p>注入内容（与运行时实际通道一一对应，Engine_Rendering_Design 第 6 节）：
 * <pre>
 * layout(set = 0, binding = 0) uniform CameraBlock { mat4 viewProj; mat4 view; } cam;  // UBO1 动态偏移选相机
 * layout(push_constant) uniform PushBlock { mat4 model; float tpf; } pc;     // 每物件 push
 * layout(std140, set = 2, binding = 0) uniform MaterialBlock { ... } material; // 数值属性（有才注入）
 * layout(set = 2, binding = N) uniform sampler2D _Name;                      // 贴图槽，声明序
 * </pre>
 * MaterialBlock 字段排布与 {@code MaterialBlock.pack} 同源（RHI 15.7 三方布局一致）：
 * 声明序、跳过 2D；float（FLOAT/RANGE）/ vec4（COLOR/VECTOR），std140 对齐由 GLSL 编译器与
 * CPU 排布器同规则保证；无数值属性不注入 MaterialBlock（空块 16B 占位只存在 CPU 侧，
 * 描述符布局超集 Vulkan 合法）。</p>
 *
 * <p>fs 专属（批次③，Engine_Lighting_Design §3/§6/§7）：set1 灯光/cluster 声明 +
 * {@code javelinLighting} 光照段函数（Lambert + 环境项 + 点/聚光衰减）。不调用该函数
 * 的 fs 不受影响（超集合法，与批次②兼容口径同族）。</p>
 *
 * <p>保留字校验（RHI 21.6-7）：块名/数据面实例名手写即构建报错（lighting / lightList /
 * clusterGrid / clusterIndices 等批次②③保留字，Engine_Uniform_Keys §6）；注入函数
 * {@code javelinLighting} 与 cam / pc / material 同类——用户直接使用，重定义由
 * GLSL 编译期兜底。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class ShaderInjector {

    private static final String CAMERA_BLOCK = """
            layout(set = 0, binding = 0) uniform CameraBlock {
                mat4 viewProj;
                mat4 view;
            } cam;""";
    private static final String PUSH_BLOCK =
            "layout(push_constant) uniform PushBlock { mat4 model; float tpf; float receiveShadow; } pc;";
    private static final String ENGINE_BLOCKS = CAMERA_BLOCK + "\n" + PUSH_BLOCK;

    /**
     * per-object 接收阴影标志取法（四期 §3.6）——按注入变体不同：
     *
     * <ul>
     *   <li><b>材质 fs</b>（FORWARD 档）：读 push 空余位 {@code pc.receiveShadow}
     *       （{@code RenderingSystem.PUSH_RECEIVE_SHADOW_OFFSET=68}）——逐物体绘制天然
     *       per-draw；PushBlock 已注入，直接读。</li>
     *   <li><b>全屏光照</b>（HYBRID 档）：LightingPass 无物体身份，标志经 G-Buffer
     *       {@code albedo.a} 逐像素传回（GBuffer 几何 Pass 写 1/0），按 gl_FragCoord
     *       像素坐标 texelFetch 取。albedo 为 RGBA8，0.5→~0.502 &gt; 0.5 判真可靠</li>
     * </ul>
     *
     * <p>{@code javelinLighting} 内不区分变体（同一常量），只调本辅助函数——故 LIGHTING_BLOCKS
     * 两档同源，差异仅在这两个变体常量。</p>
     */
    private static final String RECEIVE_SHADOW_PUSH = """
            float javelinReceiveShadow() {
                return pc.receiveShadow;
            }""";
    private static final String RECEIVE_SHADOW_GBUFFER = """
            float javelinReceiveShadow() {
                return texelFetch(gbufferAlbedo, ivec2(gl_FragCoord.xy), 0).a > 0.5 ? 1.0 : 0.0;
            }""";

    /**
     * set1 光照声明 + 光照段函数（只注入 fs，Engine_Lighting_Design §3.3/§3.4/§6/§7
     * 批次③版 + 批次④主光阴影段）——排布与 RenderingSystem.packLightingParams 同源：
     * directional[16]（8 盏 × 2 vec4，槽 0=主光，每盏 dir+castShadow / color+intensity）、
     * lightCounts（x=dirCount, y=pointCount）、gridDim（xyz=维度, w=tileSize）、
     * zParams（x=near, y=far, z=ortho 标志）、viewport（帧缓冲原点+尺寸，
     * gl_FragCoord 定位 cell 用）、ambient、sunViewProj（批次④：主光 世界→光裁剪，
     * fs 重建 shadowMap 采样坐标）、shadowParams（四期阴影质量分级：x=品质档编码 /
     * y=PCF 半径（纹素）/ z=阴影强度 / w=参考深度偏移）。
     *
     * <p>{@code javelinLighting(viewNormal, viewPos, worldPos)}：Lambert + 环境项 +
     * 点/聚光衰减（§7）+ 主光阴影（§4.5 批次④门控 + 四期品质档：槽 0 castShadow 位门控，
     * sunViewProj 投影 → NDC xy 映 [0,1] → 硬影单采样 / 4 tap 复现 3×3 / 9 tap 复现 5×5
     * 高斯（MJP 亚纹素权重），盒外受光；强度与参考深度偏移由 shadowParams 控制）。
     * ambient + 方向光全局段（UBO 直读）+ gl_FragCoord 定位 cell →
     * clusterIndices 索引段 → lightList 逐盏（inverse-square + range smoothstep
     * 滚降；SPOT 锥判定 cos 平滑带）。视空间光照全链（vs 用 {@code cam.view} 传出
     * 插值）+ 阴影重建世界位置插值（vs 从 {@code pc.model} 直出）。调用方：
     * {@code vec3 color = albedo * javelinLighting(n, p, wp);}。</p>
     */
    /**
     * CSM 级联求值核心（P4-06 B3 对照前置）：级联矩阵投影 → 盒外判定 → atlas 象限 uv →
     * 参考深度（含半纹素外扩 + 用户偏移）。**注入段与对照 Pass 共用这一份**（改动只在此处）。
     *
     * <p>返回 {@code vec4(uv.x, uv.y, refZ, valid)}；{@code valid = 0} 表示落在级联盒外
     * （调用方按受光处理）。PCF/PCSS 软化不进核心——它只影响半影过渡，不影响遮挡真值。</p>
     */
    public static final String CSM_CORE_GLSL = """
            vec4 javelinCsmCore(int cascade, vec3 worldPos, mat4 sunViewProj, float invZ,
                    vec4 shadowParams, vec4 shadowParams2) {
                vec3 proj = (sunViewProj * vec4(worldPos, 1.0)).xyz;
                if (proj.x < -1.0 || proj.x > 1.0 || proj.y < -1.0 || proj.y > 1.0
                        || proj.z < 0.0 || proj.z > 1.0) {
                    return vec4(0.0);
                }
                float grid = shadowParams2.x;
                float atlasSize = shadowParams2.y;
                float quad = 1.0 / grid;
                int col = cascade - (cascade / int(grid)) * int(grid);
                int row = cascade / int(grid);
                vec2 quadOrigin = vec2(float(col), float(row)) * quad;
                vec2 uv = quadOrigin + (proj.xy * 0.5 + 0.5) * quad;
                // 接触漏光修复（2026-11-20）：遮挡轮廓只能落在纹素边界，按当前级联归一化深度
                // 补半个纹素；与用户偏移（世界单位 × invZ）叠加，后者用于细调。
                float cascadeTexels = atlasSize / grid;
                float refZ = proj.z + shadowParams.w * invZ + 0.5 / cascadeTexels;
                return vec4(uv, refZ, 1.0);
            }""";

    /** 对照 Pass 注入标记：只内联 {@link #CSM_CORE_GLSL}（不注入相机块/光照函数） */
    public static final String CSM_CORE_MARKER = "//@javelinCsmCore";

    private static final String LIGHTING_BLOCKS = CSM_CORE_GLSL + "\n" + """
            layout(std140, set = 1, binding = 0) uniform LightingParamsBlock {
                vec4 directional[16];
                ivec4 lightCounts;
                ivec4 gridDim;
                vec4 zParams;
                vec4 viewport;
                vec4 ambient;
                mat4 sunViewProj[4];
                vec4 cascadeSplits;
                vec4 shadowParams;
                vec4 shadowParams2;
                vec4 cascadeInvZ;
                vec4 contactParams;   // x = 开关(0/1)，y = tMax，z = 强度，w = 步数（REND-21-C2）
                vec4 rtParams;        // x = 混合系数(0 纯 CSM / 1 全替换 / 中间混合)，y = tMax，z,w = 全 backbuffer 尺寸（P4-06 B5）
                vec4 rtgiParams;      // x = GI 混合系数(0 关 / 1 全替换环境项)，y = tMax，z,w = 全 backbuffer 尺寸（P4-06 B7）
                vec4 reflectParams;   // x = 反射强度(0 关 / 1 全量)，y = tMax，z,w = 全 backbuffer 尺寸（P4-06 B9）
            } lighting;
            layout(std430, set = 1, binding = 1) readonly buffer lightList {
                vec4 lights[];
            };
            layout(std430, set = 1, binding = 2) readonly buffer clusterGridBuffer {
                uvec2 clusterGrid[];
            };
            layout(std430, set = 1, binding = 3) readonly buffer clusterIndicesBuffer {
                uint clusterIndices[];
            };
            layout(set = 1, binding = 4) uniform sampler2DShadow shadowMap;
            // 同一张 atlas 的原始深度（无 compare）：PCSS 阻挡搜索要真实深度值，比较采样器只给
            // 0/1 可见性（REND-21-C1）
            layout(set = 1, binding = 11) uniform sampler2D shadowMapRaw;

            // 光追阴影可见性图（P4-06 B5，半分辨率 R8：1 = 受光 / 0 = 遮挡）。材质 shader 只
            // 采样、不参与求交——求交在 RTShadowPass 的 compute 里；rtParams.x=0 时整段不进。
            layout(set = 1, binding = 12) uniform sampler2D rtShadowMap;

            // 光追 GI 辐照度图（P4-06 B7，半分辨率 RGBA16F：1-bounce 间接辐照度）。与 rtShadowMap
            // 同口径：按 gl_FragCoord 归一全 backbuffer 1:1 采样；rtgiParams.x=0 时不采样。
            layout(set = 1, binding = 13) uniform sampler2D rtgiMap;

            // 光追镜面反射图（P4-06 B9，半分辨率 RGBA16F：rgb = 反射辐射度，a = 命中置信）。
            // PBR 注入段按 Fresnel 份额乘进环境镜面项；reflectParams.x=0 时不采样。
            layout(set = 1, binding = 15) uniform sampler2D rtReflectMap;

            // per-object 接收阴影标志（变体实现见 RECEIVE_SHADOW_PUSH / RECEIVE_SHADOW_GBUFFER）
            float javelinReceiveShadow();

            // 单 tap 深度比较（夹取到象限界内——PCF 核不跨 atlas 象限渗漏）
            float javelinShadowTapC(vec2 tuv, float refZ, vec2 qmin, vec2 qmax) {
                return texture(shadowMap, vec3(clamp(tuv, qmin, qmax), refZ));
            }

            // PCSS 段一：阻挡搜索（REND-21-C1）——在 uv 圆盘上取 taps 个原始深度，统计 refZ
            // 以下的平均遮挡深度；无遮挡返回 -1（调用方据此直接判受光）。
            // 用黄金角螺旋散布（避免固定盘出现方向性锯齿）；半径以纹素计，逐 tap 折算成 uv。
            float javelinPcssBlockerDepth(vec2 uv, float refZ, vec2 qmin, vec2 qmax,
                    float radiusTexels, float texelSize, int taps) {
                float sum = 0.0;
                int count = 0;
                for (int i = 0; i < 24; i++) {
                    if (i >= taps) {
                        break;
                    }
                    float ang = float(i) * 2.39996323;
                    float r = sqrt((float(i) + 0.5) / float(taps)) * radiusTexels * texelSize;
                    vec2 suv = clamp(uv + vec2(cos(ang), sin(ang)) * r, qmin, qmax);
                    float d = texture(shadowMapRaw, suv).r;
                    if (d < refZ) {
                        sum += d;
                        count++;
                    }
                }
                return count == 0 ? -1.0 : sum / float(count);
            }

            // PCSS 段二：软化核——黄金角螺旋 taps 个比较采样，半径由阻挡距离推出的半影决定。
            // 固定偏移的 MJP 核（4 tap / 9 tap）在放大半径上会把每个 tap 变成一条可见亮度带，
            // 半影越大层级越多越明显，故 PCSS 档专用连续核；softness = 0 不进本函数。
            float javelinPcssSoftShadow(vec2 uv, float refZ, vec2 qmin, vec2 qmax,
                    float radiusTexels, float texelSize, int taps) {
                float sum = 0.0;
                for (int i = 0; i < 24; i++) {
                    if (i >= taps) {
                        break;
                    }
                    float ang = float(i) * 2.39996323;
                    float r = sqrt((float(i) + 0.5) / float(taps)) * radiusTexels * texelSize;
                    sum += javelinShadowTapC(uv + vec2(cos(ang), sin(ang)) * r, refZ, qmin, qmax);
                }
                return sum / float(taps);
            }

            // 单级联阴影因子（四期 CSM §4.5/§4.6）：级联选择/参考深度走共享核心
            // {@code javelinCsmCore}（与 B3 对照 Pass 同源），本函数只负责 PCF / PCSS 软化。
            float javelinCascadeShadow(int cascade, vec3 worldPos) {
                vec4 csm = javelinCsmCore(cascade, worldPos, lighting.sunViewProj[cascade],
                        lighting.cascadeInvZ[cascade], lighting.shadowParams, lighting.shadowParams2);
                if (csm.w < 0.5) {
                    return 1.0;
                }
                vec2 uv = csm.xy;
                float refZ = csm.z;
                float grid = lighting.shadowParams2.x;
                float atlasSize = lighting.shadowParams2.y;
                float quad = 1.0 / grid;
                int col = cascade - (cascade / int(grid)) * int(grid);
                int row = cascade / int(grid);
                vec2 quadOrigin = vec2(float(col), float(row)) * quad;
                int pcfMode = int(lighting.shadowParams.x);
                float pcfRadius = lighting.shadowParams.y;
                if (pcfMode <= 0 || pcfRadius <= 0.0) {
                    return texture(shadowMap, vec3(uv, refZ));
                }
                // MJP 亚纹素权重（Babylon shadowsFragmentFunctions.fx:382/443）：4 tap 复现 3×3、
                // 9 tap 复现 5×5 高斯；texelSize = 1/atlas 边长（正方形 atlas，跨级联一致）
                float texelSize = 1.0 / atlasSize;
                vec2 qmin = quadOrigin + vec2(texelSize * 0.5);
                vec2 qmax = quadOrigin + vec2(quad) - vec2(texelSize * 0.5);
                vec2 uvT = uv * atlasSize + 0.5;
                vec2 st = fract(uvT);
                vec2 base = (floor(uvT) - 0.5) * texelSize;
                // PCSS 档（REND-21-C1）：软化系数 > 0 时先做阻挡搜索，把核半径换成由遮挡距离
                // 推出的半影，再用连续螺旋核采样；无阻挡直接判受光。关档（w = 0）整段不进，
                // 仍走下方原 MJP 4/9 tap 分支，逐像素一致（判据 D）。
                // 半影尺度是相对量：遮挡深度差（归一化光空间 z）× softness × atlas 边长系数，
                // 近级联 z 范围小 → 同样世界距离的遮挡差给出更大半影，符合近处更软的直觉。
                float kernelRadius = pcfRadius;
                if (lighting.shadowParams2.w > 0.0) {
                    float blockerZ = javelinPcssBlockerDepth(uv, refZ, qmin, qmax,
                            pcfRadius * 8.0, texelSize, pcfMode == 1 ? 16 : 24);
                    if (blockerZ < 0.0) {
                        return 1.0;
                    }
                    float penumbra = (refZ - blockerZ) * lighting.shadowParams2.w * atlasSize * 0.05;
                    kernelRadius = clamp(penumbra, pcfRadius, pcfRadius * 4.0);
                    return javelinPcssSoftShadow(uv, refZ, qmin, qmax, kernelRadius, texelSize,
                            pcfMode == 1 ? 16 : 24);
                }
                if (pcfMode == 1) {
                    vec2 uvw0 = 3.0 - 2.0 * st;
                    vec2 uvw1 = 1.0 + 2.0 * st;
                    vec2 u = vec2((2.0 - st.x) / uvw0.x - 1.0, st.x / uvw1.x + 1.0) * texelSize * kernelRadius;
                    vec2 v = vec2((2.0 - st.y) / uvw0.y - 1.0, st.y / uvw1.y + 1.0) * texelSize * kernelRadius;
                    float s = uvw0.x * uvw0.y * javelinShadowTapC(base + vec2(u.x, v.x), refZ, qmin, qmax)
                            + uvw1.x * uvw0.y * javelinShadowTapC(base + vec2(u.y, v.x), refZ, qmin, qmax)
                            + uvw0.x * uvw1.y * javelinShadowTapC(base + vec2(u.x, v.y), refZ, qmin, qmax)
                            + uvw1.x * uvw1.y * javelinShadowTapC(base + vec2(u.y, v.y), refZ, qmin, qmax);
                    return s / 16.0;
                }
                vec2 uvw0 = 4.0 - 3.0 * st;
                vec2 uvw1 = vec2(7.0);
                vec2 uvw2 = 1.0 + 3.0 * st;
                vec3 u = vec3((3.0 - 2.0 * st.x) / uvw0.x - 2.0, (3.0 + st.x) / uvw1.x,
                        st.x / uvw2.x + 2.0) * texelSize * kernelRadius;
                vec3 v = vec3((3.0 - 2.0 * st.y) / uvw0.y - 2.0, (3.0 + st.y) / uvw1.y,
                        st.y / uvw2.y + 2.0) * texelSize * kernelRadius;
                float s = uvw0.x * uvw0.y * javelinShadowTapC(base + vec2(u.x, v.x), refZ, qmin, qmax)
                        + uvw1.x * uvw0.y * javelinShadowTapC(base + vec2(u.y, v.x), refZ, qmin, qmax)
                        + uvw2.x * uvw0.y * javelinShadowTapC(base + vec2(u.z, v.x), refZ, qmin, qmax)
                        + uvw0.x * uvw1.y * javelinShadowTapC(base + vec2(u.x, v.y), refZ, qmin, qmax)
                        + uvw1.x * uvw1.y * javelinShadowTapC(base + vec2(u.y, v.y), refZ, qmin, qmax)
                        + uvw2.x * uvw1.y * javelinShadowTapC(base + vec2(u.z, v.y), refZ, qmin, qmax)
                        + uvw0.x * uvw2.y * javelinShadowTapC(base + vec2(u.x, v.z), refZ, qmin, qmax)
                        + uvw1.x * uvw2.y * javelinShadowTapC(base + vec2(u.y, v.z), refZ, qmin, qmax)
                        + uvw2.x * uvw2.y * javelinShadowTapC(base + vec2(u.z, v.z), refZ, qmin, qmax);
                return s / 144.0;
            }

            // 前向声明：重载在下方定义（GLSL 需先声明后调用）
            vec3 javelinLightingEx(vec3 viewNormal, vec3 viewPos, vec3 worldPos, float contactShadow);

            vec3 javelinLighting(vec3 viewNormal, vec3 viewPos, vec3 worldPos) {
                return javelinLightingEx(viewNormal, viewPos, worldPos, 1.0);
            }

            // 带接触阴影系数的重载（REND-21-C2）：只乘主光直射项，环境/点聚光不受影响。
            // Forward 路调三参版本（系数恒 1.0）；只有全屏光照 Pass 会传真实值
            vec3 javelinLightingEx(vec3 viewNormal, vec3 viewPos, vec3 worldPos,
                                   float contactShadow) {
                // 环境项：常量基线 → 光追 GI 按 rtgiParams.x 混合替换
                vec3 ambientBase = lighting.ambient.rgb * lighting.ambient.a;
                // 光追 GI（P4-06 B7）：rtgiParams.x = 混合系数。图是半分辨率、与 G-Buffer 逐像素对齐，
                // 所以直接按 gl_FragCoord 归一到全 backbuffer 采（不投影世界位置，多视口也成立）；
                // 关档（0）整段不进，常量环境项原样保留
                if (lighting.rtgiParams.x > 0.001) {
                    vec2 giUV = gl_FragCoord.xy / max(lighting.rtgiParams.zw, vec2(1.0));
                    vec3 gi = texture(rtgiMap, giUV).rgb;
                    ambientBase = mix(ambientBase, gi, lighting.rtgiParams.x);
                }
                vec3 result = ambientBase;
                for (int i = 0; i < lighting.lightCounts.x; i++) {
                    vec3 L = -normalize(lighting.directional[2 * i].xyz);
                    float ndl = max(dot(viewNormal, L), 0.0);
                    float shadow = 1.0;
                    // 主光 CSM 阴影（四期 §4.5）：槽 0 castShadow 位门控 + per-object
                    // javelinReceiveShadow（§3.6）；按 -viewPos.z 选级联（超出末级联恒受光），
                    // 相邻级联混合带内 mix 两张（接缝软化）
                    if (i == 0 && ndl > 0.0 && lighting.directional[0].w > 0.5
                            && javelinReceiveShadow() > 0.5) {
                        // 光追档（P4-06 B5）：rtParams.x 是混合系数（0 = 纯 CSM / 1 = 全替换 / 中间两路混）。
                        // CSM 路只在非全替换时算，省掉全替换档的级联选择与 PCF；阴影强度统一在末尾乘，
                        // 保证两路口径一致（RT 档也不再绕过 shadowParams.z）
                        float rtMix = clamp(lighting.rtParams.x, 0.0, 1.0);
                        float csmShadow = 1.0;
                        if (rtMix < 0.999) {
                            int cc = int(lighting.zParams.w);
                            float dist = -viewPos.z;
                            int idx = -1;
                            for (int k = 0; k < cc; k++) {
                                if (dist < lighting.cascadeSplits[k]) {
                                    idx = k;
                                    break;
                                }
                            }
                            if (idx >= 0) {
                                float pcfShadow = javelinCascadeShadow(idx, worldPos);
                                float splitFar = lighting.cascadeSplits[idx];
                                float blendPct = lighting.shadowParams2.z;
                                if (idx + 1 < cc && blendPct > 0.0) {
                                    float blendStart = splitFar * (1.0 - blendPct);
                                    if (dist > blendStart) {
                                        float t = (dist - blendStart) / max(splitFar - blendStart, 1e-6);
                                        pcfShadow = mix(pcfShadow,
                                                javelinCascadeShadow(idx + 1, worldPos), t);
                                    }
                                }
                                csmShadow = pcfShadow;
                            }
                        }
                        // 光追路：可见性图覆盖全视口，不受级联盒与 shadowDistance 限制；
                        // 图是半分辨率全屏纹理，uv 用 gl_FragCoord 归一到全 backbuffer
                        float rtShadow = 1.0;
                        if (rtMix > 0.001) {
                            vec2 rtUV = gl_FragCoord.xy / max(lighting.rtParams.zw, vec2(1.0));
                            rtShadow = texture(rtShadowMap, rtUV).r;
                        }
                        float shadowFactor = rtMix <= 0.001 ? csmShadow
                                : (rtMix >= 0.999 ? rtShadow : mix(csmShadow, rtShadow, rtMix));
                        // 阴影强度：1 = 原强度；0 = 视觉等同关影（遮挡处保留 1-strength）
                        shadow = mix(1.0, shadowFactor, lighting.shadowParams.z);
                    }
                    result += lighting.directional[2 * i + 1].rgb
                            * lighting.directional[2 * i + 1].a * ndl * shadow
                            * (i == 0 ? contactShadow : 1.0);
                }
                if (lighting.gridDim.x <= 0) {
                    return result;
                }
                vec2 pixel = gl_FragCoord.xy - lighting.viewport.xy;
                ivec2 tile = ivec2(pixel) / lighting.gridDim.w;
                tile = clamp(tile, ivec2(0), lighting.gridDim.xy - 1);
                float dist = -viewPos.z;
                float nearZ = lighting.zParams.x;
                float farZ = lighting.zParams.y;
                int zSlice;
                if (lighting.zParams.z > 0.5) {
                    zSlice = int(clamp(float(lighting.gridDim.z) * (dist - nearZ) / max(farZ - nearZ, 1e-6), 0.0, float(lighting.gridDim.z - 1)));
                } else {
                    zSlice = int(clamp(float(lighting.gridDim.z) * log2(max(dist, nearZ) / nearZ) / log2(farZ / nearZ), 0.0, float(lighting.gridDim.z - 1)));
                }
                int cell = tile.y * lighting.gridDim.x + tile.x
                        + zSlice * lighting.gridDim.x * lighting.gridDim.y;
                uvec2 range = clusterGrid[cell];
                int base = int(range.x);
                int count = min(int(range.y), 64);
                for (int k = 0; k < count; k++) {
                    int idx = int(clusterIndices[base + k]);
                    vec4 posType = lights[4 * idx];
                    vec4 colorIntensity = lights[4 * idx + 2];
                    vec3 toLight = posType.xyz - viewPos;
                    float d = length(toLight);
                    float rangeL = lights[4 * idx + 1].w;
                    if (d >= rangeL) {
                        continue;
                    }
                    float atten = colorIntensity.a / max(d * d, 1e-4);
                    // 距离窗 = (1-(d/range)²)²（Unity URP 口径）：全程单调淡出、
                    // range 处导数归零（C¹）——无平台段即无边界线；替代初版
                    // 「前 75% 全亮 + 末 25% smoothstep」滚降（贴地掠射实测光圈过硬）
                    float dn = d / rangeL;
                    float window = 1.0 - dn * dn;
                    atten *= window * window;
                    vec3 L = toLight / max(d, 1e-4);
                    float ndl = max(dot(viewNormal, L), 0.0);
                    if (ndl <= 0.0) {
                        continue;
                    }
                    if (posType.w > 0.5) {
                        vec3 spotDir = normalize(lights[4 * idx + 1].xyz);
                        float cd = dot(-L, spotDir);
                        // spotParams：x=cos(inner)、y=cos(outer)（§3.1 LightItem 排布）
                        float cosInner = lights[4 * idx + 3].x;
                        float cosOuter = lights[4 * idx + 3].y;
                        float cone = smoothstep(cosOuter, cosInner, cd);
                        if (cone <= 0.0) {
                            continue;
                        }
                        atten *= cone;
                    }
                    result += colorIntensity.rgb * atten * ndl;
                }
                return result;
            }""";

    /**
     * G-Buffer 三采样声明（批次④-B，HYBRID 档 set1 binding5/6/7——与
     * {@code RenderingSystem.frameStorageLayoutDesc} 同源）。仅全屏光照 Pass 注入。
     */
    private static final String GBUFFER_BLOCKS = """
            layout(set = 1, binding = 5) uniform sampler2D gbufferAlbedo;
            layout(set = 1, binding = 6) uniform sampler2D gbufferNormal;
            layout(set = 1, binding = 7) uniform sampler2D gbufferWorldPos;
            layout(set = 1, binding = 14) uniform sampler2D gbufferMaterial;""";

    /**
     * roughness/metallic 取值——HYBRID 变体：读 G-Buffer material 附件（与 G-Buffer 逐像素对齐，
     * 用 textureSize 归一而不是外部参数表，多视口无需额外口径）。
     */
    private static final String MATERIAL_RM_GBUFFER = """
            vec2 javelinRoughMetal() {
                return texture(gbufferMaterial,
                        gl_FragCoord.xy / vec2(textureSize(gbufferMaterial, 0))).rg;
            }""";

    /**
     * PBR 光照块（REND-03）：GGX 微表面 + Schlick 近似 Fresnel + 能量守恒（金属度工作流）。
     *
     * <p><b>为什么不复用 {@code javelinLightingEx}</b>：Lambert 的约定是「返回辐照度、albedo 在外层
     * 乘一次」，而 PBR 必须进 BRDF（F0 依赖 albedo 与 metallic，漫反射要被镜面项扣能量），
     * 所以是独立函数 {@code javelinLightingPbr(..., albedo)}。阴影段与簇灯段与 Lambert 同源
     * （CSM/RT 混合、粗化距离窗、聚光锥角口径一致），只在 BRDF 处不同。</p>
     *
     * <p><b>亮度口径</b>：漫反射不除以 π——与既有 Lambert 的亮度习惯一致，否则 PBR 一落码全场景
     * 会暗约 3 倍、所有场景都要重调光。需要物理归一的一天再统一加。</p>
     */
    private static final String PBR_LIGHTING_BLOCKS = """
            float javelinD_GGX(float NoH, float rough) {
                float a = rough * rough;
                float a2 = a * a;
                float d = NoH * NoH * (a2 - 1.0) + 1.0;
                return a2 / max(3.14159265 * d * d, 1e-5);
            }

            float javelinG_Smith(float NoL, float NoV, float rough) {
                float a = rough * rough;
                float a2 = a * a;
                float gv = NoL * sqrt(a2 + (1.0 - a2) * NoV * NoV);
                float gl = NoV * sqrt(a2 + (1.0 - a2) * NoL * NoL);
                return 0.5 / max(gv + gl, 1e-5);
            }

            vec3 javelinF_Schlick(vec3 f0, float VoH) {
                return f0 + (vec3(1.0) - f0) * pow(max(1.0 - VoH, 0.0), 5.0);
            }

            // 单个光源的 GGX 响应（含 NoL）：kd = (1-F) × (1-metal) 即能量守恒地扣掉镜面份额
            vec3 javelinPbrDirect(vec3 N, vec3 V, vec3 L, vec3 diffuse, vec3 f0,
                    float rough, float metal) {
                vec3 H = normalize(L + V);
                float NoL = max(dot(N, L), 0.0);
                float NoV = max(dot(N, V), 1e-4);
                float NoH = max(dot(N, H), 0.0);
                float VoH = max(dot(V, H), 0.0);
                vec3 F = javelinF_Schlick(f0, VoH);
                vec3 spec = F * (javelinD_GGX(NoH, rough) * javelinG_Smith(NoL, NoV, rough)
                        / max(4.0 * NoL * NoV, 1e-4));
                vec3 kd = (vec3(1.0) - F) * (1.0 - metal);
                return (kd * diffuse + spec) * NoL;
            }

            vec3 javelinLightingPbr(vec3 viewNormal, vec3 viewPos, vec3 worldPos,
                    float contactShadow, vec3 albedo, vec2 roughMetal) {
                float rough = clamp(roughMetal.x, 0.04, 1.0);
                float metal = clamp(roughMetal.y, 0.0, 1.0);
                vec3 f0 = mix(vec3(0.04), albedo, metal);
                vec3 diffuse = albedo * (1.0 - metal);
                vec3 V = normalize(-viewPos);
                // 环境项：常量基线 → 光追 GI 混合（这里只取漫反射份额，环境镜面由 RT 反射图
                // 供给（P4-06 B9，见下方 fEnv 段），完整 IBL 归 REND-16）
                vec3 ambientBase = lighting.ambient.rgb * lighting.ambient.a;
                if (lighting.rtgiParams.x > 0.001) {
                    vec2 giUV = gl_FragCoord.xy / max(lighting.rtgiParams.zw, vec2(1.0));
                    ambientBase = mix(ambientBase, texture(rtgiMap, giUV).rgb, lighting.rtgiParams.x);
                }
                vec3 result = ambientBase * diffuse;
                // 环境镜面（P4-06 B9）：RT 反射图按 Fresnel 份额计入。粗糙度越高 Fresnel 越向 1
                // 收敛（能量摊进宽 lobe）而镜面强度越衰减——单射线近似下这是唯一能让粗糙面
                // 不出现"镜面锐利反射"的压制项。
                if (lighting.reflectParams.x > 0.001) {
                    float NoV = max(dot(normalize(viewNormal), V), 1e-4);
                    vec3 fEnv = f0 + (max(vec3(1.0 - rough), f0) - f0) * pow(1.0 - NoV, 5.0);
                    vec2 reflUV = gl_FragCoord.xy / max(lighting.reflectParams.zw, vec2(1.0));
                    vec3 refl = texture(rtReflectMap, reflUV).rgb;
                    result += refl * fEnv * (1.0 - 0.5 * rough * rough) * lighting.reflectParams.x;
                }
                for (int i = 0; i < lighting.lightCounts.x; i++) {
                    vec3 L = -normalize(lighting.directional[2 * i].xyz);
                    float ndl = max(dot(viewNormal, L), 0.0);
                    float shadow = 1.0;
                    // 主光阴影（与 Lambert 段同口径：CSM 级联 + 混合带，或 RT 可见性图按系数混合）
                    if (i == 0 && ndl > 0.0 && lighting.directional[0].w > 0.5
                            && javelinReceiveShadow() > 0.5) {
                        float rtMix = clamp(lighting.rtParams.x, 0.0, 1.0);
                        float csmShadow = 1.0;
                        if (rtMix < 0.999) {
                            int cc = int(lighting.zParams.w);
                            float dist = -viewPos.z;
                            int idx = -1;
                            for (int k = 0; k < cc; k++) {
                                if (dist < lighting.cascadeSplits[k]) {
                                    idx = k;
                                    break;
                                }
                            }
                            if (idx >= 0) {
                                float pcfShadow = javelinCascadeShadow(idx, worldPos);
                                float splitFar = lighting.cascadeSplits[idx];
                                float blendPct = lighting.shadowParams2.z;
                                if (idx + 1 < cc && blendPct > 0.0) {
                                    float blendStart = splitFar * (1.0 - blendPct);
                                    if (dist > blendStart) {
                                        float t = (dist - blendStart) / max(splitFar - blendStart, 1e-6);
                                        pcfShadow = mix(pcfShadow,
                                                javelinCascadeShadow(idx + 1, worldPos), t);
                                    }
                                }
                                csmShadow = pcfShadow;
                            }
                        }
                        float rtShadow = 1.0;
                        if (rtMix > 0.001) {
                            vec2 rtUV = gl_FragCoord.xy / max(lighting.rtParams.zw, vec2(1.0));
                            rtShadow = texture(rtShadowMap, rtUV).r;
                        }
                        float shadowFactor = rtMix <= 0.001 ? csmShadow
                                : (rtMix >= 0.999 ? rtShadow : mix(csmShadow, rtShadow, rtMix));
                        shadow = mix(1.0, shadowFactor, lighting.shadowParams.z);
                    }
                    result += lighting.directional[2 * i + 1].rgb * lighting.directional[2 * i + 1].a
                            * javelinPbrDirect(viewNormal, V, L, diffuse, f0, rough, metal)
                            * shadow * (i == 0 ? contactShadow : 1.0);
                }
                if (lighting.gridDim.x <= 0) {
                    return result;
                }
                vec2 pixel = gl_FragCoord.xy - lighting.viewport.xy;
                ivec2 tile = ivec2(pixel) / lighting.gridDim.w;
                tile = clamp(tile, ivec2(0), lighting.gridDim.xy - 1);
                float dist = -viewPos.z;
                float nearZ = lighting.zParams.x;
                float farZ = lighting.zParams.y;
                int zSlice;
                if (lighting.zParams.z > 0.5) {
                    zSlice = int(clamp(float(lighting.gridDim.z) * (dist - nearZ) / max(farZ - nearZ, 1e-6), 0.0, float(lighting.gridDim.z - 1)));
                } else {
                    zSlice = int(clamp(float(lighting.gridDim.z) * log2(max(dist, nearZ) / nearZ) / log2(farZ / nearZ), 0.0, float(lighting.gridDim.z - 1)));
                }
                int cell = tile.y * lighting.gridDim.x + tile.x
                        + zSlice * lighting.gridDim.x * lighting.gridDim.y;
                uvec2 range = clusterGrid[cell];
                int base = int(range.x);
                int count = min(int(range.y), 64);
                for (int k = 0; k < count; k++) {
                    int idx = int(clusterIndices[base + k]);
                    vec4 posType = lights[4 * idx];
                    vec4 colorIntensity = lights[4 * idx + 2];
                    vec3 toLight = posType.xyz - viewPos;
                    float d = length(toLight);
                    float rangeL = lights[4 * idx + 1].w;
                    if (d >= rangeL) {
                        continue;
                    }
                    float atten = colorIntensity.a / max(d * d, 1e-4);
                    float dn = d / rangeL;
                    float window = 1.0 - dn * dn;
                    atten *= window * window;
                    vec3 L = toLight / max(d, 1e-4);
                    float ndl = max(dot(viewNormal, L), 0.0);
                    if (ndl <= 0.0) {
                        continue;
                    }
                    if (posType.w > 0.5) {
                        vec3 spotDir = normalize(lights[4 * idx + 1].xyz);
                        float cd = dot(-L, spotDir);
                        float cosInner = lights[4 * idx + 3].x;
                        float cosOuter = lights[4 * idx + 3].y;
                        float cone = smoothstep(cosOuter, cosInner, cd);
                        if (cone <= 0.0) {
                            continue;
                        }
                        atten *= cone;
                    }
                    result += colorIntensity.rgb * atten
                            * javelinPbrDirect(viewNormal, V, L, diffuse, f0, rough, metal);
                }
                return result;
            }""";

    /**
     * 接触阴影助手（REND-21-C2，Engine_Shadow_Contact_Design §3）：世界位置沿主光方向短距
     * 步进，投影到屏幕后采 G-Buffer 世界位置比较「谁更靠近相机」——命中率即遮挡比。
     *
     * <p>只注入全屏光照 Pass（Forward 路同帧读 G-Buffer 语义不可靠），故本项天然 HYBRID 限定。
     * 不读深度附件（不需要 {@code SAMPLED}），与 SSAO 同一取证方式。</p>
     *
     * <p>三道门控压误命中（v0.3/v0.4）：掠射/背光面直接不参与（屏幕空间步进在逆光时几乎全命中），
     * 起点沿法线抬半格避开自遮挡，命中深度差限制在 {@code [容差, tMax]}（滤掉远处墙面与凸曲率绕出）。</p>
     *
     * <p>返回太阳直射项系数：1 = 无遮挡；{@code contactParams.z}（强度）越大压得越狠，0 = 关。</p>
     */
    private static final String CONTACT_SHADOW = """
            // 接触阴影（REND-21-C2）：短距屏幕空间遮挡，乘在主光直射项上
            float javelinContactShadow(vec3 worldPos, vec3 viewPos, vec3 worldNormal, vec3 towardLight) {
                if (lighting.contactParams.x < 0.5) {
                    return 1.0;
                }
                // 近景门控（v0.5）：接触阴影只补近处贴合的暗缝；中远景大面积平地上，
                // 屏幕步进的采样式条带会被屏幕压缩放大（移动相机还随采样网格变化）
                if (-viewPos.z > 4.0) {
                    return 1.0;
                }
                vec3 n = normalize(worldNormal);
                vec3 dir = normalize(towardLight);
                // 掠射/背光门控：夹角过大时主光项本身已弱，且凸曲面沿光向步进后投影回
                // 屏幕会落到自身更靠前的表面（系统性自命中 → 整幅压暗），故直接不参与
                if (dot(n, dir) < 0.35) {
                    return 1.0;
                }
                int steps = max(int(lighting.contactParams.w), 1);
                float tMax = lighting.contactParams.y;
                float strength = lighting.contactParams.z;
                // 起点沿法线抬半格，避开「第一步就采回自己所在表面」的自遮挡
                float normalBias = tMax / float(steps) * 0.5;
                float hits = 0.0;
                for (int i = 1; i <= steps; i++) {
                    vec3 p = worldPos + n * normalBias + dir * (tMax * float(i) / float(steps));
                    vec4 clip = cam.viewProj * vec4(p, 1.0);
                    if (clip.w <= 0.0) {
                        continue;
                    }
                    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
                    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
                        continue;                      // 步进点出屏
                    }
                    vec4 q = texture(gbufferWorldPos, uv);
                    if (q.w < 0.5) {
                        continue;                      // 落在背景上
                    }
                    // 视空间前向 = −Z：值更大 = 更靠近相机 = 挡在步进点前面
                    float sceneZ = -(cam.view * vec4(q.xyz, 1.0)).z;
                    float stepZ = -(cam.view * vec4(p, 1.0)).z;
                    float dz = sceneZ - stepZ;
                    // 命中带：比步进点更近相机（dz > 容差），且深度差不超过步进距离
                    // （dz < tMax）——上限按步进距离取，凸曲面上「绕出处自身更前的表面」被剔掉
                    if (dz > 0.03 && dz < tMax) {
                        hits += 1.0;
                    }
                }
                float occ = hits / float(steps);
                // v0.4：去掉 2× 放大——occ 是「十步里几步被挡」，0.5 命中就该压一半，
                // 原来乘 2 会让 0.1 强度也压 20%，观感像整幅调暗
                return 1.0 - min(occ * strength, 1.0);
            }""";

    /**
     * 全屏光照 Pass 注入标记：{@code SpvShaderImporter} 见到即注入相机块 +
     * 光照段函数 + G-Buffer 三采样（其余 .vert/.frag 保持零注入现状）。
     */
    public static final String LIGHTING_STAGE_MARKER = "//@javelinLighting";

    /**
     * 全屏光照 Pass 注入（批次④-B）：在 {@code #version} 行后拼入相机块、
     * 光照段函数与 G-Buffer 三采样。光照公式与材质 fs 的 {@code javelinLighting}
     * 是同一常量——「Forward / Deferred 两路一致」由单源保证，不做两处复制。
     */
    public static String injectLightingStage(String body, String sourceName) {
        int versionEnd = findVersionLineEnd(body, sourceName, "fs");
        StringBuilder injected = new StringBuilder(body.length() + 3072);
        injected.append(body, 0, versionEnd);
        injected.append("\n// ── engine injected lighting stage (auto-generated, do not write) ──\n");
        injected.append(CAMERA_BLOCK).append('\n');
        injected.append(LIGHTING_BLOCKS).append('\n');
        // PBR 公式块（REND-03）：全屏光照 Pass 是主渲染，一律 PBR；roughness/metallic 从
        // G-Buffer material 附件取（该附件必须已由 GBufferPass 渲染，见 GBUFFER_BLOCKS）
        injected.append(PBR_LIGHTING_BLOCKS).append('\n');
        // G-Buffer 采样声明必须先于 MATERIAL_RM_GBUFFER / RECEIVE_SHADOW_GBUFFER 函数体
        injected.append(GBUFFER_BLOCKS).append('\n');
        injected.append(MATERIAL_RM_GBUFFER).append('\n');
        injected.append(CONTACT_SHADOW).append('\n');
        injected.append(RECEIVE_SHADOW_GBUFFER).append('\n');
        injected.append("// ── injection end ──\n");
        injected.append(body, versionEnd, body.length());
        return injected.toString();
    }

    /**
     * alpha 裁剪助手（REND-21-A，Engine_Shadow_Cutout_Design §5.1）：只注入 fs（含
     * {@code discard}，片元专属）。用户 shader 在取回 albedo 后调用，阈值一般取材质属性
     * {@code _AlphaCutoff}（内置 Lit 已接）；阴影 Pass 的 {@code shadow.frag} 走同一阈值口径
     * （cutoff > 0 才启用，缺省 0 = 逐像素零变化）。
     */
    private static final String ALPHA_CLIP = """
            // alpha 裁剪（REND-21-A）：cutoff > 0 且 albedo.a < cutoff 时丢弃片元
            void javelinAlphaClip(vec4 albedo, float cutoff) {
                if (cutoff > 0.0 && albedo.a < cutoff) {
                    discard;
                }
            }""";

    private ShaderInjector() {
    }

    /**
     * 注入一个 vs/fs 块：#version 行之后拼入引擎内置块 + 材质声明。
     *
     * <p>{@link LightingModel#UNLIT} 只跳过帧级光照声明（LightingParamsBlock/lightList/
     * {@code javelinLighting}/receiveShadow）——相机块与材质声明照常注入（unlit 仍要
     * {@code cam.viewProj}、{@code pc.model}、材质属性）。</p>
     *
     * @param body          GLSL 原文（{@code #version 450} 必填，RHI 21.6-6 注入锚点）
     * @param properties    Properties 声明序（MaterialBlock 字段 + sampler 槽来源）
     * @param lightingModel shader 顶层 {@code LightingModel}（UNLIT 不注入光照段）
     * @param sourceName    源文件名（报错定位）
     * @param stageName     阶段标记（"vs"/"fs"，报错定位）
     */
    public static String inject(String body, List<MaterialProperty> properties,
                                LightingModel lightingModel,
                                String sourceName, String stageName) {
        validateReservedNames(body, sourceName, stageName);

        int versionEnd = findVersionLineEnd(body, sourceName, stageName);
        StringBuilder injected = new StringBuilder(body.length() + 2560);
        injected.append(body, 0, versionEnd);
        injected.append("\n// ── engine injected declarations (auto-generated, do not write) ──\n");
        injected.append(ENGINE_BLOCKS).append('\n');
        // set1 光照声明只入 fs（消费在片元；vs 声明未用 binding 反增布局面）
        if ("fs".equals(stageName)) {
            injected.append(lightingStageBlocks(lightingModel));
            // alpha 裁剪助手（含 discard → 仅 fs）
            injected.append(ALPHA_CLIP).append('\n');
        }
        appendMaterialDeclaration(injected, properties);
        injected.append("// ── injection end ──\n");
        injected.append(body, versionEnd, body.length());
        return injected.toString();
    }

    /**
     * 按光照模型取 set1 光照段声明块（{@link LightingModel#UNLIT} 为空）。
     *
     * <p>单一封闭轴：新增模型值必须在此显式给块集（switch 无 default，漏值即编译失败）。</p>
     */
    private static String lightingStageBlocks(LightingModel lightingModel) {
        return switch (lightingModel) {
            case LAMBERT -> LIGHTING_BLOCKS + "\n" + RECEIVE_SHADOW_PUSH + "\n";
            // PBR 与 Lambert 共用 UBO/CSM 核心/set1 声明（LIGHTING_BLOCKS），只多一段 GGX 公式块；
            // roughness/metallic 由 shader 自己取（PBR shader 必须在 Properties 里声明两个标量）
            case PBR -> LIGHTING_BLOCKS + "\n" + PBR_LIGHTING_BLOCKS + "\n"
                    + RECEIVE_SHADOW_PUSH + "\n";
            case UNLIT -> "";
        };
    }

    /**
     * 定位 #version 行结尾（其后插入）。要求 #version 450 出现在首个非空行。
     */
    private static int findVersionLineEnd(String body, String sourceName, String stageName) {
        int i = 0;
        int length = body.length();
        while (i < length && (body.charAt(i) == ' ' || body.charAt(i) == '\t'
                || body.charAt(i) == '\r' || body.charAt(i) == '\n')) {
            i++;
        }
        if (!body.startsWith("#version", i)) {
            throw new ShaderParseException(EditorLang.key("shader.inject.missingVersion", stageName),
                    sourceName, 0);
        }
        while (i < length && body.charAt(i) != '\n') {
            i++;
        }
        return Math.min(i + 1, length);
    }

    /**
     * 材质声明：MaterialBlock（数值属性 std140）+ 贴图 sampler 槽（binding 1..n 声明序）。
     * 无数值属性时不注入 MaterialBlock。
     */
    private static void appendMaterialDeclaration(StringBuilder out, List<MaterialProperty> properties) {
        StringBuilder fields = new StringBuilder();
        for (MaterialProperty property : properties) {
            if (property.getType() == MaterialPropertyType.COLOR
                    || property.getType() == MaterialPropertyType.VECTOR) {
                fields.append("    vec4 ").append(property.getName()).append(";\n");
            } else if (property.getType() == MaterialPropertyType.FLOAT
                    || property.getType() == MaterialPropertyType.RANGE) {
                fields.append("    float ").append(property.getName()).append(";\n");
            }
        }
        if (!fields.isEmpty()) {
            out.append("layout(std140, set = 2, binding = 0) uniform MaterialBlock {\n");
            out.append(fields);
            out.append("} material;\n");
        }

        int binding = 1;
        for (MaterialProperty property : properties) {
            if (property.getType() == MaterialPropertyType.TEXTURE_2D) {
                out.append("layout(set = 2, binding = ").append(binding++)
                        .append(") uniform sampler2D ").append(property.getName()).append(";\n");
            }
        }
    }

    /**
     * 保留字校验：注入同名声明冲突（RHI 21.6-7）。块名任何出现即报错
     * （注释里提及也会拦——保留字，写注释请换说法）。lighting / lightList /
     * LightingParamsBlock 为批次②数据面保留字；clusterGrid / clusterIndices /
     * clusterGridBuffer / clusterIndicesBuffer 为批次③ cluster 数据面保留字
     * （数据只经注入函数消费）；shadowMap 为批次④数据面保留字。
     * {@code javelinLighting} 是<b>用户可调用</b>的注入函数
     * （与 cam/pc 同类——重定义冲突由 GLSL 编译期兜底），不进构建期名单。
     */
    private static void validateReservedNames(String body, String sourceName, String stageName) {
        ArrayList<String> found = new ArrayList<>();
        for (String reserved : new String[]{"CameraBlock", "PushBlock", "MaterialBlock",
                "LightingParamsBlock", "lighting", "lightList",
                "clusterGridBuffer", "clusterIndicesBuffer", "clusterGrid", "clusterIndices",
                "shadowMap", "gbufferAlbedo", "gbufferNormal", "gbufferWorldPos"}) {
            if (body.contains(reserved)) {
                found.add(reserved);
            }
        }
        if (!found.isEmpty()) {
            throw new ShaderParseException(EditorLang.key("shader.inject.reservedName", stageName, found),
                    sourceName, 0);
        }
    }
}

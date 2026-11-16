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
    private static final String LIGHTING_BLOCKS = """
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

            // per-object 接收阴影标志（变体实现见 RECEIVE_SHADOW_PUSH / RECEIVE_SHADOW_GBUFFER）
            float javelinReceiveShadow();

            // 单 tap 深度比较（夹取到象限界内——PCF 核不跨 atlas 象限渗漏）
            float javelinShadowTapC(vec2 tuv, float refZ, vec2 qmin, vec2 qmax) {
                return texture(shadowMap, vec3(clamp(tuv, qmin, qmax), refZ));
            }

            // 单级联阴影因子（四期 CSM §4.5/§4.6）：该级联矩阵投影 → atlas 象限定位 →
            // 品质档 PCF（核夹在象限内）。shadowParams.w = 参考深度偏移（世界单位），
            // cascadeInvZ[c] = 1/光空间 z 范围（逐级联折算成裁剪 z 增量）。盒外=受光。
            float javelinCascadeShadow(int cascade, vec3 worldPos) {
                vec3 proj = (lighting.sunViewProj[cascade] * vec4(worldPos, 1.0)).xyz;
                if (proj.x < -1.0 || proj.x > 1.0 || proj.y < -1.0 || proj.y > 1.0
                        || proj.z < 0.0 || proj.z > 1.0) {
                    return 1.0;
                }
                float grid = lighting.shadowParams2.x;
                float atlasSize = lighting.shadowParams2.y;
                float quad = 1.0 / grid;
                int col = cascade - (cascade / int(grid)) * int(grid);
                int row = cascade / int(grid);
                vec2 quadOrigin = vec2(float(col), float(row)) * quad;
                vec2 uv = quadOrigin + (proj.xy * 0.5 + 0.5) * quad;
                float refZ = proj.z + lighting.shadowParams.w * lighting.cascadeInvZ[cascade];
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
                if (pcfMode == 1) {
                    vec2 uvw0 = 3.0 - 2.0 * st;
                    vec2 uvw1 = 1.0 + 2.0 * st;
                    vec2 u = vec2((2.0 - st.x) / uvw0.x - 1.0, st.x / uvw1.x + 1.0) * texelSize * pcfRadius;
                    vec2 v = vec2((2.0 - st.y) / uvw0.y - 1.0, st.y / uvw1.y + 1.0) * texelSize * pcfRadius;
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
                        st.x / uvw2.x + 2.0) * texelSize * pcfRadius;
                vec3 v = vec3((3.0 - 2.0 * st.y) / uvw0.y - 2.0, (3.0 + st.y) / uvw1.y,
                        st.y / uvw2.y + 2.0) * texelSize * pcfRadius;
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

            vec3 javelinLighting(vec3 viewNormal, vec3 viewPos, vec3 worldPos) {
                vec3 result = lighting.ambient.rgb * lighting.ambient.a;
                for (int i = 0; i < lighting.lightCounts.x; i++) {
                    vec3 L = -normalize(lighting.directional[2 * i].xyz);
                    float ndl = max(dot(viewNormal, L), 0.0);
                    float shadow = 1.0;
                    // 主光 CSM 阴影（四期 §4.5）：槽 0 castShadow 位门控 + per-object
                    // javelinReceiveShadow（§3.6）；按 -viewPos.z 选级联（超出末级联恒受光），
                    // 相邻级联混合带内 mix 两张（接缝软化）
                    if (i == 0 && ndl > 0.0 && lighting.directional[0].w > 0.5
                            && javelinReceiveShadow() > 0.5) {
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
                            // 阴影强度：1 = 原强度；0 = 视觉等同关影（遮挡处保留 1-strength）
                            shadow = mix(1.0, pcfShadow, lighting.shadowParams.z);
                        }
                    }
                    result += lighting.directional[2 * i + 1].rgb
                            * lighting.directional[2 * i + 1].a * ndl * shadow;
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
            layout(set = 1, binding = 7) uniform sampler2D gbufferWorldPos;""";

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
        // G-Buffer 三采样声明必须先于 RECEIVE_SHADOW_GBUFFER 函数体（其读 gbufferAlbedo）
        injected.append(GBUFFER_BLOCKS).append('\n');
        injected.append(RECEIVE_SHADOW_GBUFFER).append('\n');
        injected.append("// ── injection end ──\n");
        injected.append(body, versionEnd, body.length());
        return injected.toString();
    }

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

package cn.donting.javelin.editor.importer.shader;

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
            "layout(push_constant) uniform PushBlock { mat4 model; float tpf; } pc;";
    private static final String ENGINE_BLOCKS = CAMERA_BLOCK + "\n" + PUSH_BLOCK;

    /**
     * set1 光照声明 + 光照段函数（只注入 fs，Engine_Lighting_Design §3.3/§3.4/§6/§7
     * 批次③版 + 批次④主光阴影段）——排布与 RenderingSystem.packLightingParams 同源：
     * directional[16]（8 盏 × 2 vec4，槽 0=主光，每盏 dir+castShadow / color+intensity）、
     * lightCounts（x=dirCount, y=pointCount）、gridDim（xyz=维度, w=tileSize）、
     * zParams（x=near, y=far, z=ortho 标志）、viewport（帧缓冲原点+尺寸，
     * gl_FragCoord 定位 cell 用）、ambient、sunViewProj（批次④：主光 世界→光裁剪，
     * fs 重建 shadowMap 采样坐标）。
     *
     * <p>{@code javelinLighting(viewNormal, viewPos, worldPos)}：Lambert + 环境项 +
     * 点/聚光衰减（§7）+ 主光单张硬影（§4.5 批次④：槽 0 castShadow 位门控，
     * sunViewProj 投影 → NDC xy 映 [0,1] 采样 → compare LESS 深度比较，盒外受光）。
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
                mat4 sunViewProj;
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

            vec3 javelinLighting(vec3 viewNormal, vec3 viewPos, vec3 worldPos) {
                vec3 result = lighting.ambient.rgb * lighting.ambient.a;
                for (int i = 0; i < lighting.lightCounts.x; i++) {
                    vec3 L = -normalize(lighting.directional[2 * i].xyz);
                    float ndl = max(dot(viewNormal, L), 0.0);
                    float shadow = 1.0;
                    // 主光单张硬影（批次④）：槽 0 castShadow 位门控；sunViewProj 正交
                    // 投影（z 映 [0,1]），NDC xy×0.5+0.5 成采样 UV；盒外/越深度界=受光。
                    // compare LESS：参考深度（像素到光深度）< 纹素深度（最近遮挡面）= 1.0
                    if (i == 0 && ndl > 0.0 && lighting.directional[0].w > 0.5) {
                        vec3 proj = (lighting.sunViewProj * vec4(worldPos, 1.0)).xyz;
                        if (proj.x >= -1.0 && proj.x <= 1.0
                                && proj.y >= -1.0 && proj.y <= 1.0
                                && proj.z >= 0.0 && proj.z <= 1.0) {
                            vec2 uv = proj.xy * 0.5 + 0.5;
                            shadow = texture(shadowMap, vec3(uv, proj.z));
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
        injected.append(GBUFFER_BLOCKS).append('\n');
        injected.append("// ── injection end ──\n");
        injected.append(body, versionEnd, body.length());
        return injected.toString();
    }

    private ShaderInjector() {
    }

    /**
     * 注入一个 vs/fs 块：#version 行之后拼入引擎内置块 + 材质声明。
     *
     * @param body       GLSL 原文（{@code #version 450} 必填，RHI 21.6-6 注入锚点）
     * @param properties Properties 声明序（MaterialBlock 字段 + sampler 槽来源）
     * @param sourceName 源文件名（报错定位）
     * @param stageName  阶段标记（"vs"/"fs"，报错定位）
     */
    public static String inject(String body, List<MaterialProperty> properties,
                                String sourceName, String stageName) {
        validateReservedNames(body, sourceName, stageName);

        int versionEnd = findVersionLineEnd(body, sourceName, stageName);
        StringBuilder injected = new StringBuilder(body.length() + 2560);
        injected.append(body, 0, versionEnd);
        injected.append("\n// ── engine injected declarations (auto-generated, do not write) ──\n");
        injected.append(ENGINE_BLOCKS).append('\n');
        // set1 光照声明只入 fs（消费在片元；vs 声明未用 binding 反增布局面）
        if ("fs".equals(stageName)) {
            injected.append(LIGHTING_BLOCKS).append('\n');
        }
        appendMaterialDeclaration(injected, properties);
        injected.append("// ── injection end ──\n");
        injected.append(body, versionEnd, body.length());
        return injected.toString();
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

# RHI 设计 · 分卷四：着色器与材质

> 总纲：`RHI_Design.md`（含 1 概述 / 2 设计原则 / 11 架构取舍与坑点 + 完整章节索引）
> 本卷包含：第 20 Shader 多 Pass 与 Material 实例模型 / 21 着色器文件语法（.shader）
> 章节编号全局唯一（1~21），跨卷引用按编号查找，完整索引见总纲。

---


## 20. Shader 多 Pass 与 Material 实例模型（对齐 Unity，v0.6）

> 目标：对齐 Unity 概念——**Shader 是"模板"且内含多个 Pass；Material 是 Shader 的实例（参数 + 贴图）**。
> 对应关系：
> - Unity Shader ≈ 本设计的 `Shader`（多 Pass，变体暂不采用）
> - Unity Material ≈ 本设计的 `Material`（实例：参数 UBO + 贴图 + set 2 描述符集）
> - Unity MaterialPropertyBlock ≈ set 1 物体级动态 UBO（第 15.3 节）

### 20.1 为什么需要多 Pass

一个材质在引擎的不同渲染阶段需要不同的 shader 程序：

|渲染阶段|Pass 名|输出|
|---|---|---|
|Deferred 几何|GBuffer|Albedo / Normal / WorldPos / Depth|
|阴影|ShadowCaster|仅深度（Shadow Map）|
|透明 / 半透明|Transparent|Forward 混合输出|
|深度预 Pass / 后处理辅助|DepthOnly|仅深度|

如果材质只绑一个 PSO，阴影 Pass、深度预 Pass 就要另写一套逻辑。Unity 通过 Shader 内多 Pass 解决：**一个 Pass = 一个渲染阶段的着色器 + 光栅 / 混合 / 深度状态集合**。

### 20.2 概念定义

```java
// Pass：Shader 内的一个渲染阶段（对应 Unity 的 Pass 块）
public class ShaderPass {
    public final String name;          // "GBuffer" / "ShadowCaster" / "Transparent" / "DepthOnly"
    public final GraphicsPipelineDesc desc; // 该 Pass 的 shader + 状态（0 变体，见第 16.3 节）

    public GraphicsPipeline pipeline() {    // 经 PipelineCache 取 PSO（第 16.4 节）
        return pipelineCache.getOrCreate(desc);
    }
}

// Shader：模板，内含多个 Pass + 材质参数接口
public class Shader {
    public final String name;
    public final List<ShaderPass> passes;               // 多个 Pass
    public final DescriptorSetLayout materialLayout; // set 2 布局（材质参数接口）

    public ShaderPass pass(String passName) { /* 按名查找 */ }
}

// Material：Shader 的实例
public class Material {
    public final Shader shader;               // 引用模板
    private final Buffer materialUbo;      // 参数值（Cleaner 管理，第 17.1 节）
    private final DescriptorSet materialSet; // set 2（布局来自 shader.materialLayout）

    public GraphicsPipeline pipelineFor(String passName) {
        return shader.pass(passName).pipeline();  // 按渲染阶段取 PSO
    }
}
```

### 20.3 关键约束：所有 Pass 共用同一 set 2 布局

- 材质参数接口（`materialLayout`）是 **Shader 级**定义，**所有 Pass 共用**；
- 好处：一个 Material 实例（描述符集）可跨 Pass 复用——GBuffer Pass、Shadow Pass 绑同一个 `materialSet`，只是 PSO 不同（换程序不换数据）；
- 代价：每个 Pass 的 shader 都要声明相同的 MaterialBlock + 贴图绑定（即使该 Pass 不用贴图），未用 binding 绑占位资源（第 15 节）；
- 与 Unity 实践一致：Material 面板上的参数对 Shader 的所有 Pass 可见。

### 20.4 渲染阶段取 PSO（代码）

```java
// GBuffer 阶段（第 19 节 buildViewPasses 内）
pass.setRenderCallback(cmd -> {
    cmd.bindDescriptorSet(0, frameSet, 1, new int[]{camOffset});
    cmd.bindDescriptorSet(1, objSet,   1, new int[]{objOffset});
    for (RenderObject obj : scene.getOpaqueObjects()) {
        cmd.bindDescriptorSet(2, obj.material.materialSet, 0, null);
        cmd.bindPipeline(obj.material.pipelineFor("GBuffer"));  // 取 GBuffer Pass 的 PSO
        cmd.bindVertexBuffer(obj.vertexBuffer, 0);
        cmd.drawIndexed(obj.indexCount, 1);
    }
});

// 阴影阶段：同一材质，换 ShadowCaster Pass
graph.addGraphicsPass("ShadowPass", pass -> {
    pass.addDepthOutput(shadowMap, RHILoadOp.CLEAR, CLEAR_DEPTH);
    pass.setRenderCallback(cmd -> {
        for (RenderObject obj : scene.getOpaqueObjects()) {
            cmd.bindDescriptorSet(0, frameSet, 1, new int[]{lightCamOffset});
            cmd.bindDescriptorSet(1, objSet,   1, new int[]{objOffset});
            cmd.bindDescriptorSet(2, obj.material.materialSet, 0, null); // 同一实例
            cmd.bindPipeline(obj.material.pipelineFor("ShadowCaster"));  // 换 Pass PSO
            cmd.drawIndexed(obj.indexCount, 1);
        }
    });
});
```

### 20.5 变体：暂不采用（决策 v0.7）

- **本项目不引入变体系统**（复杂度高、收益小，见第 16.3 节 / 第 12.7 节）；
- 一个 Pass 一个 PSO，`Shader = Pass 列表`，无 Pass×变体网格；
- 若未来需要（如 AlphaTest、GBuffer 输出格式差异成为真实瓶颈），按第 12.7 节扩展思路引入**手动变体**：`PipelineKey = (shaderName, passName, defineFlags, layoutHash, rtHash)`，懒创建 + 预编译常用组合。

### 20.6 与文档其他章节的衔接

- 第 15.3 节 set 2：材质实例的描述符集；
- 第 16.3 / 16.4 节：0 变体策略与 PipelineCache（PSO 按 Pass 取，变体暂不采用）；
- 第 17.1 节：Material / ShaderPass / 纹理 / buffer 都是 Cleaner 负责的上层资源；Shader 对象全局驻留（模板），Material 实例按需创建；
- 第 19 节：`buildViewPasses` 中的 `pipelineCache.get("gbuffer_opaque")` 替换为 `obj.material.pipelineFor("GBuffer")`。

### 20.7 工程坑点

1. 材质参数布局跨 Pass 一致是硬约束，改布局必须同步所有 Pass 的 shader；
2. 阴影 / 深度 Pass 通常不用贴图 → 也要绑占位贴图，防止描述符布局不一致；
3. Pass 数量别膨胀（Shadow + DepthOnly + GBuffer + Transparent + ...）：用「材质用不到的 Pass 不实例化 PSO」控制显存与编译时间；
4. `pipelineFor` 每次调用走缓存查找，返回同一 PSO 实例，绝不新建（第 16.4 节）。

---


---

## 21. 着色器文件语法（.shader，v0.8）

> 目标：一个 Shader = 一个 `.shader` 文件；采用 **Unity ShaderLab 简化版**语法（用户决策）——保留 Properties / 状态命令手感，去掉 SubShader 层、FallBack、Tags.Queue 与 Pass 的 Name+LightMode 双标签，**`Pass GBuffer {}` 块名即渲染阶段名**。
> 0 变体策略贯穿：无 multi_compile，功能开关属性走 uniform 分支（第 16.3 节）。

### 21.1 设计原则（相对 Unity 的取舍）

- **保留**：`Shader "name"` 顶层块、`Properties`、状态命令（Cull / ZWrite / ZTest / Blend / ColorMask）；
- **简化**：去掉 `SubShader` 层（单平台不需要平台降级）；去掉 `Pass { Name + Tags{LightMode} }` 双标签——**`Pass GBuffer {}` 块名即 LightMode**，引擎 `pipelineFor("GBuffer")` 直接对应；
- **去掉（v0.11）**：`FallBack`（加载失败由引擎报错并替换内置默认）；`Tags.Queue`（排序完全归引擎，第 14.5 节，理由见下）；`[Toggle]` 属性语法（开关属性用普通 `Float` 声明）；
- **改掉**：不用 `GLSLPROGRAM ... ENDGLSL`（Unity 靠 `#pragma vertex / fragment` 在整段代码里找入口）——改为 **`vs { }` / `fs { }` 分离块**，入口统一 `main()`，无需 #pragma；
- **不依赖 `#include`（v0.10）**：GLSL 全部内嵌于 vs/fs 块；构建工具将引擎内置块（camera / obj）与 Properties 生成的材质声明注入 `//@auto` 标记处，文件完全自包含；
- **不做**：`#pragma multi_compile`（变体系统，v0.7 决策）；keyword 系统。

**为什么不需要 `Tags.Queue`（设计决策存档，v0.12）**：Unity 的渲染是「全提交 + 全局排序」模型——所有渲染器（网格、粒子、UI、天空盒）往同一个 draw list 提交 draw call，引擎从 draw call 本身推断不出顺序意图，只能让材质自己声明「排第几」。这是历史架构的补丁，问题：① 顺序信息碎片化在上百个材质里，全局顺序不可见、不可验证；② 写错 Queue 值不报错，画面顺序悄悄烂掉；③ `2450` 这类桶号 + `+1/-1` 微调全靠约定；④ 引擎放弃排序优化权，只能照单执行。本架构的渲染顺序由**引擎 Pass 链集中编排**（第 14 节：GBuffer → Lighting → Transparent），材质含哪些 Pass 自动归类——有 `Pass GBuffer` 即 Deferred 几何阶段、有 `Pass Transparent` 即前向混合阶段，**Pass 名本身携带分类信息，Queue 是冗余声明**。若同一阶段内需细分排序（如多个透明物体谁先画），在 Material 实例上加排序键（renderOrder），不进 shader 语法。

### 21.2 语法规范（骨架示例）

```shader
Shader "PBR_Standard"
{
    Properties
    {
        _BaseColor   ("Base Color", Color) = (1, 1, 1, 1)
        _Metallic    ("Metallic", Range(0, 1)) = 0.0
        _Roughness   ("Roughness", Range(0, 1)) = 0.5
        _AlbedoMap   ("Albedo", 2D) = "white" {}
        _NormalMap   ("Normal", 2D) = "bump" {}
        _UseNormalMap ("Use Normal Map", Float) = 0   // 开关属性：普通 Float + uniform 分支
    }

    // ===== Pass：块名即渲染阶段名 =====
    Pass GBuffer
    {
        Cull Back
        ZWrite On
        ZTest LEqual
        Blend Off
        ColorMask RGBA

        vs {                            // 顶点着色器：入口统一 main()
            #version 450
            //@auto                     // 构建工具在此注入：camera/obj 引擎块 + MaterialBlock + sampler
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 0) out vec2 vUV;

            void main() {
                vUV = aUV;
                gl_Position = camera.viewProj * obj.model * vec4(aPos, 1.0);
            }
        }

        fs {                            // 片元着色器
            #version 450
            //@auto
            layout(location = 0) in vec2 vUV;
            layout(location = 0) out vec4 fragColor;

            void main() {
                vec4 base = texture(_AlbedoMap, vUV) * material._BaseColor;
                fragColor = base;
            }
        }
    }

    Pass ShadowCaster
    {
        Cull Front                      // 阴影防漏光
        ZWrite On
        ZTest LEqual

        vs {
            #version 450
            //@auto
            layout(location = 0) in vec3 aPos;
            void main() {
                // camera 块内容由引擎绑定为光源的 viewProj（第 19 节 lightCamOffset）
                gl_Position = camera.viewProj * obj.model * vec4(aPos, 1.0);
            }
        }
        fs {
            #version 450
            //@auto
            void main() { /* 阴影 Pass 只写深度，片元可空 */ }
        }
    }

    Pass Transparent
    {
        Blend SrcAlpha OneMinusSrcAlpha
        ZWrite Off
        ZTest LEqual

        vs { /* ... */ }
        fs { /* ... */ }
    }
}
```

### 21.3 语法块 ↔ 架构映射

|语法|映射|说明|
|---|---|---|
|`Properties`|set 2 materialLayout（第 15.3 节）|生成 GLSL 声明文本（MaterialBlock + sampler）+ 布局元数据（std140）|
|`Pass GBuffer`|ShaderPass("GBuffer")（第 20.2 节）|`pipelineFor("GBuffer")` 的查找键|
|`Cull / ZWrite / ZTest / Blend / ColorMask`|GraphicsPipelineDesc 固定状态|光栅 / 深度模板 / 混合状态|
|`vs { }` / `fs { }`（内嵌，`//@auto` 注入声明）|离线编译 .vert.spv / .frag.spv（第 16.1/16.2 节）|入口统一 main()，无需 #pragma|

### 21.4 Properties 类型集（v0.1）

|类型|Unity 对应|UBO / 绑定落点|默认值示例|
|---|---|---|---|
|Color|Color|vec4|(1, 1, 1, 1)|
|Vector|Vector|vec4|(0, 0, 0, 0)|
|Float|Float|float|0.0|
|Range(min, max)|Range|float|0.5|
|2D|2D|sampler2D + 贴图槽位|"white" / "bump"|

- **占位贴图约定**：`white` = (255,255,255,255)、`bump` = (128,128,255)、`black` = (0,0,0,1)；
- **无变体**：功能开关属性用普通 `Float` 声明（0/1）+ uniform 分支（`if (_UseNormalMap > 0.5)`），**不是** shader keyword（第 16.3 节）。

### 21.5 构建管线（离线）

```Plain Text
StandardMetal.shader
   ├─ Properties ──→ 生成 GLSL 声明文本（MaterialBlock + sampler，std140 自动排布）
   │              └─→ 生成 materialLayout 元数据（RHI / 编辑器用）
   └─ 每个 Pass：vs/fs 块 + 注入声明（//@auto）──→ glslc ──→ .spv ──→ GraphicsPipeline（PipelineCache，第 16.4 节）
```

- 生成材质声明保证 **GLSL / RHI / CPU 三方布局一致**（消灭 std140 对齐与参数名拼写 bug，第 15.7 节坑点 1）；
- **注入机制（v0.10）**：构建工具把两类声明注入 vs/fs 块的 `//@auto` 标记处（缺省时注入在 `#version` 之后）：
  1. **引擎内置块**（同一份模板）：set 0 CameraBlock、set 1 ObjectBlock——用户代码直接引用 `camera` / `obj`；
  2. **材质声明**（从 Properties 生成）：set 2 MaterialBlock + sampler 槽位；
  **无 `#include`，文件完全自包含**；
- 构建工具抽取注入后的 vs 块 → `.vert.spv`、fs 块 → `.frag.spv` 编译。

### 21.6 约定与坑点

1. Pass 名是引擎查找键，改名必须同步 `pipelineFor()` 调用（第 20 节）；
2. 所有 Pass 共用 Properties 生成的布局：每个 Pass 的 vs/fs 都注入同一份材质声明（`//@auto`），未用贴图绑占位（第 20.3 节）；
3. 无变体：不要写 `#pragma multi_compile`；功能开关一律 uniform 分支；
4. 材质 / shader 加载失败：引擎 Debug 报错并替换为内置默认材质（不依赖 FallBack，v0.11）；
5. 透明 Pass 别忘了 `Blend` + `ZWrite Off`；渲染队列完全由引擎决定（第 14.5 节），shader 不声明 Queue（v0.11）。
6. `//@auto` 每个 vs/fs 块最多一个；缺省时工具默认注入在 `#version` 之后；
7. 用户**不得手写** camera / obj / MaterialBlock 同名声明（与注入冲突，构建期报错）；
8. MaterialBlock 的 std140 填充由生成器处理，用户不要手动补 padding。

### 21.7 完整参考示例（PBR_Standard.shader，v0.12）

> 一个真实可编译的 PBR 材质 shader，覆盖全部语法：多 Pass、vs/fs 内嵌、`//@auto` 注入、占位贴图、uniform 分支、GBuffer 三 RT 输出。

```shader
Shader "PBR_Standard"
{
    Properties
    {
        _BaseColor     ("Base Color", Color) = (1, 1, 1, 1)
        _Metallic      ("Metallic", Range(0, 1)) = 0.0
        _Roughness     ("Roughness", Range(0, 1)) = 0.5
        _AlbedoMap     ("Albedo", 2D) = "white" {}
        _NormalMap     ("Normal", 2D) = "bump" {}
        _MetalRoughMap ("Metallic Roughness", 2D) = "white" {}
        _UseNormalMap  ("Use Normal Map", Float) = 0
    }

    // ===== Pass 1：GBuffer（Deferred 几何阶段） =====
    Pass GBuffer
    {
        Cull Back
        ZWrite On
        ZTest LEqual
        Blend Off
        ColorMask RGBA

        vs {
            #version 450
            //@auto
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 2) in vec3 aNormal;
            layout(location = 0) out vec2 vUV;
            layout(location = 1) out vec3 vWorldPos;
            layout(location = 2) out vec3 vNormal;

            void main() {
                vec4 worldPos = obj.model * vec4(aPos, 1.0);
                vUV       = aUV;
                vWorldPos = worldPos.xyz;
                vNormal   = mat3(obj.model) * aNormal;
                gl_Position = camera.viewProj * worldPos;
            }
        }

        fs {
            #version 450
            //@auto
            layout(location = 0) in vec2 vUV;
            layout(location = 1) in vec3 vWorldPos;
            layout(location = 2) in vec3 vNormal;
            layout(location = 0) out vec4 outAlbedo;    // RGB=反照率   A=1
            layout(location = 1) out vec4 outNormal;    // RGB=法线     A=金属度
            layout(location = 2) out vec4 outWorldPos;  // RGB=世界坐标 A=粗糙度

            void main() {
                vec4 albedo = texture(_AlbedoMap, vUV) * material._BaseColor;

                vec3 normal = normalize(vNormal);
                if (material._UseNormalMap > 0.5) {          // uniform 分支，非变体
                    normal = normalize(texture(_NormalMap, vUV).xyz * 2.0 - 1.0);
                }

                vec2 mr = texture(_MetalRoughMap, vUV).rg;
                float metallic  = material._Metallic  * mr.r;
                float roughness = material._Roughness * mr.g;

                outAlbedo   = vec4(albedo.rgb, 1.0);
                outNormal   = vec4(normal, metallic);
                outWorldPos = vec4(vWorldPos, roughness);
            }
        }
    }

    // ===== Pass 2：ShadowCaster（阴影深度） =====
    Pass ShadowCaster
    {
        Cull Front                      // 阴影防漏光
        ZWrite On
        ZTest LEqual

        vs {
            #version 450
            //@auto
            layout(location = 0) in vec3 aPos;
            void main() {
                // 阴影阶段 camera 块由引擎绑定为光源 viewProj（第 19 节 lightCamOffset）
                gl_Position = camera.viewProj * obj.model * vec4(aPos, 1.0);
            }
        }
        fs {
            #version 450
            //@auto
            void main() { }              // 只写深度，片元可空
        }
    }

    // ===== Pass 3：Transparent（前向混合，可选） =====
    Pass Transparent
    {
        Blend SrcAlpha OneMinusSrcAlpha
        ZWrite Off
        ZTest LEqual

        vs {
            #version 450
            //@auto
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 0) out vec2 vUV;
            void main() {
                vUV = aUV;
                gl_Position = camera.viewProj * obj.model * vec4(aPos, 1.0);
            }
        }
        fs {
            #version 450
            //@auto
            layout(location = 0) in vec2 vUV;
            layout(location = 0) out vec4 fragColor;
            void main() {
                vec4 albedo = texture(_AlbedoMap, vUV) * material._BaseColor;
                fragColor = vec4(albedo.rgb, albedo.a);   // 混合由 Blend 状态完成
            }
        }
    }
}
```

**示例关键点**：
- `//@auto` 注入后可直接引用：`camera.viewProj` / `obj.model`（引擎内置块）、`material._BaseColor`（材质块成员）、`_AlbedoMap` 等 sampler；
- GBuffer 输出 3 个 RT，材质参数塞 alpha 省显存（normal.a=金属度、worldPos.a=粗糙度）；
- 开关属性 `_UseNormalMap` 走 uniform 分支，非变体；
- 阴影 Pass 复用同一套注入声明，`camera` 块由引擎绑定为光源 viewProj；
- 未传贴图自动用占位贴图（white / bump）。

---

> （注：部分内容可能由 AI 生成）

---


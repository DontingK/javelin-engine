---
name: write-shader
description: "Use when writing, modifying, or debugging Javelin engine shaders: .shader files (Unity ShaderLab 简化版), GLSL vs/fs blocks, engine uniform channels (UBO1 camera / push constants / set2 material), #version-after injection semantics, PassState fixed states, or compiling shaders via shaderc. Also use when a demo needs a new builtin shader resource. Main agent only."
---

# 编写 Javelin 着色器

Javelin 引擎着色器编写规范。权威定义见 `doc/RHI_Design_Shader.md` 第 21 节（语法）、`doc/Engine_Material_Design.md` §4（材质桥）、`doc/Engine_Rendering_Design.md` 第 6 节（UBO1 槽内布局）。本技能是快速操作手册，与文档冲突时以文档为准。

## 一、四条 uniform 通道（引擎自动绑定，作者零感知）

着色器作者**不声明布局、不碰描述符**，按名字直接用：

| 通道 | 声明（注入模板生成 / 手写对齐） | 使用 | 值来源 |
|------|------------------------|------|--------|
| 相机（set 0, UBO1 动态） | `layout(set=0, binding=0) uniform CameraBlock { mat4 viewProj; } cam;` | `cam.viewProj` | 引擎每帧写入；绑定时动态偏移选"当前帧当前相机"的段 |
| 每物件（push constants, 80B） | `layout(push_constant) uniform PushBlock { mat4 model; float tpf; } pc;` | `pc.model` / `pc.tpf` | 引擎每个 draw call 前自动推送 |
| 材质数值（set 2, binding 0） | `layout(std140, set=2, binding=0) uniform MaterialBlock { /* 按 Properties 生成 */ } material;` | `material._Tint` 等 | 材质实例属性，std140 打包 |
| 材质贴图（set 2, binding 1..n） | `layout(set=2, binding=1) uniform sampler2D _MainTex;` | 直接 `texture()` 采样 | 按 Properties 声明序，未赋值走占位贴图 |

- set 1 是 ObjectBlock 空占位（per-object 已改走 push constants），**不写任何声明**。
- push 形态 = `model` + `tpf`，**不是 mvp**（早期原型用过，已弃用：多相机要每物件×每相机重算，且着色器拿不到 model 无法做世界空间计算。拍板存档见 Engine_Rendering_Design 第 6 节）。
- 多相机：着色器**无相机索引概念**，同一 SPIR-V 复用所有相机；"哪个相机"由 Pass 录制时的动态偏移 + viewport/scissor 决定。
- 时间块（timeSec/tpf）在 UBO1 槽尾（offset 1024）但当前描述符 range=256B **不可见**；着色器要 tpf 用 `pc.tpf`。

## 二、.shader 文件语法（Unity ShaderLab 简化版）

一个 Shader = 一个 `.shader` 文件：

```shader
Shader "PBR_Standard"
{
    Properties
    {
        _BaseColor  ("Base Color", Color) = (1, 1, 1, 1)
        _Metallic   ("Metallic", Range(0, 1)) = 0.0
        _AlbedoMap  ("Albedo", 2D) = "white" {}
        _UseNormalMap ("Use Normal Map", Float) = 0   // 开关 = 普通 Float + uniform 分支
    }

    Pass Forward                  // 块名即渲染阶段名（pipelineFor 查找键）
    {
        Cull Back                 // 状态命令：Cull / ZWrite / ZTest / Blend / ColorMask
        ZWrite On
        ZTest LEqual
        Blend Off                 // Blend/ZWrite 声明是底稿——场景材质的前向 PSO 被
                                  //   Material.surfaceType 覆盖（方案 B，见下"坑点"）

        vs {                      // 入口统一 main()，无 #pragma
            #version 450          // ← 构建工具在此行之后自动注入 cam/pc 等声明（无标记，位置固定）
            layout(location = 0) in vec3 aPos;
            void main() {
                gl_Position = cam.viewProj * pc.model * vec4(aPos, 1.0);
            }
        }
        fs {
            #version 450          // ← 构建工具在此行之后注入 cam/pc + MaterialBlock + sampler
            layout(location = 0) out vec4 fragColor;
            void main() { /* ... */ }
        }
    }
}
```

取舍红线（不要写这些）：无 `SubShader`、无 `FallBack`、无 `Tags.Queue`、无 `[Toggle]`、无 `#pragma multi_compile`/keyword、无 `#include`（文件完全自包含）。

**Pass 名即阶段归属（严格制，2026-10-17）**：要被前向主通道（Opaque/Transparent 管线 Pass）绘制必须显式 `Pass Forward`——无回退，不声明即不画。其他名（如 `Gizmo`）进 RenderList 自定义桶，需管线有对应消费 Pass（`RenderPass.shaderPassName()` 同名）才画；声明了没人消费 = 静默不画（预期行为）。

## 三、注入的两层含义（重要）

1. **运行时注入**：引擎自动绑 set0（动态偏移）+ 每 draw push `pc`——已落地。
2. **编译时注入**：编辑器导入管线（`com.javelin.editor.importer.shader`：ShaderParser/ShaderInjector/ShaderImporter，2026-09-26 落地）在每个 vs/fs 块的 `#version` 行之后自动拼入 cam/pc/MaterialBlock(实例名 material)/sampler 声明——**无 `//@auto` 标记**（v0.13 拍板，位置固定），`.shader` 作者只写 `void main()` 业务代码。注入器会校验保留字（CameraBlock/PushBlock/MaterialBlock 手写即报错）。

**当前现实**：`.shader` 走导入管线（零声明）；纯 GLSL 路径（test resources 下的 `.vert`/`.frag`）**仍须手写全部声明**，实例名须与注入模板一致（cam/pc/material，`builtin_quad.frag` 的 `mat` 是历史手写形态）。

## 四、GLSL 约定与坑点

- **内置 `.shader` 必须首行 `// @javelin-guid: {32位hex}`**：内置资源无伴生 .meta，GUID 以文件头为唯一真值（删缓存重编译 GUID 不变，材质引用不失效）；无头文件首次导入自动注入确定性 GUID。
- `#version 450` 起头且必填（注入锚点，缺失构建报错）；Y 翻转已入投影矩阵（视口正高度），frontFace=CCW 结论不变。
- 顶点属性 location 与 Mesh 顶点布局一致（Position=0 等，见 Engine_Assets_Design）。
- std140 对齐由 MaterialBlock 排布器保证，作者不手补 padding；不得手写与注入同名的 cam/pc/MaterialBlock 声明（构建期报错）。
- 注入位置固定 `#version` 之后一行，无标记。
- 占位贴图：`white` / `bump` / `black`。
- **透明分桶 = Material.surfaceType（材质显式裁决，2026-10-17 方案 B），不是 PassState 的 Blend 态，也不是 Queue**：材质 surfaceType=TRANSPARENT → 透明桶 + PSO 覆盖 Blend SrcAlpha OneMinusSrcAlpha / ZWrite Off；OPAQUE → Blend Off / ZWrite On。PassState 里的 Blend/ZWrite 声明对场景材质的前向路径恒被覆盖（写 .shader 时不用靠改 Blend 开透明；要透明改材质）。
- **坑**：`bindDescriptorSet` 前必须先 `bindPipeline`（Vulkan 命令序，Vulkan_Pitfalls.md）。

## 五、编译与验证

- 工具链：`javelin-tool` 的 shaderc（`ShaderCompiler.compile(source, stage, name)`）；`.shader` 导入走编辑器 `ShaderImporter.buildShader(source, name)`（解析+注入+编译全链）；test 侧用 `TestShaders.compileResource(path, stage)` 读 classpath 资源编译。
- 着色器源码放 test/应用层 resources——**core 主代码不出现 GLSL**（拍板）。
- 验证方式：写/改后让用户跑对应 Demo（`gradlew :javelin-core:runTest -DmainClass=...`），编译验收归用户（AGENTS 红线 1）。
- API 不确定时用 IDEA MCP 查证（AGENTS 红线 4），不猜、不网页搜索。

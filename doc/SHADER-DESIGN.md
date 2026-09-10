# 着色器与材质系统设计文档

> 本文档描述 `javelin-core` 中着色器源码格式（ShaderLab 风格 DSL）、材质资产与渲染参数绑定的完整设计。
> 设计参考 Unity ShaderLab 与 javelin-engine4 的同名系统，按 engine5 的新 RHI（类型安全 desc、动态渲染、framesInFlight 帧资源）修正。

## 设计目标

- 单文件着色器源码（`.shader`），Properties / Pass / 内嵌 GLSL，进版本库、可 diff
- 纯导入期编译：GLSL → SPIR-V 只发生在编辑器导入管线，运行时只加载 `.asset` 二进制
- `javelin-core` 不依赖 `javelin-tool`（shaderc 只存在于编辑器侧）
- 材质参数（uniform / 纹理）声明式定义，编辑器 Inspector 可直接从属性表生成
- 补齐 engine4 遗留缺口：多纹理槽、MaterialParams UBO 运行时填充、blend 声明生效、#include 接线

## 总体分层

```
源码层   .shader 文件（ShaderLab 风格 DSL，内嵌 GLSL，纯文本，进版本库）
   │     编辑器导入期：ShaderParser → ShaderAST → ShaderPreprocessor → shaderc → SPIR-V
资产层   Shader（.asset 二进制：属性表 + 各 Pass 的 SPIR-V + 渲染状态）
         Material（.asset 二进制：Shader GUID 引用 + 参数覆盖值）
运行时   MeshRenderer(Mesh+Material) → 按 (ShaderPass × VertexLayout) 建 GraphicsPipeline
         → RenderCommand 进 RenderList → RenderingPipeline 调度
```

包布局（`javelin-core`）：

| 包 | 类 | 职责 |
|----|----|----|
| `core.render.shader` | `ShaderParser` | DSL 文本 → AST（手写 tokenizer + 递归下降），行列号报错 |
| `core.render.shader` | `ShaderAST` | 纯字段节点树 + 工具方法（提取纹理槽、查属性/Pass） |
| `core.render.shader` | `ShaderPreprocessor` | GLSL 生成：默认全局 uniform 块、Properties → 材质块、#include 展开 |
| `core.render.material` | `Shader` | Shader 资产：ShaderPass 模板列表 + ShaderProperty 定义 |
| `core.render.material` | `ShaderPass` | Pass 模板：SPIR-V 字节码 + 渲染状态 + 纹理槽 + 路径/层 |
| `core.render.material` | `Material` | 材质资产：Shader GUID 引用 + 参数覆盖 Map |
| `core.render.material` | `Pass` | ShaderPass 的运行时不可变实例（paramBuffer + DescriptorSet，不持 pipeline） |
| `core.render.material` | `ShaderProperty` / `ShaderPropertyType` | 可暴露属性定义（名/显示名/类型/默认值/Range 上下限） |

`render/mesh`、`render/pass` 占位目录分别预留给 Mesh/MeshRenderer 与渲染 Pass 实现，不属于本文档范围。

## 着色器源码格式（DSL）

### 完整示例

```glsl
// @javelin-guid: 8f3a2c1e-9b47-4d21-a5f6-2c8e1d0b7a34
Shader "Default" {
    Properties {
        _Color    ("Tint",       Color)      = (1, 1, 1, 1)
        _Metallic ("Metallic",   Range(0, 1)) = 0.0
        _MainTex  ("Base Map",   Texture2D)   = "white"
        _BumpScale("Bump Scale", Float)       = 1.0
    }
    GlobalUniforms {
        JavelinPerFrame { mat4 u_view; mat4 u_proj; vec3 u_cameraPos; float u_time; }
    }
    Pass Forward {
        renderPath   Forward
        renderLayer  Opaque
        zWrite       On
        zTest        LessEqual
        cullMode     Back
        blend        Off
        vs {
            #version 450
            layout(location = 0) in vec3 a_pos;
            void main() { gl_Position = u_mvp * vec4(a_pos, 1.0); }
        }
        fs {
            #version 450
            layout(location = 0) out vec4 o_color;
            void main() { o_color = texture(_MainTex, vec2(0.5)) * _Color; }
        }
    }
}
```

### 语法规则

```
文件        := 头注释(guid)? 'Shader' 字符串 '{' Properties? GlobalUniforms? Pass+ '}'
Properties  := 'Properties' '{' 属性声明* '}'
属性声明     := 标识符 '(' 显示名 ',' 类型 ('(' 数字 ',' 数字 ')')? ')' '=' 默认值
类型         := Float | Range | Int | Bool | Vector2 | Vector3 | Vector4 | Color | Texture2D
默认值       := 数字 | true | false | 向量字面量 '(' 数字 (',' 数字)* ')' | 字符串
GlobalUniforms := 'GlobalUniforms' '{' uniform 块* '}'        // 缺省用引擎默认块
uniform 块   := 'JavelinPerFrame' | 'JavelinPerObject' '{' 字段声明* '}'
Pass        := 'Pass' 标识符 '{' 状态声明* (vs|fs|gs 代码块)+ '}'
状态声明     := renderPath Forward|Deferred|Shadow
              | renderLayer Opaque|Transparent|Overlay
              | zWrite On|Off
              | zTest Off|Never|Less|LessEqual|Equal|Greater|GreaterEqual|Always
              | cullMode Back|Front|None
              | blend Off | <srcFactor> <dstFactor>
代码块       := ('vs'|'fs'|'gs') '{' ...大括号配平的 GLSL... '}'
```

约定与约束：

- GUID 从文件头注释 `// @javelin-guid:` 提取，缺失时由导入器生成并写回
- Pass 名在文件内唯一；属性名在文件内唯一；每个 Pass 至少一个阶段
- 向量字面量 `(1.0, 0.0, ...)` 是独立 token；字符串默认值仅用于 Texture2D（内建纹理名或 GUID）
- `vs`/`fs` 代码块用大括号配平提取，GLSL 内部允许任意大括号
- `Range(min, max)` 的 min/max 必须存入 `ShaderProperty`（engine4 解析后丢弃，编辑器滑动条需要）

### 相对 engine4 的格式改动

| 改动 | 原因 |
|------|------|
| `blend` 声明真正生效 | engine4 被 renderLayer 二选一硬编码覆盖；规则改为 DSL 显式声明优先，未声明时按 renderLayer 取默认（Opaque→Off，其余→alphaBlend） |
| `Range` 上下限入库 | 编辑器 Inspector 滑动条需要 |
| `#include` 解析器接线 | engine4 传入 null 未实现（TODO 遗留） |
| 不引入 `multi_compile` | 排除着色器变体系统复杂度，后续版本再议（engine4 解析了未实现） |

## 预处理与 GLSL 生成

`ShaderPreprocessor` 对 vs/fs 源码按序处理：

1. `#version` 保留（必须在首行，否则报错）
2. 注入全局 uniform：
   - 默认 `JavelinPerFrame`：`layout(std140, set=0, binding=0) uniform JavelinPerFrame { ... }`（默认含 u_view / u_proj / u_cameraPos / u_time / u_resolution / u_deltaTime / u_frame）
   - Push Constants：`layout(push_constant) uniform JavelinPushConstants { mat4 u_mvp; }`（**仅 MVP**，MeshRenderer 在 CPU 侧计算 `proj * view * model` 推入，64B）
   - 默认 `JavelinPerObject`：`layout(std140, set=0, binding=1) uniform JavelinPerObject { mat4 u_model; mat4 u_normalMatrix; }`，经**动态偏移**寻址（所有对象共享一个帧级缓冲，见"Uniform 绑定模型"）
   - DSL 中声明了 GlobalUniforms 则以 DSL 为准（覆盖默认块；Push Constants 块不受 GlobalUniforms 覆盖）
3. 从 Properties 生成材质声明（Texture2D 与非纹理分开）：
   - 每个 Texture2D → `layout(set=1, binding=i) uniform sampler2D <名>`，binding 从 0 开始按声明顺序
   - 其余属性 → `layout(std140, set=1, binding=纹理数) uniform MaterialParams { ... }`，GLSL 类型映射：Color/Vector4→vec4、Vector3→vec3、Vector2→vec2、Float/Range→float、Int→int、Bool→bool（std140 下 bool 按 int 布局）
4. 展开 `#include`（`IncludeResolver` 回调，导入器提供；内建 include 库本期不做）

## Uniform 绑定模型

| set / 机制 | 内容 | 提供者 |
|---|---|---|
| `set=0 binding=0` | PerFrame UBO（std140） | 渲染器每帧填充；按 `Renderer` 的 framesInFlight **逐帧轮转缓冲**（engine5 新增，engine4 单帧缓冲） |
| `set=0 binding=1` | PerObject UBO（std140：u_model / u_normalMatrix） | **动态偏移**寻址：所有对象共享一个帧级 `CPU_TO_GPU` 大缓冲，MeshRenderer 逐对象写入后经 `RenderCommand.dynamicOffsets` 传入 |
| Push Constants | `mat4 u_mvp`（64B，仅此一项） | MeshRenderer 在 CPU 侧计算 `proj * view * model`，提交命令时推入 |
| `set=1 binding=0..n-1` | sampler2D，按 Properties 中 Texture2D 声明顺序 | Material 实例持有 DescriptorSet，setProperty(纹理) 时 writeTexture |
| `set=1 binding=n` | MaterialParams UBO（std140 打包全部非纹理属性） | Material 实例持有 mapped（CPU_TO_GPU）Buffer，setProperty 时写回并 flush |

引擎层每帧职责：PerFrame UBO 一份（所有材质共享）；PerObject 缓冲由**帧级线性分配器**管理（每帧重置写指针、容量不足倍增、framesInFlight 轮转防写冲突）；push constants 每命令一份（恒定 64B）；set=1 每材质实例一份、内容变化才写回。

PerObject 帧级缓冲分配器（新增基础设施，渲染器持有）：

```
beginFrame：写指针归零（当前帧对应的轮转槽）
alloc(64B 对齐)：偏移 = 写指针；写指针 += size；容量不足 → 倍增重建（GPUResource 延迟释放旧缓冲）
MeshRenderer.collect：map 写入 model/normalMatrix → RenderCommand.dynamicOffsets = {偏移}
```

## 资产层设计

### Shader（`extends AbsAsset`，`@Reflective`）

- 字段：`List<ShaderPass> passes`、`List<ShaderProperty> properties`
- 方法：`addPass/getPasses`、`addProperty/getProperties`、`getProperty(String)` 按名查找
- 由编辑器导入管线生成；**运行时只加载二进制，不接触原始 GLSL**

### ShaderPass（模板，Serializable）

- 字段：`name`、`byte[] vertexBytecode`、`byte[] fragmentBytecode`、`boolean zWrite`、`CompareFunction zTest`、`CullMode cullMode`、`RenderPath renderPath`、`RenderLayer renderLayer`、`BlendDesc blend`（DSL 显式值或 renderLayer 默认）、`List<String> textureSlots`（按声明顺序）
- 完整 `write/read` 序列化

### Material（`extends AbsAsset`，Serializable）

- 字段：`Shader shader`（GUID 引用，走 SERIALIZE 的 ASSET 机制）、`Map<String, Object> propertyOverrides`
- 覆盖值运行时形态：float[]（向量/颜色）、Number、Boolean、String、Texture 资产（GUID 引用）
- `setShader` / `rebuildPasses`：按 Shader 模板重建运行时 Pass 列表；**反序列化后自动 rebuild**（去掉 engine4 的手动步骤）
- 属性写入直通 MaterialParams Buffer；纹理写入 DescriptorSet

### Pass（运行时不可变实例，非 Serializable）

- 字段：`name`、`Buffer paramBuffer`、`DescriptorSet descriptorSet`、`Set<String> textureSlots`、`RenderPath`、`RenderLayer`
- **不持有 GraphicsPipeline**——pipeline 是 ShaderPass × VertexLayout 的组合产物

## 管线缓存

- 缓存 key：`(ShaderPass, VertexLayout)`，引擎级全局缓存
- engine4 是每个 MeshRenderer 各建各的（同网格同材质重复创建）；engine5 改为全局缓存，命中即复用
- GPU 资源生命周期走现有 `GPUResource` Cleaner 延迟释放机制

## 渲染时序

```
GameScene.collectRenderables → MeshRenderer.collect
  ├─ 管线缓存命中? 否 → GraphicsPipelineDesc(ShaderDesc×2, VertexLayout, 深度/混合/剔除) → 全局缓存
  ├─ PerObject 缓冲 alloc → 写入 u_model / u_normalMatrix（得动态偏移）
  ├─ CPU 侧计算 u_mvp = proj * view * model
  └─ RenderCommand(descriptorSets, dynamicOffsets, textures) → RenderList
RenderingPipeline.render
  └─ 每 Pass：更新 PerFrame UBO（帧轮转缓冲）→ 遍历 RenderList：pushConstants(u_mvp) → bindDescriptorSets(含动态偏移) → draw
```

## 编辑器导入管线

`javelin-editor` 的 `ShaderImporter`（扩展名 `.shader`，Gradle 的 builtin 资产索引任务已预留该后缀）：

```
读文件 → 提取/生成 GUID（写回头注释）
  → ShaderParser.parse → ShaderAST
  → 构建 Shader 资产 + ShaderProperty（Range 上下限入库）
  → 每 Pass：ShaderPreprocessor → ShaderCompiler（shaderc）→ SPIR-V byte[]
  → ShaderImportSettings 真正生效（优化级别 / targetVulkanVersion / preservePreprocessedSource）
  → BinaryExporter 序列化为 .asset
```

`ShaderCompiler` 新增按选项编译的重载；engine4 中 `ShaderImportSettings` 字段存在但未传入编译器的问题一并修正。

## 序列化

- SPIR-V 字节码：`byte[]` 直接进 `.asset`
- Shader/Material 均为 `AbsAsset`：guid + lastModified 由基类统一处理
- Material → Shader：字段类型 Asset 子类，只写 GUID（SERIALIZE 既有机制）
- 纹理属性值：Texture 资产 GUID 引用

## 单元测试

- `ShaderParserTest`：DSL 语法解析（属性类型/默认值/向量字面量/状态声明/代码块配平/GUID 提取/报错行列号）
- `ShaderPreprocessorTest`：默认块注入、MaterialParams/sampler 生成、#include 展开
- 测试资源：`src/test/resources/shader/*.shader` 示例文件
- 导入端到端测试在 `javelin-editor` 侧

## MVP 边界

**做**：DSL 解析与预处理、多纹理槽、MaterialParams UBO、blend 生效、全局管线缓存、ShaderImporter 移植、内建示例 shader（仅 Forward 单 Pass）

**不做**：ShadowCaster / deferred 路径实质实现、shader 变体（multi_compile）、compute、材质继承、内建 include 库、Inspector UI（属性表数据已具备，UI 属编辑器前端任务）

## 决策记录（已全部确认）

1. ~~**builtin shader 编译方式**~~ **已定（2026-09-09）**：编辑器导入。`javelin-core` 运行时只加载导入管线产出的 `.asset` 二进制，不接触原始 GLSL、不依赖 `javelin-tool`
2. ~~**PerObject 传输方式**~~ **已定（2026-09-09）**：Push Constants 仅传 `mat4 u_mvp`（64B）；u_model / u_normalMatrix 等其他逐对象数据走 `set=0 binding=1` 动态偏移 UBO（帧级线性分配器 + framesInFlight 轮转）
3. ~~**`renderPath` 消费范围**~~ **已定（2026-09-09）**：本期渲染端**只实质实现 Forward 单 Pass**；格式保留 Forward / Deferred / Shadow 枚举（导入与资产层不限制），ShadowCaster 与 deferred 留待后续版本

## 附：engine4 缺口对照

| engine4 缺口 | 本设计处理 |
|---|---|
| #include 解析器传 null | IncludeResolver 由导入器提供，正式接线 |
| 多纹理槽只绑第一个 | set=1 全槽绑定 |
| MaterialParams UBO 恒 null | Material 实例持有 mapped Buffer，setProperty 写回 |
| blend 声明被忽略 | DSL 显式声明优先，缺省按 renderLayer 默认 |
| multi_compile 解析未实现 | 格式中不引入，排除变体系统 |
| ShaderImportSettings 未传入编译器 | 编译重载携带选项 |
| Material 反序列化后手动 rebuildPasses | 反序列化自动 rebuild |

---

*本文档设计已确认定稿（仅 Forward 单 Pass 进入实现），最后更新：2026-09-09*

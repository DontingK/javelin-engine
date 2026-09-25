# 引擎材质设计（Engine Material Design）

> 版本：v0.1（2026-09-25）
> 说明：引擎侧材质/着色器数据模型与加载链路设计。着色器文件语法（.shader）、多 Pass 与材质实例模型的概念定义在 RHI 分卷四（20/21 节），本文只管引擎侧落地：包结构、编译产物形态、加载链路、渲染侧桥接。
> 拍板（2026-09-25）：**Shader = .shader 源码文件编译后的结构**；**离线二进制资产**（着色器源码不进 core）；**RHI 资源渲染侧懒创建**（Shader/Material 纯数据、device 无关）。

## 1. 定位与总则

- 对齐 Unity 心智（RHI 20 节）：**Shader 是模板**（多 Pass + 材质参数接口），**Material 是实例**（参数值 + 贴图槽）；
- core 的 material 包**只放纯数据**：不含 .shader 解析器、不含 GLSL、不含 shaderc、不碰 RHI Device；
- 所有 device-bound 资源（set 2 布局、material UBO、描述符集、PSO）由渲染侧（RenderingSystem 体系）懒创建与缓存。

## 2. 包结构（com.javelin.core.render.material）

| 类 | 职责 | 对应 RHI 文档 |
|---|---|---|
| `Shader`（extends AbsAsset） | 模板：shaderName + `List<MaterialProperty>` + `LinkedHashMap<String, ShaderPass>`；**构建用 `Shader.builder(name)`（预编译/加载侧），build 后只读** | 20.2 节 Shader |
| `ShaderPass` | 一个渲染阶段：name + vsSpirv/fsSpirv + `PassState`；块名即查找键 | 20.2 节 ShaderPass / 21 节 Pass 块 |
| `PassState` | 固定状态 POJO：Cull/ZWrite/ZTest/Blend/ColorMask（RHI 枚举，device 无关）；默认 Back/On/LEqual/Off/RGBA | 21.3 节状态命令 |
| `MaterialProperty` | Properties 一条声明：name/displayName/type/默认值/区间/占位贴图名；静态工厂 color/vector/floatProp/range/texture2D | 21.4 节类型集 |
| `MaterialPropertyType` | COLOR/VECTOR/FLOAT/RANGE/TEXTURE_2D + `glslType()`（vec4/float/sampler2D） | 21.4 节 |
| `Material`（extends AbsAsset） | 实例：shader 引用 + 数值参数 + 贴图槽（存引擎 `Texture` 资产）；setFloat/setColor/setTexture 经 findProperty 校验名称与类型 | 20.2 节 Material |
| `Texture2D`（render.texture 包，extends AbsAsset） | 纹理资产，**对齐 Unity Texture2D**：CPU 像素数据持有者（width/height/format/mipLevels + 紧密打包 ByteBuffer + filterMode/wrapMode）+ **GPU 句柄字段**（渲染侧首绑上传后回填，同 Mesh 缓冲模型：资产 GC 不可达 → 句柄走 RHI Cleaner）；内置占位常量 WHITE / BLACK / NORMAL（device 无关，Unity whiteTexture/blackTexture/normalTexture 语义）；`TextureLoader` 同包 | 21.4 占位贴图约定 |

## 3. 编译与加载链路（离线）

```
xxx.shader（Unity ShaderLab 简化版，RHI 21 节语法）
   └─ javelin-tool 构建工具（待写）：
        解析 Properties / Pass / 状态命令
        → //@auto 注入（camera/obj 引擎块 + MaterialBlock + sampler，RHI 21.5 节）
        → shaderc 编译 vs/fs 块 → SPIR-V
        → 打包二进制资产（结构 + SPIR-V，序列化格式随加载器一并定）
   └─ core 运行时：加载二进制 → 装配 Shader（addProperty / addPass）
```

- 构建工具保证 GLSL / RHI / CPU 三方布局一致（RHI 15.7 节坑点 1）；
- 加载失败：报错并替换内置默认材质（RHI 21.6-4，不依赖 FallBack）；
- **P2**：.shader 解析器、二进制资产格式、AssetManager 接入、std140 排布生成器。

## 4. 渲染侧桥接（已落地 2026-09-25，场景渲染接入一并验收）

材质进渲染管线的三个 device-bound 环节，全部懒创建 + 缓存：

1. **set 2 布局**：按 Shader（Properties 元数据）生成 `DescriptorSetLayout`，RenderingSystem 按 shader 缓存——所有 Pass 共用（RHI 20.3 硬约束）。**编号与 binding 约定（落地补充）**：set0 = CameraBlock（UBO1 动态）、set1 = ObjectBlock **空占位**（per-object 走 push constants，空布局在 PSO 布局列表中占位，Vulkan 允许零 binding 布局）、set2 = 材质：binding0 = MaterialBlock UBO（全部数值属性，std140），binding1..n = 贴图槽按声明序；
2. **material UBO + 描述符集**：Material 首次渲染时按属性表写 UBO（std140 排布，排布器 = `MaterialBlock.pack`，CPU/GLSL 同源规则：声明序、float 4B 对齐、vec4 16B 对齐、空块 16B 占位）+ 建 set；**数值参数每次绑定时重打包写入**（运行期 setFloat/setColor 即时生效）；贴图槽经 `TextureUpload.upload(device, texture2D)`（render.texture 包，幂等、返回 GPU 句柄）首绑懒上传——句柄回填资产（`setGpu`，package-private），同 Mesh 缓冲生命周期（GC → Cleaner）；filter/wrap 参数建 Sampler 时消费（SamplerDesc 作缓存键）；未赋值贴图槽按占位约定解析（white → Texture2D.WHITE、bump/normal → NORMAL、black → BLACK，RHI 21.4 节）；**贴图槽仅首绑解析——运行期换贴图 = 换材质实例（v0.1 语义）**；
3. **PSO 按 Pass 取用**：`RenderingSystem.pipelineFor(Material, VertexLayout)`——取 Shader 的前向 Pass（`Shader.forwardPass()`：名 "Forward" → 第一个非混合 Pass → 第一个 Pass，Engine_RenderPipeline 4.4 Pass 名分类的 v0.1 前向规则），PassState → GraphicsPipelineDesc 固定状态，缓存键 = shader identity + layout（当前 RenderingSystem 的 HashMap 缓存；RHI 16.4 节 PipelineCache 落地后收编）；
4. Pass 名分类即渲染阶段归属（RHI 21.1：Queue 是冗余声明）：有 `Pass GBuffer` 进 Deferred 几何、有 `Pass Transparent` 进前向混合；材质用不到的 Pass 不实例化 PSO（RHI 20.7-3）。v0.1 前向形态：透明/不透明分桶 = 前向 Pass 的 Blend 态（RenderObject 构造时定型，RHI 21.1 引擎决定队列）。

## 5. 与现有代码的关系

- ~~`RenderingSystem` 当前的"内置无光照着色程序 + 单贴图 set1"是阶段③直注册模式的临时形态~~——**已收编（2026-09-25）**：内置程序路径整体删除（`installBuiltinShaders`/`textureSet(Texture)`/旧 `pipelineFor(layout, blend)` 移除），MultiCameraDemo 与 SceneRenderDemo 均走材质路径；内置 .shader（编译进二进制）仍待 javelin-tool 落地；
- 渲染资源包归属（用户拍板 2026-09-25）：Mesh/VertexAttribute + 桥接 MeshUpload/MeshDraw 在 **render.mesh**，Texture2D/TextureLoader + 桥接 TextureUpload 在 **render.texture**，材质五件在 **render.material**；
- **"用户侧零 GPU 暴露"原则**（用户拍板 2026-09-25）：资产公开 API 不出现 RHI 句柄——Texture2D 的 getGpu/setGpu、Mesh 的 vertexBuffer/indexBuffer/indexType/indexCount 全部 package-private，GPU 代码只存在于同包桥接类（**上传逻辑全在桥接**：MeshUpload/TextureUpload 持有 device 逻辑，Mesh/Texture2D 是纯 CPU 数据容器）；`Mesh.layout()`（VertexLayout 描述值，PSO 键）例外公开，类比 Texture2D 的 format 元数据；
- **冻结语义（拍板 2026-09-25）**：上传后资产不可变——Mesh 修改抛异常（既有），Texture2D 补齐守卫（setPixels/setFilterMode/setWrapMode 上传后抛 IllegalStateException，堵"改了静默失效"洞）；动态顶点/像素更新（骨骼、粒子、物理形变）将来走专门的动态缓冲路径，不经重传；
- MeshRenderer 的 `mesh`/`material` 字段已补（场景渲染接入，2026-09-25）：null = 不渲染（missing 语义），SceneRenderBridge pull 翻译；
- AbsAsset 提供 guid/lastModified，.mat/.jshader 的 Serializable 协议随格式定义补 write/read。

## 6. 不做（P2）

- **动态 Mesh / 动态纹理**（需求已记录，后续专项支持）：骨骼蒙皮、粒子、物理形变、地形编辑等**每帧变化**的几何/像素数据，静态资产"上传即冻结"语义扛不住 destroy-and-recreate 的频率。届时走专门设计：hostVisible 常驻缓冲 + 每帧映射写入（或 staging 环形），作为独立类型与静态资产并存（对齐 RHI 27 节线程模型，逻辑线程写、渲染线程消费）；动态纹理同理（rhi.Texture 已有 `update(TextureRegion, ByteBuffer)` 运行期更新口）；
- .shader 文本解析器与构建工具本体（本文只定产物结构）；
- .mat 材质资产格式与 AssetManager 加载；
- 材质变体（RHI 20.5 决策：不引入）；
- 同阶段细分排序键（renderOrder，RHI 21.1 决策存档）。

# 引擎材质设计（Engine Material Design）

> 版本：v0.2（2026-10-19）
> 说明：引擎侧材质/着色器数据模型与加载链路设计。着色器文件语法（.shader）、多 Pass 与材质实例模型的概念定义在 RHI 分卷四（20/21 节），本文只管引擎侧落地：包结构、编译产物形态、加载链路、渲染侧桥接。
> 拍板（2026-09-25）：**Shader = .shader 源码文件编译后的结构**；**离线二进制资产**（着色器源码不进 core）；**RHI 资源渲染侧懒创建**（Shader/Material 纯数据、device 无关）。
> v0.2（2026-10-19）：**.mat 资产格式已落地并验收（2026-10-23 用户实测）**——.mat = Material 的文本序列化（与 .scene 源同款协议），导入器/创建命令/文件类型见 §3.2；v0.1 沿革见各节注记。

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
| `SurfaceType` | 材质面型枚举 OPAQUE/TRANSPARENT——透明性裁决源（2026-10-17 拍板方案 B，§4.1） | URP Surface Type 口径 |
| `Material`（extends AbsAsset） | 实例：shader 引用 + 数值参数 + 贴图槽（存引擎 `Texture` 资产）；setFloat/setColor/setTexture 经 findProperty 校验名称与类型 | 20.2 节 Material |
| `Texture2D`（render.texture 包，extends AbsAsset） | 纹理资产，**对齐 Unity Texture2D**：CPU 像素数据持有者（width/height/format/mipLevels + 紧密打包 ByteBuffer + filterMode/wrapMode）+ **GPU 句柄字段**（渲染侧首绑上传后回填，同 Mesh 缓冲模型：资产 GC 不可达 → 句柄走 RHI Cleaner）；内置占位常量 WHITE / BLACK / NORMAL（device 无关，Unity whiteTexture/blackTexture/normalTexture 语义）；`TextureLoader` 同包 | 21.4 占位贴图约定 |

## 3. 编译与加载链路（离线）

```
xxx.shader（Unity ShaderLab 简化版，RHI 21 节语法）
   └─ javelin-tool 构建工具（待写）：
        解析 Properties / Pass / 状态命令
        → `#version` 后注入声明（camera/obj 引擎块 + MaterialBlock + sampler，RHI 21.5 节，v0.13 起无 @auto 标记）
        → shaderc 编译 vs/fs 块 → SPIR-V
        → 打包二进制资产（结构 + SPIR-V，见下方产物格式）
   └─ core 运行时：加载二进制 → 装配 Shader（addProperty / addPass）
```

- 构建工具保证 GLSL / RHI / CPU 三方布局一致（RHI 15.7 节坑点 1）；
- 加载失败：报错并替换内置默认材质（RHI 21.6-4，不依赖 FallBack）；
- **解析→注入→编译→打包已落地（2026-09-26）**：编辑器 `importer/shader`（ShaderParser / ShaderInjector / ShaderImporter），归属 editor 模块、不经 javelin-tool（用户拍板：导入管线整体在 editor，tool 只留 shaderc）；
- **P2**：AssetManager 加载侧接入与内置默认材质替换、std140 排布生成器独立化。

### 3.1 Shader 产物格式（已定，2026-09-26）

`Library/{guid}.asset` 产物 = core Capsule 二进制协议（BinaryExporter/BinaryImporter）：

- `Shader.write/read`：super（guid/lastModified）+ `shaderName` + `properties`（ArrayList，声明序）+ `passes`（ArrayList，声明序）；
- `ShaderPass / MaterialProperty / PassState` 实现 `Serializable` + `@Reflective`（补 ReflectiveFactory 注册），SPIR-V 为 byte[]、PassState 枚举走 name() 字符串、`colorMask` 存 int 位掩码（枚举集不直接序列化——list 元素类名要 @Reflective 注册，绕开）。
- **Material.write/read 已落地（2026-10-04，.scene 内嵌数据面；2026-10-17 增 `surfaceType` 枚举字段，readEnum null 回退 OPAQUE 兼容旧场景）**：super（guid/lastModified）+ `shader`（Asset 引用走框架 guid 分流：guid 非空经 AssetManager 解回、空走图内嵌）+ `values`/`textures` 条目列表直通（`MaterialValueEntry`/`MaterialTextureEntry`——Capsule 无 Map 通道，HashMap 存储重构为 name 唯一条目列表，存储形态 = 序列化形态同 Mesh 拍板）。内嵌材质（guid 空）经 .scene 文本通道全数据面往返；**.mat 资产格式已落地并验收（2026-10-23 用户实测；2026-10-19 落地，§3.2）**。

### 3.2 .mat 资产格式（已落地并验收：2026-10-19 落地、2026-10-23 用户验收）

**拍板：.mat 就是 Material 的序列化**——源文件即 Material 经文本序列化协议落盘（`javelin-text-serialization`，与 .scene 源同款；Material.write/read 复用 2026-10-04 闭环，零改 core）。

- **guid 口径 = 伴生 .meta**（同 .scene：首行是协议头不能走文件头自包含；MetaManager/管线两处既有 ".mat"→"Material" 类型标签直接生效，mtime 比对口径）；
- **导入器** `editor/importer/material/MaterialImporter`（SceneImporter 同款）：TextImporter 解析即验证（`shader=@guid:` 经 task 携带 assetManager 解回）→ **shader 引用缺失显式拒绝**（残档=文件损坏口径，不落残产物，与 RenderObject null shader 报错同语义）→ `setGuid(meta.guid)` 产物自描述 → BinaryExporter 落 `Library/{guid}.asset`。消费侧 `loadGuid(guid, Material.class)` 直接可用；.scene 里 `material=@guid:{guid}` 引用经 TextInputCapsule 声明类型回解（通道 2026-10-04 已备）；
- **创建入口** `project.create.material`（ctx.project 右键「创建」域，创建族首项实做）：选中目录落 `material.mat`（重名 `material 1.mat` 编号去重），初始内容 = 内置 default.shader 的 `new Material(shader)` 文本序列化；**源文本 guid 与伴生 .meta 同源**（.scene 保存同口径，2026-10-19 用户纠偏：先 getOrCreateMeta 建 .meta 拿 guid、setGuid 后一次性落盘——源文本/伴生 .meta/Library 产物/index 四处一致；导入器仍以 meta 为真值源覆盖自述，防手改脱节）；落盘即 `submitImport`（产 Library 产物/更新 index，异步）；发 `project.changed` 事件驱动项目面板重拉树快照（刷新链随首个改盘命令接线，Editor_Design §10 遗留销账）；
- **文件类型** `.mat` 注册 IconFileType（材质球图标，canOpen=false——材质编辑面板后置，双击暂无动作）；
- **已知边界**：texture 条目引用 Texture2D 资产——纹理导入管线未落，槽可空（null=占位贴图），guid 解析通道框架已备；shader 引用未导入（首启多 worker 竞态）时导入失败、下次启动经 !isImportedInLibrary 重试（Scene 同语义）；
- **验收路径（用户实机待跑）**：①右键创建 → 文件+伴生 .meta+Library/{guid}.asset+index 收录齐全；②重启不重复导入；③手改 .scene MeshRenderer `material=@guid:{guid}` → 打开场景渲染差异可见；④project.reimport 强制重导生效。

**坑点存档（AGENTS 红线 10 出处，2026-09-26 调试得出）**：

1. **write 禁止包装写入**：`write()` 里集合/数组必须直传字段本体（`output.write("passes", passes)`）。曾在 write 里 `new ArrayList<>(properties)` 包一层——`Collections.unmodifiableList` 视图不是 `ArrayList`（Capsule 只有 `write(key, ArrayList)` 重载）被迫拷贝，拷贝切断对象图引用同一性（BinaryImporter 按 identity 重建共享引用），包装一律禁止；**集合字段类型直接用 `ArrayList`**，write/read 双向直通零转换；
2. **read 禁止派生嵌套字段**：BinaryImporter 按对象 id 升序统一 populate，根对象 id=0 **先于**嵌套对象填充——曾在 `Shader.read()` 里 `pass.getName()` 建名→Pass 映射，取到的全是 null（单 Pass 查不到、双 Pass 塌成一个键）。修法：唯一存储 = 与序列化同构的有序 `ArrayList<ShaderPass>`，按名查找用线性扫描（Pass 个位数），`read()` 只回写字段引用；
3. **枚举集序列化**：`writeEnumList/readEnumList` 的元素类名要过 `ReflectiveFactory.getRegisteredClass`，RHI 枚举（如 ColorComponentFlags）没注册会抛"未注册的类"——枚举集改存 int 位掩码，单枚举字段走 `write(key, Enum)`（name 字符串，无需注册）。

## 4. 渲染侧桥接（已落地 2026-09-25，场景渲染接入一并验收）

材质进渲染管线的三个 device-bound 环节，全部懒创建 + 缓存：

1. **set 2 布局**：按 Shader（Properties 元数据）生成 `DescriptorSetLayout`，RenderingSystem 按 shader 缓存——所有 Pass 共用（RHI 20.3 硬约束）。**编号与 binding 约定（落地补充；2026-10-23 修订）**：set0 = CameraBlock（UBO1 动态）、set1 = 空布局**预留光照数据**（拍板：原 ObjectBlock 空占位语义作废，per-object 恒走 push constants；空布局在 PSO 布局列表中占位，Vulkan 允许零 binding 布局，三期灯光块在此建光照布局）、set2 = 材质：binding0 = MaterialBlock UBO（全部数值属性，std140），binding1..n = 贴图槽按声明序；
2. **material UBO + 描述符集**：Material 首次渲染时按属性表写 UBO（std140 排布，排布器 = `MaterialBlock.pack`，CPU/GLSL 同源规则：声明序、float 4B 对齐、vec4 16B 对齐、空块 16B 占位）+ 建 set；**数值参数每次绑定时重打包写入**（运行期 setFloat/setColor 即时生效）；贴图槽经 `TextureUpload.upload(device, texture2D)`（render.texture 包，幂等、返回 GPU 句柄）首绑懒上传——句柄回填资产（`setGpu`，package-private），同 Mesh 缓冲生命周期（GC → Cleaner）；filter/wrap 参数建 Sampler 时消费（SamplerDesc 作缓存键）；未赋值贴图槽按占位约定解析（white → Texture2D.WHITE、bump/normal → NORMAL、black → BLACK，RHI 21.4 节）；**贴图槽仅首绑解析——运行期换贴图 = 换材质实例（v0.1 语义）**；
3. **PSO 按 Pass 取用（严格制，2026-10-17）**：`pipelineFor(Material, layout, topology, samples, passName)`——管线 Pass 经 `RenderPass.shaderPassName()` 声明自己消费的 shader Pass 名（前向主通道 "Forward"，GizmoPass "Gizmo"），`Shader.pass(name)` 直查、**无回退**（forwardPass() 已废除：不声明即不画），PassState → GraphicsPipelineDesc 固定状态（其中 Blend/depthWrite 按材质 surfaceType 覆盖，§4.1），缓存键 = shader identity + layout + topology + samples + surfaceType + passName（当前 RenderingSystem 的 HashMap 缓存；RHI 16.4 节 PipelineCache 落地后收编）；
4. **Pass 名即阶段归属**（RHI 21.1：Queue 是冗余声明）：收集侧按 shader 声明的 Pass 名分发进 RenderList（"Forward" → 前向主通道按 surfaceType 分桶；其他名如 "Gizmo" → 自定义桶，管线有则消费、无则忽略——Unity LightMode 不匹配同口径）；材质用不到的 Pass 不实例化 PSO（RHI 20.7-3）。有 `Pass GBuffer` 的 shader 将来进 Deferred 几何，无 Forward 声明不进前向主通道（gizmo 即此——单画，不再双画）。

### 4.1 面型裁决：Material.surfaceType（拍板 2026-10-17，方案 B）

**旧形态（已废）**：透明性从 Shader 前向 Pass 的混合态推导（`RenderObject` 构造时读 `forwardPass().getState().isBlendEnabled()` 烘死 final）——一个 Shader 只能服务一种队列，材质没有发言权。

**新形态（URP Surface Type 口径）**：透明性由 `Material.surfaceType`（OPAQUE 默认 / TRANSPARENT）显式裁决：

- **分桶**：`RenderObject.isTransparent()` 活读材质（运行期 `setSurfaceType` 翻转即时换桶；旧数据 .scene 无此字段 → readEnum 回退 OPAQUE）；
- **PSO 覆盖**：`pipelineFor` 组装前对 `PassState.copy()` 覆盖——TRANSPARENT → Blend SrcAlpha OneMinusSrcAlpha / ZWrite Off；OPAQUE → Blend Off / ZWrite On；Cull/ZTest/ColorMask 保留 Shader 声明。覆盖不回写 Shader 资产，面型进 PSO 缓存键；
- **同一 Shader 通吃两种面型**（材质实例各说各话），demo 均已改单 Shader 双材质；
- surfaceType 不随 `setShader` 重置（随材质资产走，区别于 values/textures 随模板重置）；
- **.shader 的 Blend/ZWrite 声明降级为非前向路径的底稿**（场景材质恒被面型覆盖）；URP 的 Blending Mode（Additive/Premultiply 等）后置扩展，届时升为材质字段。

### 4.2 Pass 分桶严格制：RenderList 按 Pass 名分发（拍板 2026-10-17，同日第二决策）

**旧形态（已废）**：所有物体无条件进 opaque/transparent 两桶，PSO 取用走 `Shader.forwardPass()` 回退规则（名 "Forward" → 第一个非混合 Pass → 第一个 Pass）；GizmoPass 再从 opaque 里按材质着色器**身份过滤**捞 gizmo，靠"Opaque 先画 + 末尾重绘置顶"双画——块名即查找键的原设计（RHI 20.2）没兑现。

**新形态**：

- **收集分发**（`RenderingSystem.collectList`）：遍历 shader 声明的全部 Pass——`Pass Forward` → 前向主通道（surfaceType 分 opaque/transparent）；**其他声明名 → `RenderList.passes[name]` 自定义桶**；无 Forward 声明不进主通道（不声明即不画，Unity LightMode 不匹配同口径）；
- **消费**：管线 Pass 经 `RenderPass.shaderPassName()`（新接口方法，默认 "Forward"）声明自己消费的 Pass 名——`GizmoPass` 取 `passObjects("Gizmo")`，**身份过滤与双画一并废除**（gizmo.shader 无 Forward → 主通道不画它，GizmoPass 是唯一绘制）；
- **PSO 取用**同键：`pipelineFor(..., passName)`，`Shader.pass(name)` 直查无回退，`forwardPass()` 已删除；
- shadowCasters 桶暂保留（阴影后置），ShadowCaster 声明制落地时并入 passes；
- 存量合规（当日核查）：default/editor-grid/demo 全 `Pass Forward`，gizmo `Pass Gizmo`，轴线诊断线复用 editor-grid（Forward，主通道可被遮挡——诊断语义正确）。

## 5. 与现有代码的关系

- ~~`RenderingSystem` 当前的"内置无光照着色程序 + 单贴图 set1"是阶段③直注册模式的临时形态~~——**已收编（2026-09-25）**：内置程序路径整体删除（`installBuiltinShaders`/`textureSet(Texture)`/旧 `pipelineFor(layout, blend)` 移除），MultiCameraDemo 与 SceneRenderDemo 均走材质路径；内置 .shader（编译进二进制）~~仍待 javelin-tool 落地~~——**导入链已落地（2026-09-26，编辑器 importer/shader + capsule 二进制产物，见 §3）**；
- 渲染资源包归属（用户拍板 2026-09-25）：Mesh/VertexAttribute + 桥接 MeshUpload/MeshDraw 在 **render.mesh**，Texture2D/TextureLoader + 桥接 TextureUpload 在 **render.texture**，材质五件在 **render.material**；
- **"用户侧零 GPU 暴露"原则**（用户拍板 2026-09-25）：资产公开 API 不出现 RHI 句柄——Texture2D 的 getGpu/setGpu、Mesh 的 vertexBuffer/indexBuffer/indexType/indexCount 全部 package-private，GPU 代码只存在于同包桥接类（**上传逻辑全在桥接**：MeshUpload/TextureUpload 持有 device 逻辑，Mesh/Texture2D 是纯 CPU 数据容器）；`Mesh.layout()`（VertexLayout 描述值，PSO 键）例外公开，类比 Texture2D 的 format 元数据；
- **冻结语义（拍板 2026-09-25）**：上传后资产不可变——Mesh 修改抛异常（既有），Texture2D 补齐守卫（setPixels/setFilterMode/setWrapMode 上传后抛 IllegalStateException，堵"改了静默失效"洞）；动态顶点/像素更新（骨骼、粒子、物理形变）将来走专门的动态缓冲路径，不经重传；
- MeshRenderer 的 `mesh`/`material` 字段已补（场景渲染接入，2026-09-25）：null = 不渲染（missing 语义），SceneRenderBridge pull 翻译；
- AbsAsset 提供 guid/lastModified；~~.shader 侧 Serializable 协议待补~~——**已落地（2026-09-26，§3.1）**；~~.mat 的协议随 .mat 格式另行定义~~——**Material write/read 已先随 .scene 内嵌落地（2026-10-04，§3.1），.mat 资产格式已落地并验收（2026-10-23 用户实测；2026-10-19，§3.2）**。

## 6. 不做（P2）

- **动态 Mesh / 动态纹理**（需求已记录，后续专项支持）：骨骼蒙皮、粒子、物理形变、地形编辑等**每帧变化**的几何/像素数据，静态资产"上传即冻结"语义扛不住 destroy-and-recreate 的频率。届时走专门设计：hostVisible 常驻缓冲 + 每帧映射写入（或 staging 环形），作为独立类型与静态资产并存（对齐 RHI 27 节线程模型，逻辑线程写、渲染线程消费）；动态纹理同理（rhi.Texture 已有 `update(TextureRegion, ByteBuffer)` 运行期更新口）；
- .shader 文本解析器与构建工具本体（本文只定产物结构）；
- ~~.mat 材质资产格式与 AssetManager 加载~~——**已落地并验收（2026-10-23 用户实测；2026-10-19 落地，§3.2）**；
- 材质变体（RHI 20.5 决策：不引入）；
- 同阶段细分排序键（renderOrder，RHI 21.1 决策存档）。

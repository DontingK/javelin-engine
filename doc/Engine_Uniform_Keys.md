# 引擎 Uniform 通道 Key 单点表

> push constants 与各 set UBO 的 **key（块名 / 实例名 / 字段 / 偏移 / 类型 / 语义）** 全项目唯一权威表。
> 改通道字段 = 改契约：本表先改，再同步注入器（ShaderInjector）/ RenderingSystem / 材质桥，已编译 .shader 产物重导。
> set 编号拍板（2026-10-23）：set0 = 相机类帧数据（历史名 UBO1，实住 set0）、set1 = 预留光照、set2 = 材质；设计论证归 Engine_Rendering_Design §4.2/§6、Engine_Material_Design §4，本文只记事实。

## 1. 总览

| 通道 | 位置 | 块名 | 实例名（key） | 更新节奏 | 绑定方 |
|---|---|---|---|---|---|
| 相机 | set 0 / binding 0 | CameraBlock | `cam` | 每视口（动态偏移选相机） | Pass 回调 |
| 灯光 | set 1（binding0 UBO + 1 SSBO 批次②已实装；2/3 SSBO **批次③已实装**；4 sampler 批次④） | LightingParamsBlock + LightList + Cluster 套件 | `lighting` / `lightList` / `clusterGrid` / `clusterIndices`（+ fs 函数 `javelinLighting`） | 每视口每帧（剔除后） | RenderingSystem（CPU 构建，批次③拍板） |
| 材质数值 | set 2 / binding 0 | MaterialBlock | `material` | 每次绑定时重打包 | drawObject |
| 材质贴图 | set 2 / binding 1..n | ——（独立 sampler） | TEXTURE_2D 属性名原样 | 首绑解析后固定 | drawObject |
| 每物件 | push constants | PushBlock | `pc` | 每物件 | drawObject |

## 2. push constants（PushBlock / `pc`）

| 字段 | 类型 | 偏移 | 大小 | 语义 |
|---|---|---|---|---|
| `model` | mat4 | 0 | 64B | localToWorld 世界矩阵 |
| `tpf` | float | 64 | 4B | 帧间隔（秒，Time.getDeltaTime） |

- 管线布局声明范围 **80B**（`RenderingSystem.PUSH_SIZE`；68B 实需 + 对齐）；vendor 上限 128B，余量即 per-object 数据天花板；
- 打包：小端、列主序（JOML `Matrix4.get` 16 float 直传）。

## 3. set 0 相机（CameraBlock / `cam`）

| 字段 | 类型 | 语义 |
|------|------|------|
| `viewProj` | mat4 | view × proj（投影已含 Y 翻转：`proj.m[5] *= -1`） |
| `view` | mat4 | view 矩阵（世界 → 视空间，无翻转；vs 传视空间位置/法线插值用，批次③ Lit 类受光着色器） |

- 描述符：`UNIFORM_BUFFER_DYNAMIC` ×1，**stage = VERTEX**（布局只放行顶点阶段；fs 侧注入了声明但若 fs 要实际消费 `cam`，需先扩布局 stage）；
- 描述符 range = **256B**（`CAMERA_STRIDE`，对齐 minUniformBufferOffsetAlignment）；动态偏移 = 槽基址 + 相机序 × 256；
- 缓冲：hostVisibleCoherent 环形，**2 槽（frameIndex % 2）× 1280B**；
- 槽内布局（CPU 侧引擎私有，256B 对齐）：相机块 ×4（偏移 0/256/512/768，viewProj@0 + view@64，128B 实需）+ **时间块 256B @1024 = { timeSec, tpf }**——时间块不占描述符范围（末槽末相机 offset+range 不越界）、无着色器 key；着色器侧时间目前走 `pc.tpf`。

## 4. set 1 灯光（binding0/1 批次②已实装；2/3 **批次③已实装**；4 批次④）

2026-10-28 拍板（Engine_Lighting_Design v0.1）：**跳过 UBO 数组中间档，直接 clustered + SSBO（Forward+ 形态）**——原 LightBlock UBO 落此预留口径作废。set1 = 光照域多 binding（FRAGMENT）：

| binding | 类型 | key（实例名） | 内容 |
|---|---|---|---|
| 0 | UBO（**DYNAMIC**，每视口动态偏移；批次②实装） | `lighting` | LightingParamsBlock：方向光数组（≤8，槽 0=主光）+ 灯数计数 + cluster grid 元数据 + 环境项（主光阴影段批次④扩块） |
| 1 | SSBO（批次②实装；**批次③起按视口各缓存各打包——首视口口径作废**） | `lightList` | 点/聚光列表（视空间 64B/灯统一结构，容量 4096/视口，强度预排序） |
| 2 | SSBO（批次③实装，按视口） | `clusterGrid` | 每 cell `{offset, count}`（uvec2，8B/cell；CPU 构建，批次③拍板，GPU 化后置 §10） |
| 3 | SSBO（批次③实装，按视口） | `clusterIndices` | 变长光索引池（uint 定容 1M/视口；每 cell 上限 64，截尾=去最弱——CPU 列表序写入精确兑现） |

### 4-1. LightingParamsBlock 字段表（批次③版；CPU=RenderingSystem.packLightingParams、GLSL=ShaderInjector fs 注入同源）

| 字段 | GLSL 声明 | 偏移 | 类型 | 语义 |
|------|-----------|------|------|------|
| `lighting.directional` | `vec4[16]` | 0 | 256B | 8 盏 × 2 vec4：`[2i]`=（视空间传播方向 xyz + castShadow 标志 w）、`[2i+1]`=（rgb + intensity）；空位零填 |
| `lighting.lightCounts` | `ivec4` | 256 | 16B | x=dirCount、y=pointCount（本视口 lightList 有效长度），zw 预留 |
| `lighting.gridDim` | `ivec4` | 272 | 16B | xy=grid 维度（ceil(视口像素/16)）、z=Z 切片数（32）、w=tileSize（16）——批次③填真值 |
| `lighting.zParams` | `vec4` | 288 | 16B | x=相机 near、y=far、**z=ortho 标志（正交=1：Z 线性切分分支）**、w 预留 |
| `lighting.viewport` | `vec4` | 304 | 16B | **批次③新增**：视口帧缓冲区 x/y/w/h（Vulkan 左上原点，同 gl_FragCoord/RHIViewport——着色器 cell 定位用） |
| `lighting.ambient` | `vec4` | 320 | 16B | 环境项（v0.x 常量色，0.15 中性；天空盒环境项归 GI 期） |

- 块实需 **336B**，描述符 range/动态偏移步长 = 512B（256 对齐进取）；缓冲 = 帧槽（frameSlots）× 相机块（MAX_CAMERAS）× 512B hostVisible 环形，与 UBO1 同奇偶；主光阴影段（sunViewProj + 采样参数）批次④追加扩块（新字段在 ambient 后串接，届时随批产物重导）；
- lightList（binding1）：`vec4 lights[]`（std430，无名块）——16 float/灯布局语义归 `Engine_Lighting_Design.md` §3.1；**批次③起 per-view 缓冲**（首视口单缓冲口径作废——多视口挂账 BatchB 解除）；
- clusterGrid（binding2）：`uvec2 clusterGrid[]`（std430 无名块，块名 clusterGridBuffer）；clusterIndices（binding3）：`uint clusterIndices[]`（块名 clusterIndicesBuffer）——cell 线性索引 = tileX + tileY×gridX + zSlice×gridX×gridY；
- **fs 光照段函数**：注入器随声明注入 `vec3 javelinLighting(vec3 viewNormal, vec3 viewPos)`（Lambert + 环境项 + 点/聚光衰减，消费上述全部 binding；定义归 Engine_Lighting_Design §7）；不调用即不受影响（超集合法）。

## 5. set 2 材质（MaterialBlock / `material` + 贴图槽）

| binding | 内容 | key 规则 |
|---|---|---|
| 0 | MaterialBlock UBO（std140） | .shader Properties **声明序**：COLOR/VECTOR → vec4（16B 对齐）、FLOAT/RANGE → float（4B）；TEXTURE_2D 不入块 |
| 1..n | sampler2D 贴图槽 | TEXTURE_2D 属性名原样（如 `_AlbedoMap`），按声明序递增编号 |

- 无数值属性不注入 MaterialBlock（空块 16B 占位只存在 CPU 侧）；
- 排布 CPU/GLSL 同源（`MaterialBlock.pack`：声明序、float 4B、vec4 16B）；
- 数值参数每次绑定时重打包写入（运行期 `setFloat`/`setColor` 即时生效）；贴图槽仅首绑解析（运行期换贴图 = 换材质实例，v0.1 语义）；
- 未赋值贴图槽占位：white / bump·normal / black（RHI 21.4）。

## 6. 保留字（用户 .shader 手写即报错）

| 类别 | 保留字 | 拦截时机 |
|------|--------|----------|
| 块名 | `CameraBlock` / `PushBlock` / `MaterialBlock` / `LightingParamsBlock`（批次②生效）/ `clusterGridBuffer` / `clusterIndicesBuffer`（批次③生效） | 构建期（注释里出现也拦） |
| 实例名 | `cam` / `pc` / `material` / `lighting` / `lightList`（批次②生效）；`clusterGrid` / `clusterIndices`（批次③生效） | GLSL 编译期（与注入声明重复定义） |

> 数据面实例名（lighting / lightList / clusterGrid / clusterIndices）用户不得直接消费——光照数据只经注入函数 `javelinLighting(viewNormal, viewPos)` 访问；该函数与 cam / pc / material 同类（用户直接使用，重定义由 GLSL 编译期兜底，不进构建期拦截名单）。

> 注入声明由 ShaderInjector 在每个 vs/fs 块 `#version` 行后固定生成（v0.13：无标记、位置固定），声明内容 = 本表 §2/§3/§4(fs 专属）/§5；fs 专属光照段函数 `javelinLighting` 随 §4 声明注入。
> 旧 .shader 产物不重导不含 set1 声明——引擎布局超集合法照常跑（批次②兼容口径，批次③.viewport 新字段同族适用：无 shader 消费 ambient 旧偏移，无破坏面）。

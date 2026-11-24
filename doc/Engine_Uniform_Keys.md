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

## 4. set 1 灯光（binding0/1 批次②已实装；2/3 批次③已实装；**4 批次④-A 已实装——binding4 并入 set1 布局**；5/6/7 批次④-B；**8/9 四期 P4-17 体素 AO 已于 2026-11-20 删除，同日 2026-11-21 由 P4-05 DDGI 复用为 binding8 辐照度 atlas / binding9 DDGI 参数 UBO，并新增 binding10 深度矩 atlas；2026-11-25 新增 binding11 shadowMap 原始深度（REND-21-C1 PCSS），布局现到 binding11**）

2026-10-28 拍板（Engine_Lighting_Design v0.1）：**跳过 UBO 数组中间档，直接 clustered + SSBO（Forward+ 形态）**——原 LightBlock UBO 落此预留口径作废。set1 = 光照域多 binding（FRAGMENT）：

| binding | 类型 | key（实例名） | 内容 |
|---|---|---|---|
| 0 | UBO（**DYNAMIC**，每视口动态偏移；批次②实装） | `lighting` | LightingParamsBlock：方向光数组（≤8，槽 0=主光）+ 灯数计数 + cluster grid 元数据 + 环境项 + sunViewProj（批次④-A 扩块 §4-1） |
| 1 | SSBO（批次②实装；**批次③起按视口各缓存各打包——首视口口径作废**） | `lightList` | 点/聚光列表（视空间 64B/灯统一结构，容量 4096/视口，强度预排序） |
| 2 | SSBO（批次③实装，按视口） | `clusterGrid` | 每 cell `{offset, count}`（uvec2，8B/cell；CPU 构建，批次③拍板，GPU 化后置 §10） |
| 3 | SSBO（批次③实装，按视口） | `clusterIndices` | 变长光索引池（uint 定容 1M/视口；每 cell 上限 64，截尾=去最弱——CPU 列表序写入精确兑现） |
| 4 | **COMBINED_IMAGE_SAMPLER**（批次④-A 实装；深度比较采样器） | `shadowMap` | 主光单张硬影深度图（2048² D32 全局唯一非视口归属；**LINEAR 比较采样=硬件双线性 PCF（2x2 免费软化，2026-11-01 实机锯齿反馈换装）** + CLAMP_TO_EDGE + compare LESS；GLSL 声明 `sampler2DShadow`）——per-view set 各自指同图同采样器（图+采样器引擎常驻） |
| 5 | COMBINED_IMAGE_SAMPLER（批次④-B 实装；per-view，NEAREST/CLAMP） | `gbufferAlbedo` | G-Buffer 基色（R8G8B8A8_UNORM，`_Color × _MainTex`）——HYBRID 档 LightingPass 专属消费；材质 shader 不声明即不用 |
| 6 | COMBINED_IMAGE_SAMPLER（批次④-B 实装；per-view） | `gbufferNormal` | G-Buffer 世界空间法线（R16G16B16A16_SFLOAT，归一）——LightingPass 用 `cam.view` 换算视空间 |
| 7 | COMBINED_IMAGE_SAMPLER（批次④-B 实装；per-view） | `gbufferWorldPos` | G-Buffer 世界位置（R32G32B32A32_SFLOAT，**w=有效标志**：几何 1.0 / 清屏 0.0）——LightingPass 据此 discard 背景 |

### 4-1. LightingParamsBlock 字段表（**四期 CSM 版**；CPU=RenderingSystem.packLightingParams、GLSL=ShaderInjector fs 注入同源）

| 字段 | GLSL 声明 | 偏移 | 类型 | 语义 |
|------|-----------|------|------|------|
| `lighting.directional` | `vec4[16]` | 0 | 256B | 8 盏 × 2 vec4：`[2i]`=（视空间传播方向 xyz + castShadow 标志 w）、`[2i+1]`=（rgb + intensity）；空位零填 |
| `lighting.lightCounts` | `ivec4` | 256 | 16B | x=dirCount、y=pointCount（本视口 lightList 有效长度），zw 预留 |
| `lighting.gridDim` | `ivec4` | 272 | 16B | xy=grid 维度（ceil(视口像素/16)）、z=Z 切片数（32）、w=tileSize（16）——批次③填真值 |
| `lighting.zParams` | `vec4` | 288 | 16B | x=相机 near、y=far、z=ortho 标志（正交=1）、**w=CSM 级联数（四期新增，1/2/4；采样按此遍历）** |
| `lighting.viewport` | `vec4` | 304 | 16B | 视口帧缓冲区 x/y/w/h（Vulkan 左上原点，同 gl_FragCoord/RHIViewport——着色器 cell 定位用） |
| `lighting.ambient` | `vec4` | 320 | 16B | 环境项（v0.x = 纯黑，Unity 口径 2026-11-01 拍板；真值源归四期天空盒环境光） |
| `lighting.sunViewProj[4]` | `mat4[4]` | 336 | 256B | **四期 CSM（2026-11-14）**：逐级联 主光 世界→光裁剪 正交矩阵（zZeroToOne；未用槽 = identity）。原单 `mat4 sunViewProj`（批次④-A @336）扩为数组 |
| `lighting.cascadeSplits` | `vec4` | 592 | 16B | 各级联视空间 z 远界（`-viewPos.z` 选级联；超出末级联 = 恒受光）；未用槽 = 相机 far |
| `lighting.shadowParams` | `vec4` | 608 | 16B | x=品质档编码（0 硬影 / 1=4 tap→3×3 / 2=9 tap→5×5）、y=PCF 半径（纹素）、z=阴影强度（0~1）、**w=参考深度偏移（世界单位，逐级联乘 `cascadeInvZ` 折算，四期改语义）**；注入段另加**固定半纹素外扩** `0.5/(atlasSize/grid)`（2026-11-20 接触漏光修复，非 UBO 字段、由 y/x 现推） |
| `lighting.shadowParams2` | `vec4` | 624 | 16B | **四期 CSM 新增**：x=atlas 每轴象限数（1 或 2）、y=atlas 边长（纹素）、z=级联混合带比例、w 预留 |
| `lighting.cascadeInvZ` | `vec4` | 640 | 16B | **四期 CSM 新增**：逐级联 1/光空间 z 范围（参考深度偏移「世界单位→裁剪 z 增量」的除数） |

- 块实需 **656B**，描述符 range/动态偏移步长 = **768B**（256 对齐进取 3×；四期 CSM 由 512→768，2026-11-14）；缓冲 = 帧槽（frameSlots）× 相机块（MAX_CAMERAS）× 768B hostVisible 环形，与 UBO1 同奇偶；
- lightList（binding1）：`vec4 lights[]`（std430，无名块）——16 float/灯布局语义归 `Engine_Lighting_Design.md` §3.1；**批次③起 per-view 缓冲**（首视口单缓冲口径作废——多视口挂账 BatchB 解除）；
- clusterGrid（binding2）：`uvec2 clusterGrid[]`（std430 无名块，块名 clusterGridBuffer）；clusterIndices（binding3）：`uint clusterIndices[]`（块名 clusterIndicesBuffer）——cell 线性索引 = tileX + tileY×gridX + zSlice×gridX×gridY；
- shadowMap（binding4）：`layout(set=1, binding=4) uniform sampler2DShadow`——采样结果 = compare LESS 深度比较（ref < 纹素深度=未遮挡=1.0）；**2026-11-25 起 PCSS 的变半径 PCF 仍走本槽**（核半径按遮挡距离推出），阻挡搜索另走 binding11；
- gbufferAlbedo/gbufferNormal/gbufferWorldPos（binding5/6/7，批次④-B）：LightingPass 全屏读 G-Buffer 三张（`RenderingSystem.frameStorageLayoutDesc` 与 `ShaderInjector` 的 G-Buffer 注入块同源）；G-Buffer 三张 per-view（与 depth 同尺码，resize 换绑并重建 set1 描述符集）；
- ~~voxelAO（binding8，四期 P4-17）~~ **已删除（2026-11-20，P4-17 整体退役）**：原 `sampler3D` 体素 AO 模糊图与 `syncVoxelAo` 重指逻辑一并移除；**binding8 槽已于 2026-11-21 由 DDGI 辐照度 atlas 复用**（见下）；
- ~~voxelParams（binding9，四期 P4-17）~~ **已删除（2026-11-20）**：`VoxelParamsBlock` / `VoxelGrid.writeParams` 同批移除；**binding9 槽已于 2026-11-21 由 DDGI 参数 UBO 复用**（见下）；
- **ddgiIrradiance（binding8，四期 P4-05/B3）**：`layout(set=1,binding=8) uniform sampler2D`——探针辐照度 atlas（RGBA16F，10 列 × 8 内区 tile，NEAREST/CLAMP 采样器；采样走注入段 `javelinProbeIrradiance` 的 **内区手写双线性**，不依赖硬件 LINEAR 以免跨 tile 渗漏）；per-view，`RenderingSystem.syncDdgi` 在 atlas 句柄变化时重建 set1，未就绪时指 1×1 HDR 占位图；
- **ddgiParams（binding9，四期 P4-05/B3）**：`DdgiParamsBlock` UBO（std140，实需 **96B**，range/步长 96B 非动态）——`vec4 gridOriginSpacing`(xyz 网格原点 / w 探针间距)、`ivec4 counts`(xyz 三向探针数 / w tilesPerRow)、`vec4 tileParams`(x 辐照度 tile 边长=10 / y 内区=8 / z 深度 tile=18 / w 深度内区=16)、`vec4 ddgiParams`(**x mode 0/1** / y 强度 / z 法线偏置（世界单位）/ w 预留)；CPU=`RenderingSystem.writeDdgiParams`、GLSL=`ShaderInjector.DDGI_BLOCKS` 同源，每帧重写（mode = `ambientMode==DDGI && DdgiSettings.enabled`）；
- **ddgiDepth（binding10，四期 P4-05/B3）**：`layout(set=1,binding=10) uniform sampler2D`——深度矩 atlas（RG16F，18 × 16 tile），供 `javelinProbeVisibility` 做 Chebyshev 上界；per-view、与 binding8 同一 `syncDdgi` 生命周期；
- **shadowMapRaw（binding11，REND-21-C1 PCSS）**：`layout(set=1,binding=11) uniform sampler2D`——与 binding4 **同一张 shadow atlas**，但绑 NEAREST/CLAMP **非比较采样器**：阻挡搜索要真实深度值，比较采样器只给 0/1 可见性。per-view，随 shadow map 换代与 binding4 同步重指（`updateLightingSet` 与 atlas 换绑两处都写）；
- **set1 布局现为 binding0~11**（8/9 由体素 AO 槽复用、10 为 DDGI 新增、11 为 PCSS 原始深度）；`@javelinLighting` 注入段对 DDGI 的消费是**超集**：`ddgiParams.x==0` 时环境项回落 `ambient.rgb*ambient.a`，不依赖 binding8/10 内容，故未接 DDGI 的视口行为与分批前一致；PCSS 由 `shadowParams2.w` 门控（0 = 不采样 binding11，行为与改前一致）；
- **fs 光照段函数**：注入器随声明注入 `vec3 javelinLighting(vec3 viewNormal, vec3 viewPos, vec3 worldPos)`（Lambert + 环境项 + 点/聚光衰减 + 主光 CSM 阴影，消费上述全部 binding；第三参=世界位置插值供阴影重建；定义归 `Engine_Lighting_Design.md` §7 / `Engine_Shadow_Quality_Design.md` §3/§4）；不调用即不受影响（超集合法）——**签名变更（批次④-A 三参）= 契约变更，调用方须随改，注入器改动须强制重导**；同段另注入辅助函数 `float javelinCascadeShadow(int cascade, vec3 worldPos)`（单级联：矩阵投影 + atlas 象限定位 + 品质档 PCF，核夹在象限内）与 `float javelinShadowTapC(vec2, float, vec2, vec2)`（单 tap 深度比较，夹取象限界）；**2026-11-25 另注入 `float javelinPcssBlockerDepth(vec2 uv, float refZ, vec2 qmin, vec2 qmax, float radiusTexels, float texelSize, int taps)`（REND-21-C1 阻挡搜索：黄金角螺旋 16/24 tap 读 binding11 原始深度，取 refZ 以下均值，无遮挡返回 -1）**——`javelinCascadeShadow` 内按 `shadowParams2.w > 0` 调用它，把 PCF 核半径从常量换成半影；级联选择 + 相邻混合带在 `javelinLighting` 内（四期 CSM 2026-11-14）；另注入 `float javelinVoxelAO(...)` 已于 2026-11-20 随 P4-17 删除（遮蔽由 SSAO 承担）；**环境项自 2026-11-21 起由 DDGI 接管**——`javelinLighting` 首行：`ddgiParams.x>0.5 && javelinDDGIInside(worldPos)` 时环境项 = `javelinDDGI(worldPos, worldNormal)`，否则 `ambient.rgb*ambient.a`（同段另注入 `javelinOctaEncode` / `javelinProbeIrradiance` / `javelinProbeVisibility` / `javelinDDGIInside` / `javelinDDGI` 五个辅助函数，定义归 `Engine_DDGI_Design.md` §13.3）；**`javelinLighting` 签名未变**（世界法线由 `transpose(mat3(cam.view)) * viewNormal` 现推——该行亦随之精简），故调用方零改动，但注入器改动仍须强制重导。

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
| 实例名 | `cam` / `pc` / `material` / `lighting` / `lightList`（批次②生效）；`clusterGrid` / `clusterIndices`（批次③生效）；`shadowMap`（批次④-A 生效）；`gbufferAlbedo` / `gbufferNormal` / `gbufferWorldPos`（批次④-B 生效——G-Buffer 数据面，仅全屏光照 Pass 消费） | GLSL 编译期（与注入声明重复定义） |

> 数据面实例名（lighting / lightList / clusterGrid / clusterIndices / shadowMap）用户不得直接消费——光照数据只经注入函数 `javelinLighting(viewNormal, viewPos, worldPos)` 访问；该函数与 cam / pc / material 同类（用户直接使用，重定义由 GLSL 编译期兜底，不进构建期拦截名单）。

> 注入声明由 ShaderInjector 在每个 vs/fs 块 `#version` 行后固定生成（v0.13：无标记、位置固定），声明内容 = 本表 §2/§3/§4(fs 专属）/§5；fs 专属光照段函数 `javelinLighting` 随 §4 声明注入。
> 旧 .shader 产物不重导不含 set1 声明——引擎布局超集合法照常跑（批次②兼容口径，批次③.viewport 新字段同族适用：无 shader 消费 ambient 旧偏移，无破坏面）。

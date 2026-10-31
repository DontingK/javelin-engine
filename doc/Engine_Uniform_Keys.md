# 引擎 Uniform 通道 Key 单点表

> push constants 与各 set UBO 的 **key（块名 / 实例名 / 字段 / 偏移 / 类型 / 语义）** 全项目唯一权威表。
> 改通道字段 = 改契约：本表先改，再同步注入器（ShaderInjector）/ RenderingSystem / 材质桥，已编译 .shader 产物重导。
> set 编号拍板（2026-10-23）：set0 = 相机类帧数据（历史名 UBO1，实住 set0）、set1 = 预留光照、set2 = 材质；设计论证归 Engine_Rendering_Design §4.2/§6、Engine_Material_Design §4，本文只记事实。

## 1. 总览

| 通道 | 位置 | 块名 | 实例名（key） | 更新节奏 | 绑定方 |
|---|---|---|---|---|---|
| 相机 | set 0 / binding 0 | CameraBlock | `cam` | 每视口（动态偏移选相机） | Pass 回调 |
| 灯光 | set 1（binding0 UBO + 1 SSBO **批次②已实装**；2/3 SSBO 随批次③；4 sampler 批次④） | LightingParamsBlock + LightList | `lighting` / `lightList` | 每视口每帧（剔除后） | RenderingSystem / ClusterBuildPass |
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
|---|---|---|
| `viewProj` | mat4 | view × proj（投影已含 Y 翻转：`proj.m[5] *= -1`） |

- 描述符：`UNIFORM_BUFFER_DYNAMIC` ×1，**stage = VERTEX**（布局只放行顶点阶段；fs 侧注入了声明但若 fs 要实际消费 `cam`，需先扩布局 stage）；
- 描述符 range = **256B**（`CAMERA_STRIDE`，对齐 minUniformBufferOffsetAlignment）；动态偏移 = 槽基址 + 相机序 × 256；
- 缓冲：hostVisibleCoherent 环形，**2 槽（frameIndex % 2）× 1280B**；
- 槽内布局（CPU 侧引擎私有，256B 对齐）：相机块 ×4（偏移 0/256/512/768，viewProj 64B 实需）+ **时间块 256B @1024 = { timeSec, tpf }**——时间块不占描述符范围（末槽末相机 offset+range 不越界）、无着色器 key；着色器侧时间目前走 `pc.tpf`。

## 4. set 1 灯光（binding0/1 批次②已实装；2/3 批次③；4 批次④）

2026-10-28 拍板（Engine_Lighting_Design v0.1）：**跳过 UBO 数组中间档，直接 clustered + SSBO（Forward+ 形态）**——原「LightBlock UBO 落此」预留口径作废。set1 = 光照域多 binding（FRAGMENT）：

| binding | 类型 | key（实例名） | 内容 |
|---|---|---|---|
| 0 | UBO（**DYNAMIC**，每视口动态偏移；批次②实装） | `lighting` | LightingParamsBlock：方向光数组（≤8，槽 0=主光）+ 灯数计数 + cluster grid 元数据 + 环境项（主光阴影段批次④扩块） |
| 1 | SSBO（批次②实装，引擎常驻） | `lightList` | 点/聚光列表（视空间 64B/灯统一结构，容量 4096，强度预排序；批次②首视口打包，多视口归批次③） |
| 2 | SSBO | `clusterGrid` | 每 cluster {offset, count}（8B/cell） |
| 3 | SSBO | `clusterIndices` | 变长光索引池（每 cluster 上限 64，截尾=去最弱） |

### 4-1. LightingParamsBlock 字段表（批次②版；CPU=RenderingSystem.packLightingParams、GLSL=ShaderInjector fs 注入同源）

| 字段 | GLSL 声明 | 偏移 | 类型 | 语义 |
|---|---|---|---|---|
| `lighting.directional` | `vec4[16]` | 0 | 256B | 8 盏 × 2 vec4：`[2i]`=（视空间方向 xyz + castShadow 标志 w）、`[2i+1]`=（rgb + intensity）；空位零填 |
| `lighting.lightCounts` | `ivec4` | 256 | 16B | x=dirCount、y=pointCount（lightList 有效长度），zw 预留 |
| `lighting.gridDim` | `ivec4` | 272 | 16B | cluster grid 维度（批次③ ClusterBuildPass 落位时填，批次②写 0） |
| `lighting.zParams` | `vec4` | 288 | 16B | x=相机 near、y=far（cluster Z 切分重建用） |
| `lighting.ambient` | `vec4` | 304 | 16B | 环境项（v0.x 常量色，0.15 中性；天空盒环境项归 GI 期） |

- 块实需 320B，**描述符 range/动态偏移步长 = 512B**（256 对齐进取）；缓冲 = 帧槽（frameSlots）× 相机块（MAX_CAMERAS）× 512B hostVisible 环形，与 UBO1 同奇偶；主光阴影段（sunViewProj + 采样参数）批次④追加扩块、届时随批重导；
- lightList（binding1）：`vec4 lights[]`（std430，无实例名块式声明）——16 float/灯布局语义归 `Engine_Lighting_Design.md` §3.1；
- **多视口边界（批次②挂账）**：binding0 UBO 已按视口动态偏移各自正确打包；binding1 lightList 为帧级单缓冲、内容=**首视口**的剔除/打包结果——多视口各自列表随批次③ ClusterBuildPass（binding2/3 视口级 SSBO）一并落位；
- ClusterBuildPass 构建侧为 compute 布局，另行声明（不与着色布局混用）。

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
|---|---|---|
| 块名 | `CameraBlock` / `PushBlock` / `MaterialBlock` / `LightingParamsBlock`（**批次②已生效**） | 构建期（注释里出现也拦） |
| 实例名 | `cam` / `pc` / `material` / `lighting` / `lightList`（**批次②已生效**）；`clusterGrid` / `clusterIndices`（随批次③生效） | GLSL 编译期（与注入声明重复定义） |

> 注入声明由 ShaderInjector 在每个 vs/fs 块 `#version` 行后固定生成（v0.13：无标记、位置固定），声明内容 = 本表 §2/§3/§4（fs 专属）/§5。
> 旧 .shader 产物不重导不含 set1 声明——引擎布局超集合法照常跑（批次②兼容口径）。

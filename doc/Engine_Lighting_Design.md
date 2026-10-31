# 引擎光照设计（Engine Lighting Design）

> 版本：v0.6
> 日期：2026-10-28（v0.2 2026-10-31；v0.3 2026-10-31；v0.4 2026-10-31；v0.5 2026-10-31；v0.6 2026-10-31）
> 说明：三期光照专档——clustered 光照（Forward+ 形态）的数据结构、set1 契约、Light 组件与收集、cluster 构建 compute Pass、ShadowPass、着色消费与实现批次。**设计拍板完成；批次① RHI 底座已落地并验收关账（2026-10-31 当日用户实测）；批次② 光数据模型已落地并验收关账（2026-10-31 当日用户实测）**，实装随四批路线推进（§9），字段详表落位时同步 `Engine_Uniform_Keys.md`。
> v0.6 变更（2026-10-31）：**灯组件子类化拍板**（用户提案+拍板：「每种 light 一个类」——每型参数集不相交，单类+type 枚举会摆无生效语义的死字段（点光显锥角/方向光显 range），Collider 族同构）：`Light` 抽象基类（color/intensity/castShadow + getType() 抽象 + 序列化 super 链）+ `DirectionalLight`（零附加字段）/`PointLight`（+range）/`SpotLight`（+range+锥角）；**不支持切换类型**（换型 = 删组件重加，不做 Inspector 条件显隐）；type 不落盘（子类身份即类型）；引擎侧 `RenderLight` 改自持 `RenderLight.Type` 枚举（render 包不再依赖 scene 组件类层次）；菜单三项全解锁（spot 收集侧已支持，着色批次③）；Inspector 面板每型只显自己的字段。
> v0.5 变更（2026-10-31）：**主光裁决拍板改向——推翻 v0.1 显式 isMain 标志，对齐 Unity 自动裁决**（用户拍板「引擎自己处理」）：主光 = 最强激活方向光（tie 取挂入序首个保确定性），`Light.isMain` 字段/访问器全删、`Scene.getSun` 改裁决实现、LightCollector 槽 0 改强度排定、菜单创建命令去 isMain 预设——原「确定性优先/编辑器可视化清晰」论证作废（Unity 对照后用户裁决：标志位是多余的用户负担）。ShadowPass 消费口径不变（仍只服务主光）；**相机 main 标志不动**（本拍板只涉灯光域）。
> v0.4 变更（2026-10-31）：**批次②落地**（Light 组件 + lights 注册表/getSun + SceneRenderBridge pull 翻译 + LightCollector 收集（剔除/预排序/视空间打包）+ set1 binding0/1（LightingParamsBlock UBO 动态偏移 + lightList SSBO）+ ShaderInjector fs 声明注入/保留字生效 + F3 行 8 在册灯数 + 单测——LightCollectorTest/GameSceneTest 灯注册表与主光解析）；**实装拍板**：binding0 UBO 用 DYNAMIC 每视口动态偏移（set0 相机块同构）；视锥剔除自写视空间球测试（viewProj 含 Y 翻转是反射矩阵，FrustumIntersection 平面朝向不可信）；**多视口挂账**：lightList 帧级单缓冲=首视口口径，各视口列表随批次③ ClusterBuildPass 落位（§3.1）。
> v0.3 变更（2026-10-31）：**批次①落地**（§9 批次①行——RHI 五缺口补齐 + `.compute` 资产面 + set1 帧级 storage 通道 + 编辑器验收件）；**compute 着色器资产路线拍板**（§7——`.compute` 独立资产类型，内嵌 spv 路线推翻，DebugText/pick/DebugLine 迁源码独立批待派）。
> v0.2 变更（2026-10-31）：**补 ShadowPass 明细（§4.2——单张硬影拍板，级联/软化后置）**；§7 着色注入补红线（**default.shader = DefaultMaterial 兜底专用，禁止改造成注入样例**——用户拍板）；批次④产出补阴影消费路径；天空盒视觉化（cubemap）转专档 `Engine_Skybox_Design.md`（批次⑤）。
> 职责边界：
> - 本文管**光数据通路与光照组织**（灯怎么收集、打包、分簇、进着色器）+ **主光阴影的深度通路**（ShadowPass 数据侧：shadowCasters 收集、lightViewProj、shadowMap 采样参数）；
> - 管线档管 Pass 编排与视口资源（clusterBuild/Skybox 的插入位置、Lighting/GBuffer 的 RT 读写）；
> - 着色模型（BRDF 演进、材质光照钩子）归材质/着色模型专档（后置），本文 v0.x = Lambert + 环境项；
> - 天空盒（视觉 Pass + cubemap 底座）归 `Engine_Skybox_Design.md`（2026-10-31 建档）。

---

## 1. 拍板记录（2026-10-28）

- **直接 clustered + SSBO（Forward+ 形态），跳过中间档**。用户拍板：只面向 PC、质量优先、一步到位。
  - 弃用档 A：UBO 光数组 + 固定上限（URP / Godot Mobile 档）——每物体/每视口有截断，质量路线不取；
  - 弃用档 B：GBuffer 上逐盏画光体积（Unity 内置 / URP 延迟侧）——无 compute/SSBO 依赖即可「延迟侧不限量」，但几十盏以上 fullscreen×N 绘制变贵，且透明侧仍受限，作为过渡档被跳过；
  - 选定档 C：clustered + SSBO（HDRP / UE / Godot 4 Forward+ 同构）——正透明同读一份 cluster，**无每物体截断**，这就是管线档 4.5 挂的「两路光照一致性」取舍的消解方案。
- **clustered（3D grid）而非 tiled（2D）**：tiled 在相机贴近物体时单 tile 深度跨度过大、光列表爆容；clustered 以 Z 切分贴合 1/z 深度密度，三家（HDRP/UE/Godot 4）共同选择。
- **默认参数**：X/Y = 视口 16×16 像素一片；Z = 32 片指数切分；**每 cluster 光索引上限 64**（超容按强度截尾，§6）。
- **主光（sun）自动裁决**：方向光全体不进 cluster（数量少、无位置语义），全局读；主光 = **最强激活方向光**（`Scene.getSun` 每调用现算，同强度取挂入序首个保确定性——**2026-10-31 用户拍板对齐 Unity**，推翻原「显式 isMain 标志/确定性优先」方案：组件无 main 标志位，调 intensity 即时换主）。ShadowPass 只服务主光（管线档 4.1 既定）。
- **灯组件子类化（2026-10-31 拍板，v0.6）**：每型一类（`Light` 抽象基类 + Directional/Point/Spot 子类）——参数集不相交不摆死字段、Collider 族同构；**不支持切换类型**（不做 Inspector 条件显隐，换型删组件重加）；相机单类+ProjectionType 枚举先例不适用（Light 是纯数据组件、无共行分支）。
- **SPOT**：数据结构留槽（64B 统一结构含 spot 参数），首批着色分支只做 DIRECTIONAL + POINT，SPOT 着色归批次③后半。
- **点光衰减**：物理 inverse-square，到 range 平滑归零（Unity/UE 口径）。

## 2. 总体数据流（Forward+）

```
RenderingSystem 帧收集（逻辑线程，与 RenderList 收集同域）
  → 灯收集：Scene.lights 注册表 → 非激活/禁用过滤 → 视锥剔除（点/聚光按包围球）
  → 视空间打包 lightList（64B/灯，按强度预排序）+ 方向光/元数据进 LightingParams UBO
  ↓ 每视口
ClusterBuildPass（compute，每视口每帧）                [三期新增，管线档 2.2 计算 Pass 转正]
  输入 lightList + 相机参数 → 输出 clusterGrid（offset/count）+ clusterIndices（变长池）
  ↓
OpaqueForward / TransparentForward /（批次④）GBuffer+LightingPass
  着色器：像素 → 定位 cell → 取该 cell 光索引 → 逐盏累加 + 方向光全局段 + 环境项
```

- **每视口一套** cluster（视口尺寸决定 X/Y 维度）——与 depth/GBuffer 同级视口资源（管线档 5.1 补行）；
- cluster 内容是**每帧数据**（不进 structureHash，相机矩阵/灯列表变化不失效图缓存）；SSBO 本体为视口级持久资源，resize 才重建；
- 相机/灯集合不变时跳过重建（脏标记）为后置优化（§10）。

## 3. 数据结构

### 3.1 光列表 LightItem（点/聚光，SSBO，std430）

统一 64B（4×vec4），**视空间**打包（cluster Z 切分与着色距离计算天然视空间，免逆变换）：

| 字段 | 类型 | 语义 |
|------|------|------|
| `posType` | vec4 | xyz=视空间位置，w=类型（0=POINT, 1=SPOT） |
| `dirRadius` | vec4 | xyz=视空间方向（SPOT 用），w=range（衰减半径，世界单位） |
| `colorIntensity` | vec4 | rgb=颜色（线性 HDR），w=强度 |
| `spotParams` | vec4 | x=cos(inner), y=cos(outer)，zw 预留 |

- 容量 **4096**（常量起步；超限 = 剔除后取强度前 4096 + WARN 显性化，F3 overlay 显示在册灯数）；
- **按强度预排序**（CPU 侧每帧，强度 = intensity / 屏幕投影面积² 量级的简化评分——实装口径 = `intensity / distSq`（相机距离平方的逆），同强度近者排前；O(n log n) 几百盏无压力，实装为插入排序）——超容截尾即「去最弱」（§6 截断承诺的兑现方式，免 GPU 排序）；
- **批次②实装口径**：视空间打包按 `-z` 前向（JOML RH lookAt，可见物 z<0）；lightList 为帧级单缓冲、内容 = **首视口**剔除/打包结果（多视口各自列表归批次③ ClusterBuildPass 一并落位——binding2/3 本就是视口级 SSBO）。

### 3.2 cluster 索引三件套

| 结构 | 类型 | 内容 |
|------|------|------|
| `clusterGrid` | SSBO（uint×2/cell） | 每 cell `{offset, count}`，指向索引池 |
| `clusterIndices` | SSBO（uint 变长池） | 光索引扁平池，按 cell 段落切分 |
| LightingParams（UBO） | §3.3 | grid 维度/切分系数等重建参数 |

- grid 规模：1080p 全屏 16×16 tile → 120×68×32 = 261,120 cell；`clusterGrid` = 2 MB 常驻；
- 索引池按需增长：持久 SSBO 定容 **1M uint（4 MB）**，溢出 WARN（截断保护见 §6）；典型场景（几百盏 × 各覆盖几十 cell）实测占用量级为几万 uint；
- ~~定长 64 槽/cell~~ 方案弃用（26 万 cell × 64 × 4B ≈ 66 MB，空槽浪费 95%+）——变长池内存 = 灯数 × 平均覆盖，非 cell × 容量。

### 3.3 LightingParamsBlock（UBO，std140，FRAGMENT）

| 段 | 内容 | 说明 |
|----|------|------|
| 方向光数组 | `directional[8]`，每盏 2×vec4（dir+castShadow / color+intensity） | 槽 0 = 主光；上限 8，超限 WARN |
| 计数 | `dirCount` / `pointCount`（uint×2） | pointCount = lightList 有效长度 |
| grid 元数据 | `gridDim`（uvec3）+ `zParams`（vec2：near/far）+ `tileSize`（uint） | 着色器重建 cell 坐标用（§6 公式） |
| 环境项 | `ambient`（vec4：RGB + 强度） | v0.x 环境光=常量色（Scene.environment 空间光/天空盒环境项后置——天空盒视觉化归 Engine_Skybox_Design，环境光照升级归 GI 探针期，Roadmap 遗留项） |
| 主光阴影段 | `sunViewProj`（mat4）+ shadowMap 采样参数 | **批次④占位**；挂点已拍板（2026-10-28）= set1 扩展 binding4 combined depth-sampler（管线档 §7 同步收口），随 ShadowPass 落位 |

- **批次②实装口径**：块为 std140 全 vec4 数组式排布（directional[16] vec4 / lightCounts ivec4 / gridDim ivec4 / zParams vec4 / ambient vec4），实需 320B、**DYNAMIC 动态偏移步长 512B**（每视口一块，字段详表单点归 `Engine_Uniform_Keys.md` §4-1）；gridDim/zParams 批次②写 0/相机裁剪面真值、cluster 消费归批次③；主光阴影段批次④追加扩块；环境项 v0.x = 0.15 中性常量。

### 3.4 set1 契约（binding 划分）

| binding | 类型 | key（实例名） | 内容 |
|---|---|---|---|
| 0 | UBO | `lighting` | LightingParamsBlock（§3.3） |
| 1 | SSBO | `lightList` | LightItem 数组（§3.1） |
| 2 | SSBO | `clusterGrid` | cell {offset, count}（§3.2） |
| 3 | SSBO | `clusterIndices` | 变长光索引池（§3.2） |
| 4 | sampler（combined depth） | ——（批次④占位，随 ShadowPass 落位定 key） | shadowMap（主光阴影采样，挂点拍板 2026-10-28） |

- key 单点归 `Engine_Uniform_Keys.md` §4（本文为设计源，实装落位时该表补字段行）；
- stage = FRAGMENT（重建与采样均片元消费）；ClusterBuildPass 构建侧为 compute 布局，另行声明（不与着色布局混用）；
- 保留字随实装进 Uniform_Keys §6：块名 `LightingParamsBlock`、实例名 `lighting` / `lightList` / `clusterGrid` / `clusterIndices`。

## 4. Light 组件与场景收集

```java
abstract class Light extends Component {      // 公共面 + getType() 抽象
    Color color;          // 线性 HDR
    float intensity;
    boolean castShadow;   // v0.x 仅主光消费（ShadowPass）
}
class DirectionalLight extends Light {}        // 零附加字段（方向=节点朝向）
class PointLight extends Light { float range; }
class SpotLight extends Light { float range; float innerAngle, outerAngle; }  // 度
```

- **子类化拍板（2026-10-31，v0.6）**：每型参数集不相交——单类+type 枚举会摆无生效语义的死字段（点光显锥角/方向光显 range），Collider 族同构拆子类；**不支持切换类型**（换型 = 删组件重加，不做 Inspector 条件显隐）；type 不落盘（子类身份即类型，序列化按子类走）；
- Scene 5.3 草稿（DIRECTIONAL/POINT/SPOT + castShadow）扩展如上，字段级单点归本文（**isMain 已删**——主光自动裁决，v0.5 拍板见 §1）；
- **注册表**：`scene.add` 按类型登记 `lights`（基类 Light 分流，与 renderers/cameras/colliders 同构，Scene_Design §5.2 口径）；`Scene.getLights` 投影（编辑器调试线/统计消费）；
- 主光解析：`Scene.getSun()` = **最强激活方向光**（对齐 Unity 自动裁决，v0.5；同强度取挂入序首个——无则 null）；
- 引擎侧翻译：`RenderLight` 自持 `Type` 枚举（DIRECTIONAL/POINT/SPOT——render 包不依赖组件类层次），SceneRenderBridge instanceof 分流填充；
- **收集**（RenderingSystem 帧收集扩展）：非激活过滤 → 点/聚光按世界包围球做相机视锥剔除 → 视空间打包 → 预排序；方向光全量进 UBO（无剔除）；
- .scene 序列化：Light 为普通 Component，随组件通则序列化，无特例。

## 4.5 ShadowPass 数据侧（v0.x = 单张硬影，2026-10-31 拍板）

- **形态拍板**：主光**全局单张** 2048² depth-only、正交包围盒盖全场景，先兑现「投影正确」验收；**PCF 软化 / CSM 级联 / bias 精调分层后置**（§10）——与 clustered「一步到位」不矛盾：clustered 解决的是「灯数量无截断」，阴影质量分级属另一维度，v0.x 单灯单张无级联必要；
- **lightViewProj**：主光方向 + 场景 AABB（RenderList.shadowCasters 世界包围盒并集）构造正交 frustum；朝向锁定主光前向、up 取稳定轴防抖动（Unity 式 stabilize 后置）；
- **shadowCasters**：v0.x = 全部不透明 mesh renderer（无 per-object 开关，`castShadow` 组件位后置）；收集归 RenderingSystem 帧收集（与 lights/renderables 同域）；
- **着色消费**：注入光照段的阴影采样分支——`sunViewProj` 重建 shadowMap UV → 单次采样 depth compare（sampler = set1 binding4 combined depth-sampler，§3.4）；`castShadow=false` 的方向光跳过采样；**v0.x 无 PCF 无 bias**（`slope-scaled depth bias` 在 PSO rasterization 侧按主光分辨率/朝向先给保守值，漏影/彼得潘宁实测再调——无经验值盲写属红线 2/5）；
- **两路一致**：Forward 注入段与 Deferred LightingPass（管线档 4.3）消费同一 shadowMap + sunViewProj，无两套阴影语义。

## 5. Cluster 切分几何

- **X/Y**：屏幕 tile，`tileIndex = floor(pixelCoord / tileSize)`，tileSize=16；
- **Z**：视空间正深度 z ∈ [near, far] 的**指数切分**（近密远疏，贴合 1/z 密度分布）：

```
slice(z)  = floor( numZ * log2(z / near) / log2(far / near) )，clamp 到 [0, numZ-1]
zSlice 边界：z_s = near * (far / near)^(s / numZ)
```

- cell 线性索引：`x + y * gridX + zSlice * gridX * gridY`（Morton 码后置，§10）；
- near/far 取**相机裁剪面**（v0.x）；正交相机 Z 切分退化为线性（正交无透视密度差）——归批次③后半边界项。

## 6. ClusterBuildPass（compute）

- **位置**：每视口 Pass 链首（ShadowPass 之前；不依赖任何几何，只吃 lightList + 相机）；
- **dispatch 组织**（三步，经典两遍构建 + 前缀和）：
  1. **count**：每 thread 一盏灯 → 求其视空间 AABB 覆盖的 tile 范围 × z 范围 → 对覆盖 cell `atomicAdd(count)`；
  2. **前缀和**：count → 池 offset（**clamp(count, 64) 后**的前缀和，决定每 cell 池配额）；初版 **CPU 侧做**（26 万元素单线程 <1ms，compute scan 后置）；
  3. **write**：每 thread 一盏灯重走覆盖 → 每 cell `atomicAdd(cursor)` 取槽位写入索引；cursor ≥ 64 丢弃（池配额只有 64，天然封顶）。
- **截断语义**：lightList 已按强度预排序（§3.1），覆盖同一 cell 的灯按列表序 append——超 64 被丢弃的是**更弱者**（强度序截尾），兑现「每像素最多迭代 64 盏」的封顶承诺；
- **每帧 reset**：cursor/offset 池清零（小缓冲 host-visible 直写；26 万 cell 的 grid 清零归 compute 或 CPU，批次③实测定）；
- dispatch 规模 = 灯数（step1/3）与 cell 数（step2 若计算化），工作组成变化不进 structureHash（数据级）。

## 7. 着色消费（v0.x = Lambert + 环境项）

```
outColor = ambient + Σ directional[0..dirCount) (Lambert)
         + Σ lightList[clusterIndices[cell 段]] (Lambert × 衰减)
衰减 = intensity / max(d², ε)，d ≥ range 处平滑归零（smoothstep 预滚降，避免硬边）
```

- **方向光全局段**直读 UBO 数组，不查 cluster；主光槽 0；
- 点光：像素重建视空间位置（vs 传出视空间坐标插值，v0.x 不走深度重建——深度重建归 P2）→ 定位 cell → 迭代索引段；
- SPOT：同点光 + 方向锥判定（cos inner/outer 平滑带）——批次③后半；
- **一致性**：Opaque/Transparent/（批次④）LightingPass 读同一套 set1 契约——正透明光照无差异（管线档 4.5 取舍消解，两处文案随批次④回填）；
- 材质钩子：着色入口由 ShaderInjector 注入的光照段提供（具体注入形态随批次③与 ShaderParser/Injector 联动拍板——旧 .shader 产物无需重导的兼容口径是目标，做不到则随批重导）；
- **注入红线（2026-10-31 用户拍板）**：**内置 `default.shader` 是 DefaultMaterial 兜底专用（缺材质/坏 shader 时的紫色错误显性化产物），禁止改造成注入光照段的样例**——光照注入的验证样例与用户面引导 = 新增内置 `Lit.shader`（命名拍板 2026-10-31，对标 HDRP 主受光着色器；随批次③落 builtin-asset-core；四期 BRDF 升级升其本体、名字不换），default.shader 的纯色语义维持不变；
- 内置光照 shader 的 .shader 源（vs 传插值视空间坐标、fs 留表面色接光照段）与注入段本身**不进 core**（着色器源码不进 core 拍板照旧）——源在 editor builtin-asset，注入段文本归 Injector/编译链；~~clusterBuild 的 compute spv 走 javelin-tool 内嵌链路（DebugText/pick 先例）~~ **已推翻（2026-10-31 用户拍板）：compute 着色器 = `.compute` 独立源码资产类型**（Unity 口径，扩展名区分）——ComputeShader 资产 + ComputeShaderImporter（shaderc COMPUTE 编译，无注入）+ 内置 builtin-asset-core/compute/；一文件一 kernel（GLSL 入口恒 main，多 kernel=多文件；Unity 式 #pragma kernel 多入口需 HLSL 源码改写机制，不值）；**DebugText/pick/DebugLine 的「GLSL 放 tool、spv 内嵌 core resources」路线同判废，迁源码独立批待派**。

## 8. 与管线的咬合

- **Pass 列表**：`ClusterBuildPass` 经管线档 6.2 有序列表插入（新增 compute 类型 Pass，图执行器扩展归 RHI 批次①）；LightingPass 输入改「set1 cluster 套件」（管线档 4.3 同步）；
- **PipelineContext 扩展**：`lightList / clusterGrid / clusterIndices`（与 gbuffer×3 / lightingRT / shadowMap 同批扩，管线档 6.1）；
- **MSAA**：cluster 构建与消费均与采样数无关（compute 全屏 1x；着色读数据非附件）——与 MSAA 分支正交，无新增路径；
- **视口资源表**：管线档 5.1 补三行（视口归属、随视口创建、resize 重建——与 depth 同生命周期口径）；
- **多视口**：每视口独立 cluster（编辑器 Scene/Game 双视口各自剔除各自构建）；共享 backbuffer 相机的 LOAD 叠加不受影响。

## 9. 实现路线（四批；批次①为 RHI 底座前置，原三期路线没有的新增段）

| 批 | 内容 | 产出/验收 |
|----|------|----------|
| ① RHI 底座 | ~~SSBO 实装 / ComputePipeline + dispatch / RenderGraph compute Pass 节点与 Buffer 读写声明同步~~ **✅ 已落地并验收关账（2026-10-31 当日用户实测）**——实勘校正：SSBO 描述符链/dispatch/双 bindPoint/compute 进图骨架一期已有（RHI 实装期产物），真缺口=①compute 管线布局装配（原空壳，setLayouts+pushConstantRange 补齐，stage=COMPUTE）②pushConstants stageFlags 按 bindPoint 分流（原写死 ALL_GRAPHICS）③Buffer 进图同步（PassDef addBufferRead/Write + 依赖推导（句柄取反命名空间防纹理撞键）+ structureHash + VkBufferMemoryBarrier 屏障计划（源/目标域按两端 Pass 类型现推））④storageBuffer range=0 VUID 坑修正（VK_WHOLE_SIZE）+offset/range 重载⑤删 VulkanPipelineLayout/createPipelineLayout 死代码（管线布局统一由 Pipeline 内建） | **验收形态终拍板（2026-10-31）：纯测试，编辑器不掺和**（同日早先"编辑器实机验收"路线被推翻——编辑器不演示流程验证件）：`.compute` 资产面（ComputeShader 资产类+ComputeShaderImporter+FileType/图标）+ set1 帧级 storage 通道（RenderingSystem registerFrameStorage binding1/2 对齐本档 §3.4 终态槽位，Opaque/Transparent 补绑+addBufferRead 声明）+ 测试件——core `ComputeSsboTest`（**批次①验收口**：双段 compute 链 A 写→B 读改写（compute→compute RAW 同步）+ 图形 Pass fs 读 SSBO（跨 Pass 同步）+ 帧 60 Buffer.read 读回 65536 元素逐元素 CPU 比对）+ editor `ComputeShaderImporterTest`（.compute→tool 编译链）——**两测试 2026-10-31 用户实测全过**（排障实录：动态 viewport 空集 AV 崩溃 + sin 区间归约精度差，Vulkan_Pitfalls #27/#28）；**通用性红线守住**：Buffer 声明/屏障为通用资源面，GI/RT 第一复用方地位不变 |
| ② 光数据模型 | Light 组件 + Scene lights 注册表 + 收集/剔除/预排序/视空间打包 + set1 布局与注入 | **✅ 已落地并验收关账（2026-10-31 当日用户实测：菜单挂灯三型+检查器编辑+getSun 换位+F3 灯数，单测全绿；随验收三笔拍板改向——isMain 推翻/getSun 自动裁决、灯族子类化、@InsHeader 子类显式贴法 + 图标彩色规则）**——`Light` 组件（Type/color/intensity/castShadow/range/锥角——**isMain 已删，主光自动裁决 v0.5 拍板**；.scene 通则序列化 + 检查器字段 + 双语包键/图标）+ `Scene.getLights/getSun`（getSun=最强激活方向光自动裁决；GameScene lights 注册表分流、read 重建；EditorScene 用户场景口径透传）+ SceneRenderBridge pull 翻译（Light→RenderLight 身份对应、isActiveAndEnabled 过滤、位姿/参数每帧同步）+ `LightCollector` 纯逻辑收集（方向光两遍收主光槽 0=强度排定 / 点聚光预排序（intensity/distSq）/ 视空间球剔除（自写，FrustumIntersection 对含 Y 翻转的反射矩阵不可信））+ RenderingSystem 帧收集打包（binding0 UBO 每视口动态偏移 + binding1 lightList SSBO 首视口口径 + 超容 WARN）+ ShaderInjector **fs 声明注入**（LightingParamsBlock + lightList，保留字生效）+ F3 overlay 行 8 在册灯数 + 菜单解锁（hierarchy.create.light 双型命令，Editor_Menu_Design v0.19）；单测 = `LightCollectorTest`（视空间打包/剔除/预排序/主光槽=强度排定/容量截尾）+ `GameSceneTest` 灯注册表/getSun 裁决用例；验收 = 单测全绿 + 用户挂灯实机 F3 灯数显示（着色消费归批次③——本批无画面验收项） |
| ③ cluster 构建 + Forward 消费（= Forward+ 成形） | ClusterBuildPass 三步 + 着色器光照段注入 + Lambert+环境项 + SPOT（后半） | 点光/方向光明暗正确（用户运行）；几十盏场景帧率稳；F3 帧时间不劣化超阈值 |
| ④ ShadowPass + Deferred 复用 | ShadowPass（管线档 4.1 兑现，**v0.x 单张硬影**——§4.5：无 PCF/无 CSM，rasterization 侧保守 bias；sunViewProj 段+binding4 落位）+ 注入段阴影采样分支 + GBuffer/LightingPass 上线（同一 cluster 直喂）+ 混合/纯 Forward 可配置切换 | 主光投影正确（单张硬影口径）；两路光照一致（同场景切换对比）；管线档 §8 步骤二验收口径 |
- 批次③收尾即拥有全质量光照（几十~几百盏无截断），Deferred 是第四批的另一个消费者，非光照质量前提；
- 后处理链（Bloom/Tonemap + HDR 切换点，管线档 4.6）不在本四批内，随批次④后按管线档步骤三独立推进。

## 10. 后置项与边界（2026-10-31 拍板：质量升级整体归四期——Roadmap §4 单点）

- **阴影质量分级**：PCF 软采样（注入段采样次数 × 邻域偏移）、CSM 级联（多 lightViewProj/级联选择/接缝处理）、深度 bias 精调、shadowCasters per-object 开关、stabilize 抖动抑制——**归四期第 3 项**（Roadmap §4）；
- 天空盒环境光照（天空盒作 ambient 源：SH9/mipmap 降采样，替换 §3.3 ambient 常量）——**归四期第 4 项**（过渡环境光，探针 GI 落地后并入第 5 项）；
- **GI 方向**：目标态 = 探针实时 GI（DDGI 路线）、烘焙管线不建、SSAO 为 GI 第一步——**归四期第 5 项**（Roadmap §4，探针复用本档批次①底座、求交后端可替换软光栅 → rayQuery；3D 纹理/纹理数组为采样前置缺口）；
- **光追方向**：rayQuery 优先、RT pipeline 后置——**归四期第 6 项**（Roadmap §4，缺口 = AS 资源种类/图状态、BDA、GL_EXT_ray_query 编译链、蒙皮 BLAS 首范围=静态几何；复用本档批次①通用性红线）；
- 脏标记跳过重建（相机/灯集合不变时不跑 ClusterBuildPass）；前缀和计算化（compute scan 替代 CPU）；cell 线性索引 Morton 化（局部性）；深度重建视空间位置；正交相机 Z 线性切分——clustered 后置项不设期，随四期批次需要（Roadmap §4 尾注）；
- **cluster 索引系统复用**：decals / 反射探针后续并入同一 cluster（Godot 4 启发——索引池语义泛化为「per-cell 可见物列表」时不重复建基建）；
- 光照 gizmo/调试显示（编辑器侧，DebugLine 光球线框 + 灯图标，随批次③后编辑器批次）；
- 着色模型升级（Lambert → BRDF）归材质/着色模型专档；tiled/clustered 的进一步分档（如 reducing Z 精度）随实测调参。

## 11. 文档衔接

| 概念 | 出处 | 关系 |
|------|------|------|
| set1 binding/key 单点 | `Engine_Uniform_Keys.md` §4/§6 | 本文 §3 契约的登记处（实装落位补字段行） |
| Pass 编排/插入/视口资源表 | `Engine_RenderPipeline_Design.md` 6.2/5.1 | ClusterBuildPass 插入点、三 SSBO 视口资源归属 |
| LightingPass/Forward Pass | 同上 4.3/4.5 | 输入改 cluster 套件；一致性取舍消解回填 |
| SSBO/compute API 设计 | `RHI_Design_Resources.md` / `RHI_Design_Core.md` | 批次①实装对象（设计稿已有 API 面，无实装） |
| Light 组件草稿/注册表 | `Engine_Scene_Design.md` 5.3/5.2 | 本文 §4 扩展其字段；收集随帧收集同域 |
| Lambert+环境项/一致性 | Roadmap 三期内容 3 | 拍板记录与消解方案归本文 §1 |

---

> （注：部分内容可能由 AI 生成）

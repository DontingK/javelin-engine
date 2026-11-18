# 引擎阴影质量分级设计（Engine Shadow Quality Design）

> 版本：v0.5（**步骤一 + 步骤二 CSM 全部落码并 2026-11-14 用户实机验收通过——P4-03 关账**）
> 日期：2026-11-14
> v0.5 变更（2026-11-14）：**步骤二 CSM 落码并用户实机验收通过**——`ShadowMath.buildCascades`（lambda 切分 + 逐级联球拟合方形盒 + 纹素吸附 + up 稳定轴 + 逐级联 zRange）+ `CascadeParams`（相机基向量自建）；`RenderingSystem.viewShadows` per-view 资源族（atlas，≥2 级联 2×2）+ `computeShadow(cam)` 逐视口级联拟合 + `shadowMapFor/cascadeCount/atlasGrid/cascadeMatrix` 访问器；`ShadowPass` 改**逐视口单 Pass**（一次 CLEAR + 回调内逐级联切 viewport/scissor 象限）；UBO 扩 `sunViewProj[4]`/`cascadeSplits`/`shadowParams2`/`cascadeInvZ`（块 416→**656B**、步长 512→**768**）；注入段级联选择 + atlas 象限 uv + PCF 象限夹取 + 相邻级联混合带；`ShadowSettings` 增 `cascadeCount`(2)/`shadowDistance`(100)/`cascadeLambda`(0.5) + 设置窗口三项。**步骤二使 §3.6 的 caster∪receiver 盒并集退役**（级联盒拟相机视锥，天然含接收者）。**P4-03 关账**（步骤一 + 步骤二全件）；余步骤三可选收尾。
> v0.4 变更（2026-11-14）：**一-C per-object 落码并用户实机验收通过**——`MeshRenderer.castShadow`/`receiveShadow` 补 `@InsField`（检查器可见）；`receiveShadow` 双档承载落地（FORWARD 走 push 空余位 @68、HYBRID 走 G-Buffer `albedo.a`，注入段 `javelinReceiveShadow()` 分变体、同一 `javelinLighting` 单源门控）。**随验两修**（两条坑，`Vulkan_Pitfalls.md` #39/#40）：① `RenderObject.castShadow` 换代时缓存 → 运行期切开关不生效、重启才生效（修=桥每帧 in-place 同步 castShadow/receiveShadow）；② 阴影正交盒只收 caster → 关地板投射致盒缩、立方体投影到地板的影子被盒外裁掉（修=盒体域改全体不透明对象 caster ∪ receiver + 1.0 外扩余量）。**步骤一全件关账**（一-A/-B/-C/-D 四件齐）。
> v0.3 变更（2026-11-14）：**用户实机验收通过**（「看起来没问题，我验收了」）——步骤一（品质档 PCF + bias 调参面 + 设置窗口）关账；已入 `Engine_Roadmap_Progress.md` §7 批次索引 + `Engine_Features.md` §2.4 验收状态，Backlog P4-03 余「一-C per-object + 步骤二 CSM」。**随验修**（§9.2 第三条）：注入段标量/向量乘错（`vec2` 当标量 → 5×5 档 `vec3 * vec2` 编译拒、3×3 档静默改义），已改标量 `texelSize`。
> v0.2 变更（2026-11-14）：**步骤一落码（一-A 品质档 PCF + 一-B bias 调参面 + 一-D 设置窗口；一-C per-object 未做）**——`ShadowQuality`/`ShadowSettings`（core settings）+ UBO 扩 `shadowParams`（实需 400→416B，步长 512 不变）+ 注入段 PCF（硬影单采样 / 4 tap÷16 复现 3×3 / 9 tap÷144 复现 5×5，Babylon 字面移植）+ 强度/参考深度偏移（`ShadowMath` 回填光空间 z 范围折算）+ `ShadowPass` bias 取设置并整批重建 PSO + 项目设置窗口「阴影」组 + 启动读盘/写盘 + 双语键；落点与一处口径细化见 §9。**未编译、未提交**（红线 1/13）。
> v0.1 变更（2026-11-14）：**方案件（待拍板）**——§3 步骤一（PCF 品质档 + bias 调参面 + per-object 开关）、§4 步骤二（CSM：lambda 切分 + 单张 2×2 atlas + per-view 阴影资源族 + 纹素吸附）、§5 边界、§6 派发粒度、§7 验收口径、§8 十一条拍板点。
> 说明：四期施工序 **②**（`Engine_Roadmap.md` §4-3 / `Engine_Backlog.md` P4-03「阴影质量分级」）的设计方案。前序 = ① SSAO（2026-11-14 验收关账）。
> 职责边界：
> - 本档管**阴影域**（PCF 软核与品质档、bias 调参面、per-object 阴影开关、CSM 级联、阴影设置面）；
> - 阴影**数据侧底座**（单张硬影形态五拍板、`sunViewProj` 语义、binding4 深度比较采样器、ShadowPass 覆盖制）归 `Engine_Lighting_Design.md` §4.5/§4.5.1——本档只在其上做增量，冲突处以本档为准并回填灯光档；
> - 通道字段（UBO 偏移 / 保留字）单点归 `Engine_Uniform_Keys.md` §4/§6，本档只登记「要加什么字段」；
> - 着色注入机制（`ShaderInjector` 两入口 / 保留字拦截）归 `Engine_Uniform_Keys.md` §4/§6 + `Engine_Material_Design.md` §4，本档只登记注入段内容；
> - 编辑器设置窗口骨架归 `Editor_ProjectSettings_Design.md`，本档只登记新增分组与字段；
> - **点/聚光阴影**（多灯阴影：Spot 透视矩阵 / Point cube 阴影）**不在本档范围**——见 §5.5 与 §8 拍板点 10；
> - 环境光（P4-04）与 GI（P4-05）不是本档范围——阴影只做直射光的可见性因子。

---

## 1. 范围与目标

**目标**：把三期批次④-A 的「单张硬影」升级为**可分级的阴影质量**——软影边缘、可控的 acne/彼得潘宁调参面、逐物体投射/接收开关、以及覆盖大场景的级联（CSM）。对标 Unity（`Editor_Design.md` §1 设计基准=完全参考 Unity）的 `Shadow Quality / Cascades / Shadow Distance / Shadow Projection` 设置口径。

**本期做**：

1. **步骤一（PCF 软核 + 品质档 + 调参面）**——注入段采样由单次比较采样扩为 4/9 tap 邻域核（MJP 亚纹素权重复现 3×3/5×5 高斯，`Babylon_Rendering_Reference.md` §4.1）；阴影品质档（硬影/软影低/软影高）、阴影强度、PCF 半径、bias 两项、参考深度偏移进设置；`MeshRenderer.castShadow/receiveShadow` 补检查器暴露并**落地 `receiveShadow` 语义**（现为死字段，§2.3）。**零新底座、零 RHI 改动**（UBO 扩 16B 仍在 512B 步长内）。
2. **步骤二（CSM 级联）**——lambda 混合切分（1/2/4 级联）、逐级联正交矩阵（球拟合方形纹素 + 纹素吸附稳定化）、阴影图承载切**单张 2×2 atlas**（零 RHI 改动）、级联选择 + 相邻级联 `mix`、阴影距离设置；`shadowMap` 由「全局单张」改**per-view 资源族**（CSM 依赖相机视锥，必须按相机各一），ShadowPass 由「帧号幂等全局」改**逐视口挂载**。
3. **步骤三（可选收尾，另行派发）**——阴影图 D16 格式档、PCSS（可变半径）、逐级联 caster 剔除、DBG-03 shadowMap 可视化（Backlog 在册）、多灯阴影（§5.5）。

**不做**（明确出范围）：烘焙阴影 / lightmap；点光、聚光阴影；阴影贴花（decals，归 cluster 索引复用项）；材质级受影（per-material receive，归材质档）。

---

## 2. 现状（代码实证，2026-11-14）

### 2.1 数据侧（`javelin-core`）

| 件 | 落点 | 现状 |
|----|------|------|
| 阴影图 | `RenderingSystem.shadowMap` | **全局单张** `TYPE_2D`、`D32_SFLOAT`（`depthFormat()`）、边长 = 主光组件 `shadowResolution`（缺省 2048，档 256~8192）、`DEPTH_STENCIL_ATTACHMENT \| SAMPLED`；`ensureShadowMap(resolution)` 按需重建 + 旧图两帧退役 + per-view set binding4 重指 |
| 采样器 | `RenderingSystem.shadowSampler` | 深度**比较**采样器：`LINEAR/LINEAR/LINEAR` + `CLAMP_TO_EDGE`×3 + `CompareOp.LESS`——LINEAR 比较 = 硬件 2×2 双线性平均（2026-11-01 实机「边缘块状锯齿」反馈换装，NEAREST 作废） |
| 阴影矩阵 | `computeShadow()` → `ShadowMath.buildSunViewProj` | 主光裁决（最强激活方向光）→ **全体 castShadow 不透明对象世界 AABB 并集**（取全局 `renderers`，不按视口 cullingMask 过滤 = 超集保守）→ 单张正交盒盖全场景（UP 稳定轴防退化，`zZeroToOne`，**Y 不翻**） |
| UBO 块 | `packLightingParams` | `LIGHTING_BLOCK_BYTES = 400`、`LIGHTING_STRIDE = 512`；`sunViewProj` 为单个 `mat4` @336（`Engine_Uniform_Keys.md` §4-1 单点表） |
| 通道 | set1 binding4 | `layout(set=1, binding=4) uniform sampler2DShadow shadowMap`——per-view set 各自指同一张图同一采样器 |

### 2.2 采样侧（`ShaderInjector.LIGHTING_BLOCKS`，javelin-editor 模块）

注入函数 `vec3 javelinLighting(vec3 viewNormal, vec3 viewPos, vec3 worldPos)` 主光段现状（`ShaderInjector.java:97-105`）：

```glsl
if (i == 0 && ndl > 0.0 && lighting.directional[0].w > 0.5) {
    vec3 proj = (lighting.sunViewProj * vec4(worldPos, 1.0)).xyz;
    if (全在 [-1,1] / [0,1] 界内) {
        vec2 uv = proj.xy * 0.5 + 0.5;
        shadow = texture(shadowMap, vec3(uv, proj.z));   // 单次比较采样
    }
}
```

- **单次采样**：软度全部来自硬件 2×2 比较双线性；无邻域 tap，无半径控制；
- **无强度/无偏置可调**：`shadow` 只能是 0/1（阶梯），采样参考深度无偏移项；
- **两路同源**：`Lit.shader` 的 `Pass Forward` fs 与全屏 `lighting.frag`（`//@javelinLighting` 注入）调用**同一个函数**——软影/级联只需改注入段一处，两档同时生效（这是本方案的最大杠杆）。

### 2.3 三个真实缺口（本方案要填的）

1. **无 PCF 邻域核**——3×3/5×5 软化、半影宽度控制都没有（批次④-A 明确「PCF 归四期」）。
2. **bias 无调参面**——`ShadowPass.pipelineFor` 里 `depthBiasEnable=true, constant 2.0, slope 1.5` 是**硬编码常量**（`ShadowPass.java` 注释：「实测漏影/彼得潘宁再调」）；改值必须改代码重编。RHI 侧 `GraphicsPipelineDesc` 有静态 bias 三参，但 `CommandBuffer` **没有** `vkCmdSetDepthBias` 口（`DynamicState.DEPTH_BIAS` 枚举存在但无实现）→ 运行期调参只能靠 PSO 重建。
3. **`receiveShadow` 是死字段**——`MeshRenderer` 有 `castShadow`/`receiveShadow` 两字段（均序列化），`castShadow` 已被 `collectList` 消费（`isCastShadow() && !isTransparent()` 进 `shadowCasters`），但 **`receiveShadow` 全仓零消费者**（只有存取器与序列化），且**两个字段都没有 `@InsField`**——检查器里根本看不到。

### 2.4 CSM 相关缺口

- 相机基向量/视锥角点无现成工具（`RenderCamera` 只给 `view/proj/viewProj/position/target/up/fovY/nearZ/farZ/viewport 尺寸`）；`proj.m11(-proj.m11())` 使 `viewProj` 含 **Y 翻转**（反射矩阵），`FrustumIntersection` 口径不可信（`LightCollector` 自写剔除先例）→ 级联拟合须**自建基向量**（避 JOML 提轴坑）；
- RHI `TextureType` **无 `TYPE_2D_ARRAY`**（仅 `TYPE_1D/2D/3D/CUBE/CUBE_ARRAY`），且无「渲染到某一层」的 per-layer 视图口 → Babylon 式「单张分层 RTT」需新增 RHI 底座；
- `shadowMap` 是**全局单张**、`ShadowPass` 以帧号幂等**全局挂载一次**（多视口共享）——CSM 后级联依赖各相机视锥，此形态必须改。

---

## 3. 步骤一：PCF 软核 + 品质档 + 调参面（零新底座）

### 3.1 品质档划分（对标 Unity Shadow Quality）

| 档 | 采样 | 说明 |
|----|------|------|
| `HARD` 硬影 | 1 tap | 与现状等价（硬件 2×2 比较双线性仍在）；严格硬影需 NEAREST 比较采样器，**不做**——避免「档位换采样器对象」的耦合（§8 拍板点 1） |
| `SOFT_LOW` 软影（低） | **4 tap** | MJP 技法：4 次采样 + 解析权重复现 3×3 高斯（Babylon `shadowsFragmentFunctions.fx:382`）；**建议默认档** |
| `SOFT_HIGH` 软影（高） | **9 tap** | 9 次采样复现 5×5 高斯（同文件 `:443`）；比朴素 25 tap 省 60%+ |

> 分辨率档不在此列——已有每灯 `Light.shadowResolution`（`@InsShadowResolution` 预设档 256~8192，批次④-A 落地）。

### 3.2 参数面：`ShadowSettings`（core `@Reflective Serializable`，挂 `JavelinSetting`）

照 `PostProcessSettings` / `SsaoSettings` 先例（嵌套对象保「缺键默认」，浮点无 null 哨兵）：

| 字段 | 语义 | 建议默认 | 生效期 |
|------|------|----------|--------|
| `quality` | 品质档（HARD/SOFT_LOW/SOFT_HIGH，枚举；`renderPipeline` 字段枚举先例） | `SOFT_LOW` | 即时（走 UBO 或重编 PSO 见下） |
| `strength` | 阴影强度 0~1（`shadow = mix(1, shadow, strength)`，0 = 视觉等同关影） | `1.0` | 即时（UBO） |
| `pcfRadius` | PCF 半径（**纹素**单位，偏移步长缩放） | `1.0` | 即时（UBO） |
| `refDepthOffset` | 参考深度偏移（**世界单位**，shader 侧；正交光空间里折算为 z 增量） | `0.0` | 即时（UBO） |
| `biasConstant` / `biasSlope` | PSO 静态 depth bias（现值 2.0 / 1.5 提为设置） | `2.0` / `1.5` | **改档重建 PSO**（ShadowPass 清缓存） |
| （步骤二）`cascadeCount` / `shadowDistance` | 级联数 / 阴影距离 | `2` / `100` | 重启或重建资源族 |

**下发路由**（不新增接线）：`JavelinEngine.createRenderPipeline` 启动期把设置灌进 `RenderingSystem`（`setShadowSettings`，同 `postProcessSetup.setSsaoParams` 先例）→ `packLightingParams` 每帧打包 UBO → `ShadowPass` 每帧经 `ctx.renderingSystem()` 读 bias 快照（**变化即清 `pipelines` 缓存重建**）。

### 3.3 UBO 扩块（步骤一：+16B，步长不变）

`LightingParamsBlock` 末尾追加 `vec4 shadowParams`（@400，实需 **416B ≤ 512 步长** ✓ 无需改 `LIGHTING_STRIDE`）：

| 分量 | 语义 |
|------|------|
| `.x` | tap 档（0=硬影单次 / 1=4 tap / 2=9 tap） |
| `.y` | PCF 半径（纹素） |
| `.z` | 阴影强度（0~1） |
| `.w` | 参考深度偏移（已折算的 z 增量 = `refDepthOffsetWorld / (2·ez + 2·BOX_MARGIN)`，CPU 侧算好） |

> 纹素尺寸**不进 UBO**：注入段用 `1.0 / vec2(textureSize(shadowMap, 0))` 现取（自动适配分辨率档；步骤二 atlas 下改按象限参数，见 §4.6）。

### 3.4 PCF 采样形态（注入段改造要点）

1. **tap 循环 + 解析权重**：按 `fract(uv / texel)` 亚纹素位置解析算 4/9 tap 的权重组并归一化（MJP，Babylon §4.1；权重表在 GLSL 里就地展开，不做纹理/常量表）；
2. **逐 tap 界检查**：每个 tap 各自做盒界检查（越界 tap 按「受光 1.0」计权），避免盒边缘硬切；
3. **斜射补偿**：PCF 有效半径按 `1 + k·(1 - ndl)` 放大（半影随入射角增大，同时掩盖斜射面自遮挡）——`k` 起步给保守值，**归实机调参暴露点**（红线 2/5：无经验值盲写）；
4. **参考深度偏移**：`refZ += shadowParams.w`（正交光空间里常数偏移 = 常数世界距离，语义干净），替代「法线偏移」方案——法线偏移需要**世界法线**，而注入函数只拿视空间法线（拿世界法线要再改三参签名 = 契约变更），步骤一不做（§8 拍板点 4）；
5. **强度**：`shadow = mix(1.0, shadow, shadowParams.z)`。

> **注入器改动 = 契约变更**：注入段文本一变，已编译 `.shader` 产物必须**强制重导**（`Engine_Uniform_Keys.md` §4 口径 + 工程 `.javelin/assets` 副本走内容比对），批次④-A 已有先例。

### 3.5 bias 承载（不动 RHI）

- **PSO 静态 bias 保留**为「斜率主导」抗 acne 项（`biasConstant`/`biasSlope` 进设置，改档即清 `ShadowPass.pipelines` 缓存重建 PSO——每 mesh layout 一次，非每帧）；
- **shader 侧参考深度偏移**（§3.4-4）即时生效、无需重建，承担「彼得潘宁↔漏影」的细调；
- **不引入 RHI 动态 bias**：`CommandBuffer` 无 `setDepthBias` 口，补它属 RHI 增量（`DynamicState.DEPTH_BIAS` 枚举已在，实现成本小但与本批收益不成比）→ §8 拍板点 5，若认可将来要平滑拖动调参再补（记 Backlog）。

### 3.6 per-object 阴影开关（把死字段盘活）

**`castShadow`**：逻辑已通（收集侧过滤 + AABB 并集同口径），只差**检查器暴露**——`MeshRenderer.castShadow/receiveShadow` 补 `@InsField` + 双语键。

**`receiveShadow`**（现为零消费者）落地承载——按渲染档分两路（**同一注入段，靠注入变体取标志**）：

| 档 | 承载 | 理由 |
|----|------|------|
| FORWARD | **材质 push 扩 1 个 float 位**（`PushBlock { mat4 model; float tpf; }` = 68B，`PUSH_SIZE=80` 有 12B 空余）——注入段直接读已注入的 `pc` | 材质 fs 是逐物体调用，push 天然 per-draw |
| HYBRID | **G-Buffer 备用通道 `outAlbedo.a`**（现恒写 1.0、全仓无消费者）——注入段在全屏变体里用 `gl_FragCoord / textureSize(gbufferAlbedo,0)` 取回该像素标志 | 全屏 LightingPass 无物体身份，逐像素标志只能经 G-Buffer 传 |

- 注入器两入口本就分叉（材质 fs 注入 `LIGHTING_BLOCKS`；全屏注入 `injectLightingStage` 附 `GBUFFER_BLOCKS`），按变体填不同的 `receiveShadow` 取法**不新增第三条路径**；
- G-Buffer 内容契约变更（albedo.a 语义）需回填 `Engine_RenderPipeline_Design.md` §4.2 / `Engine_Material_Design.md` §4.2（`Pass GBuffer` 契约）；
- 若步骤二 BRDF 升级将来要用 albedo.a 承载别的量，本标志改挪 `outNormal.a`（RGBA16F，0/1 精确可表）——**归拍板点 3**。

### 3.7 编辑器设置面

`ProjectSettingsWindow`「图形」页新增「阴影」组（骨架与配色收口复用，间距守 ≤2px）：品质档下拉 + 强度 + PCF 半径 + 参考深度偏移 + bias 常量 + bias 斜率；改动即时生效（除 bias 两项走 PSO 重建，仍在关窗写盘前）、关窗 load-modify-save 写 `<project>/Game/javelinSettings.settings`、`ProjectLoadingFrame` 启动读入、i18n 双语键。

---

## 4. 步骤二：CSM 级联

### 4.1 切分与阴影距离（对标 Unity Cascades / Shadow Distance）

- `shadowDistance`（设置；缺省建议 100 世界单位）→ `farSplit = min(camera.farZ, shadowDistance)`；**超出距离恒受光**（采样直接返回 1.0，与现状盒外语义一致）；
- 级联边界 = **lambda 混合**（Babylon §4.2：`lambda·log + (1-lambda)·uniform`）：
  `d_i = lambda·near·(far/near)^(i/N) + (1-lambda)·(near + (far-near)·i/N)`，`lambda` 缺省 `0.5`；
- 级联数 **1 / 2 / 4**（Unity 口径；`1` = 退化为单张，与现状形态同源 ✓ §8 拍板点 9）；
- 切分距离写入 UBO（`cascadeSplits` 视空间 z），**采样侧按 `-viewPos.z` 选级联**——两档都拿得到（FORWARD 的 `viewPos` 由 vs 插值、HYBRID 的 `viewPos` 由 `cam.view × worldPos` 现算）。

### 4.2 逐级联矩阵（`ShadowMath` 扩展，纯逻辑 + 单测）

新增 `ShadowMath.buildCascades(camBasis, near, far, lambda, cascadeCount, out[])`（照现有 `buildSunViewProj` 风格、纯 JOML 无 GPU）：

1. **相机基向量自建**（避开 `viewProj` 的 Y 翻转与 JOML 提轴坑）：`forward = normalize(target - position)`、`right = normalize(cross(forward, up))`、`upn = cross(right, forward)`——与 `LightCollector` 自写剔除同口径；
2. **每级联 8 角点**：由 `fovY` + 视口 aspect（`viewportWidth/Height`）与近远面向量算出（透视：`tan(fovY/2)`；正交：`orthographicSize`×aspect）——**不反投影 `proj`**；
3. **球拟合 + 方形纹素**：角点包围球 → 以球半径做方形正交盒（`ex = ey = r`），**避免各向异性纹素**（质量档语义才稳定）；
4. **纹素吸附（stabilize）**：光空间盒中心量化到纹素网格（Unity `Shadow Projection = Stable` 口径）——抑制相机旋转/移动时的阴影边缘抖动。**吸附网格步长必须 = 最终盒宽的渲染纹素**（`2·half/resolution`，half 含安全边）——吸附跳变时世界纹素网格恰平移一整纹素、重光栅化逐纹素等同，平移相机零跳动；安全边 = 吸引位移上界（半纹素对角 `texel·√2/2`），盒按此扩保覆盖（2026-11-18 订正：原实现步长用 `2r/res` 而盒宽 `r+1.0`（绝对世界单位），跳变平移 ≈0.97 渲染纹素非整数——相机移动时阴影边缘明显跳动，实机坐实后修）；
5. **up 稳定轴**：沿用现有 `UP_SWITCH` 防退化分支；
6. 近远面收紧（可选，Babylon `depthClamp` 口径）：按光空间 z 包围收紧 `near/far`，提高深度精度——**实机若见 acne 再启用**（保守起步）。

> 单测照 `ShadowMathTest` 现例：切分单调性/lambda 端点、8 角覆盖（级联盒必须包含对应角点）、亚纹素位移吸收、**吸附跳变 = 整数渲染纹素**（2026-11-18 补：`snapJumpEqualsWholeRenderTexel`，相机步进扫边界断言 uv 位移×resolution 为整数——原实现必挂的核心不变量）、up 换轴分支。

### 4.3 阴影图承载（三选一，**建议 atlas**）

| 方案 | 内容 | 代价 | 结论 |
|------|------|------|------|
| **A. 单张 2×2 atlas** | 一张 2D 深度图切四象限（级联数 1→`r×r`、2→`2r×r`、4→`2r×2r`）；每象限一个 depth-only Pass（viewport/scissor 定位，象限 0 `CLEAR`、其余 `LOAD`）；采样按级联映射 uv 并**内缩半纹素**防跨象限双线性渗漏（PCF 邻域同样 clamp 在象限内） | 单级联有效分辨率不变（用户档位语义不变），显存 ×级联数（2048 档 4 级联 = 4096² D32 ≈ 64MiB/相机）；binding4 与采样器**零改动** | ✅ **推荐**（零 RHI 底座，与「①~⑤ 零新底座」口径一致） |
| B. RHI 新增 `TYPE_2D_ARRAY` | Babylon 式单张分层 RTT（`layers = cascadeCount`）+ `sampler2DArrayShadow` | 需新增纹理类型 + **per-layer 渲染视图**（渲染到某一层）+ 采样视图类型分支 ⇒ RHI 底座先行 | ⛔ 本批不做（记 Backlog：与点光 cube 阴影/探针 3D 纹理同族需求，届时统一补） |
| C. 每级联独立 `Texture2D` | 4 张独立图 + set1 扩 binding8~11 | 采样需 4 路分支，相邻级联混合要同时访问两张图 ⇒ 注入段膨胀 | ⛔ 否 |

> atlas 与 `sampler2DShadow` + `CLAMP_TO_EDGE` 的组合成立：比较采样的双线性只在**象限内**需要，内缩 + 邻域 clamp 即可（半纹素可用面积损失可接受）。

### 4.4 per-view 资源化（结构性变更，须记明）

CSM 的切分/盒拟合依赖**各相机自己的视锥**，故：

- `RenderingSystem.shadowMap`（全局单张）→ **`shadowMapsFor(cameraName)` 资源族**（照 `depthFor` / `bloomRtFor` / `ssaoRtFor` 同模式：惰性创建、分辨率/级联数变化重建、`removeCamera` 两帧退役、`close` 释放、句柄进 `structureHash`）；
- `computeShadow()` 由「帧首全局一次」→ **逐视口**（各相机各自的主光裁决结果一致、视锥角点不同 ⇒ 各级联盒不同）；
- `ShadowPass` 挂载由「**帧号幂等、全局一次**」（`Engine_Lighting_Design.md` §4.5.1 拍板项）→ **逐视口挂载**（每个视口挂自己的一组级联 Pass，名字 `Shadow{0..N-1}`）；per-view set binding4 已按视口存在 ✓ 各指自己的图；
- **双视口显存 ×2**（编辑器 Scene + Game）——§8 拍板点 8（建议统一，行为一致优先；备选=仅主渲染相机级联、其余视口退化单张）。

### 4.5 采样与级联选择（注入段）

```glsl
// 选级联：第一个 splits[i] >= -viewPos.z 的 i（超出 shadowDistance → 恒受光）
// uv = 象限映射(proj.xy) + 半纹素内缩；refZ = proj.z + shadowParams.w
// 相邻级联混合：diff < blendZone 时同时采 i 与 i+1，按 diff/blendZone 比例 mix
```

- 混合带比例缺省 `0.1`（Babylon `cascadeBlendPercentage`），承担级联接缝软化；
- 混合区**只对非末级联生效**（末级联无下一张）；
- PCF 核与强度/偏移逻辑与步骤一共用（同一段代码，级联只是「换成第 i 张矩阵 + 象限 uv」）。

### 4.6 UBO 改造（步骤二：步长必须扩）

| 字段 | 变化 |
|------|------|
| `mat4 sunViewProj` | → **`mat4 sunViewProj[4]`**（@400，256B；级联数不足时未用槽写现值 = 单张矩阵，跌级安全） |
| `vec4 cascadeSplits` | 新增（@656，视空间 z 切分；`.w` 备用） |
| `vec4 shadowParams2` | 新增（@672：象限 uv 缩放 u/v、纹素 u/v——atlas 采样与 PCF 用） |
| `vec4 shadowParams` | 保留（§3.3 语义） |

- 实需 ≈ **688B** → `LIGHTING_STRIDE` **512 → 768**（256 对齐 3 倍）、`LIGHTING_BLOCK_BYTES` 同步；缓冲 = `frameSlots × MAX_CAMERAS × stride` 随之 ×1.5；
- `Engine_Uniform_Keys.md` §4-1 字段表须整表同步（偏移全变）。

### 4.7 ShadowPass 改造

- 每视口为每级联挂一个 depth-only Pass；push 仍 128B（`lightViewProj@0` 换第 i 张矩阵 + `model@64`），**PSO 不变**（viewport/scissor 已是动态态 ✓，同一 PSO 复用）；
- caster 集：v1 每级联**全量绘制**（超集保守、正确优先）——逐级联剔除（按级联视锥/距离）归**步骤三**性能项；
- 恒清空语义保持（级联数变化/无主光时只清不画）。

### 4.8 设置面追加

级联数（1/2/4）+ 阴影距离 + `lambda`（可选暴露）——即时生效 = 资源族重建（旧图两帧退役，同 `ensureShadowMap` 现例）。

---

## 5. 边界与交互

### 5.1 FORWARD 档

同一注入函数 ⇒ **PCF/CSM 两档同时生效**（无需第二套实现）；差异只有 §3.6 的 per-object 标志承载（push vs G-Buffer 通道）。FORWARD 档仍不支持 SSAO 等 G-Buffer 后处理（既有口径不变）。

### 5.2 透明物 / 覆盖制 Pass

- 透明物**不投影**（现口径：收集侧 `!isTransparent()`）、**接受阴影**（前向 fs 采样照常）——不变；
- Gizmo / 调试线 / 拾取 / G-Buffer 调试视图走覆盖制、不消费注入函数 ⇒ 不受影响；
- SSAO 是**后处理**（乘在已含阴影的场景色上）⇒ 与阴影顺序语义无冲突。

### 5.3 图依赖与图缓存

`Opaque/Transparent/LightingPass addTextureRead(shadowMap)` 恒声明（建 Shadow→消费边 + 句柄进 `structureHash`）——atlas 化后仍是单张图 ⇒ 依赖声明形态不变；per-view 后按视口各自声明。沉入的 `sunViewProj[4]`/splits 变化不进 `structureHash`（UBO 内容不在哈希口径内，现状同）。

### 5.4 性能与显存（诚实列账）

| 项 | 增量 |
|----|------|
| fs 采样次数 | 4 tap（软影低）/ 9 tap（软影高）逐像素 × 方向光；HDR 全屏 Pass 与材质 fs 两路 |
| draw call | CSM 4 级联 = ShadowPass 绘制 ×4（v1 不逐级联剔除） |
| 显存 | atlas：2048 档 ×4 级联 ≈ 64MiB/**相机**；1 级联与现状等价 |
| UBO | 每视口块 400B → 416B（步骤一）/ ≈688B（步骤二），步长 512 → 768 |

### 5.5 范围外（本档不做，建议登记）

- **点/聚光阴影**（Spot 透视矩阵 + Point cube 阴影，含 `shadowResolution` 基类占位已备）——现状口径「点光无影=设计内」，本档不扩；建议登记 **Backlog P4-11**（§8 拍板点 10）；
- PCSS / ESM / VSM（Babylon §4.1 过滤器族）；阴影贴花；烘焙阴影。

---

## 6. 实施步骤（派发粒度，逐步验收）

| 步 | 内容 | 验收面 |
|----|------|--------|
| **一-A** | `ShadowSettings`（core）+ UBO `shadowParams` 扩块 + 注入段 PCF（4/9 tap + 逐 tap 界检查 + 强度 + 半径）+ 参数下发 | 画面：软影边缘可见、强度/半径即时生效；硬影档与现状逐像素一致 |
| **一-B** | bias 面：PSO 静态两项提为设置（改档清缓存重建）+ shader 侧参考深度偏移 | 调 bias 能消除漏影/彼得潘宁，无需改代码 |
| **一-C** | per-object：`castShadow`/`receiveShadow` 补 `@InsField` + `receiveShadow` 双档承载落地 + G-Buffer 契约回填 | 检查器可切；关接收的物体不受影（双档一致） |
| **一-D** | 设置窗口「阴影」组 + i18n 双语 + 写盘/启动读 | 关窗重开/重启保持；`logs/error` 零 ERROR |
| **二-E** | `ShadowMath.buildCascades` 纯逻辑 + 单测（切分/球拟合/吸附/up 稳定） | 单测全绿（不涉 GPU） |
| **二-F** | per-view shadow 资源族 + ShadowPass 逐视口多级联化 + atlas 承载（象限 Pass/uv 映射/内缩） | 单张（级联数 1）与现状等价；图缓存正确失效 |
| **二-G** | 采样侧级联选择 + 相邻 mix + 设置面（级联数/距离）+ 验收 | 近处细节提升、接缝无硬线、超距离受光 |

---

## 7. 验收口径（汇总）

**步骤一**：

- 默认档（软影低）+ 默认参数：软影边缘、无阶梯；品质档切硬影 = 与三期基线逐像素一致；
- 强度 0 = 视觉等同关影；PCF 半径/参考深度偏移即时生效；bias 两项改动后漏影/彼得潘宁可消除；
- per-object：关 `castShadow` 的物体不投影、关 `receiveShadow` 的物体不接影（FORWARD / HYBRID 一致）；
- 双视口各自正确不串；Gizmo/调试线/拾取/调试视图不受影响；重开窗口与重启设置保持；`logs/error/error.log` 零 ERROR（dbg-1 已就绪）。

**步骤二**：

- 级联数 1 与步骤一形态一致；2/4 级联近处细节提升、远处不劣化；
- 相机旋转/移动时阴影边缘**不抖动**（纹素吸附生效）；级联接缝无可见硬线（混合带生效）；
- 超 `shadowDistance` 区域恒受光（无阴影断层）；级联数/距离改动即时生效（资源族重建、旧图两帧退役）；
- 双视口各自级联；Scene + Game 切换一致；`logs/error` 零 ERROR。

> 有疑点别硬猜——渲染态问题按 `AGENTS.md`「RenderDoc 抓帧」口径要抓帧请求（本例典型：级联盒矩阵、atlas 象限内容、深度比较采样器状态）。

---

## 8. 拍板点（开工前逐项确认）

| 序 | 事项 | 建议 |
|----|------|------|
| 1 | 品质档划分与默认档 | 硬影(1 tap) / 软影低(4 tap, 3×3) / 软影高(9 tap, 5×5)；**默认软影低**；硬影档**不**换 NEAREST 采样器 |
| 2 | 阴影强度的作用域 | **全局设置**（对标 Unity URP 的 per-light strength 属另一维度，后置） |
| 3 | `receiveShadow` 承载 | FORWARD = push 空余 float；HYBRID = **G-Buffer `albedo.a`**（备选 `normal.a`）；若认可「本批只做 castShadow 暴露、receiveShadow 后置」也可 |
| 4 | 是否引入 shader 侧参考深度偏移 | **引入**（正交光空间常数偏移，替代需改签名的法线偏移） |
| 5 | bias 调参方式 | **PSO 静态 + 改档重建缓存**（不动 RHI）；`vkCmdSetDepthBias` 动态化记 Backlog |
| 6 | CSM 承载 | **单张 2×2 atlas**（零 RHI 改动）；`TYPE_2D_ARRAY` 记 Backlog |
| 7 | 级联数 / 阴影距离默认 | 级联数 **2**、距离 **100**（编辑器双视口成本折中；数值归实机调参暴露点） |
| 8 | 双视口是否都上 CSM | **都上**（行为一致优先）；备选：仅主渲染相机级联、其余退化单张 |
| 9 | 级联数 1 是否保留为档 | **保留**（退化为现状单张，验收基线天然存在） |
| 10 | 点/聚光阴影是否登记 Backlog | **登记 P4-11**（本档不做，避免需求悬空） |
| 11 | PCF 斜射半径放大系数 `k` / 各默认值 | 起步保守值，**实机调参暴露点**（红线 2/5，不盲写经验值） |

---

## 9. 与邻档衔接

| 概念 | 出处 | 关系 |
|------|------|------|
| 单张硬影形态五拍板 / 数据侧底座 | `Engine_Lighting_Design.md` §4.5/§4.5.1 | 本档的起点；§4.4「全局幂等挂载」在本档步骤二被 per-view 取代（须回填） |
| 通道字段与保留字 | `Engine_Uniform_Keys.md` §4/§6 | `shadowParams` / `sunViewProj[4]` / `cascadeSplits` / `shadowParams2` / 步长 768 落此表 |
| 注入机制与两入口 | 同上 §4 + `Engine_Material_Design.md` §4 | 注入段内容归本档，机制不动；改动=强制重导 |
| G-Buffer 内容契约 | `Engine_RenderPipeline_Design.md` §4.2 / `Engine_Material_Design.md` §4.2 | `albedo.a` 语义变更（步骤一-C）须同步 |
| 设置窗口骨架 | `Editor_ProjectSettings_Design.md` | 只登记新增「阴影」组与字段 |
| 阴影可视化（DBG-03） | `Engine_DebugTools_Plan.md` §3.3 / Backlog DBG-03 | CSM 后更有价值（级联分色/级联盒可视化）——步骤三 |
| CSM / PCF 参考 | `Babylon_Rendering_Reference.md` §4.1/§4.2 | 采样技法（MJP 4/9 tap）、切分公式、纹素吸附、混合带比例 |
| 排障 | `Vulkan_Pitfalls.md` §六-7（RenderDoc 抓帧） | 级联/象限/采样器状态类问题按抓帧口径处理 |

---

## 9. 实装记录（步骤一-A/-B/-D，2026-11-14）

> 用户「动手」= 按 §8 建议默认执行 → 本批落 **一-A（PCF 品质档）+ 一-B（bias 调参面）+ 一-D（设置窗口）**；
> **一-C（per-object `castShadow`/`receiveShadow`）本批未做**（G-Buffer 内容契约变更独立成批，见 §9.4）。

### 9.1 落点（代码实证）

| 件 | 落点 | 形态 |
|----|------|------|
| 品质档枚举 | `core/settings/ShadowQuality` | `HARD(0)` / `SOFT_LOW(1)` / `SOFT_HIGH(2)`；`code()` = UBO `shadowParams.x` 编码（**枚举序即编码**，改序须同步注入段） |
| 参数对象 | `core/settings/ShadowSettings`（`@Reflective Serializable`） | 六字段：`quality`（缺省 SOFT_LOW）/ `strength`（1.0，钳 0~1）/ `pcfRadius`（1.0，钳 ≥0）/ `refDepthOffset`（0.0，世界单位）/ `biasConstant`（2.0，钳 ≥0）/ `biasSlope`（1.5，钳 ≥0）；`read` 的 `quality` 走 `readEnum` + null 保默认，浮点按既有协议 |
| 设置承载 | `JavelinSetting.shadowSettings` | 嵌套对象（缺键保默认，`readSerializable` null 判定）+ `write/read` 各一行 |
| UBO 扩块 | `RenderingSystem.LIGHTING_BLOCK_BYTES` 400 → **416**、`packLightingParams` 尾段 | `shadowParams` = (品质档编码, PCF 半径纹素, 阴影强度, 参考深度偏移折算的裁剪 z 增量)；**步长 `LIGHTING_STRIDE` 512 不变**（416 ≤ 512） |
| 参考深度偏移折算 | `ShadowMath.buildSunViewProj(..., float[] zRangeOut)` 新重载（3 参委托）+ `RenderingSystem.shadowZRange` | 光空间 z 范围 = `2·ez + 2·BOX_MARGIN`；`shadowParams.w = refDepthOffset / zRange`（正交光空间常数 z 偏移 = 常数世界距离）；未生效/退化时钳 `1e-4` 下限 |
| 下发路由 | `RenderingSystem.shadowSettings()` / `setShadowSettings(...)`（null 拒绝） | 启动期 `JavelinEngine.createRenderPipeline` 灌入（`setting.getShadowSettings()`）；运行期设置窗口改写 |
| PSO bias | `ShadowPass.ensurePipelineBias(rs)` + `builtBiasConstant/builtBiasSlope` | 每帧录制回调首比对设置，变化即**整批退役重建**（PSO 是创建态，RHI 无 `vkCmdSetDepthBias`）；旧管线走**新提为 public 的** `RenderingSystem.retireResource`（stamp + 2 帧，GPU in-flight 安全） |
| 注入段 | `ShaderInjector.LIGHTING_BLOCKS` | ①块尾加 `vec4 shadowParams;`；②新增 `float javelinShadowTap(vec2,float)`（越界按受光 1.0，软核不硬切）；③采样分支：硬影档单次比较 / `pcfMode==1` 4 tap `÷16` 复现 3×3 / 否则 9 tap `÷144` 复现 5×5（**Babylon `shadowsFragmentFunctions.fx:382/443` 字面移植**，MJP 亚纹素权重），核偏移按 `pcfRadius` 缩放；④`shadow = mix(1.0, pcfShadow, strength)`；⑤参考深度 `proj.z + shadowParams.w` |
| 编辑器设置面 | `ProjectSettingsWindow`「阴影」组 | 品质档 `JeSelect`（选项 label 走语言包）+ 强度/PCF 半径/参考深度偏移/bias 常量/bias 斜率 五个 `numberInput` + 静态态提示行；`applyShadowSettings` 即时下发；`persist()` 增 `setShadowSettings`；`ProjectLoadingFrame.loadEditorSetting` 启动读入 |
| i18n | `zh_CN.properties` / `en_US.properties` | 新增 12 键（组标题 / 品质档三项 / 五个字段名 / 静态态提示） |
| 单测 | `JavelinSettingTest`（3 例）、`ShadowMathTest.zRangeMatchesOrthoDepth`、`ShaderImporterTest.lightingStageInjectsPcfShadowStage` | 设置往返 + 枚举全取值 + 旧档默认；z 范围回填（盒心落 z=0.5）；注入段静态断言（`shadowParams`/`javelinShadowTap`/`÷16`/`÷144`/强度 mix）；**`litShaderEndToEnd` 走 shaderc 真实编译注入后的 fs**——GLSL 语法/类型错误在该测试现形 |

### 9.2 一处口径细化（记明不静默）

**核对 MJP 权重可解析还原 3×3 高斯**：`st=0`（采样点正落纹素中心）时 4 tap 退化为
`u = -1/3, +1`、权重 `3, 1`；硬件双线性把 `-1/3` 拆成 `1/3·T(-1) + 2/3·T(0)`，
合并得每轴 `3 : 6 : 3`（÷16 → `3/16 : 6/16 : 3/16`），双轴外积 = `(1,2,1)²/16` ✓
——与 Babylon 注释声明的 3×3 分布一致，故按字面移植可信（5×5 档同源、未逐点验算）。

**PCF 半径是「核偏移缩放」而非重算权重**：`pcfRadius > 1` 时 tap 不再落在相邻纹素上，
权重仍用 MJP 解析值——这是标准的「软影半径」旋钮（半影变宽、精度下降可接受），
归实机调参暴露点（§8 拍板点 11）。

**随实机导入修：核偏移乘的是标量「1/边长」，不是 `vec2(1/size)`**。首版把 Babylon 的
`* shadowMapSizeAndInverse.y`（**标量**）写成 `* texel`（`vec2`）——3×3 档两个 `vec2 * vec2`
恰好合法故未报，5×5 档的 `vec3 * vec2` 被 shaderc 拒（`Forward.frag:92/95` 两行，
用户首跑导入即现形）。修法 = 命名 `float texelSize = 1.0 / texSize.x`（**阴影图为正方形**，
x/y 纹素尺度同值；步骤二 atlas 后按象限改传显式缩放参数），`base`/`u`/`v` 全改标量乘。
**教训**：Babylon 式的标量/向量混用别照抄成向量——glsl 标量广播方向合法性和「向量×向量
逐分量」语义不同，编译器只在维度不匹配时报错，维度凑巧匹配时**静默改义**。

### 9.3 验收口径（步骤一-A/-B/-D，用户实机）

- **默认档（软影低）**：阴影边缘出现半影、无块状阶梯；品质档切**硬影**与三期批次④-A 基线视觉等价；
- 强度 1→0：阴影渐隐至「视觉等同关影」（遮挡处保留 `1-strength` 亮度）；
- PCF 半径 0→4：半影变宽（0 = 单采样）；参考深度偏移：消除「漏影 / 彼得潘宁」细调；
- bias 常量/斜率改动：下一条日志出现 `shadow PSO 重建`、画面即刻变化（旧管线两帧退役，无卡顿/花屏）；
- 双视口（Scene + Game）各自正确、不串；Gizmo / 调试线 / 拾取 / G-Buffer 视图不受影响；
- 关窗重开 / 重启编辑器：六项参数保持（写盘链）；`logs/error/error.log` 零 ERROR（dbg-1 已就绪）。

> **注入器改动 = 强制重导**：`ShaderInjector` 文本一变，已编译 `.shader` 产物（内置 Lit/default + 工程副本）须重导——启动期内置资产释放器按**内容比对**触发重导；旧产物不重导时注入段仍是单采样（画面「档位不生效」而非报错，排查先看 Library 产物时间）。

### 9.4 一-C per-object 落地（2026-11-14，接步骤一）

> 用户「动手」批 ② 尾巴（一-C + 步骤二）；一-C 与步骤一同日验收关账。

**改动（代码实证）**：

| 件 | 落点 | 形态 |
|----|------|------|
| 检查器暴露 | `MeshRenderer.castShadow/receiveShadow` | 补 `@InsField`（`ins.meshRenderer.castShadow/receiveShadow` 双语键）——原为死字段全仓无 `@InsField` |
| 桥同步 | `RenderObject` + `SceneRenderBridge` | `castShadow`/`receiveShadow` 去构造一次性、改**每帧 in-place 同步**（`RenderObject.castShadow(boolean)`/`receiveShadow(boolean)`，与 `world` 同节奏）——**随验修坑 #39**：原 `castShadow` 是 `final`、只在换代时读一次，运行期切开关不生效（重启才生效） |
| FORWARD 承载 | `AbstractForwardPass.drawObject` + `RenderingSystem.PUSH_RECEIVE_SHADOW_OFFSET`(=68) | push 扩 `receiveShadow`（`PushBlock{mat4 model; float tpf; float receiveShadow;}`，68B ≤ `PUSH_SIZE` 80）；注入段读 `pc.receiveShadow` |
| HYBRID 承载 | `Lit.shader` GBuffer `outAlbedo.a`（原恒 1.0） | 几何写 `pc.receiveShadow`（1/0）；全屏 `lighting.frag` 经 `texelFetch(gbufferAlbedo, ivec2(gl_FragCoord.xy), 0).a > 0.5` 取回（RGBA8：0.5→~0.502 判真可靠） |
| 注入段单源 | `ShaderInjector` | `javelinReceiveShadow()` **分两变体**（材质 fs 读 push / 全屏读 G-Buffer），`javelinLighting` 采样分支加 `&& javelinReceiveShadow() > 0.5` 门控——**同一 LIGHTING_BLOCKS 常量，差异仅两变体函数**；`PushBlock` 声明加 `receiveShadow` |
| 单测 | `ShaderImporterTest.lightingStageInjectsReceiveShadowFlag` | push 变体（`float receiveShadow;` + `pc.receiveShadow` + 门控）+ G-Buffer 变体（`texelFetch(gbufferAlbedo`）静态断言；真实编译归 `litShaderEndToEnd` |

**随验修（两坑，`Vulkan_Pitfalls.md`）**：

- **#39 `RenderObject` 换代时缓存组件字段**：`castShadow` 只在构造时读 → 运行期切开关消费端读陈旧值（重启才生效）。修 = 每帧 in-place 同步（判据：组件属性→渲染快照的字段，要么进换代判据、要么每帧同步，不能只构造读一次）。
- **#40 阴影正交盒只收 caster**：关地板投射 → 盒缩成立方体范围 → 立方体投影到地板的影子被盒外裁掉（一全消失一消失一部分）。修 = 盒体域改**全体不透明对象（caster ∪ receiver）并集** + 1.0 世界单位外扩余量（`SHADOW_BOX_PADDING`）——**投影正交下盒覆盖接收者表面即覆盖其影子**；代价 = 盒变大精度略降，靠 CSM 视锥拟合找回。

**验收口径（一-C，用户实机 2026-11-14）**：关 `castShadow` 的物体不投影、关 `receiveShadow` 的物体不接影，FORWARD / HYBRID 一致、运行期即时生效（不再重启才生效）。

### 9.5 步骤二 CSM 落地（2026-11-14，P4-03 随此关账）

**改动（代码实证）**：

| 件 | 落点 | 形态 |
|----|------|------|
| 级联纯逻辑 | `ShadowMath.buildCascades` + `CascadeParams` | lambda 混合切分（`d_i = λ·near·(far/near)^(i/N) + (1-λ)·(near+(far-near)·i/N)`）；相机基向量**自建**（`forward/right=cross(f,up)/upn=cross(right,f)`，避 `viewProj` Y 翻转）；逐级联视锥段 8 角点（透视 `tan(fovY/2)` 缩放、正交 `orthographicSize`）→ **包围球**（8 角均值心）→ 半径即方形正交盒半宽（各向同性纹素，盒 `r + texel·√2/2` 安全边）→ 球心光空间**纹素吸附**（步长 = 最终盒宽的渲染纹素 `2·half/resolution`，2026-11-18 订正，见下方随验订正）→；`upHint` 稳定轴沿用 `UP_SWITCH`；回填逐级联 zRange |
| per-view 资源族 | `RenderingSystem.viewShadows`（`ViewShadow`：atlas 图 + 矩阵[4]/切分[4]/invZ[4]/res/count/grid） | `computeShadow(cam)` 逐视口拟合（`buildCascades`），分辨率/级联数变化即重建（旧图两帧退役）；atlas 边长 = `resolution × grid`（grid = count≥2?2:1，2 级联只占前两象限**保持正方形**——单纹素 u/v 同值，沿用标量 PCF）；对外 `shadowMapFor/shadowCascadeCount/shadowAtlasGrid/shadowCascadeMatrix` |
| ShadowPass | 逐视口单 Pass（名 = `视口名 + ":Shadow"`） | atlas 一次 `CLEAR`；回调内逐级联 `cmd.setViewport/setScissor` 切象限（VIEWPORT/SCISSOR 动态态，同一 PSO），逐 caster 用该级联矩阵 push。**原「帧号幂等全局挂载」退役**（§4.4 取代） |
| UBO | `packLightingParams` | `mat4 sunViewProj[4]` + `vec4 cascadeSplits` + `vec4 shadowParams2`（grid/atlasSize/blend/0）+ `vec4 cascadeInvZ`；`zParams.w` = 级联数；`shadowParams.w` 改**世界单位**（逐级联乘 `cascadeInvZ` 折算）；块 416→**656B**、步长 512→**768**（`LIGHTING_STRIDE`） |
| 注入段 | `ShaderInjector.LIGHTING_BLOCKS` | 新增 `javelinCascadeShadow(int, vec3)`（矩阵投影 → atlas 象限 uv → 品质档 PCF，tap 夹在象限内 `javelinShadowTapC`）+ `javelinLighting` 内级联选择（按 `-viewPos.z` 遍历 `cascadeSplits`，超末级联恒受光）+ 相邻级联**混合带** mix（`shadowParams2.z`，只对非末级联） |
| 设置面 | `ShadowSettings` + `ProjectSettingsWindow` | 级联数（1/2/4，缺省 2）/ 阴影距离（100）/ 切分 lambda（0.5），读写盘带缺键默认守卫；窗口三行 + i18n 双语 |
| 单测 | `ShadowMathTest`（+5 例）、`JavelinSettingTest`（CSM 三项往返 + 旧档默认） | 切分单调/端点/均匀退、逐级联盒覆盖 8 角点 + zRange 正、亚纹素吸附（uv 稳定）、正交 w=1；设置往返 + 缺失默认 |

**随验两修（不是新坑，属步骤一口径在步骤二下的收口）**：盒体改由相机视锥拟合后，§3.6 的「caster ∪ receiver 世界 AABB 并集 + 外扩」**退役**（级联盒拟相机视锥，接收者天然在可视域内，`#40` 的裁剪问题从根上消除）。

**验收口径（步骤二，用户实机 2026-11-14「看起来没问题了」）**：级联数 1 与步骤一形态基本一致；2/4 级联近处影子更实、远处不劣化；相机移动/旋转边缘不抖（纹素吸附）；级联接缝无硬线（混合带）；超阴影距离恒受光无断层；设置窗口级联数/距离/切分即时生效；双视口各自正确；`logs/error` 零 ERROR。

**随验订正（2026-11-18，实机坐实「移动相机阴影明显跳动」后修）**：原吸附实现步长用 `2r/resolution` 而盒宽 `r + SNAP_SAFETY(=1.0 绝对世界单位)`——吸附跳变时世界纹素网格平移 ≈0.97 渲染纹素**非整数**，重光栅化逐纹素错位 = 整个级联的阴影边缘同帧跳一下（相机每移 ~0.03 世界单位触发一次，连续移动 = 连续跳动；缺省档级联 1 一纹素 ≈0.075 世界单位，近处物体放大到十余屏幕像素）。修 = ①安全边改**半纹素对角** `texel·√2/2`（原 1.0 世界单位比语义值大 ~50 倍，注释「半纹素对角」名不符实）；②吸附网格步长改**最终盒宽的渲染纹素** `2·half/resolution`——跳变 = 精确一整纹素、重光栅化逐纹素等同、平移相机零跳动。落点 `ShadowMath.buildCascadeMatrix`（无分辨率退化路径拆 `buildCascadeMatrixUnsnapped`）；补单测 `snapJumpEqualsWholeRenderTexel`（相机步进扫吸附边界，断言 uv 位移×resolution 为整数——**原实现必挂的核心不变量**，原「亚纹素吸收」测试只验证吸附生效、没验证跳变对齐故未拦住）；坑记 `Vulkan_Pitfalls.md` #41。用户实机验收通过（平移相机跳动消失）。旋转相机时 r 连续变化仍有平滑亚纹素蠕动（非跳变、不惹眼）；若将来要旋转也完全稳定，升级口径 = 盒尺寸也量化到纹素网格（Unity Stable 完整形态，§8 遗留候选）。

> **注入器改动 = 强制重导**（契约变更）：`.shader` 产物须重导（GLSL 动态索引 `mat4[4]` + 新字段）；旧产物不重导读新 UBO 偏移会错位（画面「无影/错影」而非报错，排查先看 Library 产物时间）。

### 9.6 未做（步骤三可选收尾）

- 阴影图 D16 格式档、PCSS（可变半径）、逐级联 caster 剔除（v1 每级联全量绘制）、DBG-03 shadowMap 可视化、多灯阴影（§5.5）。

---

> （注：部分内容可能由 AI 生成）

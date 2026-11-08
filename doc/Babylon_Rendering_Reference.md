# Babylon.js 渲染技术与实现方案参考（Babylon Rendering Reference）

> 版本：v0.1（2026-11-08 建档）
> 定位：外部引擎（Babylon.js）渲染管线全景速查——按子系统拆解「它用什么技术、怎么实现、源码在哪」，作为本引擎四期（后处理 / SSAO / 阴影分级 / 天空盒环境光 / 探针 GI / 光追）与日常渲染决策的参考源。
> 来源：本地检出 `D:\Workspaces\java\Babylon.js`（**外部目录，不随本库版本管理**），主干提交 `daae8d9`（2026-11-06）；core 源码根 `packages/dev/core/src`，下文路径与行号均以该检出为准。
> 取材口径：结论逐条对照源码原文核实（随文标 `文件:行`），未核实的推断不写；与天空盒相关的线路已单独成档 `doc/Babylon_Skybox_Reference.md`，本文只留指针。
> 边界：讲「渲染侧机制与方案」，不讲资产管线 / glTF 加载 / 编辑器 GUI / 序列化。

---

## 0. 总览：值得先记住的十条

Babylon 不是一个「RHI 层引擎」，而是**直接建在 WebGL/WebGPU 之上的场景层引擎**。它的价值不在底层同步，而在场景层把渲染特性组织成可组合的模块。以下十条是通读后最影响判断的结构性事实：

1. **执行模型极简**：`Scene.render()` → 逐相机 `_renderForCamera` → `RenderingManager`（固定 4 个 renderingGroup）→ `RenderingGroup`（四类队列：depthOnly / opaque / alphaTest / transparent）→ `SubMesh.render` → `Material.bindForSubMesh`。没有显式 RenderGraph 作为默认路径（`scene.pure.ts:5166-5351`、`Rendering/renderingManager.ts:197-276`）。
2. **不透明排序不是前到后**，而是**按材质 `uniqueId` 分组**（`Rendering/renderingGroup.ts:370-379`）；透明才是 alphaIndex + 后到前（`:307-318`）。这是跨引擎假设最容易踩错的一处。
3. **材质模型 = uber shader + `#define` 排列**：一份 `pbr.fragment.fx` 由数百个 `#ifdef` 守卫，变体身份**仅由 defines 字符串决定**——Effect 缓存键就是 `vertex + "+" + fragment + "@" + defines`（`Engines/thinEngine.pure.ts:2082`）。
4. **PBR 的绝大多数特性是「材质插件」**：clearcoat / sheen / subsurface / anisotropy / iridescence / BRDF 全部 `extends MaterialPluginBase`，通过 uber shader 里的 `#define CUSTOM_*` 注入点插代码，不是独立 shader 文件（`Materials/materialPluginManager.pure.ts:358-450`）。
5. **阴影的 PCF 用硬件比较采样器**（`sampler2DShadow` / `sampler2DArrayShadow`），PCF3/PCF5 是「4/9 次采样复现 3×3/5×5 高斯」的经典 MJP 技法（`Shaders/ShadersInclude/shadowsFragmentFunctions.fx:382-483`）。
6. **CSM 是单张分层 RTT**（`layers = cascadeCount`），级联切分是 `lambda*(log - uniform) + uniform` 混合，选层在片元里按 `diff >= 0` 取第一个级联并做相邻级联 `mix`（`Lights/Shadows/cascadedShadowGenerator.pure.ts:531-564`、`Shaders/ShadersInclude/lightFragment.fx:274-348`）。
7. **Clustered 光照是「CPU 聚类 + GPU 光栅化掩码」**：CPU 按 DOOM 对数深度切片打包每灯 20 个 float，GPU 用代理球写 tile mask 位；片元读 5 texel + 位掩码遍历（`Lights/Clustered/clusteredLightContainer.pure.ts`）。**只有点光/聚光、且不支持阴影**。
8. **FrameGraph 不做拓扑排序、不做 barrier**：`execute()` 就是按加入数组顺序跑，`dependencies` 只用于纹理生命周期/别名分析；顺序正确性由 NodeRenderGraph 的 DFS 构建保证（`FrameGraph/frameGraph.ts:396-410`、`FrameGraph/frameGraphTextureManager.ts:942-995`）。
9. **后处理链是「pass i 写进 pass i+1 的输入」**，`reusable` + 双缓冲 + `shareOutputWith` 省 RT；HDR 只是纹理类型选择，MSAA 是逐 PostProcess 的采样数（`PostProcesses/postProcessManager.ts:182-243`、`PostProcesses/postProcess.pure.ts:833-835`）。
10. **没有 DDGI / 光照探针体积 / 硬件光追**：core 里的空间 GI 只有 RSM GI（`Rendering/GlobalIllumination/giRSM*`）和反射探针（`Probes/reflectionProbe`）。四期的 DDGI / rayQuery 需另找参考，不能指望 Babylon。

---

## 1. 渲染执行模型

### 1.1 一帧的调用链

`Scene.render()` → `_renderFrame()`（`scene.pure.ts:5707-5735`），随后：

1. **相机更新**：遍历 `activeCameras`（无则 `activeCamera`），逐相机 `camera.update()`，并更新其 `_rigCameras`（`scene.pure.ts:5764-5786`）。
2. **自定义 RTT 先渲**：`scene._renderTargets` 里每个 `renderTarget.render()`（`5253-5266`），随后主帧缓冲绑定 + 清屏（`5816-5830`）。
3. **逐相机渲染**：`_processSubCameras(camera, ...)`（`5837-5848`）；rig/multiview 有专门分支（`5353-5377`）。
4. **`_renderForCamera`**（`5166-5351`）顺序固定：
   - 设 viewport（`5181`）→ 绑帧缓冲（`5187-5200`）→ `updateTransformMatrix()`（`5202`）
   - **先设 `engine.currentRenderPassId`**（`5207`），再 `_evaluateActiveMeshes()`（`5210`）——保证 per-render-pass 的实例注册正确
   - 渲染该相机关联网的 RTT（`5253-5266`）
   - 后处理 `_prepareFrame`（`5311-5313`）
   - **场景主体**：`renderingManager.render(null, null, true, !fastSnapshotMode)`（`5327`）
   - 后处理 `_finalizeFrame`（`5336-5340`）

`_evaluateActiveMeshes` 内完成视锥剔除并把每个 submesh `dispatch` 进 RenderingManager（`scene.pure.ts:4633`）。

### 1.2 RenderingManager：4 个组、只清深度不清颜色

- 固定 `MAX_RENDERINGGROUPS = 4`，组号 0..3（`Rendering/renderingManager.ts:66-71`）。
- 逐组渲染前可选 auto-clear，**但只清 depth/stencil，颜色从不按组清**（`renderingManager.ts:184-191`、`246-252`）。颜色清屏在 `Scene._clearFrameBuffer` 里按帧缓冲只做一次（`scene.pure.ts:5137-5159`）。
- 每组清屏配置 `IRenderingManagerAutoClearSetup = { autoClear, depth, stencil }`，可由场景统一或逐组设置（`renderingManager.ts:17-30`、`423-439`）。
- `dispatch` 按 `mesh.renderingGroupId` 路由（`renderingManager.ts:377-386`）。

### 1.3 RenderingGroup：四类队列与排序契约

`Rendering/renderingGroup.ts` 持有四类队列：`_depthOnlySubMeshes` / `_opaqueSubMeshes` / `_alphaTestSubMeshes` / `_transparentSubMeshes`（`:23-44`）。

入队判定（`dispatch`，`:418-456`）：

| 条件 | 队列 |
|------|------|
| `material.needAlphaBlendingForMesh(mesh)` | transparent |
| `material.needAlphaTestingForMesh(mesh)` | alphaTest（且若 `needDepthPrePass` 另入 depthOnly） |
| 其余 | opaque（`needDepthPrePass` 也另入 depthOnly） |

**默认排序**：

- opaque / alphaTest → `PainterSortCompare`：先比 `material.uniqueId`，再比 `mesh.uniqueId`（`renderingGroup.ts:370-379`）。**即按材质分组，不是前到后**；想前到后必须显式 `set frontToBackSortCompare`（`:350-360`）。
- transparent → `defaultTransparentSortCompare`：先 `alphaIndex`，再后到前（`backToFrontSortCompare`，`:307-339`）。

**渲染顺序**（`render`，`:123-215`）：

1. depthOnly（关颜色写，`:149-153`）
2. opaque（`:156-158`）
3. alphaTest（`:161-163`）
4. sprites / particles（临时关 stencil，`:165-176`）
5. transparent（可选 OIT 走 depth peeling，`:183-197`）
6. edges renderers（`:205-211`）

> **对本引擎**：这套「组内四队列 + 材质分组」与我方 `OpaqueForwardPass` / `Transparent` 分桶同构，但**默认排序策略不同**。我方可按需坚持前到后（提升 Early-Z），只是要知道 Babylon 没这么做。

### 1.4 SubMesh → Material 的实际绘制

`SubMesh.render(enableAlphaMode)` → `Mesh.render(...)`（`Meshes/subMesh.pure.ts:475-478`、`Meshes/mesh.pure.ts:2717+`）：

1. 遮挡查询 / 实例批处理（`mesh.pure.ts:2727-2743`）
2. `material.isReadyForSubMesh(...)`，未就绪直接 bail（`2780-2807`）
3. 需要时 `engine.setAlphaMode(...)`（`2810-2820`）
4. 取 `subMesh._drawWrapper` 或 `material._getDrawWrapper()`（`2822-2829`）
5. `material._preBind` → `_bind` → `bindForSubMesh(world, ...)`（`2843-2871`）
6. `_processRendering(...)` 发 draw（`2873-2878`）

### 1.5 RTT 走同一套管理器

`RenderTargetTexture.render` → `ObjectRenderer.render` → 同一个 `RenderingManager.render`（`Rendering/objectRenderer.ts:752-774`）。因此**渲染到纹理与渲到主帧缓冲共享完全相同的分组、排序、auto-clear 语义**（`objectRenderer.ts:471-472`）。这是 Babylon 结构上最干净的一点：RTT 不是特例。

---

## 2. 材质与 Effect 系统

> 源码约定：多数文件是 `X.ts` 一行转出 `X.pure.ts`（tree-shaking 用），真实实现看 `.pure.ts`。

### 2.1 Material 拥有什么

`Materials/material.pure.ts`：

| 成员 | 说明 |
|------|------|
| `_drawWrapper: DrawWrapper`（`:962`） | 编译产物 + defines + 后端绘制上下文 |
| `_uniformBuffer`（`:1009`） | 一份材质级 UBO（`_useUBO = engine.supportsUniformBuffers`） |
| `stencil = new MaterialStencilState()`（`:908`） | 模板状态 |
| `_alphaMode: number[]`（`:644`） | 数组形态以支持 MRT 多附件 |
| `pluginManager` | 惰性挂载的材质插件管理器 |
| 渲染状态布尔群 | `backFaceCulling` / `disableDepthWrite` / `forceDepthWrite` / `depthFunction` / `zOffset` / `separateCullingPass` … |

`DrawWrapper`（`Materials/drawWrapper.ts:18-48`）只快照**程序与 define 变体**（`effect` / `defines`）加两个后端上下文；深度写/颜色写等状态缓存在 Material 上（`material.pure.ts:993-1003`）。

### 2.2 Effect：编译、缓存、回退

- **缓存键**：`const name = vertex + "+" + fragment + "@" + fullDefines`（`Engines/thinEngine.pure.ts:2082`）。命中则 `_refCount++`（`:2083-2089`）。
  - 键里**没有 attributes / samplers / uniform 列表**。因此「同名同 defines 但属性集不同」的两个 Effect 会静默共享——变体身份完全由 define 串承担。
- **全局 defines**：`IS_NDC_HALF_ZRANGE`（NDC z 半区间）、`USE_REVERSE_DEPTHBUFFER`、`USE_EXACT_SRGB_CONVERSIONS`（`Engines/abstractEngine.functions.ts:91+`）。对 Vulkan 参考意义：这两个 define 正好对应「Vulkan NDC z∈[0,1]」与「reversed-Z」两件事。
- **编译流程**：`_processShaderCodeAsync` → `_ProcessShaderCode`（`Materials/effect.functions.ts:121-225`）：解析 vs/fs（字符串 / `IShaderPath` / script 标签 / 全局 store）→ 并行加载但**同一 tick 内串行处理**（shader processor 是单例）→ `#include` 展开 → 注入 `#define SHADER_NAME`。
- **回退**：`EffectFallbacks` 按 rank 升序剥 define 重编（`Materials/effectFallbacks.ts:14-15`、`76-123`）；rank 从 **32** 起，不是按重要性。CPU skinning 回退单独一档（`:52-61`）。编译失败时 `_processCompilationErrors` 打印 uniforms/attributes/defines/源码行并尝试下一次回退（`effect.pure.ts:914-994`）。
- **引用计数**：`Effect` 引用计数归零才释放（`PersistentMode` 可禁释放），`DrawWrapper.dispose` 可延迟到帧末（`effect.pure.ts:1547-1575`、`drawWrapper.ts:118-133`）。

### 2.3 uber shader + defines 排列

`MaterialDefines`（`Materials/materialDefines.ts`）：

- 普通动态对象 + 私有脏标记（`_areLightsDirty` / `_areTexturesDirty` …，`:14-28`）。
- `toString()` 生成 define 文本（`:252-273`）：数字/字符串 → `#define NAME value`，`true` → `#define NAME`，`false` → 空。
- 这段字符串**就是**传给 `createEffect` 的 `defines`，即着色器排列键。

`PBRMaterialDefines` 声明约 150 个具名开关（`Materials/PBR/pbrBaseMaterial.pure.ts:86-263`）：工作流（`METALLICWORKFLOW`）、法线族（`BUMP`/`PARALLAX`/…）、IBL 族（`REFLECTIONMAP_*`/`USESPHERICALFROMREFLECTIONMAP`/…）、实例/骨骼/形变、双面、裁剪面、雾、`SPECULARAA`、`CLUSTLIGHT_*` 等。特性配置的 define **不在基类**，由插件在运行时补（见下）。

**代价与收益**：磁盘上只有一份 `pbr.*.fx`，但每个 define 组合编译一个新管线；变体爆炸靠「精确字符串缓存 + 去重 + 引用计数」压住。热切换时旧 Effect 保留到新变体就绪（`pbrBaseMaterial.pure.ts:1251-1265`）。

### 2.4 材质插件：真正可复用的扩展点

`Materials/materialPluginBase.pure.ts` / `materialPluginManager.pure.ts` / `materialPluginEvent.ts`：

- **事件位标志**：`Created` / `GetDefineNames` / `PrepareUniformBuffer` / `IsReadyForSubMesh` / `PrepareDefines` / `BindForSubMesh` / `PrepareEffect` / `HardBindForSubMesh` …（`materialPluginEvent.ts:97-112`）。
- **注入锚点**：uber shader 里写成 `#define CUSTOM_*` 的空占位；插件 `getCustomCode(shaderType, language)` 返回代码，管理器在锚点**之前**插入（`materialPluginManager.pure.ts:443-445`）。
  - 锚点名以 `!` 开头时按**正则替换**处理，`!flags!pattern` 支持 flags，`$1` 展开捕获组（`:410-441`）。
- **每个插件类**自动获得合成 define `MATERIALPLUGIN_<n>`（`:90-92`），shader 可据此判断插件是否注入。
- `prepareDefinesBeforeAttributes`（影响属性收集）与 `prepareDefines`（普通 define）两阶段（`materialPluginBase.pure.ts:208`/`216`）。
- 内置 PBR 特性全部是插件：clearcoat（`pbrClearCoatConfiguration.ts:44`）、sheen（`pbrSheenConfiguration.ts:39`）、subsurface（`pbrSubSurfaceConfiguration.ts:68`）、anisotropy、iridescence、BRDF。
- 示例：`ditheredTileFadeMaterialPlugin.ts:381-488` 同时返回 GLSL 与 WGSL 两套注入代码，是最完整的插件样板。

> **对本引擎**：我方 `.shader` 是多 Pass + 固定 PassState 的显式模型，与 uber-shader 排列是两条路。参考价值在「**插件式能力组合**」：我方若要给材质加清漆/细节贴图等，与其改 shader，不如设计「材质插件挂到 Pass 上、向着色器注入 uniform + 代码片段」的机制。

### 2.5 `.fx` 的构建方式（值得借鉴的工程化）

`buildTools/src/buildShaders.ts` 在构建期把每个 `.fx` 生成同名 `.ts`：

- include 文件：生成 `const shader = \`...\`` 并自注册进 `IncludesShadersStore`，同时 `export { name, shader }`（`:22-31`）。
- 主 shader：**解析完整传递闭包**，为每个 include 生成显式 import 并注册，主 shader 自注册（`:37-48`、`:110-161`、`:290-405`）。
- 结果：运行时无文件加载，且显式 import 可 tree-shake——与「裸 import 副作用自注册」的旧行为兼容。

WGSL 侧是平行的 `ShadersWGSL/` 目录，材质通过 `shaderLanguage` 选择。

### 2.6 PBR 模型要点

- 金属度工作流：`isMetallicWorkflow()` 看 `_metallic` / `_roughness` / `_metallicTexture` 是否设置（`pbrBaseMaterial.pure.ts:1285-1291`），同一份 uber shader 用 `METALLICWORKFLOW` 分支同时支持镜面-光泽工作流。
- `openpbr` 是更新的独立超集材质（`Materials/PBR/openpbrMaterial.pure.ts`），有自己的 uber shader。
- Node Material（`Materials/Node/nodeMaterial.pure.ts`）是图驱动的替代路径：`build()` 从输出节点 DFS，生成源码再编译（`:838-952`）。

---

## 3. 光照

### 3.1 灯类型与逐灯 UBO

- 类型 ID：Point=0 / Directional=1 / Spot=2 / Hemispheric=3 / RectArea=4 / ClusteredContainer=5（`Lights/lightConstants.ts:75-97`）。
- **每盏灯一个 UniformBuffer** `Light{i}`（`Lights/light.ts:438`），`_buildUniformLayout()` 按类型声明字段；`_bindLight` 绑定并写值（`:434-474`）。
- 影响网格的灯 = `mesh.lightSources`；数量上限 `maxSimultaneousLights` **默认 4**（`Materials/materialHelper.functions.ts:607`）。
- WebGPU 上真正的瓶颈是 **每个 shader stage 的 uniform buffer 数量**（规范上限 12），`GetSupportedSimultaneousLights` 会据此夹紧并告警（`materialHelper.functions.ts:533-597`）。
- 排序：先阴影灯、再 `renderPriority`（`lightConstants.ts:105-112`）。

逐灯 UBO 字段（`Lights/*.pure.ts` 的 `_buildUniformLayout`）：

| 灯型 | 字段 |
|------|------|
| Point | `vLightData, vLightDiffuse, vLightSpecular, vLightFalloff, shadowsInfo(3), depthValues(2)`（`pointLight.pure.ts:169-177`） |
| Directional | 同上去掉 falloff（`directionalLight.pure.ts:298-305`） |
| Spot | 加 `vLightDirection, vLightFalloff`（`spotLight.pure.ts:380-389`） |
| Hemispheric | 加 `vLightGround`（`hemisphericLight.pure.ts:47-55`） |
| RectArea | 加 `vLightWidth, vLightHeight`（半尺寸，`rectAreaLight.pure.ts:200-209`） |
| Clustered | `vLightData, vLightDiffuse, vLightSpecular, vSliceData, vSliceRanges[depthSlices×2], shadowsInfo, depthValues`（`clusteredLightContainer.pure.ts:610-620`） |

### 3.2 Clustered（Forward+）——最值得细读的一段

**能力边界**（`Lights/Clustered/clusteredLightContainer.pure.ts:43-97`）：

- 只支持 **点光与聚光**；带投影/IES 纹理的聚光不支持；falloff 必须默认；**不支持阴影**。
- 批大小：WebGL1 → 0（不支持）；WebGPU → **32**（storage buffer 原子写）；WebGL2 → 浮点精度，移动端 **8**。
- 默认网格：`horizontalTiles=64`、`verticalTiles=64`、`depthSlices=16`、`maxRange=16383`（`:134-230`）。

**聚类在 CPU、遮罩在 GPU**：

1. `_updateLightData()`（`:417-535`）按相机视深排序灯，用 DOOM-2016 对数切分算切片：
   `sliceScale = depthSlices / log(maxZ/minZ)`，`sliceBias = -(depthSlices*log(minZ))/log(maxZ/minZ)`（`:435-437`）。
2. 每灯打包 **20 个 float（5×vec4）**（`:443-514`），上传为 **5 列 × maxLights 行 RGBA32F、NEAREST 的 RawTexture**（`:314-337`）。即**每灯 5 texel / 80 字节**。
3. 每灯按深度跨度 `[depth-range, depth+range]` 更新各切片的 min/max 灯索引区间 `_sliceRanges`（`:500-513`）。
4. **Tile mask**：
   - WebGL：`horizontalTiles × (verticalTiles × batches)` 的 FLOAT（WebGPU 为 UNSIGNED_BYTE）RTT，清 0；一个 size-2 平面 mesh thin-instance 每灯一个实例，光栅化屏幕空间球界并写位 `1 << (instance % batch)`，**加法混合**合并位（`:277-279`、`:340-388`）。
   - WebGPU：`StorageBuffer`（atomic u32），代理片元 `atomicOr`（`ShadersWGSL/lightProxy.fragment.fx`）。

每灯 5 texel 的字段布局（`:459-498`）：

| texel | 字段 | 内容 |
|-------|------|------|
| 0 | `vLightData` | position.xyz，`.w` = 聚光 exponent（点光 0） |
| 1 | `vLightDiffuse` | rgb = diffuse×强度，`.a` = range |
| 2 | `vLightSpecular` | rgb = specular×强度，`.a` = radius |
| 3 | `vLightDirection` | 方向.xyz，`.w` = cosHalfAngle（点光为 −1） |
| 4 | `vLightFalloff` | `.x` range、`.y` 1/range²、`.z/.w` 聚光 angleScale/offset |

**片元消费**（`Shaders/ShadersInclude/pbrClusteredLightingFunctions.fx:41-72`）：

```
tilePosition = ivec2(gl_FragCoord.xy * lightData.xy)       // xy = tiles/resolution
slice = int(log(viewDepth) * vSliceData.x + vSliceData.y)
mask  = texelFetch(tileMask, tilePosition, 0).r
bits  = extractBits(mask, maskOffset, maskWidth)           // 按切片裁有效灯区间
逐位遍历 bits → getClusteredLight(...) 5 次 texelFetch → 完整 PBR 贡献
```

入口在 `Shaders/ShadersInclude/lightFragment.fx:12-44`。WebGPU 因 Naga 不支持无限制指针参数，改为每灯生成 `computeClusteredLighting{X}`（`ShadersWGSL/ShadersInclude/clusteredLightingCompute.fx`）。

**与场景的接线**：`ClusteredLightingSceneComponent` 在 `_gatherActiveCameraRenderTargetsStage` 上塞入每个容器的 tile-mask RTT，保证**聚类先于主场景 pass**（`Lights/Clustered/clusteredLightingSceneComponent.pure.ts:48-81`）。

> **对本引擎**：我方已拍板「CPU 构建 cluster + 每盏 64B 打包 + 变长索引池」。Babylon 给了另一条权衡：
> - **每灯一条记录（80B）+ 逐切片灯索引区间**，而不是每 cluster 一个灯列表——省掉巨大的 cluster→light 索引池，代价是片元要按切片区间再裁位掩码。
> - **GPU tile mask 光栅化**只负责 tile×slice-batch 的可见灯位，聚类数学仍在 CPU。
> - 我方若将来要支持大量点光，可对比「每 cluster 索引池（我方）」vs「每切片区间 + 位掩码（Babylon）」的显存与 ALU 取舍。

### 3.3 标准前向光照

`PrepareDefinesForLights` 逐灯设 `LIGHT{i}` / 类型 define / `MAXLIGHTCOUNT`（`Materials/materialHelper.functions.ts:792-881`）；`BindLights` 逐灯 `bindToEffect`（`:607-614`）。着色器侧 `lightFragment.fx` 产出每灯的 `lightingInfo`，`lightsFragmentFunctions.fx` 提供 `computeLighting` / `computeSpotLighting` / `computeHemisphericLighting` / 面光 / IES 变体，衰减在 `pbrDirectLightingFalloffFunctions.fx`（`:1-55`）。衰减类型是逐灯编译 define。

### 3.4 IBL（天空盒环境光的直接参考）

- **漫反射 = 球谐**：`BaseTexture.sphericalPolynomial` 惰性把 cubemap 转 SH（`Materials/Textures/baseTexture.polynomial.pure.ts:29-43`、`Misc/HighDynamicRange/cubemapToSphericalPolynomial.ts`）。
  - 上传 `vSphericalL00..L22`（9 个 vec3，Sloan 预缩放）或 Jones 9 band 项（`materialHelper.functions.ts:322-343`）。
  - shader 求值 `computeEnvironmentIrradiance`（`Shaders/ShadersInclude/harmonicsFunctions.fx:10-61`），可放顶点侧（`USESPHERICALINVERTEX`）。
- **高光 = 预过滤 mip 链**：LOD 由粗糙度 `alphaG` 推（`getLodFromAlphaG`，`pbrIBLFunctions.fx:2-11`），乘 `vReflectionMicrosurfaceInfos = (width, lodScale, lodOffset)`；单采样器硬件 LOD 或低/高两张图（`pbrBlockReflection.fx:56-156`）。
- **环境 BRDF LUT**：内嵌 base64 PNG（2 通道；OpenPBR 3 通道），RGBD 编码，惰性创建（`Misc/brdfTextureTools.ts:7-80`）；查表 `getReflectanceFromBRDFLookup`（`pbrBRDFFunctions.fx:103-118`），无 LUT 时回退解析式 BRDF。
- **反射探针**：6 面 cube RTT，逐面独立场景 UBO（`Probes/reflectionProbe.pure.ts:70-86`），产出直接喂 `scene.environmentTexture`。

> **对本引擎**：四期「天空盒环境光」的最小可行方案就是这三件套——**SH 漫反射 + 预过滤 mip 高光 + BRDF LUT**。天空盒本体与 `SKYBOX_MODE` 的采样通路见 `Babylon_Skybox_Reference.md`。

### 3.5 面光 LTC 与 IES（速记）

- LTC：64×64 RGBA 半浮点 `LTC1`（幅度/角度）+ `LTC2`（Fresnel）两张表，由外部 `areaLightsLTC.bin` 解码（`Lights/LTC/ltcTextureTool.ts:24-45`、`Lights/areaLight.pure.ts:14-92`）。
- IES：解析 LM-63，按水平/垂直角插值烛光并归一化，烘成 **180×1** 浮点柱面条带纹理（`Lights/IES/iesLoader.ts:39-209`），聚光挂 `iesProfileTexture`。

---

## 4. 阴影与 GI

### 4.1 ShadowGenerator

**过滤器常量**（`Lights/Shadows/shadowGenerator.ts:175-242`）：`NONE` / `ESM` / `POISSON` / `BLUR_ESM` / `CLOSE_ESM` / `BLUR_CLOSE_ESM` / `PCF` / `PCSS`；质量档 `HIGH(5×5, 32+64) / MEDIUM(3×3, 16+32) / LOW(1×1, 16+16)`。cube 灯强制 ESM/Poisson。

**阴影图分配**（`_createTargetRenderTexture`，`:1043-1073`）：

- 支持 depth-stencil 时：`RenderTargetTexture` + `createDepthStencilTexture(GREATER|LESS, storeFullFloat=true)`，格式 RED（或 RGBA）。
- 纹理类型尝试 half-float → float → ubyte（`:1014-1032`）。
- PCF/PCSS 绑定**比较采样器**（`setDepthStencilTexture`，`:1981-1994`）；非 PCF 绑普通颜色纹理并在 shader 里 `unpack`（`shadowsFragmentFunctions.fx:10-15`）。

**偏移**：

- `bias` 默认 `0.00005`，`normalBias` 默认 0（`:286`、`:300`）。
- 线性 bias 在顶点着色器施加：`gl_Position.z ± bias * w`（`shadowMapVertexMetric.fx:12-18`）。
- 法线 offset：`worldPos -= normal * (normalBias * sinNL)`，`sinNL = sqrt(1 - NdotL²)`（`shadowMapVertexNormalBias.fx:10-14`）——这就是我方可直接借用的「法线偏移随入射角增大」公式。

**PCF 采样术**（`shadowsFragmentFunctions.fx:382-483`）：

- 用 `highp sampler2DShadow` 硬件比较，返回值即遮挡。
- PCF3 = 4 次采样复现 3×3 高斯；PCF5 = 9 次采样复现 5×5 高斯；权重按亚纹素偏移 `st = fract(uv)` 解析计算再归一化。这是 MJP 阴影图分布的经典做法，**比朴素 25 次采样省 60%+**。

**PCSS**（`:694-769`）：先按 Poisson 磁盘平均 blocker 深度，算 `penumbraRatio`，再按笔头大小做可变半径 PCF；16/32/64 tap 三档。

**灯矩阵**：`LookAtLH(lightPos, lightPos+dir, up)` → `light.setShadowProjectionMatrix(...)`（方向光正交、聚光透视）→ `_transformMatrix = view * proj`（`shadowGenerator.ts:2027-2072`）。

### 4.2 CSM（级联阴影）——我方下一批阴影的直接参考

`Lights/Shadows/cascadedShadowGenerator.pure.ts`：

- **级联数 2~4**（默认 4，`:85-93`），只允许 `NONE` / `PCF` / `PCSS`。
- **单张分层 RTT**：`RenderTargetTexture` 且 `layers = numCascades` + 一张深度-模板纹理（`:848-875`）——即 2D array shadow map。

**切分公式**（`_splitFrustum`，`:531-564`，原文可直接照抄）：

```
near = camera.minZ;  far = camera.maxZ || shadowMaxZ
minZ = near + minDistance*(far-near);  maxZ = near + maxDistance*(far-near)
range = maxZ-minZ;  ratio = maxZ/minZ
for i in 0..N-1:
    p = (i+1)/N
    log     = minZ * ratio^p
    uniform = minZ + range*p
    d = lambda*(log - uniform) + uniform      // lambda∈[0,1]，默认 0.5
    breakDistance[i] = (d - near)/(far-near)
```

**每级联矩阵**（`_computeMatrices`，`:566-669`）：

1. 按 `prevBreakDistance..breakDistance` 切相机视锥 8 角点 → 世界空间。
2. 求包围球；`stabilizeCascades` 时半径向上取整到 1/16，保证旋转时投影尺寸恒定（`:722-768`）。
3. 正交投影拟合级联范围，可选 `depthClamp` 用投影体包围盒收紧 near/far（`:596-647`）。
4. **纹素抖动消除**：世界原点投影到阴影空间后吸附到纹素网格（`round(v)-v) * 2/mapSize`），把平移乘进投影（`:654-665`）。注意这一步**无条件执行**，`stabilizeCascades` 控制的是包围球拟合。

**片元选层与混合**（`Shaders/ShadersInclude/lightFragment.fx:274-348`）：

- 选第一个 `viewFrustumZ[i] - vPositionFromCamera.z >= 0` 的级联（右手系符号翻转）。
- `diffRatio = clamp(diff/frustumLength,0,1) * blendFactor`；非最后级联且 <1 时采样下一级联并 `mix`。`cascadeBlendPercentage` 默认 0.1。

**工程细节**：`autoCalcDepthBounds` 用 `DepthReducer`（内部 `DepthRenderer`）算帧内 z 范围并回灌 `setMinMaxDistance`（`:466-520`）；`lambda→1` 时效果最好（源码注释）。`depthClamp` 与 PCSS 不兼容。

> **对本引擎**：我方要的「PCF/CSM 归四期」几乎可以按这份规格实现——**2D array 阴影图 + lambda 混合切分 + 纹素吸附 + 相邻级联 mix**，是 Vulkan 上同样成立的纯数学与采样策略，不依赖 Babylon 后端。

### 4.3 DepthRenderer

`Rendering/depthRenderer.pure.ts` 渲相机空间深度图（RTT），`storeNonLinearDepth` / `storeCameraSpaceZ` 决定存法；格式 RED（打包时另说）。用途：CSM 的自动深度包围、selection outline、高斯泼溅合成、Node Material 深度块等。**它不负责阴影**——阴影用的是 ShadowGenerator 自己的图。

### 4.4 RSM GI

- `ReflectiveShadowMap`（`Rendering/reflectiveShadowMap.pure.ts:187-310`）：一张 3 附件 MRT（世界位置 / 世界法线 / flux），从光源视角渲染；材质用克隆材质 + `RSMCreatePluginMaterial` 写这三个输出。
- `GIRSMManager`（`Rendering/GlobalIllumination/giRSMManager.pure.ts`）：维护多个 RSM + 预计算采样纹理 + GI 后处理 + 双边模糊 + 上采样。VPL 采样用余弦加权圆盘（`:388-419`），着色公式在 `Shaders/rsmGlobalIllumination.fragment.fx:51-89`（投影到光源空间 → Poisson 盘旋转 → 位置/法线/flux 采样 → `1/d⁴` 量级衰减 → 乘 intensity）。
- 需要 `GeometryBufferRenderer` 提供位置/法线；GI 结果按 `indirectDiffuse * albedo` 加进最终漫反射。

### 4.5 IBLShadows（一句）

`Rendering/IBLShadows/**`：把场景体素化进 3D 网格，再对 IBL 环境做体素追踪，产出环境光遮蔽阴影，配空间模糊 + 时域累积；是屏幕空间/时域技术，不是 shadow map 系统。

### 4.6 缺失项（重要）

**core 无 DDGI / 光照探针体积 / 体素 GI / 硬件光追**。全仓 grep `DDGI` / `LightProbe` / `irradiance volume` 只命中 IBL 预过滤与常量表。四期若走 DDGI / rayQuery，本文只能提供「周边件」（G-buffer、history texture、TAA）参考，核心算法需另找来源。

---

## 5. 后处理

### 5.1 PostProcess 基类与链模型

`PostProcesses/postProcess.pure.ts`：

- 构造参数：fragment、options（`size` 比例或像素、`samplingMode`、`textureType`、`textureFormat`、`reusable`、`camera` …，`:34-82`）。
- 输入：`inputTexture`（双缓冲中当前读侧，`:435-437`）；`_outputTexture` 由链运行器指向下一 pass 的输入（`:1246-1248`）。
- `apply()`（`:994-1034`）：绑 `textureSampler` → 设 `scale` → 触发 `onApplyObservable`（用户塞自定义 uniform/采样的唯一钩子）→ `effectWrapper.bind(true)`。
- `activate()`（`:879-959`）：按比例算尺寸、必要时 `resize`、绑帧缓冲、默认自动清屏、`reusable` 时翻转 `_currentRenderTextureInd`（`:955-957`）。
- 全屏三角形/四边形几何在 `EffectRenderer`（`Materials/effectRenderer.pure.ts:32-35`）与 `PostProcessManager` 自持的 VBO/IBO。

### 5.2 链的运行方式

`PostProcesses/postProcessManager.ts`：

- `_prepareFrame` 只 activate 第一个 pass（`:101-115`）。
- `_finalizeFrame` 逐 pass：`pp._outputTexture = postProcesses[i+1].activate(...)`，最后一个绑默认帧缓冲或相机输出 RT，然后 `apply()` + 画 6 个索引（`:207-233`）。
- 由此得到关键性质：**pass i 渲染进 pass i+1 的输入**，且下游 pass 可以复用上游的 `_outputTexture`（`shareOutputWith`，`:695-701`）省一张 RT。
- `reusable` 的另一半：同一 PostProcess 一帧内跑多次时读写交替双缓冲。

### 5.3 管线（Pipeline / Effect）

- `PostProcessRenderEffect` = 一个具名单元，内含一个或多个 pass，可单实例或每相机一份（`RenderPipeline/postProcessRenderEffect.ts:34-131`）。
- `PostProcessRenderPipeline` = 多个 effect 的具名集合，`_enableMSAAOnFirstPostProcess` 把 MSAA 采样数只加在**第一 pass**（`postProcessRenderPipeline.ts:239-253`）。
- `PostProcessRenderPipelineManager` 由场景组件每帧 `update()`（`postProcessRenderPipelineManagerSceneComponent.pure.ts:36-62`）。

### 5.4 DefaultRenderingPipeline 的固定顺序

`PostProcesses/RenderPipeline/Pipelines/defaultRenderingPipeline.pure.ts:591-752`：

```
DepthOfField → Bloom → ImageProcessing → Sharpen → Grain → ChromaticAberration → FXAA
```

- `_setAutoClearAndTextureSharing`（`:564-585`）：除第一个 pass 外全部 `autoClear=false`，相邻共享输出；第一个 pass 清屏并把 `scene.autoClear=false`。
- HDR 纹理类型：half-float → float → ubyte（`:443-455`）。
- **HDR 关时，image processing 不进后处理链而是塞进前向 shader**（`applyByPostProcess=false`，`:670`）——这是一条很实用的降开销路径。

### 5.5 值得抄的具体效果

| 效果 | 做法 | 出处 |
|------|------|------|
| 高斯模糊 | 可分离 X/Y，核折叠成硬件线性采样 tap，权重写成 `#define KERNEL_OFFSETi/WEIGHTi` | `thinBlurPostProcess.ts:141-285` |
| Bloom | 提亮阈值 → 降采样 → 可分离模糊 → merge 回原图 | `bloomEffect.ts:87-131` |
| DoF | CoC → 金字塔逐级模糊 Y/X → merge | `depthOfFieldEffect.ts:198-221` |
| 色彩 | exposure / contrast / ACES / KHR PBR Neutral / 色轮 / 白平衡 / 暗角 / 抖动 | `imageProcessingConfiguration.pure.ts:27-722` |
| TAA | 抖动投影 + history 采样，默认 8 Halton；相机移动时 `factor=1`；可重投影 | `thinTAAPostProcess.ts:37-288` |
| FSR1 | 1/scale 渲染 + EASU 上采样 + RCAS 锐化，需 WebGL2/WebGPU 级整数取整内建 | `fsr1RenderingPipeline.ts`、`thinFSR1UpscalePostProcess.ts:60-88` |
| 运动模糊 | 基于速度纹理（GBuffer/PrePass）或屏幕深度 | `motionBlurPostProcess.pure.ts:161-298` |

> **对本引擎**：我方四期后处理最该先抄的是**链式模型 + 共享输出 + 第一 pass 独占 MSAA**，以及 **DefaultRenderingPipeline 的顺序与 autoClear 纪律**。具体效果算法都是全屏 pass，跟 RHI 无关，可直接移植到 Vulkan。

---

## 6. FrameGraph（对我方 RenderGraph 最有价值的一章）

### 6.1 模型

`FrameGraph/frameGraph.ts`：

- `addTask(task)` 收集任务（`:181-192`）；`buildAsync()` 逐任务调用 `record()` 声明 pass 与纹理句柄（`:270-324`）。
- **`execute()` 严格按 `_tasks` 数组顺序执行**（`:396-410`），没有拓扑排序、没有自动重排。
- Pass 三型：普通 `FrameGraphPass`、`FrameGraphRenderPass`、`FrameGraphObjectListPass`（`:22-26`）。

### 6.2 资源模型：句柄 + 悬空句柄 + 瞬态别名

`FrameGraph/frameGraphTypes.ts` / `frameGraphTextureManager.ts`：

- `FrameGraphTextureHandle = number`；0 = backbuffer color，1 = backbuffer depth/stencil，自增从 2 起（`frameGraphTypes.ts:6-18`）。
- **悬空句柄**：任务可先 `createDanglingHandle()`，在 `record()` 里 `resolveDanglingHandle()` 指向实际纹理（`:370+`）。这让「任务构造时我还不知道输出给谁」成立。
- **外部纹理**：`importTexture(name, internalTexture)` 包一层且**不会被管理器释放**（`:274-295`）。
- **瞬态纹理**：`buildAsync` 时先 `_releaseTextures(false)` 再 `_allocateTextures()`；`Task` 命名空间的纹理被删除重建（`:633-762`）。
- **别名优化**：按 `textureDescriptionHash` 分组，用任务序算出的 **lifespan 区间**判断不重叠则复用同一分配（`:942-995`）。这是现代 RenderGraph 的核心省显存手段。
- **history 纹理**：`isHistoryTexture` 的 ping-pong 对，每帧执行前翻读/写索引（`:765+`、`frameGraph.ts:403`），且不参与别名。

### 6.3 关键约束

- **每个任务在 enabled 与 disabled 状态下必须产出相同输出句柄**（`frameGraphTask.ts:209-228` 校验）。这样图可以在不知道开关状态时完成资源分配。
- `dependencies` 只喂给 lifespan 计算，不影响调度（`frameGraphTextureManager.ts:1014-1053`）。
- **没有 barrier / layout transition**：绑 RT 交给 `engine.bindFramebuffer` / `unBindFramebuffer`（`frameGraphRenderContext.ts:387-406`）。`depthReadOnly` / `stencilReadOnly` 只是 WebGPU 优化提示。

### 6.4 顺序从哪来

`NodeRenderGraph`（节点图编辑器）在 `buildAsync` 时从输出块递归：先建所有输入块（DFS 后序、`_buildId` 备忘），再 `frameGraph.addTask(task)`（`NodeRenderGraph/nodeRenderGraphBlock.ts:331-387`）。于是**数组顺序 = 节点图拓扑序**。手写 graph 则完全靠调用方自觉。

### 6.5 任务清单（表明能力面）

- 渲染：`objectRenderer` / `geometryRenderer` / `shadowGenerator` / `csmShadowGenerator` / `iblShadows` / `utilityLayer`（`FrameGraph/index.ts:57-62`）。
- 层：glow / highlight / selectionOutline（`:15-17`）。
- 后处理：bloom / blur / DoF / imageProcessing / fxaa / tonemap / **ssao2RenderingPipeline** / **ssrRenderingPipeline** / **taa** / **volumetricLighting** 等（`:23-53`）。
- 杂项：`clearTexture` / `copyToBackbuffer` / `generateMipMaps` / `computeShader` / `cullObjects` / `lightingVolume` / `minMaxReducer`（`:19-21`、`:55-58`）。

任务本质是既有 `Thin*` 渲染器的**适配器**（不是重写）：SSAO2 / SSR / volumetric 这类多 pass 管线被拆成多个子任务（`ssao2RenderingPipelineTask.ts:192-222`、`volumetricLightingTask.ts:282-296`）。未整合的组件可走 `renderUnmanaged(component)` 逃生门（`frameGraphRenderContext.ts:28-47`）。

> **对本引擎（最关键的对照）**：
> - Babylon 的 FrameGraph **不做 barrier**，因为它把同步藏在后端 `bindFramebuffer` 里；我方 Vulkan RenderGraph **必须自己做 image layout / pipeline barrier / 队列归属**，不能照抄「按数组顺序跑」。
> - 但它**瞬态纹理 + lifespan 别名 + history ping-pong + enabled/disabled 同输出**四条很值得吸收，正好补我方 RenderGraph 的资源规划。
> - 「悬空句柄 + record 阶段解析」是解决「输出还没被下游指定」的优雅做法，可考虑引入。

---

## 7. 几何辅助 Pass 与 G-buffer

### 7.1 两代 G-buffer

**PrePassRenderer（新，推荐参考）**：`Rendering/prePassRenderer.pure.ts`

- 一次场景渲染写一张 MRT（`PrePassRenderTarget`），纹理类型 float → half → ubyte（`:317-337`）。
- **布局动态**：只启用被「效果配置」声明需要的附件。`PrePassEffectConfiguration = { name, enabled, texturesRequired[], postProcess?, needsImageProcessing?, clearColor?, createPostProcess? }`（`Rendering/prePassEffectConfiguration.ts:8-41`）。
- 材质 opt-in：`Material.isPrePassCapable` 默认 false（`material.pure.ts:739-741`）；Standard/PBR 覆盖为 `!disableDepthWrite`（`standardMaterial.pure.ts:594-596`、`pbrBaseMaterial.pure.ts:962-964`）；NodeMaterial 动态补附件。
- 可写通道：position / velocity / reflectivity / color / depth / normal / albedo-sqrt / world-normal / local-position / screenspace-depth / objectId 等（`Engines/constants.ts:759-846`）。
- shader 输出在 `Shaders/ShadersInclude/prePassDeclaration.fx` + `pbrBlockPrePass.fx`（位置 `vPositionW`、法线视图空间、线性深度 `vViewPos.z`、reflectivity = `vec4(specularR0, microSurface)`）。

**GeometryBufferRenderer（旧）**：`Rendering/geometryBufferRenderer.pure.ts`，自带 `geometry.*` shader 渲全场景到 MRT；可链接 PrePass 复用附件（`:289-391`）。新效果优先 PrePass，旧路径保留兼容。

### 7.2 SSAO2（可直接照搬的算法）

`PostProcesses/RenderPipeline/Pipelines/ssao2RenderingPipeline.pure.ts` + `thinSSAO2PostProcess.ts` + `Shaders/ssao2.fragment.fx`：

1. 链路：`originalColor` → `ssao` → `blurH` → `blurV` → `combine`（`:376-441`）。
2. **随机噪声**：128×128 RGBA，RGB 为归一化随机 2D 向量（`thinSSAO2PostProcess.ts:225-243`），`randTextureTiles=32`。
3. **半球样本**：<16 用均匀随机半球（`cosTheta = 1 - u*0.85` 避切平面），≥16 用 Hammersley 低差异序列（`:248-289`）。
4. 参数：`radius=2`、`maxZ=100`、`minZAspect=0.2`、`epsilon=0.02`（`:91-123`）。
5. 片元（`ssao2.fragment.fx:57-117`）：读随机向量 + 深度 + 法线 → `correctedRadius = min(radius, minZAspect*depth/near)` → 重建视空间原点 → Gram-Schmidt TBN → 循环 `SAMPLES`，用 `scales[16]` 按随机量旋转核，`smoothstep` 做距离衰减，`step(epsilon, diff)` 累计遮挡 → `1 - strength*occ/samples` → 深度淡出（`maxZ*0.75..maxZ`）。
6. **双边模糊**：H/V 两 pass，深度加权保边（`:121-236`）。

### 7.3 SSR2

`ssrRenderingPipeline.pure.ts` + `Shaders/screenSpaceReflection2.fragment.fx` + `screenSpaceRayTrace.fx`：

- 基于 Morgan McGuire 的屏幕空间光线步进；沿主轴置换、`dP/dQ/dk` 推导、抖动起点（`screenSpaceRayTrace.fx:139-175`）。
- 主循环按 stride 前进，把像素当 clip 空间体素比较 `rayZMin/rayZMax` 与场景深度 ± `thickness`（`:193-257`）。
- **refinement**：命中后回退一个 stride，在最后一步内单像素步进（`:268-326`）。
- 未命中/衰减时回退环境立方图颜色（`screenSpaceReflection2.fragment.fx:222-247`）。

### 7.4 OIT：depth peeling

`Rendering/depthPeelingRenderer.ts` + `thinDepthPeelingRenderer.pure.ts`：

- 默认 5 层（`depthPeelingRenderer.ts:50`），依赖 PrePass（否则告警退出）。
- 双缓冲 ping-pong 的 3 附件深度 MRT（depth + front + back）与 2 附件颜色 MRT（`thinDepthPeelingRenderer.pure.ts:206-248`）。
- 每层用 `ALPHA_EQUATION_MAX`/关闭深度测试挑出当前最前层，前后颜色分离，最终 `oitFinal` 合成（`:512-562`、`depthPeelingRenderer.ts:144-159`）。

### 7.5 描边与边线

- **Outline**：**沿法线挤出面**（`Shaders/outline.vertex.fx`，`offset` 沿 `normalUpdated`），不是纯 stencil 描边；stencil 只用于透明物体时遮住内部（`Rendering/outlineRenderer.pure.ts:28-47`、`:379-428`）。
- **Edges**：CPU 生成线段几何（每段 4 顶点 / 2 三角的 quad），自定义 shader 按正交/透视缩放线宽（`Rendering/edgesRenderer.pure.ts:123-385`、`:956-965`）。

### 7.6 Utility Layer（编辑器叠加层）

`Rendering/utilityLayerRenderer.ts`：建一个共享 engine 的**独立虚拟 Scene**，关后处理、`autoClear=false`，在主场景相机 `onAfterRenderCamera` 时渲染（`:172-397`）。`DefaultKeepDepthUtilityLayer` 让 gizmo 与主场景深度咬合（`:100-121`）。拾取事件优先走 utility 场景。

> **对本引擎**：我方 Gizmo / DebugLine 已是「覆盖制 Pass」路线，比 utility scene 轻。但若将来要加「独立于场景的临时绘制对象 + 独立拾取优先级」，这个双场景模型值得回看。

### 7.7 高斯泼溅（一句）

`Meshes/GaussianSplatting/**`：`GaussianSplattingMaterial` + CPU worker / GPU 排序器，WebGPU 可走 compute 点泼溅路径；激活 PrePass 时会写 position/velocity/normalized-view-depth。

---

## 8. 剔除、遮挡与实例化

- **视锥剔除**：`Scene._evaluateActiveMeshes` 每帧对每个 mesh 判 `isVisible && visibility>0 && layerMask匹配 && isInFrustum(planes)`（`scene.pure.ts:5008-5013`）；视锥平面由 `Frustum.GetPlanesToRef(this._transformMatrix, ...)` 每帧算一次（`:3011-3014`）。
- **策略**：默认 `BOUNDINGSPHERE_ONLY`；`STANDARD` 先球后盒；还有乐观包含两档（`Meshes/abstractMesh.pure.ts:374-468`）。实现于 `Culling/boundingInfo.ts:200-238`。
- **遮挡查询**：无独立场景组件，实现在 `AbstractMesh.prototype._checkOcclusionQuery` + 引擎 query API；用 `boundingBoxRenderer.renderOcclusionBoundingBox(mesh)` 在 `begin/endOcclusionQuery` 之间画包围盒测可见性，支持严格/乐观与重试（`Engines/AbstractEngine/abstractEngine.query.pure.ts:171-234`、`Meshes/abstractMesh.pure.ts:357-366`）。
- **实例化**：`InstancedMesh` 只是源 mesh 的轻包装；渲染时按 submesh 聚合可见实例世界矩阵进一个实例缓冲，一次 instanced draw（`Meshes/mesh.pure.ts:2246-2344`）。`thinInstance` 直接把实例数据作为顶点缓冲属性挂在源 mesh 上，无逐实例对象（`Meshes/thinInstanceMesh.pure.ts:189-400`）。

---

## 9. Babylon 没有的东西（避免误判）

| 能力 | 现状 |
|------|------|
| DDGI / 光照探针体积 / 体素 GI | **无**。空间 GI 只有 RSM GI |
| 硬件光追 / rayQuery | **无**（`pbrBRDFConfiguration` 里的匹配只是注释词） |
| Clustered 阴影 | **无**，clustered 容器拒绝 shadow 灯 |
| 显式 barrier / layout transition | **无**，交给后端绑 RT 时隐式处理 |
| FrameGraph 拓扑调度 | **无**，数组顺序即执行顺序 |
| 不透明前到后排序 | **无（默认）**，默认按材质分组 |

---

## 10. 对本引擎的借鉴清单（按优先级）

### P0：直接可用于四期落地

1. **PCF 采样术**（§4.1）：硬件比较采样器 + 4/9 tap 复现 3×3/5×5 高斯 + 亚纹素权重。我方阴影批次的 PCF 可直接按此实现，避免 25 次朴素采样。
2. **CSM 全套数学**（§4.2）：`lambda*(log-uniform)+uniform` 切分、纹素吸附、`depthClamp`、相邻级联 `mix`。纯数学 + 采样，Vulkan 端零适配成本。
3. **后处理链模型**（§5.1-5.4）：pass i 写进 pass i+1 输入、`shareOutputWith` 省 RT、第一 pass 独占 MSAA、HDR 关时把色彩管理塞回前向 shader。
4. **SSAO2 算法**（§7.2）：半球核 + 噪声旋转 + `minZAspect` 半径钳制 + 双边模糊。配合我方已有 G-buffer 能力即可上。

### P1：补齐 RenderGraph / 资源规划

5. **瞬态纹理 + lifespan 别名 + history ping-pong + enabled/disabled 同输出**（§6.2-6.3）。这四条是我方 RenderGraph 相对 Babylon 真正值得补的缺口；但 **barrier/layout 必须自己做**，不能学它「顺序跑、同步靠绑定」。
6. **悬空句柄 + record 阶段解析**（§6.2），解决输出后绑定的建模问题。

### P2：光照与 IBL 的长期参考

7. **IBL 三件套**（§3.4）：SH 漫反射 + 预过滤 mip 高光 + BRDF LUT。四期「天空盒环境光」的最小路径。
8. **Clustered 的另一种取舍**（§3.2）：每灯 80B 记录 + 逐切片索引区间 + GPU 位掩码，vs 我方每 cluster 索引池。灯数上去后值得做一次显存/ALU 对比。
9. **材质插件模型**（§2.4）：若我方要给材质加可组合能力（清漆/细节贴图/贴花），参考「锚点 + 声明式 define + uniform/采样注入」而非改 shader。

### P3：需要另找来源的空白

10. DDGI / 探针体积 / rayQuery（§4.6、§9）：Babylon 无实现，本文只能借用其 G-buffer、history、TAA 等周边件。

---

## 11. 源码索引（按主题）

| 主题 | 入口 |
|------|------|
| 帧循环 / 相机 | `scene.pure.ts:5166-5351`、`:5707-5848` |
| 分组与排序 | `Rendering/renderingManager.ts:197-305`、`Rendering/renderingGroup.ts:23-400` |
| RTT 复用同一管理器 | `Rendering/objectRenderer.ts:471-472`、`:752-774` |
| Material / DrawWrapper | `Materials/material.pure.ts`、`Materials/drawWrapper.ts` |
| Effect 编译与缓存 | `Engines/thinEngine.pure.ts:2057-2112`、`Materials/effect.functions.ts:121-225` |
| defines 排列 | `Materials/materialDefines.ts:252-273`、`Materials/PBR/pbrBaseMaterial.pure.ts:86-263` |
| 材质插件 | `Materials/materialPluginBase.pure.ts`、`materialPluginManager.pure.ts:358-450` |
| 逐灯 UBO | `Lights/light.ts:434-474`、`Shaders/ShadersInclude/lightUboDeclaration.fx` |
| Clustered | `Lights/Clustered/clusteredLightContainer.pure.ts`、`Shaders/ShadersInclude/pbrClusteredLightingFunctions.fx` |
| IBL | `Materials/materialHelper.functions.ts:288-360`、`Shaders/ShadersInclude/pbrBlockReflection.fx`、`Misc/brdfTextureTools.ts` |
| 阴影 | `Lights/Shadows/shadowGenerator.ts`、`Shaders/ShadersInclude/shadowsFragmentFunctions.fx` |
| CSM | `Lights/Shadows/cascadedShadowGenerator.pure.ts`、`Shaders/ShadersInclude/lightFragment.fx:274-348` |
| RSM GI | `Rendering/reflectiveShadowMap.pure.ts`、`Rendering/GlobalIllumination/giRSMManager.pure.ts` |
| 后处理基类 / 链 | `PostProcesses/postProcess.pure.ts`、`PostProcesses/postProcessManager.ts:182-243` |
| 默认后处理顺序 | `PostProcesses/RenderPipeline/Pipelines/defaultRenderingPipeline.pure.ts:591-752` |
| FrameGraph | `FrameGraph/frameGraph.ts`、`frameGraphTextureManager.ts`、`FrameGraph/Tasks/**` |
| G-buffer | `Rendering/prePassRenderer.pure.ts`、`Rendering/geometryBufferRenderer.pure.ts` |
| SSAO2 / SSR | `PostProcesses/RenderPipeline/Pipelines/ssao2*`、`ssr*`、`Shaders/ssao2.fragment.fx`、`Shaders/ShadersInclude/screenSpaceRayTrace.fx` |
| OIT / 描边 | `Rendering/depthPeeling*`、`Rendering/outlineRenderer.pure.ts`、`Rendering/edgesRenderer.pure.ts` |
| 剔除 / 实例 | `scene.pure.ts:4908-5013`、`Culling/boundingInfo.ts:200-238`、`Meshes/mesh.pure.ts:2246-2344` |
| 天空盒 | 见 `doc/Babylon_Skybox_Reference.md` |

---

## 12. 着色器源码可移植性（能不能照抄）

**结论：能参考，但要分层——纯数学函数可直接搬（只改写 I/O 与声明），绑定/声明/入口层一律重写。**

### 12.1 文件形态与语言差

- 规模：`Shaders/*.fx` 168 个 + `Shaders/ShadersInclude/*.fx` 179 个；WGSL 平行一套（174 / 161）。
- **语言风格是 WebGL ES**：`precision highp float`、`varying` / `attribute`、`texture2D`、`gl_FragColor` / `gl_FragData[]`、`#extension`。
- **我方是 Vulkan GLSL 450**：`in` / `out`、`texture()`、显式 `layout(location/set/binding)`、**无 `#include`**（`.shader` 自包含）。
- 处理模型：Babylon 靠构建期/运行期 `#include<>` 展开 + define 注入（`Engines/Processors/shaderProcessor.ts:422-527`）+ **UBO/标量双通道**（`lightUboDeclaration.fx` vs `lightFragmentDeclaration.fx` 同字段两种声明）。我方的对应物是导入器在 `#version` 后注入 `cam` / `pc` / `MaterialBlock` / sampler。

**因此搬运动作 = 函数体原样保留 + I/O 与声明重写 + include 传递闭包内联。** 第三步 Babylon 自己的构建链（`buildShaders.ts`）就是这么做的，可作先例。

### 12.2 可移植性分层

| 层 | 代表 | 可搬性 |
|----|------|--------|
| 纯数学函数 | `pbrDirectLightingFalloffFunctions` / `pbrBRDFFunctions` / `harmonicsFunctions` / `shadowsFragmentFunctions` / `ssao2` | **高**：原样，仅需内联 `helperFunctions` |
| 结构体 + 多灯循环 | `lightsFragmentFunctions`（`lightingInfo`）/ `clusteredLightingFunctions` | **中**：算法可搬，取值需按我方 SSBO/UBO 重写 |
| 声明与绑定 | `lightUboDeclaration` / `lightFragmentDeclaration` / `cam` / `material` | **不可**：命名、布局、通道全不同 |
| 入口与 I/O | `pbr.fragment.fx` 的 `main` / `gl_FragData[]` | **不可**：MRT、深度、输出全要按我方管线重写 |
| 宏 / 注入锚 | `#define CUSTOM_FRAGMENT_*` | **不可**：那是插件注入机制，我方是编译期 define |

### 12.3 高价值纯函数清单（逐条已核）

依赖统一说明：以下若无特别注明，均**无 `#include`**，只依赖 `ShadersInclude/helperFunctions.fx` 的 `PI` / `saturate` / `maxEps` / `Epsilon`（`helperFunctions.fx:1-20`）——内联时要一并带上。

| 函数 | 位置 | 签名 / 用途 | 依赖 |
|------|------|-------------|------|
| 距离光衰减 | `pbrDirectLightingFalloffFunctions.fx:1/6/11/25` | `computeDistanceLightFalloff_Standard/Physical/GLTF` + 分发 | 仅 helper |
| 方向光衰减 | 同上 `:36/49/56/74/87` | `Standard/IES/Physical/GLTF`（含聚光 von Mises-Fisher 锥） | 仅 helper |
| GGX 法线分布 | `pbrBRDFFunctions.fx:338` | `normalDistributionFunction_TrowbridgeReitzGGX(NdotH, alphaG)` | 仅 helper |
| Burley 漫反射 | `:496` | `diffuseBRDF_Burley(NdotL,NdotV,VdotH,roughness)` | 仅 helper |
| EON 漫反射 | `:513/524` | `E_FON_approx` / `diffuseBRDF_EON` | 仅 helper |
| Fresnel | `:186/191` | `fresnelSchlickGGX`（vec3/float 两版） | 仅 helper |
| 虹彩薄膜 | `:259` | `evalIridescence(...)` | 仅 helper |
| 球谐环境辐照度 | `harmonicsFunctions.fx:10/25` | `computeEnvironmentIrradiance(normal)`（Sloan L 基 / Jones SP 基） | 无 |
| PCF 1/3/5 tap | `shadowsFragmentFunctions.fx:382/402/443` | 硬件比较采样器 + 亚纹素权重复现 3×3/5×5 高斯 | 无（同文件 `unpack`/`computeFallOff`） |
| CSM PCF 1/3/5 | 同上 `:275/297/338` | 带 `layer` 的 `sampler2DArrayShadow` 版本 | 无 |
| PCSS | 同上 `:694`（+16/32/64 包装） | blocker 搜索 + 可变半径 PCF，`vec3[64] poissonSamplers` | 无 |
| 单次阴影 / ESM | 同上 `:143/207/233/167` | 单次比较 / ESM / CloseESM / Poisson | 无 |
| Clustered 取值 | `clusteredLightingFunctions.fx:1/10/20` | `struct ClusteredLight` + `getClusteredLight` + `getClusteredSliceIndex` | 无 |
| Clustered 完整消费 | `pbrClusteredLightingFunctions.fx:1-175` | `computeClusteredLighting(...)` | **需连带** `clusteredLightingFunctions` + `pbrBlockReflectance0` |
| SSAO + 双边模糊 | `Shaders/ssao2.fragment.fx` 整文件 | 半球核 + 噪声旋转 + 深度淡出 + H/V 保边模糊 | 无 |
| 可分离高斯 | `Shaders/kernelBlur.fragment.fx` + CPU 生成 `#define KERNEL_OFFSETi/WEIGHTi` | 全屏模糊核（核在 CPU 折叠成硬件线性 tap） | 无 |
| 屏幕空间光追 | `Shaders/ShadersInclude/screenSpaceRayTrace.fx:1-349` | `traceScreenSpaceRay1(...)`（McGuire 步进 + refinement） | 无 |
| 色彩管线 | `Shaders/imageProcessing.fragment.fx` | tonemap（ACES / KHR PBR Neutral）/ 色轮 / 白平衡 / 暗角 | 无 |
| 非 PBR 光照循环 | `lightsFragmentFunctions.fx:2/60/232-285` | `struct lightingInfo` + `getAttenuation` + 简化 `computeClusteredLighting` | 含 `ltcHelperFunctions`、`clusteredLightingFunctions` |

### 12.4 移植坑（逐条）

1. **关键字映射**：`varying`→`in/out`、`attribute`→`layout(location) in`、`texture2D/textureCube`→`texture()`、`gl_FragColor`→显式 `out`、`gl_FragData[i]`→多 `out`、`#extension GL_EXT_shader_texture_lod`→直接 `textureLod`、`gl_FragDepthEXT`→`gl_FragDepth`。
2. **`highp sampler2DShadow`**：Vulkan 侧比较采样要在采样器对象上设 `compareEnable`，用 `textureProj` / `texture(sampler2DShadow, vec3)`；**reversed-Z 会翻转比较操作符**——我方的投影/深度口径与 Babylon 未必一致，搬 PCF 时必须对齐自己的深度方向（我方深度比较采样器已随批次④-A 定过口径）。
3. **`sampler2DArray`**：CSM 的分层图在 Vulkan 是 `sampler2DArrayShadow` + `textureGrad`/比较采样，跨平台无差别，但层索引与 `vPositionFromCamera` 选层逻辑要按我方光矩阵布置衔接。
4. **`#include` 内联**：我方 `.shader` 禁 include，搬函数时要把传递闭包展开；`helperFunctions` 是几乎所有数学块的公共前置。
5. **保留字冲突**：我方光照保留字（`lighting` / `lightList` / `clusterGrid` / `clusterIndices` / `shadowMap` 及各块名）构建期报错——搬函数名不冲突，但别把变量命名成这些。
6. **不要带过来的分支**：`REFLECTIONMAP_OPPOSITEZ`（右手/`invertZ` 调整）、`y = 1.0 - y` 的 uv 翻转、`LOGARITHMICDEPTH`、`IS_NDC_HALF_ZRANGE`——这些是 Babylon 的后端/坐标约定，我方 Y 翻转已入投影矩阵，照搬会双重翻转。
7. **精度**：`precision highp float` 在 Vulkan GLSL 450 里无副作用可保留；但 ES 的 `mediump` 默认在 Vulkan 不适用。

### 12.5 许可证

`license.md` = **Apache License 2.0**（宽松）。逐行复制代码需保留版权与 NOTICE 署名；纯算法/公式本身不受版权保护，但整段照搬函数体时建议在文件头注明来源（`Babylon.js` + 文件名 + commit），既是合规也是溯源。

---

## 13. Gizmo 模块（附：编辑器辅助，超出前文渲染技术边界）

> 本章是应「gizmo 源码」问题追加的附章——gizmo 属编辑器工具层，与本档渲染技术主线相邻但不同类；正因为本引擎已有自己的 TransformGizmo，对照价值高，故成章。

### 13.1 有，且是独立模块

`packages/dev/core/src/Gizmos/`，15 个文件、**约 6000 行**（`wc -l` = 6002）。不是散在别处的小组件，而是一套完整的基类 + 子类 + 管理器结构。

### 13.2 基类 `Gizmo`（`Gizmos/gizmo.ts:125-691`）

- 持有 `_rootMesh`（utilityLayerScene 里的根网格，`:316`）；所有子网格挂其下，**几何体按「约 0.3 单位」的小尺寸建模，真实世界尺寸由 `_rootMesh.scaling` 给出**（见 §13.3）——所以整套 gizmo 共享一个缩放。
- 挂载：`attachedMesh` / `attachedNode`（`:183-201`）；两者并存时 mesh 优先（`:398-402`）。
- 位姿对齐开关：`updateGizmoPositionToMatchAttachedMesh` / `updateGizmoRotationToMatchAttachedMesh`（`:248-261`）。
- 选项：`anchorPoint`（`GizmoAnchorPoint.Origin` / `Pivot`，`:268`）、`coordinatesMode`（`Local` / `World`，`:280`）、`customRotationQuaternion`（`:328`）、`additionalTransformNode`（`:230`，用于把 gizmo 摆到父级坐标系）、`scaleRatio`（`:160`）。
- 静态：`PreserveScaling` / `UseAbsoluteScaling`（`:148-155`），处理非均匀缩放父级的坑。
- `setCustomMesh` 强制自定义网格必须属于 `gizmoLayer.utilityLayerScene`，否则抛异常（`:212-216`）。
- **`_update()` 订阅 utilityLayerScene 的 `onBeforeRenderObservable`**（`:319-320`），每帧按 attachedNode 的世界矩阵刷新位置/旋转/缩放。

### 13.3 距离自适应缩放（与本引擎同款能力）

`_update()` 的缩放段（`gizmo.ts` 内 Scale 分支）：

```
scale = scaleRatio
透视：scale *= dot(offsetToCamera, cameraForward)      // 与相机距离成正比 → 屏幕尺寸恒定
正交：scale *= (orthoTop - orthoBottom)
```
另有 `additionalTransformNode` 的反向最大缩放补偿，以及 **handedness 处理**：附着实体的世界矩阵行列式 < 0 时 `scaling.y *= -1`（`:157` 附近的 `_getWorldMatrixDeterminant() < 0` 分支）。

> **对照我方**：本引擎 `TransformGizmoAppState` 的距离自适应缩放与此同构。Babylon 多两条可借鉴的边角：**正交相机的 `orthoHeight` 换算**、**负行列式父级下的 y 翻转**——若我方将来支持正交编辑相机或镜像父级，这两处是现成公式。

### 13.4 拾取：独立的「放大不可见 collider」

`GizmoAxisCache`（`gizmo.ts:20-40`）每个轴缓存：`gizmoMeshes` / **`colliderMeshes`** / `material` / `hoverMaterial` / `disableMaterial` / `active` / `dragBehavior`。

**关键手法**：每个轴同时建两份几何——可见的细箭头 + 一份**加粗（`thickness + 4`）且 `visibility = 0` 的 collider 副本**（`axisDragGizmo.ts:176`、`_CreateArrow` 的 `isCollider` 分支 `:87-124`）。拾取走引擎 picking 命中 collider，可见网格只负责显示。这比自写射线-几何相交省一大截代码，且拾取手感天然更宽容。

> **对照我方**：~~本引擎走 `CylinderCollider` + 自写射线最近命中~~ → 2026-10-23 曾迁 GPU ID-buffer（PickPass 覆盖制画 `Gizmo` 桶、柄几何各写 pick ID，编辑器侧射线拾取退役）→ **2026-11-08 用户改判、本条思路被采纳**：ID 附件是「看得见即拾得」，细轴不好选、环只能加粗可见管径；改按本条——**可见几何与拾取体解耦**，拾取体更粗（轴 = 加粗圆柱 `CylinderCollider` 半径 `0.022`、环 = 解析式环带容差 `0.015`（= Babylon 粗环半径）、中心柄 = `0.07` 胖方块），走 `Collider.collideWithRay` 直调（不经场景注册表）；Gizmo 从此不进 GPU 拾取附件，场景物体拾取仍走 ID-buffer。环无对应 L0 碰撞形状，故用解析式环带表达「加粗圆环副本」。

### 13.5 交互：`PointerDragBehavior`

拖拽不由 gizmo 自己实现，而是每个轴挂一个 `PointerDragBehavior({ dragAxis })`（`axisDragGizmo.ts:190`），由行为负责拖拽平面、鼠标增量、`onDragObservable`。旋转 gizmo 另有 `sensitivity`、`useEulerRotation`（`rotationGizmo.ts:38/56/170-180`）。

### 13.6 三态材质

`material` / `hoverMaterial` / `disableMaterial` 三份 StandardMaterial，按 `dragBehavior.enabled` 与 hover/active 切换（`gizmo.ts:633-666`）；悬停/激活通常换黄（如 `scaleGizmo.ts:251` hover→`Color3.Yellow()`）。逐轴配色由父级传入：X=红/Y=绿/Z=蓝，各 `scale(0.5)`（`positionGizmo.ts:195-201`、`scaleGizmo.ts:211-213`）。

> **对照我方**：我方 `updateVisuals` 三态（高亮黄 / 近平行 `|cos|>=0.985` 变灰 0.5）多了一条「近平行轴变灰」的几何可用性判定，**Babylon 没有这个**——它是按 `dragBehavior.enabled` 灰显（如 boundingBox 零轴方向），不是按视角夹角。这条是我方自己的设计，不要在对照时误以为可回退。

### 13.7 子类清单

| 类 | 文件 | 要点 |
|----|------|------|
| `AxisDragGizmo` | `axisDragGizmo.ts`（330） | 单轴箭头（锥 + 细圆柱，`_CreateArrow:87-124`） |
| `PlaneDragGizmo` | `planeDragGizmo.ts`（269） | 平面拖动（双轴） |
| `AxisScaleGizmo` | `axisScaleGizmo.ts`（415） | 单轴缩放（`CreateBox` + `CreateCylinder`，`:324-325`） |
| `PlaneRotationGizmo` | `planeRotationGizmo.pure.ts`（529） | 单平面旋转环（`CreateTorus`，`:451/461`） |
| `PositionGizmo` | `positionGizmo.ts`（413） | x/y/z 轴 + 三个平面 gizmo 组合 |
| `RotationGizmo` | `rotationGizmo.ts`（412） | x/y/z 环组合，含 `useEulerRotation` / `sensitivity` |
| `ScaleGizmo` | `scaleGizmo.ts`（469） | x/y/z 轴 + 中心均匀缩放（灰） |
| `BoundingBoxGizmo` | `boundingBoxGizmo.ts`（1114） | 8 角缩放盒 + 旋转锚点 + 旋转球；`onDragStartObservable` 带 `DragOperation`（`:189`、`:659-660`） |
| `CameraGizmo` / `LightGizmo` / `SpatialAudioGizmo` | 各文件 | 相机/灯/空间音源的图标式 gizmo |

### 13.8 `GizmoManager`（`gizmoManager.ts:21-464`）

统一管理 position/rotation/scale/boundingBox 四个 gizmo 的启用开关（`:307-318` 等），并负责**指针点选自动挂载**（`usePointerToAttachGizmos` / `enableAutoPicking`，`:206-246`）。

### 13.9 渲染载体：utility layer（与我方 GizmoPass 的差异）

Babylon gizmo 全部活在 `UtilityLayerRenderer` 的**独立虚拟 Scene** 里（`Rendering/utilityLayerRenderer.ts`）：`autoClear=false`、无后处理、在主场景相机渲染后叠加。本引擎则用**覆盖制 `GizmoPass`**（颜色 LOAD + 深度 CLEAR）实现「恒不被场景遮挡 + 内部自写自测层次」。

两者对外表现等效，差别在附带能力：Babylon 的独立场景天然带**独立拾取优先级**（utility 场景先拣）与**独立光照**（gizmo 专用 hemispheric light）；我方 `GizmoPass` 更轻、无第二场景开销。**若无「编辑器临时对象 + 独立拾取序」的需求，维持 Pass 方案即可**；这与我方 `Editor_DebugLine_Design` 的覆盖制 Pass 选择同源。

### 13.10 轴/部件网格参数表（全部程序化，**无任何资产文件**）

全模块 grep 无 `SceneLoader` / `loadAssetContainer` / `.glb` / `.babylon`——所有几何都由 `Meshes/Builders/*` 现建。几何按「约 0.3 单位」建模，真实世界尺寸由 `_rootMesh.scaling`（距离自适应，§13.3）统一给出，所以各 gizmo 参数里没有物理尺寸概念。

| 部件 | 生成 | 关键参数 | 出处 |
|------|------|----------|------|
| 移动箭头·头 | `CreateCylinder`（锥：`diameterTop=0`） | `height=0.075`、`diameterBottom=0.0375*(1+(thickness-1)/4)`、`tessellation=96`；`rot.x=π/2`、`z+=0.3` | `axisDragGizmo.ts:89-104` |
| 移动箭头·杆 | `CreateCylinder` | `diameterTop/Bottom=0.005*thickness`、`height=0.275`、`tessellation=96`；`z+=0.1375` | `axisDragGizmo.ts:99-107` |
| 缩放轴·头 | `CreateBox` | `size=0.4*(1+(thickness-1)/4)` 再 `scaling.scaleInPlace(0.1)`；`rot.x=π/2`、`z+=0.3` | `axisScaleGizmo.ts:324-331` |
| 缩放轴·杆 | `CreateCylinder` | 同移动杆（`0.005*thickness` / `0.275` / 96） | `axisScaleGizmo.ts:325-335` |
| 旋转环 | `CreateTorus` | `diameter=0.6`、`thickness=0.005*thickness`、`tessellation=32`（`RotationGizmo` 默认）；`rot.x=π/2` | `planeRotationGizmo.pure.ts:461-472`、`rotationGizmo.ts:219` |
| 平面拖动·面 | `CreatePlane` | `width/height=0.1375`、`sideOrientation=2`（双面）；整体 `scaling ×1/3` 后 `lookAt` 轴法线 | `planeDragGizmo.ts:91`、`:131-132` |
| 包围盒·角缩放块 | `_getCornerMesh` = `Mesh.MergeMeshes` | 3×`CreateBox`（长轴 `1.6` / 其余 `0.4`，正向偏移 `0.6`）合并成一个 L 形 | `boundingBoxGizmo.ts:742-752` |
| 包围盒·旋转锚点 | `CreateBox` | 三向各 `1.6 / 0.4 / 0.4` | `boundingBoxGizmo.ts:388` |
| 包围盒·单轴缩放块 | `CreateBox` | `size=1`（零轴方向时） | `boundingBoxGizmo.ts:523` |
| 灯 gizmo | `CreateSphere` / `CreateCylinder` | `segments=10`、`diameter=1`（点光）；`1.2`（聚光）+ 锥形箭头 | `lightGizmo.ts:324-383` |
| 相机 gizmo | `CreateBox` / `CreateCylinder` | 机身盒 + 镜筒圆柱 | `cameraGizmo.ts:10-11` |

**collider 副本规律**（§13.4）：移动/缩放轴 = 同几何 **`thickness+4`** 再加粗；旋转环 = **`thickness=0.03*thickness`** 的粗环；两者 `visibility=0`。另 `AxisDragGizmo._CreateArrowInstance`（`:131-140`）用 `createInstance` 复用箭头网格，避免多轴重复建几何。

> **对我方的直接可用性**：这套参数就是一份现成的「gizmo 造型规格」——箭头 = 锥(`0.075` 高 / `0.0375` 底径，96 边) + 细杆(`0.005` 径 / `0.275` 长)，环 = 环径 `0.6` / 管径 `0.005`，都是在 0.3 单位基准下的无量纲比例。本引擎若自建模网格（非资产），可直接照这组比例换算。
>
> **已落地（2026-11-08）**：本引擎即照此比例换算了——`javelin-editor/.../editor/mesh/GizmoMeshes` 运行期程序化生成箭头/缩放柄/环/方块（模型 +Y 本体、节点一次旋转指向轴；外向绕序），三工具共用「箭头全长 `0.3375` = gizmo 单位」的缩放基准，原 `axisY.smesh` 资产退役。**拾取体同样照 §13.4 的加粗规则取值**（轴圆柱半径 `0.022`、环带容差 `0.015`、中心方块 `0.07`），与可见几何解耦——可见环保持 Babylon 的 `0.005` 细管径。

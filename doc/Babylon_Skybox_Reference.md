# Babylon.js 天空盒实现参考（Babylon Skybox Reference）

> 版本：v0.1（2026-11-07 建档）
> 定位：外部引擎（Babylon.js）「视口天空盒」实现速查——供本引擎天空盒后续批（程序化天空 / 天空盒环境光 / 四期）与排障对照。
> 来源：本地检出 `D:\Workspaces\java\Babylon.js`（**外部目录，不随本库版本管理**），主干提交 `daae8d9`（2026-11-06）；core 源码根 `packages/dev/core/src`，下文行号以该检出为准。
> 取材口径：结论逐条对照源码原文核实（随文标 `文件:行`），未核实的推断不写；与本引擎的对照项取自 `Engine_Skybox_Design.md` v0.3 与实装代码（`SkyboxPass.java` / `builtin-asset-core/skybox/*`）。
> 边界：只管「把 cubemap 当背景画出来」这条通路；`scene.environmentTexture` 的球谐/预过滤 IBL、`ReflectionProbe` 只记与天空盒的分界点，不展开。

---

## 1. 骨架一句话

**Babylon 的天空盒不是引擎特性，而是「普通网格 + 普通材质」的字段组合**：一个立方体网格、材质把 `reflectionTexture` 置为 `SKYBOX_MODE`（用顶点局部坐标当方向采样 cube）、网格 `infiniteDistance = true`（世界矩阵跟着相机走）、`ignoreCameraMaxZ = true`（绘制期间把相机远平面改成无限远）。没有专用渲染 Pass、没有清屏标志、没有 `scene.skybox` 字段——`Scene.prototype.createDefaultSkybox` 的全部内容就是这些字段的赋值（`Helpers/sceneHelpers.pure.ts:100-139`）。

由此派生出三条与本引擎 v0.3 正交的设计后果：

- **清屏与天空盒解耦**：清屏照常执行，天空盒只是「画在最远处的几何」；没覆盖到的像素露出清屏色，不存在「天空盒必须全屏覆盖」的隐式契约；
- **深度语义是普通不透明物**：写深度、走深度测试（除非材质 `disableDepthWrite`），被遮挡与遮挡透明物体全由深度测试自然成立，没有 Pass 位置约定；
- **跟随相机是世界矩阵逐帧重算**（`Meshes/transformNode.pure.ts:1119-1129`），不是着色器里的技巧。

## 2. 几何与面向

- **网格**：`CreateBox(name, { size }, scene)`（`Helpers/sceneHelpers.pure.ts:113`）。`createDefaultSkybox` 的 `scale` 默认 **1000**；`EnvironmentHelper` 默认 `skyboxSize = 20`（`Helpers/environmentHelper.ts:235`），并按场景包围盒放大（`skyboxSize *= 1.5`，`environmentHelper.ts:531`；无外部网格时取地面尺寸）。盒体尺寸只是「相对相机足够大」，没有物理含义。
- **两种面向做法并存**（等效，二选一）：
  - `createDefaultSkybox`：材质 `backFaceCulling = false`，双面渲染（`sceneHelpers.pure.ts:116/127`）；
  - `EnvironmentHelper._setupSkybox`：几何侧 `sideOrientation: Mesh.BACKSIDE`（`environmentHelper.ts:658`）——`VertexData._ComputeSides` 对 BACKSIDE 做「索引每三角首尾互换 + 法线取反」（`Meshes/mesh.vertexData.ts:2239-2271`），于是从盒内看到的是正面，背面剔除可保持开启。
- **不需要 UV**：天空盒采样用顶点局部位置当方向（§5），Cube 顶点数据里 `uvs/normals` 虽生成但不参与天空盒着色路径。

## 3. 跟随相机：`infiniteDistance`

- `TransformNode` 上的布尔量（`@serialize("infiniteDistance")`，`transformNode.pure.ts:111-126`），含义 = 「对象到相机的距离恒不变」。
- 世界矩阵计算（`transformNode.pure.ts:1119-1129`）：`translation = this._position + cameraWorldMatrix 的平移列`，**仅在无父节点时生效**（`isWorldMatrixCameraDependent()` = `this._infiniteDistance && !this.parent`，`transformNode.pure.ts:1072-1079`）。
- 该网格的世界矩阵**每帧必定重算**：`_isSynchronized()` 见到 `_infiniteDistance` 直接返回 false（`transformNode.pure.ts:338-341`），缓存不参与。
- 视锥剔除无需特判：盒子恒包住相机，包围盒天然在视锥内。反向的例外是「场景包围盒统计」要跳过它（`scene.pure.ts:6211`，`getWorldExtends` 忽略 `mesh.infiniteDistance`，否则自动取景会被天空盒撑爆）。
- 跳帧快照渲染（`engine.snapshotRendering`）下，其它网格可跳过世界矩阵更新，但 infiniteDistance 网格必须每帧 `transferToEffect(computeWorldMatrix(true))`（`Misc/snapshotRenderingHelper.ts:118-124`）——相机世界矩阵相关，缓存即错。

> **注意一处常被误以为等同于 infiniteDistance 的地方**：`EnvironmentHelper` 的天空盒**没有设** `infiniteDistance`（`environmentHelper.ts` 全文无该字段），它是挂在 `_rootMesh` 下的**固定位置盒体**，靠「尺寸相对场景足够大 + 相机不离开」工作；`rotation.y = backgroundYRotation` 也是转根节点（`environmentHelper.ts:474`），而不是让盒体跟随相机。真正跟相机走的是 `createDefaultSkybox` 那条路径。

## 4. 远平面：`ignoreCameraMaxZ`（本引擎 `z = w` 的同族另一路线）

- `Mesh.ignoreCameraMaxZ`（`Meshes/mesh.pure.ts:642`，默认 false；`createDefaultSkybox` 置 true，`sceneHelpers.pure.ts:137`）。
- 实现是一次**临时全局改写**：绘制该子网格前记下 `scene.activeCamera.maxZ`，置 0 并 `scene.updateTransformMatrix(true)`，绘制结束（含每条提前返回路径）恢复原值（`mesh.pure.ts:2750-2757` 设置、`2903-2906` 正常恢复，`2772-2838` 各提前返回处恢复）；中间渲染（中间 RTT）期间跳过该改写（`!scene._isInIntermediateRendering()`）。
- `maxZ = 0` 在 Babylon 是**约定的「无限远平面」**：`Matrix.PerspectiveFovLHToRef` 在 `f = 0` 时取 `c = 1、d = -2n`（`Maths/math.vector.pure.ts:8507-8509`，RH 版同构 8603+），即不按远平面裁切。相机侧也有同名开关 `Camera.ignoreCameraMaxZ`（`Cameras/camera.pure.ts:382`、`690/973`），那是全局无限远投影。
- **对照**：本引擎在顶点着色器里 `gl_Position.z = w` 推到远平面（`builtin-asset-core/skybox/skybox.vert`），Babylon 改投影矩阵。两者同族——都是「让天空盒不被远平面裁掉、且不污染深度」；差别是 Babylon 的写法有全局副作用窗口（改写相机并强制重算 viewProj），所以只在绘制该网格的瞬间生效，而顶点推远是纯局部、与其它绘制无关。

## 5. 采样通路：`SKYBOX_MODE`

**定义链**：`Texture.SKYBOX_MODE` = `Constants.TEXTURE_SKYBOX_MODE` = 5（`Engines/constants.ts:441`、`Materials/Textures/texture.pure.ts:185`）。材质准备阶段按 `reflectionTexture.coordinatesMode` 打宏 `REFLECTIONMAP_SKYBOX`（`Materials/materialHelper.functions.ts:944-963`，天空盒分支在 953）。

**顶点侧**（`Shaders/background.vertex.fx:46-48、63-65`）：

```glsl
varying vec3 vPositionUVW;
...
vPositionUVW = position;      // 局部位置，不归一化
```

**片元侧**（`Shaders/ShadersInclude/reflectionFunction.fx:98-101、141`）：

```glsl
vec3 computeSkyBoxCoords(vec3 positionW, mat4 reflectionMatrix) {
    return vec3(reflectionMatrix * vec4(positionW, 1.));   // 注意 w=1
}
...
#ifdef REFLECTIONMAP_SKYBOX
    return computeSkyBoxCoords(vPositionUVW, reflectionMatrix);
#endif
```

采样宏：`REFLECTIONMAP_3D` 时即 `textureCube(reflectionSampler, coords)`（`Shaders/background.fragment.fx:39`），二维图则走 `texture2D` + `y = 1.0 - y` 翻转（`background.fragment.fx:50、206`）。要点：

| 细节 | 事实 | 出处 |
|------|------|------|
| 方向是否归一化 | **不归一化**——立方图采样只关心方向，长度无关；对本引擎「世界方向插值后 normalize」是等效写法 | 上引 vs/fs |
| 反射矩阵 | `BaseTexture.getReflectionTextureMatrix()` 默认返回单位阵；`CubeTexture` 用自持 `_textureMatrix`，`rotationY` 会把它设成 `Matrix.RotationY` | `baseTexture.pure.ts:604-606`；`cubeTexture.pure.ts:169、225-227、rotationY` |
| 天空盒旋转 | 两条路：旋转盒体父节点（EnvironmentHelper 的 `backgroundYRotation`）或设 cube 纹理 `rotationY`（经反射矩阵进着色器） | `environmentHelper.ts:474`；`cubeTexture.pure.ts` `rotationY` |
| 寻址模式 | `isCube` 且模式为 `CUBIC_MODE` / `SKYBOX_MODE` 时强制 **CLAMP_TO_EDGE**，其它模式 REPEAT | `Engines/thinEngine.pure.ts:4107-4116`；WebGPU 同 `webgpuEngine.pure.ts:3055-3065` |
| 右手系 | `REFLECTIONMAP_OPPOSITEZ` 时把采样向量 `z *= -1`（右手系/`invertZ` 换算） | `materialHelper.functions.ts:931`；`background.fragment.fx:194` |
| gamma | `GAMMAREFLECTION`（= 纹理 `gammaSpace`）时 `toLinearSpace`；PBR 走线性、StandardMaterial 走 gamma 是历史分叉 | `background.fragment.fx:244`；`sceneHelpers.pure.ts`（PBR/Standard 分支同贴图） |
| 模糊 / 粗糙度 | 有 `REFLECTIONBLUR` 时按 `vReflectionInfos.y` 选 mip LOD（`textureCubeLodEXT`），无该扩展时用高低两张图 mix 近似 | `background.fragment.fx:219-243` |
| 不参与球谐 | `coordinatesMode === SKYBOX_MODE` 时**显式跳过** `USESPHERICALFROMREFLECTIONMAP`（不把该贴图当辐照度球谐源） | `materialHelper.functions.ts:976-993` |

## 6. 材质三形态与两条装配路径

| 材质 | 天空盒用法 | 关键设置 | 出处 |
|------|-----------|----------|------|
| `StandardMaterial` | `reflectionTexture` = cubemap + `SKYBOX_MODE` | `backFaceCulling=false`、`disableLighting=true` | `sceneHelpers.pure.ts:126-134` |
| `PBRMaterial` | 同上，另有 `microSurface` 控制模糊 | `microSurface = 1 - blur`、`disableLighting=true`、`twoSidedLighting=true` | `sceneHelpers.pure.ts:114-124` |
| `BackgroundMaterial` | 背景专用材质（`EnvironmentHelper` 用） | `useRGBColor=false`、`primaryColor = skyboxColor`、`enableNoise=true`；另有 `projectedGround`（地面投影到球面，供地面阴影用）、`opacityFresnel`（贴地淡出）、`fovMultiplier`（全景缩放） | `environmentHelper.ts:670-686`；`Materials/Background/backgroundMaterial.pure.ts:361-509、691-697` |

**两条装配路径**：

1. `Scene.createDefaultSkybox(environmentTexture, pbr=false, scale=1000, blur=0, setGlobalEnvTexture=true)`（`sceneHelpers.pure.ts:100-139`）：
   - 无贴图时 `Logger.Warn` 返回 `null`（不抛错）；
   - `setGlobalEnvTexture` 默认 true → **同一张 cubemap 同时成为 `scene.environmentTexture`**（即天空盒贴图顺带入 IBL）；
   - `reflectionTexture = environmentTexture.clone()`（克隆是为了让 `coordinatesMode` 只作用于天空盒这一份）；
   - 末尾统一 `isPickable = false`、`infiniteDistance = true`、`ignoreCameraMaxZ = true`。
2. `new EnvironmentHelper(options, scene)`（`Helpers/environmentHelper.ts`）：地面 + 天空盒 + BackgroundMaterial 的打包助手，默认贴图为 CDN 上的 `.dds`（`environmentHelper.ts:202`），默认色 `(0.2, 0.2, 0.3)` 线性放大 ×3（`environmentHelper.ts:237`）。

> **一处坑（默认助手未处理）**：`Material.fogEnabled` 默认 true（`Materials/material.pure.ts:776-794`），背景/天空盒着色器尾部也照常 `#include<fogFragment>`（`background.fragment.fx:338`）。也就是说场景开了雾时，天空盒默认会被雾影响，需要手工 `skyboxMaterial.fogEnabled = false`——Babylon 的两条默认装配路径都没做这一步。

## 7. 资产形态与全景转换

**cubemap 来源（`CubeTexture` 家族）**：

- `CubeTexture`：6 张图 / `.dds` / `.env`（预过滤 mip 链）/ `.ktx` / `.basis`；`isCube = true`、默认 `CUBIC_MODE`（`Materials/Textures/cubeTexture.pure.ts:73、169、225-227`）。支持 `gammaSpace`、`prefiltered`、`lodScale/lodOffset`、`boundingBoxSize`（局部反射，Unity 式 local cubemap）。
- `.env`：Babylon 自有的环境贴图容器（`Misc/environmentTextureTools.pure.ts`，`GetEnvInfo` 解析版本 1/2），存预过滤 mip 链与辐照度信息，天空盒（低 mip）与 IBL 可共用一份。
- `HDRCubeTexture`：**.hdr（RGBE 全景）在运行期 CPU 转六面**（`Materials/Textures/hdrCubeTexture.pure.ts:17-83`；解码 `Misc/HighDynamicRange/hdr.ts:146-168` 的 `GetCubeMapTextureData` → `PanoramaToCubeMapTools.ConvertPanoramaToCubemap`）。
- `EquiRectangularCubeTexture`：浏览器可解码的等距柱状图（png/jpg）→ 六面（`Materials/Textures/equiRectangularCubeTexture.pure.ts:16-185`，面映射表 `["right","left","up","down","front","back"]` 对应 +X/-X/+Y/-Y/+Z/-Z）。
- `PhotoDome` / `TextureDome`：**不转 cube 的另一条路线**——反向球体（`sideOrientation: BACKSIDE`）+ `BackgroundMaterial`，纹理设 `FIXED_EQUIRECTANGULAR_MIRRORED_MODE`（`Helpers/textureDome.ts:37、64、243`；`Helpers/photoDome.ts`）。支持半球、立体（TopBottom/SideBySide）、`fovMultiplier` 缩放。

**equirect → cube 的转换约定（与本引擎 `EquirectConverter` 可直接对照）**——`Misc/HighDynamicRange/panoramaToCubemap.ts`：

| 环节 | Babylon 做法 | 位置 |
|------|-------------|------|
| 面方向表 | 6 面各 4 个角点方向向量（FRONT/ BACK/ LEFT/ RIGHT/ UP/ DOWN），面内双线性步进取方向后归一化 | `panoramaToCubemap.ts:70-75`、`105-145` |
| 球面坐标 | `theta = atan2(dir.z, dir.x)`、`phi = acos(dir.y)` | `panoramaToCubemap.ts:188-189` |
| 全景 UV | `dx = theta/π * 0.5 + 0.5`；`dy = phi/π`；`invertY` 时 `row = height - py - 1`（行序翻转） | `panoramaToCubemap.ts:196-219` |
| 取样 | **最近邻**（`Math.round` + clamp），可选 `supersample`（按源宽/4/faceSize 决定每像素采样数做平均） | `panoramaToCubemap.ts:150-153、220` |
| 产物 | `Float32` RGB、`gammaSpace=false`（线性 HDR），由纹理上传路径再处理 | `panoramaToCubemap.ts:114-122` |

> 与本引擎 `Engine_Skybox_Design §5` 的 u 公式（`atan2(dir.z, dir.x)/(2π)+0.5`，赤道中心 u=0.5 ↔ +X）**完全一致**；Babylon 多出的是「v 轴行序翻转参数」与「超采样」两个可选项，本引擎 v0.x 用固定 faceSize=512 + 双线性、无超采样。

## 8. 边界与特例清单

| 场景 | Babylon 行为 | 出处 |
|------|-------------|------|
| 拾取 | `isPickable = false`（默认助手唯一做的拾取处理） | `sceneHelpers.pure.ts:135` |
| 深度预通道 / SSAO 深度图 | 深度渲染器**跳过** `infiniteDistance` 或 `material.disableDepthWrite` 的子网格（否则无限远盒体污染深度预通道） | `Rendering/depthRenderer.pure.ts:248、278` |
| RTT / 反射探针 | **无专门排除**：天空盒网格与普通网格同权，默认渲染列表会把它一并画进 RTT（要排除靠 `layerMask`/渲染列表） | `scene.pure.ts:5222-5273`（RTT 收集） |
| `scene.environmentTexture` | 若它是 RTT（如 CubeTexture 渲染探针）会被自动加入每帧 RTT 渲染队列 | `scene.pure.ts:5233、5573` |
| 序列化 | `mesh.infiniteDistance` 进 `.babylon` 序列化；`mesh.ignoreCameraMaxZ` **不进**（快照渲染路径会把它复位为 false） | `mesh.pure.ts:4140、4568`；`snapshotRenderingHelper.ts:317` |
| 场景加载 | 文件里可声明 `createDefaultSkybox` + `skyboxScale` / `skyboxBlurLevel` 自动建盒 | `Loading/Plugins/babylonFileLoader.pure.ts:247-250` |
| XR | `WebXRBackgroundRemover` 进 AR 时禁用天空盒网格（`environmentHelperRemovalFlags.skyBox`） | `XR/features/WebXRBackgroundRemover.pure.ts:12-35` |
| 2D 背景层 | `Layer(isBackground)` 是**全屏 2D 层**（非 cubemap），与天空盒是两套东西，别混用 | `Layers/layer.ts:22-30` |
| `scene.skybox` 字段 | **不存在**——天空盒纯靠助手/手工装配，场景级无单一引用 | `scene.pure.ts:7041` 仅 `createDefaultSkybox` 的 side-effect 桩 |

## 9. 与本引擎对照（Engine_Skybox_Design v0.3）

| 维度 | Babylon.js（本检出） | 本引擎现状 |
|------|---------------------|-----------|
| 架构 | 天空盒 = 普通网格+材质，混在不透明渲染里；**无专用 Pass** | 覆盖制 `SkyboxPass`（管线 3.5 位：Opaque 后、Transparent 前） |
| 清屏 | 清屏与天空盒解耦，清屏色兜底未覆盖像素 | `ClearFlags.SKYBOX`：Opaque **照常清屏**，SkyboxPass 后画靠 LEqual 被遮挡；无资产回退 SOLID_COLOR |
| 远平面 | 绘制瞬间临时 `camera.maxZ = 0`（无限远投影），画完恢复 | vs `gl_Position.z = w`（顶点推远，纯局部） |
| 跟随相机 | 世界矩阵 = 相机世界平移 + 自身位置，逐帧重算；仅无父节点生效 | vs 内解相机世界位置 `p = -Rᵀ·t` 平移立方体顶点（2026-11-06 修法） |
| 面向 | `backFaceCulling=false`；或几何 `sideOrientation=BACKSIDE` | PSO `CullMode.NONE` |
| 几何 | `CreateBox({size})`，默认 1000 / 20，可被场景自动放大 | 内嵌 36 顶点立方体 VB |
| 采样方向 | 局部 position 直接当方向、不归一化 | 立方体坐标当世界方向，fs `normalize` |
| 材质面 | 三形态（Background / Standard / PBR），走材质体系；`microSurface` 控模糊 | 专属覆盖制 shader（不经材质面、不吃光照注入） |
| 资产 | CubeTexture 家族（6图/dds/env/ktx）+ 运行期 equirect→cube（HDRCubeTexture） | `CubemapImage` 资产：**导入期** equirect→六面；运行时恒 cubemap |
| 光照边界 | `SKYBOX_MODE` 显式切断「该贴图作球谐 IBL」；IBL 走 `scene.environmentTexture` 另一条 | 纯视觉、不作 ambient 源；环境光升级归四期 |
| 拾取 | `isPickable=false`（网格属性） | PickPass 只消费 `RenderObject`（opaque/transparent/gizmo 桶），天空盒是 Pass 级绘制，天然不进拾取 |
| 旋转 | 转父节点 或 cube `rotationY`（反射矩阵） | v0.x 未做（属后置项） |
| 模糊/mip | `blur`/`microSurface` + mip LOD 采样 | 后置（mip 链归 GI 期） |

**值得记下的三点判断**（非拍板，供后续批参考）：

1. **「清屏兜底」这个立场可以放心维持**：Babylon 用普通几何 + 独立清屏，天然规避本引擎 2026-11-05 踩过的「色 LOAD 赌全屏覆盖 → 覆盖缺口读到上上帧 → 拖影」；两边结论一致，无迁移价值。
2. **`fogEnabled` 类「材质开关没跟随天空盒语义」的坑，Babylon 自己也有**：它的默认助手不关雾；本引擎覆盖制 shader 不吃材质面，反而天然没有这个坑——未来若给天空盒加材质层（程序化天空），需要显式定义雾/光照/后处理是否参与。
3. **环境光分界 Babylon 划得更早也更硬**：`SKYBOX_MODE` 在 define 准备段直接跳过球谐，环境光只能从 `scene.environmentTexture` 来；本引擎「天空盒纯视觉、环境光归四期 GI」是同一分界的更晚实现，四期落地时可沿用「天空盒贴图 ≠ IBL 源」这条边界，避免一张图两种语义打架。

## 10. 出处索引（核心文件）

| 主题 | 文件 |
|------|------|
| 默认天空盒装配 | `packages/dev/core/src/Helpers/sceneHelpers.pure.ts:100-139` |
| 环境助手（地面+天空盒） | `packages/dev/core/src/Helpers/environmentHelper.ts:470-722` |
| 跟随相机世界矩阵 | `packages/dev/core/src/Meshes/transformNode.pure.ts:111-126、338-341、1072-1129` |
| 远平面改写 | `packages/dev/core/src/Meshes/mesh.pure.ts:642、2750-2838、2903-2906`；`Cameras/camera.pure.ts:382、690、973`；`Maths/math.vector.pure.ts:8496-8530` |
| 天空盒采样 | `packages/dev/core/src/Shaders/background.vertex.fx:46-65`；`Shaders/background.fragment.fx:39-52、194-244、337-338`；`Shaders/ShadersInclude/reflectionFunction.fx:98-101、141` |
| SKYBOX_MODE 定义与宏 | `Engines/constants.ts:441`；`Materials/materialHelper.functions.ts:944-993`；`Materials/Textures/baseTexture.pure.ts:144-160、604-606` |
| 三种材质 | `Materials/Background/backgroundMaterial.pure.ts`；`Materials/standardMaterial.pure.ts`；`Materials/PBR/pbrBaseMaterial.pure.ts:2379-2387` |
| cube 纹理与格式 | `Materials/Textures/cubeTexture.pure.ts`；`Materials/Textures/envCubeTexture.pure.ts`；`Materials/Textures/Loaders/` |
| 全景转换 | `Misc/HighDynamicRange/hdr.ts:146-168`；`Misc/HighDynamicRange/panoramaToCubemap.ts`；`Materials/Textures/hdrCubeTexture.pure.ts`；`Materials/Textures/equiRectangularCubeTexture.pure.ts` |
| 穹顶路线 | `Helpers/textureDome.ts`；`Helpers/photoDome.ts` |
| 边界/特例 | `Rendering/depthRenderer.pure.ts:248、278`；`Misc/snapshotRenderingHelper.ts:118-124、317`；`scene.pure.ts:6211、5222-5273、5573`；`XR/features/WebXRBackgroundRemover.pure.ts` |

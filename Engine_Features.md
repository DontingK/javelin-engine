# 引擎功能清单（Engine Features）

> 版本：v0.1（2026-10-14 建档）
> 定位：按模块分级盘点每个功能的**完成状态**与**验收状态**。`doc/Engine_Roadmap.md` 叙阶段沿革（何时做、为何做），本文按模块点名现状（有什么、验没验）——待验收挂账与后置项一眼可扫。
> 维护纪律：功能落地、验收翻状态时同步更新本文（与红线 12「文档先行」同一收口动作）；本表只记结论与日期，拍板细节、坑点归各设计档。
>
> 图例：
> - **完成状态**：✅ 完成 ｜ 🔄 部分（注明缺口）｜ ⬜ 未做/后置（注明归期或拍板）
> - **验收状态**：✅ 已验收（附方式+日期：单测 / 用户实测 / 三端实测）｜ ⏳ 待验收（已落地未实测）｜ —（随消费方链路验收/支撑设施，无独立验收面）

---

## 1. javelin-rhi —— Vulkan 渲染后端

> 设计档：`doc/RHI_Design.md` 及分卷一~四；进度档：`doc/RHI_Implementation_Progress.md`；排障：`doc/Vulkan_Pitfalls.md`

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Instance / Device / Swapchain / FrameLoop 基础设施 | ✅ | ✅ 六阶段各 Demo 用户实测，MultiCameraDemo 终验（2026-09-25 关账） |
| Uniform 通道（UBO1 共享缓冲+动态偏移 / push constants） | ✅ | ✅ 随渲染 Demo |
| RenderGraph / Attachment / 多相机 RenderView | ✅ | ✅ MultiCameraDemo（分屏/透明排序/resize） |
| PSO / PipelineCache / SPIR-V 加载 | ✅ | ✅ 随渲染 Demo |
| 呈现模式（FIFO / MAILBOX=vsync 语义） | ✅ | ✅ 用户实测（2026-10-06/07 帧节奏排查关账） |
| MSAA resolve attachment（能力查询 / 深度不 resolve） | ✅ | ✅ 用户实测（2026-10-06） |
| resize 重建 + 显存手动 dispose 优先（Cleaner 仅兜底） | ✅ | ✅ 用户实测（2026-10-09 OOM 修复验收） |
| Cleaner 统一延迟销毁（Registration 合一：手动 dispose 与 Cleaner 兜底共用两帧队列、废除句柄登记；device.dispose 前 flush；compute 内部 layout 补注册，Vulkan_Pitfalls #26） | ✅（2026-10-30） | ✅ 用户实测（2026-10-30，resize/热刷回归 + F3 存活计数） |
| Buffer staging（deviceLocal 上传） | ⬜ 后置 | — |
| 描述符池环形 | ⬜ 后置 | — |
| backbuffer 句柄入 structureHash | ⬜ 后置 | — |
| R32Uint 拾取附件 + 图像读回 | ✅（2026-10-23，Engine_Picking_Design v0.1：TextureReadbackRing 环 + copyImageToBuffer/Buffer.read/Fence.isSignaled/RHIClearUintColor/Format.bytesPerPixel） | ✅ 用户实测（2026-10-23，随拾取全链） |
| **SSBO + compute 全链**（三期批次①，2026-10-31 落地：compute 管线布局装配补齐（原空壳——setLayouts+pushConstantRange，stage=COMPUTE）/ pushConstants stageFlags 按 bindPoint 分流 / **Buffer 进图同步**——PassDef addBufferRead/Write + 依赖推导 + structureHash + VkBufferMemoryBarrier 屏障计划（通用资源面，非光照特化）/ DescriptorUpdate.storageBuffer VK_WHOLE_SIZE 修正+offset/range 重载 / 删 VulkanPipelineLayout+createPipelineLayout 死代码；SSBO 描述符链/dispatch/compute 进图骨架为 RHI 实装期既有） | ✅（2026-10-31） | ✅ 用户实测（2026-10-31，ComputeSsboTest 全环——双段 compute 链+图形读+读回 65536 元素逐比对通过 + ComputeShaderImporterTest 绿；排障两条入 Vulkan_Pitfalls #27/#28） |

## 2. javelin-core —— 引擎核心

### 2.1 基础设施（serialize / reflective / settings / obj）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 二进制序列化（Capsule / BinaryImporter / write-read 契约、共享引用保真；**大数组 IO = 缓冲流 + byte[] 批量读写**） | ✅ | ✅ 单测 + .scene 往返用户实测（2026-10-04）；大数组 IO 修复用户实测（2026-11-07——天空盒 6.29MB 加载 5448ms→3ms，Pitfalls #33） |
| 反射体系（@Reflective + 注册器，Class.forName 显式化、零注解扫描） | ✅ | — 随序列化 / AOT 链验收 |
| 设置系统（JavelinSetting：vsync / maxFps / backgroundColor / antialiasing 落盘） | ✅ | ✅ 用户实测（2026-10-06/07 vsync/maxFps 关账） |
| ObjectId / ObjectCache 对象身份与引用缓存 | ✅ | — 随序列化 / 桥链 |

### 2.2 场景系统（scene）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Node / Component / Transform / GameScene 组件模型 | ✅ | ✅ GameSceneTest 单测（一期） |
| Layer / Tag / LayerMask 数据模型 | ✅ | ✅ 单测 + Inspector 头部编辑（2026-10-11） |
| 生命周期帧驱动状态机 | ✅ | ✅ 用户实测（2026-10-04） |
| .scene 文本序列化 + 保存/重载（含内嵌材质） | ✅ | ✅ 用户实测（2026-10-04 建树→保存→重启还原） |
| 渲染激活过滤 isActiveInHierarchy（Unity 口径，翻转即时注销） | ✅ | ✅ 随 Gizmo 显隐 / 编辑器日常 |
| Node.userData 运行态挂载（不入序列化） | ✅ | — 随 Gizmo 轴判别 |
| 场景级 raycast（Collider 注册表查询，L1——Physics 静态门面 + RaycastHit + Scene.getColliders 投影） | ✅（2026-10-28） | ✅ 单测全绿（2026-10-30，PhysicsTest 14 用例；Engine_Collision_Design §10） |
| **灯族组件（子类化拍板 2026-10-31）+ lights 注册表 + getSun 主光自动裁决**（三期批次②，2026-10-31 落地：`Light` 抽象基类（color/intensity/castShadow）+ `DirectionalLight`/`PointLight`(+range)/`SpotLight`(+range+锥角)——每型只显自己的字段、**不支持切换类型**（Collider 族同构）+ .scene 通则序列化（type 不落盘=子类身份）+ 双语包键/light 图标；GameScene lights 分流与 read 重建；**getSun=最强激活方向光自动裁决（对齐 Unity 拍板，isMain 标志推翻删除）**，EditorScene 用户场景口径透传；**菜单解锁**——hierarchy.create.light 三命令（directional/point/spot），Editor_Menu_Design v0.19） | ✅（2026-10-31） | ✅ 用户实测（2026-10-31——菜单挂灯/检查器编辑/主光自动裁决/F3 灯数；单测全绿；随批排障:检查器 isX 字段命名撞闸门、@InsHeader 子类显式贴法契约立定） |
| **唯一性作用域 `@UniqueComponent`**（2026-11-08 落地：不写参数=每节点至多一个、`scene=true`=全场景至多一个（跨节点判）；拒绝在落表前——`Node.addComponent` 前置判 / `GameScene.attachSubtree` 整棵子树预检（含批内互比）/ `register` 末道兜底，失败不留半挂态、不静默去重；注解读取走处理器生成的 AOT 安全通道 `ReflectiveFactory.registerAnnotationValue`（core 禁 `getAnnotation`）；首例 `Environment`，Engine_Scene_Design §2.5） | ✅（2026-11-08） | ✅ 用户实机验收（2026-11-08——环境组件创建/节点检查器编辑/天空盒生效；随验 bug 修复：禁用环境组件即摘天空盒，Pitfalls #34）；单测 SceneComponentTest 未跑 |
| environment / sun | ✅ 组件 v0.1（2026-11-08：`Environment` = 挂节点上的普通组件（场景唯一），装天空盒；环境光升级归四期 GI 期）/ sun 解析已落见上行 | ✅ 用户实机验收（2026-11-08，同上） |

### 2.3 相机（render.Camera）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 透视 / 正交投影（FOV 弧度制） | ✅ | ✅ CameraTest 单测 + Demo |
| cullingMask / viewportRect / depth 渲染排序 / per-camera 清屏 | ✅ | ✅ 多相机 Demo + 编辑器日常 |
| ClearFlags（SOLID_COLOR / DEPTH_ONLY / DONT_CLEAR / SKYBOX——默认 SKYBOX 对齐 Unity，文案「清屏模式」对齐 HDRP） | ✅ | ✅ 同上 + 批次⑤实机 |
| Camera.screenToRay（Unity ScreenPointToRay 口径、viewportRect 归一化；**简式尺寸自取=引擎静态实例渲染表面**，显式尺寸重载保留） | ✅（含 2026-10-28 简式） | ✅ CameraTest 单测（2026-10-06，显式重载；简式随运行验收） |
| 引擎壳鼠标射线 mouseRay（Input 鼠标坐标 + screenToRay 尺寸自取 = Unity Camera.main.ScreenPointToRay(Input.mousePosition) 形态；相机→射线→Physics.raycast 一行链） | ✅（2026-10-28） | ✅ 用户实测（2026-10-31，运行时） |
| ClearFlags Skybox 项（三期批次⑤，cubemap+纯视觉，Engine_Skybox_Design v0.5——**三步全部验收关账**：步骤一 RHI cubemap 底座 ✅ 2026-11-02（CUBE_COMPATIBLE 旗标 + textureCube 构造 + TextureRegion.layerCount/cubeFaces 六层整拷 + CubemapTest 读回比对通过；坑 Pitfalls #30）；步骤二 SkyboxPass ✅ 2026-11-02（ClearFlags.SKYBOX 枚举+i18n、SkyboxPass 覆盖制[skybox.vert/.frag 内置源+36顶点VB+z=w+LEqual/ZWrite Off]、管线 3.5 位、TestAssetManager、SkyboxDemo 五验收点；坑 Pitfalls #31）；步骤三资产面 ✅ 2026-11-07 用户实机全链——3a 数据面（CubemapImage 像素资产 + EquirectConverter[RGBE 解码 rgbe.c 原文口径 + 双线性转六面 u 公式 three.js 原文核对；纬度归一化排障修 Pitfalls #32] + CubemapImporter[.hdr/.pano] + 单测）+ 3b 场景接线（GameScene.skybox 场景级 Asset 引用首例 + RS 资产重载[所有权区分] + 桥身份门控推送）+ 3c 场景根行检查器（.scene 键 provider 首例 + skybox 行活实例直写）；**随验六连排障全修**（SKYBOX 默认值/清屏拖影撤 LOAD 赌注/sRGB 量化偏暗/vs 矩阵复合黑区/采样器 cubeLinear CLAMP_TO_EDGE 棱/转换器纬度归一化角部——Skybox 档 §1.3~§1.5）；cubemap 六面缩略面板后置。**v0.5（2026-11-08）：载体迁入 `Environment.skybox`（挂节点上的普通组件、全场景唯一），原 `GameScene.skybox` 字段与 `Scene.getSkybox()` 退役，桥改读 `scene.getEnvironment()`，编辑入口=节点检查器（`.scene` 检查器撤出环境）——旧 .scene 的 skybox 键不再读取**） | ✅ 全部（2026-11-02 步骤一/二 + 2026-11-07 步骤三用户实机：.hdr 拖入导入/场景根行换绑/视口即时换背景/转动无痕）；✅ 2026-11-08 载体迁移用户实机验收（环境节点/检查器编辑/禁用即摘——随验 bug 修复 Pitfalls #34） |
| main 相机语义消费（Scene.getMainCamera——Unity Camera.main 口径：首个激活 isMain，无则 null；mouseRay 首个消费方） | ✅（2026-10-28） | ✅ 单测全绿（2026-10-30，GameSceneTest） |

### 2.4 渲染系统与管线（render）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| RenderingSystem / FrameLoop / UBO1 帧环形收编 | ✅ | ✅ 一期 Demo（清屏出画面起） |
| RenderPass 有序列表 + AbstractForwardPass + 透明分桶 | ✅ | ✅ SceneRenderDemo 用户运行 |
| Mesh 子网格（SubMesh 区间列表 + setSubmeshes/getSubmeshCount + 上传校验 + 旧产物单子网格兼容，资产档 §2.9） | ✅（2026-10-29） | ✅ 单测全绿（2026-10-30，MeshSerializationTest 往返/边界） |
| RHI drawIndexed 三参扩展（firstIndex 范围绘制，参数序对齐 vkCmdDrawIndexed） | ✅（2026-10-29） | ✅ 用户实测（2026-10-30，随多子网格渲染实机） |
| MeshRenderer 多材质（materials 列表 i 位对应子网格 + 每 (renderer, 子网格) 一 RenderObject + 缺失位 DefaultMaterial 兜底（**语义变化：原 material null 不渲染作废**） + getMaterial/setMaterial 兼容门面 + 旧 .scene material 单键兼容，场景档 §5.1 v0.5） | ✅（2026-10-29） | ✅ 单测+用户实测（2026-10-30，SceneSerializationTest 往返/兼容 + 多材质渲染实机——**随验收修复文本列表 guid 硬编码 Asset.class 崩 + default.scene 手迁 5 处文本头**） |
| Material.surfaceType 面型裁决（分桶 + PSO 覆盖 Blend/ZWrite，方案 B，材质档 §4.1） | ✅ | ✅ 用户实测（2026-10-23） |
| RenderList Pass 名分桶严格制（passes map + RenderPass.shaderPassName + pipelineFor passName 维；GizmoPass 去身份过滤/去双画，材质档 §4.2） | ✅ | ✅ 用户实测（2026-10-23） |
| SceneRenderBridge pull 翻译（场景→RenderObject） | ✅ | ✅ 同上 |
| GizmoPass 末尾置顶（颜色 LOAD + 深度 CLEAR 两层深度语义） | ✅ | ✅ 随 Gizmo 链（2026-10-14 拖拽验收） |
| MSAA 视口附件（isMsaaPath 边界：stacking/预览降 1x、overlay 恒 1x；**HYBRID 档启动期强制 1x**——MSAA-GBuffer 归四期） | ✅ | ✅ 用户实测（2026-10-06） |
| **灯收集与 set1 光照数据通路**（三期批次②，2026-10-31 落地：SceneRenderBridge Light→RenderLight pull 翻译（身份对应/每帧位姿参数同步）+ LightCollector 纯逻辑收集（方向光两遍收主光槽 0/点聚光预排序 intensity÷distSq/视空间球剔除自写——FrustumIntersection 对含 Y 翻转反射矩阵不可信）+ RenderingSystem 帧收集打包（binding0 LightingParamsBlock UBO **DYNAMIC 每视口动态偏移** 320B 块/512B 步长 + binding1 lightList SSBO 引擎常驻 256KB 首视口口径 + 超容 WARN 状态位）+ Opaque/Transparent set1 绑定带动态偏移 + ShaderInjector **fs 声明注入**（lighting/lightList，保留字生效）+ F3 行 8 在册灯数；**着色消费归批次③**（本批无画面验收项）；多视口 lightList 各自列表挂账批次③，字段详表 Uniform_Keys §4-1） | ✅（2026-10-31） | ✅ 用户实测（2026-10-31，同批次②合挂销账） |
| **Cluster 构建 + Forward 着色消费（= Forward+ 成形，三期批次③，2026-11-01 落地）**：**CPU 构建拍板**（原 GPU 三步形态两硬伤作废——单命令缓冲帧图无中帧 CPU 回读门 + GPU 原子取槽序无法兑现「64 截尾=去最弱」；两路线帧占损同构，GPU 化归四期，灯光档 §6 v0.7）——`ClusterBuilder` 纯逻辑（两遍构建+前缀和单线程、透视指数/正交线性切分、球 AABB 8 角保守投影带符号保号、池溢出截断+WARN+计数收缩）；RenderingSystem **per-view 化**（lightList/clusterGrid/clusterIndices 三缓冲+set1 描述符集按视口惰性创建/视口尺寸变化重建/两帧退役——**首视口挂账解除**；gridDim/zParams/viewport 填真值；registerFrameStorage 通用通道清退）；**CameraBlock 扩 view**（@64B，旧产物超集合法免重导）；ShaderInjector 注入 **javelinLighting 光照段函数**（声明+函数一体：Lambert+环境项+方向光全局段+cluster 点/聚光衰减（**距离窗 = (1-(d/range)²)² Unity URP 口径**，2026-11-01 实机拍板换装）+SPOT 锥——一批落全；数据面实例名保留字、函数名与 cam/pc 同类可自由使用；**ambient 纯黑拍板**（Unity 口径：无环境源即纯黑，原 0.15 灰占位作废——真值源归四期天空盒环境光））；**内置 Lit.shader**（+伴生 meta，首个消费 NORMAL/UV 顶点属性）+ **新建材质默认 shader = Lit 拍板**（default.shader 维持兜底专用）+ F3 行 9 cluster 读数 + `Material.fillMissingDefaults`（坏 stub 落盘/旧 .mat 属性缺补默认——盘上加载路径回填缺口随排障补）；单测 ClusterBuilderTest（覆盖手算投影/Y 翻转保号专项/截尾强度序/池前缀链/正交线性/近平面全范围/复用扩容）+ ShaderImporterTest Lit 端到端；**随验收四坑排障**（GLSL 块名限定/坏 stub 落盘 NPE/桥层 SpotLight range 漏分支/cos 读反——Pitfalls #29） | ✅（2026-11-01） | ✅ 用户实测全过（2026-11-01：三型灯明暗正确+光圈软化认可+**Scene-Game 双视口各自光照正确**——首视口挂账销账+**单测全绿**（ClusterBuilderTest/ShaderImporterTest）+**40 盏 HSL 彩色点光压力场景帧率稳、F3 行 9 读数正常**——压力场景随验生成进 test 项目 default.scene） |
| **主光阴影 ShadowPass 单张硬影（三期批次④-A，2026-11-01 落地）**：RHI 三补（`SamplerDesc` 深度比较重载（compareOp，VulkanSampler 填 compareEnable/compareOp）+ `GraphicsPipelineDesc` depthBias 三参重载（静态 PSO 级，动态态后置）与**空颜色格式 depth-only 新约**（VulkanGraphicsPipeline rendering 空颜色路径天然支持））+ `ShadowMath` 纯逻辑视锥构造（JOML lookAt 同式光空间基 + 行和投影半宽 + `ortho zZeroToOne=true` + up 稳定轴防退化；Y 不翻——shadowMap 自写自消费自洽）+ **着色器走资产导入链（2026-11-01 用户拍板，tool 内嵌 spv 路线同日推翻）**：`SpvShader` 资产类 + `SpvShaderImporter`（.vert/.frag 扩展名即 stage，shaderc 编译，ClassUtil 自动注册）+ 内置源 `builtin-asset-core/shadow/shadow.vert/.frag`（伴生 meta + builtin-asset.index 收新后缀 + FileType/glsl 图标/双语包键）+ `ShadowPass`（覆盖制不消费材质 Pass 声明——用户 shader 零阴影负担；depth-only 无色附件 PSO + 无描述符集 + **128B 专属 push 布局**（lightViewProj@0+model@64）；PSO 静态 bias 2.0/1.5（实测再调）；**per-view 幂等挂载**（帧号门控，恒挂载只清不画保写者存在+图结构稳定）；AssetManager 按路径装载内置产物（EditGridAppState 先例），缺失/坏 stub 显性报错不兜底）+ RenderingSystem（`computeShadow` 收集域（主光裁决同 LightCollector 口径/caster 世界 AABB 并集超集保守）+ shadowMap 2048² D32 DEPTH+SAMPLED + compare LESS 采样器常驻（**NEAREST 口径**，2026-11-01 实机验收拍板换装——原 LINEAR 免费软化在斜投影边缘呈块状锯齿被否，PCF 归四期）+ packLightingParams 扩 sunViewProj @336（块 400B/步长 512B 不变）+ **binding4 并入 set1 布局**（同 set 号单布局 Vulkan 约束；per-view set 各自指同图）+ **阴影图分辨率可调**（Light 基类 shadowResolution 上移三型灯齐显/预设档 256~4096 下拉无 Custom/按需重建旧图两帧退役）+ Opaque/Transparent `addTextureRead(shadowMap)` 依赖边 + ShaderInjector（**javelinLighting 签名扩第三参 worldPos**（vs 从 pc.model 直出，fs 零重建；签名变更=契约变更）+ binding4 `sampler2DShadow` 声明 + 保留字 shadowMap；主光槽 0 castShadow 门控 → NDC xy×0.5+0.5 采样 compare）+ Lit.shader 双镜像（builtin+test 资源）vs worldPos 插值/fs 三参调用；单测 ShadowMathTest（8 角覆盖/up 换轴/正交 w=1）+ ShaderImporterTest.litShaderEndToEnd 扩三参；**注入器改动须强制重导**（Pitfalls #29 口径）；Deferred 复用拆 ④-B 后批（2026-11-01 拍板）；随批两散件（**project.create.scene 创建场景命令**——空 GameScene 落盘 guid 与 meta 同源/脏确认三键复用/创建即切换 Unity 口径（SceneFileType.openScene 抽公开入口）+ **启动场景记忆写环**——swapScene 回写 defaultOpenScene 重启恢复上次场景） | ✅（2026-11-01） | ✅ 用户实测全过（2026-11-01：主光投影正确/调 castShadow 即时增减投影物/透明物不投影不接影/Scene-Game 双视口不掉影/阴影图分辨率切档即时重建/重启恢复上次场景；**LINEAR→NEAREST 随验拍板**（边缘块状锯齿）、PCF/CSM/bias 精调归四期） |
| overlay 挂点（DebugText / RenderStats，buildFrameGraph） | ✅ | ✅ 用户实测（2026-09-27） |
| **HYBRID 档（Deferred 复用，三期批次④-B，2026-11-08 落地）**：`GBufferPass`（消费 `Pass GBuffer` 同名桶剔透明——三张 MRT = albedo RGBA8 / 世界法线 RGBA16F / 世界位置 RGBA32F+w 有效标志；复用本视口 depth；`registerPassColorFormats` 按 shader Pass 名给 PSO 三格式）+ `LightingPass`（覆盖制全屏三角形；frag 带 `//@javelinLighting` 标记 → 导入期注入相机块+光照段函数+G-Buffer 三采样——**与材质 Forward fs 同一 `javelinLighting` 常量**，同一 set1 cluster/shadow；背景按 worldPos.w<0.5 discard；无深度附件；**清屏职责自 OpaqueForward 移交**）+ set1 扩 binding5/6/7（G-Buffer 三采样 per-view，NEAREST/CLAMP 采样器；resize 换绑并重建 set1）+ `PipelineProfile` 双 Pass 列表（`setProfile` 运行期切；`addPass/removePass` 两档同步——Gizmo/拾取/调试线切档不丢）+ `JavelinSetting.renderPipeline` 持久化 + 引擎启动消费 + **HYBRID 强制 1x**（MSAA-GBuffer 归四期）+ 编辑器 `tools.renderPipeline` 命令（运行期切换 + 写盘 Game/javelinSettings.settings）+ 内置 Lit.shader/default.shader 各补 `Pass GBuffer`（default 保 HYBRID 下紫色错误显性化）+ ShaderImporterTest 补 GBuffer pass 断言；**不引入 lightingRT/HDR/Composite**（四期第 1 项） | ✅（2026-11-08） | ✅ 用户实测（2026-11-10：HYBRID 档光照正常、与 FORWARD 两路一致；随验坑 Pitfalls #35——set0 相机块 stageFlags VERTEX-only 致 LightingPass fs 读 cam.view 读零全黑，修=ALL_GRAPHICS 超集） |
| 视锥剔除 | ⬜ 后置优化（Aabb/Mesh.bounds 已备） | — |

### 2.5 资产（assets）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Mesh / VertexAttribute / VertexLayout | ✅ | ✅ 一期 |
| Shader / Material 数据模型 + 渲染侧桥（MaterialBlock std140 / materialSet / pipelineFor） | ✅ | ✅ MaterialTest 单测 + Demo（2026-09-25） |
| Material.surfaceType 字段 + Capsule 序列化（旧场景回退 OPAQUE） | ✅ | ✅ 用户实测（2026-10-23） |
| Material 序列化（.scene 内嵌往返闭环） | ✅ | ✅ 用户实测（2026-10-04） |
| Mesh.bounds / Aabb（set 即算 / 惰性兜底 / 上传前钉死） | ✅ | ✅ 单测（2026-10-06） |
| AssetManager 资产索引查询（listAssetPaths、调用先重载 index.asset） | ✅ | ✅ 随打包启动场景下拉（2026-10-11） |
| .mat 资产格式 + AssetManager 读写（MaterialImporter + project.create.material + .mat 文件类型，材质档 §3.2） | ✅ | ✅ 用户实测（2026-10-23） |
| 贴图像素资产 Image + Texture2D 引用层改造（三层模型：Image 磁盘一份/Texture2D 持设置——一图多设置免复制，贴图档 §1） | ✅（2026-10-25） | ✅ 用户实测（2026-10-25） |
| .texture 纹理资产（Texture2D 文本序列化 + TextureImporter + 检查器面板 + null-image 渲染占位守卫，贴图档 §7） | ✅（2026-10-25） | ✅ 用户实测（2026-10-25） |
| Texture2D 运行期可变 + revision（上传冻结废除——setImage 换图作废旧 GPU 句柄重传/采样设置直接生效；revision 驱动描述符集重建，贴图档 §8 v0.3） | ✅（2026-10-30） | ✅ 单测+用户实测（2026-10-30，Texture2DTest 改断言 + 运行期换图/采样实机） |
| Material.setShader Unity 保参口径 + textureRevision（同名同类型保留值/新属性取默认/旧属性丢弃；贴图槽版本比对自动重建，材质档 §3.3 v0.5） | ✅（2026-10-30） | ✅ 单测+用户实测（2026-10-30，MaterialTest 重写保参三分支用例 + 换 shader 实机） |
| .smesh 子网格段（[SUBMESH_N] 连续递增 + 与 [INDEX] 互斥显性化，资产档 §2.9） | ✅（2026-10-29） | ✅ 用户实测（2026-10-30，多子网格 .smesh 导入实机） |
| 动态 Mesh / 动态纹理 | ⬜ 后置（材质档 §6 / 资产档 §2.8） | — |

### 2.6 输入（input）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 三层架构（Window 回调→InputSubsystem 双缓冲边沿→Input 静态门面） | ✅ | ✅ InputCameraDemo + RawInputDemo 用户实测（2026-09-27） |
| bind/查询合流 Input + 失焦清态防卡键 | ✅ | ✅ 同上 |
| 性能基线（IMMEDIATE 空载 ~10180 fps） | ✅ | ✅ 存档（2026-09-27） |
| 独占全屏 | ⬜ 未做 | — |

### 2.7 碰撞（physics / math）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| L0 纯数学层（Ray / 球 / 盒 / 圆柱相交例程，解析解对照） | ✅ | ✅ 单测全绿（2026-10-06） |
| Collider 组件族 + fitFromMesh（Box 精确 / Sphere 保守 / Cylinder 半径取 max） | ✅ | ✅ 单测（2026-10-06） |
| 世界 Aabb（行和公式=精确值，8 角点仅对照基准） | ✅ | ✅ 单测（2026-10-06） |
| L1 场景查询 API 面（Physics 静态门面：raycast 最近命中 + raycastAll 全命中；maxDistance/layerMask 参数——Unity 口径；Engine_Collision_Design §10） | ✅（2026-10-28） | ✅ 单测全绿（2026-10-30，PhysicsTest 单测） |
| L2 物理（刚体 / MeshCollider / 物理库选型） | ⬜ 待拍板（窄相 vs 物理库） | — |

### 2.8 子系统 / 调试 / 窗口（subsystem / appstate / debug / window）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Subsystem / AppState 双层（底层冻结可暂停 / 控制层挂卸单心跳） | ✅ | ✅ 一期接线 Demo |
| 窗口抽象 + GLFW 实现（Window 与 RenderSurface 分离、showWindow） | ✅ | ✅ 一期 |
| 引擎静态实例 + 渲染表面访问口（JavelinEngine.getInstance()（构造赋值/shutdown 摘除）+ renderSurface()——相机 screenToRay 尺寸自取源，JavelinEditor.getInstance 同款先例） | ✅（2026-10-28） | ✅ 用户实测（2026-10-31，随 mouseRay 运行时） |
| DebugText 点阵调试文本（含汉字、行级 printf / attr 调色 / clear；**内置资源 2026-11-07 迁 `builtin-asset-core/debug`**——字库源形态改新资产类型 `.chars` 字符集文本 + `BitmapFontImporter` 烘焙 `BitmapFont`，vs/fs 走 SpvShader 导入链，子系统经 `AssetManager` 装载） | ✅（2026-09-27；资源形态 2026-11-07 重构） | ✅ 用户实测（2026-09-27；AOT 资源路径 2026-10-13 复验；**资源形态重构 2026-11-07 复验销账**） |
| RenderStats / GPUInfo（11 类存活计数 + draw call 快照双缓冲）+ F3 overlay | ✅ | ✅ 用户复跑（2026-09-27） |
| vsync 语义（MAILBOX 防撕裂不限速）+ maxFps 限帧器（0=不限） | ✅ | ✅ 用户实测（2026-10-06/07） |

### 2.9 Inspector 注解面（inspector）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| @InsField 显式 opt-in + 读 getter 写 setter 铁律 + 浮点 4 位小数口径 | ✅ | ✅ Inspector v0.1 用户实测（2026-10-11） |

## 3. javelin-editor —— 编辑器

> 设计档：`doc/Editor_Design.md` / `doc/Editor_Menu_Design.md` / `doc/Inspector_Design.md`
> （engine-editor-web 前端工程已随 2026-10-18 去 Web 化清退整体删除）

### 3.1 窗口与桥接

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Swing + JCEF 窗口体系（MainFrame / Canvas 视口、浮层遮挡约束、菜单一律 Swing） | ✅ | ✅ 编辑器日常使用 |
| 纯 Swing 主窗口 + 自研 Dock（FlatLaf 亮色 / 二分布局树 / PanelRegistry / ViewportTabs 单 Canvas / JMenuBar 单源挂载） | ✅ | ✅ 用户实测（2026-10-17，去 Web 化 M1） |
| 项目管理器 ProjectFrame（Swing 版：最近列表 + 新建/打开三模式 + tinyfd 选目录，替换 CEF 页） | ✅ | ✅ 用户实测（2026-10-23；启动链不再依赖 Vite dev server） |
| 项目加载窗 ProjectLoadingFrame（Swing 版：不确定进度条 + 资产导入/引擎启动引导链原样） | ✅ | ✅ 用户实测（2026-10-23） |
| ~~Web 前端基座（Vue 面板体系 / 组件 / 色板单点 style.css :root）~~ | 🪦 已删除 | 2026-10-18 engine-editor-web 整体移除（三窗口+桥清退后无消费方；图标资产随 SvgIcon 体系迁 editor resources） |
| JS↔Java 桥（方法桥协议 + 事件总线双向 + EDT hop / 引用计数退订） | 🪦 已清退 | 2026-10-18 jcef 包整包删除（桥方法/par/vo/EventBridgeForwarder 全量），jcefmaven、jackson 依赖移除；事件体系保留——BridgeEvent/JsEvent/PongEvent 删、五场景事件转纯 EditorEventBus 事件；Inspector 三 VO 迁 inspector 包（M4 数据模型） |
| ~~通用 CEF 模态对话框 Dialog~~ | 🪦 已退役 | 2026-10-17 随去 Web 化删除（唯一消费方打包对话框迁 Swing；原待验收场景作废） |
| 打包对话框 PackDialog（Swing 版：构建形态/启动场景/构建模式/窗口元信息表单 + 实时日志流，构建链 Java 直调） | ✅ | ✅ 用户实测（2026-10-23） |
| 耗时操作指示器 TimeConsumingDialog（`swing/dialog`：静态 `start()/end()/wrap()` 标记后台耗时操作，超 1 s 弹无边框非模态「处理中」指示器——线程名 + 已用时、标记清零自动关；EDT 标记跳过、监视线程按需存活、嵌套按最外层计时；双语键 `time.consuming.*` 随批，Editor_Swing_Design §4.3 v0.2） | ✅（2026-11-08） | ✅ 用户确认验收（2026-11-08；调用点接入为后续动作） |

### 3.2 菜单与命令（menu / selection）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 菜单/命令体系（MenuCommand 三域 + MenuRegistry 任意嵌套 + Swing 渲染 + SvgIcon 图标 + EditorSelection 单源） | ✅ | ✅ 首批实测（2026-10-04）+ v0.7 批（2026-10-11） |
| file.save / Ctrl+S | ✅ | ✅ 用户实测（2026-10-04） |
| 层级右键域（empty / delete / plane / Unity 结构重排占位） | ✅ | ✅ 随场景树批次（2026-10-04） |
| hierarchy.create.camera（v0.8） | ✅ | ✅ 用户实测（2026-10-23） |
| hierarchy.create.environment 环境设置命令（v0.20，建 `Environment` 节点 + `Environment` 组件（场景唯一），固定挂场景根级、创建后选中；**菜单项恒可用**——已存在/场景未就绪点击弹警告说明原因，不置灰） | ✅（2026-11-08） | ✅ 用户实机验收（2026-11-08） |
| hierarchy.create.cube（v0.14，内置 box.smesh + CreateBoxMenuCommand） | ✅ | ✅ 用户实测（2026-10-24） |
| hierarchy.create.sphere/.capsule/.cylinder 原始体三件套（v0.15，内置三份 .smesh；生成脚本一次性工具不入库） | ✅ | ✅ 用户实测（2026-10-25） |
| project.create.shader 着色器创建命令（v0.16，NewShader.shader 极简前向模板；用户文件不写 guid 头——guid 真值归伴生 .meta 随机分配，配套 MetaManager 缺头 .shader/.smesh 注入收窄为随机 guid） | ✅ 用户实测（2026-10-25） |
| project.create.texture 创建纹理命令（v0.17，texture.texture=空 image+默认采样设置的 Texture2D 文本序列化；创建族第三项、guid 与 meta 同源四处一致） | ✅（2026-10-25） | ✅ 用户实测（2026-10-25） |
| file.open 实做（文件类型注册器分发打开 + 脏场景三键确认） | ✅（2026-10-18） | ✅ 用户实测（2026-10-23） |
| file.preferences 首选项命令（v0.18，文件域第二项——PreferencesDialog 语言设置首批，确定写回 EditorSettings.language 存盘 + 重启生效） | ✅（2026-10-29） | ✅ 用户实测（2026-10-31，随 i18n 全量化批次验收） |
| 语言设置全链（**语言包注册制**：I18n.registerLanguage 支持语言注册表 + registerPack「域+语言+路径」显式注册（PluginContext 收口，`lang/{lang}.properties` 固定路径规范废除——文件布局自定、一文件一域、集中式语言包可翻译其他插件域；editor.core 注册 en_US/zh_CN）+ EditorSettings.language 字段 + 启动链 main setLanguage——未设置按系统语言 tag 解析、**缺键逐键回退该域基线（该域第一个注册的语言包，英文不特殊）**） | ✅（2026-10-29） | ✅ 用户实测（2026-10-31，随 i18n 全量化批次验收） |
| build.compile | 🔄 引擎侧 GradleProject.compile 已备、菜单先禁置灰 | — |
| GPU ID-buffer 拾取（**2026-11-08 起只服务场景物体**——Gizmo 柄拾取改 CPU 碰撞体射线；pass 落 core 运行时也可用；与射线检测共存；**着色器 2026-11-07 改走资产导入链**——`pick.vert/.frag` 源码落 `builtin-asset-core/pick`，`PickPass` 经 `AssetManager` 装载，原 tool 内嵌 spv 路线清退） | ✅（2026-10-23 拍板并实现，Engine_Picking_Design v0.1→v0.2 范围收窄；shader 路线改向 2026-11-07） | ✅ 用户实测（2026-10-23 场景物体路径，清单见该档 §9；**shader 换装 2026-11-07 复验销账**；**v0.2 范围收窄 2026-11-08 复验通过**） |

### 3.3 层级树与场景管理（scene / event）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 层级树（/scene/tree 帧通道 + scene.changed 增删刷新 + 选中单源 + accent 高亮 + dirty 星标镜像） | 🪦 web 实装已随 2026-10-18 去 Web 化清退 | 历史验收（2026-10-04 / 2026-10-11）；Swing 版见下行 |
| 层级树 Swing 实装（HierarchyPanel：SceneTreeSnapshot submitTask 帧快照→EDT 换树 + scene.changed/dirty/nodeRenamed/select 四事件分工 + 展开态按引用恢复 + 右键 ctx.hierarchy 先落选中） | ✅ | ✅ 用户实测（2026-10-23） |
| 选中/事件链全链引用化（EditorSelection 存 volatile Node + 三事件引用载荷 + Create 命令引用快照 + NodeSnap 引用回查键；ObjectCache 退役、快照拷贝本体保留） | ✅ | ✅ 用户实测（2026-10-23） |
| 场景根行选中=场景文件（根行点击 setSelectedPath 进 project 槽→检查器 .scene 文件链分发零改动；syncSelection 空事件不清根行修回声自灭 + project.select 跨域对齐：选本场景文件高亮根行/选他物熄灭；未存盘场景无文件退清选中） | ✅（2026-10-22） | ✅ 用户实测（2026-10-23） |
| 脏星标行宽修复（scene.dirty 置星改根行 model.nodeChanged 重算节点尺寸——repaint 不重算、旧行宽裁出省略号） | ✅（2026-10-22） | ✅ 用户实测（2026-10-23） |
| Scene/Game 视图切换（sceneModel 双模式 volatile 直写 + Game 页 resize 画布跟随） | ✅ | ✅ 用户实测（2026-10-23） |
| Transform 变更检测（TransformWatchAppState 帧级快照 diff → scene.nodeTransformChanged + 置脏） | ✅ | ✅ 用户实测（2026-10-23，Gizmo 拖拽联动） |
| GAME 模式输入隔离（飞行 / Gizmo 输入仍活） | ⬜ 留白 | — |

### 3.4 Inspector 面板（inspector / Swing）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| v0.1 节点头五件套（name/active/layer/tag）+ Transform 字段行 + Tag/Layer 下拉编辑 | ✅ | ✅ 用户实测（2026-10-11） |
| v0.2 Camera 组件接入 + ENUM/COLOR/RECT/LAYER_MASK 类型通道 + 星标即时化（scene.dirty） | ✅ | ✅ 用户实测（2026-10-23） |
| v0.3 GUID_ASSET 资产引用三态只读 + MeshRenderer 组件接入 | ✅ | ✅ 随 M4 Swing 版面验收销账（2026-10-18） |
| v0.7 .shader 资产只读诊断台（注册器第三键；guid 双源解析 + Library 产物 + 属性/Pass 三段表 + 导入缺失诊断态） | ✅ 用户实测（2026-10-25） |
| v0.8 AssetInput 资产通用输入 + 材质检查器 Shader 行换装（Class 类型参数 + 选择窗 + 右区拖放 + guid 三态 wire + setShader 重置默认值 + .mat 即时写回重导；core 补 AssetManager.guidOfPath 反查口） | ✅ 用户实测（2026-10-25） |
| v0.9 材质参数编辑（类型感知控件 COLOR/VECTOR/FLOAT·RANGE/面型 + 防抖写盘重导 + 材质信息分组固定不可折叠） | ✅ 用户实测（2026-10-25） |
| v0.10 GUID_ASSET 通道升可编辑（MeshRenderer mesh/material 行换装 AssetInput：选择窗/右区拖放/None 清空；VO 新增 assetType 声明类型；回写 guid 经 loadGuid 解实例、未索引拒绝、空串=内存创建拒绝；只读前身 GuidAssetField 退役） | ✅ 用户实测（2026-10-25） |
| v0.11 材质编辑即时联动渲染（编辑载体=loadGuid 缓存共享实例优先——命中且 shader 与盘面一致直接编辑场景同一实例、数值/面型即时联动；shader 分歧守卫防旧实例写盘导回旧 shader；Shader 换绑不走共享实例走盘面重解析（MaterialResources 按 shader 首绑烤死）；追修 GUID_ASSET 回显漏传/显示名去后缀、选择窗 editor 组内置资产过滤） | ✅（2026-10-25） | ✅ 用户实测（2026-10-25） |
| v0.4 M4 Swing 实装（InspectorPanel——Web 期 v0.1~v0.3 通道面 Java 直调重现：swing.inspector 面板层 + widget 控件七件 + VO 引用化 + markDirty 收口 + NodeTransformChangeEvent 定点刷新 + 编辑保护） | ✅（2026-10-18） | ✅ 用户实测（2026-10-18）——含验收期修正：归属校验/Scrollable 容器/节点头 Web 布局回归/折叠头分层 |
| v0.5 检查器注册器（InspectorRegistry——Node+资产统一接管 + 材质只读首期） | ✅（2026-10-21） | ✅ 用户实测（2026-10-21） |
| v0.6 输入交互增强（数字键入即提交不动显示文本保光标 + 文本提交时机归使用方 onInputChanged + label 拖动调值/数字框滚轮调值步长三级） | ✅（2026-10-22） | ✅ 用户实测（2026-10-22） |
| v0.12 .texture 纹理面板（图=AssetInput 换绑即时写回重导/None 合法 + 采样 filter/wrap 下拉防抖写盘 + 编辑载体=文本私有实例拍板；AssetInput/AssetPickerDialog 升多后缀（Image=[.png/.jpg/.jpeg]））+ 材质贴图行换装内联（行单源=shader 声明） | ✅（2026-10-25） | ✅ 用户实测（2026-10-25） |
| v0.13 GUID_ASSET_LIST 资产列表通道（MeshRenderer.materials 多材质行：行数跟随子网格数、fieldName 编码序号 materials[i] + applyAssetList 整列表 setter 回写 + 列表 setter 查找放宽 + 资产行提交后补拉重建行数；行控件/回显/回写全复用 GUID_ASSET 面） | ✅（2026-10-29） | ✅ 用户实测（2026-10-30，随多材质渲染实机） |
| v0.15 旋转行跳动根治（用户报「调整旋转时跳动、另两个值也变动」；根因=原 XYZ 欧拉规范域 Y∈(-90°,90°) 越界后帧级回显的等价翻转三元组覆盖用户显示值、与提交互为驱动逐帧振荡）：欧拉口径切 Unity 同款 Z-X-Y（RotationMath 换算单点）+ 面板级自旋回声抑制（近期写入环 + 事件原始四元数同旋转判定；随用户实机复验追修判据归一化——首版点积直比 1 误判否致抑制失灵「x 越 -90」）；回归断言 RotationMathTest | ✅（2026-11-07） | ✅ 用户实测（2026-11-07 复验通过——俯仰越 ±90° 拖动/键入不再跳动、另两值不再变；随验两轮追修：口径切换+回声抑制、判定器归一化） |
| v0.16 .scene 检查器收口（2026-11-08：环境设置改挂节点后，场景根行不再显示环境——原 skybox 行与随后的环境组件节均撤出，只留场景分组文件行 + 非当前场景只读态；编辑入口=节点检查器） | ✅（2026-11-08） | ✅ 用户实机验收（2026-11-08） |
| v0.17 节点检查器「添加组件」（面板底部常驻满宽按钮，不随内容滚动 + 选择窗：搜索过滤/候选列表/唯一冲突灰显不可选；候选面=ReflectiveFactory 注册类枚举「Component 子类 + 非抽象 + 贴 @InsHeader」，core 补 registeredClasses 口；添加链=帧边界 addComponent + 置脏 + 面板自拉 + 新分组展开并滚入可见；Inspector_Design §13） | ✅（2026-11-08） | ✅ 用户实测（2026-11-08） |
| v0.18 组件头右键菜单（右键组件分组头弹菜单，当前项=删除组件；required 组件（Transform）置灰、折叠切换改只认左键；core 补 `Node.removeComponent`+`GameScene.onComponentDetached`——即时 onDisable→onDestroy+退注册表（唯一性槽位释放），口径同节点摘出场景（Engine_Scene_Design §2.2/§2.5 v0.7）；移除链=帧边界删 + 置脏 + 面板自拉；测试 SceneComponentTest 两组断言；Inspector_Design §14） | ✅（2026-11-08） | ✅ 用户实测（2026-11-08） |

### 3.5 视图工具（appstate.gizmo / 相机控制 / 网格）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 编辑器相机控制（RMB 环视+WASDQE 飞行 Shift×3 / MMB 平移 / 滚轮推拉 / Alt+LMB 环绕 / Alt+RMB 推拉） | ✅ | ✅ 响应性验收（2026-09-27；2026-10-04 水平反向修正） |
| 网格辅助线（EditGridAppState + _CamPos 材质下发） | ✅ | ✅ 实跑通过（2026-09-26）+ 编辑器日常 |
| 变换 Gizmo 工具族（三轴视觉 / **柄拾取 CPU 碰撞体（可见几何与拾取体解耦：轴加粗圆柱 / 环解析带 / 中心胖方块）** / 拖拽三段式：移动=平面相交法、旋转=环平面增量夹角、缩放=沿轴比例；hover 高亮 + 几何语义变灰；**几何程序化生成 `GizmoMeshes`（Babylon 规格，原 axisY.smesh 退役）**） | ✅（移动 2026-10-04；旋转/缩放 2026-11-08） | ✅ 用户实测（2026-10-14 移动拖拽；**2026-11-08 旋转/缩放 + 拾取改道 + 三工具同屏修正全项通过**） |
| 场景视图工具栏（Tools 组三工具全可用 + 局部/世界坐标切换（缩放恒局部）；游戏视图同高占位空条） | ✅（旋转/缩放解禁 2026-11-08） | ✅ 用户实测（2026-10-14 移动/坐标空间；**2026-11-08 三钮全可用复验通过**） |
| 视口聚焦（层级双击节点行 / 视口 F 键 → 编辑器相机平滑滑到框住选中物；有网格走世界包围盒、无网格对准位置；姿态不变、手动输入即打断） | ✅（2026-10-24，Editor_Design §10） | ✅ 用户实测（2026-10-24） |
| DebugDrawLine 调试线（编辑器专属世界空间线框：选中 mesh bounds 世界 AABB / 相机视锥 / BoxCollider 贴合盒——组件绘制器查表分派 + 覆盖制 DebugLinePass 插 Transparent 后深度咬合 + 每帧 clear 重提交跟随选中；**着色器 2026-11-07 改走资产导入链**——`debugline.vert/.frag` 源码落 `builtin-asset-editor/debugline`，Pass 经 `AssetManager` 装载） | ✅（2026-10-23，Editor_DebugLine_Design v0.1；shader 路线改向 2026-11-07） | ✅ 用户实测（2026-10-23，清单见该档 §7；**shader 换装 2026-11-07 复验销账**） |

### 3.6 资产导入管线（importer / meta / asset）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| .shader 导入（解析 / 注入 / SPIR-V 编译 / Capsule 产物 + 自包含文件头 GUID） | ✅ | ✅ 用户亲测（2026-09-26） |
| .smesh 网格导入（拓扑 / 法线重算） | ✅ | ✅ 同上 |
| .meta / GUID / Library 资产管线骨架 | ✅ | ✅ 随导入链（2026-09-26） |
| SceneImporter（.scene→Library 二进制 + 保存即异步重导） | ✅ | ✅ 用户实测（2026-10-23） |
| 内置资源释放→索引收口（releaseAll 末 updateIndex——时序坑修复） | ✅ | ✅ 修复实测（2026-10-17） |
| meta 统一与重导检出（.shader/.smesh 落伴生 .meta、guid=文件头镜像；releaser 内容字节比对；needImport 统一 mtime 判定——gizmo 被盒遮挡旧产物坑根治链） | ✅（2026-10-25） | ✅ 用户实测（2026-10-25） |
| **guid 真值单源=伴生 .meta**（2026-10-29 拍板，推翻「文件头真值+meta 镜像」双源：guid 头只剩 src 下 .java 专属；9 内置资源剥头+伴生 .meta 随包释放（guid 沿用原头值零迁移）；BuiltinAssetReleaser 连 meta 一起释放；pipeline libraryFileFor 非脚本按 meta 解 guid——**焦点扫描无限导入循环根治**，Editor_Design §6.2 / AssetReload 档 §5.1 坑点） | ✅（2026-10-29） | ✅ 用户实测（2026-10-29，死循环根治——原场景改 .mat 颜色+切窗复测通过） |
| 文件类型注册器（FileTypeRegistry：类型→图标 + 双击/命令打开分发） | ✅（2026-10-18） | ✅ 用户实测（2026-10-23） |
| 贴图导入 ImageImporter（.png/.jpg/.jpeg → Image 像素资产；ImageIO 同入口；alpha 通道保留修复；image 文件类型/图标，贴图档 §2） | ✅（2026-10-25） | ✅ 用户实测（2026-10-25） |
| 资产热重载基建（**编辑器对象注册表 Walker**——ObjectId 构造回调 registrar + EditorObjectRegistry 弱引用全量扫描 + setter 优先换指针 + 导入事件总线化（AssetImportPipeline 发 AssetImportEvent，AssetReload 档 v0.5）） | ✅（2026-10-30） | ✅ 用户实测（2026-10-30，改 .shader → reimport → 画面当帧变 + F3 计数不涨） |
| **导入期壳管理器 ShellAssetManager**（首启依赖产物竞态修复——文本协议导入期 `@guid` 引用不加载 Library 产物：MetaManager 登记判引用存在 + ReflectiveFactory 造壳 setGuid；AssetImportPipeline 构造去 AssetManager 仅用壳；Editor_Design §6.3） | ✅（2026-11-08） | ✅ 用户实测（2026-11-08，首启重导不再 FileNotFoundException） |
| 自定义 Layer/Tag 设置管线（Settings 目录与 Game/ 接通） | ⬜ 待接（Layer 下拉空的根因） | — |

### 3.7 项目面板（project / Swing）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Unity 两栏布局（Swing ProjectPanel：左目录树 JTree + 右内容区 JList + 面包屑 + 双击进入 + JSplitPane 分栏拖拽；原 web 版 2026-10-17 落地、随去 Web 化清退） | ✅ Swing 实装（2026-10-18） | ✅ 用户实测（2026-10-23） |
| ProjectTreeSnapshot 全量树直调（仅 Assets/src 两根；.meta/隐藏项过滤；目录优先+名称序；后台线程纯 IO → EDT） | ✅（2026-10-18） | ✅ 用户实测（2026-10-23） |
| 选中直写 EditorSelection project 通道（Node 直持 File；目录变更即上报含面包屑导航——原桥「相对路径上报+闸门」随直调消亡） | ✅（2026-10-18） | ✅ 用户实测（2026-10-23） |
| ctx.project 右键域首期（showInExplorer / copyPath / reimport 三实做 + Unity 壳占位；面板三入口先落选中再弹菜单） | ✅ 命令侧（2026-10-17）/ 面板接入（2026-10-18） | ✅ 用户实测（2026-10-23） |

### 3.8 插件体系与 i18n（plugin / i18n）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 插件契约（Plugin：namespace/元数据/setup；PluginContext 注册门面含 i18n()） | ✅（2026-10-20，Editor_Plugin_Design §2） | ✅ 用户实测（2026-10-20） |
| ServiceLoader 发现 + editor.core 即插件（EditorCorePlugin + META-INF/services；三个 Setup 迁 setup 经 ctx 收口，旧直调点摘除） | ✅（2026-10-20） | ✅ 用户实测（2026-10-20） |
| 第三方装载管线（PluginClassLoader parent-first / PluginInstance.load 全程不抛 + 失败关 cl 释放 jar 锁 / 一个 jar 一个插件） | ✅（2026-10-20） | — 静态骨架，第三方 jar 实测随后置装载批 |
| PluginManager 宿主管线（classpath 恒先 fail-fast / jar 段 warn 跳过 + FAILED 账本 / namespace 冲突后来者跳过 / 语言包注册先于 setup） | ✅（2026-10-20） | ✅ 用户实测（2026-10-20） |
| 装载接入（ProjectLoadingFrame Engine Main 线程、GradleProject 后资产初始化前；Plugins/ 目录项目级） | ✅（2026-10-20） | ✅ 用户实测（2026-10-20） |
| 插件装载错误显性化（任何插件报错——本体/第三方、发现/装载/冲突/setup——弹错误框显示 namespace+错误信息，确认后关闭编辑器 System.exit；原「第三方 warn 跳过/降级装载」口径作废，Editor_Plugin_Design §3.4） | ✅（2026-10-29） | ✅ 用户实测（2026-10-31） |
| i18n 基建（I18nKey 自持 namespace + text() / I18nScope + EditorLang 门面 / I18n 注册中心：约定路径装载 UTF-8、!key! 兜底不抛、去重 warn、miss/used 采集、setLanguage 启动期锁定） | ✅（2026-10-20） | ✅ 用户实测（2026-10-20） |
| 菜单 i18n 首批（MenuCommand.label()→I18nKey 全链；key=命令 id 域基类默认；渲染点 .text()；lang/zh_cn.properties 36 条，含 create.material 补注册 2026-10-20） | ✅（2026-10-20） | ✅ 用户实测（2026-10-20） |
| 新功能文案随批 i18n（导入进度框 3 条 / 创建着色器弹框 2 条 / 着色器诊断台状态行 5 条随功能批次接 EditorLang，2026-10-27——不再攒批进第二批） | ✅（2026-10-27） | ✅ 用户实测（2026-10-30，随④焦点扫描批次验收） |
| 默认语言 en_US（`lang/en_US.properties` 全量 50 键英文翻译新建 + `I18n.DEFAULT_LANGUAGE` 切 "en_US"——必备默认文件换位，zh_CN.properties（原 zh_cn 同批大写规范）降为可选本地化包，Editor_Plugin_Design §7.1 拍板） | ✅（2026-10-29） | ✅ 用户实测（2026-10-31，随 i18n 全量化批次验收） |
| **i18n 全量化批次**（编辑模块 ~300 条非日志字面量全迁：异常/弹窗/面板与窗口标题/检查器标签与面板/打包日志行；双语言包 zh_CN·en_US 各 354 键对齐；异常面收 I18nKey——LangException 受检基类 + TipException/ShaderParseException 构造器收键；@InsField/@InsHeader/InsEnumLabel label 改语言包局部键；Editor_Plugin_Design §9） | ✅（2026-10-30） | ✅ 用户实测（2026-10-31） |
| 源码机检（CjkStringLiteralCheckTest：CJK 字面量即红，log 行豁免 + 引导期/数据面白名单） | ✅（2026-10-30，Editor_Plugin_Design §4.5-3） | — 随单测任务（:javelin-editor:test 全绿面） |
| i18n 报告导出命令（Tools 域 miss/used 分桶工单）+ 注解层启动校验（§4.5-2） | ⬜ 后置批次（Editor_Plugin_Design §7.2） | — |

## 4. 构建与发布链（跨模块）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| project-template 游戏模板（quickBuild / GameMain / Game/ 设置持久化 / -PbuildMode） | ✅ | ✅ 随打包链（2026-10-13） |
| GradleProject 构建链（compile / jarBuild 进程编排） | ✅ | ✅ 用户实测（2026-10-13） |
| jlink 裁剪 JRE 运行时（jdk.unsupported 必含） | ✅ | ✅ 用户实测（2026-10-11） |
| 打包对话框（Swing PackDialog：构建形态/启动场景/构建模式/窗口元信息表单 + 实时日志流回显；原 CEF pack.html + /build 桥于 2026-10-17 迁 Swing，构建链逻辑原样） | ✅ | ✅ 构建链三端实测（2026-10-13）+ Swing 迁移用户实测（2026-10-23） |
| JAR 形态产物（Builds/jar，双击可玩） | ✅ | ✅ 三端实测（2026-10-13）；lib 目录路径错位修复（2026-10-17） |
| AOT 形态（native-image 原生 exe / 外置 DLL lib/ / AotGameMain / reflect-config / resource-config） | ✅ | ✅ 三端实测（2026-10-13，AOT/JAR/编辑器一致） |
| javelin-ast ReflectiveProcessor（编译期生成 reflect-config.json，core/game 分目录） | ✅ | ✅ 随 AOT（2026-10-13） |
| 内置资源索引分模块生成（core / editor 各一份 `builtin-asset.index`，释放器按 `ClassLoader.getResources` 枚举合并释放；引擎级内置资产 `builtin-asset-core` 2026-11-07 迁回 `javelin-core/src/main/resources`，core 测试侧 `testImplementation project(':javelin-editor')` 反向依赖随批摘除） | ✅（2026-11-07） | ✅ 用户实测（2026-11-07，编辑器释放+导入链与 core 测试装载全过） |

## 5. 支撑模块

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| javelin-tool（DebugText 字库光栅化 / shaderc 编译调用） | ✅ | ✅ 字库生成链（2026-09-27） |
| javelin-log（日志实现） | ✅ | — 支撑设施，随全链使用 |
| logback 文件输出启用（info/error 双 rolling appender：单文件 20MB 切分 + info 保留 10 天/512MB、error 仅 ERROR 保留 30 天/256MB + gzip；运行时 logs/ 入 .gitignore） | ✅（2026-10-30） | ✅ 用户实测（2026-10-30，logs/info、logs/error 生成） |

## 6. ⏳ 待验收挂账一览

> 已落地、用户实测未做——验收后翻状态并从本表移除。
>
> **当前挂账 0 项**。
>
> （**2026-11-10 销账**：HYBRID 档 / Deferred 复用（三期批次④-B）——用户实机 HYBRID 光照正常、与 FORWARD 两路一致；随验坑 Pitfalls #35（set0 相机块 stageFlags VERTEX-only，LightingPass fs 读 cam.view 越权读零全黑——修=ALL_GRAPHICS 超集）；遗留口径=非光照材质（default.shader）HYBRID 档 albedo 仍乘光照（无灯=黑，Deferred 物理正确），「直读 albedo」契约归四期材质专档。§2.4 行已翻✅；设计=Engine_RenderPipeline_Design v0.6、Engine_Lighting_Design v0.10。）
>
> （**2026-11-08 三批同日验收销账**：①节点检查器「添加组件」v0.17（§3.4 行翻✅）；②组件头右键菜单 / 组件移除 v0.18（§3.4 行翻✅）；③耗时操作指示器 TimeConsumingDialog（§3.1 行翻✅）——设计=Inspector_Design §13/§14、Editor_Swing_Design §4.3 v0.2、Engine_Scene_Design §2.2 v0.7。**遗留**：耗时指示器的调用点（焦点扫描 / 工程加载链 / 打包构建线程三候选）仍未接入，属后续动作。）
> （**变换 Gizmo 旋转/缩放 + 几何程序化 + 柄拾取改道 2026-11-08 用户实测全项通过、销账**——验收状态见 §3.5 两行；设计=Editor_Design §10、Engine_Picking_Design v0.2。）

（**内置资源归属迁移整批 2026-11-07 用户实测销账**——步骤一：`builtin-asset-core/`
（mesh/shader/shadow/skybox）从 editor 模块整体迁回 `javelin-core/src/main/resources`，
索引生成分模块（core / editor 各一份 `builtin-asset.index`），core 测试侧
`testImplementation project(':javelin-editor')` 反向依赖摘除；步骤二：内嵌 spv 三件全迁
源码导入链——pick（→`builtin-asset-core/pick`）、DebugText（→`builtin-asset-core/debug`，
含**新资产类型 `.chars`** 字库字符集源 + `BitmapFont`/`BitmapFontImporter` 烘焙链）、
DebugLine（→`builtin-asset-editor/debugline`），javelin-tool 三个生成 CLI 全清退、回归纯库。
验收口径 = 编辑器开项目内置资产释放并经导入链出 Library 产物、视口拾取 / F3 调试文本
（含汉字）/ 调试线实机正常、core 测试装载链通过。细则见 Skybox 档 §8 / Picking 档 §5 /
DebugText 档 §4 v0.2 / DebugLine 档 §3.5 / Editor_Design §2。）

（检查器旋转行跳动根治 2026-11-07 用户实机复验销账——俯仰越 ±90° 拖动/键入不再
跳动、另两值不再变；两轮修法（欧拉口径切 Unity 同款 Z-X-Y + 面板级自旋回声抑制，
含随验追修：判定器按模长归一、判定数据源改事件原始四元数）与根因存档
Inspector_Design v0.15。）

（三期批次④-A 主光阴影 ShadowPass 单张硬影 + 两散件 2026-11-01 用户实机验收
销账——主光投影正确/调 castShadow 即时增减投影物/透明物不投影不接影/Scene-Game
双视口不掉影/阴影图分辨率切档即时重建；随批拍板改向：深度比较采样器 LINEAR 换装
NEAREST（实机边缘块状锯齿反馈，PCF 软化归四期）；散件两枚同批验收：project.create.scene
创建场景命令（创建即切换 Unity 口径）+ 启动场景记忆写环（重启恢复上次场景）。细则见
Roadmap 三期条目与光照档 §4.5.1/§9 批次④-A 行 v0.8。）

（三期批次③ cluster 构建 + Forward 着色消费 2026-11-01 用户实测全项
销账——三型灯明暗正确/光圈软化认可（距离窗换 Unity URP 口径）/Scene-Game 双视口各自
光照（批次②首视口挂账销账）/单测全绿（ClusterBuilderTest + ShaderImporterTest）/
**40 盏 HSL 彩色点光压力场景帧率稳**（压力场景随验生成进 test 项目 default.scene，40 灯
网格铺 plane 彩色混光）；随验四坑排障入 Vulkan_Pitfalls #29、ambient 纯黑拍板（Unity
口径）。细则见 Roadmap 三期条目与光照档 §9 批次③行 v0.7。）

（三期批次② 光数据模型 2026-10-31 当日用户实测销账——菜单挂灯（三型）+ 检查器编辑
+ getSun 主光自动裁决换位 + F3 行 8 灯数；单测全绿（LightCollectorTest + GameSceneTest
灯用例）；随验收过程三笔拍板改向：isMain 显式标志推翻改 getSun 自动裁决（对齐 Unity）、
灯族子类化（不支持切换类型）、@InsHeader 子类显式贴法契约 + 图标彩色规则立定——细则见
Roadmap 三期条目与光照档 v0.6。）

**历史注**：二期 ①~⑧ 2026-10-30 全清零；开源前置批 Ⓐ 包名迁移+版本 `0.1.0-SNAPSHOT` 定版 2026-10-31 用户实测销账——test 全绿 + mavenLocal 重发新坐标 + 编辑器新建项目/打包三端实机与改前一致，细则与坑点见 `doc/Engine_Roadmap_Progress.md` §6 构建链归档；三期批次① RHI SSBO/compute 底座 2026-10-31 当日用户实测销账（ComputeSsboTest 全环 + ComputeShaderImporterTest 绿；两坑入 Vulkan_Pitfalls #27/#28）。

**二期挂账历史（①~⑧全销账，保留记录）**：

~~①两个 Demo 回归（SceneRenderDemo·MultiCameraDemo——Texture2D 引用层改造回归；单测面已全销：
ShaderParserTest / Texture2DTest 2026-10-30 跑绿，随绿修复三笔——ShaderParser Range
外层括号漏吞（实现）、float[] 断言须 assertArrayEquals、Blend alpha 通道期望写反；另
GameSceneTest 两用例期望错、MaterialTest setShader 旧口径断言，均为「实现/拍板已改、测试
没跟上」的「写下即未跑绿」——**教训：批次收口必须整套 test task 跑绿，不挑用例跑**）。~~
**2026-10-30 用户实测销账：两 Demo 画面与改造前一致（分屏/旋转/透明混合/贴图正确）——
程序化直构造贴图路径（`new Texture2D(Image)`）引用层回归闭环。**

~~②资产热重载批次①（2026-10-27 落地，Engine_AssetReload_Design v0.3/v0.4——invalidate/reload
+ 反射 Walker（含 Material 补默认值后处理）+ requestReconcile 孤儿回收 + AssetReloadService
手动 reimport 链 + 材质面板 AssetReloadedEvent 最小钩子；**2026-10-29 拍板收窄只 shader**
（.mat/.texture/.smesh/像素源摘出 reload 链「后面再加」，外部手改 .mat 不自动生效——
材质编辑走检查器内存操作即时生效）；验收清单见该档 §9：改 .shader → 选中 reimport →
画面当帧变 / 同名参数用户值保留 / F3 存活计数不涨）~~ **2026-10-30 用户实测验收销账。**

~~③坏 shader stub 产物 + 渲染回退默认 shader（2026-10-27 落地，Engine_Material_Design
v0.3——Shader.isValid 判据 + ShaderImporter 编译失败落 stub + DefaultShader 门面渲染链
回退（bridge.sync/materialSet/pipelineFor）+ 修复后热重载 walker 换代恢复；验收：改坏
.shader → reimport → 画面回退默认紫色 + 诊断台坏态 + 创建命令弹框；修复 → reimport →
画面恢复）~~ **2026-10-30 用户实测验收销账。**

~~④焦点扫描资产刷新 + 导入进度模态框（2026-10-27 落地，Engine_AssetReload_Design §5.1——
主窗口重获焦点 → EditorFocusGainedEvent → AssetReloadService 后台 refresh() 扫描自动导入
（Unity Auto Refresh 口径）→ 有待导入弹 ImportProgressDialog 模态小框（Timer 轮询在途数
自动关）；验收：外部改 .mat/.shader/贴图 → 切回编辑器 → 自动生效（模态进度框闪现），
自身保存不误触发）~~ **2026-10-30 用户实测验收销账。**

~~⑤L1 场景级 raycast（2026-10-28 落地，Engine_Collision_Design v0.2 §10——Physics 静态门面
（raycast 最近命中 + raycastAll 全命中；maxDistance/layerMask 参数，Unity 口径——同日
Ray.limit 方案短暂落地即回退）+ RaycastHit + Scene.getColliders 注册表投影 + 引擎 setScene
接线；随批相机链 Scene.getMainCamera（Unity Camera.main 口径）+ JavelinEngine.mouseRay
（窗口尺寸+鼠标坐标组装））~~ **2026-10-30 销账：PhysicsTest 14 用例 + GameSceneTest
主相机用例全绿（含随批两处期望值修正——首帧即驱动 start/update，设计档 §4 口径）；
mouseRay 归运行时装机验证（后置）。**

~~⑥Mesh 子网格 + MeshRenderer 多材质（2026-10-29 落地，Engine_Assets_Design §2.9 /
Engine_Scene_Design §5.1 v0.5 / Inspector_Design v0.13——SubMesh 区间列表 +
setSubmeshes/getSubmeshCount + 上传校验 + RHI drawIndexed 三参 firstIndex + 每
(renderer, 子网格) 一 RenderObject + 缺失位 DefaultMaterial 兜底（**语义变化：原
material null 不渲染作废**）+ .smesh [SUBMESH_N] 段（与 [INDEX] 互斥）+ 检查器
GUID_ASSET_LIST 多材质行 + getMaterial/setMaterial 兼容门面 + 旧 .scene material 单键兼容）~~
**2026-10-30 销账：MeshSerializationTest + SceneSerializationTest 单测绿 + 编辑器实机
（两子网格各按材质渲染、缺材质位默认材质可见）——验收揪出并修复文本列表 guid 引用
硬编码 Asset.class 崩（TextImporter 走对象头泛型类、TextExporter 写具体类名，同
readSerializable 2026-10-04 坑复发）+ default.scene 5 处文本头手迁 Asset→Material
（用户拍板方案 B：不做代码兼容旧头）。**

⑦guid 真值单源=伴生 .meta（2026-10-29 落地，Editor_Design §6.2——用户拍板：只有
src 下不需要 meta、guid 头只剩 src 下 .java、内置资源生成 meta 一起释放）：9 个内置
源文件剥 guid 头 + 同目录随包伴生 .meta（guid=原头值——既有引用零迁移）；释放器连
meta 一起释放（首次拷 meta+补默认导入设置，包缺 meta 抛错显性化）；管线
libraryFileFor/ShaderImporter/SimpleMeshImporter/AssetInput/ShaderInspectorContent
全部收敛 meta 单源；MetaManager createNewMeta 一律随机 guid + updateIndex 去头兜底
分支。**起因=焦点扫描无限导入循环**（改 .mat 颜色触发：无头 .shader 恒判未导入 +
模态进度框夺焦/还焦反馈环，AssetReload 档 §5.1 坑点存档）；验收 = 实机：首启释放
内置 meta 无报错 / 焦点反复得失不再循环导入 / 场景里内置资产（default/gizmo/
editor-grid）引用不断、画面正常 / ShaderParserTest 回归——**2026-10-29 用户实测
销账（死循环根治，原触发场景复测通过）**。

~~⑧资产热重载注册表流派 + 运行期可变 + Cleaner 重构（2026-10-30 落地：
**① Walker 改对象注册表全量扫描**（ObjectId 构造回调 registrar + EditorObjectRegistry
弱引用收拢 + setter 优先换指针 + Material 保参退回 setShader，AssetReload 档 v0.5）；
**② 导入回调总线化**（AssetImportPipeline 删 ImportListener，改发 AssetImportEvent）；
**③ Texture2D 冻结废除 + revision**（贴图档 v0.3 §8）；**④ Material.setShader Unity 保参
+ textureRevision**（材质档 v0.5 §3.3）；**⑤ Cleaner Registration 合一**（手动/Cleaner 共用
两帧队列 + device.dispose flush + compute layout 补注册，Vulkan_Pitfalls #26 + RHI 进度档）；
**⑥ logback 文件输出启用**）~~ **2026-10-30 销账：改 .shader → reimport → 画面当帧变 +
F3 存活计数不涨 / 运行期 setImage/setFilterMode 即时生效 / Texture2DTest·MaterialTest
回归绿（MaterialTest 旧「重置默认」用例随保参拍板重写为三分支用例）/ 实机 resize 与热刷
无显存增长 + logs/ 生成——单测+实机全过。**

2026-10-25 用户实测销账 2 笔（状态已翻入各模块表）：①贴图导入管线 v0.1（§2.5/§3.6
——Image 像素资产 + Texture2D 三层改造 + ImageImporter + alpha 修复）；②纹理资产
v0.2（§2.5/§3.2/§3.4/§3.6——project.create.texture + TextureImporter + .texture
面板 + AssetInput 多后缀 + 材质贴图行换装内联）。连串追修随批验收：NewShader
_MainTex 属性、ShaderParser 2D 死分支、GuidHeaderManager 二进制读炸 + 拖放 guid
双源反序 + 无 meta 错误弹窗、材质行单源=shader 声明、贴图行回显漏传。

2026-10-25 用户实测批量销账 6 笔（状态已翻入各模块表）：资产管线 meta 统一与重导检出（§3.6）、project.create.shader 创建命令（§3.2）、检查器 v0.7 .shader 诊断台 / v0.8 AssetInput+材质 Shader 行换装（含 v0.9 参数编辑·分组形态）/ v0.10 GUID_ASSET 升可编辑 / v0.11 材质编辑即时联动渲染（§3.4）。

2026-10-23 用户实测批量销账 15 项：去 Web 化收官链七项（ProjectFrame/ProjectLoadingFrame、PackDialog 迁 Swing、jcef 桥包/jcefmaven/jackson 清退、M2 层级面板、全链引用化、M3 项目面板、文件类型注册器+file.open）+ 场景树 10-22 增量两项（根行选中、脏星标行宽）+ 散件六项（Scene/Game 切换、hierarchy.create.camera、SceneImporter、Transform 变更检测、.mat 全链、Material.surfaceType 面型裁决与 Pass 名分桶——状态已翻入各模块表）。

~~通用 CEF 对话框 Dialog~~ 挂账项作废（2026-10-17 类已随去 Web 化删除，验收场景消亡）。

## 7. 已知问题（bug 类，非功能缺口）

- ~~resize Cleaner 兜底只销毁 image 不释放内存~~ 已修并验收（A 项 resize 显式 dispose 2026-10-09 / B 项 `DestroyTask` 带 `memoryHandle` 成对 free 2026-10-23，均用户实测；坑点存档 Vulkan_Pitfalls #24）；
- loadScene 失败路径 getEditorGameScene() NPE 未补；
- 导入成功即全量 `updateIndex()` 热点（Editor_Design §9）；
- Gizmo 拖拽中按 Alt 相机环绕与拖拽并动（已知边缘，后调）。
- 窗口拖拽 resize 期画面非等比拉伸、松手即愈（2026-10-17 拍板接受，Editor_Design §9-11）；

> 细节与修法归 `doc/Editor_Design.md` §9 / 各排查记忆档，本文只挂账。

---

> （注：部分内容可能由 AI 生成）

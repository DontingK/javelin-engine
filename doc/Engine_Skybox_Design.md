# 引擎天空盒设计（Engine Skybox Design）

> 版本：v0.5
> 日期：2026-11-08
> 说明：三期批次⑤专档——视口天空盒的 cubemap 底座、SkyboxPass、资产与场景接线。**三步全部验收关账（一/二 2026-11-02、三 2026-11-07 用户实机全链）**。**v0.5（2026-11-08）：天空盒归属迁入环境设置组件（`Environment.skybox`，挂节点上的普通组件、全场景唯一）——原 `GameScene.skybox` 字段与 `Scene.getSkybox()` 退役，§5 接线/编辑器两段随改；编辑入口=节点检查器（`.scene` 检查器不再显示环境）；旧 .scene 的 skybox 键不再读取；随用户实测 bug 修复——**禁用环境组件（或停用其节点）即摘天空盒**（桥按 isActiveAndEnabled 过滤）**。
> 职责边界：
> - 本文管**天空盒的视觉通路**（cubemap 资产 → 场景引用 → SkyboxPass 绘制 → ClearFlags 兑现）；
> - 管线档管 Pass 编排（SkyboxPass 插入位置 3.5 位，RenderPipeline_Design §3）；
> - **环境光照不在本文范围**（2026-10-31 拍板：纯视觉——天空盒不作 ambient 源；ambient 常量色维持，环境光升级归 GI 探针期，Engine_Lighting_Design §10）；
> - cubemap 底座与 GI 探针的 texture array 同族（3D 纹理/纹理数组为探针采样前置缺口，Roadmap 三期 GI 段）——RHI 侧扩展按通用纹理种类面建。

---

## 1. 拍板记录（2026-10-31）

- **cubemap（立方图）形态，不走 equirect 全景图捷径**：六面独立采样方向标准、无极区畸变；代价 = RHI 新增 cubemap 纹理种类（Texture2D 之外的第一种新纹理），用户明知此缺口仍选质量路线（与 clustered 一步到位同风格）。equirect→cubemap 转换工具（用户拿到的全景图转六面）随导入链评估，非本期；
- **纯视觉范围**：天空盒只当背景画，不参与光照（ambient 源/SH/mipmap 降采样环境项全部后置归 GI 探针期）；
- **批次定位 = 三期批次⑤**（光照四批之后的独立批）：依赖仅 RHI cubemap 底座 + 管线 Pass 列表，与光照批次零耦合，可穿插执行；

## 1.1 实装拍板（2026-11-02，批次⑤开工前决策点全拍）

- **资产面 = equirect 全景图导入自动转六面**（用户拍板）：导入器对每面每像素按方向向量双线性采样源全景图生成六面。素材最易得；运行时仍是标准 cubemap 六面，与 §1「不走 equirect 形态」拍板不冲突（equirect 只是导入源，非运行时形态）。六面独立图/.cubemap 引用形态不做；
- **深度语义 = `gl_Position.z = w` 远平面技巧** + ZTest LEqual + ZWrite Off（推荐组合拍板）：一次推到远平面免调深度上限，Unity 同款；
- **着色器装载 = SpvShader 资产导入链**：内置 `builtin-asset-core/skybox/skybox.vert/.frag` 源码经 SpvShaderImporter 编译落 Library，Pass 经 AssetManager 按路径装载（ShadowPass 2026-11-01 同款先例；覆盖制不经材质面——天空盒不受光照注入影响）；~~引擎内嵌 spv~~ 随 ④-A 拍板方向一致作废；
- **几何 = 内嵌 36 顶点 vertex buffer**：覆盖制 Pass 自持 VB，不走 box.smesh 资产链；
- **视图矩阵 = set0 CameraBlock `view@64B`**（批次③ view 扩块复用，零新 push、零新 uniform 通道）：~~vs 内 `mat4(mat3(view))` 去平移~~ **已修（2026-11-06 用户实测「相机离原点出黑区」排障）：`viewProj * mat4(mat3(view))` 复合 = `proj·(R²w+t)`（`[R|t]·[R|0]=[R²|t]` 非想去平移）——相机在原点仅天空转速×2（自旋 Demo 未暴露），离原点 |t|>1 相机跑出单位立方体=黑区。现行=立方体平移到相机世界位置 `p=-Rᵀ·t`（vs 内从 view 反解）走正常 viewProj，`view·(w+p)=R·w` 平移自然抵消**；
- **SKYBOX 清屏语义**：~~视口 SKYBOX 时 Opaque 转「颜色 LOAD + 深度 CLEAR」（省一次纯色清屏，SkyboxPass 3.5 位全屏覆盖）~~ **已作废（2026-11-05 排障改定：色 LOAD 赌 SkyboxPass 全屏覆盖被实锤破——覆盖缺口像素保留上上帧内容（swapchain 多图轮换 LOAD 读到两张前旧帧），相机一动即拖影；现行=清屏→不透明→天空→透明 Unity 同序，Opaque 对 SKYBOX 视口照常清屏（背景色+深度），SkyboxPass 画在不透明后靠 LEqual 被遮挡，清屏只影响无天空像素=背景色）**；**场景无 skybox 资产时回退纯色清屏**（正常域空状态不弹错——红线 15 边界）；skybox 有无经 PipelineContext 透传给 Opaque；
- **实勘确认（缺口清单）**：`TextureType.CUBE/CUBE_ARRAY` 枚举与 viewType 映射已预埋，缺①`VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT` 创建旗标（`VkImageCreateInfo.flags` 现完全未设）②`Device.uploadTexture` staging 初始上传写死 `whole2D` 单层拷贝——CUBE 走单命令六层整拷（`TextureRegion.cubeFaces` layerCount=6，随验首版逐层循环踩 Pitfalls #30 已翻篇；buffer 六面连续紧密打包）；

## 1.2 步骤三资产面拍板（2026-11-03）

- **equirect 源 = `.hdr` + `.pano` 双后缀**：`.hdr` 新写 RGBE 解码器（约 200 行，Poly Haven 生态素材直接可用）；`.pano` = png/jpg 全景图改后缀拖入（零解码工作量）。产物 v0.x RGBA8 量化，float 产物归四期环境光批；
- **faceSize 固定 512**：少一个导入设置面板，产物 6MB/个可接受；ImportSettings 可调后置；
- **不设材质中间层，`Scene.skybox` 直挂 cubemap 资产**（知情分歧 Unity：Unity 挂 Skybox 材质的价值在「换 shader=换天空类型」，现仅一种天空加层是空壳；程序化天空立项时再升级引用类型，一次序列化迁移）；
- **Scene.skybox 编辑行挂场景根行检查器**：新开 `.scene` 键 InspectorProvider——层级根行=场景文件已有选中链路，天然入口；
- **cubemap 检查器 = 六面 2×3 缩略网格**（Java2D 直画）+ faceSize/源尺寸参数行；
- **运行时形态维持 cubemap、不跟 Unity 直采 equirect**（2026-10-31 质量路线拍板照旧：导入时转六面是一次性成本，六面采样方向标准、无极区畸变，equirect 逐像素换算两极 texel 密度不均）。

## 1.3 相机默认清屏翻 SKYBOX（2026-11-04，步骤三验收排障拍板）

- **起因**：用户实机「拖 .hdr 换绑成功但视口不显示天空盒」——SkyboxPass 挂载双判定（视口 `ClearFlags.SKYBOX` + rs 挂有 skybox）缺前者：Camera 组件默认 SOLID_COLOR（建枚举时 SKYBOX 尚不存在的历史遗留），编辑器相机同吃默认，SkyboxPass 全程不挂载；
- **Camera 组件默认值翻 SKYBOX**（对齐 Unity 默认）：无 skybox 资产时 Opaque 回退 SOLID_COLOR 清屏（backgroundColor 照旧消费）——无天空盒场景视觉零变化；已保存场景的落盘值不受默认值影响；
- **编辑器 editorCamera 同设 SKYBOX**（Unity Scene 视图口径）：SCENE 视口也画天空盒；
- **连带已知边界**（不新增）：`isMsaaPath` 对 SKYBOX 视口降级 1x（AbstractForwardPass，v0.x 边界）——MSAA 默认 OFF，开 MSAA + Skybox 相机才触发，设计已有记录。

## 1.4 随验收四连排障（2026-11-05~06，步骤三实机验收揪出、全修全复验）

- **拖影**（11-05）：§1.1 SKYBOX 清屏语义的「色 LOAD 赌全屏覆盖」作废改定（见 §1.1 划线段——swapchain 多图轮换 LOAD 读到两张前旧帧）；**教训：颜色 LOAD 的前提是数学上保证全覆盖**；
- **偏暗**（11-06）：quantize 线性直存 RGBA8 缺伽马编码——.hdr 解出线性光而显示链是伽马字节直通（png/jpg 本就伽马域）；修复=线性→sRGB 编码后量化（§5 ④段已标修），只进 .hdr 路径、.pano 字节直通不受影响；**教训：往字节直通显示链灌线性光必须带伽马编码**；
- **黑区**（11-06）：skybox.vert 矩阵复合 bug（§1.1 视图矩阵段已标修——`[R|t]·[R|0]=[R²|t]` 非去平移，相机离原点跑出立方体）；**教训：「去平移 view」的正确姿势是移物体到相机位置，不是把去平移矩阵叠进 view**；
- **棱**（11-06~07，**双根因**）：①渲染侧=SkyboxPass 采样器 `linear()` 带 REPEAT 寻址——REPEAT 卷绕面边缘 texel 毁掉跨面插值，修复=新 `SamplerDesc.cubeLinear()`（LINEAR+CLAMP_TO_EDGE 三轴，Babylon thinEngine 同款强制口径——Babylon_Skybox_Reference §5）；②数据侧=§2 步骤三行 11-07 转换器未归一化（用户自修）；**教训：cubemap 采样器寻址必须 CLAMP_TO_EDGE；验收素材要面边缘高对比（纯渐变掩盖接缝 bug）**。

## 1.5 相机文案对齐 HDRP（2026-11-06）

用户以 HDRP 为参照（相机无 Clear Flags 字样）：架构本已同构（HDRP Clear Mode ↔ ClearFlags 一一对应；HDRP 天空盒=Volume HDRI Sky cubemap 槽独立于相机 ↔ Scene.skybox 场景级槽+相机只选模式），i18n 行改「清屏模式/Clear Mode」（键名不动零迁移，枚举名/序列化零改动）。

## 2. 实装分步（三步派发）

| 步骤 | 内容 | 验收口径 |
|------|------|----------|
| 一 | RHI cubemap 底座（CUBE_COMPATIBLE 旗标 + `TextureDesc.textureCube()` 便捷构造 + `TextureRegion.layerCount/cubeFaces` 六层整拷 + `Device.uploadTexture` CUBE 分支）+ CubemapTest | 六面上传+采样读回（§7 第三条）——**✅ 验收关账（2026-11-02 用户实机：读回比对通过）**——首验踩 layerCount 写死 1 坑（Pitfalls #30）修毕 |
| 二 | SkyboxPass + `ClearFlags.SKYBOX` + skybox 透传（RenderingSystem.skybox RHI 纹理引用，测试形态）+ SkyboxDemo | §7 前两条——**✅ 验收关账（2026-11-02 用户实机：SkyboxDemo 五验收点全过）**；随批两修：PSO 深度参数序填反 + depthAttachmentFormat 漏声明（Pitfalls #31）；测试装载链补 TestAssetManager（core 测试环境 SpvShader 现编译）；Scene.skybox 字段/序列化随步骤三资产类一并落 |
| 三 | 资产面（equirect 导入器自动转六面 + CubemapImage 资产类型 + 检查器面板 + Scene.skybox 编辑行） | 导入链往返 + 编辑器实机（拍板 2026-11-03 §1.2/§5）——**✅ 验收关账（2026-11-07 用户实机：.hdr 拖入导入/场景根行换绑/视口即时换背景/转动无痕全过）**。3a 数据面（CubemapImage/EquirectConverter[RGBE+双线性转六面]/CubemapImporter[.hdr/.pano]/单测；u 公式与 RLE 规格均经原文核对——three.js EquirectUV + Cornell rgbe.c，见 §5；2026-11-07 排障修：纬度吃未归一化面坐标（面心 \|dir\|=1 恰对、越靠棱角越错）——±Y 面整面塌成源图极行、侧面纵向从 ±45° 变覆盖整球，实机症状=上下盖一圈（立方体棱/角）明显分界、竖直棱正常；`faceCoordToDir` 出口归一化 + 单测补角点归一化断言，用户复验通过）；3b 场景接线（GameScene.skybox 首个 Asset 引用字段 + Scene 接口/EditorScene 委托 + TextureUpload.uploadCubemap + RS 资产重载[懒上传+旧图两帧退役+所有权区分测试形态] + 桥 sync 身份门控推送/clear 复位 + SkyboxDemo 改资产形态）；3c 场景根行检查器（.scene 键 SceneInspectorProvider + skybox 编辑行[编辑载体=当前打开活实例直写+markDirty，非当前场景只读态] + AssetInput 收 CubemapImage）；随验收六连排障全修（§1.3~§1.5） |

## 3. RHI cubemap 底座（步骤一）

- **纹理种类**：`TextureCube`（或 Texture2D 泛 viewType 方案——实装前按 RHI 既有 TextureDesc 形态定，不预定案）；Vulkan 侧 = `VK_IMAGE_VIEW_TYPE_CUBE` + 六层 face array + `VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT`；
- **ImageView/采样**：viewType CUBE、六面 layer range；sampler 复用现有（无特殊参数）；
- **上传**：六面像素数据分 layer 上传（staging 现走 host-visible 直写路径）；
- **通用性红线**（对齐光照批次①口径）：按通用纹理种类面建，禁止只伺候天空盒的特化窄接口——GI 探针 texture array / 反射探针 cubemap 复用此底座；
- **边界**：mip 生成后置（无 mipmap 采样先用 LINEAR/无 mip 过滤；环境项 mipmap 链归 GI 期）。

## 4. SkyboxPass（引擎侧）

- **位置**：管线 Pass 列表 3.5 位——Lighting（或 v0.1 形态的 OpaqueForward）之后、TransparentForward 之前；天空盒画在不透明几何之后 = 深度测试挡掉被遮部分，透明物体画在天空盒之后 = 正确遮挡关系；
- **几何**：单位立方体（36 顶点，复用 box.smesh 生成链或内嵌 vertex buffer——实装定）以相机为中心（**去除相机平移的世界矩阵**，视口视锥剔除免做——立方体恒包住视锥）；
- **深度语义**：depth **LOAD + ZWrite Off + ZTest LEqual**、深度上限技巧（`gl_Position.z = w` 推到远平面）二选一——实装定，目标=不污染深度缓冲；
- **着色**：方向向量采样 cubemap（vs 传出世界方向插值）；引擎内嵌 spv（javelin-tool 链路，DebugText/pick 先例——core 无 GLSL）或 builtin-asset shader——**若走材质面则受光照注入影响**（天空盒不应被光照注入加亮），倾向内嵌直绘，实装拍板；
- **ClearFlags 兑现**：`ClearFlags.SKYBOX` 枚举启用（Features 长挂的空槽）——该视口相机清屏时 SkyboxPass 生效；SOLID_COLOR/DEPTH_ONLY/DONT_CLEAR 语义不变；
- **无天空盒引用时**：SKYBOX 标志视同 SOLID_COLOR 回退（正常域空状态，不弹错——红线 15 边界）。

## 5. 资产与场景接线（步骤三定稿，2026-11-03）

- **像素资产类 `CubemapImage extends AbsAsset`**（core render/texture 包，Image 同族单层像素资产）：`faceSize / format（R8G8B8A8_UNORM 起步）/ pixels`（byte[] 六面×faceSize²×4 紧密打包，layer 序 +X/-X/+Y/-Y/+Z/-Z 同 SkyboxDemo/CubemapTest）；Library 二进制产物（Image 同款，非文本协议）；红线 9 字段不 final；
- **`EquirectConverter` 纯逻辑转换**（editor importer 包，`SpvShaderImporter.buildSpvShader` 先例同构——静态方法供单测共用）：每面每像素 → **单位**面方向向量（GL 立方图标准，**与 SkyboxPass fs 采样方向同一约定**；必须归一化——纬度 asin 吃单位向量、经度 atan2 尺度不变，2026-11-07 修）→ 球面 → equirect (u,v) → 源图手写双线性（四邻加权、边缘 clamp；**横向 u=0/1 未跨接缝回绕**，-X 面中线留亚像素断线，改 wrap 要动既有断言，随批待定，core 无现成 CPU 采样器需自写）。单测：轴向 u/v 渐变斜坡源 faceSize=1 精确回采 + RGBE 解码往返。**约定核对存档（2026-11-03 原文核对，均先于单测互验）**：① u 公式 = `atan2(dir.z, dir.x)/(2π)+0.5`（赤道中心 u=0.5 ↔ +X、+Z=0.75、-Z=0.25；v 顶行=+Y 天空）——three.js `EquirectUV.js` 原文（GitHub 浅克隆法），Poly Haven 预览同源，首版误写 `atan2(x,-z)` 差 90° 已修；② 面内方向表 = GL 规范 cubemap face 选择表（Table 8.19）反推；③ RGBE 解码 = Cornell `rgbe.c` 原文口径：RLE 标记 `02 02 宽度大端`、每通道独立 RLE（count>128=重复 count-128 次、count==128 归字面、0 非法）、flat 无标记、换算无偏移 `v·2^(e-136)`（[0,1] 往返精确，Ward +0.5 分叉不取）、`-Y +X`=行主序顶到底；④ v0.x 量化 = ~~线性 0..1 截断（>1 截白；HDR 源的天空漫射值线性入 8bit 会偏暗属已知取舍，视觉不适配归四期环境光批 float 产物一并解决）~~ **已修（2026-11-06 用户实测偏暗排障）：量化改线性→sRGB 编码（标准分段传递函数）后入 8bit——引擎显示链是伽马字节直通（swapchain 非 sRGB 格式、png/jpg 像素本就伽马域存储），线性直存 0.5→128 显示整体发暗，编码后 0.5→188 与 png 口径一致；>1 高亮仍截白、暗部线性段 12.92 保精度。HDR→LDR 本身有高亮截白信息损失（真解归四期 float 产物）不变**；⑤ bgfx 全库无 equirect 转换（IBL 示例用 cmft 预加工 lightprobe，09/18 的 skybox 着色器仅 textureCube 采样与本引擎 SkyboxPass 同形）——转换约定定锚只能取 three.js；
- **`CubemapImporter`**（`.hdr`/`.pano` 双后缀）：RGBE 解码 / ImageIO 解码 → 转六面 faceSize=512 → guid 回写（meta 真值）→ BinaryExporter 落 Library；坏源显性报错（红线 15）；FileType/图标/双语包键随批；index 随 meta 自动收录零改装载链；
- **场景接线**（3b 落地 2026-11-03，步骤二欠账兑现；**2026-11-08 载体迁移**：字段从 `GameScene.skybox` 迁入**环境设置组件** `Environment.skybox`（挂在层级树节点上的普通组件，`@UniqueComponent(scene = true)` 全场景至多一个，见 Engine_Scene_Design §2.5），`Scene.getSkybox()` 接口退役改 `getEnvironment()`——引用语义不变，只是宿主从场景字段改为组件字段，桥侧改读 `scene.getEnvironment()?.getSkybox()`）：write/read（@guid 分流通则）+ 渲染侧 = `TextureUpload.uploadCubemap`（Texture2D 懒上传同族）+ `RenderingSystem.setSkybox(CubemapImage)` 资产重载（引用身份幂等、变化即懒上传 textureCube 单命令整拷、旧图两帧退役走 SkyboxPass 既有纹理换代重建路径；**所有权区分**：资产产物归 RS 持有释放、测试形态直灌 Texture 归调用方——close/退役按 skyboxAsset 判）+ `SceneRenderBridge.sync` 每帧身份门控推送（无环境组件/未挂 skybox 时 null 不动 rs，测试直灌不遭清；clear 复位摘除；**环境组件或所在节点未激活 = 视同无 skybox**——2026-11-08 随 bug 修复补：桥按 `isActiveAndEnabled` 过滤，与相机/可渲染/灯光同口径，禁用环境组件即摘天空盒、视口回退纯色清屏）；资产热重载不入（范围仍只 shader）；
- **编辑器**（3c 落地 2026-11-03；**2026-11-08 改节点组件形态**）：环境设置由菜单显式创建（hierarchy.create.environment：建名为 `Environment` 的节点挂在**场景根级**并选中，层级树可见可删；已存在则菜单置灰）；编辑入口 = **节点检查器**（选中该节点即出环境组件节——`ComponentSection` 同一套字段行/回显/回写，与相机/灯完全同构，编辑载体=内存活实例直写）；`.scene` 检查器（场景根行）**不再显示环境**（2026-11-08 用户拍板「场景不显示」，原手写 skybox 行与其后的环境节均撤出，只留场景分组文件行）；cubemap 资产六面缩略面板后置（v0.x 只读行够用，随需要再扩）；多相机统一读环境组件的同一 skybox（per-camera 天空盒后置照旧）。**旧 .scene 影响**：原 `skybox` 键不再读取，旧档天空盒需在编辑器重建环境并重挂（字段退役的既定代价，2026-11-08 拍板）。

## 6. 与管线的咬合

- Pass 列表/插入：管线档 §3.2 已补 3.5 行（v0.4）；`PipelineContext.skybox`（或现目标直写）随批扩；
- MSAA：SkyboxPass 与 MSAA 分支正交（随现目标采样数走）；
- 多视口：每视口独立判定（相机 ClearFlags）+ 同一天空盒资产；GAME/SCENE 视口共享场景引用；
- 后处理链：天空盒写 lightingRT（HDR 场景色的一部分），链式直通无特例。

## 7. 验收口径

- 场景挂天空盒：旋转相机各方向背景正确、不透明物体正确遮挡天空盒、透明物体画在天空盒之上；
- 无天空盒 + SKYBOX 标志：回退纯色清屏不报错；
- cubemap 底座 Demo（RHI 面独立验收：六面上传+采样读回）。

## 8. 后置项

- **内置资源归属迁移（拍板 2026-11-02，2026-11-07 两步同日执行并用户实测销账）**：
  - **步骤一 ✅（2026-11-07）**：~~builtin-asset-core 现居 editor 模块应迁回 core~~——`builtin-asset-core/`（mesh/shader/shadow/skybox 四组 + 伴生 .meta）整体 `git mv` 到 `javelin-core/src/main/resources`；**索引生成分模块**：core 与 editor 各出一个同名 `builtin-asset.index`（各自只扫本模块资源目录，`generateBuiltinAssetIndex` 两处各一份），释放器仍按 `ClassLoader.getResources` 枚举全部索引合并释放、互不越界；随批摘除 `javelin-core` 测试侧 `testImplementation project(':javelin-editor')` **反向依赖**（`TestAssetManager` 改读 core 主资源，归属错位根因消除）；**同名索引的边界**：`ClassLoader.getResources` 仅在同一 classpath 内并列生效，**shading / fat-jar 合并会把两个同名条目压成一个（后者覆盖前者）**——当前拓扑 game 侧 `project-template` 只依赖 core、editor 侧不经 shading，故无冲突；将来若出现 editor+core 合并 fat jar，须改按模块原名或改扫描协议；
  - **步骤二 ✅ 收官（2026-11-07）**：内嵌 .spv 等非源码资源迁源码（.vert/.frag），统一走编辑器导入链形态（SpvShader 先例 2026-11-01 已定方向）——内嵌二进制与「源码进库、导入链编译」路线相悖。**pick ✅**：`pick.vert/.frag` 落 `builtin-asset-core/pick/`（+ 伴生 .meta）、`PickPass` 经 `AssetManager.loadAsset("core/pick/*")` 装载；tool 侧 `PickAssets`/`resources/pick/`/`generatePickSpv` 与 `core/resources/pick/*.spv` 全清退。**DebugText ✅**：`debugtext.vert/.frag` + 字库一并落 `builtin-asset-core/debug/`——字库新增**资产类型 `.chars`**（字符集源码纯文本），导入器 `BitmapFontImporter` 用打包字体烘焙出 `BitmapFont` 产物（原 `debug_font16.bin` 的 JDTF 格式不变、由 bin 表导出文本字符集，迁移零视觉变化）；`DebugTextAssets` CLI、`core/resources/debug/` 整目录、`resource-config.json` 死条目同批清退（DebugText 档 §4 v0.2）。**DebugLine ✅**：`debugline.vert/.frag` 落 `builtin-asset-editor/debugline/`（+ 伴生 .meta）、`DebugLinePass` 经 `AssetManager.loadAsset("editor/debugline/*")` 装载；`DebugLineAssets`/`resources/debugline/`/`generateDebugLineSpv` 与 editor 侧 `.spv` 全清退（DebugLine 档 §3.5）。**三件全迁、javelin-tool 现无任何生成 CLI（回归纯库）**；
- equirect→cubemap 转换工具、per-camera 天空盒、天空盒 mip 链、程序化天空（大气散射，远期）；
- **天空盒环境光照（作 ambient 源：SH9 球谐/mipmap 降采样）——归四期第 4 项**（Roadmap §4，2026-10-31 立项——本档纯视觉拍板的对应升级位；DDGI 探针落地后并入四期第 5 项）。

## 9. 文档衔接

| 概念 | 出处 | 关系 |
|------|------|------|
| Pass 编排/插入位置 | `Engine_RenderPipeline_Design.md` §3 | SkyboxPass 3.5 位（v0.4 已插） |
| ClearFlags | `Engine_Scene_Design.md` 相机段 / Features §2.3 | SKYBOX 项兑现 |
| 光照/环境项边界 | `Engine_Lighting_Design.md` §10 | 纯视觉拍板单点；ambient 升级归四期第 4 项（Roadmap §4） |
| cubemap/texture array 缺口 | Roadmap §4 四期 GI 段 | 同族 RHI 纹理种类扩展（探针 texture array 随 GI 实装前核实） |

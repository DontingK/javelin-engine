# 引擎天空盒设计（Engine Skybox Design）

> 版本：v0.3
> 日期：2026-11-03
> 说明：三期批次⑤专档——视口天空盒的 cubemap 底座、SkyboxPass、资产与场景接线。**步骤一/二已验收关账（2026-11-02）；步骤三（资产面）方案定稿（2026-11-03，§1.2/§5）待派发实装**。
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

## 2. 实装分步（三步派发）

| 步骤 | 内容 | 验收口径 |
|------|------|----------|
| 一 | RHI cubemap 底座（CUBE_COMPATIBLE 旗标 + `TextureDesc.textureCube()` 便捷构造 + `TextureRegion.layerCount/cubeFaces` 六层整拷 + `Device.uploadTexture` CUBE 分支）+ CubemapTest | 六面上传+采样读回（§7 第三条）——**✅ 验收关账（2026-11-02 用户实机：读回比对通过）**——首验踩 layerCount 写死 1 坑（Pitfalls #30）修毕 |
| 二 | SkyboxPass + `ClearFlags.SKYBOX` + skybox 透传（RenderingSystem.skybox RHI 纹理引用，测试形态）+ SkyboxDemo | §7 前两条——**✅ 验收关账（2026-11-02 用户实机：SkyboxDemo 五验收点全过）**；随批两修：PSO 深度参数序填反 + depthAttachmentFormat 漏声明（Pitfalls #31）；测试装载链补 TestAssetManager（core 测试环境 SpvShader 现编译）；Scene.skybox 字段/序列化随步骤三资产类一并落 |
| 三 | 资产面（equirect 导入器自动转六面 + CubemapImage 资产类型 + 检查器面板 + Scene.skybox 编辑行） | 导入链往返 + 编辑器实机（拍板 2026-11-03 §1.2/§5）——**3a 数据面落地待验收（2026-11-03：CubemapImage/EquirectConverter[RGBE+双线性转六面]/CubemapImporter[.hdr/.pano]/单测；u 公式与 RLE 规格均经原文核对——three.js EquirectUV + Cornell rgbe.c，见 §5；2026-11-07 排障修：纬度吃未归一化面坐标（面心 |dir|=1 恰对、越靠棱角越错）——±Y 面整面塌成源图极行、侧面纵向从 ±45° 变覆盖整球，实机症状=上下盖一圈（立方体棱/角）明显分界、竖直棱正常；`faceCoordToDir` 出口归一化 + 单测补非中心点/面边界断言，待用户复验）；3b 场景接线落地待验收（2026-11-03：GameScene.skybox 首个 Asset 引用字段 + Scene 接口/EditorScene 委托 + TextureUpload.uploadCubemap + RS 资产重载[懒上传+旧图两帧退役+所有权区分测试形态] + 桥 sync 身份门控推送/clear 复位 + SkyboxDemo 改资产形态）；3c 场景根行检查器落地待验收（2026-11-03：.scene 键 SceneInspectorProvider + skybox 编辑行[编辑载体=当前打开活实例直写+markDirty，非当前场景只读态] + AssetInput 收 CubemapImage）** |

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
- **场景接线**（3b 落地 2026-11-03，步骤二欠账兑现）：`GameScene.skybox: CubemapImage` 字段 + write/read 一行（@guid 分流通则，**场景级 Asset 引用首个先例**）+ `Scene.getSkybox()` 接口（EditorScene 委托用户场景口径）；渲染侧 = `TextureUpload.uploadCubemap`（Texture2D 懒上传同族）+ `RenderingSystem.setSkybox(CubemapImage)` 资产重载（引用身份幂等、变化即懒上传 textureCube 单命令整拷、旧图两帧退役走 SkyboxPass 既有纹理换代重建路径；**所有权区分**：资产产物归 RS 持有释放、测试形态直灌 Texture 归调用方——close/退役按 skyboxAsset 判）+ `SceneRenderBridge.sync` 每帧身份门控推送（场景未挂 null 不动 rs，测试直灌不遭清；clear 复位摘除）；资产热重载不入（范围仍只 shader）；
- **编辑器**（3c 落地 2026-11-03）：Scene.skybox 编辑行挂**场景根行检查器**（新开 `.scene` 键 SceneInspectorProvider——**场景级字段编辑首个先例**，AssetInput 收 CubemapImage/.hdr+.pano）；**编辑载体 = 当前打开的 EditorScene.gameScene 活实例直写 + markDirty**（区别 .texture/.mat 文本私有实例——改活实例桥下帧推送即视口换背景零重导链；持久化走用户 file.save 既有保存链），非当前打开场景只读态；cubemap 资产六面缩略面板后置（v0.x 只读行够用，随需要再扩）；多相机统一读场景级引用（per-camera 天空盒后置照旧）。

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

- **内置资源归属迁移（用户拍板 2026-11-02，后续批执行）**：builtin-asset-core 现居 editor 模块应迁回 core（core 引擎自有着色器/网格却挂在 editor 资源树下，TestAssetManager 还要 testImplementation editor 才读得到——归属错位）；**内嵌 .spv 等非源码资源（pick.spv/字库/spv 等）也要全部迁源码**（.vert/.frag 等），统一走编辑器导入链形态（SpvShader 先例 2026-11-01 已定方向）——内嵌二进制与「源码进库、导入链编译」路线相悖；
- equirect→cubemap 转换工具、per-camera 天空盒、天空盒 mip 链、程序化天空（大气散射，远期）；
- **天空盒环境光照（作 ambient 源：SH9 球谐/mipmap 降采样）——归四期第 4 项**（Roadmap §4，2026-10-31 立项——本档纯视觉拍板的对应升级位；DDGI 探针落地后并入四期第 5 项）。

## 9. 文档衔接

| 概念 | 出处 | 关系 |
|------|------|------|
| Pass 编排/插入位置 | `Engine_RenderPipeline_Design.md` §3 | SkyboxPass 3.5 位（v0.4 已插） |
| ClearFlags | `Engine_Scene_Design.md` 相机段 / Features §2.3 | SKYBOX 项兑现 |
| 光照/环境项边界 | `Engine_Lighting_Design.md` §10 | 纯视觉拍板单点；ambient 升级归四期第 4 项（Roadmap §4） |
| cubemap/texture array 缺口 | Roadmap §4 四期 GI 段 | 同族 RHI 纹理种类扩展（探针 texture array 随 GI 实装前核实） |

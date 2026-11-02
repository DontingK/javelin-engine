# 引擎天空盒设计（Engine Skybox Design）

> 版本：v0.2
> 日期：2026-11-02
> 说明：三期批次⑤专档——视口天空盒的 cubemap 底座、SkyboxPass、资产与场景接线。**设计拍板完成（v0.2 实装前决策点已全部拍板，见 §1.1）；步骤一（RHI 底座）待实装**。
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
- **视图矩阵 = set0 CameraBlock `view@64B` 去平移**（vs 内 `mat4(mat3(view))`）：批次③ view 扩块复用，零新 push、零新 uniform 通道；
- **SKYBOX 清屏语义**：视口 SKYBOX 时 Opaque 转「颜色 LOAD + 深度 CLEAR」（省一次纯色清屏，SkyboxPass 3.5 位全屏覆盖）；**场景无 skybox 资产时回退 SOLID_COLOR 清屏**（正常域空状态不弹错——红线 15 边界）；skybox 有无经 PipelineContext 透传给 Opaque；
- **实勘确认（缺口清单）**：`TextureType.CUBE/CUBE_ARRAY` 枚举与 viewType 映射已预埋，缺①`VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT` 创建旗标（`VkImageCreateInfo.flags` 现完全未设）②`Device.uploadTexture` staging 初始上传写死 `whole2D` 单层拷贝——CUBE 走单命令六层整拷（`TextureRegion.cubeFaces` layerCount=6，随验首版逐层循环踩 Pitfalls #30 已翻篇；buffer 六面连续紧密打包）；

## 2. 实装分步（三步派发）

| 步骤 | 内容 | 验收口径 |
|------|------|----------|
| 一 | RHI cubemap 底座（CUBE_COMPATIBLE 旗标 + `TextureDesc.textureCube()` 便捷构造 + `TextureRegion.layerCount/cubeFaces` 六层整拷 + `Device.uploadTexture` CUBE 分支）+ CubemapTest | 六面上传+采样读回（§7 第三条）——**✅ 验收关账（2026-11-02 用户实机：读回比对通过）**——首验踩 layerCount 写死 1 坑（Pitfalls #30）修毕 |
| 二 | SkyboxPass + `ClearFlags.SKYBOX` + skybox 透传（RenderingSystem.skybox RHI 纹理引用，测试形态）+ SkyboxDemo | §7 前两条——**✅ 验收关账（2026-11-02 用户实机：SkyboxDemo 五验收点全过）**；随批两修：PSO 深度参数序填反 + depthAttachmentFormat 漏声明（Pitfalls #31）；测试装载链补 TestAssetManager（core 测试环境 SpvShader 现编译）；Scene.skybox 字段/序列化随步骤三资产类一并落 |
| 三 | 资产面（equirect 导入器自动转六面 + .cubemap 资产类型 + 检查器面板 + Scene.skybox 编辑行） | 导入链往返 + 编辑器实机 |

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

## 5. 资产与场景接线

- **场景引用**：`Scene.skybox`（Asset 引用，cubemap 资产类型；序列化随 @guid 通则）；多相机各视口统一读场景级引用（per-camera 天空盒后置）；
- **资产面**：导入链形态随批拍板（六面独立图片 vs 单资产格式 vs equirect 转换工具）——**实装前出方案再动手**（先方案后码）；检查器 = cubemap 资产只读面板 + Scene.skybox 编辑行（AssetInput 复用，多后缀）；
- **编辑器**：项目面板预览/创建命令随资产面方案一并定。

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

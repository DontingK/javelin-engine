# 引擎天空盒设计（Engine Skybox Design）

> 版本：v0.1
> 日期：2026-10-31
> 说明：三期批次⑤专档——视口天空盒的 cubemap 底座、SkyboxPass、资产与场景接线。**设计拍板完成、未实装**（三项拍板同日：cubemap 形态 / 纯视觉范围 / 批次⑤定位）。
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

## 2. RHI cubemap 底座（批次⑤第一步）

- **纹理种类**：`TextureCube`（或 Texture2D 泛 viewType 方案——实装前按 RHI 既有 TextureDesc 形态定，不预定案）；Vulkan 侧 = `VK_IMAGE_VIEW_TYPE_CUBE` + 六层 face array + `VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT`；
- **ImageView/采样**：viewType CUBE、六面 layer range；sampler 复用现有（无特殊参数）；
- **上传**：六面像素数据分 layer 上传（staging 现走 host-visible 直写路径）；
- **通用性红线**（对齐光照批次①口径）：按通用纹理种类面建，禁止只伺候天空盒的特化窄接口——GI 探针 texture array / 反射探针 cubemap 复用此底座；
- **边界**：mip 生成后置（无 mipmap 采样先用 LINEAR/无 mip 过滤；环境项 mipmap 链归 GI 期）。

## 3. SkyboxPass（引擎侧）

- **位置**：管线 Pass 列表 3.5 位——Lighting（或 v0.1 形态的 OpaqueForward）之后、TransparentForward 之前；天空盒画在不透明几何之后 = 深度测试挡掉被遮部分，透明物体画在天空盒之后 = 正确遮挡关系；
- **几何**：单位立方体（36 顶点，复用 box.smesh 生成链或内嵌 vertex buffer——实装定）以相机为中心（**去除相机平移的世界矩阵**，视口视锥剔除免做——立方体恒包住视锥）；
- **深度语义**：depth **LOAD + ZWrite Off + ZTest LEqual**、深度上限技巧（`gl_Position.z = w` 推到远平面）二选一——实装定，目标=不污染深度缓冲；
- **着色**：方向向量采样 cubemap（vs 传出世界方向插值）；引擎内嵌 spv（javelin-tool 链路，DebugText/pick 先例——core 无 GLSL）或 builtin-asset shader——**若走材质面则受光照注入影响**（天空盒不应被光照注入加亮），倾向内嵌直绘，实装拍板；
- **ClearFlags 兑现**：`ClearFlags.SKYBOX` 枚举启用（Features 长挂的空槽）——该视口相机清屏时 SkyboxPass 生效；SOLID_COLOR/DEPTH_ONLY/DONT_CLEAR 语义不变；
- **无天空盒引用时**：SKYBOX 标志视同 SOLID_COLOR 回退（正常域空状态，不弹错——红线 15 边界）。

## 4. 资产与场景接线

- **场景引用**：`Scene.skybox`（Asset 引用，cubemap 资产类型；序列化随 @guid 通则）；多相机各视口统一读场景级引用（per-camera 天空盒后置）；
- **资产面**：导入链形态随批拍板（六面独立图片 vs 单资产格式 vs equirect 转换工具）——**实装前出方案再动手**（先方案后码）；检查器 = cubemap 资产只读面板 + Scene.skybox 编辑行（AssetInput 复用，多后缀）；
- **编辑器**：项目面板预览/创建命令随资产面方案一并定。

## 5. 与管线的咬合

- Pass 列表/插入：管线档 §3.2 已补 3.5 行（v0.4）；`PipelineContext.skybox`（或现目标直写）随批扩；
- MSAA：SkyboxPass 与 MSAA 分支正交（随现目标采样数走）；
- 多视口：每视口独立判定（相机 ClearFlags）+ 同一天空盒资产；GAME/SCENE 视口共享场景引用；
- 后处理链：天空盒写 lightingRT（HDR 场景色的一部分），链式直通无特例。

## 6. 验收口径

- 场景挂天空盒：旋转相机各方向背景正确、不透明物体正确遮挡天空盒、透明物体画在天空盒之上；
- 无天空盒 + SKYBOX 标志：回退纯色清屏不报错；
- cubemap 底座 Demo（RHI 面独立验收：六面上传+采样读回）。

## 7. 后置项

- equirect→cubemap 转换工具、per-camera 天空盒、天空盒 mip 链、程序化天空（大气散射，远期）；
- **天空盒环境光照（作 ambient 源：SH9 球谐/mipmap 降采样）——归四期第 4 项**（Roadmap §4，2026-10-31 立项——本档纯视觉拍板的对应升级位；DDGI 探针落地后并入四期第 5 项）。

## 8. 文档衔接

| 概念 | 出处 | 关系 |
|------|------|------|
| Pass 编排/插入位置 | `Engine_RenderPipeline_Design.md` §3 | SkyboxPass 3.5 位（v0.4 已插） |
| ClearFlags | `Engine_Scene_Design.md` 相机段 / Features §2.3 | SKYBOX 项兑现 |
| 光照/环境项边界 | `Engine_Lighting_Design.md` §10 | 纯视觉拍板单点；ambient 升级归四期第 4 项（Roadmap §4） |
| cubemap/texture array 缺口 | Roadmap §4 四期 GI 段 | 同族 RHI 纹理种类扩展（探针 texture array 随 GI 实装前核实） |

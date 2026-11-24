# 引擎阶段路线（Engine Roadmap）

> 版本：v0.35（2026-11-25）
> v0.35 变更（2026-11-25）：**L4 第三笔 REND-21-C2 接触阴影落码**（补齐 C 的另一半）——勘察推翻旧前置：不需要给相机深度附件加 `SAMPLED`（SSAO 早已用 `cam.viewProj` 反投影世界位置取证），故本批**零 RHI 改动、零新描述符槽**：`ShadowSettings` 新增 `contactShadows`（0~1 强度，缺省 0.5，0 = 关），经 `LightingParamsBlock` 尾部新增 `vec4 contactParams`（@656，块实需 656→672，`LIGHTING_STRIDE` 768 不变）；注入段新增 `javelinContactShadow`（世界位置沿主光方向 10 步、tMax 0.35，投影后采 G-Buffer 世界位置比较相机空间 z）+ `javelinLightingEx(..., contactShadow)` 重载（只乘主光直射项），**只在全屏光照 Pass 调用**——Forward 路走三参版本、系数恒 1，故本项天然 HYBRID 限定。`Engine_Shadow_Contact_Design.md` v0.2。**未编译**（`lighting.frag` 需重导）。
> 版本：v0.34（2026-11-25）
> v0.34 变更（2026-11-25）：**REND-13 光照 gizmo 落码**（路线 §4.1 贯穿项「L4 开工前先备可视化」，接在 DBG-03 之后）——编辑器侧新增 `LightGizmoAppState`（全场景灯、不走选中门控）：方向光沿节点 +Z 画行进方向箭头、点光画 range 三轴大圆、聚光画外角四母线 + 末端圆，选中灯白线叠画，`DebugText` 第 13 行报灯数；入口 = 菜单「工具 \| 光照 gizmo」两项 + F7 边沿循环；线框仍由 `DebugLineAppState` 每帧 clear 后统一代提交（本状态保持唯一 clear 执行者）。纯编辑器 DebugDrawLine 路径，**不动着色器与 RHI**。说明档 `Engine_DebugTools_Plan.md` v0.5 §3.7。**未编译**。
> 版本：v0.33（2026-11-25）
> v0.33 变更（2026-11-25）：**L4 第二笔 REND-21-C 的 C1（PCSS）落码**——按方案 §5 五点默认（C 与 B 对调已获认可）：`ShadowSettings` 新增 `softness`（缺省 1.0，0 = 关），经 `shadowParams2.w` 进 UBO（`LIGHTING_STRIDE` 768 不变）；set1 新增 **binding11 `shadowMapRaw`**（同一张 atlas、NEAREST 非比较采样器——阻挡搜索要真实深度，比较采样器只给 0/1）；注入段新增 `javelinPcssBlockerDepth`（黄金角螺旋 16/24 tap）+ `javelinCascadeShadow` 内把 PCF 核半径换成由遮挡距离推出的半影（钳制 `[pcfRadius, ×4]`），关档逐像素回退旧路径。C2 接触阴影挂 `Engine_Backlog.md`（需深度附件加 `SAMPLED`）。方案件 `Engine_Shadow_PCSS_Design.md` v0.2。**未编译**（注入段改动 = 全部 `.shader` 与内置 `lighting.frag` 强制重导）。
> 版本：v0.32（2026-11-24）
> v0.32 变更（2026-11-24）：**L4 首笔 REND-21-A（cutout/alpha-test 阴影）落码**——按方案 §4 五点默认执行：①语义载体 = 只加 shader 属性 `_AlphaCutoff`（`Range(0,1)`，缺省 0 = 关，不动 `SurfaceType` 枚举）；②阴影侧以 push 128→144B 传 cutoff / hasAlbedoTex（`Device.maxPushConstantsSize()` 新增只读口，超限构造期抛错）；③贴图槽固定 `_MainTex`；④主渲染与阴影同批开（`ShaderInjector` 新增 fs 注入助手 `javelinAlphaClip`，内置 `Lit.shader` Forward/GBuffer 两条 fs 各接一行）；⑤作为 L4 首笔。`ShadowPass` 首次获得专属 set0（binding0 = 材质 `_MainTex`，每材质描述符集按贴图版本重建、旧集两帧退役），`shadow.vert` 补 `aUV`→`vUV`，`shadow.frag` 由空体改为按阈值 `discard`。方案件 `Engine_Shadow_Cutout_Design.md` v0.2（§9 落码记录）。**未编译**：内置 `Lit.shader` / `shadow.vert/.frag` 需 reimport 出 SPIR-V；`DdgiVoxelizer` 不吃 alpha 记为后置。
> 版本：v0.31（2026-11-24）
> v0.31 变更（2026-11-24）：**L3 探针可视化落码（DBG-07 / dbg-3）**——按用户「先做可视化、验收靠后」落三视图：探针网格盒 + 三轴十字（`DebugDrawLine` 由 `DebugLineAppState` 统一代提交）、atlas 直显覆盖 Pass（`DdgiProbeViewPass`，`texelFetch` 1:1 + 未扫 tile 红框 + 溢出洋红，零 CPU 回读）、`DebugText` 第 11 行数值行；入口菜单「工具 | DDGI 探针视图」四项 + F5；core 仅加只读 `DdgiProbePass.grid()/atlases()` 与 `JavelinEngine.ddgiProbePass()`。方案件 `Engine_DDGI_ProbeViz_Design.md` v0.2；未编译，`ddgiView.frag` 待重导出 SPIR-V。**下一步 = DBG-03 shadowMap 可视化（L4 开工前的调参眼）→ 再进 L4 REND-21-A cutout 阴影**。
> 版本：v0.30（2026-11-21）
> v0.30 变更（2026-11-21）：**L2 第一笔关账**——RHI-15 + RHI-08 格式能力查询**用户实机验收通过**（`FormatCapabilityTest` 设备 AMD 780M：8 格式能力表 + 7 条必支持断言全过、负例创建期抛错；快照 `R32_UINT` color=true/blend=false/linear=false、`D32F` depth=true/storage=false）。`RHI_Texture_Types_Design.md` → v0.5 §7 验收记录；Backlog 两行移出；**L2 剩 RHI-14（按 DDGI 单张 atlas 选型后置）**。主线转入 **L3 DDGI 首版**：§10 四点按推荐默认拍板（单张 2D atlas / 纯相机滚动体积 / 体素 DDA / SSAO 0.25+0.45），**B1 数据面同日落码**（`DdgiSettings` + `DdgiProbeGrid` + 单测 `DdgiProbeGridTest` + `DdgiAtlases`（两张 atlas GPU 资源，验证件 `DdgiAtlasTest`）；档 v0.2 §11），残留 = 两支 compute（追踪/更新）与消费面；**B1 残留②体素化重建同批落码**（`DdgiVoxelField` 128³ `RGBA8`（基色 + 占用）+ `DdgiVoxelizer`（不透明 + `castShadow` 集合、静态集签名两帧稳定门控）+ 改写版 `voxelize.compute`）。
> v0.29 变更（2026-11-21）：**L2 硬前置第一笔（RHI-15 + RHI-08 格式能力查询）落码，待编译验收**——`FormatSupport`（`vkGetPhysicalDeviceFormatProperties` 能力快照 + `supports(TextureUsage)`）+ `Device.formatSupport(Format)`（按格式缓存）+ 纹理创建期 usage 校验 + PSO 附件校验（颜色/混合/深度），缺项 `IllegalStateException` 带设备名；验证件 `FormatCapabilityTest`（能力表 + 7 条必支持断言 + 负例）。口径 `RHI_Texture_Types_Design.md` v0.4 §7。**RHI-14（mip 链/逐层屏障/createView）未开工**——按 DDGI 选型「单张 2D atlas」可后置，待用户拍板后定序。
> v0.28 变更（2026-11-21）：**L1 在飞账全清**——第三笔 **BUG-12 SSAO 噪点**同日收口（用户「看起来比之前好，先这样」）：模糊改可分离两遍（各 9 tap、支撑 9×9）+ `sigmaZ`/AO 偏置自适应 + AO RT 升 `R16_SFLOAT`，口径 `Engine_SSAO_Design.md` v0.6 §10，未做项转 `Engine_Backlog.md` REND-22。**L1 只剩提交动作**；主线进入 **L2 硬前置**（RHI-14 纹理子资源面 / RHI-15 格式能力查询），之后 L3 DDGI。
> v0.27 变更（2026-11-21）：**L1 在飞账两笔清完**——①**BUG-11 阴影接触漏光修复用户实机验收通过**（重导 `.shader` 与内置 `lighting.frag` + 项目设置「阴影」组恢复默认后「没问题了」，口径 `Engine_Shadow_Quality_Design.md` v0.7 §9.6；半影残留 1~2 纹素归 L4 PCSS）→ `Engine_Backlog.md` 已移出、`Engine_Features.md` §2.4 行翻 ✅；②**体素 AO（P4-17）退役批编译 + 实机确认关账**（无残留引用、环境项回到 `ambient.rgb * ambient.a`）→ `Engine_VoxelAO_Design.md` 归档档补关账行。§4.1 L1 行同步为「验收完成、余提交」。**当前主线下一项 = L2 硬前置（RHI-14 纹理子资源面 / RHI-15 格式能力查询）**，之后 L3 DDGI。
> v0.26 变更（2026-11-18）：**⑥ 前置 RHI 纹理底座 + 跨帧布局记忆 RHI-13 用户实机验收通过**——`TextureTypesTest` 3D 16³ 逐体素 + 2D 数组逐字节两条读回全一致、3D 采样画面 XOR 图案；编辑器常规画面与 TAA 零视觉差异、`[VVL]` 零 ERROR（无 `oldLayout` 不匹配）；`ComputeSsboTest`/`CubemapTest` 回归正常；补 `TextureDescShapeTest`。**随验收修测试件取样点**（z=0.5 撞 z=7/z=8 互补层被三线性平均成恒 0.5 → 改第 7 层体素中心）。同步：§4 施工序 ⑥ 前置已备、§5 四期行；同日 BUG-10 检查器颜色行复测通过（`Editor_Swing_Design.md` v0.5）。
> v0.25 变更（2026-11-16）：**四期施工序 ④（P4-04 步骤一 常量环境光）与 ⑤（P4-08 非光照材质语义）用户实机验收通过**——用户原话「环境光没问题」「非光照材质 看起来也没问题」。④ = `Environment.ambientMode`（`OFF` / `CONSTANT`）+ `AmbientSetting`（颜色 + 强度 0~8）→ `SceneRenderBridge` 每帧推送 → `LightingParamsBlock.ambient`；`OFF`/无环境组件 = 纯黑（与三期基线逐像素等价）；步骤二 SH9 仍挂后置候选。⑤ = `.shader` 顶层 `LightingModel`（单一封闭轴 `Lambert` 缺省 / `Unlit`）+ `Shader.lightingModel` + `RenderList.unlitOpaque` + 新 `UnlitForwardPass`（HYBRID `…→Skybox→Unlit→Transparent`）+ 注入器按模型取块集 + `default.shader` 改 Unlit；`Lit` 资产行为不变。§4 施工序与 §5 四期行同步：④⑤ 关账 → 下一项 ⑥（P4-05 DDGI / ⑥ 前置轻档 P4-17 体素 AO），前置 RHI 纹理底座仍待验收。
> v0.24 变更（2026-11-16）：**RHI 纹理底座阶段三落码——跨帧布局记忆 RHI-13（⑥ 前置硬项）**。形态 = 布局状态随资源持有：`VulkanTexture.layout` 跨帧沿用、`VulkanCommandBuffer.pipelineBarrier` 以真实布局为准并回写、`RenderGraphImpl.execute` 删 `LayoutTracker`（交换链图像改用本帧局部记账，坑 #37 口径不变）、`LayoutTracker.java` 删除。跨帧持久纹理（TAA 历史 / 将来的探针 atlas、体素网格）不再每帧声明 `UNDEFINED`（不再有「内容可丢弃」UB）；上传终态布局同被记住。**未编译、验收推迟**（用户 2026-11-16）；验收专项见 `RHI_Texture_Types_Design.md` v0.2 §5（编辑器/TAA 零视觉差异 + `[VVL]` 无 oldLayout 不匹配）。
> v0.23 变更（2026-11-16）：**RHI 纹理种类底座（⑥/⑦ 前置批之一）阶段一/二落码**——新档 `RHI_Texture_Types_Design.md` v0.1：`TextureType.TYPE_2D_ARRAY` + `TextureDesc.validateShape`（3D ⇒ arrayLayers=1、CUBE 六面正方形、CUBE_ARRAY 6 的倍数）+ `texture3D`/`texture2DArray` 便捷构造 + `VulkanTexture` 2D 数组 image/view 映射 + `TextureRegion.whole3D`/`whole2DArray` + `VulkanDevice.uploadRegionOf` 按种类取上传区域（原 3D/数组只拷 1 层）+ `DescriptorUpdate.storageTexture`（compute 写纹理唯一入口）；验证件 `TextureTypesTest`（compute `image3D` 写 + `sampler3D` 采样 + `copyImageToBuffer` 逐体素比对；2D 数组上传逐字节比对）。**八条现状实证**含 `LayoutTracker` 每帧重置 → 跨帧纹理首用声明 UNDEFINED（内容可被丢弃＝UB，登记 RHI-13，为 ⑥ 硬项）。**未编译、验收推迟（用户 2026-11-16 明确）**；待拍板 = 自定义视图 / 跨帧布局记忆 / 格式能力查询 / AS·BDA 独立批。§4 施工序与 ⑥ 状态同步。
> v0.22 变更（2026-11-16）：**四期施工序 ⑤（P4-08 非光照材质语义）落码**——用户四拍板点「都认可」后按方案件实装（`Engine_Material_LightingModel_Design.md` 升 v0.3）：`LightingModel` 枚举（**单一封闭轴**：`Lambert` 缺省 / `Unlit`，旧资产零迁移；两轮用户追问后把 `Lit` 纠偏为 `Lambert`、并否决「公式 × 受光」正交两轴）+ `Shader.lightingModel` + `RenderList.unlitOpaque` 桶 + 新 `UnlitForwardPass`（HYBRID 序 `…Skybox→Unlit→Transparent`、FORWARD 同相对位置）+ 注入器改 `lightingStageBlocks(model)` switch（UNLIT 跳 `LIGHTING_BLOCKS`/`RECEIVE_SHADOW_PUSH`，保留相机块与材质声明；无 default，新增值漏处理即编译失败）+ `default.shader` 标 Unlit（错误紫无灯可见）+ 双语键与两件单测；**边界静态核验成立**（`taa.frag` 无效世界位置直出当前帧不累积 → 无拖影、但无 AA；`ssao.frag` unlit 像素 AO 恒 1、邻居采样跳过）。**未编译验证，待用户验收**；余项（`.shader` 诊断台只读行 / Unlit 示例资产）登记后置。§4-4 与 §5 状态同步。
> v0.21 变更（2026-11-16）：**四期施工序 ⑤（P4-08 非光照材质语义）方案件落档**——新档 `Engine_Material_LightingModel_Design.md` v0.1（待拍板）：`.shader` 顶层加一等语义头 `LightingModel`（`Lit` / `Unlit`，缺省 Lit、旧资产零迁移），shader 级粒度（材质级变体后置）；UNLIT 期跳过 `LIGHTING_BLOCKS`/`RECEIVE_SHADOW_PUSH` 注入、不写 G-Buffer、进 `RenderList.unlitOpaque` 新桶、由新 `UnlitForwardPass` 覆盖制单画（HYBRID 序 Lighting→Skybox→**Unlit**→Transparent，深度 LOAD + ZWrite On）；`default.shader` 改标 Unlit（错误紫材质无灯可见）。原「Pass 名兼职」与「SurfaceType 扩 UNLIT」两案经用户 2026-11-16 讨论后否决（光照模型应与面型正交）。**未落码**，四拍板点见档 §10。
> v0.20 变更（2026-11-16）：**四期施工序 ④ 步骤一（常量环境光）落码**——用户拍板「先出枚举：OFF（关）/ 直接环境光（颜色 + 强度），其他方案后置」。`Environment` 新增 `ambientMode` + `AmbientSetting`（`@InsFieldGroup` 条件展开、两字段滑条/色板）→ 桥层每帧推送 → `LightingParamsBlock.ambient`；`OFF`/无环境组件 = 纯黑（三期基线）。SH9 天空盒环境光仍为 ④ 步骤二（方案对比已归档：SH9 / irradiance mip / PBR IBL 三件套 / 常量；Unity 与 Babylon 漫反射档同为 L2 SH）。**未编译验证，待用户验收**。§4-4 状态同步。
> v0.19 变更（2026-11-15）：**四期施工序 ③ TAA 关账（含步骤二锐化 P4-11）**——`Engine_TAA_Design.md` 升至 v0.7：步骤一（抖动/重投影/历史 ping-pong/失效触发，经三轮实机排障：描述符池耗尽、整屏跳动、累积糊）与步骤二锐化（RCAS 字面移植 Babylon `fsr1Sharpen`、Tonemap 降级为 order 40、锐化终节点）**同日用户实机验收**；**载体变更**：抗锯齿开关与四项参数（锐化强度/历史锐化/防闪烁/速度抑制）从项目设置搬上 `Camera`（`@InsFieldGroup` 条件子组选 TAA 才展开、组头可折叠、四项滑条、per-camera 后处理），项目设置「TAA」「锐化」两组删除。§4-3 与 §5 四期行状态同步为「③ TAA 关账、下一项 ④ 天空盒环境光照」。
> v0.18 变更（2026-11-15）：**四期施工序 ③ TAA 方案件**——新档 `Engine_TAA_Design.md` v0.1（待拍板）：仅 HYBRID 档、链序 SSAO 之后（order 15）、Halton(2,3) 8 相 1px 抖动（注入 set0 UBO1 写入点，阴影/簇/灯收集用未抖动矩阵）、G-Buffer 世界位置 + 上帧 jitter 后 viewProj 重投影、RGBA16F per-view 历史 ping-pong、3×3 min/max 邻域钳制、两 Pass 形态（resolve 写历史 → copy 写链输出）、自管参数 UBO（set0/set1 布局零改动）、切场景/resize/相机跳变失效；10 条拍板点见档 §6。**未落码**，§4-3 与 §5 四期行状态同步为「③ TAA 方案已出、待拍板」。
> v0.17 变更（2026-11-15）：**Layer/Tag 体系重构 + 设置窗扩五页（非四期施工序，四期主线未推进）**——`Layer`/`Tag` 注册表单点 `LayerAndTagManger`（引擎构造期投影、设置窗改动即时刷新），Tag 只增删不改名、`Untagged` 不可删、删标签同时从场景节点摘引用；项目设置窗加「图层 / 标签 / 场景」三页（前两页即时生效、场景页走新 `@SettingAsset` 资产选择器），检查器两份静态缓存（`LAYER_NAMES`/`TAG_OPTIONS`）删除；说明行换行从 HTML 手算宽度改 `JTextArea` 原生软换行、行 label 顶对齐。详见 `Engine_Roadmap_Progress.md` §7。
> v0.16 变更（2026-11-15）：**设置域重构批次（非四期施工序，四期主线未推进）**——① `InputCapsule` 缺键语义改为「读法自带默认值」（标量/字符串/枚举无参版删除，默认值 = 字段声明初值）；② 设置对象分组化（新 `WindowSetting`、`GraphSetting` 成为「图形」页根、SSAO 独立成组、旧档根键一次性迁移）；③ `RenderSettings` 静态门面**退役**（下发收进 `JavelinEngine.applyRenderSettings()`，读写走设置对象）；④ **注解引擎接管设置窗**（`@Setting`/`@SettingGroup`/`@InsField`/`@SettingHint`/`@SettingSelect`/`@SettingRestart` 声明面，双页「图形｜窗口」，窗口页只写不读）。落点：`Editor_ProjectSettings_Design.md` v0.2 / `Engine_Rendering_Design.md` v0.4 §11 / `Engine_Features.md` 两行；**§4/§5 无施工序变化**（四期仍在施工序 ③ TAA 待开工）；同日用户实机验收通过。
> v0.15 变更（2026-11-14）：**四期施工序 ② 阴影质量分级全件关账（步骤一 + 步骤二 CSM）**——CSM（lambda 切分 + per-view 资源族 + 单张 2×2 atlas + 纹素吸附）当日落码并用户实机验收通过；§4-3 与 §5 映射表四期行同步为「② 关账、余步骤三可选收尾，下一项 ③ TAA」。
> v0.14 变更（2026-11-14）：**四期施工序 ② 阴影质量分级步骤一全件关账（含一-C per-object）**——`castShadow`/`receiveShadow` 检查器暴露 + `receiveShadow` 双档承载当日落码并用户实机验收通过（随验两修：坑 #39 `RenderObject` 换代缓存致运行期切换不生效、#40 阴影盒只收 caster 致影子被裁）；§4-3 与 §5 映射表四期行同步为「步骤一全件已验收，余 步骤二 CSM」。
> v0.13 变更（2026-11-14）：**四期施工序 ② 阴影质量分级步骤一验收关账**——品质档 PCF（4/9 tap）+ bias 调参面 + 设置窗口当日落码并用户实机验收通过（随验修：注入段标量/向量乘错）；§4-3 与 §5 映射表四期行同步为「步骤一已验收，余 一-C per-object + 步骤二 CSM」。
> v0.12 变更（2026-11-14）：**四期施工序 ② 阴影质量分级步骤一落码（待用户实测）**——品质档 PCF（4/9 tap）+ bias 调参面 + per-object 未做；§4-3 与 §5 映射表四期行同步为「方案已出、步骤一落码待验收」。
> v0.11 变更（2026-11-14）：**四期施工序 ② 阴影质量分级出方案件**——新档 `Engine_Shadow_Quality_Design.md` v0.1（待拍板）：步骤一 PCF 4/9 tap 品质档 + bias 调参面 + per-object `castShadow`/`receiveShadow` 落地（零新底座）、步骤二 CSM（lambda 切分 + 单张 2×2 atlas + per-view 阴影资源族 + 纹素吸附）、步骤三可选收尾；§4-3 状态同步。
> v0.10 变更（2026-11-14）：**四期施工序 ① SSAO 验收关账**——落码（步骤一 + 步骤二）当日用户实机验收通过；§4-2 与 §5 映射表四期行同步为「后处理链 + SSAO 已验收，下一项 ② P4-03 阴影质量分级」。
> v0.9 变更（2026-11-14）：**四期施工序 ① SSAO 出方案件**——`Engine_SSAO_Design.md` v0.1（待拍板）：不读深度改读 G-Buffer 世界位置+法线（零 RHI 改动、仅 HYBRID）、`SsaoPass` 三 Pass + 半分辨率 AO + `PostProcessNode.order()` 排序声明；§4-2 状态同步。
> v0.8 变更（2026-11-14）：**四期优先级拍板**——**四期为当前最高优先级主线**（跨期散件让位），§4 补施工序 ①~⑦（SSAO → 阴影质量分级 → TAA → 天空盒环境光 → 非光照材质语义 → 探针 GI → 光追），逐项标注施工序；§5 映射表四期行同步。
> v0.7 变更（2026-11-14）：**四期第 1 项后处理链验收关账**——步骤一/二 + 参数可配（常量→push）+ 编辑器「项目设置」窗口 2026-11-14 用户实机验收通过；§4 状态与 §5 映射表四期行同步为「🔄 第 1 项已验收、余项未开工」。
> v0.6 变更（2026-11-11）：§6 跨期遗留收敛至新建 `Engine_Backlog.md`（「已计划、尚未完成」的唯一登记处），本节留指针；「插件域待拍板」更正为 2 项。
> v0.5 变更（2026-11-10）：**三期批次④-B 验收关账**——HYBRID 档用户实机两路光照一致（随验坑 Pitfalls #35 set0 阶段可见性读零已修）；三期五批全部关账。§3/§5 状态同步。
> v0.4 变更（2026-11-08）：**三期批次④-B（Deferred 复用）落地**——GBufferPass+LightingPass+`PipelineProfile` 双档可切（同场景两路光照一致待用户验收）；§3/§5 三期状态同步为「收官（除 ④-B 待验外无剩余项）」。
> 说明：阶段路线**总纲**——各期目标、拍板、验收标准与**状态摘要**；一期关账总结、二期引擎功能、三期光照、四期高质量渲染立项。各期细节以对应专档为准；**批次索引**（每个批次一行：日期｜改动要点｜落点设计档｜验收）2026-11-08 拆分入分卷（见下方索引）。
> 拍板沿革：三期路线 2026-09-25 拍板（一期渲染基础完 → 二期引擎功能 → 三期光照，阴影随光照；Deferred/后处理列三期扩展）；**2026-10-31 拍板：高质量光照 / 光追 / 天空盒环境光 / 实时 GI 等后期项归四期**（三期收口为「有光照」= Forward+ 成形 + 单张硬影 + 视觉天空盒，质量升级整体下期）。

## 分卷索引（拆分后，2026-11-08）

| 文档 | 内容 |
|------|------|
| `Engine_Roadmap.md`（本文，总纲） | 各期目标 / 拍板 / 验收标准 / 状态摘要、§5 六阶段映射表、§6 跨期遗留 |
| `Engine_Roadmap_Progress.md` | **批次索引**——§2 二期（§2.3 Ray / §2.4 编辑器基本）与 §3 三期散件逐条一行、§4 排障基建散件（`Engine_DebugTools_Plan` 批次）；§5 批挂靠归档（每批落哪期哪个阶段）+ §6 已收口专项归档 |

---

## 1. 一期：渲染基础——✅ 完成（2026-09-25 关账）

### 1.1 交付清单

| 模块 | 内容 | 验收 |
|------|------|------|
| 引擎接线 RHI（实现计划阶段①） | JavelinEngine 生命周期（窗口 → Instance/Device/FrameLoop → 渲染系统）、shutdown 逆序、vsync→呈现模式 | `JavelinEngine.main` 清屏出画面 |
| 场景系统（阶段②） | Node/Component/Transform/GameScene + 生命周期 + Layer/Tag + 扁平组件注册表 | GameSceneTest 用例全绿（用户运行） |
| 渲染系统 v0.1 Forward（阶段③） | RenderingSystem 注册表/每帧收集/UBO1 帧环形收编、多相机 RenderView、透明排序、每视口深度纹理 | MultiCameraDemo 验收（分屏/透明排序/resize），**RHI 阶段⑥正式关账** |
| Pass 编排重构 | PipelineHook 机制移除（零调用方），管线 = 有序 `RenderPass` 列表（`addToGraph`），`passes()` 活列表扩展 | 行为对等，structureHash 不变 |
| 场景渲染接入 | `SceneRenderBridge` pull 翻译层（阶段②遗留关闭）：Camera→RenderCamera、MeshRenderer→RenderObject、多相机横向均分视口；JavelinEngine.setScene + scene.update 驱动 | SceneRenderDemo（双相机分屏 + Spinner 旋转 + 透明混合）用户运行正常 |
| 材质桥（材质档 §4 全项） | set 2 布局按 Shader 缓存 / `MaterialBlock` std140 排布器 / `materialSet` 懒创建（运行期改值即时生效）/ `pipelineFor(Material, layout)`（PassState→PSO）/ `Shader.forwardPass()` / 透明分桶 = Blend 态（**已演进：改 Material.surfaceType 材质裁决 + Pass 名分桶严格制（passes map，forwardPass 废除），2026-10-17 方案 B，材质档 §4.1/§4.2**） | MaterialBlockTest（CPU 单测）+ 两 Demo 材质路径迁移；旧内置程序路径删除 |

### 1.2 一期关键拍板（决策存档）

- **着色器源码不进 core**：GLSL 放启动/应用层资源，SPIR-V 由启动层编译（shaderc 仅 testImplementation）；
- **渲染衔接走 pull**：每帧从 `Scene.getCameras/getRenderables` 翻译进 RenderingSystem（推翻 push 预登记）；
- **管线 = 有序 Pass 列表**：`PipelineHook` 枚举移除（曾致 POST_LIGHTING 漏触发），扩展 = 列表插入（RHI 14.3 方式 1/2）；
- **Transform 是 Component 子类**、**无 createNode**（setParent 挂到已入场景父节点自动入场景）、摘除即出场景；
- **视锥剔除/Aabb 后置**（优化阶段回加，届时 FrustumIntersection.testAab 用 6×float 重载）；
- **用户侧零 GPU 暴露 + 冻结语义**：Mesh/Texture2D 纯 CPU 容器，上传逻辑全在桥接（MeshUpload/TextureUpload），上传后资产不可变；
- **资产/序列化类字段禁 final**（AGENTS 红线 9），只读靠 API 面。

### 1.3 一期结束时的能力边界

- **能**：场景里摆 Node（MeshRenderer+Transform）即渲染；多相机分屏/叠加；材质颜色/贴图/透明混合；resize 自适应；材质参数运行期修改即时生效。
- **不能**：灯光/阴影/后处理（三期）；资产文件加载（.shader/.mat 靠程序化构建，二期补格式）；输入/碰撞/Ray（二期）；视锥剔除（优化项）。

---

## 2. 二期：引擎功能

> 目标：让游戏/编辑器"可交互"。依赖顺序：**①输入 → ②碰撞检查 → ③Ray → ④编辑器基本**（Ray 与碰撞是编辑器拾取的前置）。

### 2.1 输入子系统——✅ 已验收关账（2026-09-27 用户验收通过）

- **目标**：键鼠状态采集（GLFW 事件源）+ Action 映射；消费方演示 = 输入驱动相机移动；
- **设计档**：`Engine_Input_Design.md`（v0.1，含拍板记录：KeyCode 纯语义枚举零平台关联（键码映射归 Window 实现私有）/ Input 静态门面对齐 Time / 动作层三次收束——ActionMap 概念移除、bind 与查询合流 Input（唯一使用方式）/ ESC 关窗便利逻辑移除）；
- **架构**：Window.attachInputSink 事件出口（平台键码经 Window 侧翻译成引擎枚举投递）→ InputSubsystem（双缓冲帧边沿，earlyUpdate 发布本帧快照 + 动作绑定全局单表）→ Input 静态门面（原始查询 + 动作 bind/按名查询）；失焦清态防卡键；
- **验收**：**已通过（2026-09-27）**——InputCameraDemo（WASD 移动/右键拖拽转视角/SPACE 边沿/失焦不卡键）+ RawInputDemo（原始层对照）；性能基线存档：IMMEDIATE 下空载 ~10180 fps、avg 0.1ms/帧，引擎每帧固定成本地板过关；
- **对应实现计划**：阶段⑤主体。

### 2.2 碰撞检查

- **目标**：场景对象的相交查询（游戏逻辑 + 编辑器拾取共用）；
- **前置**：**Aabb 回归**——视锥剔除后置时随 Mesh.bounds 一起删除，碰撞与 Ray 都需包围盒回来（Mesh 补"从 positions 算 Aabb"，设计归碰撞档 §5）——**✅ 已随碰撞片③回归（2026-10-05）**；`Engine_Collision_Design.md`（v0.1：**L0 纯数学 + Collider 组件族已落地、片①②③④全片单测验收关账（2026-10-06）**——Ray/形状/Aabb + 缩放=射线逆变换吸收；**已拍板：碰撞体=组件（负责形状）、刚体=独立组件后置**；场景级查询 API 面归 L1 Ray 期定）；
- **产出**：碰撞体组件 + 窄相查询 API（盒/球/射线公共部分）；
- **验收**：~~两物体相交/分离查询正确的最小 Demo~~——**✅ 单测全绿已验收（2026-10-06）**（L0 解析解对照 + Collider 管线缩放往返），场景级拾取 Demo 归 §2.3 Ray 一并验收；
- **待定拍板**：只做窄相查询（自研，推荐——拾取与逻辑查询够用）还是连带阶段⑥物理库选型（刚体动力学）一起定。

### 2.3 Ray

- **目标**：屏幕点 → 世界射线（逆 viewProj）→ 射 vs 碰撞体 → RaycastHit；
- **前置**：②的碰撞体与包围盒；
- **产出**：Ray/RaycastHit 数据类型 + Camera 屏幕射线生成 + 场景级 raycast 查询（**Ray 已随碰撞 L0 落地**（2026-10-05 片①）；**Camera.screenToRay 已进核心并单测验收**——2026-10-05 随 Gizmo 轴拾取落地、CameraTest 2026-10-06 验收，Unity ScreenPointToRay 口径）；
- **验收**：~~点击拾取场景物体正确（为编辑器拾取闭环）~~——编辑器拾取已改道 GPU ID-buffer 并于 2026-10-23 实测验收（§2.4 拾取闭环收官）；Ray 线定位收窄为**运行时物理查询**，验收 = PhysicsTest 单测（用户运行）。

> **批次索引**（L1 场景级 raycast 一条：要点 / 落点 `Engine_Collision_Design.md` §10 / 验收日期）→ `Engine_Roadmap_Progress.md` §2.3。

### 2.4 编辑器基本——✅ 主体验收收官（2026-10-14；物体拾取 ID-buffer 2026-10-23 用户实测验收——拾取闭环收官）

- **目标**：场景编辑最小闭环——场景视口（飞行相机）+ 层级面板 + 拾取 + Transform 移动/旋转/缩放 + 场景保存/加载；
- **前置**：
  - **.scene 序列化**（现为 P2，编辑器存取必须）；
  - **资产格式层**（原阶段④剩余，编辑器读写的前提）：~~.shader 二进制格式 + 解析器（声明注入 + shaderc + 打包）~~——**✅ 已落地（2026-09-26，提交 e03c057）**：导入管线在编辑器 `importer/shader`（归属拍板：不经 javelin-tool），Capsule 二进制产物 + GUID 文件头规则 + .smesh 产物/拓扑/法线一并补齐；~~.mat 格式 + AssetManager 读写未做~~——**✅ 已落地（2026-10-23 用户实测验收通过）**：.mat = Material 文本序列化（伴生 .meta 口径），MaterialImporter + project.create.material 创建命令，Engine_Material_Design §3.2；
  - **编辑器形态已定：Swing 外壳 + JCEF Web 面板**（~~内嵌 imgui / Nuklear / 独立 Swing 三选一~~ 作废——视口=Vulkan Canvas 挖洞浮于 CEF 之上，浮层遮挡约束由此而来，Editor_Design §1）；
  - `Editor_Design.md`（✅ 已建档 v0.1：JavelinEditor=JavelinEngine 子类、CEF 窗口体系、JS↔Java 桥、资产管线、线程模型）；
- **产出**：编辑器应用骨架 + 上述最小闭环；
- **验收**：搭建场景 → 摆放/移动物体 → 保存 → 重载还原（用户运行）。

**拾取方案拍板（2026-10-06 用户；2026-10-23 统一/落点/共存三增补）**：拾取终态 = **GPU ID-buffer 全线统一**（物体拾取 + Gizmo 轴拾取终态均写 ID 附件、点击读回像素——不依赖用户物体挂 Collider、不给物体隐藏挂网格碰撞体，Unity 编辑器同路线）；编辑器侧射线拾取退役、「过渡期先落 CPU 数学拾取」开放项**关账不落**；落点 = **pass 本体进引擎 core、运行时也可用**（有意分歧 Unity：Unity 拾取为编辑器内部实现、运行时无对应 API，短板不抄）；RHI 前置 = 拾取附件 + 图像读回路径，~~后置实现、非当前排期~~——**已落地并验收（2026-10-23 用户实测，Engine_Picking_Design.md v0.1：RHI 读回环/PickPass 覆盖制/PickSystem 双通道/编辑器消费 + 轴拾取全线迁移，CylinderCollider 过渡线退役）**（详见 Editor_Design §10 / Engine_Picking_Design）。射线检测与 GPU 拾取**共存**：游戏内大多走射线检测（Physics.Raycast 语义），运行时场景级 raycast（Collider 注册表）照常归 §2.3 Ray 期——射线=物理查询（有 Collider 才打得中），ID-buffer=渲染拾取（看得见即拾得到），两 API 并存不冲突。

**状态摘要**（逐批落地与验收流水见分卷）：编辑最小闭环主体 2026-10-14 验收（窗口层 / .scene 序列化 / Gizmo 轴拾取与拖拽）；编辑器骨架全链 2026-10-11 破土 → 打包 JAR/AOT 双形态 2026-10-13 三端验收；GPU ID-buffer 拾取 2026-10-23 验收（物体 + Gizmo 轴统一）；层级 / 项目 / 检查器三面板 Swing 实装与去 Web 化 M1~M6 收口 2026-10-17~18 落地、2026-10-23 验收；资产链（.mat / 贴图三层 / 纹理 / meta 统一 / 热重载 / 坏 shader 兜底）2026-10-19~30 陆续落地与验收；插件体系 + i18n 全量化 2026-10-20/30 落地、2026-10-31 验收；变换 Gizmo 旋转/缩放工具与几何程序化 2026-11-08 验收；检查器组件增删 2026-11-08 验收。

> **批次索引**（§2.4 共 40 条：每批一行「日期｜要点｜落点设计档｜验收」，含检查器注册器 / 视口聚焦两条仅见映射表的批次）→ `Engine_Roadmap_Progress.md` §2.4。

---

## 3. 三期：光照

- **目标**：场景开灯出可见差异——灯光数据通路 + 有光照着色 + 阴影 + 视觉天空盒；**质量升级（后处理/阴影软化级联/环境光照/实时 GI/光追）整体归四期（2026-10-31 拍板）**；
- **拍板（2026-10-28，`Engine_Lighting_Design.md` v0.1 单点；主光裁决 2026-10-31 改向 v0.5）**：**clustered + SSBO（Forward+ 形态）一步到位，跳过 UBO 数组 / 逐盏画光体积中间档**——只面向 PC、质量优先；正透明同读一份 cluster（管线档 4.5 两路光照一致性取舍**消解**）；默认参数 = 16×16 tile × Z32 指数切分、每 cluster 索引上限 64（截尾=去最弱）、光列表容量 4096；主光 = **最强激活方向光自动裁决**（`Scene.getSun()`，对齐 Unity 拍板 2026-10-31——原显式 isMain 方案推翻，同强度取挂入序首个）；shadowMap 挂点 = set1 binding4；
- **内容**（四批，灯光档 §9）：
  1. **RHI 底座前置（新增段）**：~~SSBO 实装（现仅设计稿 API）+ ComputePipeline/dispatch + RenderGraph compute Pass 节点与 Buffer 读写同步——compute 写 SSBO → 图形 Pass 读回最小闭环为验收~~ **✅ 批次①落地并验收关账（2026-10-31 用户实测，细则见 §5 阶段表三期行）**；
  2. **Light 组件 + 收集**：DIRECTIONAL/POINT/SPOT（字段级归灯光档 §4）+ Scene lights 注册表 + 视锥剔除/强度预排序/视空间 64B 打包——**✅ 批次②落地并验收关账（2026-10-31 当日用户实测）**：Light/RenderLight/LightCollector + set1 binding0/1 数据面（LightingParamsBlock UBO 动态偏移 + lightList SSBO）+ ShaderInjector fs 声明注入/保留字生效 + F3 行 8 灯数 + 单测（LightCollectorTest/GameSceneTest 灯用例）；验收 = 单测全绿 + 挂灯实机 F3 灯数显示（着色消费归批次③，本批无画面验收项；细则灯光档 §9 批次②行）；
  3. **cluster 构建 + Forward 消费（= Forward+ 成形）**：ClusterBuildPass（compute，每视口，三步构建）+ 着色器光照段注入 + Lambert + 环境项 + SPOT（后半）；
  4. **ShadowPass + Deferred 复用（2026-11-01 拆分拍板：④-A/④-B 两步派发——Deferred 复用牵材质着色模型/材质专档拍板，混批风险大）**：**④-A ShadowPass 单张硬影 ✅ 已落地并验收关账（2026-11-01 用户实机全项）**——v0.x = 单张硬影（2026-10-31 拍板：单张 2048² 正交包围盒、无 PCF/无 CSM/bias 保守值，质量分级后置灯光档 §10）+ 注入段阴影采样分支（javelinLighting 三参签名扩 worldPos）；实装形态/挂点五拍板（PSO 静态 bias 字段/per-view 幂等挂载/binding4 并入 set1 布局/深度比较采样器（**随验 NEAREST→LINEAR 拍板**：硬件双线性 2×2 免费软化，NEAREST 硬取样在斜投影边缘呈块状锯齿被否）/sunViewProj 扩块 @336——灯光档 §4.5.1 全录）；**④-B GBuffer/LightingPass 同一 cluster 直喂 + 混合/纯 Forward 可配置切换 ✅ 已落地并验收关账（2026-11-10 用户实机：HYBRID 档两路光照一致；随验坑 Pitfalls #35 set0 阶段可见性读零已修）**：GBufferPass（三张 MRT，albedo/世界法线/世界位置+w 有效标志）+ LightingPass（全屏三角形，`javelinLighting` 单源注入=与 Forward 同一函数同一 set1）+ set1 扩 binding5/6/7（G-Buffer 三采样 per-view）+ `PipelineProfile` 双 Pass 列表（`JavelinSetting.renderPipeline` 持久化 + 工具域 `tools.renderPipeline` 运行期切换）+ HYBRID 强制 1x；**不引入 lightingRT/HDR/Composite**（四期第 1 项）；实装明细归管线档 §4.2/§4.3 实装形态段、光照档 §9 批次④-B 行、材质档 §4.2 `Pass GBuffer` 契约；
  5. **天空盒（2026-10-31 建档）**：cubemap 形态（RHI 新纹理种类底座）+ SkyboxPass（管线 3.5 位）+ ClearFlags.SKYBOX 兑现 + 资产/场景接线——**纯视觉不参与光照**（ambient 升级归 GI 探针期）；`Engine_Skybox_Design.md` v0.4 单点——**三步全部验收关账（步骤一/二 2026-11-02、步骤三资产面 3a/3b/3c 2026-11-07 用户实机全链：.hdr 拖入导入/场景根行换绑/视口即时换背景/转动无痕）**；随验六连排障全修（SKYBOX 默认值/清屏拖影/sRGB 量化偏暗/相机位置黑区/采样器 CLAMP_TO_EDGE 棱/转换器纬度归一化角部——坑 Pitfalls #30/#31/#32，档 §1.3~§1.5）；
- **着色器配套口径（2026-10-31 用户拍板，2026-11-01/02 两次演化）**：**内置 default.shader = DefaultMaterial 兜底专用（纯紫=错误显性化产物），禁止改造成光照注入样例**——注入验证样例 = 批次③新增内置 Lit.shader；~~clusterBuild compute 与 SkyboxPass 走引擎内嵌 spv 链路~~（已双重作废：cluster 构建 CPU 化拍板 2026-11-01、GPU 归四期；着色器装载改走 SpvShader 资产导入链——shadow/skybox 内置 .vert/.frag 源码 + SpvShaderImporter，2026-11-01 ④-A 拍板推翻 tool 内嵌 spv）；内嵌 .spv 等非源码资源全迁源码走导入链已拍板（Skybox 档 §8 后置项，后续批执行）；
- **验收**：方向光/点光下物体明暗正确 + 主方向光投影正确（用户运行）；几十盏场景帧率稳（F3 帧时间不劣化超阈值）；混合/纯 Forward 可配置切换且两路光照一致（管线档 §8 步骤二回填点）；
- **后置项全部归四期**（2026-10-31 拍板，见 §4）：后处理链、clustered 后置项（前缀和计算化/脏标记跳重建/Morton/深度重建，灯光档 §10）、阴影质量分级（PCF/CSM/bias 精调，灯光档 §10）、GI、光追、天空盒环境光照。

---

## 4. 四期：高质量渲染（2026-10-31 立项，拍板：后期项整体归此期）

> 三期收官 = 「有光照」（Forward+ 成形 + 单张硬影 + 视觉天空盒）；四期 = 质量升级全谱。开工前逐项拍板方案（先方案后码）。
>
> **优先级拍板（2026-11-14 用户）**：**四期 = 当前最高优先级主线**——跨期散件让位（`Engine_Backlog.md` §1 其余候选在四期之后排，四期未满档前不开其他线）。施工序（依据 = 依赖 / 底座成本 / 画面收益）：
> **① P4-02 SSAO ✅ → ② P4-03 阴影质量分级（PCF 起步）✅ → ③ P4-10 TAA ✅ → ④ P4-04 天空盒环境光照（+ P4-09）步骤一 ✅（2026-11-16 验收）→ ⑤ P4-08 非光照材质语义契约 ✅（2026-11-16 验收）→ ⑥ P4-05 探针 GI（DDGI）→ ⑦ P4-06 光追 rayQuery**。
> ①~⑤ 零新底座（方案现成，逐项出设计档 → 拍板 → 落码）；⑥⑦ 需先补 RHI 底座前置批——**纹理那条 2026-11-18 已验收**（`RHI_Texture_Types_Design.md` v0.3 §5.1：3D 纹理 / 2D 纹理数组 / storage image 写口 / **跨帧布局记忆 RHI-13**，`TextureTypesTest` 读回全一致 + 编辑器/TAA 零差异 + `[VVL]` 零 ERROR），**AS / BDA / `GL_EXT_ray_query` 编译链未开工**。⑥ 前置 = **RHI-14 / RHI-15**（原前置 P4-17 体素化 AO 于 2026-11-20 整体退役，不存在了；DDGI 首版若走体素 DDA 投射线，体素化按「静态集 + 动态 tile」口径在 P4-05 内部重建，见 `Engine_Backlog.md` BUG-13 历史备注与 `Engine_VoxelAO_Design.md`（已归档）。

1. **后处理链**（Bloom/Tonemap + **HDR 切换点**，管线档 4.6——四期第一候选：链式结构已落地，具体化零新底座）——**方案 `Engine_PostProcess_Design.md`；步骤一（HDR 切换点 + Tonemap）+ 步骤二（Bloom，独立开关）2026-11-13 落码、2026-11-14 用户实机验收通过；同日后处理参数接设置（着色器常量 → push 常量）+ 编辑器「项目设置」窗口（`Editor_ProjectSettings_Design.md`）同日落地、2026-11-14 用户实机验收通过**；
2. **SSAO**（后处理链 Pass，管线档 4.6 挂点——**GI 第一步**，零新底座）——**施工序 ①**；**✅ 2026-11-14 落码并用户实机验收关账**（`Engine_SSAO_Design.md` v0.4）：不读深度改读 G-Buffer 世界位置+法线（零 RHI 改动、仅 HYBRID）、`SsaoPass` AO/双边模糊/合成三 Pass、`PostProcessNode.order()` 排序声明、`SsaoSettings` 三参数接项目设置窗口；
3. **阴影质量分级**（PCF 软采样 → CSM 级联 → bias 精调/per-object 开关/stabilize，灯光档 §10——消费侧注入段与 shadowMap 底座三期已备，纯增量）——**施工序 ②**；**方案档 `Engine_Shadow_Quality_Design.md`（v0.5）**：**步骤一全件「一-A 品质档 PCF + 一-B bias 调参面 + 一-C per-object 开关 + 一-D 设置窗口」+ 步骤二 CSM（lambda 切分 + per-view 资源族 + 单张 2×2 atlas + 纹素吸附）2026-11-14 全部落码并用户实机验收关账**（随验两修：坑 #39 `RenderObject` 换代缓存致运行期切换不生效、#40 阴影盒只收 caster 致影子被裁）；**余 步骤三可选收尾（D16 / PCSS / 逐级联剔除 / DBG-03 / 多灯阴影）**——**② 关账，下一项 ③ TAA：`Engine_TAA_Design.md` v0.7，步骤一（抖动/重投影/历史 ping-pong）+ 步骤二锐化 2026-11-15 全部落码并用户实机验收，同日晚参数载体迁到 `Camera`（`TaaSetting` 条件子组 + 折叠组头 + 滑条、per-camera 后处理）一并验收**；
4. **天空盒环境光照**（天空盒作 ambient 源：SH9 球谐或 mipmap 降采样，替换灯光档 §3.3 ambient 常量——**DDGI 落地前的过渡环境光**；天空盒视觉面三期批次⑤已备）——**施工序 ④**（连带 P4-09 HDR 截白真解）；**步骤一（常量环境光）2026-11-16 落码并用户实机验收通过**：`Environment.ambientMode`（OFF / CONSTANT）+ 颜色 + 强度 → `LightingParamsBlock.ambient`（用户拍板「先出枚举、其他方案后置」）；**步骤二 = SH9 天空盒投影暂不做（2026-11-16 用户拍板，挂后置候选——重启触发=无主光氛围（阴天/黄昏/室内）或多通道共享受益（镜面/反射）；落法已定见 `Engine_Backlog.md` P4-04）**；
5. **探针实时 GI**（GI 目标态拍板 2026-10-28：**DDGI 路线**，团结引擎实时 GI / HDRP APV 产业验证）——烘焙 GI 管线**不建**（只面向 PC 无移动端约束、lightmap 生产线/烘焙编辑器链不投入，探针实时为主场景）；探针系统复用三期批次① SSBO/compute 底座，求交后端可替换（软光栅/迷你深度图 → rayQuery，前端探针网格/时序混合/着色采样对求交后端无关）；Lambert+环境项的 ambient 即 GI 占位符，探针落地时按位置查询替换常数（含第 4 项的过渡环境光）；探针采样前置 = **3D 纹理/纹理数组**（**RHI 底座 2026-11-18 用户实机验收通过**：`RHI_Texture_Types_Design.md` v0.3 §5.1 补齐 3D 纹理 / 2D 数组 / storage image 写口 + **跨帧布局记忆 RHI-13** + `TextureTypesTest`/`TextureDescShapeTest`；**仍缺 mip 链上传（RHI-14）与格式能力查询（RHI-15）**）；探针软技术门槛（重定位/边界混合/薄墙光渗抑制 visibility）届时专档——**施工序 ⑥（前置：纹理底座已验收 2026-11-18 + RHI-13 同批关账）**；**⑥ 前置 = RHI-14 / RHI-15**（**2026-11-20 变更**：原前置 P4-17 体素化环境光遮蔽整体退役——用户拍板「有 SSAO 目前够了」，代码已删；DDGI 首版若走体素 DDA 投射线，体素化在 P4-05 内部按「静态集过滤 + 动态 tile」重建，不再作为独立批次。连带口径变动：原「DDGI/光追档不得再乘 AO」改为「DDGI 档不与 SSAO 双层压暗」，三层收敛降为「GI 一层 + SSAO 一层补强」，见 `Engine_Backlog.md` P4-05 备注②）；
6. **光线追踪**（光追方向 2026-10-28 记录）：**rayQuery（VK_KHR_ray_query，compute/fs 内 inline 求交）优先**、ray tracing pipeline（SBT/rgen-chit）后置；架构支持结论 = 声明式 RenderGraph 同步/有序 Pass 列表/set 化描述符天然兼容——GI 与 RT 均为「新资源种类进图 + 新消费者」，不返工管线骨架；缺口清单 = 加速结构（AS）新资源种类与 build 写/trace 读图状态、BDA（buffer device address）、GL_EXT_ray_query 编译链（ShaderImporter/工具链）、蒙皮动态几何 BLAS 重构（引擎无动画系统，RT 首范围=静态几何）；**前置约束 = 三期批次①通用性红线**（灯光档 §9——Buffer 进图同步 + storage 绑定按通用 RHI 资源面建，禁止光照特化窄接口——四期 GI/RT 是该红线的第一复用方）——**施工序 ⑦（需先补 RHI 底座：AS / BDA / `GL_EXT_ray_query` 编译链）**。

- **AA 路线改向 + 管线拆分（2026-11-13 用户拍板，同日执行）**：**MSAA 已从引擎/编辑器/设置层移除**（RHI 多采样设施保留休眠），**AA 空窗至 TAA（P4-10）落地**（TAA = **施工序 ③**）——TAA 取代 MSAA，原「HYBRID 档 MSAA-GBuffer」（P4-07）作废。同期**渲染管线拆成两个独立实现类**（`AbstractRenderPipeline` 共用骨架 + `DefaultHybridRenderPipeline` / `ForwardRenderPipeline`），**运行期不再切档**、编辑器固定混合，主口径 = 混合管线（前向「先不管」）。设计归 `Engine_RenderPipeline_Design.md` v0.9 / 后处理档 v0.3。
- **着色模型升级**（Lambert → BRDF，材质/着色模型专档）与 **clustered 后置项**（前缀和计算化/脏标记跳重建/Morton/深度重建，灯光档 §10）不设期，排在**施工序 ①~⑦ 之后**，随四期各批次需要顺带或独立小批。

### 4.1 光照路线（2026-11-20 排定，逐项收口）

> 段序依据 = 先清在飞账、再补硬前置、再上主体、最后上消费面。**排序拍板（2026-11-20 用户）**：**REND-03 BRDF / REND-16 IBL 排在光追（P4-06）之后**；连带 **REND-17 反射族同步后移到 L8**——原因：Lambert 无镜面项，SSR / 反射探针 / RT 反射在 BRDF 落地前没有消费点（L6 光追只能出「阴影与 GI 求交」，出不了镜面画面）。L5 后处理与 BRDF 无关，故提前到光追前。

| 段 | 项 | 条目 / 落点 | 验收口径 | 前置 / 备注 |
|----|----|-----------|----------|-------------|
| **L1** 清在飞账 | 阴影接触漏光验收 + 提交 | BUG-11 / `Engine_Shadow_Quality_Design.md` v0.7 §9.6 | 半埋 cube 接触环亮缝收敛到 1~2 纹素、无 acne | ✅ 验收 2026-11-21（重导 `.shader`+`lighting.frag`、阴影组恢复默认）；**余项 = 提交**（拆 `fix(shadow)` + `docs` 两 commit，待用户指令） |
| | SSAO 噪点 | **BUG-12** / `ssao.frag` + `ssao_blur.frag` | 斜视地面与墙角无颗粒、接触区 AO 不被模糊出去 | ✅ 2026-11-21 用户实机收口（两遍分离 + 自适应 `sigmaZ`/bias + `R16_SFLOAT`；颗粒减轻、接触遮蔽保留，「先这样」定为基线）；余项转 REND-22 |
| ~~P4-17 批 E 收口~~ | **已退役（2026-11-20）**：随「有 SSAO 目前够了」整链删除，批 E 五项全部作废 | — | 代码可从 `dc1e9e0` 取回 |
| **L2** 硬前置 | 纹理子资源面 | RHI-14 | mip / 逐层读回一致、`createView` 从死代码转正 | 解锁点光 cube、CSM 单张分层 atlas、DDGI mip、探针 atlas 分块 |
| | 格式能力查询 | RHI-15（并 RHI-08） | 非法 `STORAGE`+格式组合创建期 fail-fast | ✅ 2026-11-21 用户实机验收（`FormatCapabilityTest`，设备 AMD 780M）；档 v0.5 §7；RHI-14 按「DDGI 单张 atlas」选型后置 |
| **L3** GI 主体 | DDGI 首版 | P4-05 / **`Engine_DDGI_Design.md` v0.2** | 单色房间有渗色、关主光墙角仍有方向性、无黑斑 | **B1 数据面 2026-11-21 落码待验收**（`DdgiSettings` + `DdgiProbeGrid` + 单测）；§10 四点按推荐默认拍板；残留 = atlas GPU 资源 + 体素化重建 + 两支 compute｜投射线走体素 DDA；网格外回落 `ambient` |
| | 环境遮蔽层收敛 | P4-05 备注② / `SsaoPass` | DDGI 档下不与 SSAO 双层压暗 | 体素 AO 已退役（2026-11-20），现只剩 SSAO 一层 |
| | 探针可视化 | DBG-07 / `Engine_DDGI_ProbeViz_Design.md` | 探针位置 / 辐照度 / 可见性可读 | ✅ 2026-11-24 落码（网格线框 + atlas 直显 + 数值行；未编译待验收）|
| **L4** 阴影补强 | cutout/alpha-test 阴影 | REND-21-A | 镂空与植被投影有孔洞 | ✅ 2026-11-24 落码（`Engine_Shadow_Cutout_Design.md` v0.2）：材质 `_AlphaCutoff` 语义 + 阴影专 set0 采样 `_MainTex` + push 144B；主渲染同口径 discard；未编译待验收 |
| | 逐级联剔除 | REND-21-B + REND-01 | 视锥外 caster 不被误剔、级联外不白画 | 必须拆两套集合（可见性 vs caster）；距离门限用 `shadowDistance` |
| | PCSS + 接触阴影 | REND-21-C | 半影随距离变化、细缝接触有暗 | **C1 PCSS 2026-11-25 落码待验收**（`softness` + binding11 原始深度 + 变半径 PCF）；**C2 接触阴影 2026-11-25 已落码**（世界位置屏幕空间 10 步短距步进 + `contactParams` @656，仅 HYBRID、零 RHI 改动；`Engine_Shadow_Contact_Design.md` v0.2）；接触漏光的根治面 |
| | 多灯阴影 | REND-21-D | 点光 / 聚光有影 | 吃 L2-1 分层能力 |
| **L5** 后处理 | 曝光测光 / 色彩分级 / 雾 / DOF | REND-18 + REND-19 + REND-12 | 有自动曝光、有环境雾、有色彩分级 LUT | 与 BRDF 无关，故排在光追前 |
| **L6** 光追 | rayQuery 首范围 | P4-06 | 静态几何求交一致；**范围限阴影与 GI 求交，不做镜面反射消费** | 需 AS / BDA / `GL_EXT_ray_query` 编译链 |
| **L7** 着色模型 | BRDF/GGX + IBL | REND-03 / REND-16 / P4-04 步骤二（SH9 重启） | 金属球有环境反射、粗糙度扫过连续 | 依赖 AST-01 mip 生成链 / AST-09 / AST-12；**用户拍板排在光追后** |
| **L8** 反射消费 | SSR / 反射探针 / RT 反射 | REND-17 | 镜面反射出画面 | 依赖 L7 的镜面项；**因无镜面消费点而随 L7 后移** |

> **贯穿项**（不单列批次）：`REND-04/05/06` cluster GPU 化 / 脏标记 / Morton 在性能告警时插入；~~`DBG-03` shadowMap 可视化~~（**2026-11-24 已落码**）与 ~~`REND-13` 光照 gizmo~~（**2026-11-25 已落码**）均已在 L4 开工前就位（见 `Engine_DebugTools_Plan.md` §3.6/§3.7）；`REND-08` 世界位置改深度重建随 REND-01 落地后算带宽账。

---

## 5. 与 `Engine_Implementation_Plan.md` 六阶段的映射

| 路线期 | 实现计划阶段 | 状态 |
|--------|-------------|------|
| 一期 | 阶段① 引擎接线 / 阶段② 场景系统 / 阶段③ 渲染系统 | ✅ 完成 |
| 一期 | 阶段④ 材质系统——**桥接半边**（§4 渲染侧三环节 + Shader/Material 数据模型） | ✅ 完成 |
| 二期 | 阶段④ 剩余（.shader/.mat 资产格式 + javelin-tool）→ 并入编辑器基本前置 | 🔄 .shader 侧已落地（2026-09-26，e03c057）；.mat/AssetManager✅ 已落地（2026-10-23 用户实测验收通过） |
| 二期 | 阶段⑤ 子系统 + 输入；**新增**：碰撞检查 / Ray / 编辑器基本（原计划未列，路线档补入） | 🔄 输入子系统 ✅（2026-09-27）；碰撞检查 v0.1 ✅ 全片单测（2026-10-06，场景级查询归 Ray 期）；Ray 侧 Camera.screenToRay ✅（2026-10-06）+ **L1 场景级 raycast ✅（2026-10-28 落地、2026-10-31 用户实测验收）**；编辑器基本 ✅ 主体验收收官（2026-10-14）——窗口层 / .scene 序列化 / Gizmo 拾取与拖拽 / 骨架全链 / 打包 JAR·AOT 双形态 / 三面板 Swing 实装 / 去 Web 化 M1~M6 / 拾取 ID-buffer / 资产链（.mat·贴图三层·纹理·meta 统一·热重载·坏 shader 兜底）/ 插件体系与 i18n 全量化 / 变换 Gizmo 三工具齐备 / 检查器组件增删——**逐批落地与验收明细见 `Engine_Roadmap_Progress.md` §2.4** |
| 三期 | 灯光 + ShadowPass + SkyboxPass + Deferred 复用（clustered Forward+ 拍板 2026-10-28——五批实装：RHI SSBO/compute 底座→Light 组件收集→cluster 构建+Forward 消费→ShadowPass（单张硬影 2026-10-31 拍板、④-A/④-B 拆分 2026-11-01）→GBuffer/LightingPass（④-B 2026-11-08 落地、11-10 验收）；Engine_Lighting_Design v0.10 / Engine_RenderPipeline_Design v0.6 / Engine_Skybox_Design v0.4） | ✅ **批次①②③④-A④-B⑤全部落地并验收关账**（①~③ 2026-10-31/11-01 验收；④-A 2026-11-01 用户实机全项；⑤天空盒 2026-11-02/11-07 用户实机全链；④-B 2026-11-10 用户实机关账）——**三期全部收官**；批挂靠见 `Engine_Roadmap_Progress.md` §5 |
| 四期 | 高质量渲染（2026-10-31 立项，§4 单点——后处理链/SSAO/阴影质量分级/天空盒环境光照/探针实时 GI（DDGI）/光追 rayQuery） | 🔄 **当前最高优先级主线（2026-11-14 拍板，跨期散件让位）**；**第 1 项后处理链（含参数可配 + 项目设置窗口）2026-11-14 验收通过**、**施工序 ① SSAO 2026-11-14 验收通过**；**施工序 ② P4-03 阴影质量分级全件（步骤一 + 步骤二 CSM）2026-11-14 验收关账，余步骤三可选收尾**；**施工序 ③ P4-10 TAA + P4-11 锐化 2026-11-15 验收关账（含相机级参数迁移与检查器条件子组/折叠组头/滑条）**；**施工序 ④ P4-04 步骤一（常量环境光）与 ⑤ P4-08 非光照材质语义 2026-11-16 用户实机验收通过（「环境光没问题」「非光照材质 看起来也没问题」）——④ 步骤二 SH9 挂后置候选、⑤ 余项（`.shader` 诊断台只读行 / Unlit 示例资产）登记后置**；**⑥ 前置 RHI 纹理底座 + 跨帧布局记忆 RHI-13 2026-11-18 验收通过**（`TextureTypesTest` 读回全一致 + 编辑器/TAA 零差异 + `[VVL]` 零 ERROR）→ **下一项 ⑥ P4-05 探针 GI（DDGI）**（⑥ 前置轻档 P4-17 体素化 AO 已退役；L2 里 **RHI-15 + RHI-08 格式能力查询 2026-11-21 落码待验收**，RHI-14 按 DDGI atlas 选型后置）；余项按 §4 施工序推进（逐项出方案 → 拍板 → 落码） |
| 未定 | 阶段⑥ 物理 / 音频（选型拍板后各成阶段） | 未开始 |

> **批挂靠归档（每批落哪期哪个阶段）与映射表明细版溯源** → `Engine_Roadmap_Progress.md` §5。

## 6. 跨期遗留（不阻塞当期推进）

> **已收敛至 [`doc/Engine_Backlog.md`](Engine_Backlog.md)**（2026-11-11 建档——「已计划、尚未完成」的单一登记处：未开工 / 半成品 / 后置项 / 待拍板 / 已知问题按域一项一行）。本节不再逐条维护。

- 原列于此的 RHI 遗留（Buffer staging / 描述符池环形 / backbuffer structureHash）、资产（动态 Mesh/纹理专项）、渲染优化（视锥剔除、遮挡剔除/Instancing/LOD）等 → `Engine_Backlog.md` §4~§6；
- **已落定、本表不再追踪**：~~environment/sun~~（2026-11-08 `Environment` 组件承接天空盒、sun 主光自动裁决三期批次②已落）、~~.scene 序列化~~（二期编辑器前置已落地）、groupId/包名迁移 + 版本定版 + 开源前置批（2026-10-31 已验收）——细则归档见 `Engine_Roadmap_Progress.md` §6；
- **插件域待拍板剩 2 项**（命令 id 前缀化 / FileType 冲突策略）→ `Engine_Backlog.md` PLG-01/PLG-02（原「3 项」中「组件显示名口径」已于 2026-10-30 拍板 key 化）。

---

> （注：部分内容可能由 AI 生成）

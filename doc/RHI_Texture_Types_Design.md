# RHI 纹理种类底座设计（3D 纹理 / 2D 纹理数组 / storage image 写口）

> v0.5（2026-11-21）——**§7 格式能力查询用户实机验收通过（RHI-15 + RHI-08 关账）**：`FormatCapabilityTest` 八行能力表 + 7 条必支持断言全通过、负例「RGBA8 建深度用途」在创建期抛 `IllegalStateException`（消息含设备名与能力快照）。**设备 = AMD Radeon 780M（集显）**，快照要点：R8_UNORM/RGBA8/R16F/RGBA16F 的 sampled+linear+storage+color+blend 全 true、`R32_UINT` color=true 但 **blend=false / linear=false**（拾取 ID-buffer 只写不混合、只 texelFetch，口径吻合）、`D32_SFLOAT` depth=true 而 storage/color=false、BC1/BC7 仅 sampled（无 color/存储，符合压缩纹理用途）。窗口为纯清屏（0.08 灰，看起来近黑）属预期，本件不看画面。
>
> v0.4（2026-11-21）——**缺口 8（格式能力查询，RHI-15 + RHI-08）落码，待编译验收**：新增 `FormatSupport`（`vkGetPhysicalDeviceFormatProperties` 的 optimalTilingFeatures 快照 + `supports(TextureUsage)` 映射）、`Device.formatSupport(Format)`（按格式缓存）；纹理创建期按 usage 逐项校验、PSO 创建期校验附件格式（颜色要 `COLOR_ATTACHMENT`、开混合要 `COLOR_ATTACHMENT_BLEND`、深度要 `DEPTH_STENCIL_ATTACHMENT`），缺项抛 `IllegalStateException` 并带设备名/格式/缺失用途。验证件 `FormatCapabilityTest`（能力表打印 + 规范必支持断言 + 负例抛错）。
>
> v0.3（2026-11-18）——**阶段一/二/三全部用户实机验收通过**：`TextureTypesTest` 两条「读回比对通过」（3D 16³ 逐体素 + 2D 数组逐字节）+ 画面为 XOR 灰度图案；编辑器常规画面与 TAA 零视觉差异、`[VVL]` 零 ERROR（无 `oldLayout` 不匹配）；`ComputeSsboTest` / `CubemapTest` 回归正常。**随验收修一处测试件设计缺陷**：图形段原取 z=0.5，恰好落在 z=7 与 z=8 两层正中间，而两层图案互补（`(x^y^8)&7 = 7 - ((x^y^7)&7)`），三线性各取一半 → 恒 0.5，整屏中灰看不出图案；改取第 7 层体素中心 `(7+0.5)/16`（`texture3d-read.frag`，仍走 `texture()` 采样路径，未换 `texelFetch`）。补纯逻辑 `TextureDescShapeTest`（§5 最后一条「形状校验可触发」）。
>
> v0.2（2026-11-16）——**阶段一、二、三已落码（未编译、待验收）**：本批服务四期施工序 ⑥（P4-05 DDGI）与 ⑥ 前置（P4-17 体素化 AO），补齐「3D 纹理 / 纹理数组 + compute 写纹理 + 跨帧布局记忆」这条底座链路。用户 2026-11-16 明确「验收推迟」，先落码。
>
> 版本沿革：v0.1 = 阶段一/二（纹理种类 + storage image 写口 + 按种类上传 + 形状校验）；**v0.2 = 阶段三（跨帧布局记忆 RHI-13，布局状态随资源持有）**。
>
> 归属：RHI 层设计增量，与 `RHI_Design_Resources.md`（资源/描述符/绑定）互补；实装与验收口径在本文，长期待办落 `Engine_Backlog.md` §4。

---

## 1. 目标与范围

| 范围 | 内容 |
|------|------|
| **本批做** | 3D 纹理种类真正可用（创建 / 上传 / 视图 / 采样）、2D 纹理数组种类新增、storage image 绑定口（compute 写纹理的唯一入口）、纹理种类 × 维度形状的创建期校验、按种类正确的上传区域、**跨帧布局记忆（布局状态随资源持有）** |
| **本批不做** | 自定义子资源视图（`TextureViewDesc` 仍无消费方）、格式能力查询（`vkGetPhysicalDeviceFormatProperties`）、逐层/逐 mip 屏障、mip 链上传、AS / BDA / `GL_EXT_ray_query`（⑦ P4-06 独立批） |

判据：DDGI 首版需要的是「3D 体数据（体素化）→ compute 写 → 着色器采样」，以及「探针辐照度 atlas（2D 或 2D 数组）→ compute 分帧写 → 采样」。本批只做到这两条能跑通，其余按 §4 拍板点后置。

---

## 2. 现状实证（缺口 → 位置 → 影响）

| # | 缺口 | 实证位置 | 影响 |
|---|------|----------|------|
| 1 | `TextureType` 无 `TYPE_2D_ARRAY` | `types/TextureType.java`（原仅 1D/2D/3D/CUBE/CUBE_ARRAY） | 分层附件、探针分块、点光 cube 阴影无数组种类可用；`Shadow_Quality §B` 已把它记为「RHI 底座先行」 |
| 2 | 上传只认 CUBE 与 2D | `VulkanDevice.uploadTexture`（原非 CUBE 一律 `TextureRegion.whole2D`） | 3D / 数组上传只拷 1 层 1 切片，读回即错；等于 3D 只能靠 compute 填 |
| 3 | storage image 绑不出去 | `DescriptorUpdate` 无 `storageTexture` 工厂——`Type.STORAGE_TEXTURE` 与 `VulkanDescriptorSet` 的 `GENERAL` 映射早已就位 | compute 写纹理**没有 API 入口**：这是 ⑥ 最硬的一条 |
| 4 | 种类 × 形状无校验 | `TextureDesc` 构造器只校验正数 | 3D 传 `arrayLayers≠1`、CUBE 非六面/非正方形等只在驱动或校验层报；错在创建期抛更省排障 |
| 5 | 无自定义视图 | `TextureViewDesc` 全项目**零引用**（`VulkanTexture` 只有 `ensureView()` 全范围默认视图） | 按层/按 mip 子集绑定不可达（点光 cube 阴影、CSM 分层、探针 atlas 分块）；DDGI 首版整张绑定即可不用 |
| 6 | 屏障仅全范围 | `VulkanCommandBuffer.pipelineBarrier` 恒 `0..mipLevels / 0..arrayLayers` | 逐层/逐 mip 转换不可达（首版够用，分帧刷探针若要按层转换则需扩） |
| 7 | **跨帧布局记忆缺失** | `RenderGraphImpl.execute`：执行器每帧新建 `LayoutTracker`，非 backbuffer 纹理一律起手 `UNDEFINED`。**阶段三已修**：布局状态改为随纹理资源持有（`VulkanTexture.layout`，跨帧沿用），`LayoutTracker` 类随之退役删除 | 跨帧持久纹理（TAA 历史、将来的探针 atlas / 体素网格）每帧首用都声明 `UNDEFINED → X`——按规范内容**可被丢弃**。现驱动实测保留（TAA 能跑），但属未定义行为，DDGI 依赖它会更危险 |
| 8 | 格式能力未查 | **已修（2026-11-21，RHI-15 + RHI-08）**：新增 `FormatSupport` + `Device.formatSupport`；纹理/PSO 创建期按 usage 与附件格式校验，缺项 fail-fast | `STORAGE` + `R16G16B16A16_SFLOAT` 这类组合曾靠驱动兜，失败滞后；现错误点前移到创建期 |

> 补充：compute 底座本身已在三期批次① 验收（`ComputeSsboTest`：compute→compute→图形 + 65536 元素读回），`PassDef.addTextureStorageRead/Write`、`RenderGraphImpl` 的 `GENERAL` 屏障计划、`DescriptorType.STORAGE_IMAGE` 布局、`ComputePipelineDesc.pushConstantSize` 均已就位。**本批缺的是纹理种类与绑定 API，不是 compute 链路。**

---

## 3. 本批实装（阶段一 + 二）

| 文件 | 改动 |
|------|------|
| `types/TextureType.java` | 新增 `TYPE_2D_ARRAY`（VK 映射 = `VK_IMAGE_TYPE_2D` + `arrayLayers > 1`） |
| `desc/TextureDesc.java` | 新增 `validateShape(...)`：`TYPE_3D` ⇒ `arrayLayers == 1`；`TYPE_2D_ARRAY`/1D/2D ⇒ `depth == 1`；`CUBE` ⇒ 六面 + 正方形；`CUBE_ARRAY` ⇒ 层数 6 的倍数 + 正方形。新增便捷构造 `texture3D(format, w, h, d, usage)` / `texture2DArray(format, w, h, layers, usage)`（后者 `layers ≥ 2`，单层请用 `texture2D`） |
| `VulkanTexture.java` | `toVkImageType` 补 `TYPE_2D_ARRAY → VK_IMAGE_TYPE_2D`；`createDefaultView` 补 `TYPE_2D_ARRAY → VK_IMAGE_VIEW_TYPE_2D_ARRAY`（3D 保留 `VK_IMAGE_VIEW_TYPE_3D`） |
| `TextureRegion.java` | 新增 `whole3D(w, h, d)`（extent.depth 表达第三维、单层）/ `whole2DArray(w, h, layers)`（按层整拷、extent.depth = 1） |
| `VulkanDevice.java` | `uploadTexture` 改按种类取区域（新 `uploadRegionOf(TextureDesc)`）：3D → `whole3D`、2D 数组 / cube 数组 → `whole2DArray`、CUBE → `cubeFaces`（原分支保留）、其余 → `whole2D`。上传仍回迁 `SHADER_READ_ONLY`（坑 #37 口径不变） |
| `DescriptorUpdate.java` | 新增 `storageTexture(int binding, Texture texture)`：`Type.STORAGE_TEXTURE` → `VK_DESCRIPTOR_TYPE_STORAGE_IMAGE` + 描述符布局恒 `GENERAL`；纹理须以 `TextureUsage.STORAGE` 创建，并在 Pass 内 `addTextureStorageRead/Write` 让执行器转布局 |

### 3.1 验证件（真实 GPU，用户运行）

`javelin-core/src/test/java/cn/donting/javelin/core/TextureTypesTest.java` + 三份 test resources 着色器（`compute/texture3d-write.compute`、`shader/texture3d-read.vert`、`shader/texture3d-read.frag`）：

1. **3D + compute 写 storage image**：compute Pass 以 `image3D`（`layout(r8)`）写 16³ `R8_UNORM` 体数据（图案 = `(x ^ y ^ z) & 7`，纯整数位运算，CPU 侧同式复算）→ 图形 Pass `sampler3D` 取 z=0.5 切片显示（验证 `GENERAL → SHADER_READ_ONLY` 跨 Pass 屏障 + 3D 默认视图 + 3D 采样）→ 帧 30 一次性命令缓冲 `copyImageToBuffer(whole3D)` 读回，逐体素比对（容差 1.5 码值，UNORM 量化）。
2. **2D 数组上传整层**：`texture2DArray`（4×4 RGBA8 ×3 层）经 `UploadData` 上传已知字节 → `copyImageToBuffer(whole2DArray)` 读回逐字节比对（此前该路径只拷 1 层，必然失败）。

运行：`gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.TextureTypesTest`

### 3.2 阶段三：跨帧布局记忆（RHI-13）

**形态（取 §4-2 之外的第三种写法，优于原建议 A）**：不建「执行器侧持久表」，改为**布局状态随资源持有**——

| 文件 | 改动 |
|------|------|
| `VulkanTexture.java` | 新增 `volatile TextureLayout layout = UNDEFINED` + 包内 `layout()/setLayout()`；随纹理对象生死，天然跨帧沿用、无句柄复用错配、无表膨胀 |
| `VulkanCommandBuffer.pipelineBarrier` | 以**资源记录的布局为准**（`VkImageMemoryBarrier.oldLayout` 必须等于真实布局），`oldLayout` 参数仅对无状态实现（交换链图像）回退；屏障录制完成后回写 `newLayout` |
| `RenderGraphImpl.execute` | 删 `LayoutTracker`，屏障前取 `layoutOf(texture)`（非 `VulkanTexture` ⇒ `UNDEFINED`）；**交换链图像另用本帧局部变量记账**（acquire 后真实布局恒 `UNDEFINED`，坑 #37 口径不变，图尾 `PRESENT_SRC` 转换同前） |
| `LayoutTracker.java` | **删除**（唯一消费方已改） |
| `types/TextureLayout.java` | 注释改为「状态随资源持有」 |

为什么比原建议 A 好：① 上传路径（`VulkanDevice.uploadTexture` 结束在 `SHADER_READ_ONLY`）的布局自动被记住——原方案下「上传完的纹理」首帧仍会被声明 `UNDEFINED`（内容可丢），这次连这个隐患一起消掉；② 手动录制（读回环、测试）与图执行共用同一份状态，不会出现「图认为 A、手动 Barrier 认为 B」；③ 无生命周期/句柄复用问题。

代价：布局状态隐含「录制即提交」前提——命令缓冲若录制后被丢弃（未提交），状态会偏一格。现工程内无此路径（图执行、上传、读回环均录制即提交）。

---

## 4. 拍板点（下一步方向，待定）

1. **自定义视图 `Texture.createView(TextureViewDesc)`**——DDGI 首版整张绑定不需要；点光 cube 阴影、CSM 单张分层 atlas（`Shadow_Quality §B`）、探针 atlas 分块需要。建议：**等 ⑥ 真需要时再做**，避免又造一个无消费方的 API。
2. **跨帧布局记忆（缺口 7）**——**阶段三已落码**，形态见 §3.2（资源自持布局，优于原建议的「执行器持久表」）。剩余待拍板：是否需要在验收时观察 `LayoutTracker` 删除对 TAA / 阴影 / Skybox 的既有效果（预期：无视觉差异，仅少若干冗余屏障）。
3. **格式能力查询**——建议与 `RHI-08` 合并做：`PhysicalDevice` 暴露 `supportsFormat(Format, usage, tiling)`，创建期 fail-fast。
4. **AS / BDA / `GL_EXT_ray_query`（⑦ P4-06 前置）**——建议独立成批（扩展启用 + 编译链 + 资源种类，与纹理这条不耦合）。

---

## 5. 验收清单（本批）

- `TextureTypesTest` 跑通：画面 = 体数据第 7 层体素中心（`(7+0.5)/16`）的 XOR 灰度图案稳定；控制台两条「读回比对通过」；ESC 退出不崩。
- 校验层 `[VVL]` 零 ERROR：重点看 3D 视图创建、`GENERAL` 布局转换、`copyImageToBuffer` 区域（3D extent 的 depth、数组 layerCount）。
- **阶段三专项（跨帧布局）**：编辑器常规画面零差异；TAA 拖影/收敛表现与改造前一致（历史 ping-pong 现在按真实 `COLOR_ATTACHMENT / SHADER_READ_ONLY` 转换，不再每帧 `UNDEFINED`）；`[VVL]` 不得出现 `oldLayout` 不匹配类（提示 `VUID-vkCmdPipelineBarrier-oldLayout`）。
- 回归不破：Skybox（CUBE 上传 + cube 采样）、`ComputeSsboTest`、编辑器常规画面与 `logs/error` 零 ERROR。
- 形状校验可触发：`TextureDescShapeTest`（纯逻辑，不上 GPU）——`TYPE_3D` + `arrayLayers=2`、`CUBE` + 5 层 / 非正方形、`TYPE_2D` + `depth=2`、`texture2DArray` 层数 < 2 各抛 `IllegalArgumentException`，合法形状（`texture3D` / `texture2DArray` / `textureCube`）不抛。

### 5.1 验收记录（2026-11-18 用户实机）

| 项 | 结果 |
|---|---|
| `TextureTypesTest` 3D 读回 | ✅ `16³ = 4096 体素全一致`（compute 写 storage image + `GENERAL` 布局 + 3D 视图 + `copyImageToBuffer` 全链） |
| `TextureTypesTest` 2D 数组读回 | ✅ `192 字节全一致`（`whole2DArray` 按层整拷；原路径只拷 1 层必挂） |
| 画面（3D 采样） | ✅ XOR 灰度图案（**首跑全灰 = 测试件 z 取样点撞互补层**，改第 7 层中心后恢复；非链路缺陷） |
| `[VVL]` | ✅ 零 ERROR（含无 `oldLayout` 不匹配） |
| 阶段三专项（RHI-13） | ✅ 编辑器侧正常：常规画面零差异、TAA 表现不变 |
| 回归 | ✅ `ComputeSsboTest` / `CubemapTest` 正常，编辑器常规画面无回归 |
| 形状校验 | ✅ `TextureDescShapeTest`（6 用例，纯逻辑） |

---

## 6. 已知边界（本批不做，已在 §4 挂号）

- `UploadData.mipLevels` 仍未被 `uploadTexture` 消费（只拷 mip 0）——mip 链上传缺失（DDGI 若要走 mip 采样需先补）。
- 屏障全范围（缺口 6）：3D / 数组语义正确但粒度粗。
- 布局状态不区分子资源（逐层 / 逐 mip 转换后状态会互相覆盖——现阶段无人做子区间转换，与缺口 6 同批再解）。
- 命令缓冲「录制未提交」会让布局状态偏一格（现工程无此路径，见 §3.2 代价）。
- 格式能力未查（缺口 8）——**已修（2026-11-21）**，见 §7。

---

## 7. 格式能力查询与创建期 fail-fast（RHI-15 + RHI-08，2026-11-21）

> 动机：`STORAGE` + `RGBA16F`、`R32_UINT` 作拾取附件这类组合此前**全靠驱动兜**——不支持时错误在绘制期被校验层点出，离创建点很远；DDGI 要上 `RGBA16F` storage + atlas 分块，先把这层闸门补上。

| 件 | 落点 |
|----|------|
| 能力快照 | `FormatSupport`（public，`javelin-rhi`）：构造于 `optimalTilingFeatures`；暴露 `sampledImage / linearFilter / storageImage / colorAttachment / colorAttachmentBlend / depthStencilAttachment / transferSrc / transferDst`，以及 `supports(TextureUsage)` 映射表 |
| 查询与缓存 | `VulkanPhysicalDevice.formatSupport(Format)`：`vkGetPhysicalDeviceFormatProperties` + `ConcurrentHashMap` 按格式缓存（驱动查询只做一次）；`Device.formatSupport(Format)` 公开入口（诊断/按机型选档用） |
| 纹理创建期 | `VulkanTexture` 构造首步 `validateFormatSupport(desc)`：遍历 `desc.usage()`，任一用途缺 feature 即抛 `IllegalStateException`（消息带设备名、格式、缺失用途、能力快照） |
| PSO 创建期 | `VulkanGraphicsPipeline.validateAttachmentFormats(...)`：颜色附件要 `COLOR_ATTACHMENT`；`blendEnable=true` 还要 `COLOR_ATTACHMENT_BLEND`；`depthAttachmentFormat` 非空要 `DEPTH_STENCIL_ATTACHMENT` |
| 验证件 | `javelin-core/src/test/java/.../FormatCapabilityTest.java`：打印主力格式能力表 + 7 条规范「必支持」断言（R8G8B8A8_UNORM 可采样/可色附件/不可深度、R16_SFLOAT 可色附件且可线性过滤、RGBA16F 可采样可色附件、R32_UINT 可色附件、D32_SFLOAT 可深度）+ 负例（RGBA8 建深度用途纹理必须创建期抛） |

**映射口径两处特例**：`INPUT_ATTACHMENT` 无独立格式位，按「可作颜色或深度附件」判定；`TRANSIENT_ATTACHMENT` 是内存策略（`LAZILY_ALLOCATED`），不查格式能力（恒 true）。

**验收**：`gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.FormatCapabilityTest` → 控制台能力表 + 「格式能力断言全部通过」，`[VVL]` 零 ERROR，编辑器常规画面/阴影/SSAO 无回归。

**验收记录（2026-11-21 用户实机，设备 AMD Radeon 780M）**：

| 项 | 结果 |
|---|---|
| 能力表（8 格式） | ✅ 全打印（R8_UNORM / RGBA8 / R16F / RGBA16F / R32_UINT / D32F / BC1 / BC7） |
| 7 条规范「必支持」断言 | ✅ 全 `[通过]` |
| `R32_UINT` 快照 | ✅ color=true、blend=false、linear=false（拾取只写不混合 + `texelFetch`，口径吻合） |
| `D32_SFLOAT` 快照 | ✅ depth=true、storage=false、color=false |
| BC1/BC7 快照 | ✅ 仅 sampled=true（压缩格式不作附件/存储，符合用途） |
| 负例（RGBA8 + 深度用途） | ✅ 创建期抛 `IllegalStateException`，消息含设备名 + 格式 + 缺失用途 + 能力快照 |
| 窗口 | ✅ 正常清屏（0.08 灰近黑，本件不看画面） |

> 结尾提示：快照来自集显 780M；换到独显（5060）跑同一件仍应全通过，若某组合在独显上反而缺失，fail-fast 会直接把设备名与缺失位打出来。

**边界**：只查 `optimalTilingFeatures`（线性平铺仅用于读回中转）；多采样（MSAA）能力与 format 组合未查（本项目 MSAA 业务面已删，REND-11）；纹理过滤（`linearFilter`）只在需要处（如 SSAO 半分辨率上采样）人工确认，创建期不强制。

> （注：部分内容可能由 AI 生成）

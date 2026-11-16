# RHI 纹理种类底座设计（3D 纹理 / 2D 纹理数组 / storage image 写口）

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
| 8 | 格式能力未查 | 无 `vkGetPhysicalDeviceFormatProperties` 调用（`RHI-08` 已记同族） | `STORAGE` + `R16G16B16A16_SFLOAT` 这类组合是否支持靠驱动兜，失败滞后 |

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

- `TextureTypesTest` 跑通：画面 = 体数据 z 中切片 XOR 灰度图案稳定；控制台两条「读回比对通过」；ESC 退出不崩。
- 校验层 `[VVL]` 零 ERROR：重点看 3D 视图创建、`GENERAL` 布局转换、`copyImageToBuffer` 区域（3D extent 的 depth、数组 layerCount）。
- **阶段三专项（跨帧布局）**：编辑器常规画面零差异；TAA 拖影/收敛表现与改造前一致（历史 ping-pong 现在按真实 `COLOR_ATTACHMENT / SHADER_READ_ONLY` 转换，不再每帧 `UNDEFINED`）；`[VVL]` 不得出现 `oldLayout` 不匹配类（提示 `VUID-vkCmdPipelineBarrier-oldLayout`）。
- 回归不破：Skybox（CUBE 上传 + cube 采样）、`ComputeSsboTest`、编辑器常规画面与 `logs/error` 零 ERROR。
- 形状校验可触发：手造 `TYPE_3D` + `arrayLayers=2`、`CUBE` + 5 层 应抛 `IllegalArgumentException`。

---

## 6. 已知边界（本批不做，已在 §4 挂号）

- `UploadData.mipLevels` 仍未被 `uploadTexture` 消费（只拷 mip 0）——mip 链上传缺失（DDGI 若要走 mip 采样需先补）。
- 屏障全范围（缺口 6）：3D / 数组语义正确但粒度粗。
- 布局状态不区分子资源（逐层 / 逐 mip 转换后状态会互相覆盖——现阶段无人做子区间转换，与缺口 6 同批再解）。
- 命令缓冲「录制未提交」会让布局状态偏一格（现工程无此路径，见 §3.2 代价）。
- 格式能力未查（缺口 8）。

> （注：部分内容可能由 AI 生成）

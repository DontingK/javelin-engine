# 引擎贴图资产设计（Engine Texture Design）

> 版本：v0.4（2026-11-08 **导入期引用解析壳管理器**——`image=@guid` 解析不再要求 Image 产物已生成，首次导入 FileNotFoundException 修复；Editor_Design §6.3，同日用户实测验收）
> v0.3（2026-10-30）：**Texture2D 运行期可变 + revision 版本号**——同日 Texture2DTest 改断言 + 运行期换图/采样实机验收销账。
> 范围：图片导入管线（.png/.jpg/.jpeg → Image 像素资产 → Library 产物）+ 三层资产模型拍板 + Texture2D 引用层改造 + **v0.2 增量：纹理资产（.texture 用户文件 + 创建命令 + 导入器 + 检查器面板）** + **v0.3 增量：上传冻结废除、运行期可变（setImage/setFilterMode/setWrapMode 即时生效）+ revision 驱动渲染侧描述符集重建**。
> 消费链（Material 贴图槽 → RenderingSystem → TextureUpload 懒上传 → RHI Texture/Sampler）为既有已落地件，消费兼容性本批一并补齐（null-image 占位守卫）。

---

## 1. 三层资产模型（拍板 2026-10-25）

```
源文件(.png/.jpg/.jpeg)          Image（像素资产）           Texture2D（引用层）        Material 贴图槽
  │ ImageIO 解码        ──▶      width/height/format/  ──▶   image 引用 + filter/wrap ──▶ MaterialTextureEntry
  │ (ImageImporter)              byte[] pixels              + mipLevels + gpu 句柄       (guid 分流→Texture2D)
```

- **Image（`cn.donting.javelin.core.render.texture.Image`，extends AbsAsset）**：像素资产=导入产物，纯数据持有者。磁盘一份（`Library/{guid}.asset`）全项目共享，多引用零复制。
- **Texture2D**：引用层=Image 引用 + 采样/派生设置（FilterMode/WrapMode/mipLevels）。**同一 Image 可被多个 Texture2D 以不同设置引用**——消解 Unity「导入设置在源文件 .meta、一文件一导入结果、多设置须复制文件」痛点；本质=Vulkan image/sampler 分离上移资产层。宽高/格式/像素经 image 委托（image 为 null 访问即抛）。gpu 句柄维持懒上传回填、序列化不落地。
- **占位常量**（whiteTexture/blackTexture/normalTexture）：Texture2D 包 1×1 `Image.solid`，device 无关语义不变。

**沿革**：Texture2D 原为单层式（像素内联+设置，2026-09-25 冻结语义拍板时期产物）；2026-10-25 贴图管线批次先按「单层先行」出方案，用户中途改派「Texture2D 就是引用像素资源」——三层为终态拍板，单层方案作废。冻结语义（上传后 setImage/setFilterMode/setWrapMode 抛 IllegalStateException）曾随改造保留、覆盖 setImage；**2026-10-30（v0.3）冻结语义废除**——Texture2D 改为运行期可变（详见 §8）。

## 2. 导入链

| 环节 | 落点 | 说明 |
|------|------|------|
| 解码 | `TextureLoader.loadImage(Path)`（core，javax.imageio.ImageIO） | png/jpg/jpeg 同一入口；getRGB 逐像素 → RGBA8 紧密打包 byte[]（行主序、顶行在前） |
| 导入器 | `ImageImporter` + `ImageImportSettings`（editor `importer/image` 包） | ClassUtil classpath 扫描自动注册，管线 refresh/reimport/needImport/index 链零改动；settings type="Texture" 与 MetaManager.detectAssetType 一致 |
| guid 口径 | 伴生 .meta 随机 guid | 二进制源无 guid 文件头（guid 头=内置资源专属，2026-10-25 拍板）——同 .mat/.scene 口径，MetaManager 现行为已吻合零改动 |
| 产物 | `Library/{guid}.asset`（BinaryExporter） | AbsAsset super（guid/lastModified）+ width/height/format/pixels 直传 |
| 触发 | 项目打开期 refresh / 右键 Reimport | 外部拷入即时感知（watcher）未做——重开项目/手动 reimport 触发（全管线现状一致） |

文件类型：`IconFileType("image", ".png", ".jpg", ".jpeg")`（纯图标，无打开器）+ `icon/image.svg`。.bmp 将来加扩展名一行即可（ImageIO 原生支持，detectAssetType 已预置识别）。

## 3. 序列化

- **Image**：`super.write` 首行（guid/lastModified）+ `width`/`height` int + `format` 枚举 + `pixels` byte[] 直传（红线 10——Capsule 只有 byte[] 通道，字段类型即序列化实体类型）；read 枚举 null 不回写保默认。
- **Texture2D**：`image` 走 `readSerializable` guid 分流（guid 非空→GUID 引用解析、空→图内嵌）+ `mipLevels`/`filterMode`/`wrapMode`；read 缺失不回写保字段初始默认（BILINEAR/REPEAT/1）；**gpu 不落盘**（重载后渲染侧懒上传重建）。
- **TextureUpload**：像素经 Texture2D 委托取 byte[] → `UploadData.of(ByteBuffer.wrap(...))`（运行时转换、非序列化路径，不触红线 10）。

## 4. alpha 修复（本批顺带）

`TextureLoader.decode` 原写死 `0xFF` 丢弃透明度 → 改取真实 `argb >>> 24`：PNG 透明通道保留（此前被压成不透明，透明混合材质贴 PNG 直接翻车）；JPEG 无 alpha 恒 0xFF 不受影响。

## 5. 已知边界（后置）

- **mip 生成链 RHI 未实现**：导入恒 mipLevels=1（TextureUpload 现有校验照旧拦截）；
- **块压缩后置**：RGBA8 直读（BC/ASTC 归内容类设置、落 Texture2D 层，Image 永存原始像素——将来多压缩设置各派生一份 GPU image、磁盘仍一份）；
- **sRGB 口径后置**：当前 R8G8B8A8_UNORM 直读；线性工作流接交换链 sRGB 变体时需视图格式分支（VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT 的 sRGB/UNORM 互看规则需实现期 MCP 查证）；
- **纹理资产（.texture 用户文件 + 导入自动伴生默认 Texture2D + Inspector 贴图槽编辑）**：挂账另批（Inspector_Design 遗留「贴图槽编辑」项）；
- **一图多设置的 UI 消费未落**：模型已支持（多个 Texture2D 引用同一 Image），设置入口待纹理资产批次；
- **旧测试断言修正**：MaterialTest 占位断言 `assertEquals(1, getPixels().get(2))`（byte 0xFF 符号位歧义）改 `getPixels()[2] & 0xFF` 无歧义形式。

## 6. 验收清单（✅ 用户实测 2026-10-25——编辑器全链；单测项另行挂账）

- [ ] Texture2DTest 全绿（新增：Image 往返 / Texture2D 引用+设置往返两例）——跑测归用户，Engine_Features §6 挂账；
- [x] 拷一张 .png 与一张 .jpg 进 Assets/ → 重开项目或右键 Reimport → 日志见「贴图导入成功: xxx (WxH)」；
- [x] `Library/{guid}.asset` 产物生成、`loadGuid(guid, Image.class)` 取回完整像素；
- [x] 项目面板图片文件显示 image 图标（非 unknown）；
- [x] SceneRenderDemo/MultiCameraDemo 照常渲染（Texture2D 引用层改造回归）——IDEA 检查零错误，实跑归单测挂账。

---

## 7. 纹理资产（.texture，v0.2 2026-10-25）

**「.texture 就是 Texture2D 的序列化」**（.mat 同款拍板）：源 = Texture2D 文本序列化
（javelin-text-serialization，image 字段 Asset 引用走 guid 分流——`image=@guid:`
引已导入 Image / `@null`=未选图纹理壳合法）、伴生 .meta 口径（guid 真值源）。

| 环节 | 落点 | 说明 |
|------|------|------|
| 创建命令 | `CreateTextureMenuCommand`（project.create.texture，创建族第三项） | 选中目录落 `texture.texture`（重名编号去重）；`new Texture2D()`+meta guid → TextExporter 一次性落盘 → submitImport → project.changed |
| 导入器 | `TextureImporter` + `TextureImportSettings`（importer/texture 包） | TextImporter 解析（image=@guid 解经壳管理器 `ShellAssetManager`——不加载 Image 产物、按 meta 登记判引用存在，未登记=解 null；2026-11-08，Editor_Design §6.3）→ meta guid 覆盖自述 → BinaryExporter 产物 |
| 检查器 | `TextureInspectorProvider`/`TextureInspectorContent`（.texture 键） | 「纹理」固定分组（文件/图 AssetInput/GUID）+「采样」分组（filter/wrap 下拉）；图换绑即时写回重导（None 合法/空串拒绝）、采样提交防抖 500ms（.mat 同款合并阀+flushPending 兜底） |
| 通用件 | AssetInput/AssetPickerDialog 升多后缀 | TYPE_SUFFIX 值改列表——Image 三后缀 [.png/.jpg/.jpeg] 首个多扩展名类型；选择窗按后缀列表枚举、拖放按列表过滤 |
| 渲染守卫 | RenderingSystem materialSet | 贴图槽实例非 null 但 image=null（纹理壳）→ 同未赋槽走占位贴图（上传路径对 null image 会抛） |

**编辑载体拍板（与 .mat v0.11 分歧）**：.texture 面板编辑载体=**文本解析私有实例**
（不取 loadGuid 缓存共享实例，拍板 2026-10-25）。**v0.3 注**：原理由之一（Texture2D
上传冻结使共享实例不可编辑）随冻结废除而消解，但本批未改面板载体，仍取文本私有实例——
其语义 = 只编辑文件镜像，不触场景持有实例（同 .mat 面板）；运行期即时联动改由
Texture2D.revision 承担（§8，针对场景实例）。

**已知边界（v0.3 修订）**：运行期换图/改采样对**场景持有的 Texture2D 实例**经 revision
通道即时生效（§8）；`.texture` 面板改的是文本私有实例，只更新文件镜像、不触场景实例 →
面板改动对运行中场景仍不即时生效（需重载场景或后续热重载接入）。材质贴图槽（.mat 面板）
行换装 Texture2D 已同批落地（Unity 口径内联参数组、None=占位贴图，Inspector_Design v0.12 再追修）；
mip/压缩/sRGB 见 §5。

**v0.2 追修**：GuidHeaderManager.extractGuid 原整文件按 UTF-8 文本读——二进制源
（拖 .png 进图行 / 项目打开期 sync 对每个新 png 补 meta）炸 MalformedInputException
错误栈（IOException 兜底捕获未崩、拖放经 meta 兜底仍生效）。修=只读首行 +
CharacterCodingException 降级 debug「二进制源无头=预期态」+ **拖放 guid 双源反序**
（用户拍板）：伴生 .meta 优先（面板拖入用户文件恒命中、真值=meta）、文件头降为
外部拖入带头副本的兜底——原「头优先」对无头的用户文件恒 miss 白读源文件。

**v0.2 验收清单（✅ 用户实测 2026-10-25）**：
- [x] 项目面板右键「创建 → 纹理」落 texture.texture + 面板见新文件（texture 图标）；
- [x] 选中 .texture → 检查器出「纹理」分组（文件/图/采样），图=AssetInput（选择窗列 .png/.jpg/.jpeg、可拖放）；
- [x] 图换绑 → .texture 文本 image=@guid 落盘 + 日志「纹理导入成功 (image=…)」；None 回清合法；
- [x] 采样下拉改动 → 防抖落盘（filterMode/wrapMode 文本更新）；
- [x] 材质面板 _MainTex 行（内联参数组）换装 texture.texture → .mat 文本 textures 条目落盘、
      None 回清合法（运行中场景画面不变=已知边界，重载场景生效）；
- [ ] 材质引用纹理壳（image=null）不崩、按占位贴图渲染（守卫已落，未实测）。

---

## 8. 运行期可变与 revision（v0.3，2026-10-30）

**冻结废除**：删除 `checkMutable` 与「上传后 setImage/setFilterMode/setWrapMode 抛 IllegalStateException」守卫——Texture2D 上传后仍可就地改（取代原「要改就新建资产」口径，与 Mesh 冻结口径自此分道）。

**变更语义**：

| 方法 | 行为 |
|------|------|
| `setImage(Image)` | 同一 Image 引用 → 直接返回（不重建）；换引用 → 换绑 + **旧 GPU 句柄置空**（下次绑定按新像素重新上传，旧句柄随旧描述符集释放后由 RHI Cleaner 兜底）+ `revision++` |
| `setFilterMode` / `setWrapMode` | 只影响 sampler、不涉像素 → 不设冻结守卫，直接改 + `revision++` |
| `getRevision()` | 内容/采样设置版本（**不入序列化**；每次变更自增） |

**渲染侧消费**：`RenderingSystem.materialSet` 取材质资源后比对 `material.getTextureRevision()` 与资源建造时版本、以及每个 `SlotBinding` 记的 `Texture2D.getRevision()`——不一致即两帧安全退役旧描述符集/UBO 并重建（运行期换图/改采样/换贴图自动生效，调用方无需触发对账）。

**旧槽位判据替换**：v0.2 的 `SlotBinding.usedPlaceholder`（「实例同、image 状态变」比对）改为 `textureRevision`——纹理壳后补图与内容/采样变更统一由 revision 体现（`Engine_AssetReload_Design` v0.3 的实锤缺口判据升级）。

**单测**：`Texture2DTest` 原「上传后修改抛异常」用例改「允许修改（revision 自增、换图置空 gpu）」+ 新增「同一 Image 引用不重建（句柄保持）」用例。

**v0.3 验收清单（✅ 2026-10-30 用户实测；换图重传 2026-11-12 复核）**：
- [x] 运行期对场景 Texture2D 实例 setImage（换图）→ 画面下帧更新为新图；旧 GPU 图片经 Cleaner 释放（F3 存活计数不涨）；**2026-11-12 用户实机复核**：面板换图 → `material set created` 出新 guid + 旧 TEXTURE/IMAGE_VIEW 经 Cleaner 回收、画面当帧变；
- [x] 运行期 setFilterMode/setWrapMode → 采样效果即时变（POINT/CLAMP 可见）；
- [x] `Texture2DTest` 修改后两例全绿（跑测归用户）。

---

> （注：部分内容可能由 AI 生成）

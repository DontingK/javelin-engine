# 引擎阶段路线——批次索引（Engine Roadmap Progress）

> 版本：v0.31（2026-11-25 补光追 B3：加速结构描述符 + 求交直显）；v0.30（2026-11-25 补光追 B2b：TLAS + 构建后屏障 + 光追场景装配）；v0.29（2026-11-25 补光追 B2a：AS 设备地址 + BLAS 构建 + Mesh 缓存）；v0.28（2026-11-25 补光追 B1 能力启用落码条目）；v0.27（2026-11-25 补光追 B0 能力探针条目 + L5 四项暂缓拍板）；v0.26（2026-11-25 补 REND-21-B 逐级联剔除落码条目）；v0.25（2026-11-25 补 REND-13 光照 gizmo + REND-21-C2 接触阴影两笔落码条目）；v0.24（2026-11-25 补 REND-13 光照 gizmo 落码条目）；v0.23（2026-11-25 补 L4 第二笔 REND-21-C1 PCSS 落码条目）；v0.22（2026-11-24 补 cutout 阴影落码条目）；v0.21（2026-11-24 补阴影图可视化落码条目）；v0.20（2026-11-24 补 DDGI 探针可视化落码条目）
> 定位：`Engine_Roadmap.md` 的**分卷**（2026-11-08 拆分，同日由逐字流水压成索引）。总纲承载各期目标 / 拍板 / 验收标准 / 状态摘要；本文承载**批次索引**——哪个日期、做了哪一批、关键改动是什么、落点在哪个设计档、验收状态如何。
> 压缩口径：每条一行要点，**散文细节不再复述**（实现过程、逐文件枚举、方案论证）——它们归各设计档的**版本沿革**与 `Engine_Features.md` 的**验收状态行**（验收状态/日期全项目单点）。**只在台账出现过、别处无副本的要点**（各条挂靠关系、跨档散件、排障事实）保留在条目内与 §5/§6 归档。
> 维护口径：新批次的进度明细写本文（AGENTS 红线 12），`Engine_Roadmap.md` 只同步**状态摘要**与 §5 映射表。条目内「见 §x」指 `Engine_Roadmap.md` 同号节。
> 溯源：v0.1 逐字流水版见 git 历史（2026-11-08）；本卷每条均可在对应设计档版本段核到。
> 章节对应：**§2 二期已分卷**（`Engine_Roadmap_Progress_P2.md`，2026-11-15）、§3 三期散件、§7 四期（2026-11-13 起补批次）；**§1 一期（关账总结）无批次条目**；§4 排障基建、§5 六阶段映射、§6 已收口专项各自独立。

---

## 2. 二期：引擎功能——批次索引（2026-11-15 分卷）

> 本节（§2.3 Ray / §2.4 编辑器基本，共 40 条、约 27.4KB）超 AGENTS 红线 16 的单文件 50KB 上限，
> 内容已**逐字迁至分卷**；节号沿用原编号，与总纲对读。

| 分卷 | 承载 | 条目数 |
|------|------|--------|
| `Engine_Roadmap_Progress_P2.md` | §2.3 Ray + §2.4 编辑器基本 | 40 |

---

## 3. 三期：光照——散件台账

> 批次①②③④-A⑤的目标、拍板与实装形态明细内联在总纲 §3（四批内容 + 着色器配套口径）与各专档（`Engine_Lighting_Design.md` §9 / `Engine_Skybox_Design.md`），故本节只收不属五批主线的随批散件；**批次④-B（三期收官件）同步列此**。

- **2026-11-01｜随批两散件**：project.create.scene 创建场景命令 + 启动场景记忆写环（重启恢复上次场景） → `Editor_Menu_Design.md` / `Editor_Design.md` ｜✅ 2026-11-01 同日用户实测
- **2026-11-08｜三期批次④-B（Deferred 复用，三期收官件）**：GBufferPass（消费 `Pass GBuffer` 同名桶剔透明；三张 MRT = albedo RGBA8 / 世界法线 RGBA16F / 世界位置 RGBA32F+w 有效标志；复用本视口 depth）+ LightingPass（覆盖制全屏三角形；frag 带 `//@javelinLighting` 标记 → 导入期注入相机块+光照段函数+G-Buffer 三采样 = **与材质 Forward fs 同一 `javelinLighting` 常量**；背景按 worldPos.w<0.5 discard；无深度附件）+ set1 扩 binding5/6/7（G-Buffer 三采样 per-view，NEAREST/CLAMP）+ `pipelineFor` 按 shader Pass 名取颜色格式列表（`registerPassColorFormats`，"GBuffer" = 三张 MRT）+ 管线双 Pass 列表（`setProfile` 运行期切；`addPass/removePass` 两档同步——Gizmo/拾取/调试线切档不丢）+ `JavelinSetting.renderPipeline` 持久化 + 引擎启动消费 + **HYBRID 强制 1x**（MSAA-GBuffer 归四期）+ 编辑器 `tools.renderPipeline` 命令（运行期切换 + 写盘 Game/javelinSettings.settings）+ 内置 Lit.shader/default.shader 各补 `Pass GBuffer`（default 保 HYBRID 下紫色错误显性化）+ `ShaderImporterTest` 补 GBuffer pass 断言；**不引入 lightingRT/HDR/Composite**（四期第 1 项） → `Engine_RenderPipeline_Design.md`（§4.2/§4.3 实装形态、§5.1、§6.3、§8 步骤二）/ `Engine_Lighting_Design.md`（§9 批次④-B）/ `Engine_Material_Design.md` §4.2（`Pass GBuffer` 契约）/ `Engine_Uniform_Keys.md` §4（binding5/6/7 + 保留字）/ `Editor_Menu_Design.md` v0.21 ｜✅ 2026-11-10 用户实机验收（HYBRID 光照正常、两路一致；随验坑 Pitfalls #35——set0 阶段可见性 VERTEX-only 致 LightingPass fs 读 cam.view 读零全黑，修=ALL_GRAPHICS 超集）

---

## 4. 排障基建——散件台账

> 跨期支撑设施（`Engine_DebugTools_Plan.md` 批次），不属任一期能力主线；每批一行。

- **2026-11-12｜dbg-1 校验层消息回调（`Engine_DebugTools_Plan` §2 步骤一）**：`JavelinSetting.engineDebug`（原生 `boolean`、默认 true、启动期消费；序列化走既有协议**不新增接口**）+ `LaunchArgs` 启动参数持有类（`--debug=true|false` 命令行覆盖，四入口 main 首行 parse、`JavelinEngine.initialize` 实例创建前读取）+ 无 `VK_LAYER_KHRONOS_validation` 即 **fail-fast 不启动**（`VulkanInstance` 抛错含「装 SDK / 关 engineDebug」两条出路）+ `DebugUtilsMessenger` 回调（WARNING\|ERROR × VALIDATION\|GENERAL\|PERFORMANCE → logback `[VVL]` 前缀，回调对象/handle 存字段、dispose 先毁 messenger）+ 编辑器启动链异常弹错误框（红线 15） → `Engine_DebugTools_Plan.md` v0.2 §2.5 ｜✅ 2026-11-12 用户实测（启动正常、`logs/error/error.log` 零 ERROR；口径 §2.3 的「违约现形」同日实证——首跑五连报即据以定位坑 #37/#38）
- **2026-11-12｜dbg-1 首跑排障产出（两轮）：布局两条 + WSI 信号量一条**：dbg-1 首次把校验层消息接进日志即刷出五连报。**第一轮（坑 #37，同日重跑确认两条 09600 全消）**：`RenderGraphImpl.execute` backbuffer 起手布局 `PRESENT_SRC → UNDEFINED`（acquire 后真实态；谎报会让图首屏障声明成「保留内容的转换」）+ `VulkanDevice.uploadTexture` 拷完补 `TRANSFER_DST → SHADER_READ_ONLY` 回迁（原停在 TRANSFER_DST 直接采样，描述符却声明 SHADER_READ_ONLY）。**第二轮（坑 #38）**：acquire 信号量三连环（`non-acquired-swapchain-image-used` / `MissingAcquireWait` / `01286`）根因 = **LWJGL `VkSubmitInfo.pWaitSemaphores` 只写指针不写 `waitSemaphoreCount`**（该 count 与 `pWaitDstStageMask` 共用，生成器留给调用方；而 `npCommandBuffers`/`npSignalSemaphores` 都自动带）→ 驱动整条忽略 wait 列表，故显式补 `waitSemaphoreCount(n)`；同批把引擎全部 Vulkan「结构体 × 设置器」对核了一遍，仅此一处中招。首轮曾误判「外部诊断（submit 漏传 acquire 信号量）不成立」，实为现象属实、机制猜错（丢在结构体字段而非 Java 参数），方法论落 `Vulkan_Pitfalls.md` §六-8 → `Vulkan_Pitfalls.md` #37/#38 / `RHI_Design_Pipeline.md` §24.3 / `RHI_Implementation_Progress.md` API 记录 ｜✅ 2026-11-12 用户实测（重跑 `logs/error/error.log` 零 ERROR，三类错全消）；残留项进待办总账（RHI-10/11/12、BUG-09 泄漏）
- **2026-11-12｜dbg-2 G-Buffer 可视化调试视图（`Engine_DebugTools_Plan` §3 步骤二，**归属=编辑器**）**：**切换入口 = 顶部菜单「工具 | G-Buffer 视图」四项单选**（关闭/基色/世界法线/世界位置，`GBufferViewMenuCommand` 只写静态意图）+ F4 快捷循环；`GBufferViewPass`（覆盖制全屏贴图，内置 `builtin-asset-editor/debug/gbufferview.vert/.frag` 走 SpvShader 导入链、不带光照注入标记；**源内含标记字面量会误触注入撞名**——写注释也别写）+ push 常量 `int mode` 三选一 + 自建 set0 三采样器（不依赖 set1/set2）；`GBufferDebugAppState extends AbsEditorAppState`（`JavelinEditor.onInitialize` 装配链挂载、引擎帧消费意图挂/卸 Pass；仅 HYBRID 档——FORWARD 档菜单项置灰 + F4 提示、切走自动收起；Pass 惰性创建 + 装载失败捕获）；Pass/AppState/内置资产全迁 javelin-editor、**core 零改动** → `Engine_DebugTools_Plan.md` v0.3 §3.5 / `Engine_Features.md` §3.5 ｜✅ 2026-11-12 用户实测（口径 §3.4：HYBRID 档四态循环 / FORWARD 档菜单项置灰 + 提示行）；**随验修**：世界位置视图原「xyz 直传」在 UNORM 缓冲把负值钳成 0（负坐标全黑看不见）→ 改带符号线性重映射 `p/(2·RANGE)+0.5`（`RANGE` 常量默认 10，0=中灰、±外饱和）

---

## 5. 六阶段映射——批挂靠归档

> 总纲 §5 是映射表本体（含各期状态摘要）；本节补**批挂靠**（每批落哪期哪个阶段）。拆分前的映射表明细版与逐字流水版（v0.1）一并归 git 历史（2026-11-08）；其独有事实（检查器注册器 / 视口聚焦 / 三期散件）已折入 §2.4、§3 条目。

| 批次 | 挂靠 | 状态 |
|------|------|------|
| 资产热重载注册表流派（Walker 全量扫描 + 导入事件总线）、Texture2D 运行期可变 + revision、Material.setShader 保参 + textureRevision、logback 文件输出 | 二期「编辑器基本 + 资产管线」 | ✅ 2026-10-30 验收销账 |
| Cleaner Registration 合一（既有关账能力上的防御性重构） | 一期 RHI 底座 | ✅ 2026-10-30 验收销账 |
| 二进制序列化大数组 IO 修复（core serialize 基础设施，Importer/Exporter/ArrayOutput 三点） | 二期「资产格式层 + 资产管线」（随天空盒加载耗时暴露，非阶段能力变更） | ✅ 2026-11-07 用户实测销账 |
| 内置资源归属迁移步骤一（`builtin-asset-core` 迁回 javelin-core 资源树 + 索引分模块 + core 测试侧 editor 反向依赖摘除）+ 步骤二内嵌 spv 迁源码三件（pick / debug（含 `.chars` 字库资产面）/ debugline 全走 AssetManager 装载，javelin-tool 回归纯库） | 二期「资产格式层 + 资产管线」（构建/资源归属基建，非阶段能力变更） | ✅ 2026-11-07 用户实测销账 |
| 耗时操作指示器（编辑器侧「耗时反馈」基建，与导入进度框同族但不绑业务） | 二期「编辑器基本」 | ✅ 2026-11-08 用户确认验收（调用点接入为后续动作） |
| 导入期引用解析壳管理器 ShellAssetManager（资产基建修复） | 二期「资产格式层 + 资产管线」 | ✅ 2026-11-08 用户实测销账 |
| 环境设置组件 + 唯一性作用域 `@UniqueComponent`（组件模型基建：唯一性作用域 + 注解值 AOT 通道 / 天空盒载体迁移 / 创建菜单与检查器入口） | 一期「阶段② 场景系统」+ 三期批次⑤ + 二期「编辑器基本」 | ✅ 2026-11-08 用户实机验收 |
| 检查器组件增删（core 补 `Node.removeComponent` / `GameScene.onComponentDetached`，唯一性槽位释放） | 二期「编辑器基本」 | ✅ 2026-11-08 用户实测验收 |
| 检查器资产预览族（纹理面板预览行 / 图片检查器 .png·.jpg·.jpeg / cubemap 检查器 .hdr·.pano 十字预览 / 可缩放查看窗 `ImageViewerDialog`） | 二期「编辑器基本」 | ✅ 2026-11-11 用户实测验收 |
| 编辑器对话框收口 `swing/dialog` 包（六个对话框迁入，零行为变化） | 二期「编辑器基本」（结构重构，非能力变更） | ✅ 2026-11-11 用户验收（编译通过） |
| 检查器颜色行压扁修复（BUG-10，两轮：validate root 定案 + 分组/色块/组头竖直 minimum=pref 兜底 + 压扁自检日志） | 二期「编辑器基本」（UI 缺陷修复，非能力变更） | ✅ 2026-11-18 用户实机复测通过 |

---

## 6. 已收口专项归档

> 跨模块专项，别处无副本（构建/开源基建、文档结构治理，非阶段能力变更）。

- **groupId / 包名迁移 + 版本定版（2026-10-29 拍板 → 2026-10-31 用户实测销账）**：目标命名 `cn.donting.javelin`（包根与坐标对齐，artifactId 与目录名零改动，触发条件 = 开源 / Maven Central）；执行 = 用户 IDEA Shift+F6 主树重构，随批补齐 IDEA 未覆盖面（javelin-core test 树整树、字符串字面量 15 处、全部 build.gradle、project-template 坐标与 mainClass）+ doc 17 份 FQCN 替换；**用户游戏项目默认 groupId 占位符 = `com.javelin.game`**（与引擎坐标无关，非残留）；全仓机检 `com.javelin` 归零。**版本号终态 = `0.1.0-SNAPSHOT`**（沿革：`1.0-SNAPSHOT` → 当日反转定 `0.1.0` 固定无后缀 → 用户复盘改回 SNAPSHOT：开发期保持 SNAPSHOT、release 发布时才去后缀定版，Central 不收 SNAPSHOT 的约束移到发布批）；收 `gradle.properties` 单点（7 个 build.gradle 删 `version =` 继承根）。验收 = test 全绿 + mavenLocal 重发新坐标 + 三端实机（编辑器新建项目/打包）与改前一致。台账独有要点：MCP rename 实测不支持包级故手工迁移。
- **开源前置批（2026-10-29 起步 → 2026-10-31 落库）**：**License = MIT**（个人开发者作品，与 LWJGL BSD-3 / JOML MIT 生态一致）；THIRD-PARTY-NOTICES.md 十一项清单 + 思源黑体 OFL.txt 随字体同目录，**许可倒挂纠错 = FlatLaf 实为 Apache-2.0（jar 内 META-INF/LICENSE 实证，此前记 MIT 错）、JSVG = MIT**；README 双语落库（README.md 英文主档 + README.zh-CN.md 互链；用户指正两处已修：AOT 部署为核心目标须在定位段突出、环境要求写 **GraalVM for JDK 25** 而非裸 JDK）；**默认语言 en_US**（`lang/en_US.properties` editor.core 全量 50 键 + `I18n.DEFAULT_LANGUAGE` 切 "en_US"，zh_CN 降为可选本地化包）+ 首选项菜单与语言设置全链（file.preferences + 语言包注册制 + EditorSettings.language + 启动链 setLanguage，确定写盘 + 重启生效）；命名收尾 = GitHub 远端与 `rootProject.name` 去 "5"（本地目录名不动——改目录会断 IDE 工程引用 / gradle 缓存 / AGENTS 路径）；剩余 = 插件域待拍板 3 项（组件显示名 / 命令 id 前缀化 / FileType 冲突策略）。已知遗留呼应 = `./gradlew` 对 Windows cmd/PowerShell 不可用（根缺 gradlew.bat）、distributionUrl 仍走腾讯镜像。

- **文档结构治理（2026-11-08）**：`Engine_Roadmap.md` 涨到 72KB（单文件逼近不可读）——按「总纲 + 分卷」拆出本卷，并把逐字流水压成批次索引（54KB → 26KB）；同期立 AGENTS **红线 16**（进度 / 台账类文档单文件 ≤ 50KB，超限即分片，手法照本次：总纲留状态摘要 + 分卷索引表 + 指针，节号沿用原编号）。范围收窄 = 只约束随批次线性增长的进度文档，设计档 / 参考资料 / `Engine_Features.md` 功能清单不受此限（量由设计范围决定）。

- **待办总账建档（2026-11-11）**：新建 `doc/Engine_Backlog.md`——**「已计划、尚未完成」的唯一登记处**；起因 = 待办散在 30 份文档的「后置项 / 挂账 / 遗留 / 待拍板 / 已知边界」段，无单点可查。手法 = 全量清点（`doc/*.md` + `Engine_Features.md` + `AGENTS.md`）去重后按域收敛，**106 条**（DBG 6 / P4 9 / RHI 9 / REND 13 / AST 15 / HOT 13 / ED 12 / PLG 8 / SYS 9 / PICK 3 / BLD 1 / BUG 8），一项一行「ID｜待办｜落点｜归类｜依赖」，ID 建档一次分配、永久稳定、完成后留空档不复用；归类 = 未开工 / 半成品 / 后置项 / 待拍板 / 已知问题。**落点收敛三处**：`Engine_Features.md` §7 已知问题并入本文 §13（原处留指针）、`Engine_Roadmap.md` §6 跨期遗留改指向本文（同步更正两处残留——`environment/sun` 与 `.scene 序列化` 实已落地、「插件域待拍板」实剩 2 项，总纲升 v0.6）、`AGENTS.md` 文档索引登记新档（Features/Roadmap 两行描述同步）。维护纪律四条写入档内（开工改状态 / 完成整行移出进本卷批次索引 + Features 验收状态 / 设计档新写后置段须同批登记 / 拍板后去 `待拍板` 归类）；体量 24KB 在红线 16 限内。

---

## 7. 四期：高质量渲染——批次索引（2026-11-22 分卷）

> **分卷**：§7 批次明细已整体拆至 **`Engine_Roadmap_Progress_P4.md`**（红线 16：总卷 86KB 超 50KB 上限）。
> 本节只留分卷指针；四期状态摘要另见 `Engine_Roadmap.md` §4。新批次写分卷，不回本卷。
>
> 下面的一行索引按批次时序（原条目全量见分卷）：

- 2026-11-13｜后处理链步骤一（HDR 切换点 + Tonemap）+ 管线拆两个实现类 + MSAA 移除｜✅ 已验收
- 2026-11-13｜后处理链步骤二 Bloom（独立开关）｜✅ 已验收
- 2026-11-14｜管线与后处理解耦（零行为变化）｜✅ 已验收
- 2026-11-14｜项目设置窗口 + 后处理参数可配（对标 Unity Edit → Project Settings → Graphics）｜✅ 已验收
- 2026-11-14｜SSAO（四期施工序 ① = GI 第一步；方案 + 步骤一/二同日落码）｜✅ 已验收
- 2026-11-14｜阴影质量分级方案件（四期施工序 ②，未落码）｜⏳ 待验收
- 2026-11-14｜阴影质量分级 步骤一（一-A 品质档 PCF / 一-B bias 调参面 / 一-D 设置窗口；一-C per-object 未做）｜✅ 已验收
- 2026-11-14｜阴影质量分级 一-C（per-object `castShadow`/`receiveShadow`；步骤一全件关账）｜✅ 已验收
- 2026-11-14｜阴影质量分级 步骤二 CSM（P4-03 随此关账）｜✅ 已验收
- 2026-11-14｜渲染设置门面 `RenderSettings`（背景/后处理/阴影统一读写入口）｜⏳ 待验收
- 2026-11-15｜设置域重构（序列化缺键语义 + 设置对象分组 + 注解驱动设置窗 + 门面退役）｜✅ 已验收
- 2026-11-15｜Layer/Tag 体系重构 + 设置窗「图层/标签/场景」页落地｜✅ 已验收
- 2026-11-15｜TAA 方案件 + 步骤一落码（四期施工序 ③；未编译验证）｜✅ 已验收
- 2026-11-15｜TAA 步骤二·锐化 pass（P4-11；2026-11-15 验收）｜✅ 已验收
- 2026-11-15｜TAA 步骤二·相机级迁移（抗锯齿/锐化从项目设置搬到 Camera 组件；2026-11-15 验收）｜✅ 已验收
- 2026-11-15｜Inspector 条件子组 `@InsFieldGroup`（TAA 四项收进 `TaaSetting`；未编译验证）｜✅ 已验收
- 2026-11-15｜`@InsSlider` 滑条行 + 条件子组折叠组头（未编译验证）｜✅ 已验收
- 2026-11-16｜常量环境光（四期施工序 ④ P4-04 步骤一；2026-11-16 验收）｜✅ 已验收
- 2026-11-16｜非光照材质语义落码（四期施工序 ⑤ = P4-08；2026-11-16 验收）｜✅ 已验收
- 2026-11-16｜RHI 纹理种类底座 阶段一/二（⑥ 前置：3D 纹理 + 2D 纹理数组 + storage image 写口；未编译验证，验收推迟）｜⏳ 待验收
- 2026-11-16｜RHI 纹理种类底座 阶段三：跨帧布局记忆（RHI-13，⑥ 前置硬项；未编译验证）｜⏳ 待验收
- 2026-11-18｜CSM 纹素吸附修（相机平移阴影跳动，坑 #41）｜✅ 已验收
- 2026-11-18｜RHI 纹理底座 + 跨帧布局记忆 RHI-13 验收销账（⑥ 前置底座清零）｜✅ 已验收
- 2026-11-19｜体素化 AO（四期 P4-17 = ⑥ DDGI 前置轻量档）批 A~D 落码（未编译验证，验收推迟）｜⏳ 待验收
- 2026-11-19｜体素 AO 批 A~D 实机排障五轮（P4-17；链路全通，「整体均匀变暗」口径问题遗留）｜⏳ 待验收
- 2026-11-20｜阴影接触漏光修复（BUG-11，2026-11-21 用户实机验收通过）｜✅ 已验收
- 2026-11-20｜GI 路线拍板三条（跳过中间档 + 体素 AO 定位 + 环境项互斥；其中 ② 于同日 ③ 被推翻）｜（状态见分卷）
- 2026-11-20｜体素 AO（P4-17）整体退役、整链删除（同日 ③）｜✅ 已验收
- 2026-11-21｜SSAO 降噪重做（BUG-12，同日用户实机收口）｜✅ 已验收
- 2026-11-21｜RHI-15 + RHI-08 格式能力查询与创建期 fail-fast（L2 硬前置第一笔，同日用户实机验收通过）｜✅ 已验收
- 2026-11-21｜DDGI B1 数据面落码（P4-05，待验收）｜⏳ 待验收
- 2026-11-21｜DDGI B1 残留①（atlas GPU 资源）落码｜⏳ 待验收
- 2026-11-21｜DDGI B1 残留②（体素化重建）落码｜⏳ 待验收
- 2026-11-21｜B1 验收进度（部分通过）｜✅ 已验收
- 2026-11-21｜B1 关账（残留②实跑通过）｜✅ 已验收
- 2026-11-21｜DDGI B2 落码（追踪 + 更新两段 compute + 宿主 Pass）｜⏳ 待验收
- 2026-11-21｜B2 首实跑（两轮）与三处收口｜⏳ 待验收
- 2026-11-21｜DDGI B3 前置：探针网格按体素场收缩（B2 第三轮实测暴露，同日收口）｜✅ 已验收
- 2026-11-21｜DDGI 诊断口径 v2（第三轮读数被判不可用）｜✅ 已验收
- 2026-11-21｜`engineDebug` 默认改 false（dbg-1 口径调整）｜⏳ 待验收
- 2026-11-21｜DDGI B3 消费面落码（环境项接管，P4-05）｜⏳ 待验收
- 2026-11-21｜B3 首跑排障（过亮 / 闪 / 画面不对 → 四条修法）｜⏳ 待验收
- 2026-11-21｜B3 第二轮（重导修生效后仍无画面变化 → 消费面没接管）｜（状态见分卷）
- 2026-11-22｜B3 第三轮：消费面接管成功，阴影过亮 → 补太阳可见性｜⏳ 待验收
- 2026-11-22｜B3 终态：直射项体素 sunVisible → CSM 阴影图 + 描述符池归还（P4-05）｜⏳ 待验收
- 2026-11-21｜构建侧修：toolchain 钉死 GraalVM 25.0.3（IDEA 主类启动失败）｜（状态见分卷）
- 2026-11-23｜出「DDGI 近景窗口」方案件（低优先级后置，不排入 §4 施工序）：探针块已随相机、但体素层仍是整世界单张 N³（200m 场景默认档 1.56m 体素，薄构件从 GI 消失）→ 方案 = 64m 滚动窗口 + 8m chunk 位移 + 乒乓拷贝与 slab 增量体素化 + 探针 chunk 吸附与位移重投影 + `MAX_RAY_DISTANCE` 40m；§10 记动态物体（角色）现状 = 角色被并入静态集致体素图冻结、停下才整场重建，口径定「只接收 GI、发射与遮挡交 rayQuery」 → `doc/Engine_DDGI_NearField_Design.md` v0.2 ｜📌 后置方案件（未排期）
- 2026-11-24｜DDGI 探针可视化落码（DBG-07 / dbg-3，L3 验收依赖件；用户「先做可视化、验收靠后」）：新档 `Engine_DDGI_ProbeViz_Design.md` v0.1 方案 → v0.2 落码——三视图 = 探针网格盒 + 三轴十字（世界空间 `DebugDrawLine`，由 `DebugLineAppState` 在每帧 clear 后统一代提交，超 2000 十字按整数步长降采样）+ atlas 直显（`DdgiProbeViewPass` 全屏三角、push `{mode, scale}`、自建 set0 单采样器，`texelFetch` 1:1 + tile 边框「未扫红 / 已扫暗灰」+ 溢出洋红，零 CPU 回读）+ `DebugText` 第 11 行数值行（网格/atlas 尺寸/首圈/十字 1/N/每帧探针数）；入口 = 菜单「工具 | DDGI 探针视图」四项 + F5 循环，未就绪置灰；core 只加只读 `DdgiProbePass.grid()/atlases()` + `JavelinEngine.ddgiProbePass()`（`MAX_RAY_DISTANCE` 提 public）；新内置资产 `builtin-asset-editor/debug/ddgiView.frag`(+`.meta`) 与 test-project 副本 → `doc/Engine_DDGI_ProbeViz_Design.md` v0.2 ｜⏳ 待验收（未编译；`ddgiView.frag` 需重导出 SPIR-V 产物后 atlas 视图才可点亮）
- 2026-11-24｜阴影图可视化落码（**DBG-03**，L4 阴影补强前的调参眼；路线 §4.1 贯穿项要求）：`ShadowMapViewPass`（覆盖制全屏三角、push `{mode, grid}`、set0 非比较 NEAREST 单采样器）+ `shadowview.frag`（原始深度灰度 / 2×2 象限级联分色 + 遮挡体压暗 + 象限分隔线）+ `ShadowMapDebugAppState`（F6 循环、`DebugText` 行 12 报级联数/版面/边长/主光投影、未就绪不进图）+ 菜单「工具 | 阴影图视图」三项；**不走深度比较采样**（比较采样只给 0/1 可见性、看不出纹素吸附与漏光，改直读深度值）；顶点复用 `gbufferview.vert`，core 零改动 → `doc/Engine_DebugTools_Plan.md` v0.4 §3.6 ｜⏳ 待验收（未编译；`shadowview.frag` 需重导出）
- 2026-11-25｜**L4 第二笔 REND-21-C1 PCSS 落码**（方案件 `Engine_Shadow_PCSS_Design.md` v0.1 → v0.2；C 与 B 对调经用户认可，五点全默认）：`ShadowSettings.softness`（缺省 1.0 / 0 = 关，注解滑块 + 双语）经 `shadowParams2.w` 进 UBO（**步长 768 不变**，该位原恒 0 无消费方）；set1 新增 **binding11 `shadowMapRaw`**（同一张 atlas + NEAREST 非比较采样器，`updateLightingSet` 与 atlas 换代重指两处同步写）；注入段新增 `javelinPcssBlockerDepth`（黄金角螺旋 24 上限/16-24 tap）→ `javelinCascadeShadow` 内 `penumbra = (refZ-blockerZ) * softness * atlasSize * 0.05`、`kernelRadius = clamp(…, pcfRadius, pcfRadius×4)`，两档 PCF 偏移量改乘 `kernelRadius`，`softness=0` 逐像素回退旧路径 → `doc/Engine_Shadow_PCSS_Design.md` v0.2 §5/§9 ｜⏳ 待验收（未编译；**注入段改动 = 全部 `.shader` 与内置 `lighting.frag` 强制重导**）；C2 接触阴影挂 Backlog（需相机深度附件加 `SAMPLED`）
- 2026-11-25｜**光追 B1 能力启用落码**（用户：实际测试显卡是 RTX，780M 不作判据）：`VulkanDevice` 建设备前 `vkGetPhysicalDeviceFeatures2` 探 AS / rayQuery / BDA 三组特性，按「扩展在列 **且** 位为 true」拼装启用列表（`deferred_host_operations` → `acceleration_structure` → `ray_query`/`buffer_device_address`，AS 缺失整组降级）；特性链另建一套结构（探测那套用完即弃）；`Device.rayQueryEnabled()` 报最终态（与 `supportsRayQuery()` 的「只报驱动能力」分工）；`BufferUsage` 加 `SHADER_DEVICE_ADDRESS`/AS 存储/AS 构建输入并对映 VK 标志；启动日志改报四态。**能力可选不阻断启动**，B2 建 AS 前查 `rayQueryEnabled()` 缺失即抛错；B2 另须补 `VulkanMemory` 的 `VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT` 路径 → `doc/Engine_RayTracing_Plan.md` v0.2 §3.1 ｜⏳ 待验收（未编译；需 RTX 机器跑）

- 2026-11-25｜**光追 B2a 落码（BLAS + 设备地址）**（用户：「后面验证，继续写代码」）：`VulkanMemory.allocate(..., deviceAddress)` 挂 `VkMemoryAllocateFlagsInfo` + `VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT`（补掉 B1 记的缺口）；`Buffer.deviceAddress()` + `VulkanBuffer` 按 `SHADER_DEVICE_ADDRESS` 用途决定是否带址分配、bind 后 `vkGetBufferDeviceAddress` 取值；`CommandBuffer.handle()` 逃生口 + `buildBlas`（只发 `vkCmdBuildAccelerationStructuresKHR`，几何声明由 `VkBlasGeometry` 装配、尺寸查询与构建共用）；`VulkanPhysicalDevice.accelerationStructureScratchAlignment()`（`VkPhysicalDeviceProperties2` + AS 属性链取 `minAccelerationStructureScratchOffsetAlignment`）；新增 `AccelerationStructure`/`BlasDesc`/`VulkanAccelerationStructure`（尺寸查询→device-local 存储缓冲→建 AS→对齐 scratch→一次提交+fence→取地址，失败回收、显式 dispose 不进 Cleaner）；`Device.createBlas` + `VulkanDevice` 实现（`rayQueryEnabled()` false 即 `IllegalStateException`）；core 侧 `Mesh.blas(device)` 惰性单条 BLAS 缓存 + `disposeBlas()`，`MeshUpload` 顶点/索引补 `SHADER_DEVICE_ADDRESS` 用途 → `doc/Engine_RayTracing_Plan.md` v0.3 §3.2 ｜⏳ 待验收（未编译；B2b = TLAS 与 GPU 侧屏障待做）
- 2026-11-25｜**光追 B2b 落码（TLAS + 构建后屏障 + 场景装配）**（用户「嗯」承接 B2a）：①`VkBlasGeometry` 更名 `VkAsGeometry` 并扩 TLAS 三件（实例几何 `INSTANCES_KHR`/`arrayOfPointers=false`、`TOP_LEVEL` 构建信息、共用 `range(primitiveCount)`）+ `buildToReadBarrier`（`ACCELERATION_STRUCTURE_BUILD` 写 → 顶点/片元/计算读），`buildBlas` 同批补屏障；②`TlasInstance`（64B 记录：3×4 世界变换 + BLAS 地址 + 24 位自定义索引 + 8 位掩码 + `FORCE_OPAQUE` 默开）；③`Tlas`/`VulkanTlas`（固定容量：host-visible 实例缓冲带设备地址 + AS 存储 + 预分配对齐 scratch，`build(cmd, instances)` 写实例 + 记一次重建 + 屏障）与 `CommandBuffer.buildTlas`、`Device.createTlas`；④core `RayTracingScene`（`RenderObject` → `Mesh.hasTriangles()` 筛 → `mesh.blas()` → 身份表复用 `TlasInstance` → 容量 `max(16, 2^ceil)` 重建 → build；`customIndex` 稳定、`objectAt` 反查）+ `RenderingSystem` 持有/访问器/close 释放 → `doc/Engine_RayTracing_Plan.md` v0.4 §3.3 ｜⏳ 待验收（未编译；实例缓冲单份不双缓冲、BLAS 无自动失效钩子、`build` 尚无调用方——B3 以 RenderGraph Pass 接入）
- 2026-11-25｜**光追 B3 落码（求交直显，F9）**：RHI 加速结构描述符通路 = `DescriptorType.ACCELERATION_STRUCTURE` + 布局映射 `VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR` + `DescriptorUpdate.accelerationStructure(binding, Tlas)`（`VulkanDescriptorSet` 走 `VkWriteDescriptorSetAccelerationStructureKHR` pNext）+ 描述符池 AS 档 64；`RayTracingScene.tlasDescriptorSet()`（set0 binding0，TLAS 换代自动重指，dispose 一并释放）；新增两资产 `editor/debug/raytrace.compute`（半分辨率相机主射线：`invViewProj` 反投影方向 → `rayQueryInitializeEXT`（Opaque/mask 0xFF/tMin 0.05/tMax=farZ）→ `while(rayQueryProceedEXT)` → 命中 t/tMax、miss −1 写 r32f）与 `raytraceview.frag`（近蓝远橙渐变）；编辑器 `RayTraceViewPass`（两段图：compute 回调内先 `scene.build(cmd, renderers)` 再绑集派发 8×8，尺寸变经 `retireResource` 延迟换代；全屏大三角直显）+ `RayTraceDebugAppState`（F9、第 14 行实例数/TLAS 容量、未就绪不进图）与 `JavelinEditor` 挂载、语言包 4 键 → `doc/Engine_RayTracing_Plan.md` v0.5 §3.4 / `doc/Engine_DebugTools_Plan.md` v0.6 §3.8 ｜⏳ 待验收（未编译；**两个新着色器需重导出 SPIR-V**；首批用相机主射线，主光方向求交与 CSM 替换/混合后置）
- 2026-11-25｜**主线转光追（L6 / P4-06）· B0 能力探针落码 + L5 四项暂缓拍板**（用户：「这个都是后期的，主线还是往光追推进」）：①**暂缓** = 曝光测光（卡 RHI-14 mip 链）/ 色彩分级 LUT（卡 REND-03 口径）/ DOF（要深度）/ 体积雾（raymarch+降噪）/ 环境雾，`Engine_Environment_Fog_Design.md` 标 v0.0 留档不开工；②**B0** = `VulkanPhysicalDevice.extensions()`（`vkEnumerateDeviceExtensionProperties` 懒算一次）+ `hasExtension(String)`、`Device` 新增 `hasDeviceExtension(String)` / `supportsRayQuery()`（扩展在列 **且** `VkPhysicalDeviceRayQueryFeaturesKHR.rayQuery` 为 true——部分驱动上报扩展却关位）、`JavelinEngine` 启动打一行 `光追能力探针: rayQuery/accelerationStructure/bufferDeviceAddress`；**纯探测**：不启用扩展、不建 AS、不改 `BufferUsage`、不动着色器 → `doc/Engine_RayTracing_Plan.md` v0.1（B0~B3 批次：B1 启用 / B2 BLAS+TLAS / B3 rayQuery 求交可视化）｜⏳ 待验收（未编译；本机 780M 是否支持 ray query 待日志判）

- 2026-11-25｜**L4 第四笔 REND-21-B 逐级联剔除落码**（纯性能项，此前 CSM 四级联各画一遍全量 caster）：`ShadowPass` 级联循环内加 `visibleInCascade`——`Mesh.getBounds()` 本地盒 → `Aabb.transform(ro.world())` 世界盒 → `Aabb.transform(lightViewProj)` 光裁剪盒（正交无除 w），判 x/y 区间重叠且 `min.z <= 1`（未整体越过远平面）；**近平面侧不裁**（光与级联之间的遮挡体影子仍会投进来，裁了漏光），无包围盒网格恒画；`cascadeMat`/`worldBox`/`clipBox` 复用零分配，首帧打 `末级联存活 k/total`。**不引入 JOML `FrustumIntersection`**（其平面按 z∈[-1,1] 约定，与引擎 zZeroToOne 不符），零新依赖；主视图剔除仍属 REND-01 → `doc/Engine_Shadow_CascadeCull_Design.md` v0.1 ｜⏳ 待验收（未编译；纯 CPU 侧逻辑，着色器与绑定零改动）

- 2026-11-25｜**L4 第三笔 REND-21-C2 接触阴影落码**（方案件 `Engine_Shadow_Contact_Design.md` v0.1 → v0.2；五点全默认）：**推翻旧前置**——原记录「必须先给 `depthFor` 加 `SAMPLED` + binding12」，勘察发现 `ssao.frag` 早已用 `cam.viewProj` 反投影世界位置，G-Buffer 世界位置（binding7）在 LightingPass 本就可读，故本批**零 RHI 改动、零新槽位**；`ShadowSettings.contactShadows`（0~1 强度，缺省 0.5）经 `LightingParamsBlock` 尾部 `vec4 contactParams`（@656，块 656→672，stride 768 不变）；注入段新增 `javelinContactShadow`（朝主光 10 步 / tMax 0.35 / 命中率 × 强度 ×2）+ `javelinLightingEx(..., contactShadow)` 重载只乘主光直射项，**仅 `lighting.frag` 调用**（Forward 三参版本系数恒 1 → HYBRID 限定）→ `doc/Engine_Shadow_Contact_Design.md` v0.2 §4/§7 ｜⏳ 待验收（未编译；`lighting.frag` 需重导，注入段改动仍需全量重导）

- 2026-11-25｜**REND-13 光照 gizmo 落码**（路线 §4.1 贯穿项「L4 开工前先做一版可视化」，接在 DBG-03 之后）：新 `LightGizmoAppState`（**全场景灯、不走选中门控**）每帧经 `DebugLineAppState` 统一代提交线框——方向光 = 沿节点 +Z 行进方向箭头（与 `SceneRenderBridge.worldDir` 同口径）、点光 = range 三轴大圆、聚光 = 外角四母线 + 末端圆，灯色向白拉 40% 保暗场景可见、选中灯白线叠画、`DebugText` 第 13 行报灯数；入口 = 菜单「工具 \| 光照 gizmo」关闭/显示 + **F7 边沿循环**；配套 `LightGizmoMenuCommand` 新建、`EditorMenuSetup` 注册与子菜单、`JavelinEditor` attach、zh/en `tools.lightGizmo*` 四键 → `doc/Engine_DebugTools_Plan.md` v0.5 §3.7 ｜⏳ 待验收（未编译；纯编辑器线框，不动着色器与 RHI）

- 2026-11-24｜**L4 首笔 REND-21-A cutout/alpha-test 阴影落码**（用户「动手」= 方案 §4 五点全默认）：语义 = 材质 shader 属性 `_AlphaCutoff`（`Range(0,1)` 缺省 0 = 关，不动 `SurfaceType`）；阴影侧 = `ShadowPass` 首获专属 set0（binding0 = 材质 `_MainTex`，每材质描述符集按贴图版本重建、旧集两帧退役、不裁剪共用 1×1 白图集）+ push 128→144B（`cutoff@128` / `hasAlbedoTex@132`）+ `shadow.vert` 补 `aUV`→`vUV` + `shadow.frag` 空体改按阈值 `discard`（无 UV0 网格告警一次后按不裁剪）；主渲染侧 = `ShaderInjector` 新增 fs 注入助手 `javelinAlphaClip(vec4, float)`，内置 `Lit.shader` Forward/GBuffer 两条 fs 各接一行、`Properties` 加 `_AlphaCutoff`；RHI 新增 `Device.maxPushConstantsSize()`（`ShadowPass` 构造期超限抛错，红线 15）→ `doc/Engine_Shadow_Cutout_Design.md` v0.2 §4/§9 ｜⏳ 待验收（未编译；`Lit.shader` 与 `shadow.vert/.frag` 需 reimport；`DdgiVoxelizer` 不吃 alpha 记后置）

---

> （注：部分内容可能由 AI 生成）

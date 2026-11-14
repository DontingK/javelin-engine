# 编辑器项目设置设计（Editor Project Settings Design）

> 版本：v0.2（2026-11-15，注解驱动 + 双页 + 编辑/打包分流；**代码待实机验收**）
> 日期：2026-11-15
> 职责边界：
> - 本档管**「项目设置」窗口**（对标 Unity `Edit → Project Settings → Graphics → HDRP Global Settings`）——窗口形态、页面声明面、设置项的**即时/重启/只写不读三档生效口径**、与首选项的边界；
> - 各设置项本身的**语义**归对应设计档（后处理参数 = `Engine_PostProcess_Design.md` §3.7/§9.8；清屏色 = `Engine_Window_Design.md` / `Engine_RenderPipeline_Design.md`；SSAO 参数 = `Engine_SSAO_Design.md`；阴影 = `Engine_Shadow_Quality_Design.md`）；
> - 菜单域组织归 `Editor_Menu_Design.md`；Swing 面板/对话框框架归 `Editor_Swing_Design.md`。

> **v0.2 变更（2026-11-15）**
> - **页面声明改注解驱动**：`ProjectGraphicsSchema`（命令式链式声明）退场——`SettingsSchema.pagesOf` 扫设置根上类型贴 `@Setting` 的字段派生页清单，页内按 `@SettingGroup` / `@InsField` / `@SettingHint` / `@SettingSelect` / `@SettingRestart` 展开成「分组 → 行」。**加一页 = 加一个 `@Setting` 设置类 + 设置根上一个字段**，窗口/写盘/启动读盘都不用改。
> - **双页**：图形（`GraphSetting`）/ 窗口（`WindowSetting`）。导航序 = 设置根字段声明序。
> - **编辑与打包分流**：窗口页 `@Setting(editorStartup = false)` → 页根宿主是**项目文件那一份**，编辑只落文件、**不影响编辑器活实例**（§4/§5）。
> - **表单布局修**：容器实现 `Scrollable`（宽度跟随视口）+ 说明行 HTML 软换行——长提示不再逼出横向滚动条（照 `InsScrollablePanel` 先例，见 §2）。

---

## 1. 定位

Unity 把「全局渲染设置」放在 `Edit → Project Settings → Graphics`（HDRP 下即 HDRP Global Settings）。本引擎需要一个同类窗口，承载**项目级**（随项目走、非编辑器个人偏好）的设置。

**与首选项（`file.preferences` / `PreferencesDialog`）的边界**（照 Unity 的 Preferences vs Project Settings）：

| 维度 | 首选项（Preferences） | 项目设置（Project Settings） |
|------|----------------------|------------------------------|
| 归属 | 编辑器个人偏好（跨项目） | 项目级（随项目走） |
| 落点 | `~/.javelin/editor.settings`（编辑器侧 `EditorSettings`） | `<project>/Game/javelinSettings.settings`（引擎侧 `JavelinSetting`） |
| 作用域 | 编辑器本体 | 引擎渲染行为 / 打包运行时窗口配置 |
| 示例 | 界面语言、新建项目默认目录 | 后处理开关/参数、背景清屏色、管线档位、打包窗口尺寸 |

---

## 2. 窗口形态

- **非模态**（`JDialog` MODELLESS，照 `ImageViewerDialog`）：可停留、**边调边看视口**；单实例——再点「项目设置…」关旧窗重建（保证回显最新值）。
- **布局**：左栏分类导航（`JList`）+ 右栏内容（`CardLayout`），分栏用 `JSplitPane`（`ProjectPanel` 先例）。**导航项与卡片由页清单生成**（§3），不再硬编码。
- **紧凑度**：行间距守 AGENTS ≤2px；label 列 120px（Inspector label/控件列 4px 间隔同口径）；控件复用检查器叶子件（`ColorField` / `JeNumberInput` / `JeSelect`）。
- **表单容器 = `Scrollable`**（`SettingsFormPanel.FormPane`）：**宽度跟随视口**（`getScrollableTracksViewportWidth() = true`）、高度按 preferred。
  - 理由（照 `InsScrollablePanel` 2026-10-18 实测）：普通 `JPanel` 作滚动视图时宽度由内容首选宽决定——长说明行会把表单撑过视口，逼出水平滚动条。
  - 说明行（`HintLabel`）在此基础上按可用宽度做 **HTML 软换行**（`JLabel` 自身不换行，长文案会撑宽或被裁）；高度钉在首选高度（宽度才放开），否则纵向富余空间会被 `BoxLayout` 摊给提示行。

---

## 3. 页面（注解驱动）

**页发现**：`SettingsSchema.pagesOf(JavelinSetting)` 扫设置根（`JavelinSetting`）里**类型贴 `@Setting` 的字段**，声明序即导航序。首个字段是 `graphSettings` → 「图形」为首页。

### 3.1 图形页（页根 `GraphSetting`）

页根字段声明序 = 分组序（`@SettingGroup`：对象 = 整组；标量 = 由此开组）。

| 分组 | 页根字段 | 行 | 生效 |
|------|---------|----|------|
| 渲染管线 | `renderPipeline`（`@SettingRestart` + `@SettingHint`） | 只读下拉（FORWARD / HYBRID） | **重启**（编辑器固定 HYBRID；该值供打包运行时启动期选实现类） |
| 背景 | `backgroundColor` | `ColorField` | **即时**（`JavelinEngine.applyRenderSettings` → 管线清屏 sink + 后处理节点清屏色） |
| 后处理 | `postProcessSettings` | Tonemap 开关 / Bloom 开关 / Bloom 阈值·软膝·强度 / Tonemap 肩部 | **即时**（`PostProcessSetup` push 常量） |
| 环境光遮蔽 | `ssaoSettings` | **开关** + 半径 / 深度偏移 / 强度 | **即时**（仅 HYBRID 档；FORWARD 忽略并 warn） |
| 阴影 | `shadowSettings` | 品质档 / 强度 / PCF 半径 / 参考深度偏移 / bias 常量·斜率（`@SettingHint`）/ 级联数（`@SettingSelect` 1/2/4）/ 阴影距离 / 级联切分（`@SettingHint`） | 品质档等每帧打包进 UBO；bias 两项为 PSO 静态态（下一帧重建） |

> **SSAO 独立成组（2026-11-15 迁入 `GraphSetting`）**：开关与参数同居 `SsaoSettings`（组自持开关），原「后处理组内展平 + 开关在后处理」的形态作废。

### 3.2 窗口页（页根 `WindowSetting`，`editorStartup = false`）

整页**只写不读**：值是打包运行时的窗口配置，编辑器启动不跟随（编辑器恒关 vsync + 144 限帧，见 §4）。

| 行 | 控件 | 生效 |
|----|------|------|
| 宽 / 高 / 标题 / 帧率上限 | 数字 / 文本 / 数字 | 只落项目文件（打包运行时启动期消费）；**不作用于编辑器** |
| vsync（`@SettingRestart` + `@SettingHint`） | 只读 | 启动期定型（swapchain 已建）；不写文件（保留文件原值） |

> 页根上未贴 `@SettingGroup` 的标量归入**页面默认组**（组标题 = `@Setting.label` 的页名键）——窗口页即此形态。

---

## 4. 生效口径（三档）

- **即时**：后处理开关/参数、SSAO 开关/参数经 `PostProcessSetup` push 常量下发；背景清屏色经 `JavelinPipeline.setClearColor` + `PostProcessSetup.setClearColor` 双 sink 下发。改动当帧/下一帧可见。
- **重启**：`@SettingRestart` 字段（管线档位、vsync）——**只读展示 + 不入持久化清单**，标注「重启生效」。
- **只写不读（打包配置）**：`@Setting(editorStartup = false)` 的页——编辑落项目文件，编辑器运行期与启动读盘都不碰（§5）。

参数从「着色器内常量」改为「push 常量」是即时可调的前提（`Engine_PostProcess_Design.md` §9.8）。**下发单点 = `JavelinEngine.applyRenderSettings()`**（静态门面 `RenderSettings` 已于 2026-11-15 退役，见 `Engine_Rendering_Design.md` §11）。

---

## 5. 数据流与真值源

- **真值源 = 项目文件** `<project>/Game/javelinSettings.settings`（`JavelinSetting`）。
- **页绑定分流**（`SettingsSchema.pagesOf(settingsRoot, projectRoot)`）：
  - `editorStartup = true` 的页（图形）→ 页根绑**编辑器活实例**（`editor.setting()`），改动即时生效并进文件；
  - `editorStartup = false` 的页（窗口）→ 页根绑**项目文件实例**（开窗时 import 一份），编辑只改这份、关窗并回文件，活实例不受影响。
- **窗口改动**：即时下发（仅活实例页）+ **关窗时**逐页 load-modify-save 写回文件（每页只覆写 `managedRoots()` 里自己管理的页根字段，其余原样保留）——写盘收口在关窗，避免数字框「键入即提交」/拖动调值逐次落盘。
- **编辑器启动**：`ProjectLoadingFrame.loadEditorSetting` 逐页按 `managedRoots()` 读入，**跳过 `editorStartup = false` 的页**；文件缺失/损坏回退默认（损坏 warn + 回退）。
- **打包运行时**：`GameMain`/`AotGameMain` 读同一文件（`SettingManager.loadJavelinSettings`）——图形页与窗口页的值都生效。

> **旧档迁移**：`JavelinSetting.read` 以「缺嵌套键 ⇒ 旧档」为判据，把根上的 `backgroundColor` / `renderPipeline` / `postProcessSettings` / `shadowSettings` 与窗口五项一次性迁入 `GraphSetting` / `WindowSetting`；SSAO 的旧嵌套位置（`postProcessSettings.ssaoEnabled` / `.ssao.*`）经 `PostProcessSettings` 的一次性交接通道迁入独立 SSAO 组。

---

## 6. 注解面（设置声明）

| 注解 | 目标 | 语义 |
|------|------|------|
| `@Setting(label, editorStartup)` | TYPE | 页根：`label` = 导航项 i18n 键（页名兼页面默认组标题）；`editorStartup=false` = 只写不读 |
| `@SettingGroup(label)` | FIELD | 分组：对象字段 = 整组；标量字段 = 由此开组（本行为组内首行） |
| `@InsField(label)` | FIELD | 行闸门 + 行标签 i18n 键（未贴 `@SettingGroup` 的标量归当前组/页面默认组） |
| `@SettingHint(value)` | FIELD | 本行**下方**一行说明（i18n 键）：解释生效时机/静态态/取值含义 |
| `@SettingSelect(values, labelPrefix)` | FIELD | int 离散值 → 下拉（值串 = 值、标签 = `labelPrefix + "." + 值`）；回写仍走原 int setter |
| `@SettingRestart` | FIELD | 启动期定型：行置只读 + **不入持久化清单** |

**展开规则**：页根字段声明序 = 分组/行序；嵌套设置对象递归展平（深度 ≤4）；已贴注解但既不可映射也非设置对象 → 抛错（显式意图不静默吞）。

**持久化清单**：只登记**页根那一层**的可写字段——嵌套设置对象登记对象名本身（其内部字段不在页根上有 getter），组内全只读则不登记。

---

## 7. 入口（菜单）

新增**编辑域**（Unity 对齐）：`Edit → Project Settings…`（命令 id `edit.projectSettings`，`ProjectSettingsMenuCommand`）。顶栏域顺序 = file → edit → tools → build（Unity 的 File→Edit 序）。详见 `Editor_Menu_Design.md`。

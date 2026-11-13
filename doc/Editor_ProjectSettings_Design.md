# 编辑器项目设置设计（Editor Project Settings Design）

> 版本：v0.1（2026-11-14 落地，用户实机验收通过）
> 日期：2026-11-14
> 职责边界：
> - 本档管**「项目设置」窗口**（对标 Unity `Edit → Project Settings → Graphics → HDRP Global Settings`）——窗口形态、分类骨架、设置项的**即时/重启两档生效口径**、与首选项的边界；
> - 各设置项本身的**语义**归对应设计档（后处理参数 = `Engine_PostProcess_Design.md` §3.7/§9.8；清屏色 = `Engine_Window_Design.md` / `Engine_RenderPipeline_Design.md`）；
> - 菜单域组织归 `Editor_Menu_Design.md`；Swing 面板/对话框框架归 `Editor_Swing_Design.md`。

---

## 1. 定位

Unity 把「全局渲染设置」放在 `Edit → Project Settings → Graphics`（HDRP 下即 HDRP Global Settings）。本引擎需要一个同类窗口，承载**项目级**（随项目走、非编辑器个人偏好）的设置。

**与首选项（`file.preferences` / `PreferencesDialog`）的边界**（照 Unity 的 Preferences vs Project Settings）：

| 维度 | 首选项（Preferences） | 项目设置（Project Settings） |
|------|----------------------|------------------------------|
| 归属 | 编辑器个人偏好（跨项目） | 项目级（随项目走） |
| 落点 | `~/.javelin/editor.settings`（编辑器侧 `EditorSettings`） | `<project>/Game/javelinSettings.settings`（引擎侧 `JavelinSetting`） |
| 作用域 | 编辑器本体 | 引擎渲染行为（打包运行时同读） |
| 示例 | 界面语言、新建项目默认目录 | 后处理开关/参数、背景清屏色、管线档位 |

---

## 2. 窗口形态

- **非模态**（`JDialog` MODELLESS，照 `ImageViewerDialog`）：可停留、**边调边看视口**；单实例——再点「项目设置…」关旧窗重建（保证回显最新值，照 `ImageViewerDialog.show`）。
- **布局**：左栏分类导航（`JList`）+ 右栏内容（`CardLayout`），分栏用 `JSplitPane`（`ProjectPanel` 先例）。首版只填一栏**「图形」**，骨架预留多分类（运行时/物理/输入…后续按需扩）。
- **紧凑度**：行间距守 AGENTS ≤2px；label 列 120px（Inspector label/控件列 4px 间隔同口径）；控件复用检查器叶子件（`ColorField` / `JeNumberInput`）。

---

## 3. 首版页面：「图形 | 后处理」

| 分组 | 项 | 控件 | 生效 |
|------|----|------|------|
| 渲染管线 | 管线档位 | 只读文本 | **重启**（编辑器固定混合；该值供打包运行时启动期选实现类） |
| — | 背景清屏色 | `ColorField` | **即时**（管线清屏 sink + 后处理节点清屏色） |
| 后处理 | 后处理（HDR Tonemap）开关 | `JCheckBox` | **即时**（`PostProcessSetup.setEnabled(chain, TONEMAP, on)`） |
| — | 泛光（Bloom）开关 | `JCheckBox` | **即时**（`PostProcessSetup.setEnabled(chain, BLOOM, on)`） |
| — | Bloom 阈值 / 软过渡 / 强度 | `JeNumberInput` | **即时**（`PostProcessSetup.setBloomParams` → push 常量） |
| — | Tonemap 肩部 | `JeNumberInput` | **即时**（`PostProcessSetup.setTonemapKnee` → push 常量） |

---

## 4. 生效口径（两档）

- **即时**：后处理开关/参数经 `PostProcessSetup` 下发到运行中的 `BloomPass`/`TonemapPass`（push 常量，录制回调按当前值推）；背景清屏色经 `RenderPipeline.setClearColor`（管线清屏 sink）+ `PostProcessSetup.setClearColor`（后处理节点）下发。改动当帧/下一帧可见。
- **重启**：管线档位、窗口尺寸/标题、vsync、maxFps、engineDebug 等**启动期定型**字段——本窗口只读展示（或后续另页），标「重启生效」。

参数从「着色器内常量」改为「push 常量」是即时可调的前提（`Engine_PostProcess_Design.md` §9.8）。

---

## 5. 数据流与真值源

- **真值源 = 项目文件** `<project>/Game/javelinSettings.settings`：
  - **编辑器启动**：`ProjectLoadingFrame.loadEditorSetting` 读该文件的**图形/后处理字段**（背景色、后处理/Bloom 开关、后处理参数）入编辑器设置；其余字段（窗口尺寸/标题、vsync、maxFps、engineDebug、管线档位）是编辑器运行环境或打包期的事，**不随项目文件**（编辑器固定 HYBRID、恒关 vsync）。
  - **窗口改动**：即时下发到运行中的引擎（见 §4）+ 更新 `editor.setting()`；**关窗时** load-modify-save 写回该文件（只覆写本页字段，保留文件里其余字段）——写盘收口在关窗，避免数字框「键入即提交」/拖动调值逐次落盘。
  - **打包运行时**：`GameMain`/`AotGameMain` 读同一文件（`SettingManager.loadJavelinSettings`），行为一致。

> **闭环修正（本批）**：此前编辑器**不读**项目 `javelinSettings.settings`（`new JavelinSetting()` + 硬编码），导致「菜单里开了 Bloom、重启就丢」。本批起编辑器启动读入图形/后处理字段 → 项目设置为一致真值源。

---

## 6. 入口（菜单）

新增**编辑域**（Unity 对齐）：`Edit → Project Settings…`（命令 id `edit.projectSettings`，`ProjectSettingsMenuCommand`）。顶栏域顺序 = file → edit → tools → build（Unity 的 File→Edit 序）。详见 `Editor_Menu_Design.md`。

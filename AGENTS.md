# Javelin Engine 5 — AI Agent Guide

> 本文档面向 AI 编程助手，提供项目全景、技术栈、构建流程、代码规范与模块划分。项目主要使用中文注释和文档。

## 优先级
代码编辑优先使用IDEA 的 MCP 服务

## 红线
1. 不需要编译验证。 编译交给用户
2. 不允许自己编写不确定的代码
3. 当你不确定要怎么设计时可以询问用户意见
4. 当你有任何不确定的API调用时，特别是lwjgl,vk 相关的，先用 IDEA MCP 自查（方法见下文「查找依赖 API 方法签名」），查不到就提出让用户来给你明确的API签名；**不要去网页搜索**
5. 不允许自己瞎猜，不确定都要询问用户
6. 无法找到问题时，第一优先级应该是添加日志让用户运行后看日志。
7. **Edit 工具必须验证唯一性**：使用 `Edit` 前，确保 `old_string` 在目标文件中唯一。若存在多处匹配，必须用更长的上下文定位到具体位置，或改用 `Write` 整文件重写。每次 `Edit` 后必须 `Read` 验证文件状态，禁止连续多个 `Edit` 不做验证。
8. 禁止使用record类
9. **资产/序列化类字段禁止 `final`**：`Serializable` / `Asset`（AbsAsset 子类）反序列化需回写字段；不可变/只读靠 API 面保证——构建走 Builder、不暴露公开修改方法，不靠 `final`。无独立序列化协议、随宿主重建的纯值对象不受此限
10. **序列化 write 禁止包装写入、read 禁止派生嵌套字段**：`write()` 里集合/数组字段必须**直传字段本体**（`output.write("x", field)`），不许 `new ArrayList<>(field)`、unmodifiable 视图等任何包装——包装切断对象图引用同一性，共享引用会丢失。集合字段类型就用序列化实体类型（`ArrayList` 直通）。`read()` 只回写字段引用，不许解引用嵌套元素字段做派生结构（BinaryImporter 按 id 升序 populate，根对象先于嵌套对象，此时嵌套字段全是 null）。详见 Engine_Material_Design.md §3 坑点存档
11. **git 提交信息一句话**：只写一行 `类型(范围): 描述`（示例：`feat(editor): 去Web化收官——三窗口迁Swing + jcef桥包与前端工程整体清退`）；不加 bullet、不加日期——细节归设计文档/记忆，不在提交里展开（2026-10-18 收严，原「主题行+少量 bullet」口径作废）
12. **提交 git 前必须先写进度文档**：本批进度先落 doc/——功能批次写对应设计档（版本段+验收状态）并同步 `Engine_Roadmap.md` 的阶段进度与 §4 映射表，排障坑写 `Vulkan_Pitfalls.md`，然后才提交。git 只是收口，禁止「先提交、进度文档后补」
13. **没有用户指令禁止提交 git**：commit/push 一律由用户明确发起——验收通过、进度文档收口均不构成提交授权（红线 12 只约束提交前文档先行的顺序，不是提交触发器；历史会话的「验收→提交」模式不延伸到本次）
14. **javadoc 精简——注释说「是什么、怎么用」，设计档说「为什么」**：类注释最多 3~4 行（一句定位 + 使用方必须知道的契约，如「全程不抛、失败转 FAILED」）；方法注释一句话 + 必要 `@param/@throws`。设计论证（拍板依据、取舍对比、将来规划）一律归 doc/ 设计档，不进 javadoc——参照 PanelRegistry / FileTypeRegistry 既有风格（2026-10-20 拍板）

---

## 编辑器 UI 紧凑度规范（Swing）

> 2026-10-11 拍板于 Web UI 期，2026-10-18 随去 Web 化平移为 Swing 侧口径（原 engine-editor-web 及 style.css 已删除）。

- **间距上限 2px**：编辑器面板常驻布局的所有间距（布局 gap / padding / border 空白，含控件内部 padding）一律 ≤2px；≤2 的原值保留不凑成 2（如 1px 行距）。独立窗口（启动器/加载窗等非常驻面板）不受此限，按其自身设计定。
- 例外：**Inspector 字段行 label 列与控件列间隔 4px**——label 列对齐两列布局的专用间隔，其余间距仍守 ≤2。
- 尺寸类不是间距：图标、勾选框、标签列宽、滚动条宽度、tab 高度等按设计值定，不受 2px 约束。
- 浮层例外：点击才出现的弹出层（JPopupMenu / 下拉列表等 overlay 非常驻布局）不适用此限，按可点性定。
- 设计基准=完全参考 Unity（Editor_Design.md §1），紧凑度对标 Unity Inspector 贴行风格。
- 配色单点=UIManager / FlatLaf 属性统一收口（Editor_Swing_Design §10-3），组件禁止硬编码色值。

---

## 查找依赖 API 方法签名（IDEA MCP）

借助 IDEA MCP 确认 LWJGL / Vulkan / GLFW 等第三方库的方法签名，**优先自己查证而不是盲猜或反复询问用户**。

**首选 `get_symbol_info`**：相当于 Quick Documentation，给定符号位置，直接返回**声明签名、类型、Javadoc**。拿签名最快的工具，先用它。

签名不够、需要看实现或确认用法时，按以下顺序补充：

1. `search_symbol`（`include_external: true`）——不知道符号在哪个 jar 时，在 SDK 与库依赖里按名称定位；
2. `get_project_dependencies` + `read_file` 读 jar 内源码或反编译 `.class`：
   - 带源码的依赖：`jar:///path/to/xxx.jar!/org/lwjgl/vulkan/VK10.java`
   - 无源码的依赖：直接读 `.class`（如 `...!/VK10.class`），IDEA 会自动反编译；
   - 也支持 `jrt://`（JDK 模块）和普通绝对路径；
3. `analyze_calls`——调用层级分析（`INCOMING_CALLS` / `OUTGOING_CALLS`），看项目内已有调用点，确认 API 实际用法；
4. 其他辅助工具：`search_text` / `search_regex`（文本/正则搜索常量、字段、用法示例）、`execute_terminal_command`（必要时跑 `javap` 确认字节码签名）。

### 注意事项

- 这些工具依赖 IDEA 的索引：IDEA 未打开项目或索引未完成时，`search_symbol` / `analyze_calls` 可能查不到或结果不全；
- `get_symbol_info` / `analyze_calls` 作用于项目代码而非 jar 路径：前者需要"你自己的源文件里引用了该符号的位置（行+列）"，后者要求完整限定名（FQN）；名字有歧义时先用 `search_symbol` 拿到精确签名；
- 巨型类（如 LWJGL 的 `VK10`，上万行）不要全量读：`read_file` 默认只读 2000 行，先用 `search_regex` 定位方法所在行号，再带 `offset` 跳读；
- 反编译代码的变量名可能与真实源码不一致，属于近似源码；
- 兜底：MCP 查不到时**不要去网页搜索**，直接按红线第 4 条请用户提供签名。

---

## 文档索引

### 设计文档（doc/）

> 索引行=一行钩子（标题+版本+核心）；版本沿革、拍板细节、坑点、验收状态与日期一律档内与 `Engine_Features.md`（根目录，验收状态全项目单点），不在本表累加。

| 文档 | 内容 |
|------|------|
| `Engine_Design.md` | 引擎设计总纲（v0.2）：系统大方向全景、职责边界、文档规划、功能模块化方向（Gradle 依赖即包 + core ServiceLoader 自注册，§3.1）（引擎侧，与 RHI 文档互补） |
| `Engine_Uniform_Keys.md` | uniform 通道 key 单点表：push（PushBlock `pc`）/ 各 set UBO（set0 CameraBlock `cam`、set1 预留光照、set2 MaterialBlock `material`+贴图槽）的字段、偏移、类型与语义 + 保留字 |
| `RHI_Design.md` | RHI 总纲（v0.22）：概述、设计原则、架构取舍、章节索引 |
| `RHI_Design_Core.md` | 分卷一：包结构、核心数据类型、相机与视口、RenderView、API 全貌清单（第 26 节） |
| `RHI_Design_Pipeline.md` | 分卷二：渲染管线、多相机、RenderGraph 职责分层、呈现模型、帧执行与同步、线程模型 |
| `RHI_Design_Resources.md` | 分卷三：资源描述符与绑定、Shader SPIR-V 加载与 PipelineCache、资源生命周期 |
| `RHI_Design_Shader.md` | 分卷四：Shader 多 Pass 与 Material 实例模型、着色器文件语法（.shader） |
| `RHI_Implementation_Plan.md` | RHI 实现计划（v0.1）：六阶段路线、约束、验收标准 |
| `Engine_Assets_Design.md` | 引擎资产设计（v0.2）：Mesh 类、VertexAttribute、VertexLayout |
| `Engine_Material_Design.md` | 引擎材质设计（v0.2）：Shader/Material 数据模型、渲染侧桥接（§4）、.scene 内嵌材质与 .mat 资产格式（§3.2）、序列化坑点存档（§3） |
| `Engine_Scene_Design.md` | 引擎场景设计（v0.3）：组件模型、Node/Component/Scene、RenderingSystem、生命周期帧驱动状态机（§4）、.scene 序列化（§9）——拍板细节与红线辨析见档内 |
| `Engine_Rendering_Design.md` | 引擎渲染设计（v0.1）：RenderingSystem、帧描述交接契约、FrameLoop 调用关系、UBO1 收编 |
| `Engine_RenderPipeline_Design.md` | 引擎渲染管线设计（v0.1）：默认混合管线 Pass 组织、视图级资源规划、钩子点、MSAA 抗锯齿（§5.3） |
| `Engine_Window_Design.md` | 引擎窗口设计（v0.1）：Window 抽象、与 RenderSurface 分离、GLFW 实现（Window 归 JavelinEngine 持有） |
| `Editor_Design.md` | 编辑器模块架构（v0.1）：**设计基准=完全参考 Unity（交互口径/面板组织/命名，分歧以 Unity 为默认答案）**、资产管线（.meta/GUID/导入器/Library）+文件类型注册器、线程模型、已知问题与演进方向（§9/§10）——Web/JCEF 章节为历史沿革，UI 现状以 Editor_Swing_Design 为准 |
| `Editor_Menu_Design.md` | 编辑器菜单与命令系统设计（v0.13）：**命令=类实现 MenuCommand（三域基类）+ EditorSelection 单源**、Command 数据内核/lambda 双通道、MenuRegistry 声明式结构（子菜单任意嵌套）、file/project/hierarchy/build 四域命令实做 |
| `Editor_Swing_Design.md` | 编辑器 Swing UI 框架设计（v0.1）：**去 Web 化重构总纲（纯 Swing + FlatLaf，视口零拷贝路径保留）**、自研轻量 Dock（二分布局树 + PanelRegistry 扩展点 + ViewportTabs 单 Canvas 不 reparent）、面板迁移路线、里程碑 M1~M6、配色单点（§10-3） |
| `Editor_Plugin_Design.md` | 编辑器插件体系与 i18n 总纲（v0.1）：**插件契约（namespace/元数据/约定路径语言包）+ ServiceLoader 发现（editor.core 即插件无特权）+ 装载管线与错误分级（本体 fail-fast/第三方 warn 跳过）+ I18nKey/I18nScope/Lang 门面 + I18n 注册中心（!key! 兜底/去重 warn/miss-used 采集）**；待拍板项见档内 |
| `Inspector_Design.md` | 检查器设计（v0.6）：**@InsField 字段闸门 + @InsHeader 组件闸门（opt-in）+ 读 getter 写 setter 铁律**、节点头四件套、12 值类型通道（ENUM/COLOR/RECT/LAYER_MASK/GUID_ASSET 三态只读）、浮点 4 位小数口径、两列布局 label/控件列 4px 例外、检查器注册器（Node+资产统一接管+材质只读首期）、输入交互增强（键入即提交+label 拖动调值） |
| `Engine_Input_Design.md` | 引擎输入设计（v0.1）：三层架构（Window 回调→InputSubsystem 状态机→Input 静态门面）、bind/查询合流 Input、边沿双缓冲帧语义、GLFW/AWT 双接入 |
| `Engine_DebugText_Design.md` | DebugDrawText 调试文本设计（v0.1）：bgfx 式全屏点阵文本（含汉字，静态 16x16 1bpp 字库/用户提供 TTF 经 tool 光栅化）、.spv 内嵌 core、attr 16+16 色调色板、持续到 clear、buildFrameGraph overlay 挂点 |
| `Engine_RenderStats_Design.md` | 渲染统计设计（v0.1）：GPUInfo（设备/驱动信息）/RenderStats（11 类存活资源计数 + draw call/三角形快照双缓冲）RHI 打点口径、引擎显式 resetFrameStats、F3 开关 DebugText overlay（默认显示，占行 0~7） |
| `Engine_Collision_Design.md` | 引擎碰撞设计（v0.1）：纯数学层 L0（Ray/碰撞形状/Aabb/相交例程，D1~D9 决策表）+ L1 场景查询/L2 物理系统预留；Collider 组件族 + fitFromMesh + 世界 Aabb；拍板：碰撞体=组件、刚体独立后置、Ray 收窄为运行时物理查询 |
| `Engine_Picking_Design.md` | GPU 拾取设计（v0.1）：**RHI 读回环（TextureReadbackRing 非阻塞）+ 覆盖制 PickPass（内嵌 pick.spv，不消费材质 Pass 声明——看得见即拾得）+ PickSystem 双通道（hover/click、帧表防串帧、落点 core 运行时可用）+ 编辑器消费（点击门控/选中收口/Gizmo 轴迁移）**；透明物体可拾取（Unity 口径）；已知边界与验收清单见档内 |
| `Editor_DebugLine_Design.md` | 编辑器调试线设计（v0.1）：**编辑器专属世界空间线框（门面+batch+覆盖制 DebugLinePass 插 Transparent 后深度咬合/MSAA 双分支）+ 组件绘制器策略层（ComponentDebugLine 查表分派，mesh bounds 世界 AABB/相机视锥/BoxCollider）+ 选中驱动每帧 clear 重提交**；GAME 视口无线；验收清单见档内 |

### 实现进度文档

| 文档 | 内容 |
|------|------|
| `Engine_Implementation_Plan.md` | 引擎实现计划（v0.1）：六阶段路线（接线 RHI→场景→渲染→材质→子系统+输入→物理/音频）、RHI 遗留修复插入时机、开放项拍板表 |
| `Engine_Roadmap.md` | 引擎阶段路线（v0.1）：一期渲染基础关账总结、二期引擎功能（输入/碰撞/Ray/编辑器基本）、三期光照，与六阶段计划的映射 |
| `Engine_Features.md`（根目录） | 功能清单（v0.1）：按模块（RHI/核心/编辑器/构建链/支撑）分级盘点每项功能的完成状态+验收状态（方式+日期）——**验收状态/日期全项目单点**；§6 待验收挂账、§7 已知问题一览 |
| `RHI_Implementation_Progress.md` | RHI 实现进度记录：各阶段完成状态、文件清单、已知问题、API 签名确认记录 |
| `Vulkan_Pitfalls.md` | Vulkan / LWJGL / GLFW / Java NIO 陷阱记录（症状→根因→修法→出处）+ GPU 排障方法论 |

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

| 文档 | 内容 |
|------|------|
| `Engine_Design.md` | 引擎设计总纲（v0.1）：系统大方向全景、职责边界、文档规划（引擎侧，与 RHI 文档互补） |
| `RHI_Design.md` | RHI 总纲（v0.22）：概述、设计原则、架构取舍、章节索引 |
| `RHI_Design_Core.md` | 分卷一：包结构、核心数据类型、相机与视口、RenderView、API 全貌清单（第 26 节） |
| `RHI_Design_Pipeline.md` | 分卷二：渲染管线、多相机、RenderGraph 职责分层、呈现模型、帧执行与同步、线程模型 |
| `RHI_Design_Resources.md` | 分卷三：资源描述符与绑定、Shader SPIR-V 加载与 PipelineCache、资源生命周期 |
| `RHI_Design_Shader.md` | 分卷四：Shader 多 Pass 与 Material 实例模型、着色器文件语法（.shader） |
| `RHI_Implementation_Plan.md` | RHI 实现计划（v0.1）：六阶段路线、约束、验收标准 |
| `Engine_Assets_Design.md` | 引擎资产设计（v0.2）：Mesh 类、VertexAttribute、VertexLayout |
| `Engine_Material_Design.md` | 引擎材质设计（v0.1）：Shader/Material 数据模型、§4 渲染侧桥接（已落地 2026-09-25）；Material write/read + 条目存储（2026-10-04，.scene 内嵌材质往返闭环；.mat 资产格式仍待） |
| `Engine_Scene_Design.md` | 引擎场景设计（v0.3）：组件模型、Node/Component/Scene、RenderingSystem；§4 生命周期帧驱动状态机（2026-10-02 拍板：awake/enable/disable/start/update 由 update 三遍序驱动下帧生效、destroy 摘出即时、setEnabled 联动缺口修复）；§9 .scene 序列化（✅ 2026-10-01 落地、2026-10-04 实测验收通过、2026-10-02 拍板 read 全量恢复零收尾：scene 落盘根 id 引用（框架 Asset 分流对根实例走 id）+ 注册表直传/分流重建、生命周期状态不落盘 update 首帧自然驱动、Tag/layer 投影、红线 10 辨析、Material 内嵌数据面已通（2026-10-04）剩 .mat 资产格式） |
| `Engine_Rendering_Design.md` | 引擎渲染设计（v0.1）：RenderingSystem、帧描述交接契约、FrameLoop 调用关系、UBO1 收编 |
| `Engine_RenderPipeline_Design.md` | 引擎渲染管线设计（v0.1）：默认混合管线 Pass 组织、视图级资源规划、钩子点、v0.1 先 Forward 落地路线 |
| `Engine_Window_Design.md` | 引擎窗口设计（v0.1）：Window 抽象、与 RenderSurface 分离、GLFW 实现（Window 归 JavelinEngine 持有；show 已改名 showWindow） |
| `Editor_Design.md` | 编辑器模块架构（v0.1）：**设计基准=完全参考 Unity（交互口径/面板组织/命名，分歧以 Unity 为默认答案）**、Swing+JCEF 窗口体系、JS↔Java 桥接、资产管线（.meta/GUID/导入器/Library）、EditorApp 接引擎、线程模型、已知问题；§10 演进方向：变换 Gizmo v0.1 工具族+**拖拽（2026-10-06：TransformTools 拖拽三段式契约、LMB 平面相交法、视觉三态——hover 高亮黄/近平行不可选变灰，待实测验收）** + 物体拾取终态拍板=GPU ID-buffer（2026-10-06：不依赖用户 Collider/不隐藏挂碰撞体，RHI 前置 uint 附件+读回、后置实现）+ Transform 变更检测（2026-10-06：scene.nodeTransformChanged 第三类定点事件，TransformWatchAppState 帧级快照 diff、写入方零通知义务、拖拽自动置脏，待验收） |
| `Editor_Menu_Design.md` | 编辑器菜单与命令系统设计（v0.6）：**命令=类实现 MenuCommand（三域基类：顶栏/场景树/project，右键域提供选中 Node/文件上下文）+ EditorSelection 单源**、Command 数据内核/lambda 双通道、**MenuRegistry 声明式结构（含子菜单任意嵌套，v0.5）**、**菜单渲染统一 Swing 弹窗（v0.4 拍板：Web 浮层菜单实测仍被视口 Canvas 盖，不按位置判定；v0.5 子菜单 JMenu 递归 + 每层打开瞬间求值 + 快捷键 accelerator 显示 + 层级右键 Unity 壳占位命令置灰）**、v0.6「文件」域 file.save 保存场景（随 .scene 序列化落地）、engine2 对照取舍、三桥方法协议（/menu/list /menu/open /command/execute）；v0.5 子菜单与 v0.6 file.save/Ctrl+S 已实测验收（2026-10-04）；创建域补 hierarchy.create.plane（内置 plane.smesh 共享加载 + default.shader 运行期材质实例，2026-10-04） |
| `Engine_Input_Design.md` | 引擎输入设计（v0.1）：三层架构（Window 回调→InputSubsystem 状态机→Input 静态门面）、bind/查询合流 Input、边沿双缓冲帧语义、GLFW/AWT 双接入、验收双 Demo |
| `Engine_DebugText_Design.md` | DebugDrawText 调试文本设计（v0.1）：bgfx 式全屏点阵文本（含汉字，静态 16x16 1bpp 字库/用户提供 TTF 经 tool 光栅化）、.spv 内嵌 core、attr 16+16 色调色板、持续到 clear、buildFrameGraph overlay 挂点 |
| `Engine_RenderStats_Design.md` | 渲染统计设计（v0.1）：GPUInfo（设备/驱动信息）/RenderStats（11 类存活资源计数 + draw call/三角形快照双缓冲）RHI 打点口径、引擎显式 resetFrameStats、F3 开关 DebugText overlay（默认显示，占行 0~7） |
| `Engine_Collision_Design.md` | 引擎碰撞设计（v0.1 草案，实现已全片验收）：纯数学层 L0（Ray/碰撞形状/Aabb/相交例程，D1~D9 决策表）+ L1 场景查询/L2 物理系统预留（缩放=射线逆变换吸收、t 即世界距离不变量、JOML 例程适配性已核——球相交 tca 变体不满足 t 契约需自写）；已拍板：碰撞体=组件（负责形状）、刚体=独立组件后置（2026-10-05）；**片①②③④全片单测验收关账（2026-10-06）**——L0 形状相交+Collider 查询管线+片③ Aabb/Mesh.bounds/fitFromMesh（BoxShape 自写 slab、Cylinder 取最小边界穿越点、Collider collideShape 统一管线含法线矩阵回换、Aabb 行和公式即精确值 8 角点仅作对照基准、Mesh.bounds 三路径 set 即算+惰性兜底+上传前钉死、fitFromMesh Box 精确/Sphere 保守/Cylinder 半径取 max(半宽X,半宽Z)）；CylinderCollider 已建并挂编辑器三移动轴（fitFromMesh 贴合箭头包围圆柱，拖拽判定 Ray 期接入，2026-10-05）；编辑器物体拾取拍板走 GPU ID-buffer（2026-10-06，Editor_Design §10）——三角网格不再为拾取服务、MeshCollider 归运行时物理 L2 |

### 实现进度文档

| 文档 | 内容 |
|------|------|
| `Engine_Implementation_Plan.md` | 引擎实现计划（v0.1）：六阶段路线（接线 RHI→场景→渲染→材质→子系统+输入→物理/音频）、RHI 遗留修复插入时机、开放项拍板表 |
| `Engine_Roadmap.md` | 引擎阶段路线（v0.1）：一期渲染基础关账总结、二期引擎功能（输入/碰撞/Ray/编辑器基本）、三期光照，与六阶段计划的映射 |
| `RHI_Implementation_Progress.md` | RHI 实现进度记录：各阶段完成状态、文件清单、已知问题、API 签名确认记录 |
| `Vulkan_Pitfalls.md` | Vulkan / LWJGL / GLFW / Java NIO 陷阱记录（症状→根因→修法→出处）+ GPU 排障方法论 |

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
11. **git 提交信息精简——只说重点**：一行 `类型(范围): 描述`，**中文约 40 字、两三个要点封顶**，只写「这一批改了什么关键变化」（示例：`feat(scene): 环境设置组件落地——Environment（挂节点、场景唯一）接管天空盒，@UniqueComponent 唯一性作用域落地`）；**不写**怎么实现、随批改了哪些文档、验收状态与日期、Pitfalls/设计档编号、逐文件实现点枚举；不加 bullet、不加日期——细节归设计文档与记忆，不在提交里展开（2026-10-18 立「一句话」，2026-11-08 收严为「单行也要精简、只描述重点」）
12. **提交 git 前必须先写进度文档**：本批进度先落 doc/——功能批次写对应设计档（版本段+验收状态）；批次索引（每批一行「日期｜改动要点｜落点设计档/坑号｜验收状态」）写 `Engine_Roadmap_Progress.md`，状态摘要与 §5 映射表同步 `Engine_Roadmap.md`；排障坑写 `Vulkan_Pitfalls.md`，然后才提交。git 只是收口，禁止「先提交、进度文档后补」
13. **没有用户指令禁止提交 git**：commit/push 一律由用户明确发起——验收通过、进度文档收口均不构成提交授权（红线 12 只约束提交前文档先行的顺序，不是提交触发器；历史会话的「验收→提交」模式不延伸到本次）
14. **javadoc 精简——注释说「是什么、怎么用」，设计档说「为什么」**：类注释最多 3~4 行（一句定位 + 使用方必须知道的契约，如「全程不抛、失败转 FAILED」）；方法注释一句话 + 必要 `@param/@throws`。设计论证（拍板依据、取舍对比、将来规划）一律归 doc/ 设计档，不进 javadoc——参照 PanelRegistry / FileTypeRegistry 既有风格（2026-10-20 拍板）
15. **错误显性化——异常态该弹错误就弹，禁止代码静默消化本来就不正常的逻辑**：GUI 流程遇异常（解析不到引用、导入失败、状态缺失等）一律弹错误提示框让用户看见（JOptionPane 挂 MainFrame 先例），不许只打日志或写防御分支兜过去——静默兜底掩盖真问题（数据损坏、流程错序）。注意与**正常域的缺失态**区分：设计口径内合法的空状态（如未赋贴图槽=占位贴图、新建纹理未选图=纹理壳）按设计处理不算异常，不弹（2026-10-25 拍板，起因=拖入未导入文件静默失败）
16. **进度文档单文件 ≤ 50KB——超了就分片**：适用范围仅限**进度 / 台账类文档**——`Engine_Roadmap.md`（阶段进度）、`Engine_Roadmap_Progress.md`（批次索引）、`RHI_Implementation_Progress.md`（实现进度）：它们随批次线性增长、只会越来越大，故设硬上限。设计档 / 参考资料 / `Engine_Features.md` 功能清单**不受此限**（体量由设计范围决定，不随批次膨胀）。超限或接近上限即分片，手法照 `Engine_Roadmap.md` → `Engine_Roadmap_Progress.md`（总纲留状态摘要 + 分卷索引表 + 指针）：①内容零丢失——逐字搬迁，或压成一行索引（日期｜要点｜落点设计档/坑号｜验收），只删与设计档版本沿革、Features 状态行重复的叙述；②节号保持全局唯一、与总纲对读（拆出的节沿用原编号）；③分卷留溯源说明（原版归 git 历史）；④`AGENTS.md` 文档索引表同步登记分卷；⑤维护落点写清（新批次进哪一卷）。先例=2026-11-08 `Engine_Roadmap.md` 72KB 拆出并压成索引卷（54KB→26KB）
17. **【重点】只读 Library 产物——资产源文件只喂导入器，任何非导入器代码不得读 / 解析源文件**：资产只有一条单循环——**源文件 = 导入器输入**（`.scene/.mat/.shader/.texture/.smesh` 等），**Library 产物 = 唯一读取面**（运行时与编辑器一律经 `AssetManager.loadGuid`：缓存优先，miss 落 Library 产物并注册）；**编辑 = 改内存实例**（内存即真值，改完保存写回源文件 + `submitImport` 重导产物——读侧不需要也不允许回读任何文件来"取最新"）。绕过导入器读源只会造出**与资产缓存不一致的私有实例**：编辑落不到场景持有的实例上、渲染侧按实例身份烤死的指针/资源无人换代，症状即「改了不生效 / 静默不一致」（材质检查器 2026-11-10 实锤：装载解析文本源 + 换绑写私有实例 → 场景物体不更新）。**例外**：①导入器自身（读源是其职责）与导入期引用解析壳 `ShellAssetManager`；②**元数据 / 配置文本**（`.meta`、settings、Library `index.asset`，以及 `.java` 无 meta 时的头 guid）不是资产、无产物，按各自协议读；③工具 / 测试的序列化往返。**履约现状**：材质面板 / 纹理面板 / 场景装载（`JavelinEditor.loadSceneAsset`）/ `.shader` 诊断台均已按此，**残留项清零**（2026-11-12 实机复核：运行期换图同链即时生效——`setImage` 置空 GPU 句柄 + `revision` 自增 → materialSet 退役重建重传，旧图经 Cleaner 回收）；仍摘除 reload 链的是外部手改 `.texture`/`.png` 的 file-level 重载（HOT-02，待拍板真值源优先序）

---

## 编辑器 UI 紧凑度规范（Swing）

> 2026-10-11 拍板于 Web UI 期，2026-10-18 随去 Web 化平移为 Swing 侧口径（原 engine-editor-web 及 style.css 已删除）。

- **间距上限 2px**：编辑器面板常驻布局的**内部**间距（相邻元素之间的布局 gap / 分隔 border 空白 / 控件内部 padding）一律 ≤2px；≤2 的原值保留不凑成 2（如 1px 行距）。
- **面板边缘留白不受此限**（2026-11-19 拍板，起因=文字贴面板左边框）：内容与面板/分组外边框之间的 padding 按可读性定，不占「元素间距」预算——先例：节点检查器组件分组 `EmptyBorder(2, 6, 2, 4)`、项目设置窗表单 `EmptyBorder(8, 12, 8, 12)`。仅限**外沿**一层，分组内部的行距/列距仍守 ≤2（label 列 4px 例外不变）。
- 独立窗口（启动器/加载窗/项目设置窗等非常驻面板）不受本限，按其自身设计定。
- 例外：**Inspector 字段行 label 列与控件列间隔 4px**——label 列对齐两列布局的专用间隔，其余间距仍守 ≤2。
- 尺寸类不是间距：图标、勾选框、标签列宽、滚动条宽度、tab 高度等按设计值定，不受 2px 约束。
- 浮层例外：点击才出现的弹出层（JPopupMenu / 下拉列表等 overlay 非常驻布局）不适用此限，按可点性定。
- 设计基准=完全参考 Unity（Editor_Design.md §1），紧凑度对标 Unity Inspector 贴行风格。
- 配色单点=UIManager / FlatLaf 属性统一收口（Editor_Swing_Design §10-3），组件禁止硬编码色值。
- **SVG 图标不限主题色板**（2026-10-31 拍板）：`/icon/*.svg` 可彩色，以好看、语义清晰为准（灯族暖琥珀色系为先例：`#FFD75E` 光体 + `#E0A32E` 描边）；「禁止硬编码色值」只约束 Java 组件代码内联色，不涉静态图标资源。

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

## 渲染 / Shader 排障：RenderDoc 抓帧

> 2026-11-11 用户指定：**RenderDoc 是重要工具**。

渲染数据 / shader 问题**没有头绪、或静态排查定位不到时，不要在推演里硬猜**——请用户用 RenderDoc 抓帧，拿 GPU 侧真实状态断案。

- **分工**：引擎与 RenderDoc 都由用户运行（红线 1）；我方只给**具体的抓帧请求**——抓哪一帧 / 哪个 Pass 的哪次 draw、要导出哪几张附件或哪些绑定（G-Buffer albedo / 法线 / 世界位置、set0 相机块、set1 各 binding）、要核对哪个管线阶段（descriptor 绑定、stageFlags、**vertex input 的 location / format**）。
- **一次能看清**：各 attachment 的实际内容、描述符实际指向哪个资源、PSO 的顶点输入布局与固定态、每 draw 的管线状态、校验层消息。
- **典型命中**：越权读零（坑 #35，descriptor stage 可见性）、声明与引擎契约错位（坑 #36，vertex input location）、清屏 / 依赖顺序（#30/#31 一族）——「链路看起来全对、结果就是不对」这类问题的分钟级断案手段。
- **引擎内轻量替代面**：dbg-1 校验层回调接 logback（`JavelinSetting.engineDebug`）+ dbg-2 G-Buffer 可视化（`doc/Engine_DebugTools_Plan.md`）；校验层开着能把同批违规先在引擎日志里现形。
- 坑例与方法论归档：`doc/Vulkan_Pitfalls.md` §六。

---

## 文档索引

### 设计文档（doc/）

> 索引行=一行钩子（标题+版本+核心）；版本沿革、拍板细节、坑点、验收状态与日期一律档内与 `Engine_Features.md`（根目录，验收状态全项目单点），不在本表累加。

| 文档 | 内容 |
|------|------|
| `Engine_Design.md` | 引擎设计总纲（v0.2）：系统大方向全景、职责边界、文档规划、功能模块化方向（Gradle 依赖即包 + core ServiceLoader 自注册，§3.1）（引擎侧，与 RHI 文档互补） |
| `Engine_Uniform_Keys.md` | uniform 通道 key 单点表：push（PushBlock `pc`）/ 各 set UBO（set0 CameraBlock `cam`、**set1 光照+阴影+G-Buffer（binding0-7）**、set2 MaterialBlock `material`+贴图槽）的字段、偏移、类型与语义 + 保留字 |
| `RHI_Design.md` | RHI 总纲（v0.22）：概述、设计原则、架构取舍、章节索引 |
| `RHI_Design_Core.md` | 分卷一：包结构、核心数据类型、相机与视口、RenderView、API 全貌清单（第 26 节） |
| `RHI_Design_Pipeline.md` | 分卷二：渲染管线、多相机、RenderGraph 职责分层、呈现模型、帧执行与同步、线程模型 |
| `RHI_Design_Resources.md` | 分卷三：资源描述符与绑定、Shader SPIR-V 加载与 PipelineCache、资源生命周期 |
| `RHI_Design_Shader.md` | 分卷四：Shader 多 Pass 与 Material 实例模型、着色器文件语法（.shader） |
| `RHI_Implementation_Plan.md` | RHI 实现计划（v0.1）：六阶段路线、约束、验收标准 |
| `RHI_Texture_Types_Design.md` | RHI 纹理种类底座设计（v0.5，2026-11-18 **阶段一/二/三已验收**；**v0.4/0.5 = 2026-11-21 格式能力查询 RHI-15 + RHI-08 落码并用户实机验收（AMD 780M）**）：3D 纹理 / 2D 纹理数组 / storage image 写口 / 按种类上传区域 / 种类形状校验 / **跨帧布局记忆 RHI-13（布局状态随资源持有：`VulkanTexture.layout` 跨帧沿用 + `pipelineBarrier` 以真实布局为准，`LayoutTracker` 退役）** / **格式能力 `FormatSupport` + 纹理与 PSO 创建期 fail-fast（§7）**——服务 ⑥ P4-05 DDGI；**八条现状实证**＋四个拍板点（自定义视图 / 跨帧布局记忆 / 格式能力查询 / AS·BDA 独立批）＋验证件 `TextureTypesTest`/`TextureDescShapeTest`/`FormatCapabilityTest`（§5.1 验收记录：读回全一致 + 编辑器/TAA 零差异 + `[VVL]` 零 ERROR） |
| `Engine_Assets_Design.md` | 引擎资产设计（v0.3）：Mesh 类、VertexAttribute、VertexLayout、**Mesh 子网格（SubMesh 区间列表 + setSubmeshes/getSubmeshCount，Unity subMeshCount/SetTriangles 语义，§2.9，2026-10-29）** |
| `Engine_Material_Design.md` | 引擎材质设计（v0.8）：Shader/Material 数据模型、渲染侧桥接（§4）、.scene 内嵌材质与 .mat 资产格式（§3.2）、坏 shader stub 产物与 DefaultMaterial 渲染回退（原 DefaultShader，2026-10-29 改名）、序列化坑点存档（§3）、**`Pass GBuffer` 几何通道契约（§4.2，2026-11-08：HYBRID 档不透明几何声明契约 + surfaceType 交互）** |
| `Engine_Material_LightingModel_Design.md` | 材质光照模型设计（v0.3，2026-11-16 **已验收**）：`.shader` 顶层语义头 `LightingModel`（**单一封闭轴**：`Lambert`（缺省）/ `Unlit`，旧资产零迁移；后置 `PBR`/`Toon` 同轴追加——不做「公式 × 受光」两轴以免无效组合）+ `Shader.lightingModel` 枚举数据面 + 注入器按模型取块集（`lightingStageBlocks` switch；`Unlit` 跳过 `LIGHTING_BLOCKS`/`RECEIVE_SHADOW_PUSH`，保留相机块与材质声明）+ `RenderList.unlitOpaque` 新桶与新 `UnlitForwardPass` + HYBRID 序 `Shadow→GBuffer→Lighting→Skybox→Unlit→Transparent`（深度 LOAD、ZWrite On）+ `default.shader` 改 Unlit（错误态可见）+ 边界（TAA/SSAO 世界位置失效**已静态核验成立**：无拖影、无 AA、AO 恒 1）+ 验收清单 8 条 + 四拍板点 + §11 实装记录 |
| `Engine_Texture_Design.md` | 引擎贴图设计（v0.4）：三层资产模型（Image 像素资产 / Texture2D 引用+设置层 / 材质贴图槽——一图多设置免复制）、.png/.jpg/.jpeg 导入链（ImageImporter）、.texture 纹理资产（创建命令+导入器+检查器面板，编辑载体=文本私有实例）、alpha 修复、边界（mip/压缩/sRGB 后置）；v0.4=导入期引用解析壳管理器（Editor_Design §6.3） |
| `Engine_Scene_Design.md` | 引擎场景设计（v0.7）：组件模型、Node/Component/Scene、RenderingSystem、生命周期帧驱动状态机（§4）、**组件移除 `Node.removeComponent`（§2.2，2026-11-08：即时 onDisable→onDestroy + 退注册表、唯一性槽位释放；Transform 不可移除）**、.scene 序列化（§9）、colliders 注册表分流投影（L1 物理查询遍历源，2026-10-28）、**MeshRenderer 多材质（materials[i] 对应子网格 i + 每 (renderer, 子网格) 一 RenderObject + 缺失位默认材质兜底，§5.1，2026-10-29）**、**唯一性作用域 `@UniqueComponent`（§2.5，2026-11-08：不带参=每节点至多一个、`scene=true`=全场景至多一个；落表前拒绝不留半挂态；注解值走处理器生成的 AOT 安全通道）+ `Environment` 首例（挂节点上的普通组件、全场景唯一，装天空盒）**——拍板细节与红线辨析见档内 |
| `Engine_Rendering_Design.md` | 引擎渲染设计（v0.4）：RenderingSystem、帧描述交接契约、FrameLoop 调用关系、UBO1 收编、**§11 渲染设置门面 `RenderSettings`（2026-11-15 退役——下发收进 `JavelinEngine.applyRenderSettings()`，读写走设置对象 `GraphSetting`）** |
| `Engine_Lighting_Design.md` | 引擎光照设计（v0.10）：**clustered + SSBO（Forward+ 形态，2026-10-28 拍板一步到位、跳过 UBO 数组/逐盏画光中间档）**——**灯组件子类族（2026-10-31 拍板：Light 抽象基类+Directional/Point/Spot 各一类，每型参数集不相交不摆死字段、不支持切换类型——Collider 族同构）**、主光=最强激活方向光自动裁决（2026-10-31 拍板对齐 Unity）、收集（视空间 64B 打包/强度预排序）、cluster 三件套（grid/变长索引池/参数 UBO，每 cluster 上限 64 截尾=去最弱）、**CPU 构建（2026-11-01 拍板，§6——GPU 三步形态两硬伤作废）**、Lambert+环境项+Unity URP 距离窗着色消费（javelinLighting 三参签名）、**ShadowPass 单张硬影（批次④-A 已验收关账 2026-11-01——§4.5.1 实装形态五拍板：PSO 静态 bias/binding4 并入 set1/深度比较采样器（随验 LINEAR→NEAREST）/per-view 幂等挂载/sunViewProj 扩块；着色器走资产导入链 SpvShader（.vert/.frag，2026-11-01 拍板推翻 tool 内嵌 spv）；PCF/CSM 归四期）**、**批次④-B Deferred 复用（2026-11-10 验收关账——GBufferPass+LightingPass 同一 cluster 直喂、set1 扩 binding5/6/7、PipelineProfile 双档可切、HYBRID 强制 1x；随验坑 Pitfalls #35 set0 阶段可见性读零；三期收官）**、注入红线（default.shader=DefaultMaterial 兜底禁改造、注入样例=内置 Lit.shader）、实装路线（**①②③④-A④-B⑤ 全部验收关账——三期收官**） |
| `Engine_Skybox_Design.md` | 引擎天空盒设计（v0.5）：**三期批次⑤三步全部验收关账（步骤一/二 2026-11-02、步骤三资产面 3a/3b/3c 2026-11-07 用户实机全链）——.hdr/.pano 双后缀 equirect 导入转六面 + CubemapImage 资产 + 场景级引用/检查器双首例；随验六连排障全修（SKYBOX 默认值/清屏拖影/sRGB 量化/相机位置/采样器 CLAMP_TO_EDGE/纬度归一化，§1.3~§1.5，坑 Pitfalls #30/#31/#32）**；**v0.5 载体迁移（2026-11-08）：天空盒归 `Environment.skybox`（场景域组件），原 `GameScene.skybox`/`Scene.getSkybox()` 退役**；纯视觉不参与光照（ambient 归 GI 探针期）、RHI cubemap 通用纹理种类底座红线、SkyboxPass 3.5 位/ClearFlags.SKYBOX（默认值+文案已对齐 Unity/HDRP）；内置资源归属迁移步骤一 2026-11-07 执行、**步骤二同日收官**（`builtin-asset-core` 迁回 javelin-core 资源树 + 索引分模块；内嵌 spv 三件全迁源码导入链——pick→`builtin-asset-core/pick`、DebugText→`builtin-asset-core/debug`（含新资产类型 `.chars` 字库字符集源）、DebugLine→`builtin-asset-editor/debugline`，javelin-tool 回归纯库，§8） |
| `Engine_RenderPipeline_Design.md` | 引擎渲染管线设计（v0.11）：**管线 = 两个独立实现类**（`AbstractRenderPipeline` 共用骨架 + `DefaultHybridRenderPipeline` 混合 / `ForwardRenderPipeline` 前向，**启动期选定、运行期不可切**，编辑器固定混合）——混合 = Shadow→GBuffer→Lighting→Skybox→Transparent；Pass 逐个设计与实装形态（§4.2/§4.3：G-Buffer 三张 MRT + 全屏光照与 Forward 同一 javelinLighting 单源）、视图级资源规划、§6.3 管线选择；**§5.3 MSAA 已移除（2026-11-13，RHI 多采样设施保留休眠，AA 空窗至 TAA）**；§4.6/§4.7 后处理细节指向 `Engine_PostProcess_Design.md` |
| `Engine_PostProcess_Design.md` | 引擎后处理设计（v0.6，**步骤一/二已验收 2026-11-14；管线解耦 + 参数可配（常量→push）**）：四期第 1 项——HDR 切换点（`lightingRT` RGBA16F per-view + 链非空才启用）、`sceneColorTarget` 抽象、链展开机制（`PostProcessNode` 契约 + 中间 RT + 展开点在场景 Pass 之后/overlay 之前 + 链入 `AbstractRenderPipeline` 两管线同享）、`TonemapPass`（高光肩部 KNEE=0.8：≤0.8 逐像素不变、>0.8 压向 1.0；原 ACES 因实机发灰换装 §9.5，链尾直出并接管清屏）、`BloomPass`（步骤二：**独立开关**、4 级降采样 + 帐篷核模糊 + 上采样累加，§3.7/§9.6）、**管线只承载链与展开、不认识具体节点**（`PostProcessNode.isTerminal` 排序 + 组合根 `PostProcessSetup` 持有/按 id 增删链，§9.7）、**参数可配（着色器常量→push）+ 编辑器「项目设置」窗口（§9.8，见 `Editor_ProjectSettings_Design.md`）**、**AA 路线 = TAA 取代 MSAA（P4-07 作废 / P4-10 TAA；MSAA 已删、AA 空窗）**、8 条拍板点 + §9 实装记录 |
| `Engine_DDGI_Design.md` | 引擎 DDGI 设计（v0.6，**2026-11-23 移动闪烁止血 a+b（跳格重扫保持 hysteresis + 场盒只增不减）+ 阴影区反弹修复（`ambientInHit` 恒 1，推翻 v0.5 §13.7 环境项门控——消费面是整项替换非叠加，门控成 0 反把阴影/背光区环境项替换成 ≈0）；纯宿主侧改动、`.compute` 零改动无需重导；待实机验收**；v0.5 = 2026-11-22 直射项改采 CSM 阴影图；v0.4 = 2026-11-21 B2 数据面关账 + B3 消费面落码；v0.1 = 2026-11-20 方案件）：四期施工序 ⑥（P4-05）——**DDGI 是算法（Majercik JCGT 2019），非 NVIDIA RTXGI SDK，本引擎不引 SDK**（黄档许可 + AS/BDA 依赖 + 首版不需要）；形态 = 三段 compute（`ddgi_trace`（静态体素 DDA 求交，体素化在 P4-05 内部按「静态集 + 签名门控 + albedo 通道」重建）→ `ddgi_update`（八面体辐照度 + 深度矩 + hysteresis）→ 着色采样 `javelinDDGI`）；数据面 = **单张 2D atlas 拼 tile**（辐照度 8×8+1px `RGBA16F` / 深度 16×16+2px `RG16F`，避开分层屏障故 RHI-14 不阻塞），探针自动铺网格（**按体素场 AABB 收缩 `fittedToField`**，实测 6×3×4=72 探针）、分帧刷新；**v0.2 落码 = `DdgiSettings`（项目设置新组）+ `DdgiProbeGrid`（§11）+ `DdgiProbeGridTest` + `DdgiAtlases`（`RGBA16F`/`RG16F`、按版面换代、接 `RenderingSystem.ddgiAtlasesFor`）+ `DdgiAtlasTest` + `DdgiVoxelField`/`DdgiVoxelizer`/`StaticSetSignature`（128³ `RGBA8`：rgb=基色、a=占用；集合 = 不透明 + `castShadow`；两帧稳定签名门控）**；**v0.3 = B2 追踪/更新两段 compute + `DdgiProbePass` + 诊断口径 v2**；**v0.4 = B3 消费面（§13）**——`Environment.ambientMode` 扩 `DDGI` + set1 binding8（辐照度 sampler）/ 9（`DdgiParamsBlock` 96B）/ 10（深度矩 sampler）（8/9 复用 P4-17 腾出槽、10 新增）+ `ShaderInjector.DDGI_BLOCKS`（八面体编码 / 内区手写双线性 / Chebyshev 可见性 / 8 探针三线性）+ `javelinLighting` 环境项接管 + `RenderingSystem.syncDdgi`；**ambient = GI 环境源光，DDGI 档下仍要设非零（v0.6 口径）**；**注入器改动须重导全部 `.shader` 与内置 `lighting.frag`**；**与 SSAO 分工 = DDGI 管低频、SSAO 改「接触 AO」（radius 0.25 / intensity 0.4~0.5，不关）** |
| `Engine_DDGI_NearField_Design.md` | 引擎 DDGI 近景窗口设计（v0.2，**后置方案件·低优先级，不排入 §4 施工序，触发条件见 §8**）：探针块已随相机（`fittedToField`）但体素层仍是整世界单张 N³（`DdgiVoxelField`，200m 场景默认 128³ ⇒ 1.56m 体素，薄构件从 GI 消失）——方案 = 64m 滚动窗口体素场（128³ @0.5m、8m chunk 位移、乒乓 image 拷贝 + 3 片 slab 增量体素化、需补 `CommandBuffer.copyImage`）+ 探针块 chunk 吸附（跳格 1.5m→8m）与 atlas 位移重投影 + `MAX_RAY_DISTANCE` 60→40m；**§10 动态物体（角色）**：现状角色被并入静态集 → 体素图冻结、停下才整场重建，而 CSM 已覆盖直射遮挡，口径 = 动态物体只接收 GI、发射与遮挡交 rayQuery（P4-06） |
| `Engine_DDGI_ProbeViz_Design.md` | 引擎 DDGI 探针可视化设计（v0.2，**2026-11-24 已落码（DBG-07 / dbg-3），未编译待验收**）：L3 验收依赖件——三视图 = ①世界空间网格盒 + 三轴十字（`DebugDrawLine`，由 `DebugLineAppState` 每帧 clear 后统一代提交，超 2000 十字按整数步长降采样并在数值行标 1/N）②atlas 直显（`DdgiProbeViewPass` 全屏三角 + push `{int mode, float scale}` + 自建 set0 单采样器，`texelFetch` 1:1、tile 边框「未扫红/已扫暗灰」、`E/π > 1` 溢出洋红、深度按 `MAX_RAY_DISTANCE` 归一，**零 CPU 回读**）③`DebugText` 第 11 行数值行；入口 = 菜单「工具 \| DDGI 探针视图」四项 + F5，未就绪置灰；只读面 = `DdgiProbePass.grid()/atlases()` + `JavelinEngine.ddgiProbePass()`；新内置资产 `builtin-asset-editor/debug/ddgiView.frag`(+`.meta`，顶点复用 `gbufferview.vert`），需重导出后 atlas 视图才可点亮 |
| `Engine_TAA_Design.md` | 引擎 TAA 设计（v0.7，**相机级四项参数 + Inspector 条件子组/折叠组头/滑条，待实机验收**）：四期施工序 ③、收口「MSAA 已删 → AA 空窗」——**仅 HYBRID 档**（FORWARD 忽略 + warn）、链序 SSAO 之后（`order()=15`）、Halton(2,3) 8 相 1px 抖动（**注入 set0 UBO1 写入点**，阴影/簇/灯收集用未抖动矩阵；TAA 关必须停抖）、重投影 = G-Buffer 世界位置 + 上帧 jitter 后 viewProj、RGBA16F per-view 历史 ping-pong（`taaRtFor`）+ 3×3 min/max 邻域钳制 + 速度自适应 α、两 Pass 形态（resolve 写历史 → copy 写链输出）、自管参数 UBO（set0/set1 布局零改动）、失效触发（切场景/resize/相机跳变/上帧未渲染/**上帧未启用 TAA**）；**v0.5 载体迁移：抗锯齿与四项参数搬上 `Camera`（`@InsFieldGroup` 条件子组 + `TaaSetting`），项目设置 TAA/锐化两组删除、per-camera 后处理**；**v0.6/0.7 面板形态：组头可折叠、四项滑条（`@InsSlider`）**；10 条拍板点 + 步骤一/二划分 + 验收清单见档内 |
| `Engine_SSAO_Design.md` | 引擎 SSAO 设计（v0.6，**步骤一/二 2026-11-14 用户实机验收关账**；**v0.5/0.6 = 2026-11-21 降噪重做（BUG-12）落码并用户实机收口**）：四期施工序 ①（GI 第一步）——`SsaoPass` 单节点内四覆盖制 Pass（AO 计算 / 水平模糊 / 竖直模糊 / 合成，2026-11-21 两遍分离）、**不读深度改读 G-Buffer 世界位置+法线**（零 RHI 改动、仅 HYBRID 档；深度采样三条缺口实证在档）、半分辨率 AO（`R8_UNORM`）、模糊保边权用视空间 z 差（掠射面下世界位置距离会拒绝平滑）、`PostProcessNode.order()` 排序声明（SSAO=10/Bloom=20，保 AO 在泛光之前）、开关 `JavelinSetting.ssao` + `SsaoSettings` 三参数接项目设置窗口、FORWARD 档忽略并 warn；十个拍板点见档 §7、落点与三处实装细化见 §9 |
| `Engine_Shadow_Cutout_Design.md` | 引擎阴影镂空（alpha cutout）设计（v0.2，**2026-11-24 已落码（REND-21-A，L4 首笔），未编译待验收**）：语义 = 材质 shader 属性 `_AlphaCutoff`（`Range(0,1)` 缺省 0 = 关，不动 `SurfaceType`）；阴影侧 = `ShadowPass` 首获专属 set0（binding0 = 材质 `_MainTex`，每材质描述符集按贴图版本重建、旧集两帧退役、不裁剪共用 1×1 白图集）+ push 128→144B（`cutoff@128` / `hasAlbedoTex@132`）+ `shadow.vert` 补 `aUV`→`vUV` + `shadow.frag` 空体改按阈值 `discard`（无 UV0 网格告警一次后按不裁剪）；主渲染同批 = `ShaderInjector` 新增 fs 注入助手 `javelinAlphaClip(vec4,float)`，内置 `Lit.shader` Forward/GBuffer 两条 fs 各接一行 + `Properties` 加 `_AlphaCutoff`；RHI 新增 `Device.maxPushConstantsSize()`（超限构造期抛错）；后置 = `DdgiVoxelizer` 不吃 alpha（体素图里镂空物仍实心） |
| `Engine_Shadow_PCSS_Design.md` | 引擎阴影软化设计（v0.2，**2026-11-25 已落码 C1 PCSS（REND-21-C，L4 第二笔），未编译待验收**）：`ShadowSettings.softness`（缺省 1.0 / 0 = 关 PCSS，注解滑块 + 双语）经 `shadowParams2.w` 进 UBO（`LIGHTING_STRIDE` 768 不变）；set1 新增 binding11 `shadowMapRaw`（同一张 shadow atlas + NEAREST 非比较采样器——阻挡搜索要真实深度，比较采样器只给 0/1）；注入段 `javelinPcssBlockerDepth`（黄金角螺旋 16/24 tap）+ `javelinCascadeShadow` 把 PCF 核半径换成半影 `(refZ-blockerZ)*softness*atlasSize*0.05`、钳制 `[pcfRadius, ×4]`；**C2 接触阴影挂 Backlog**（前置 = 相机深度附件加 `SAMPLED` + binding12，仅 HYBRID）；注入段改动 = 全部 `.shader` 与内置 `lighting.frag` 强制重导 |
| `Engine_Shadow_Contact_Design.md` | 引擎接触阴影设计（v0.2，**2026-11-25 已落码 C2 接触阴影（REND-21-C2，L4 第三笔），未编译待验收**）：世界位置沿主光方向短距步进（10 步 / tMax 0.35）+ `cam.viewProj` 投影后采 `gbufferWorldPos` 比较相机空间 z，命中率 × 强度即主光直射项的接触遮挡系数；**推翻旧前置**——不读相机深度、不加 `SAMPLED`、不占新槽位（与 SSAO 同一取证方式），只乘主光、只在全屏光照 Pass 生效（Forward 走三参 `javelinLighting` 恒 1.0 → HYBRID 限定）；参数 `contactParams` 挂 `LightingParamsBlock` @656（块 656→672，stride 768 不变） |
| `Engine_Shadow_CascadeCull_Design.md` | 逐级联剔除设计（v0.1，**2026-11-25 已落码 REND-21-B（L4 第四笔，纯性能项），未编译待验收**）：`ShadowPass` 级联循环内做 caster AABB 粗筛——本地盒 → 世界盒 → 光裁剪盒（正交 z∈[0,1]），判 x/y 重叠且未越过远平面，**近平面侧不裁**（裁了漏光）、无包围盒恒画；不引入 JOML `FrustumIntersection`（约定不符），零新依赖零分配；主视图剔除仍归 REND-01 |
| `Engine_RayTracing_Plan.md` | 光追首范围推进计划（v0.4，**2026-11-25 B0 探针 + B1 能力启用 + B2a BLAS/设备地址 + B2b TLAS/屏障/场景装配均已落码，未编译待验收**；B3 待开工）：B0 = 设备扩展枚举 + `supportsRayQuery()` 双判 + 启动日志；B1 = 特性探测 → 扩展启用列表 + `Device.rayQueryEnabled()` + `BufferUsage` 三档 AS 位；B2a = `VulkanMemory` 带址分配 + `Buffer.deviceAddress()` + `CommandBuffer.handle/buildBlas` + scratch 对齐属性 + `Device.createBlas` + `Mesh.blas()` 缓存；B2b = `VkAsGeometry`（BLAS/TLAS 几何 + 构建后屏障）+ `TlasInstance`/`Tlas`/`VulkanTlas` + `CommandBuffer.buildTlas` + `Device.createTlas` + core `RayTracingScene`（对象→实例、容量自扩、`objectAt` 反查）；B3 = rayQuery 求交可视化（先与 shadow map 深度图对照，以 RenderGraph Pass 接入并每帧 build TLAS）；范围限阴影与 GI 求交；已知风险 = 实例缓冲单份不双缓冲、BLAS 无自动失效钩子 |
| `Engine_Shadow_Quality_Design.md` | 引擎阴影质量分级设计（v0.8，**2026-11-24 随 REND-21-A 同步 §4.7：push 128→144B + ShadowPass 首获 set0（alpha 裁剪 albedo）**；v0.7 = **步骤一全件 + 步骤二 CSM 2026-11-14 用户实机验收、P4-03 关账**；**v0.6 = 2026-11-20 接触漏光修复落码，v0.7 = 2026-11-21 用户实机验收通过、BUG-11 销账**）：四期施工序 ②——步骤一 PCF 品质档（`ShadowQuality` 硬/软低 4 tap÷16 复现 3×3/软高 9 tap÷144 复现 5×5，MJP 亚纹素权重、Babylon 字面移植）+ UBO `shadowParams`（@400，实需 416B、步长 512 不变）+ 强度/参考深度偏移（`ShadowMath` 回填光空间 z 范围折算）+ bias 两项取设置（PSO 静态，改档整批退役重建、旧管线两帧销毁）+ 项目设置窗口「阴影」组 + 读写盘 + 双语；步骤二 CSM（lambda 切分 + 球拟合方形纹素 + 纹素吸附 + 单张 2×2 atlas **零 RHI 改动** + per-view 阴影资源族 + 级联选择/相邻 mix，UBO 步长 512→768）；**v0.6 接触漏光（BUG-11）= 注入段 `refZ` 固定半纹素外扩 `0.5/(atlasSize/grid)` + 五项缺省重标（`pcfRadius`0.6 / `refDepthOffset`0.02 / `biasSlope`0.6 / 级联 4 / 距离 80，近级联纹素 3.3cm→1.3cm、atlas 显存不变）**；步骤三可选收尾（D16/PCSS/逐级联剔除/DBG-03/多灯阴影/SSCT/`castShadow` 拆位）；§9.6 实装记录（含「标量 vs 向量混用」随验修）+ 强制性重导前置 |
| `Engine_VoxelAO_Design.md` | 引擎体素化 AO 设计（**⛔ 已退役 2026-11-20，2026-11-21 用户编译+实机确认关账**：用户拍板「有 SSAO 目前够了」→ 整链删除（`voxelAO/voxelAOBlur/voxelize.compute`、`VoxelizationPass/VoxelGrid/VoxelAoPass/VoxelAoDebugReadback/MeshVoxelGeometry`、`VoxelAoSettings`、`javelinVoxelAO` + set1 binding8/9、`tools.voxelAoRebuild` 命令全部移除），代码可从 `dc1e9e0` 取回；本档留档供 P4-05 首版投射线参考）。历史内容（v0.9）：**2026-11-19 批 A~D 落码入库（`dc1e9e0`）+ 五轮排障，未验收**；**2026-11-20 定位扩展 = DDGI 前置底座 + 无 GI/低端档兜底，并拍板「DDGI/光追档不乘 AO、SSAO 三层收敛」，见 §11**）：四期施工序 ⑥（DDGI，P4-05）的前置档 P4-17——场景体素化成 3D 占用图（128³ `R32_UINT` + `imageAtomicOr`）→ 逐体素球面 16 射线求可见度（AO 64³ `R8_UNORM` 原图 + 3×3×3 blur 图）→ 运行期三线性采样乘环境项（`ShaderInjector` L188 乘算点，set1 新增 binding8/9）；**拍板：GPU 体素化读 mesh 顶点/索引缓冲（`MeshUpload` 补 `BufferUsage.STORAGE` + 索引 4 字节补零、无需重导）、128³+64³ 默认档**；执行面走帧图 compute Pass（恒挂载 + 派发门控，屏障交图执行器；**跨 kernel 读一律走采样读——同格式同布局的 storage 写→读不插屏障**）；`VoxelAoSettings` 接图形页（滑条 + 下拉 + 恢复默认继承）；批 A/B/C/D 全链落码（`MeshVoxelGeometry` + `VoxelGrid` + `VoxelizationPass` + `VoxelAoPass` + `builtin-asset-core/voxel/{voxelize,voxelAO,voxelAOBlur}.compute`，引擎挂管线尾；set1 扩 binding8/9 + `ShaderInjector` 的 `javelinVoxelAO` 作环境项乘子——**注入器改动须重导全部 `.shader` 与内置 `lighting.frag` 产物**）；**采样侧须沿法线偏半体素**（球面距离加权无方向性）；含与 SSAO 分工、批次 A~E、8 条验收清单、待拍板 P3/P4 |
| `Engine_Window_Design.md` | 引擎窗口设计（v0.1）：Window 抽象、与 RenderSurface 分离、GLFW 实现（Window 归 JavelinEngine 持有） |
| `Editor_Design.md` | 编辑器模块架构（v0.1）：**设计基准=完全参考 Unity（交互口径/面板组织/命名，分歧以 Unity 为默认答案）**、资产管线（.meta/GUID/导入器/Library）+文件类型注册器、线程模型、已知问题与演进方向（§9/§10）——CEF/Web 历史章节已清退（2026-10-23，§4 仅留指针），UI 现状以 Editor_Swing_Design 为准 |
| `Editor_Menu_Design.md` | 编辑器菜单与命令系统设计（v0.25）：**命令=类实现 MenuCommand（三域基类）+ EditorSelection 单源**、Command 数据内核/lambda 双通道、MenuRegistry 声明式结构（子菜单任意嵌套）、file/project/hierarchy/build 四域命令实做 + 创建族（material/shader/texture + **灯光三命令（定向/点/聚光，2026-10-31 随三期批次②灯族子类化）** + **环境设置（建 `Environment` 节点 + `Environment` 组件、固定场景根级，2026-11-08）**）+ file.preferences 首选项命令（语言设置首批，2026-10-29）+ **tools.postProcessing 后处理开关 / tools.bloom 泛光独立开关（2026-11-13，随四期第 1 项步骤一/二）** + **tools.voxelAoRebuild 重建体素 AO（2026-11-19，随四期 P4-17）**；~~tools.renderPipeline 渲染档位切换~~ 已于 2026-11-13 删除（管线拆两个实现类、运行期不再切档）；**新增编辑域 `edit` + `edit.projectSettings`（2026-11-14，项目设置窗口，见 `Editor_ProjectSettings_Design.md`）** |
| `Editor_ProjectSettings_Design.md` | 编辑器项目设置设计（v0.2，**2026-11-15 注解化重构，待实机验收**）：对标 Unity Edit → Project Settings——非模态窗口，**页清单与页面内容全由注解派生**（`@Setting` 页根 + `@SettingGroup`/`@InsField`/`@SettingHint`/`@SettingSelect`/`@SettingRestart`，`SettingsSchema.pagesOf` 页发现，声明序=导航序），双页「图形（`GraphSetting`）\|窗口（`WindowSetting`）」；**三档生效口径**：即时（后处理/背景，经 `JavelinEngine.applyRenderSettings`）/重启（`@SettingRestart` 只读不入持久化）/只写不读（`editorStartup=false` 的页宿主=项目文件实例，编辑不影响编辑器）；关窗逐页 load-modify-save 写 `<project>/Game/javelinSettings.settings`；表单容器 `Scrollable` 宽度跟随视口 + 说明行换行；与首选项（个人偏好 `~/.javelin`）边界 |
| `Editor_Swing_Design.md` | 编辑器 Swing UI 框架设计（v0.2）：**去 Web 化重构总纲（纯 Swing + FlatLaf，视口零拷贝路径保留）**、自研轻量 Dock（二分布局树 + PanelRegistry 扩展点 + ViewportTabs 单 Canvas 不 reparent）、面板迁移路线、里程碑 M1~M6、配色单点（§10-3）；**v0.2 新增 §4.3 耗时操作指示器（后台耗时操作「处理中」浮层——类与双语包已落并验收，调用点接入为后续）** |
| `Editor_Plugin_Design.md` | 编辑器插件体系与 i18n 总纲（v0.3）：**插件契约（namespace/元数据）+ ServiceLoader 发现（editor.core 即插件无特权）+ 装载管线（语言包随 setup registerPack 显式域+路径注册；任何插件报错弹框 + 确认后关闭编辑器，2026-10-29）+ I18nKey/I18nScope/EditorLang 门面 + I18n 注册中心（语言包注册制：registerLanguage 支持语言 + registerPack「域+语言+路径」显式注册、!key! 兜底/去重 warn/miss-used 采集/{} 顺位插值/缺键逐键回退该域基线）**；**i18n 全量化批次（2026-10-30，§9）：编辑模块非日志字面量全迁 + 双语言包 354 键 + 异常收 I18nKey（LangException 基类）+ 源码机检落测试红线（§4.5-3）**；待拍板项见档内 |
| `Inspector_Design.md` | 检查器设计（v0.28）：**@InsField 字段闸门 + @InsHeader 组件闸门（opt-in）+ 读 getter 写 setter 铁律**、节点头四件套、13 值类型通道（GUID_ASSET 可编辑——AssetInput 选择窗/拖放/None 清空、多后缀；GUID_ASSET_LIST 资产列表多材质行——行数跟随子网格数、整列表 setter 回写，2026-10-29）、**label i18n 键化（@InsField/@InsHeader/InsEnumLabel 存语言包局部键、反射层三解析点经 EditorLang，2026-10-30）**、浮点 4 位小数口径、两列布局 label/控件列 4px 例外、检查器注册器（Node+资产统一接管+.shader 诊断台+.mat 可编辑材质面板+.texture 纹理面板——.mat 编辑载体=缓存共享实例优先、.texture=文本私有实例）、输入交互增强（键入即提交+label 拖动调值）、**旋转行跳动根治（v0.15，2026-11-07 验收通过——欧拉口径切 Unity 同款 Z-X-Y 换算单点 RotationMath + 面板级自旋回声抑制（事件原始四元数同旋转判定，判据按模长归一））**、**v0.17 节点面板底部「添加组件」（常驻底栏不随内容滚动，候选=注册表枚举 Component 子类且贴 @InsHeader + 选择窗，唯一冲突灰显不可选，2026-11-08）**、**v0.18 组件头右键菜单（当前项=删除组件；required 置灰、折叠只认左键；core 补 Node.removeComponent，2026-11-08）**、**v0.19 材质 Shader 换绑落共享实例（换绑引擎帧首原位 setShader，原「换绑例外不落共享实例」作废；2026-11-10 验收通过）**、**v0.20 材质面板装载只走 Library 产物（文本源只喂导入器；产物缺失=诊断态可重选重试；分歧守卫退役，内存即真值；新增 `ProjectAssetLibManager.loadCache` 只查缓存口）**、**v0.21 纹理面板装载同改（缓存/产物实例为编辑载体，v0.12 文本私有实例作废；产物缺失=诊断态；2026-11-10 落地待验）**、**v0.27 条件子组 `@InsFieldGroup`（字段取值命中才内联显示嵌套参数容器，递归展开为「容器.子字段」、容器空即抛）+ `@InsSlider` 滑条行（`JeSliderField` = 滑条 + 数字框）**、**v0.28 组头可折叠（`label()`/`collapsed()`，整行宽扁平按钮 `▾/▸`，显隐 = 父值命中且未收起）** |
| `Engine_Input_Design.md` | 引擎输入设计（v0.1）：三层架构（Window 回调→InputSubsystem 状态机→Input 静态门面）、bind/查询合流 Input、边沿双缓冲帧语义、GLFW/AWT 双接入 |
| `Engine_DebugText_Design.md` | DebugDrawText 调试文本设计（v0.2）：bgfx 式全屏点阵文本（含汉字，静态 16x16 1bpp 字库）、attr 16+16 色调色板、持续到 clear、buildFrameGraph overlay 挂点；**内置资源 2026-11-07 迁 `builtin-asset-core/debug`**——字库源形态 = 新资产类型 `.chars`（字符集纯文本，导出 4532 字形）经 `BitmapFontImporter` 用打包思源黑体烘焙 `BitmapFont`，vs/fs 走 SpvShader 导入链，子系统经 AssetManager 装载（tool CLI `DebugTextAssets` 与 `core/resources/debug/` 同批清退，§4） |
| `Engine_RenderStats_Design.md` | 渲染统计设计（v0.1）：GPUInfo（设备/驱动信息）/RenderStats（11 类存活资源计数 + draw call/三角形快照双缓冲）RHI 打点口径、引擎显式 resetFrameStats、F3 开关 DebugText overlay（默认显示，占行 0~7） |
| `Engine_Collision_Design.md` | 引擎碰撞设计（v0.3，**2026-11-23 修 `Aabb.transform` 行和取元素索引（列↔行取反）——非均匀缩放 + 旋转下包围盒某轴恒取小缩放档，§5 实现勘误 + 回归用例**）：纯数学层 L0（Ray/碰撞形状/Aabb/相交例程，D1~D9 决策表）+ **L1 场景级 raycast 落地（Physics 静态门面 raycast/raycastAll + RaycastHit + Scene.getColliders 注册表投影，§10）**/L2 物理系统预留；Collider 组件族 + fitFromMesh + 世界 Aabb；拍板：碰撞体=组件、刚体独立后置、Ray 收窄为运行时物理查询、射程=maxDistance 参数（Unity 口径，三拍定稿） |
| `Engine_Picking_Design.md` | GPU 拾取设计（v0.2）：**RHI 读回环（TextureReadbackRing 非阻塞）+ 覆盖制 PickPass（不消费材质 Pass 声明——看得见即拾得；着色器 2026-11-07 改走资产导入链，源码 `builtin-asset-core/pick` + AssetManager 装载）+ PickSystem（click；hover 无消费者即不投递、帧表防串帧、落点 core 运行时可用）+ 编辑器消费（点击门控/选中收口）**；**v0.2 范围收窄（2026-11-08）：ID 附件只服务场景物体，Gizmo 柄拾取改 CPU 碰撞体射线（可见几何与拾取体解耦——轴太细/环太粗根治）**；透明物体可拾取（Unity 口径）；已知边界与验收清单见档内 |
| `Editor_DebugLine_Design.md` | 编辑器调试线设计（v0.1）：**编辑器专属世界空间线框（门面+batch+覆盖制 DebugLinePass 插 Transparent 后深度咬合/MSAA 双分支）+ 组件绘制器策略层（ComponentDebugLine 查表分派，mesh bounds 世界 AABB/相机视锥/BoxCollider）+ 选中驱动每帧 clear 重提交**；GAME 视口无线；**着色器 2026-11-07 改走资产导入链**（源码 `builtin-asset-editor/debugline` + AssetManager 装载，tool CLI 同批清退，§3.5）；验收清单见档内 |
| `Engine_AssetReload_Design.md` | 资产热重载设计（v0.6）：**反射遍历流派（持有引用不动，重载协调器反射走场景对象图换 Asset 字段指针）+ AssetLibManager invalidate/reload + Material 补默认值后处理（pack 缺 entry NPE 实锤）+ RenderingSystem 孤儿两帧退役 + 焦点扫描/手动 reimport 触发**；**热重载范围收窄只 shader（2026-10-29 拍板——文件真值源+渲染侧按实例身份烤死；.mat/.texture/.smesh/像素源摘出「后面再加」，targetClass 加回分支即恢复）**；**v0.6 材质 shader 换绑改落共享实例（检查器引擎帧首原位 setShader，收窄口径与 targetClass 不变，2026-11-10 验收通过）**；setter 旁路警示、失败安全（导入失败不 invalidate=天然回退）与批次划分见档内（v0.1 令牌表方案同日推翻，沿革在档） |
| `Engine_DebugTools_Plan.md` | 引擎调试工具计划（v0.5，2026-11-10 立项起因=Pitfalls #35 排障半日；**v0.5 = 2026-11-25 补 §3.7 光照 gizmo 落码（REND-13）：`LightGizmoAppState` 全场景灯线框（箭头/三轴大圆/聚光锥，F7 / 行 13）经 `DebugLineAppState` 代提交，待验收**；v0.4 = 2026-11-24 §3.6 shadowMap 可视化（DBG-03））：两件排障基建——**dbg-1 校验层消息回调**（`engineDebug` 设置默认 false（2026-11-21 改拍，排障用 `--debug=true` 打开）、启动期消费、**无 SDK 报错不启动**推翻静默降级；messenger 接进 logback，越权/错序坑当场现形）+ **dbg-2 G-Buffer 可视化**（F4 循环 albedo/法线/世界位置、覆盖制贴屏、HYBRID 档专用）+ **dbg-3 阴影图可视化**（菜单「工具｜阴影图视图」+ F6，编辑器相机视口）；SPIR-V 静态自检后置挂账；批次划分与拍板记录见档内 |

### 实现进度文档

| 文档 | 内容 |
|------|------|
| `Engine_Implementation_Plan.md` | 引擎实现计划（v0.1）：六阶段路线（接线 RHI→场景→渲染→材质→子系统+输入→物理/音频）、RHI 遗留修复插入时机、开放项拍板表 |
| `Engine_Roadmap.md` | 引擎阶段路线**总纲**（v0.8）：一期渲染基础关账总结、二期引擎功能（输入/碰撞/Ray/编辑器基本）、**三期光照收官（clustered Forward+ 五批+单张硬影+视觉天空盒+Deferred 复用——2026-11-10 ④-B 验收关账）**、四期高质量渲染（2026-10-31 立项——**2026-11-14 拍板 = 当前最高优先级主线**，§4 施工序 ①~⑦：SSAO→阴影质量分级→TAA→环境光→非光照材质语义→DDGI→光追；后处理链已验收），与六阶段计划的映射；§6 跨期遗留已收敛至 `Engine_Backlog.md`（留指针） |
| `Engine_Roadmap_Progress.md` | 阶段路线**分卷**（v0.14）：批次索引——**§2 二期已再分卷**（`Engine_Roadmap_Progress_P2.md`，2026-11-15 超红线 16 上限后拆出）、**§7 四期已再分卷**（`Engine_Roadmap_Progress_P4.md`，2026-11-22 总卷 86KB 超限后拆出——总卷只留一行索引与指针）、三期散件、§5 批挂靠归档、§6 已收口专项归档（2026-11-08 从总纲拆出并压成索引，红线 12 的落点） |
| `Engine_Roadmap_Progress_P2.md` | 二期批次索引**分卷**（v0.1，2026-11-15 自分卷总卷 §2 拆出，红线 16）：§2.3 Ray + §2.4 编辑器基本，共 40 条（日期｜要点｜落点｜验收） |
| `Engine_Roadmap_Progress_P4.md` | 四期批次索引**分卷**（v0.1，2026-11-22 自总卷 §7 拆出，红线 16）：四期高质量渲染批次（后处理/SSAO/阴影/TAA/环境光/DDGI 等 46 条；**已验收压一行索引、未验收保留全文**；四期新批次进本卷） |
| `Engine_Features.md`（根目录） | 功能清单（v0.1）：按模块（RHI/核心/编辑器/构建链/支撑）分级盘点每项功能的完成状态+验收状态（方式+日期）——**验收状态/日期全项目单点**；§6 待验收挂账（§7 已知问题已并入 `Engine_Backlog.md`，原处留指针） |
| `Engine_Backlog.md` | 引擎待办总账（v0.1，2026-11-11 建档）：**「已计划、尚未完成」的唯一登记处**——未开工 / 半成品 / 后置项 / 待拍板 / 已知问题（bug 类）按域收敛，一项一行（ID 稳定不复用｜待办｜落点简写｜归类｜依赖）；§1 近期候选**四期施工序居首（2026-11-14 拍板「四期 = 最高优先级」，其余候选让位）**、§3 四期条目按施工序 ①~⑦ 排列；**条目完成即移出**（进 `Roadmap_Progress` 批次索引 + `Features` 验收状态，编号留空档）；与 `Features` §6（已落地待验收）/ §7（已并入本文 §13）/ `Roadmap` §6（留指针）分界 |
| `RHI_Implementation_Progress.md` | RHI 实现进度记录：各阶段完成状态、文件清单、已知问题、API 签名确认记录 |
| `Vulkan_Pitfalls.md` | Vulkan / LWJGL / GLFW / Java NIO 陷阱记录（症状→根因→修法→出处）+ GPU 排障方法论 |

### 参考资料（doc/）

| 文档 | 内容 |
|------|------|
| `Bgfx_Examples_Reference.md` | bgfx 官方示例集速查索引（v0.1）：`00-helloworld`~`51-gpufont` 共 52 个示例逐个列「官方描述 / 演示技术点 / 本引擎参考点」+ `common`/`assets`/`runtime` 支撑目录；来源=外部检出 `D:\Workspaces\java\bgfx\examples`（不随本库版本管理）——实现渲染技术前先查有无现成参考 |
| `Babylon_Skybox_Reference.md` | Babylon.js 天空盒实现参考（v0.1）：天空盒=普通网格+普通材质的字段组合（`infiniteDistance` 跟随相机 / `ignoreCameraMaxZ` 临时无限远投影 / `SKYBOX_MODE` 局部坐标采样）+ 三材质形态与两条装配路径 + CubeTexture 家族与 equirect→cube 转换约定——含与本引擎 `Engine_Skybox_Design` 的逐项对照；来源=外部检出 `D:\Workspaces\java\Babylon.js`（不随本库版本管理） |
| `Babylon_Rendering_Reference.md` | Babylon.js 渲染技术全景参考（v0.1）：执行模型（4 renderingGroup + 材质分组排序）/ uber-shader+defines 材质与插件注入 / 光照（clustered 每灯 80B+GPU tile mask、IBL 三件套 SH+mip+BRDF LUT）/ 阴影（PCF 硬件比较采样 4-9tap 分布、CSM lambda 切分与纹素吸附、RSM GI）/ 后处理（链式 pass+共享输出+第一 pass 独占 MSAA）/ FrameGraph（瞬态别名+history，无拓扑无 barrier）/ G-buffer（PrePass 动态 MRT）+ SSAO2/SSR/OIT + §12 着色器源码可移植性分层（纯函数清单 + Apache-2.0 许可）+ §13 Gizmo 模块（距离自适应缩放/放大不可见 collider 拾取/utility layer 载体）——含与四期路线的借鉴清单；来源=外部检出 `D:\Workspaces\java\Babylon.js`（不随本库版本管理） |
| `GI_RT_OpenSource_Reference.md` | GI / 体素 / 光追开源参考索引（v0.1，2026-11-16 建档）：服务四期 ⑥（P4-05）、⑥ 前置（P4-17）、⑦（P4-06）——Godot 4 SDFGI/VoxelGI（MIT，Vulkan RD+GLSL compute，五个 `sdfgi_*.glsl` 切分、仅 Forward+、非屏幕空间、约 30 帧收敛）/ G3D DDGI 官方参考 GLSL（**BSD-2**，octahedral atlas + 矩插值 + 探针轮转）/ DDGI-Minecraft（Apache-2.0，Vulkan compute 软光追 RVPT，无 RT 硬件的首版工程模板）/ O3DE DiffuseProbeGrid（四条 compute pass 划分范式）/ nvpro-samples 16·17（rayQuery 与 compute 内 rayQuery AO）/ Khronos Vulkan-Samples / The-Forge（AS 双后端抽象）/ Kajiya（后置 ReSTIR）+ **§8.1 仓库地址表（含 G3D 无 GitHub 官方镜像、RTXGI 主仓+DDGI 专版）**+ 许可三级（绿可抄署名 / 黄 Flax·RTXGI 只读 / 红 UE·Unity 不开放）+ §9 按 P4-17·P4-05·P4-06 的「抄哪层」适配表 + **附录 A：UE5 Lumen/DFAO 只读参照（私有仓库 EpicGames/UnrealEngine + 公开技术文档；GDF 相机 clipmap 与两级 SDF 锥体混合，首版不抄）** + §10 待核实清单（G3D/Godot 文件级细节、UE EULA 条款、LWJGL 光追签名走 IDEA MCP）；来源=外部仓库与官方文档（不随本库版本管理） |

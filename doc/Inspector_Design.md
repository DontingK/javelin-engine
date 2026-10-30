# 检查器（Inspector）设计（v0.14，2026-10-30 随多材质实机验收销账 + i18n 全量化随批）

> 定位：`javelin-editor` 选中对象检查器面板（Node/资产文件经注册器统一接管，§11）——Java 反射收集字段清单（`InspectorReflector`，
> @InsField 显式闸门 + 读 getter 写 setter 铁律不变），M4 Swing 面板直调消费。
> 设计基准=完全参考 Unity（Editor_Design §1）。
> v0.13（2026-10-29）：**GUID_ASSET_LIST 资产列表通道（§4）**——MeshRenderer.materials
> 多材质行（行数跟随子网格数，fieldName 编码序号 materials[i]，整列表 setter 回写）。
> v0.14（2026-10-30）：**标签 i18n 全量化**——`@InsField/@InsHeader/InsEnumLabel` 的
> label 值改「editor.core 语言包局部键」（core 侧存中性键、InspectorReflector 三解析点
> componentLabel/displayName/enumLabel 经 EditorLang 解析，core 零 i18n 依赖）；
> 面板文案（材质/纹理/着色器/节点全件、字段行校验异常、「无检查器」空态等）随批迁
> `EditorLang`（异常面收 I18nKey，见 Editor_Plugin_Design §9）。
> （2026-10-18 去 Web 化：原「经桥下发、Web 渲染、编辑回写」通道随 jcef 清退作废——
> /inspector/* 桥已删、三 VO 迁 inspector 包；本文类型通道/序列化/回写规则继续有效，
> v0.1~v0.3 落地与验收记录为 Web 期沿革。）
> 落地：2026-10-09 全链首版（仅 Transform 打通流程）→ 2026-10-11 头部 Tag/Layer 可
> 编辑 + 间距规范 + 星标联动；用户实测验收通过（2026-10-11）。
> v0.2（2026-10-11）：**Camera 组件接入**——组件白名单放开（§3）、ENUM/COLOR/RECT/
> LAYER_MASK 四值类型通道放开（§4）、`@InsLayerMask` 注解落地、枚举选项/显示名下发
> （`InsEnumLabel` 接口，ProjectionType/ClearFlags 首批实现）、setter 同名重载按参数
> 类型解析（§3，cullingMask 双重载实锤）、字段 label 中文显示（Unity 口径，对齐
> Transform 位置/旋转/缩放先例）；可空值：backgroundColor 序列化 null + 编辑即物化、
> viewportRect 默认显式 (0,0,1,1)（Unity 口径，遗留 null 显示代理，§4）；**星标即时化（§7）：markDirty 由净变脏翻转沿发布
> scene.dirty（`SceneDirtyEvent` 新事件）——任何属性修改前端本地亮星、不重拉树
> （scene.changed 语义回归树刷新，用户拍板 2026-10-11）**；JeSelect 下拉遮挡
> 修复（CollapsiblePanel overflow 吞浮层 + 换向判定按最近裁剪祖先，§6）。
> v0.3（2026-10-17）：**GUID_ASSET 资产引用通道 + MeshRenderer 组件接入**——
> Asset 引用型字段（mesh/material 首批）只读展示（§4 三态 wire 契约），显示名
> guid 反查源路径尾名（AssetManager 新增 `pathOfGuid`）；**赋值通道=项目面板拖拽
> 资产（用户拍板，后置遗留）**；回写侧显式拒绝（"不支持字符串回写"）。
> v0.4（2026-10-18）：**M4 Swing 面板实装（§6——v0.1~v0.3 通道面的 Java 直调重现；用户实测验收通过 2026-10-18）**——三 VO 引用化（objId → Node/Component 引用直持、字段行 VO 删宿主锚点）、
> LAYER_MASK 层名选项随 VO 下发（value=层索引串，勾选弹层数据源）、markDirty 收口
> 进 InspectorPanel 回写出口（原桥层职责）、`NodeTransformChangeEvent` 首个消费方
> （Gizmo 拖拽定点刷 Transform 三行，四元数→欧拉同收集侧口径——较 Web 版防抖 150ms
> 重拉整面板更精细）、编辑保护（控件编辑中重建延后，提交收尾补拉）、组件折叠态
> 跨重建按名记忆；COLOR 控件形态拍板=色块 + JColorChooser（alpha 保留原值不进
> 拾色器，2026-10-18）；失败提示走状态栏（`MainFrame.getStatusBar()` 本批新增）。
> 验收期实测修正四条（Editor_Swing_Design §7.7 ③④⑨⑩）：归属校验比对对象
> （getGameScene 本体）、JScrollPane 视图 Scrollable 双向钉（高不拉伸/宽不水平滚）、
> 节点头/多段行/折叠头布局逐轮打磨（见 §6）。
> v0.4 增量（2026-10-18）：**`@InsHeader` 组件分组头注解**——@InsField 的 TYPE 职责
> 收编成独立注解（label=显示名（空串兜底类简单名）、icon=图标名（classpath
> /icon/{name}.svg 裸名口径，空串不渲染）），@InsField 收窄回字段闸门（TYPE 贴法
> 废弃，其 icon 属性失消费方待清理）；分组头图标 16px 经 SvgIcon 渲染、资源缺失
> 不占空位（菜单域同口径）；Transform/Camera/MeshRenderer 三组件首批贴注
> （「变换」「相机」「网格渲染器」，icon=transform/camera/mesh——mesh.svg 本批新画）。
> **@InsHeader 升格组件 opt-in 闸门**（同日调整）：收集改遍历 `node.getComponents()`
> （Node 新增公开只读口，`components()` 维持 package-private 不动）——贴注解的组件
> 才进面板、旧白名单 `INSPECTABLE_COMPONENTS` 退役；挂载序=面板分组序（Unity
> 口径），Transform 构造首挂恒首位；多实例组件全部入面板，节点单组件假设作废。
> v0.6（2026-10-22，**用户实测验收通过**）：**输入交互增强（§12）**——数字键入
> 即提交（enter/blur 才应用作废）、文本提交时机归使用方（默认 enter/blur、可选每键
> 通道）、label 按住左键左右拖调值（Unity scrub 口径，每像素步长可配：默认整 1/浮
> 0.1、range 字段=注解 step）；滚轮调值与无限拖（指针回绕）试做后按用户要求移除。
> v0.7（2026-10-25，**用户实测验收通过**）：**.shader 资产只读诊断台（§11 注册器第三键，
> ShaderInspectorProvider/Content）**——Unity 着色器检查器对照（无编辑字段、诊断
> 台定位）：后台线程经伴生 .meta 解 guid（2026-10-29 起单源；旧=文件头/meta 双源）→ AssetManager.loadGuid 读 Library 产物
>（**读产物即编译后真值**，与材质面板读源互补）、EDT 组装三段——头（文件/Shader/
> GUID）+ 属性声明表（名/类型/默认值或区间或贴图占位）+ Pass 状态表（Cull/ZWrite/
> ZTest/Blend 底稿摘要）；产物缺失=显式诊断态（对应 Unity 编译错误区，导入失败/
> 未导入可判）、guid 双源皆缺同显式态；代际守卫同材质面板。「Show compiled
> code」等价物（注入后 GLSL 查看）留后置。
> v0.8（2026-10-25 落地，**用户实测验收通过**；方案经用户拍板「确认」）：**.mat 联动第一项：
> AssetInput 资产通用输入（Unity ObjectField 口径，§6 widget 第八件）**——构造传
> {@code Class<? extends Asset>} 声明接受类型（TYPE_SUFFIX 私有表 Shader/Mesh/
> Material/GameScene 四行，资产管线落地新类型补行、未知类型构造抛错）；值=guid
> wire 三态（null=None 清空合法/空串=内存创建/非空=guid，GUID_ASSET 同构）；三
> 输入通道=选择窗（AssetPickerDialog：模态、后台 listAssetPaths 拉取、None 恒首、
> 搜索过滤、双击/确定提交、取消不动值）+ 项目面板右区拖拽（JList 拖源
> javaFileListFlavor 传 File；AssetInput 按扩展名过滤 + guid 解析——2026-10-29 起
> 伴生 .meta 单源，旧=文件头优先伴生 .meta 兜底）+ setValue 外源刷新；提交走 Consumer<String>（机制归控件、
> 策略归使用方先例）；FieldEditor 兼容（isEditing 恒 false——原子提交无逐键态）。
> **core 新口 AssetManager.guidOfPath**（pathMap 反查、pathOfGuid 对偶——选择窗
> 路径条目取 guid 提交；Stub 桩同步补）。**同日追加：材质检查器 Shader 行换装
> 落地**（MaterialInspectorContent 增量）——onShaderCommit 提交链：材质必绑
> Shader（None/空拒绝，显示回滚旧值）→ loadGuid 取 Shader（失败拒绝）→
> **setShader 即 core 全量重置语义**（values/textures 清空按新属性表填默认值——
> pack 缺 entry NPE 坑在 core 正面消解，无需编辑器侧重建）→ TextExporter 即时
> 写回 .mat + submitImport 重导（**Unity 资产即时保存口径拍板：资产无脏标，
> 改即落盘**）→ build 重刷面板；写回失败=盘面即权威，从盘面重载自正。运行期
> 在用材质实例热切归资产热重载批次（未实现，重载场景后生效）。参数/贴图槽
> 编辑见 v0.9（参数已可编辑、贴图槽仍只读）。同日**布局美化**（用户派发）：三段折叠分组（材质/
> 参数/贴图槽，CollapsiblePanel 灰头亮内容+material 头图标）+ 两列字段网格
> （FieldGrid：label 列自适应、控件列填满，ComponentSection 同口径）替代原
> FlowLayout 左挤行；类型感知值展示（COLOR=只读色块 ColorSwatch+数值，圆角/
> 描边同 ColorField 口径）；贴图已赋显示名（guid 反查尾名）、诊断行（文件/
> GUID/占位）暗色；重建折叠态复位展开（无记忆机制，后置随需）。
> v0.9（2026-10-25，**用户实测验收通过**）：**材质参数编辑落地（.mat 联动主目标）+ 分组形态拍板**
> （用户派发「材质不可以折叠/参数为啥不可以编辑」）——①「材质」信息分组**固定
> 不可折叠**（CollapsiblePanel 增 `setCollapsible(false)`：点击/hover/箭头全关，
> Unity 材质头口径；参数/贴图槽维持可折叠）；②参数行**类型感知可编辑控件**（全
> 复用 widget 家族：COLOR=ColorField（拾色器，alpha 保留原值）、VECTOR=VectorRow
> 四段 X/Y/Z/W（任一段提交整行组包）、FLOAT/RANGE=JeNumberInput（**RANGE 按
> .shader 声明 min/max 硬钳**，键入即提交+label scrub）、面型=JeSelect 不透明/
> 透明——透明分桶裁决源）；③**提交链=写内存实例 + 防抖持久化**：键入即提交的
> 高频提交 × 写盘重导重活，单发 Timer 500ms 合并阀（restart 滚动续期），换选前
> flushPending / onDetach 兜底防丢尾改，写回失败=盘面即权威从盘面重载——Unity
> 资产即时保存口径的工程化形态；④换绑 shader 仍即时持久化（单发动作）+ 面板
> 重刷（参数重置新默认值）。贴图槽编辑待贴图资产管线（AssetInput TYPE_SUFFIX
> 无 Texture 键）；运行期在用材质热切归资产热重载批次。
> 追修（2026-10-25，用户报「label 不是 shader 里定义的显示名」）：参数/贴图槽行 label
> 误取条目绑定名（如 `_Tint`）——改 shader Properties 的 **displayName**（Unity 口径，
> 新增 `displayName(material, name)` 解析，shader 缺失/属性失配/显示名空回退绑定名）。
> v0.10（2026-10-25，**用户实测验收通过**）：**GUID_ASSET 通道升可编辑（用户派发「MeshRenderer
> 的检查器需要放出材质输入」）**——字段行工厂 GUID_ASSET 分支换装 AssetInput
> （**通道级通用：mesh/material 双行同享**，非材质单行特例；声明类型随 VO 新增
> assetType 下发——PropMeta 由 field.getType().asSubclass(Asset.class) 携带）；
> 回写侧放开（原「不支持字符串回写」拒绝退役）：wire **null=None 清空合法**（Unity
> 口径：MeshRenderer 材质可空）、空串=内存创建拒绝、guid 经 **loadGuid 声明类型**
> 解实例（共享缓存，同 .scene 序列化口径；未索引=资产不存在拒绝防静默置 null）；
> 提交链复用字段行既有链（submitTask 帧边界 ｛归属校验→apply→markDirty｝，场景
> 保存时 .scene 按新 guid 序列化）；只读前身 GuidAssetField 退役删除。运行期在用
> 引用热切归资产热重载批次（换材质后重载场景生效）。
> v0.11（2026-10-25，**用户实测验收通过**）：**材质编辑即时联动渲染——编辑载体改「缓存共享
> 实例优先」**（用户报「检查器和场景的材质实例不是同一个、改材质不同步」：原
> 面板 TextImporter 私有实例编辑、仅落盘重导，场景内存实例无人换指针）——
> .mat 面板加载链=文本源解析（guid 来源+兜底载体）→ loadGuid 查缓存，命中且
> **shader guid 与盘面一致** → 直接编辑场景 MeshRenderer 持有的同一实例：数值/
> 面型提交即时生效（三项前提已核实：RenderingSystem.materialSet 每帧重打包
> MaterialBlock、收集期逐帧按 surfaceType 分桶、pipelineFor key 含面型）；缓存
> 未命中由 loadGuid 落 Library 产物并注册（后续场景加载同享）；产物缺失/损坏
> 或 shader 与盘面分歧（换绑后）走文本解析实例兜底——**分歧守卫**防旧实例防抖
> 写盘把换绑悄悄导回旧 shader（数据丢失级坑）。**Shader 换绑例外不落共享实例**
> （渲染侧 MaterialResources 按 shader 首绑烤死 UBO 尺寸/贴图绑定数，live
> setShader 会越界/错配）：改文本重解析实例上换绑+写盘+重导（生效口径同 v0.8
> ——盘面先行、场景实例随重载/热重载批次换指针），onShaderCommit 先
> flushPending 防丢尾改。彻底根治（外部改盘感知、贴图槽 live 编辑、指针级
> 换绑）归资产热重载批次（Engine_AssetReload_Design v0.2 已定稿待派发）。
> **同日追修（用户复验报「MeshRenderer 材质回显还是显示 None」）**：根因=
> FieldRowFactory GUID_ASSET 分支漏传初值（其余通道均构造传 vo.value，AssetInput
> 无初值构造恒显 None）——修=创建后 setValue(vo.value)（三态齐走：null=None/
> 空串=内存创建/guid）；同修 AssetInput 显示名**去后缀**（「xxx.mat」→「xxx」，
> Unity 资产名口径，对齐 Reflector.guidAssetLabel 先例——用户点名「Unity 是直接
> 显示的文件名称」）。**再追修（用户报「资产选择器不应该能选编辑器的」）**：选择器
> 列表源=listAssetPaths 扫全部监视根，`.javelin/assets`（BuiltinAssetReleaser 内置
> 释放目录）的条目一并入列——AssetPickerDialog 补过滤，**范围仅 editor 组**（用户
> 拍板：core 组内置原始体/默认着色器照常可选=Unity builtin 口径，editor 组
> gizmo/网格辅助线等编辑器内脏不列）：索引键相对各自监视根，editor 组键以
> "editor/" 起头、以 `.javelin/assets` 下存在同名文件坐实（防用户 Assets/editor/
> 撞名误伤，后台线程纯 IO）；项目面板树根仅 Assets/src，拖放通道天然不达
> .javelin，无需同款守卫。
> v0.12（2026-10-25，**当日用户实测验收通过**）：**.texture 纹理面板（Engine_Texture_Design v0.2
> 贴图管线批次）**——TextureInspectorProvider/TextureInspectorContent（.texture 键）：
> 「纹理」固定分组（文件/图=AssetInput/GUID）+「采样」分组（filter/wrap JeSelect
> 中文名）；图换绑即时写回重导（None 合法=纹理壳、空串拒绝、loadGuid 校验存在）、
> 采样提交防抖 500ms（.mat 同款合并阀+flushPending 兜底）。**编辑载体拍板=文本
> 解析私有实例**（与 v0.11 共享实例分歧）：Texture2D 冻结语义（上传后
> setFilterMode/setWrapMode/setImage 抛）使共享已上传实例不可编辑——即时联动统一
> 归资产热重载批次。配套 AssetInput/AssetPickerDialog 升**多后缀**（TYPE_SUFFIX
> 值改列表，Image=[.png/.jpg/.jpeg] 首个多扩展名类型：选择窗按列表枚举、拖放按
> 列表过滤）；~~材质面板贴图槽行仍只读~~。
> 追修（2026-10-25，用户报「mat 检查器没有纹理的属性」）：根因=材质面板行单源错位——行枚举
> material 存量条目（绑 shader 那刻播种），模板后增属性（NewShader 加 _MainTex）存量条目不长
> →行缺失。修=**参数/贴图槽行单源改 shader Properties 声明**（Unity 口径：模板是属性单源、
> 条目只存值——模板增删属性面板即时跟随；条目缺值回退属性默认值，提交走 setFloat/setColor
> 落条目即补齐；shader 不可得回退条目迭代残档兜底）。
> 再追修（同日，用户报「纹理托不上去 + 纹理为啥单独折叠面板」）：**材质贴图行换装提前落地**
> （原归下批）——TEXTURE_2D 行换 AssetInput(Texture2D)（选择窗/拖放、None=占位贴图合法、
> 空串拒绝、loadGuid 校验、即时写回重导=Shader 行同款；拒绝路径=整面板 build 重刷回滚）；
> **参数/贴图槽合组内联**（Unity 口径：贴图行与其他参数按声明序同网格、不按类型分组——
> 「贴图槽」独立分组退役）；同批 .texture 面板「采样」两下拉并入固定「纹理」组（不值独立
> 分组，用户拍板合组）。materialSet 按材质缓存烤死贴图绑定——live 换绑不重绑，生效=场景
> 重载/热重载批次。

## 1. 总则

- **读 getter、写 setter 铁律**：`Transform.setLocalXxx` 内含值拷贝 + markDirty 脏传播，
  裸 `Field.set` 绕过（localToWorld 不重算）——字段定位可反射，值通道不裸写字段；
- **@InsField 显式 opt-in 闸门**（等价 Unity `[SerializeField]` 语义）：未标记一律不展示；
  已标记缺 public get/set 或类型不可映射 → 构建即抛错（显式意图不静默吞）；
- **线程**：收集/回写经 `JavelinEngine.submitTask` 帧边界引擎主线程执行（与 mainLoop
  场景访问无竞争；v0.4 Swing 面板读写同队列串行不交错——EDT 不活读引擎对象图）；
- **回写即置脏**：回写成功 `EditorScene.markDirty`（v0.4 收口进 InspectorPanel 回写
  出口——原桥层职责随桥清退移交；TipException 走状态栏提示，UI 侧钳制前置使该路径
  以不可达兜底为主）。

## 2. 通道（桥协议已废，v0.4 直调形态）

~~桥协议 `/inspector/info` + `/inspector/setValue`（jcef 桥，2026-10-18 整包清退）~~。
Swing 面板直调：拉取 = `InspectorReflector.collect(Node)` 帧边界组装；回写 =
`InspectorReflector.apply(host, fieldName, valueType, value)`。

`NodeInspectorVo`：ready / **node（引用，v0.4 引用化）** / nodeName / layer（索引）/
layerNames / tags / tagNames / active / components[]（**component（引用）** / name /
enabled / required / fields[]）。
`InspectorFieldVo`：fieldName / name / valueType / value / range / options /
assetName（v0.3，GUID_ASSET 显示名，其他类型 null）——字段行 VO 无宿主锚点
（宿主=所在分组 `component`）。

## 3. 字段收集（InspectorReflector）

- 收集面 = 节点头（手拼 VO）+ **@InsHeader 闸门组件扫描**（v0.4 增量）：遍历
  `node.getComponents()`（Node 新增公开只读口，挂载序），贴 `@InsHeader` 的组件
  才进面板——旧白名单 `INSPECTABLE_COMPONENTS` 退役（v0.1~v0.3 沿革：Transform
  → v0.2 Camera → v0.3 MeshRenderer 渐次放开）；挂载序=面板分组序（Unity 口径），
  Transform 构造首挂恒首位；多实例组件全部入面板，节点单组件假设作废；
- 类 → 属性清单缓存（ConcurrentHashMap，首次访问构建一次）：沿类层级收集声明字段，
  @InsField 标记才进清单；getter 按 getX/isX（boolean）、setter 单参 setX
  **按参数类型与字段类型装箱等价解析**（findSetter：getMethods 顺序不保证，同名重载
  putIfAbsent 取首个即随机命中——Camera.cullingMask 的 `setCullingMask(int)` 与
  `setCullingMask(Layer...)` 双重载实锤后改为显式匹配；零参 getter 无重载免解析），
  缺一抛错；
- 注解（`core.inspector` 包）：`@InsField`（label=**i18n 键**（editor.core 语言包
  局部键，反射层经 EditorLang 解析；空串兜底字段原名，v0.14）；TYPE 贴法已废弃——
  组件头职责移交 `@InsHeader`，icon 属性随之失消费方待清理）、`@InsHeader`（TYPE
  贴组件类：label=**i18n 键**（空串兜底类简单名）、icon=图标名（classpath
  /icon/{name}.svg 裸名，空串无图标；分组头 16px 经 SvgIcon 渲染、资源缺失不
  占空位——菜单域同口径））、`@InsRotation`（Quaternionf opt-in 按欧拉角展示）、`@InsIntRange`/`@InsFloatRange`
  （滑条元数据投影为 range）、`@InsLayerMask`（int 字段按层位掩码勾选多选，
  v0.2）、`InsEnumLabel`（**接口**，枚举实现 getLabel() 返回 **i18n 键**（v0.14）供下拉
  显示名解析，未实现兜底
  常量名；ProjectionType=透视/正交、ClearFlags=颜色+深度/仅深度/不清除 首批实现——
  显示名口径=自述行为，SOLID_COLOR 不用 Unity 直译「纯色」，用户拍板 2026-10-11）；
- 组件头：enabled 提升分组头勾选不入行；`required` 标志（Transform 必需组件不渲染
  勾选，Unity 口径，将来组件删除菜单同用此标志）；显示名/图标经 `@InsHeader`
  收集进 VO（componentLabel/componentIcon，v0.4 增量）。

## 4. 值类型（InspectorValueType）与序列化口径

INT（Layer 字段=索引串，读写经 Layer.of/getValue 换算）/ FLOAT / BOOLEAN（"true"/"false"）/
STRING / VEC3_F（"x,y,z"）/ ROTATION（欧拉角度数 "x,y,z"；四元数↔欧拉换算单点在 Java
侧）/ ENUM（常量名；options 随 VO 下发，显示名走 InsEnumLabel）/ COLOR（"r,g,b,a"
0-1 浮点，回写构造自动夹取 [0,1]）/ RECT（"x,y,width,height"）/ LAYER_MASK（位掩码
int 串，前端勾选式多选按 Layer 名投影）/ **TAG（节点头专用通道，§5）**/
**GUID_ASSET（v0.3 引入、v0.10 升可编辑：Asset 引用型字段三态 wire：value=null=无
资产"None"（清空合法，Unity 口径）、空串=内存创建（guid 空，只读显示禁用态「（内存
创建）」，回写拒绝）、非空=guid 原串；显示名另走 VO assetName=guid 反查源路径尾名
去后缀（`AssetManager.pathOfGuid`），反查失败兜底 guid；声明类型随 VO assetType
下发（AssetInput 接受范围与回写 loadGuid 类型）；回写=guid 经 loadGuid 解实例、
未索引拒绝；行控件=AssetInput，v0.3 只读形态已随 v0.10 退役）**。
**GUID_ASSET_LIST（v0.13，2026-10-29：资产列表字段，List&lt;Asset 子类&gt; 泛型实参
判据——MeshRenderer.materials 首例）**：收集面展开为 N 行（N = 列表长度 min 1——
列表长度由 setMesh/setMaterials 按子网格数对齐，行数即子网格数，未赋 mesh 兜 1；
场景加载后不强制对齐），每行一个元素、行控件/回显/回写全复用 GUID_ASSET 面
（AssetInput + 元素级 guid 三态 + assetName/assetType=元素类型）；fieldName 编码
序号（`materials[i]`）供回写定位，applyAssetList 解析序号 → guid 解实例（同
GUID_ASSET 三态语义）→ 复制现列表、i 位替换（缺位补 null）→ 整列表 setter（读
getter 写 setter 铁律）；列表通道 setter 查找放宽（参数 List 可从 ArrayList 字段
赋值）；资产行提交后检查器补拉重建（mesh 行换绑 → 材质列表对齐行数变）。
ENUM/COLOR/RECT/LAYER_MASK 编解码 v0.2 放开（Camera 接入驱动；前端 Ins 控件族与
VO options 字段 v0.1 已预留，本片纯 Java 侧反射器补齐）。

- 浮点显示/输入统一最多 4 位小数：Java `formatFloat`（BigDecimal HALF_UP + 去尾零，
  `0.30000012→"0.3"`）+ 前端 `JeNumberInput` precision=4 三路钳制（显示/输入/滚轮）；
- **可空值物化语义**：backgroundColor null=跟随引擎全局背景色——序列化传 null
  （前端控件兜底显示不误写），用户编辑即物化显式值（null→显式单向，清回 null 归
  后续「继承/显式」UI 形态）；**viewportRect 已改 Unity 口径默认显式 (0,0,1,1)**
  （用户拍板 2026-10-11「新增相机视口矩形应 0,0,1,1 而非全 0」——Unity 无 null
  形态），遗留旧档 null 序列化代理显示等价值 "0,0,1,1"（控件兜底全 0 会误读），
  编辑即物化；
- 回写解析失败/类型不匹配/Layer 索引越界/枚举选项外值抛 TipException。

## 5. 节点头编辑通道

头部字段手拼 VO 不走字段行收集；@InsField 在 Node 三字段上仅为**回写闸门**：

| 字段 | 通道 | 语义 |
|---|---|---|
| name | STRING（`Node.name` @InsField） | blur/enter 提交；改名联动树行见 §7 |
| active | BOOLEAN（`Node.active` @InsField） | 勾选即时回写 |
| layer | INT（`Node.layer` @InsField，parseValue `Layer.of` 越界校验） | 下拉单选，value=层号索引 |
| tags | **TAG 专用分支**（`applyTag`，findProp 前分流，不经 PropMeta） | 替换式单选（Unity 口径单 tag）：非 "Untagged" → `tags=[Tag.of(value)]`，"Untagged" → 清空（空列表显示侧即回落 Untagged） |

Tag 走专用通道因 `List<Tag>` 塞不进「字段名 + setter + 字符串」的字段行协议；多 tag
节点的展示与编辑形态留后续（当前单选展示首个 tag）。

## 6. 面板结构（v0.4 Swing，`swing.inspector` 包；Web 期结构见本节末沿革注）

- **`InspectorPanel`**（主面板，PanelRegistry id=inspector）：选中驱动（订阅
  scene.select；无选中=占位「未选中任何对象」——null 是正常态非未就绪，无重试）；
  拉取 = submitTask 帧边界 collect（含归属校验——僵尸选中清空兜底）→ invokeLater
  整体重建；回写出口 `commitField`：submitTask ｛归属校验 → apply → markDirty｝，
  TipException → 状态栏提示；scene.changed → 重拉（编辑保护：任一控件编辑中
  （焦点内且文本已改）重建延后 pendingRefresh，提交收尾补拉——防重拉吞输入）；
  scene.nodeTransformChanged → **Transform 三行定点刷新**（字段名索引
  localPosition/localRotation/localScale；载荷四元数→欧拉 getEulerAnglesXYZ 同收集
  侧口径；控件值等价跳过防回声——较 Web 版防抖 150ms 重拉整面板精细，拖拽不闪不
  打断）；组件折叠态按组件名跨重建记忆；
- **`NodeHeaderPanel`**（节点头 §5 四件套）：节点图标（box 36px 占左列两行垂直居中，
  2026-10-18 用户定尺寸）+ 右列两行——行 0 激活勾选+名称输入 JeTextInput、行 1
  「图层」「标签」两下拉**并排单行**（Web 版 entity-meta-row 单行双 meta-field 形态
  回归，2026-10-18 用户派发；中文文案口径同 Web 拍板）；当前值不在选项表补临时选项
  （未命名层 "Layer N" / 自定义 tag）防静默失配；
- **`ComponentSection`**（组件分组）：CollapsiblePanel 折叠头（headerLeft=enabled
  勾选，required 不渲染）+ 字段行两列区（GridBagLayout：label 列宽自动=列内最宽
  即组内最长显示名、控件列 fill HORIZONTAL）；组内按 fieldName 索引控件；
- **`FieldRowFactory` + `FieldEditor` 契约**：valueType → 控件（INT/FLOAT→
  JeNumberInput、STRING→JeTextInput、BOOLEAN→勾选、ENUM/Layer/Tag→JeSelect、
  VEC3_F/ROTATION→VectorRow(X/Y/Z)、RECT→VectorRow(X/Y/W/H)、COLOR→ColorField、
  LAYER_MASK→LayerMaskField、GUID_ASSET→AssetInput（v0.10））；FieldEditor.setValue=
  外源刷新（编辑中/值等价跳过）、isEditing=重建保护判据；提交回调走 FieldCommit
  ｛owner 归属校验锚点 + host 回写宿主 + fieldName/valueType/wire 值｝；
- **`widget` 控件七件**：JeNumberInput（4 位小数钳制+range 硬钳+非法 blur 回滚+
  **键入即提交（§12）**+值真变才回调）、JeSelect（value/label 分离、程序化置值抑制上报）、
  VectorRow（N 段数字行**等分自适应**——GridBag 等权列 fill HORIZONTAL，Flow 布局
  恒 preferred 挤左形态废弃；RECT 四段两行 X/Y+W/H，2026-10-18 用户拍板；任一段提交
  整行组包——组包读各段 committedText（§12））、CollapsiblePanel（自绘头：▸/▾ 自绘三角+headerLeft
  槽+加粗标题；**视觉=灰头/亮内容分层**（头=Panel.background、内容提亮 Button.
  background——tabAreaBackground 在 FlatLightLaf 不存在取之落空实锤）+hover+
  整组四周 1px 边框；AWT 鼠标事件不冒泡——切换/hover 监听逐组件挂）、ColorField
  （**色块+JColorChooser 拍板 2026-10-18：alpha 保留原值不进拾色器；null 未物化
  白色预览首点即物化**）、LayerMaskField（值摘要按钮+JPopupMenu 勾选列表——非
  CheckBoxMenuItem，勾选不关弹层可连续多选）。AssetInput（v0.8 资产通用输入：
  类型图标+显示名类输入框 + 「…」按钮弹 AssetPickerDialog 选择窗；三态 wire、
  拖放按扩展名过滤、Consumer<String> 提交——v0.10 起 GUID_ASSET 行与 .mat 检查器
  双宿主，只读前身 GuidAssetField 随之退役删除）；
  AssetPickerDialog（v0.8 选择窗：模态 + 后台 listAssetPaths 拉取 + None 恒首 +
  搜索过滤 + 双击/确定提交、取消不动值）。
- （Web 沿革注：原 engine-editor-web 结构——inspectorService/EntityInspectorPanel/
  InspectorField + Ins 控件族 + 下拉遮挡修复（overflow 吞浮层终局）——随 2026-10-18
  去Web化删除；Swing 无同款遮挡坑（heavyweight popup 天然浮于一切）。）

## 7. 事件联动与 dirty 星标

- 回写链：v0.4 = 控件提交（EDT）→ submitTask 帧边界 ｛归属校验 → apply →
  markDirty｝；失败状态栏提示；
- **星标**（层级树根 `名 *`，VO dirty 单一真源）：`EditorScene.markDirty` **由净变脏的
  翻转沿发布 scene.dirty（`SceneDirtyEvent`，2026-10-11 拍板新增）**——置脏是所有
  写入路径（Inspector 字段/组件属性回写、变换检出、watch 检出）的必经点，翻转即
  星标事件；前端本地镜像置星、<b>不重拉树</b>（树内容未变，为一个 `*` 重建整树不值，
  用户拍板）；已脏重复置脏不重发（每脏周期至多一响，滑条连续回写无事件风暴）；
  熄灭归保存路径 clearDirty + scene.changed(false) 重拉（VO dirty 为准）；
- **两事件分工**（scene.changed 语义回归树刷新）：scene.changed = 树内容变更
  （结构/元数据 watch 检出、保存完成）→ 前端重拉 `/scene/tree`；scene.dirty =
  脏翻转仅置星不重拉。改名走 `scene.nodeRenamed` 定点刷新树行 + 本地镜像置星；
- **写入方零通知义务**：变更检出归侦测器（SceneWatchAppState），写入侧只管写 +
  置脏（翻转沿事件在 markDirty 内部，写入方无感知不加义务）。

## 8. 注册表与设置源（Layer/Tag）

- Layer 下拉选项：`SettingManager.loadLayerSettings()` **直读**（InspectorReflector
  LAYER_NAMES 静态缓存），31 槽按索引投影、未命名槽位=空串前端不渲染（"Layer i"
  占位兜底已删，2026-10-11 拍板只显有名层）；
- Tag 下拉选项：`SettingManager.loadTagSettings()`（TAG_OPTIONS 静态缓存）+ "Untagged"
  常驻注入（Unity 内建口径）；节点自持 tag 前端兜底并入选项（自定义 tag 保显示）；
- 运行目录：`JavelinEditor` 构造期设 `core.runtime.dir` = 项目根 → 设置读
  `<项目根>/Settings/`；**项目模板（GradleProject.createDefaultSettings）当前写
  `Game/` 目录与其未合——设置管线遗留，接通前 Layer 下拉空**。

## 9. UI 规范

间距 ≤2px 等编辑器通用规范见 AGENTS.md「编辑器 UI 紧凑度规范（Swing）」章节
（本 Inspector 全链落地驱动定档，2026-10-11；Web 期措辞 2026-10-18 平移 Swing 口径）；
浮层（拾色器/JPopupMenu 勾选列表）不受此限。**字段行两列布局（2026-10-11 用户
拍板）**：label 列与控件列网格对齐——label 列宽=组内最长文本、列间 4px（通用 ≤2
规范的已拍板例外）、label 超过 96px 截断省略（现状字段名均不超限，超限截断后置）。
Swing 实现（v0.4）：GridBagLayout 两列天然列宽对齐（列宽=列内最宽 preferred）、
label 列 insets right=4、行内顶对齐 anchor=NORTHWEST（多行控件 label 顶对齐拍板
条款——RECT 两行 X/Y+W/H 形态下生效，2026-10-18 用户拍板回归 Web 版 v0.2 两行
形态；单行控件视觉差 1~2px 可接受）。

## 10. 落地清单与暂缓

**Java（反射/数据链，v0.1~v0.3 沿革）**：`core.inspector` 注解六件（+`@InsLayerMask`
v0.2、+`@InsHeader` v0.4 增量）+ 接口 `InsEnumLabel` / `editor.inspector`（InspectorReflector +
InspectorValueType + vo 三件）/ `SceneWatchAppState`（快照含 layer/tags）/ `Node`
（name/active/layer @InsField 回写闸门）/ `Camera`（v0.2 字段注解十件，main 不入
面板——引擎 main 消费未落地）/ `MeshRenderer`（v0.3 mesh/material @InsField）/
`core.assets`（AssetManager.pathOfGuid，AssetLibManager guidPathMap 反查表）/
InspectorFieldVo.assetName（v0.3）。

**Swing（v0.4，M4 批次 2026-10-18 落地、同日用户验收）**：`swing.inspector` 包——InspectorPanel /
NodeHeaderPanel / ComponentSection / FieldRowFactory / FieldEditor / FieldCommit +
`widget` 七件（JeNumberInput / JeTextInput / JeSelect / VectorRow / CollapsiblePanel /
ColorField / LayerMaskField；GuidAssetField 原七件之一，v0.10 GUID_ASSET 换装
AssetInput 后退役删除）+ `InspectorPanelSetup` factory 替换 +
`MainFrame.getStatusBar()`（状态栏提示通道）+ VO 引用化（node/component 直持、字段行
VO 删 objId）+ Reflector LAYER_MASK options 下发（value=层索引串）+ markDirty 收口。
（Web 前端清单——inspectorService / EntityInspectorPanel / InspectorField + Ins 控件族
十件 + InsGuidAsset——已随去 Web 化删除。）

**暂缓**：Collider 字段（资产引用型同通道复用 GUID_ASSET）、多 tag 展示与编辑、
同节点多实例组件收集形态、
可空值字段「清回 null」的 UI 形态、Inspector 订阅 nodeRenamed（外部改名刷面板——
场景变更重拉兜底）、设置管线合流（Game/→Settings）。（v0.2 曾留白「组件字段星标
延迟 / Transform 拖拽星标即时化」——markDirty 翻转沿发布后关闭，见 §7。）

## 11. 检查器注册器（v0.5，2026-10-21 用户实测验收通过）

> 起因：检查器写死「选中节点」单形态，Unity 检查器是多态的——选中什么检查什么
> （节点→组件清单、材质文件→材质检查器、不支持的→灰提示）。三拍板（2026-10-21）：
> ①注册器**统一接管 Node 与资产文件**（分轨被否——两类检查器同走注册面，外壳零
> 特例）；②文件选中通知=**平行事件** ProjectSelectEvent（统一 SelectionChanged
> 大收口被否——Gizmo/TransformWatch/层级高亮等现有消费方零改动）；③Material
> 检查器**只读先行**（shader 驱动属性表+资产脏标+file.save 写回的可编辑通道独立
> 批次拍板）。

**契约（`swing.inspector` 包）**：

- `InspectorProvider`：`keys()`（node 键 = `NODE_KEY` 常量；文件键 = 扩展名含点号
  小写，对齐 FileTypeRegistry 口径）+ `create()`（EDT）→ InspectorContent。
- `InspectorContent`：`component()`（自含滚动结构）+ 生命周期 `onAttach` /
  `onDetach` / `onSelection`（同 provider 内目标切换）——外壳调序恒定
  create → onAttach → onSelection → … → onDetach；事件订阅 onAttach 订、
  onDetach 退（Subscription.dispose）。
- `InspectorRegistry`：静态门面（对齐菜单/面板/文件类型域先例），LinkedHashMap
  保持注册序、同键重复注册 IllegalStateException fail-fast；`find(InspectorSelection)`
  ——节点走 NODE_KEY、文件按扩展名 endsWith（忽略大小写）、目录/无选中 null；
  node 键不参与文件名 endsWith（分轨防「xx.node」误配）。
- `InspectorSetup`：集中显式注册（FileTypeSetup 先例），经
  `PluginContext.registerInspector` 收口，接线 EditorCorePlugin.setup。
- `InspectorSelection`：分发用选中快照（node/file 双槽，互斥后至多一非空）。

**外壳（InspectorPanel 重写为分发壳）**：Dock 面板只做分发与空态。选中驱动 =
双事件触发（scene.select / project.select）拉态单源读 EditorSelection（连发幂等
——互斥清槽同点击双事件只触发两次同态 dispatch）；provider 命中且与当前同 →
`onSelection` 换目标不重建内容件（组件折叠记忆/编辑保护状态保活）；换 provider →
全量换件。空态分档：无选中 =「未选中任何对象」、有选中无 provider（未支持文件/
目录）=「无检查器」灰提示（Unity 对不支持资产口径）。

**选中链（EditorSelection）**：双槽互斥（Unity 单 selection 口径）——
setSelectedNode 清文件槽（静默，文件侧消费方拉态无平行状态）；setSelectedPath
清节点槽并补发 SceneSelectEvent(null)（Gizmo/层级高亮/变换基线随选中移交复位）+
收口发布 ProjectSelectEvent；新增 getSelectedPath() 原始路径口（目录也达，
分发/展示侧用）。

**场景根行选中（2026-10-22 增量，2026-10-23 用户复验通过）**：层级树根行点击 = 场景文件进
project 槽（HierarchyPanel 报 setSelectedPath）——检查器零改动按 .scene 文件
走既有分发链（暂无 provider 显示「无检查器」灰提示，将来注册 .scene 检查器
即自动接管；用户拍板场景名/描述编辑不做——Unity 也没有）。选中链其余语义
不变。详见 Editor_Swing_Design §7.5 增量 10/11。

**Node 内容平移（NodeInspectorContent）**：M4 面板逻辑整体迁出（数据通道/回写
链/编辑保护/折叠记忆口径不变，验收基线 = 节点检查器行为与 v0.4 一致）；差异仅
事件分工——选中驱动归外壳，本件订阅 scene.changed（重拉）与
nodeTransformChanged（Transform 定点刷新）。ScrollableContent 升公用
InsScrollablePanel（Node/材质内容件共用）。

**Material 只读内容（MaterialInspectorContent）**：TextImporter 后台线程读 .mat
文本源（MaterialImporter 同款解析链——读源即所见，不经 Library 产物）、EDT 组装
（文件/Shader/面型/GUID 头 + values/textures 条目列表，浮点 InsFormat 4 位口径）；
代际守卫防连点竞态（选中前进即 +1，旧结果丢弃）、解析失败显错误态不静默。core
Material 补 getValues()/getTextures() 只读视图（unmodifiable 展示面，变更仍走
setFloat/setColor/setTexture——红线 10 只禁 write 侧包装，展示视图无碍）。

**Shader 只读诊断台（ShaderInspectorContent，v0.7 2026-10-25）**：与材质「读源」
互补走**读产物**——伴生 .meta 单源解 guid（2026-10-29 guid 真值单源拍板；旧=
文件头优先、meta 兜底），经
AssetManager.loadGuid 取 Library 编译产物（shaderName/属性元数据/Pass + PassState
即导入后真值）；EDT 组装三段（头 + 属性声明表 + Pass 状态表，浮点 InsFormat 同
口径）；产物缺失/meta 缺 guid=显式诊断态（导入失败或未导入，对应 Unity 着色器
检查器的编译错误区——诊断信息归导入日志，面板只判存在性）；代际守卫同材质面板。
着色器源码编辑归外部编辑器体系（Unity 同口径，双击外开待 file.open 打开器批次）。

**遗留**：材质可编辑通道（≈Unity ShaderGUI：shader 驱动属性表 + 资产脏标 +
file.save 写回 .mat——资产脏标与场景 dirty 不同源，需单独设计拍板）；project
面板高亮跨域同步（节点接管选中后文件槽被静默清空，项目面板右区条目高亮残留
视觉态——订阅 ProjectSelectEvent 清高亮留后）；面板标题/文案 i18n（硬编码中文，
随面板标题接 I18nKey 批次）；.smesh 等其余资产检查器按需注册；注入后 GLSL 查看
（Unity Show compiled code 等价物）留后置。

## 12. 输入交互与 label 拖动调值（v0.6，2026-10-22 用户实测验收通过）

> 起因：输入框失焦才应用太钝——数值要改即所见；文本侧用户拍板「机制归控件、
> 策略归使用方」。滚轮调值与无限拖试做后按用户要求移除（沿革见本节末）。

- **数字键入即提交（JeNumberInput）**：DocumentListener——可解析即钳制回写（live），
  **不动显示文本**保光标（每键归一化会把光标顶到末尾）；非法中间态（"-"、"0."、
  空串）跳过，enter/blur 回滚归一显示。三守卫沿用：值数值等价不回调、suppress 旗标
  （程序化 setText 跳过文档监听）防外源刷新回声提交、live 后 lastCommitted 同步跟进
  （blur 不重复）。
- **文本提交时机=使用方决定（JeTextInput）**：默认 enter/blur；三参构造可选
  `onInputChanged`=每键回调（挂了则 lastCommitted 同步、blur 不重复）。name 行不挂
  ——每键重命名连树刷新/星标无收益、打一半的中间态入模型；要 live 的使用方按需自接。
- **VectorRow 组包改读 `committedText()`**：live 输入期段显示文本是原始态（可能
  未过钳制），整行组包改读各段**已提交值**（恒归一/已钳制）——JeNumberInput 新访问口。
- **label 拖动调值（ScrubDragger，Unity scrub 口径）**：字段行 label（INT/FLOAT）
  + VectorRow 段 label（X/Y/Z 各管一段）按住左键左右拖——每像素=scrubStep、
  **拖动实时提交**（场景跟动，非松手才应用）；悬停/拖动显示水平调整光标。
  scrubStep：默认整 1/浮 0.1、range 字段=注解 step（FieldRowFactory/VectorRow
  接线）、`setScrubStep(0)` 禁用。Vector 行主 label（如「位置」）不挂——拖一下
  三分量同变易误触。
- **沿革（试做后移除，勿再当候选）**：悬停滚轮调值（步长同源、consume 防面板
  滚动）——用户要求去掉，悬停数字框滚轮恢复滚动面板；无限拖（Robot.mouseMove
  指针回绕 + 累计位移）——同批移除，恢复有限拖拽（拖到屏幕边即止）。

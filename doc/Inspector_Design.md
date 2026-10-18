# 检视器（Inspector）设计（v0.4）

> 定位：`javelin-editor` 选中节点检视面板——Java 反射收集字段清单（`InspectorReflector`，
> @InsField 显式闸门 + 读 getter 写 setter 铁律不变），M4 Swing 面板直调消费。
> 设计基准=完全参考 Unity（Editor_Design §1）。
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

- 收集面 v0.2 = 节点头（手拼 VO）+ **组件白名单**（`INSPECTABLE_COMPONENTS`：
  Transform（必需，恒首位）+ Camera（v0.2 放开）+ MeshRenderer（v0.3 放开，
  mesh/material 走 GUID_ASSET 只读））；收集经 `node.getComponent(type)`
  取首个实例（`components()` 为 package-private，外部 API 面即 getComponent）——
  节点单组件假设，同节点多实例组件形态留后续；白名单序=面板分组序；
- 类 → 属性清单缓存（ConcurrentHashMap，首次访问构建一次）：沿类层级收集声明字段，
  @InsField 标记才进清单；getter 按 getX/isX（boolean）、setter 单参 setX
  **按参数类型与字段类型装箱等价解析**（findSetter：getMethods 顺序不保证，同名重载
  putIfAbsent 取首个即随机命中——Camera.cullingMask 的 `setCullingMask(int)` 与
  `setCullingMask(Layer...)` 双重载实锤后改为显式匹配；零参 getter 无重载免解析），
  缺一抛错；
- 注解（`core.inspector` 包）：`@InsField`（label/icon 属性；TYPE 贴法=组件头显示名）、
  `@InsRotation`（Quaternionf opt-in 按欧拉角展示）、`@InsIntRange`/`@InsFloatRange`
  （滑条元数据投影为 range）、`@InsLayerMask`（int 字段按层位掩码勾选多选，
  v0.2）、`InsEnumLabel`（**接口**，枚举实现 getLabel() 提供下拉显示名，未实现兜底
  常量名；ProjectionType=透视/正交、ClearFlags=颜色+深度/仅深度/不清除 首批实现——
  显示名口径=自述行为，SOLID_COLOR 不用 Unity 直译「纯色」，用户拍板 2026-10-11）；
- 组件头：enabled 提升分组头勾选不入行；`required` 标志（Transform 必需组件不渲染
  勾选，Unity 口径，将来组件删除菜单同用此标志）。

## 4. 值类型（InspectorValueType）与序列化口径

INT（Layer 字段=索引串，读写经 Layer.of/getValue 换算）/ FLOAT / BOOLEAN（"true"/"false"）/
STRING / VEC3_F（"x,y,z"）/ ROTATION（欧拉角度数 "x,y,z"；四元数↔欧拉换算单点在 Java
侧）/ ENUM（常量名；options 随 VO 下发，显示名走 InsEnumLabel）/ COLOR（"r,g,b,a"
0-1 浮点，回写构造自动夹取 [0,1]）/ RECT（"x,y,width,height"）/ LAYER_MASK（位掩码
int 串，前端勾选式多选按 Layer 名投影）/ **TAG（节点头专用通道，§5）**/
**GUID_ASSET（v0.3，Asset 引用型字段三态只读：value=null=无资产"None"、空串=内存
创建（guid 空，前端禁用态「（内存创建）」）、非空=guid 原串；显示名另走 VO
assetName=guid 反查源路径尾名去后缀（`AssetManager.pathOfGuid`），反查失败兜底 guid；
回写侧显式抛"不支持字符串回写"——赋值通道=项目面板拖拽资产，拍板 2026-10-17 后置）**。
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
  LAYER_MASK→LayerMaskField、GUID_ASSET→GuidAssetField）；FieldEditor.setValue=
  外源刷新（编辑中/值等价跳过）、isEditing=重建保护判据；提交回调走 FieldCommit
  ｛owner 归属校验锚点 + host 回写宿主 + fieldName/valueType/wire 值｝；
- **`widget` 控件七件**：JeNumberInput（4 位小数钳制+range 硬钳+非法 blur 回滚+
  enter/blur 提交值真变才回调）、JeSelect（value/label 分离、程序化置值抑制上报）、
  VectorRow（N 段数字行**等分自适应**——GridBag 等权列 fill HORIZONTAL，Flow 布局
  恒 preferred 挤左形态废弃；RECT 四段两行 X/Y+W/H，2026-10-18 用户拍板；任一段提交
  整行组包——此刻其余段恒归一态）、CollapsiblePanel（自绘头：▸/▾ 自绘三角+headerLeft
  槽+加粗标题；**视觉=灰头/亮内容分层**（头=Panel.background、内容提亮 Button.
  background——tabAreaBackground 在 FlatLightLaf 不存在取之落空实锤）+hover+
  整组四周 1px 边框；AWT 鼠标事件不冒泡——切换/hover 监听逐组件挂）、ColorField
  （**色块+JColorChooser 拍板 2026-10-18：alpha 保留原值不进拾色器；null 未物化
  白色预览首点即物化**）、LayerMaskField（值摘要按钮+JPopupMenu 勾选列表——非
  CheckBoxMenuItem，勾选不关弹层可连续多选）、GuidAssetField（三态只读暗色显示）。
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

**Java（反射/数据链，v0.1~v0.3 沿革）**：`core.inspector` 注解五件（+`@InsLayerMask`
v0.2）+ 接口 `InsEnumLabel` / `editor.inspector`（InspectorReflector +
InspectorValueType + vo 三件）/ `SceneWatchAppState`（快照含 layer/tags）/ `Node`
（name/active/layer @InsField 回写闸门）/ `Camera`（v0.2 字段注解十件，main 不入
面板——引擎 main 消费未落地）/ `MeshRenderer`（v0.3 mesh/material @InsField）/
`core.assets`（AssetManager.pathOfGuid，AssetLibManager guidPathMap 反查表）/
InspectorFieldVo.assetName（v0.3）。

**Swing（v0.4，M4 批次 2026-10-18 待验收）**：`swing.inspector` 包——InspectorPanel /
NodeHeaderPanel / ComponentSection / FieldRowFactory / FieldEditor / FieldCommit +
`widget` 七件（JeNumberInput / JeTextInput / JeSelect / VectorRow / CollapsiblePanel /
ColorField / LayerMaskField / GuidAssetField）+ `InspectorPanelSetup` factory 替换 +
`MainFrame.getStatusBar()`（状态栏提示通道）+ VO 引用化（node/component 直持、字段行
VO 删 objId）+ Reflector LAYER_MASK options 下发（value=层索引串）+ markDirty 收口。
（Web 前端清单——inspectorService / EntityInspectorPanel / InspectorField + Ins 控件族
十件 + InsGuidAsset——已随去 Web 化删除。）

**暂缓**：GUID_ASSET 赋值通道（项目面板拖拽资产，拍板 2026-10-17）、Collider 字段
（资产引用型同通道复用 GUID_ASSET）、多 tag 展示与编辑、同节点多实例组件收集形态、
可空值字段「清回 null」的 UI 形态、Inspector 订阅 nodeRenamed（外部改名刷面板——
场景变更重拉兜底）、设置管线合流（Game/→Settings）。（v0.2 曾留白「组件字段星标
延迟 / Transform 拖拽星标即时化」——markDirty 翻转沿发布后关闭，见 §7。）

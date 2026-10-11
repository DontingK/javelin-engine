# 检视器（Inspector）设计（v0.2）

> 定位：`javelin-editor` 选中节点检视面板——Java 反射收集字段清单经桥下发、Web 渲染、
> 编辑回写。设计基准=完全参考 Unity（Editor_Design §1）。
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

## 1. 总则

- **读 getter、写 setter 铁律**：`Transform.setLocalXxx` 内含值拷贝 + markDirty 脏传播，
  裸 `Field.set` 绕过（localToWorld 不重算）——字段定位可反射，值通道不裸写字段；
- **@InsField 显式 opt-in 闸门**（等价 Unity `[SerializeField]` 语义）：未标记一律不展示；
  已标记缺 public get/set 或类型不可映射 → 构建即抛错（显式意图不静默吞）；
- **线程**：收集/回写经 `JavelinEngine.submitTask` 帧边界引擎主线程执行（与 mainLoop
  场景访问无竞争），桥线程 `CompletableFuture` + 2s 超时转 ready:false（SceneMethod 口径）；
- **回写即置脏**：setValue 成功 `EditorScene.markDirty`（前端乐观更新、fire-and-forget，
  失败仅日志；提示类异常 Java 弹原生框，前端不重复弹错）。

## 2. 桥协议

| 方法 | 参数 | 返回/行为 |
|---|---|---|
| `/inspector/info` | `{id}`（IdPar，严格一致） | `NodeInspectorVo`（ready=false=未就绪/节点不存在/超时，前端节流重试或清面板） |
| `/inspector/setValue` | `{objId, fieldName, valueType, value}`（四字段严格一致） | 引擎线程解析 + 写入 + markDirty；解析失败抛 TipException 桥层弹原生框 |

`NodeInspectorVo`：ready / objId / nodeName / layer（索引）/ layerNames / tags / tagNames /
active / components[]（objId / name / enabled / required / fields[]）。
`InspectorFieldVo`：objId / fieldName / name / valueType / value / range / options。

## 3. 字段收集（InspectorReflector）

- 收集面 v0.2 = 节点头（手拼 VO）+ **组件白名单**（`INSPECTABLE_COMPONENTS`：
  Transform（必需，恒首位）+ Camera（v0.2 放开））；收集经 `node.getComponent(type)`
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
int 串，前端勾选式多选按 Layer 名投影）/ **TAG（节点头专用通道，§5）**。
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

## 6. 前端结构（engine-editor-web）

- `inspectorService.ts`：VO/Par 类型 + 两桥方法；
- `EntityInspectorPanel.vue`：头部（启用勾选 + 名称输入；「标签」「图层」双下拉）+
  组件分组（CollapsiblePanel `header-left` 启用勾选）；选中驱动=订阅 `scene.select`
  （pull-on-mount 经 useSelection 兜底启动竞态）；Transform 变更=订阅
  `scene.nodeTransformChanged` 防抖 150ms 重拉（拖拽逐帧事件收敛为一次拉取）；未就绪
  30×500ms 节流重试（同 HierarchyPanel 范式）；
- `InspectorField.vue` 按 valueType 分发 `inspector/input/` Ins 控件族（v-model 均 string，
  对应各类型序列化格式）；
- **下拉遮挡终局（2026-10-11 用户实测定因）**：真凶=`CollapsiblePanel` 根 div 的
  `overflow: hidden`（为 3px 圆角裁子元素所加）把 JeSelect 浮层裁在分组框内（换向
  也逃不出——上下都在框内）。修=去掉该 overflow（折叠 v-show 无动画不依赖裁剪；
  圆角由 header 补 `border-radius: 2px 2px 0 0`（外 3px − 1px 边框），字段行自带
  3px 圆角）；浮层随即逃到 `.inspector-content` 滚动区（真正的 clip 边界）。
  `JeSelect` 换向判定同步加固：**最近裁剪祖先**可视矩形替代 window 底缘（停靠布局
  下面板下还有底部停靠区，window 底缘高估下方空间）+ 展开后实测 offsetHeight +
  展开期捕获祖先 scroll 重算；顺带修两处未定义色板变量
  `--editor-text-secondary`→`--editor-text-dim`。教训：**带圆角的小容器 overflow
  hidden 会吞掉一切内部浮层——下拉/弹层要么放它出来（去 overflow），要么
  Teleport 出去**。

## 7. 事件联动与 dirty 星标

- 回写链：前端乐观更新 + fire-and-forget `/inspector/setValue` → Java 写入 + markDirty；
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

间距 ≤2px 等编辑器 Web UI 通用规范见 AGENTS.md「编辑器 Web UI 设计规范」章节
（本 Inspector 全链落地驱动定档，2026-10-11）；浮层菜单内部间距不受此限。
**字段行两列布局（2026-10-11 用户拍板）**：label 列与控件列网格对齐——label 列宽
= 组内最长文本（.property-group 两列 grid + 行 subgrid 汇流列轨，无 subgrid
支持回退按行自宽）、列间 4px（通用 ≤2 规范的已拍板例外）、label 超过 96px
`text-overflow: ellipsis` 截断省略；行内 `align-items: start`——多行控件
（如视口矩形 X/Y+W/H 两行）label 顶部对齐不垂直居中（同日拍板）。

## 10. 落地清单与暂缓

**Java**：`core.inspector` 注解五件（+`@InsLayerMask` v0.2）+ 接口 `InsEnumLabel` /
`editor.inspector`（InspectorReflector + InspectorValueType）/ `jcef.domain.par.InspectorValuePar`
+ vo 三件（NodeInspectorVo / ComponentInspectorVo / InspectorFieldVo）/ `InspectorMethod` /
`SceneWatchAppState`（快照含 layer/tags）/ `Node`（name/active/layer @InsField 回写闸门）/
`Camera`（v0.2 字段注解十件，main 不入面板——引擎 main 消费未落地）。

**前端**：inspectorService / EntityInspectorPanel / InspectorField + `inspector/input/`
Ins 控件族十件（v0.1 已备 ENUM/COLOR/RECT/LAYER_MASK 控件，v0.2 零改动）。

**暂缓**：MeshRenderer/Collider 字段（Mesh/Material 资产引用型，随资产面板设计）、
mesh/material 资产引用字段、多 tag 展示与编辑、同节点多实例组件收集形态、
可空值字段「清回 null」的 UI 形态、Inspector 订阅 nodeRenamed（外部改名刷面板）、
设置管线合流（Game/→Settings）。（v0.2 曾留白「组件字段星标延迟 / Transform 拖拽
星标即时化」——markDirty 翻转沿发布后关闭，见 §7。）

# 检视器（Inspector）设计（v0.1）

> 定位：`javelin-editor` 选中节点检视面板——Java 反射收集字段清单经桥下发、Web 渲染、
> 编辑回写。设计基准=完全参考 Unity（Editor_Design §1）。
> 落地：2026-10-09 全链首版（仅 Transform 打通流程）→ 2026-10-11 头部 Tag/Layer 可
> 编辑 + 间距规范 + 星标联动；用户实测验收通过（2026-10-11）。

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

- 收集面 v0.1 = 节点头（手拼 VO）+ **组件白名单仅 Transform**（`collectComponent` 即扩展点）；
- 类 → 属性清单缓存（ConcurrentHashMap，首次访问构建一次）：沿类层级收集声明字段，
  @InsField 标记才进清单；getter 按 getX/isX（boolean）、setter 单参 setX，缺一抛错；
- 注解（`core.inspector` 包）：`@InsField`（label/icon 属性；TYPE 贴法=组件头显示名）、
  `@InsRotation`（Quaternionf opt-in 按欧拉角展示）、`@InsIntRange`/`@InsFloatRange`
  （滑条元数据投影为 range）；
- 组件头：enabled 提升分组头勾选不入行；`required` 标志（Transform 必需组件不渲染
  勾选，Unity 口径，将来组件删除菜单同用此标志）。

## 4. 值类型（InspectorValueType）与序列化口径

INT（Layer 字段=索引串，读写经 Layer.of/getValue 换算）/ FLOAT / BOOLEAN（"true"/"false"）/
STRING / VEC3_F（"x,y,z"）/ ROTATION（欧拉角度数 "x,y,z"；四元数↔欧拉换算单点在 Java
侧）/ ENUM（常量名，待编解码）/ COLOR / RECT / LAYER_MASK / **TAG（节点头专用通道，§5）**。

- 浮点显示/输入统一最多 4 位小数：Java `formatFloat`（BigDecimal HALF_UP + 去尾零，
  `0.30000012→"0.3"`）+ 前端 `JeNumberInput` precision=4 三路钳制（显示/输入/滚轮）；
- 回写解析失败/类型不匹配/Layer 索引越界抛 TipException。

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
  对应各类型序列化格式）。

## 7. 事件联动与 dirty 星标

- 回写链：前端乐观更新 + fire-and-forget `/inspector/setValue` → Java 写入 + markDirty；
- **星标**（层级树根 `名 *`，VO dirty 单一真源）：layer/tags 变更经 SceneWatchAppState
  快照 diff 检出（快照含 layer 索引 + tagNames，0.25s 节流）→ 发 `scene.changed` → 前端
  重拉 `/scene/tree` → VO dirty 落地星标（保存后 `scene.changed(false)` 重拉熄灭）；
  改名走 `scene.nodeRenamed` 定点刷新树行 + 本地镜像（有定点更新需求、不重拉）；
- **写入方零通知义务**：变更检出归侦测器（SceneWatchAppState），写入侧只管写 + 置脏；
- 已知缺口：Transform 拖拽路径（nodeTransformChanged）星标要等下次 scene.changed
  （同法可解：TransformWatchAppState 检出时顺发 scene.changed，留后续）。

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

## 10. 落地清单与暂缓

**Java**：`core.inspector` 注解四件 / `editor.inspector`（InspectorReflector +
InspectorValueType）/ `jcef.domain.par.InspectorValuePar` + vo 三件（NodeInspectorVo /
ComponentInspectorVo / InspectorFieldVo）/ `InspectorMethod` / `SceneWatchAppState`
（快照含 layer/tags）/ `Node`（name/active/layer @InsField 回写闸门）。

**前端**：inspectorService / EntityInspectorPanel / InspectorField + `inspector/input/`
Ins 控件族十件。

**暂缓**：Camera/MeshRenderer/Collider 字段、Color/Rect/枚举编解码、@InsLayerMask、
mesh/material 资产引用字段、多 tag 展示与编辑、组件字段变更事件（非 Transform 回写
无事件，已由 setValue 内 markDirty 兜底）、Inspector 订阅 nodeRenamed（外部改名刷面板）、
Transform 拖拽星标即时化、设置管线合流（Game/→Settings）。

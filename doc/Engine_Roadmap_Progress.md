# 引擎阶段路线——批次索引（Engine Roadmap Progress）

> 版本：v0.3（2026-11-08）
> 定位：`Engine_Roadmap.md` 的**分卷**（2026-11-08 拆分，同日由逐字流水压成索引）。总纲承载各期目标 / 拍板 / 验收标准 / 状态摘要；本文承载**批次索引**——哪个日期、做了哪一批、关键改动是什么、落点在哪个设计档、验收状态如何。
> 压缩口径：每条一行要点，**散文细节不再复述**（实现过程、逐文件枚举、方案论证）——它们归各设计档的**版本沿革**与 `Engine_Features.md` 的**验收状态行**（验收状态/日期全项目单点）。**只在台账出现过、别处无副本的要点**（各条挂靠关系、跨档散件、排障事实）保留在条目内与 §5/§6 归档。
> 维护口径：新批次的进度明细写本文（AGENTS 红线 12），`Engine_Roadmap.md` 只同步**状态摘要**与 §5 映射表。条目内「见 §x」指 `Engine_Roadmap.md` 同号节。
> 溯源：v0.1 逐字流水版见 git 历史（2026-11-08）；本卷每条均可在对应设计档版本段核到。
> 章节对应：§2 二期、§3 三期散件；**§1 一期（关账总结）与 §4 四期（立项未开工）无批次条目**，节号与总纲保持一致以便对读。

---

## 2. 二期：引擎功能——批次索引

> §2.1 输入子系统与 §2.2 碰撞检查的进度描述内联在总纲（无独立流水段），本节只收 §2.3 / §2.4。条目按日期升序排列（同日多条保持原台账顺序）。

### 2.3 Ray

- **2026-10-28｜L1 场景级 raycast（运行时物理查询线）**：`physics.query.Physics` 静态门面（`raycast` / `raycastAll` 2×3 重载，射程 = `maxDistance` 查询参数，Unity 口径；jME 式 `Ray.limit` 同日落地即回退）+ `RaycastHit`（复用缓冲零分配、未命中 out 不变）+ 查询源 `Scene.getColliders` 注册表投影 + 过滤序（非激活/禁用 → layerMask → 最近命中）+ 退化值闸门 + 未绑定场景抛 IllegalStateException；随批相机→射线组装链 `Scene.getMainCamera` + `JavelinEngine.mouseRay` + `Camera.screenToRay(x,y,out)` 尺寸自取（显式尺寸重载留给单测/离屏/编辑器） → `Engine_Collision_Design.md` §10（v0.2）/ `Engine_Scene_Design.md` §5.2 ｜✅ 2026-10-30 销账（PhysicsTest 14 用例 + GameSceneTest 主相机用例全绿）；mouseRay 运行时面 2026-10-31 用户实测验收

### 2.4 编辑器基本

- **2026-10-01｜.scene 序列化落地**：GameScene/Node/Component/Transform/Camera/MeshRenderer write/read + read 全量恢复零收尾 + 文本通道 file.save/Ctrl+S；同批生命周期帧驱动状态机 → `Engine_Scene_Design.md` §9 ｜✅ 2026-10-04 用户实测（单测 + 编辑器「建树→保存→重启还原」）
- **2026-10-11｜编辑器骨架大破土（→ 10-13 编辑→打包全流程闭环）**：菜单/命令三域 + 层级树与场景增删 + Inspector v0.1/v0.2 + Scene/Game 切换 + 资产管线（.meta/GUID/导入器/Library + SceneImporter） → `Editor_Menu_Design.md` v0.9/§、`Editor_Design.md`
- **2026-10-13｜打包 JAR/AOT 双形态**：JAR = jlink 裁剪 JRE；AOT = GraalVM native-image（外置 DLL + reflect-config 处理器生成 + resource-config includes），产物 Builds/ 双击可玩 → `Editor_Menu_Design.md` v0.10 ｜✅ 2026-10-13 AOT/JAR/编辑器三端实测
- **2026-10-14｜Gizmo 拖拽验收**：TransformTools 三段式（beginDrag/updateDrag/endDrag）+ MoveTool 平面相交 + 世界位移经父逆回写 local + 拖拽轴高亮 → `Editor_Design.md` §10 ｜✅ 2026-10-14（§2.4 最小闭环主体验收收官）
- **2026-10-17｜修复批次**：打包 jar 产物 lib 路径错位（`build/jar/lib` → `Builds/jar/lib`，依赖落错位、启动脚本 classpath 落空）+ 内置资源释放后 Library 索引不重扫（MetaManager 构造 sync 早于 releaseAll，`@guid:` 解析 null 渲染崩）；连带定位旧格式 Mesh 产物不迁移坑 → `Editor_Menu_Design.md` v0.10 追记 / `Editor_Design.md` §6.3 / `Engine_Features.md` §3.6、§4
- **2026-10-17｜项目面板落地（Web 期）**：Unity 两栏（左目录树 / 右内容区）+ 面包屑 + 双击进入；`/project/tree` 全量树（仅 Assets/src 两根）+ `/project/select` 选中上报 + ctx.project 右键域首期三实做 → `Editor_Menu_Design.md` v0.11 / `Editor_Design.md` §10 ｜✅ 2026-10-23 用户实测
- **2026-10-17｜编辑器 UI 栈去 Web 化 M1**：主窗口弃 JCEF 改纯 Swing + FlatLaf 亮色；自研轻量 Dock（二分布局树 + PanelRegistry 扩展点 + 自研分割条）+ ViewportTabs 单 Canvas 不 reparent 直挂 + JMenuBar 挂载；排障五坑（heavyweight 事件形态 / dragger 卡死 / Canvas minimum 钳制 / 弹性侧策略 / 关闭零重挂） → `Editor_Swing_Design.md` §10-5、§10-6 ｜✅ 用户实测
- **2026-10-17｜M6 窗口迁移全项提前**：ProjectFrame / ProjectLoadingFrame / PackDialog 三窗口迁 Swing，CEF 运行时零使用，`/project`·`/build` 两域桥随消费方消亡退役 → `Editor_Swing_Design.md` §2、§9 ｜✅ 2026-10-23 用户实测
- **2026-10-18｜M3 项目面板 Swing 实装**：ProjectPanel + ProjectTreeSnapshot（后台线程纯 IO 全量树）→ `Editor_Swing_Design.md` §7.6 ｜✅ 2026-10-23 用户实测
- **2026-10-18｜M4 检查器面板 Swing 实装**：InspectorPanel + swing.inspector 两层装配（面板层 + widget 七件）+ NodeTransformChangeEvent 首个消费方 + markDirty 收口进回写出口；验收期修正归属校验比对对象 / Scrollable 内容容器 / 节点头布局 → `Editor_Swing_Design.md` §7.7 / `Inspector_Design.md` v0.4 ｜✅ 用户实测
- **2026-10-18｜M6 收口——jcef 桥包整体清退**：桥方法/par/vo/EventBridgeForwarder 全删；jcefmaven、jackson 依赖移除；jcef-bundle 运行时缓存（353MB）删除；五场景事件转纯 EditorEventBus 事件，Inspector 三 VO 迁 inspector 包；engine-editor-web 前端工程整体删除 → `Editor_Swing_Design.md` §7.1、§9、§11 ｜✅ 用户实测（引擎-编辑器全链路纯 JVM）
- **2026-10-18｜M2 层级面板 Swing 实装**：HierarchyPanel + SceneTreeSnapshot（帧边界组装快照 → invokeLater 换树）+ 四事件分工刷新 + 展开态按节点引用恢复；同日拍板**全链引用化**（ObjectCache 退役，选中/三事件/创建命令/树回查键全改 Node 引用） → `Editor_Swing_Design.md` §7.5 ｜✅ 2026-10-23 用户实测
- **2026-10-19｜.mat 资产格式（阶段④剩余收官件）**：「.mat 就是 Material 的序列化」——文本序列化源 + 伴生 .meta；MaterialImporter（SceneImporter 同款，shader 残档拒绝）+ project.create.material + project.changed 面板刷新链（首个改盘命令接线）+ .mat 文件类型 → `Engine_Material_Design.md` §3.2 / `Editor_Menu_Design.md` v0.13 ｜✅ 2026-10-23 用户实测
- **2026-10-20｜插件体系 + editor.core 迁移 + i18n 基建 + 菜单 i18n 首批**：插件契约/装载管线/错误分级（ServiceLoader 发现，editor.core 即插件无特权，三个 Setup 迁 EditorCorePlugin.setup）+ 装载接入 ProjectLoadingFrame + I18n 注册中心（`!key!` 兜底 / 去重 warn / miss-used 采集）+ 菜单 label 全链 I18nKey（12 命令类覆写全删，zh_cn 36 条） → `Editor_Plugin_Design.md` v0.1 ｜✅ 同日用户实测；台账独有挂账 = 命令 id 前缀化 / FileType 冲突策略 / 默认语言
- **2026-10-21｜检查器注册器**：Node + 资产统一接管 + 材质只读面板 → `Inspector_Design.md` v0.5 ｜✅ 2026-10-21 用户实测（原仅见于映射表，拆分时折入）
- **2026-10-22｜层级根行选中 + 脏星标行宽修复**：场景根行可选中（=场景文件进 project 槽）+ 根行高亮回声自灭修复 + scene.dirty 置星改 nodeChanged 重算行尺寸 → `Editor_Swing_Design.md` §7.5 增量 / `Inspector_Design.md` §11 ｜✅ 2026-10-23 用户实测
- **2026-10-22｜检查器输入交互增强**：数字键入即提交（失焦才应用作废）+ 文本提交时机归使用方 + label 按住左键左右拖调值（Unity scrub 口径，步长 = 注解 step > 控件默认） → `Inspector_Design.md` v0.6 §12 ｜✅ 用户实测
- **2026-10-24｜视口聚焦**：层级双击 + F 键 → 相机平滑框住选中物 → `Editor_Design.md` §10 ｜✅ 2026-10-24 用户实测（原仅见于映射表，拆分时折入）
- **2026-10-24｜资产热重载设计定档**：两稿拍板反转——v0.1 令牌表流派（持有者钩子 + 依赖登记）推翻，终态 = 反射遍历流派（持有引用不动，协调器反射遍历场景对象图换 Asset 字段指针；core 仅增 invalidate/reload，Walker 居编辑器不进 AOT 镜像）；配套 Material 补默认值后处理 / 孤儿两帧退役 / Scene 根豁免 / 导入失败不 invalidate=天然回退 → `Engine_AssetReload_Design.md` v0.2 ｜⬜ 定档时未实现（批次①~④待派发）
- **2026-10-24｜hierarchy.create.cube**：内置 box.smesh（1×1×1 居中、六面外向绕序）+ CreateBoxMenuCommand（创建族第二项） → `Editor_Menu_Design.md` v0.14 ｜✅ 用户实测
- **2026-10-25｜内置原始体三件套 sphere/capsule/cylinder**：三份 .smesh 由旋转体生成脚本离线产出（一次性工具不入库；母线绕 Y + 绕序叉积自检 + guid 重跑稳定；Unity 口径：球直径 1 / 圆柱高 2×直径 1 / 胶囊总高 2×直径 1） → `Editor_Menu_Design.md` v0.15 ｜✅ 当日用户实测
- **2026-10-25｜project.create.shader + guid 头收窄 + .shader 诊断台**：NewShader.shader 极简前向模板 + 重名编号去重；**拍板：用户文件不写 guid 头**（guid 头=内置资源专属），MetaManager.createNewMeta 缺头 .shader/.smesh 改随机 guid（.java 保持注入）；配套 .shader 只读诊断台（guid 双源解析 + Library 产物 + 属性/Pass 表 + 导入缺失诊断态） → `Editor_Menu_Design.md` v0.16 / `Inspector_Design.md` v0.7 ｜✅ 当日用户实测
- **2026-10-25｜.mat 联动三件（Inspector v0.8~v0.10）**：① AssetInput 资产通用输入（Unity ObjectField 口径、guid 三态 wire、选择窗/拖放/外源刷新三通道）+ core 补 `AssetManager.guidOfPath`；② Shader 行换装（None/加载失败拒绝回滚、setShader 即重置默认值、TextExporter 即时写回 + submitImport、写回失败从盘面重载）；③ 参数编辑（类型感知控件全复用、区间硬钳、防抖 500ms 写盘；材质信息分组固定不可折叠）+ GUID_ASSET 通道升可编辑（GuidAssetField 退役） → `Inspector_Design.md` v0.8~v0.10 ｜✅ 当日用户实测
- **2026-10-25｜材质编辑即时联动渲染（v0.11）**：编辑载体改 loadGuid 缓存共享实例优先（命中且 shader guid 与盘面一致 → 直接编辑场景持有同一实例）+ shader 分歧守卫 + **Shader 换绑例外不落共享实例**（渲染侧按 shader 首绑烤死 UBO 尺寸/贴图绑定数） → `Inspector_Design.md` v0.11 ｜✅ 当日用户实测
- **2026-10-25｜资产管线 meta 统一与重导检出**：.shader/.smesh 落伴生 .meta（guid=文件头镜像）+ BuiltinAssetReleaser 内容字节比对驱动重导 + needImport 统一 mtime 判定 → `Editor_Design.md` §6.3 ｜✅ 当日用户实测（旧产物劫持坑根治）
- **2026-10-25｜贴图资产管线（Texture 档 v0.1）**：三层模型拍板——Image 像素资产（导入产物，磁盘一份全项目共享）/ Texture2D 降为引用层（image 引用 + filter/wrap/mipLevels，一图多设置免复制）+ ImageImporter（.png/.jpg/.jpeg 同入口）+ alpha 通道保留修复 + image 文件类型/图标；消费链零改动兼容 → `Engine_Texture_Design.md` v0.1 ｜✅ 当日用户实测
- **2026-10-25｜纹理资产 v0.2**：project.create.texture + TextureImporter（`image=@guid` 解 / null=纹理壳合法）+ .texture 检查器面板（**编辑载体=文本私有实例**，与 .mat 共享实例路线分歧）+ AssetInput 多后缀 + RenderingSystem null-image 占位守卫 + texture.svg 棋子格图标；同批修 ShaderParser 2D 类型关键字死分支（readIdentifier 拒数字开头）+ 材质面板参数行 label 改 displayName → `Engine_Texture_Design.md` v0.2 / `Editor_Menu_Design.md` v0.17 / `Inspector_Design.md` v0.12 ｜✅ 当日用户实测
- **2026-10-27｜资产热重载批次①（AssetReload 档 v0.3）**：反射遍历流派最小闭环——core 仅 `loadCache` 放 protected（reload 归编辑器 ProjectAssetLibManager）+ invalidate/reload + 反射 Walker（材质补默认值后处理）+ requestReconcile 孤儿回收（RenderingSystem mark-and-sweep 两帧退役）+ AssetReloadService（订导入事件 → 帧首 reload→换指针→对账→发事件）+ 材质面板最小钩子 → `Engine_AssetReload_Design.md` v0.3 ｜✅ 2026-10-30 用户实测（验收口：改 .shader/.mat → reimport → 画面当帧变 + F3 存活计数不涨）
- **2026-10-27｜坏 shader stub 产物 + 渲染回退默认 shader（Material 档 v0.3）**：判据 = `Shader.isValid()`（无有效二进制 = 坏 shader）；编译失败落 stub 产物（任务 SUCCESS 带 errorMessage，原「保持旧版本天然回退」口径作废）+ 渲染链经 `DefaultShader` 门面回退默认材质单例 + reconcile 活集显式加默认材质防误退役 + 诊断台坏态与创建命令弹框（红线 15）；附带收益 = 坏 shader 不再炸帧循环 → `Engine_Material_Design.md` v0.3 ｜✅ 2026-10-30 用户实测
- **2026-10-27｜焦点扫描资产刷新 + 导入进度模态框**：MainFrame windowGainedFocus → EditorFocusGainedEvent（总线解耦）→ 后台跑 `AssetImportPipeline.refresh()`（纯 IO 不占 EDT，导入中不重扫）→ 有待导入弹 ImportProgressDialog（不确定进度条 + Timer 轮询归零自动关，不可手动关防重扫循环）；同批 10 条新文案接 EditorLang（不攒批进第二批） → `Engine_AssetReload_Design.md` §5.1 / `Editor_Plugin_Design.md` §7.2-0 ｜✅ 2026-10-30 用户实测
- **2026-10-29｜Mesh 子网格 + MeshRenderer 多材质**：子网格 = 单索引数组 + SubMesh 区间列表（Unity subMeshCount/SetTriangles 语义，RHI drawIndexed 扩 firstIndex）；材质列表 i 位对应子网格 i，每 (renderer, 子网格) 一 RenderObject（RenderingSystem 零改动）；**缺失兜底语义变化** = materials 缺失位/空列表/shader 无效 → DefaultMaterial（原「material null 不渲染」作废，仅 mesh null 不渲染）；.smesh 增 [SUBMESH_N] 段 + 检查器 GUID_ASSET_LIST 多材质行 + get/setMaterial 兼容门面 → `Engine_Assets_Design.md` §2.9 / `Engine_Scene_Design.md` §5.1 v0.5 / `Inspector_Design.md` v0.13 ｜✅ 2026-10-30 用户验收（随验收修文本列表 guid 硬编码 Asset.class 崩）
- **2026-10-29｜热重载范围收窄只 shader + DefaultMaterial 改名 + 双重检查锁**：判据 = 文件真值源 + 渲染侧按实例身份烤死——Shader 必须 reload；.mat/.texture/.smesh/像素源摘出 reload 链「后面再加」（基建不动，targetClass 加回分支即恢复）；DefaultShader → DefaultMaterial 改名（身份=共享默认材质单例）；material() 改双重检查锁 → `Engine_AssetReload_Design.md` v0.4 / `Engine_Material_Design.md` v0.4 ｜⬜ 台账记录时为「待用户验收」
- **2026-10-29｜插件装载错误显性化 + 语言包注册制**：装载期任何插件报错（本体/第三方、发现/装载/namespace 冲突/setup 失败，含语言包路径缺失）一律弹错误框（namespace + 包路径 + 错误信息），确认后 System.exit；**原「第三方 warn 跳过」口径作废**（红线 15 推到装载期）；语言包注册制 = registerLanguage + registerPack「域+语言+路径」显式注册（固定路径规范废除）+ 缺键逐键回退该域基线 → `Editor_Plugin_Design.md` §3.4、§4.3 ｜✅ 2026-10-31 用户实测
- **2026-10-30｜i18n 全量化批次**：编辑模块 ~300 条非日志字面量全迁（异常/弹窗/标题/检查器全件/打包日志行），双语言包 zh_CN·en_US 各 354 键；异常面收 I18nKey（LangException 受检基类 + TipException/ShaderParseException 构造器收键）；@InsField/@InsHeader/InsEnumLabel 的 label 改语言包局部键；源码机检 CjkStringLiteralCheckTest 落测试红线；测试侧 EditorLangTestBase 夹具 → `Editor_Plugin_Design.md` v0.3 §9 ｜✅ 2026-10-31 用户实测
- **2026-10-30｜资产热重载注册表流派 + 运行期可变 + Cleaner 重构随批**：① Walker 改对象注册表全量扫描（ObjectId 增构造回调 registrar，零运行时开销 + EditorObjectRegistry 弱引用 + Walker 只扫单对象不递归，换指针 setter 优先，导入回调总线化）；② **Texture2D 冻结废除**（运行期可变 + revision 驱动 materialSet 两帧退役重建）+ Material.setShader 改 Unity 保参；③ Cleaner Registration 合一（手动 dispose 与 Cleaner 共用两帧延迟队列、废除句柄登记集合）+ VulkanDevice.dispose 前 flush；④ logback 文件输出启用（info/error 双 rolling、20MB 切分、logs/ 入 .gitignore） → `Engine_AssetReload_Design.md` v0.5 / `Engine_Texture_Design.md` v0.3 / `Engine_Material_Design.md` v0.5 / `Vulkan_Pitfalls.md` #26 ｜✅ 同日用户验收（单测 + 实机全过）
- **2026-11-07｜修复批次：检查器旋转行跳动根治**：用户报「调旋转时跳动、另两个值也变」——根因 = 原 XYZ 欧拉规范域 Y∈(-90°,90°) 越界后帧级回显的等价翻转三元组覆盖显示值；修法 = 欧拉口径切 Unity 同款 Z-X-Y（RotationMath 换算单点）+ 面板级自旋回声抑制（近期写入环 + 事件原始四元数同旋转判定）；随实机复验追修 = 判定器按模长归一（float32 非严格单位化致抑制失灵） → `Inspector_Design.md` v0.15 ｜✅ 用户实机验收
- **2026-11-07｜修复批次：二进制序列化大数组 IO 修复**：用户问「天空盒加载怎么这么耗时」（6.29MB cubemap 5448ms）——根因 = 两侧直连裸 FileInputStream/OutputStream（自身无内部缓冲），byte[] 逐字节 = 每字节一次系统调用（实测 ~1.15MB/s）；修法 = 两侧外层流加缓冲 + byte[] 走批量（字节布局零变化、旧产物免重导）；效果 5448ms → 3ms，同族受益 Image 像素 / SPIR-V / .scene 内字节数组 → `Vulkan_Pitfalls.md` #33 / `Engine_Material_Design.md` §3.2 第 4 条 ｜✅ 用户实测
- **2026-11-08｜变换 Gizmo 旋转/缩放三件齐备**：旋转/缩放工具本体 + 几何改程序化生成（`GizmoMeshes` 照搬 Babylon 规格，`axisY.smesh` 退役）+ 缩放恒局部 + 工具栏两钮解禁 + **柄拾取改 CPU 碰撞体射线**（用户实机反馈「轴太细/环太粗」后改判：ID 附件只服务场景物体，Gizmo 按 Babylon `colliderMeshes` 口径「可见几何与拾取体解耦」） → `Editor_Design.md` §10 / `Engine_Picking_Design.md` v0.2 ｜✅ 2026-11-08 用户实测全项
- **2026-11-08｜耗时操作指示器**：`swing/dialog/TimeConsumingDialog` 静态 start/end/wrap，超 1s 弹无边框非模态「处理中」（线程名 + 已用时，200ms 刷新），标记清零自动关；EDT 标记跳过 + 监视线程按需存活 + 同线程嵌套按最外层计时；双语键随批 → `Editor_Swing_Design.md` §4.3 v0.2 ｜✅ 2026-11-08 用户确认验收；遗留 = 接调用点（焦点扫描 / 工程加载链 / 打包构建线程）
- **2026-11-08｜修复批次：导入期引用解析壳管理器 ShellAssetManager**：用户报首启 .texture 导入必现 `FileNotFoundException: Library/<image guid>.asset`——根因 = 文本协议导入期 `@guid` 引用经真实 AssetManager 强加载 Library 产物，而索引登记以 .meta 为准不检查产物 + 导入多 worker 无依赖序；修法 = 独立壳管理器（导入期不加载产物：引用存在性按 MetaManager.getMetaByGuid 判、命中造壳 + setGuid；其余接口面显性抛错）+ AssetImportPipeline 构造去 AssetManager（改 `(Project, MetaManager)`）；语义边界 = 只去掉「依赖产物必须已生成」，引用存在性校验保留 → `Editor_Design.md` §6.3 / `Engine_Texture_Design.md` v0.4 / `Engine_Material_Design.md` v0.7 ｜✅ 2026-11-08 用户实测

---

## 3. 三期：光照——散件台账

> 批次①②③④-A⑤的目标、拍板与实装形态明细内联在总纲 §3（四批内容 + 着色器配套口径）与各专档（`Engine_Lighting_Design.md` §9 / `Engine_Skybox_Design.md`），故本节只收不属五批主线的随批散件；**批次④-B（三期收官件）同步列此**。

- **2026-11-01｜随批两散件**：project.create.scene 创建场景命令 + 启动场景记忆写环（重启恢复上次场景） → `Editor_Menu_Design.md` / `Editor_Design.md` ｜✅ 2026-11-01 同日用户实测
- **2026-11-08｜三期批次④-B（Deferred 复用，三期收官件）**：GBufferPass（消费 `Pass GBuffer` 同名桶剔透明；三张 MRT = albedo RGBA8 / 世界法线 RGBA16F / 世界位置 RGBA32F+w 有效标志；复用本视口 depth）+ LightingPass（覆盖制全屏三角形；frag 带 `//@javelinLighting` 标记 → 导入期注入相机块+光照段函数+G-Buffer 三采样 = **与材质 Forward fs 同一 `javelinLighting` 常量**；背景按 worldPos.w<0.5 discard；无深度附件）+ set1 扩 binding5/6/7（G-Buffer 三采样 per-view，NEAREST/CLAMP）+ `pipelineFor` 按 shader Pass 名取颜色格式列表（`registerPassColorFormats`，"GBuffer" = 三张 MRT）+ 管线双 Pass 列表（`setProfile` 运行期切；`addPass/removePass` 两档同步——Gizmo/拾取/调试线切档不丢）+ `JavelinSetting.renderPipeline` 持久化 + 引擎启动消费 + **HYBRID 强制 1x**（MSAA-GBuffer 归四期）+ 编辑器 `tools.renderPipeline` 命令（运行期切换 + 写盘 Game/javelinSettings.settings）+ 内置 Lit.shader/default.shader 各补 `Pass GBuffer`（default 保 HYBRID 下紫色错误显性化）+ `ShaderImporterTest` 补 GBuffer pass 断言；**不引入 lightingRT/HDR/Composite**（四期第 1 项） → `Engine_RenderPipeline_Design.md`（§4.2/§4.3 实装形态、§5.1、§6.3、§8 步骤二）/ `Engine_Lighting_Design.md`（§9 批次④-B）/ `Engine_Material_Design.md` §4.2（`Pass GBuffer` 契约）/ `Engine_Uniform_Keys.md` §4（binding5/6/7 + 保留字）/ `Editor_Menu_Design.md` v0.21 ｜✅ 2026-11-10 用户实机验收（HYBRID 光照正常、两路一致；随验坑 Pitfalls #35——set0 阶段可见性 VERTEX-only 致 LightingPass fs 读 cam.view 读零全黑，修=ALL_GRAPHICS 超集）

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

---

## 6. 已收口专项归档

> 跨模块专项，别处无副本（构建/开源基建、文档结构治理，非阶段能力变更）。

- **groupId / 包名迁移 + 版本定版（2026-10-29 拍板 → 2026-10-31 用户实测销账）**：目标命名 `cn.donting.javelin`（包根与坐标对齐，artifactId 与目录名零改动，触发条件 = 开源 / Maven Central）；执行 = 用户 IDEA Shift+F6 主树重构，随批补齐 IDEA 未覆盖面（javelin-core test 树整树、字符串字面量 15 处、全部 build.gradle、project-template 坐标与 mainClass）+ doc 17 份 FQCN 替换；**用户游戏项目默认 groupId 占位符 = `com.javelin.game`**（与引擎坐标无关，非残留）；全仓机检 `com.javelin` 归零。**版本号终态 = `0.1.0-SNAPSHOT`**（沿革：`1.0-SNAPSHOT` → 当日反转定 `0.1.0` 固定无后缀 → 用户复盘改回 SNAPSHOT：开发期保持 SNAPSHOT、release 发布时才去后缀定版，Central 不收 SNAPSHOT 的约束移到发布批）；收 `gradle.properties` 单点（7 个 build.gradle 删 `version =` 继承根）。验收 = test 全绿 + mavenLocal 重发新坐标 + 三端实机（编辑器新建项目/打包）与改前一致。台账独有要点：MCP rename 实测不支持包级故手工迁移。
- **开源前置批（2026-10-29 起步 → 2026-10-31 落库）**：**License = MIT**（个人开发者作品，与 LWJGL BSD-3 / JOML MIT 生态一致）；THIRD-PARTY-NOTICES.md 十一项清单 + 思源黑体 OFL.txt 随字体同目录，**许可倒挂纠错 = FlatLaf 实为 Apache-2.0（jar 内 META-INF/LICENSE 实证，此前记 MIT 错）、JSVG = MIT**；README 双语落库（README.md 英文主档 + README.zh-CN.md 互链；用户指正两处已修：AOT 部署为核心目标须在定位段突出、环境要求写 **GraalVM for JDK 25** 而非裸 JDK）；**默认语言 en_US**（`lang/en_US.properties` editor.core 全量 50 键 + `I18n.DEFAULT_LANGUAGE` 切 "en_US"，zh_CN 降为可选本地化包）+ 首选项菜单与语言设置全链（file.preferences + 语言包注册制 + EditorSettings.language + 启动链 setLanguage，确定写盘 + 重启生效）；命名收尾 = GitHub 远端与 `rootProject.name` 去 "5"（本地目录名不动——改目录会断 IDE 工程引用 / gradle 缓存 / AGENTS 路径）；剩余 = 插件域待拍板 3 项（组件显示名 / 命令 id 前缀化 / FileType 冲突策略）。已知遗留呼应 = `./gradlew` 对 Windows cmd/PowerShell 不可用（根缺 gradlew.bat）、distributionUrl 仍走腾讯镜像。

- **文档结构治理（2026-11-08）**：`Engine_Roadmap.md` 涨到 72KB（单文件逼近不可读）——按「总纲 + 分卷」拆出本卷，并把逐字流水压成批次索引（54KB → 26KB）；同期立 AGENTS **红线 16**（进度 / 台账类文档单文件 ≤ 50KB，超限即分片，手法照本次：总纲留状态摘要 + 分卷索引表 + 指针，节号沿用原编号）。范围收窄 = 只约束随批次线性增长的进度文档，设计档 / 参考资料 / `Engine_Features.md` 功能清单不受此限（量由设计范围决定）。

---

> （注：部分内容可能由 AI 生成）

# 编辑器菜单与命令系统设计（v0.22）

> v0.22（2026-11-13）：工具域**删 `tools.renderPipeline`**（管线拆成两个独立实现类、运行期不再切档，编辑器固定混合管线；原命令表行划除）；同期新增 `tools.postProcessing`（后处理开关，随四期第 1 项步骤一）。
> v0.21（2026-11-08）：工具域补 `tools.renderPipeline`（渲染管线档位 FORWARD ↔ HYBRID 运行期切换 + 写盘，随三期批次④-B 落地；命令表 §… 已登记）。

> 定位：`javelin-editor` 的菜单/命令子系统——命令层（Command）+ 菜单结构注册（MenuRegistry）
> + 菜单渲染统一 Swing 弹窗（Web 浮层菜单会被视口 Canvas 盖）。
> （2026-10-18 去 Web 化收尾：/menu、/command 桥入口随 jcef 清退删除，命令执行全量
> Swing 直调 CommandService；本文桥协议相关章节转为历史沿革，命令/注册/渲染本体不变。）
> 定稿：2026-09-28。讨论沉淀路径：菜单遮挡约束 → 渲染形态 → engine2 体系对照 → 融合方案；
> v0.2：菜单域收敛为**三类**（顶栏 / 场景树 / project），右键两域设**上下文域基类**；
> v0.3：命令定义形态改为**类实现 MenuCommand 接口（三域基类）**，lambda 保留为快捷通道；
> v0.4：**渲染判定规则作废、菜单统一 Swing**——2026-09-30 实测层级面板右键 Web 菜单
> （已做 boundary 夹紧）仍被视口 Canvas 遮挡，「按浮层位置判定渲染方式」不可靠，
> `DropdownMenu.vue` Web 右键路径删除、右键域并入 `/menu/open` Swing 链路。
> v0.5（2026-10-01）：**子菜单支持**——MenuRegistry 结构项（label + children 任意嵌套）、
> Swing JMenu 递归渲染（每层打开瞬间求值 enabled/checked）、MenuItemVo.children 填充
> （/menu/list 协议面）；层级右键域按 Unity Create 口径组「创建」子菜单；弹窗最小宽度
> 垫宽（MIN_WIDTH=140，项少不局促）；**层级右键域 Unity 壳对齐**（未实现命令占位
> 注册置灰，§4；pasteAsChild/duplicate 暂不注册，随功能落地再加）+ 菜单项快捷键
> accelerator 右对齐显示（仅弹窗期间生效，§5）。
> v0.6（2026-10-01）：**「文件」顶栏域首个命令 file.save（保存场景 Ctrl+S）**——
> 随 .scene 序列化落地（Engine_Scene_Design §9）：TextExporter 文本协议写回
> EditorScene.gameSceneFile，保存走 submitTask 帧边界，成功 clearDirty +
> 发布 scene.changed（前端树根星号消失）；另存为/打开/新建场景随下期落地（拍板
> 2026-10-01：首期仅保存）。
> v0.7（2026-10-11）：**菜单图标 + 层级右键 Unity 结构重排 + tools.refresh**——
> SvgIcon（JSVG）classpath /icon/ 加载 SVG 渲染 Swing Icon、MenuCommand.icon()
> override 即配（勾选命令不配，§5.3）；层级右键按用户清单重排为 Unity 结构
> （剪切/复制/粘贴—删除—空对象—3D 对象/灯光/粒子/摄像机，rename 撤出、
> 未实现项占位置灰）；工具域补 tools.refresh 刷新 web 页（§8）。
> v0.8（2026-10-11）：**hierarchy.create.camera 摄像机命令落地**（替换占位注册行）——
> Unity Camera 口径：节点名 Main Camera / Main Camera (n)、位姿 Unity 新建默认
> （原点无旋转）、相机参数全取组件默认（main 标志不预设——引擎侧 main 消费未落地）；
> 入用户 game scene，SCENE 模式编辑器视口不受影响（EditorScene 相机按 SceneModel
> 双模式分流：SCENE 渲染编辑器相机、GAME 渲染用户相机），Camera write/read 已通
> 保存重载可恢复。
> v0.9（2026-10-11）：**「构建」顶栏域落地（非 Unity 菜单结构，用户指定域）**——
> build.compile 编译（实而先禁：引擎侧 `GradleProject.compile()` 已备，菜单入口
> 按拍板恒置灰，启用去掉 enabled() 覆写即接通）、build.pack 打包（弹 CEF 模态
> 对话框：独立前端页 page/pack.html，宿主=JDialog 版 CEF 容器
> `window/cef/dialog/Dialog`（URL 传参、模态挂 MainFrame、DISPOSE_ON_CLOSE 连
> 浏览器销毁）；「开始打包」同日接入真构建（用户落地 GradleProject.jarBuild：
> /build/jar 桥同步执行 dumpRuntimeJars→build→**jlink 裁剪运行时**→产物组装
> （运行时=jdeps 算模块清单+jlink 只装所需模块的 JRE 级，替代整 JDK 镜像——
> 2026-10-11 拍板「游戏运行时只需 JRE，不带 javac/jmods 工具链」）；**日志实时推送**——
> ConsoleOut 行经 JavaScriptBridge 定向推调用者浏览器（build.out.info/warn/error，
> console.* 前缀保留给控制台面板，
> 依赖桥方法 CefBrowser 参数注入新机制，见 Editor_Design §4.1），对话框日志区
> 流式回显+自动滚底、响应到达即完成（返回值为结束标记），构建期按钮
> loading/取消置灰；**「启动场景」下拉已接真数据**（onMounted 拉取 /build/scenes
> ——AssetManager.listAssetPaths(".scene") 从资产索引枚举、每次先重载索引拾取
> 运行期新导入，默认选中项目默认场景；core AssetManager 接口为此补了
> listAssetPaths 查询方法，见 Editor_Design §6.4）；**表单参数随构建持久化进项目
> Game/**（2026-10-11 拍板「写进 Game 再原封复制」：窗口元信息→
> javelinSettings.settings、启动场景→game.settings 的 defaultSceneGuid 经伴生 .meta
> 解 guid；空值/非法值跳过不覆写、构建模式仍不消费），组装阶段 **Game/ 与 Library/
> 原封复制进产物**（GameMain 运行时从 runDir/Game 读设置、场景走 Library guid 产物）；
> 关窗仍走 X）。
> v0.10（2026-10-13）：**打包对话框「构建形态」JAR/AOT 双链落地**（四拍板：AOT
> 全链落地 / 产物目录采纳 **Builds/**（jar+aot 一级子目录抗 gradle clean，jar 形态
> 产物自 build/jar 迁移，根与 project-template 的 .gitignore 补 Builds）/ 构建模式
> 本批消费 / 桥改名 **/build/pack**——BuildPackPar+packBuild 按形态分流，引擎侧
> jarBuild/aotBuild 链名不动）。AOT 链 = nativeCompile（GraalVM native-image，模板
> graalvmNative 配置已备本批接通）+ 产物组装 Builds/aot：exe + **外置 lib/*.dll**
>（模板补 extractLwjglNatives 任务镜像 javelin-core；新增 **AotGameMain** 主类设
> org.lwjgl.librarypath=./lib——DLL 不进 exe 防体积膨胀，2026-10-13 拍板）+ Game/
> + Library/ + bat（同 jar 形态口径）。构建模式仅 AOT 形态显示并消费：Debug→
> quickBuild 快速构建 / Release→全优化（runTask withArguments -PbuildMode 下发，
> JAR 形态下该行隐藏——JVM 无优化差异）。首跑风险在档：GraalVM 25 toolchain +
> MSVC 环境前置、三方反射（logback）可达性配置、exe 体积验证（DLL 外置应显著
> 更小）。
> 实测验收（2026-10-13 深夜）：AOT/JAR/编辑器三端 DebugText/FPS overlay 一致——
> 三条资源路径（classpath / jlink JAR / native-image include）全验证，双链关账。
> v0.10 修复（2026-10-17）：jar 组装 lib 目录路径错位——`createOutputJarApp` 拼成
> `rootDir/build/jar/lib`（构建工作区）而非产物目录 `Builds/jar/lib`，依赖 jar 全部
> 落错位、启动脚本 `-cp %~dp0lib\*` 落空致主类不可寻；改 `new File(file, "lib")` 与
> AOT 形态（`createOutputAotApp`）同口径（GradleProject.createOutputJarApp）。
> v0.11（2026-10-17）：**project 右键域首期落地（2026-10-23 随 M3 批用户验收）**——
> EditorSelection project 写入方接线：`/project/select`（ProjectMethod，相对路径
> 解析 + 边界闸门防逃逸项目根，null 清除；右键路径前端 await 落上报再 openMenu，
> 菜单 enabled 求值读新选中态）；三实做命令（menu.project：showInExplorer/
> copyPath/reimport——Desktop.browseFileDirectory 或 open / 剪贴板 /
> AssetImportPipeline.submitImport 目录递归）；Unity 壳占位（menu.project 域
> UnimplementedMenuCommand 恒置灰：open/创建族/paste/rename/delete）；ctx.project
> 菜单结构一次对齐 Unity（创建子菜单+两道分割线）。面板 UI 侧见 Editor_Design §10
> 项目面板条目。
> v0.12（2026-10-18）：**project.open 打开命令实做（替换占位注册行）**——
> OpenFileMenuCommand（Enter）按 FileTypeRegistry 分发（Editor_Design §6.5
> 文件类型注册器）：enabled=选中文件 canOpen；首批 .scene 可开（脏场景三键
> 确认 + submitTask 帧边界切换场景，保存分支复用 SaveSceneMenuCommand.save）。
> v0.13（2026-10-19）：**project.create.material 创建命令实做（创建族首项，
> 随 .mat 导入管线落地、2026-10-23 用户验收）**——CreateMaterialMenuCommand：选中目录落
> `material.mat`（编号去重），初始内容=内置 default.shader 的 Material 文本
> 序列化，**源文本 guid 与伴生 .meta 同源**（先 getOrCreateMeta 拿 guid 再落盘，
> .scene 保存同口径）；落盘即 submitImport（产 Library 产物/更新 index，
> 异步管线），发 project.changed 驱动项目面板重拉树（**首个改盘命令接通面板
> 刷新链**，Editor_Design §10 遗留销账——快照重拉经 lastSelectedDirPath 恢复
> 目录锚点不丢位置）；「创建」子菜单加「材质」项（folder/scene/script 占位
> 不动）。.mat 格式与导入器见 Engine_Material_Design §3.2。
> 占位剩：创建族 folder/scene/script、paste、rename、delete。
> v0.14（2026-10-24）：**hierarchy.create.cube 立方体命令实做（替换占位注册行，
> 当日用户实测验收）**——CreateBoxMenuCommand：网格取新增内置资产 box.smesh
> （1×1×1 居中，六面各 4 顶点独立法向/UV，三角绕序按几何法向外向逐面叉积
> 校验——+Y 面沿用 plane 的 0,2,1/0,3,2 模式）；材质=default.shader 运行期
> 实例，挂入/选中/重名去重（Cube (n)）/线程语义全同 CreatePlaneMenuCommand
> （自包含一份，「一动作一类」现状；plane/cube 两份同式代码的公共基类抽取
> 再议——IDEA 重复段告警已现，拍板归用户）；icon=box（图标资源已备）、
> label 走既有 i18n 立方体条目零新增。
> v0.15（2026-10-25）：**hierarchy.create.sphere/.capsule/.cylinder 原始体
> 三件套实做（sphere 替换占位注册行、capsule/cylinder 新增菜单项，当日用户实测验收）**——
> 三份 .smesh 由旋转体（lathe）生成脚本离线产出（一次性工具，用户拍板不入库——
> 产物即交付物）：母线 (r,y,2D法线)
> 绕 +Y 旋转 24 段，硬边（圆柱端盖/侧面交界）用母线重复点分离法线、平滑体
> （球/胶囊）法线连续，落盘前逐三角形叉积自检外向（多数内向则全局翻转
> 复验，对齐 box 六面校验口径）；guid 沿用既有文件头（重跑稳定、产物不换档；
> 2026-10-29 拍板废除文件头真值——guid 归伴生 .meta 随包释放，见 Editor_Design §6.2）。
> 产物尺寸 Unity 口径：球直径 1（UV 球 24×12）、圆柱高 2×直径 1（含端盖）、
> 胶囊总高 2×直径 1（中段 y∈[-0.5,0.5]+半球盖）。三命令类照 cube 自包含
> （同式第四~六份，公共基类抽取仍留拍板——「一动作一类」现状不变）；菜单序
> plane,cube,sphere,capsule,cylinder,pyramid（Unity 相对序插入）、i18n 新增
> 圆柱体/胶囊体两行。
> v0.16（2026-10-25）：**project.create.shader 创建命令实做（创建族第二项，同日用户实测验收通过）**——
> CreateShaderMenuCommand：选中目录落 `NewShader.shader`（编号去重），内容=极简
> 前向模板（_Color Color 属性走 Properties→MaterialBlock 通路、Pass Forward 不透
> 底稿）；**用户文件不写 guid 头**（用户拍板：guid 头=内置资源专属，重释放稳定
> 语义不外溢用户文件——**2026-10-29 再收窄废除：guid 头只剩 src 下 .java，「内置
> 资源专属」口径作废、内置资源改 .meta 随包释放，见 Editor_Design §6.2**）——guid 真值归伴生 .meta 随机分配，导入器走「头缺失回退
> meta」既有路径；配套收窄 MetaManager.createNewMeta：.shader/.smesh 缺头不再注入
> 路径种子头（原注入路径恰由本命令首次踩中）、改随机 guid（.java 保持注入——脚本
> 无 meta、头是唯一真值）；「创建」子菜单序 folder/material/**shader**/scene/script
> （渲染类资产相邻）、i18n 增「着色器」一行。**图标**：新建 shader.svg（紫色三角+顶点节点，顶点/片元
> 管线意象、紫呼应 default.shader 固定紫）——菜单项（icon() override）与 .shader
> 文件条目（FileTypeSetup 补注册纯图标型）共用。占位剩：创建族 folder/scene/script、
> paste、rename、delete。
> v0.17（2026-10-25，**当日用户实测验收通过**）：**project.create.texture 创建纹理命令（创建族第三项，
> Engine_Texture_Design v0.2 贴图管线批次）**——CreateTextureMenuCommand：选中目录
> 落 `texture.texture`（编号去重），内容=空 image 引用+默认采样设置的 Texture2D 文本
> 序列化（「.texture 就是 Texture2D 的序列化」、guid 与伴生 .meta 同源四处一致——
> CreateMaterialMenuCommand 同款）；落盘即 submitImport（TextureImporter 文本导入器
> →Library 二进制产物）+ project.changed 面板重拉。「创建」子菜单序
> folder/material/shader/**texture**/scene/script（渲染类资产相邻）、i18n 增「纹理」
> 一行、texture.svg 棋盘格图标（菜单项与 .texture 文件条目共用；与 image.svg 山景
> 照片区分）。占位剩：创建族 folder/scene/script、paste、rename、delete。
> 追修（2026-10-25 验收后，用户报「新建的材质拖到 MeshRenderer 上 GUID 未找到」）：
> 根因=**创建命令异步导入窗口**——submitImport 入队即返回，用户立刻拖引用时导入未
> 完成（guid 未进 index/产物未落 Library），GUID_ASSET 回写链 loadGuid 解不到。修三刀
> （material/shader/texture 三命令同步受益）：①管线新增 `awaitImport(task, timeout)`
> （ImportTask.status 升 volatile 保跨线程可见；超时=放弃等待不误报）；②三创建命令
> submitImport 后同步收口（小体量毫秒级、shader 含编译秒内），导入 FAILED 弹错误框
> （红线 15）；③AssetLibManager.loadGuid miss 先重载 index 再查（pathOfGuid/
> listAssetPaths 同口径——表为 ConcurrentHashMap，结构安全）。
> v0.18（2026-10-29，**待验收**）：**file.preferences 首选项命令（文件域第二项）**——
> PreferencesMenuCommand 弹 `swing/dialog/PreferencesDialog`（模态挂 MainFrame、
> invokeLater 弹窗对齐 PackDialog 线程契约），语言设置首批；**语言包注册制拍板**
> （同日，否决 Lang 枚举）：支持语言由插件注册（`I18n.registerLanguage(fileName,
> displayName)` 经 PluginContext 收口），语言包按 `registerLanguagePack(域, 语言,
> 路径)` 显式注册（`lang/{lang}.properties` 固定路径规范废除——文件布局自定、
> 一文件一域，集中式语言包可翻译其他插件域），editor.core setup 注册 en_US/zh_CN
> 语言 + 两域语言包，语言设置菜单选项源 = I18n.languages() 注册表；**确定写回
> EditorSettings.language 存盘 + 重启生效**（拍板 2026-10-29：全部配置修改重启生效——I18n 语言启动期一次
> 设定不热切）；启动链语言解析落 `JavelinEditor.main`（SettingsManager.
> loadStandaloneEditorSettings 独立读 ~/.javelin/editor.settings：已存值优先、
> 未设置按系统语言「语言_国家」tag 解析；**缺键逐键回退该域基线 = 该域第一个
> 注册的语言包**（2026-10-29 拍板——英文不特殊，中文作者的插件基线可为 zh_CN），
> setLanguage 启动期一次）。菜单序
> 保存 ─ 分隔 ─ 首选项；i18n 增 file.preferences + preferences.* 5 条随批。
> 命名拍板：「首选项」非「设置」（Unity Preferences / Windows 惯例，用户 2026-10-29）。
> v0.19（2026-10-31，随三期批次②）：**hierarchy.create.light.directional / point / spot 灯光三命令落地**（替换占位注册行）——
> CreateLightMenuCommand 一类三命令（构造注入 id、baseName、组件子类——灯族子类化
> 拍板后组件身份即类型，不拆三类不建切换）；Unity 口径：节点名 Directional Light /
> Point Light / Spot Light (+n)、位姿新建默认（原点无旋转——方向光朝局部 +Z）、
> 灯参数取组件默认；
> **主光无标志**（2026-10-31 拍板对齐 Unity：引擎自动裁决最强激活方向光，
> Scene.getSun 单点——创建命令不做任何 isMain 预设，Lighting 档 v0.5）；
> 菜单三项全解锁（v0.6 灯族子类化后 spot 收集侧已支持——着色消费批次③，菜单项先行）。
> v0.20（2026-11-08）：**hierarchy.create.environment 环境设置命令落地**——
> 建名为 `Environment` 的节点并挂 `Environment` 组件（`@UniqueComponent(scene = true)`
> 全场景至多一个；Engine_Scene_Design §2.5）；**固定挂场景根级**（环境是场景级设置，
> 挂到任意物体下无意义、父节点被删还会连带消失），创建后自动选中新节点
> （Unity 语义，节点检查器随即出环境组件节）；只写不发通知（SceneWatchAppState 快照
> diff 自动发现，CreateCameraMenuCommand 同口径）。
> **菜单项恒可用**（用户拍板 2026-11-08）：环境已存在/场景未就绪不置灰，而是点击后弹
> 警告说明原因——置灰会让用户不知道「为什么不让创建」；判定在引擎线程做、弹框 hop 回
> EDT（i18n 键 hierarchy.create.environment.exists / .noScene）。

## 1. 背景与约束

**设计基准**：编辑器整体完全参考 Unity（`Editor_Design.md` §1，拍板 2026-09-28）——
菜单组织、菜单项语义、快捷键习惯均以 Unity Editor 为默认答案。

### 1.1 渲染遮挡约束（第一约束）

主窗口是「挖洞式视口」结构（见 `Editor_Design.md` §3）：CEF 浏览器组件铺满整窗，
AWT `Canvas`（Vulkan 呈现面）作为独立组件**浮在 CEF 之上**、只占视口矩形（rectChange 驱动）。

推论：**Web 页面内画的任何浮层（下拉菜单、居中弹窗）延伸进视口矩形，就会被上层的
Canvas 盖住**。

由此得到本系统的渲染规则（v0.4 收敛）：

**菜单浮层一律 Swing 原生弹窗**（heavyweight popup 天然浮于 Canvas 之上，不按位置判定）。
v0.1~v0.3 曾按「浮层位置」判定（不进视口矩形 → Web 自渲染、必盖视口 → Swing），
2026-09-30 实测推翻：层级面板右键 Web 菜单已做 boundary 夹紧（菜单浮层限制在面板
矩形内）仍被视口 Canvas 盖住——面板与视口相邻，按位置判定在实践中兜不住，菜单一律
Swing、Web 浮层菜单全面禁用。

同类潜在坑备案：Web 居中模态弹窗（现有 `ConfirmDialog`/`PromptDialog`）将来若覆盖
视口区域会撞同一堵墙（届时同走 Swing 弹窗口径处理）。

### 1.2 多入口统一

动作的触发入口（现有与规划）：

1. 顶栏菜单（Swing 弹窗）
2. 面板右键菜单（Swing 弹窗）
3. 快捷键（预留，见 §9）
4. 面板工具按钮（预留）

四个入口必须指向**同一命令集**，因此需要稳定的命令 id 作为桥协议与各入口的公共锚点。
这是命令层存在的根本理由。

## 2. engine2 对照与取舍

engine2 有成熟的 Swing 面板右键菜单体系（`engine-editor/.../menu`：`PanelMenu<T>` 四件套
+ `@MenuItem` 注解 + 包扫描 + panel 侧硬编码组装）。逐项对照：

| engine2 做法 | 取舍 | engine5 替代 | 理由 |
|---|---|---|---|
| 动作与菜单结构分离（注解声明 group/name） | ✅ 继承思想 | Command + MenuRegistry | 分离方向正确，实现方式换 |
| `disabled()` 显示前动态求值 | ✅ 继承 | enabled/checked Supplier 弹出时求值 | 菜单是瞬态 UI，显示瞬间求值永远新鲜 |
| 每面板一个菜单域（Hierarchy/Project） | ✅ 继承 | MenuRegistry 菜单域 | 场景树右键、项目面板右键各自成域 |
| `setPanel(this)` 注入上下文 | ❌ 弃 | 域基类读全局选中状态（§3.2） | 动作强绑 panel 则顶栏/web/快捷键进不来；上下文全局化后四入口同一命令 |
| 注解声明 + `addGroup` 硬编码顺序（两处咬合） | ❌ 弃 | 声明式结构单源 | engine2 加一个菜单项要动三处（建类/标注解/改组装）；单源后一处 |
| 一动作一个类（30+ 文件） | ⚠️ 简化 | lambda 集中注册 | 模板代码占比高、注解 value 与 `name()` 双份真相、包扫描是已知性能坑；命令量大后再考虑类化，注册协议不变 |
| 面板右键 = JPopupMenu | ✅ 回归 | 面板右键同样 Swing JPopupMenu | v0.4：Web 右键实测仍被 Canvas 盖（§1.1），v0.2「换轨走 Web」结论推翻 |
| 无命令 id / 无 checked / 无 shortcut | ❌ 补 | Command 字段补齐 | Web 触发（桥协议）、勾选命令（网格开关）、快捷键显示都需要 |

## 3. 命令层（Command / CommandService）

### 3.1 命令定义形态与数据内核

命令以**类实现 `MenuCommand` 接口**为主形态（可选项全 default，简单命令只实现
id/label/execute）。**域分包**：`menu.top` / `menu.hierarchy` / `menu.project`
三个域包，域基类与该域具体命令同包；`menu` 根包只放框架件。`Command` 数据类是内部内核
（Supplier 模型）——桥/MenuRegistry/Swing 层只认内核，定义形态不影响下游。
两注册通道并存：

```java
public interface MenuCommand {
    String id();                                  // 如 "tools.devtools"（桥协议与四入口锚点）
    String label();                               // "开发者工具"
    default String shortcut() { return null; }    // "Ctrl+Shift+I"，第一期仅显示
    default void execute() { }
    default Boolean enabled() { return null; }    // null = 恒可用
    default Boolean checked() { return null; }    // null = 非勾选命令；有值 = 勾选菜单项
}

CommandService.register(new XxxMenuCommand(...));                            // 类形态（主）
CommandService.register(Command.of("id", "标签").action(...).build());       // lambda 快捷通道
```

集中显式注册于 `EditorMenuSetup`，**不做注解 + classpath 扫描**：菜单结构顺序/分割线
仍由 MenuRegistry 单源声明，扫描省不掉结构注册，反而回到 engine2「扫描 + 硬编码」
两处咬合的老路并引入全量扫描性能坑。

### 3.2 上下文契约：三类菜单域 + 域基类

菜单域收敛为**三类**（现在只有这三类），两个右键域各设抽象基类提供类型化上下文：

| 菜单域 | 基类 | 上下文 |
|---|---|---|
| 顶栏（如 `tools`） | `TopMenuCommand`（marker，暂无成员） | 无上下文，全局命令 |
| 场景树右键（`ctx.hierarchy`） | `HierarchyMenuCommand` | `getSelectedNode()` 当前选中 Node，无选中 null |
| 项目面板右键（`ctx.project`） | `ProjectMenuCommand` | `getSelectedFile()` / `getSelectedDirectory()` 当前选中文件/目录，无选中 null |

三域基类是**抽象类 implements MenuCommand**——一个命令 extends 一个域基类（抽象类
强制单一域归属，接口则可同时实现多域无意义）；类型即域标注（找某域全部命令 =
找基类子类）。上下文来源是 **Java 侧全局选中状态单源 `EditorSelection`**（已落地
最小实现：场景选中存 Node id 取用时经 ObjectCache 反查，project 选中存路径；
写入方=前端经桥上报，随场景树 / project 任务接线，当前读取恒为无选中）。

这是对 engine2 `PanelMenu.setPanel` 注入的替代：engine2 的 panel 在 Swing 侧、上下文从
panel 实例取；engine5 的 panel 在 web 侧，上下文从全局选中状态取——**基类只读不持有**，
单一数据源，四个触发入口（顶栏/面板右键/快捷键/按钮）行为一致。命令仍不持有 panel /
Swing 组件 / web 上下文引用。

project 选中写入方已于 2026-10-17 接线（v0.11）：前端经 `/project/select` 上报
（ProjectPanel 单击 fire-and-forget / 右键 await 后弹菜单），Java 侧相对路径解析
+ 边界闸门（越出项目根记 warn 忽略）；场景选中与 project 选中两通道均活跃。

### 3.3 线程契约

命令可能在 EDT（Swing `ActionListener`）或桥线程（`/command/execute`）被调。
**命令内部自行跳线程**：

- 动场景结构 → 引擎帧队列（场景树任务建立的任务通道）
- 动 CEF / Swing → EDT
- 纯逻辑（计数、文件路径计算等）→ 直跑

第一期命令均 EDT 安全，先约定后机制；将来需要时再给 Command 加调度标记，注册协议不变。

### 3.4 CommandService

静态门面：`register(Command)` / `unregister(id)` / `execute(String id)` / `get(id)`。

`execute` 统一包裹异常：记 error 日志 + tinyfd `messageBox` 错误弹窗
（对齐桥接层 `JavaBridgeMethod` 的异常处理惯例）；执行未知 id 记 warn 返回。

## 4. 菜单结构（MenuRegistry）

声明式单源——顺序、分割线、子菜单归属**一处说清**：

```java
MenuRegistry.menu("tools", "工具")
        .item("tools.grid")
        .separator()
        .item("tools.devtools");
MenuRegistry.menu("ctx.hierarchy", "场景树右键")   // 右键域：无顶层标签，右键拉取用
        .submenu("创建", create -> create          // 子菜单：consumer 降层，层级任意嵌套
                .item("hierarchy.create.empty"))
        .separator()
        .item("hierarchy.copy")
        .item("hierarchy.paste")
        .separator()
        .item("hierarchy.rename")
        .separator()
        .item("hierarchy.delete");
MenuRegistry.menu("ctx.project", "项目面板右键")
        .item("project.new.folder")
        ...
```

- 菜单项只存命令 id（不存在则组装时记 warn 跳过——命令未注册不算错误，容忍分批落地）；
- 子菜单为**结构项**（仅 label + 子项列表，无命令 id）：`submenu(label, consumer)` 降层
  组装、层级任意嵌套（Unity Create 口径）；子项全未注册的空壳子菜单组装/拉取时跳过不显示；
- **未实现命令占位注册**（v0.5，Unity 壳对齐）：菜单结构一次对齐 Unity——未实现命令以
  域包占位类 `UnimplementedMenuCommand`（enabled 恒 false）注册，菜单项置灰显示；
  功能落地时以同 id 实类替换 EditorMenuSetup 注册行，菜单结构不动。「未注册即 warn
  跳过」机制保留，用于真实漏注册场景，不再承担"暂未实现"语义；
- 菜单域**三类**（现在只有这三类，见 §3.2）：**顶栏菜单**（有顶层标签，供 `/menu/list`
  顶栏渲染）与两个**右键域**（`ctx.hierarchy` 场景树、`ctx.project` 项目面板，无顶层标签，
  供面板右键拉取）；
- 启动期一次性构建（`EditorMenuSetup`），不做动态增删（§9）。

## 5. 渲染（统一 Swing）

v0.4 起所有菜单浮层一律 Swing JPopupMenu（§1.1）；Web 侧只做触发转发（顶栏标签渲染、
右键坐标上报），不渲染菜单本体。顶栏与面板右键共用同一条 `/menu/open` →
`MenuPopupFactory` 链路，仅坐标语义不同。
子菜单 = `JMenu` 递归嵌套（JMenu extends JMenuItem，直接 add 进 JPopupMenu；子菜单
浮层同由 PopupFactory 决定重量级，浮于视口 Canvas 无虞）——每层挂各自的
`popupMenuWillBecomeVisible`，子菜单项在该层**打开瞬间**求值 enabled/checked。
菜单项 shortcut 文本转 KeyStroke 设为 accelerator（右对齐显示；绑定在菜单项自身
WHEN_IN_FOCUSED_WINDOW InputMap——BasicMenuItemUI.updateAcceleratorBinding，仅弹窗
显示期间生效、收起即无宿主，无全局触发，符合「shortcut 第一期仅显示」；解析失败
记 warn 跳过显示）。

### 5.1 顶栏菜单：Web 标签 + Swing 弹窗

1. Web 顶栏（`MenuBar.vue`）挂载时 `/menu/list`（无 menuId）拉顶栏菜单
   `{id, label}` 列表渲染标签——观感与 Web 主题一致；
2. 点击标签 → `/menu/open {menuId, x, y}`（x = 标签左缘、y = 顶栏下缘，Web 坐标）；
3. Java：web 坐标即 contentPane 坐标（CEF 组件铺满 contentPane，同 rectChange 口径），
   EDT 上直接 `JPopupMenu.show(contentPane, x, y)`；
4. `popupMenuWillBecomeVisible` 时逐项求值 enabled/checked 刷新置灰/勾选；
5. 菜单项 `ActionListener` → `CommandService.execute(id)`。

样式：JPopupMenu 对齐 web 浅色主题（style.css `--editor-*` 色板：白底 `--editor-surface` /
深字 `--editor-text` / 浅灰边 `--editor-border`）；hover 高亮留 LAF 默认（JMenuItem 无
selection setter，UIManager 键属 LAF 私有不瞎写），不深入 LAF 定制（§9）。
弹窗最小宽度 140px（`MenuPopupFactory.MIN_WIDTH`，顶层与子菜单同口径）——Swing 弹窗
按 preferred 尺寸开窗，`setMinimumSize` 不参与开窗尺寸计算，故 preferred 宽不足时
直接改写 preferred 宽、高度保持自然值。

### 5.2 面板右键菜单：Swing 定点弹窗（v0.4）

1. 面板右键 → 先选中上报（如 `/scene/select`——EditorSelection volatile 直写，返回即
   对 EDT 可见；enabled 求值读 Java 侧选中，须先落上报防弹窗求值读到旧态）；
2. `/menu/open {menuId: "ctx.xxx", x: clientX, y: clientY}`（右键 = 鼠标点；
   web 坐标 = contentPane 坐标，同顶栏口径）；
3. Java：EDT 弹 JPopupMenu 于鼠标位置，显示前（`popupMenuWillBecomeVisible`）
   逐项求值 enabled/checked——瞬态状态显示瞬间算一次，天然新鲜；
4. 菜单项 `ActionListener` → `CommandService.execute(id)`（Java 侧直执行，不回 Web）。

（v0.2~v0.3 本节为「Web 自渲染 + `DropdownMenu.vue` boundary 夹紧」，2026-09-30 实测
夹紧后仍被视口 Canvas 盖，路径已删除——见 §1.1。）

### 5.3 菜单图标（SvgIcon / JSVG，v0.7）

**挂点：`MenuCommand.icon()`（default null，override 返回图标名即配）**——资源约定
classpath `/icon/{name}.svg`（`javelin-editor/src/main/resources/icon/`，IntelliJ 系
图标库导出）；渲染 `SvgIcon.of(name, 16)` 实现 Swing `Icon`。渲染约定：

- **勾选命令不配图标**——勾选标记与图标在 JMenuItem 同一图标槽，互挤（Unity 口径
  勾选项也无图标）；
- **资源缺失不设置**（不占空位，也避免缺资源时逐帧 paint 警告）；
- `SvgIcon`（`editor.swing` 包）内部缓存 `SVGDocument`（ConcurrentHashMap，同图
  多处引用不重复解析），首次 paint 时渲染 BufferedImage；
- 弹窗配色三常量随色板微调同步（`--editor-text`/`--editor-border` 新档），深色档
  落地时须改读主题（Swing 不读 CSS，见 §5.1 样式注）。

## 6. 桥协议

| 方法 | 参数 | 返回/行为 |
|---|---|---|
| `/menu/list` | `{menuId?}`（省略 = 顶栏） | 菜单结构数组 `MenuItemVo[]`（menuId 分支 v0.4 后无 Web 消费方，保留作协议面） |
| `/menu/open` | `{menuId, x, y}` | Swing 弹窗（顶栏/右键域共用，无返回数据） |
| `/command/execute` | `{id}` | 命令执行（异常走桥层错误协议；Swing 菜单项 Java 侧直执行不经此，本方法留作 Web 直触发入口） |

命令上下文不进桥参数——`/command/execute` 只传命令 id，上下文由命令经域基类读
Java 侧选中状态（§3.2）；前端的选中上报随场景树 / project 对接任务落地。

```java
public class MenuItemVo {        // 顶层与子项同构
    public String id;            // 菜单 id（顶栏拉取）或命令 id（域拉取）
    public String label;
    public String shortcut;      // 可空，第一期仅显示
    public boolean separator;    // 分隔符项（true 时其余字段无效）
    public boolean checkable;    // 是否勾选命令（渲染形态：勾选框）
    public boolean checked;      // 当前勾选态（仅 checkable 有意义，即时求值）
    public boolean enabled;      // 置灰（即时求值）
    public List<MenuItemVo> children;   // 子菜单（结构项：仅 label + children 递归，无命令 id）
}
```

**与事件总线的分工**：命令走 callJava 请求/响应（动作需要结果反馈）；
事件桥（`EditorEventBus` 跨桥延伸）只负责**持久状态外溢**——场景 dirty、模式切换等
`BridgeEvent` 推送，前端订阅刷新顶栏标签等常驻 UI。两协议各管各的，不混用。

## 7. 状态模型

| 状态类型 | 机制 | 说明 |
|---|---|---|
| 瞬态（enabled / checked） | Swing 弹窗显示前求值 | 菜单瞬态显示，显示瞬间算一次永远新鲜，零同步成本 |
| 持久（场景 dirty、编辑模式） | 事件桥推送 | 影响常驻 UI（标题 `*` 等），走 BridgeEvent |

## 8. 首批命令与菜单

| id | label | 菜单 | 语义 |
|---|---|---|---|
| `file.save` | 保存 | 文件 | TextExporter 写回 gameSceneFile（.scene 文本协议），成功 clearDirty + scene.changed 推送（2026-10-01 落地，随 Engine_Scene_Design §9 .scene 序列化） |
| `file.preferences` | 首选项 | 文件 | 弹 PreferencesDialog（模态挂 MainFrame）——语言设置首批（Lang 下拉），确定写回 EditorSettings.language 存盘 + 重启生效（2026-10-29 落地 v0.18，待验收） |
| `tools.devtools` | 开发者工具 | 工具 | `CefFrame.openDevTools()`——同时是事件桥 ping/pong 验收的 DevTools 入口 |
| `tools.grid` | 网格显示 | 工具 | checked 勾选命令，切换 `EditGridAppState` 网格开关——验证 checked 求值链路 |
| ~~`tools.renderPipeline`~~ | ~~混合渲染（Deferred）~~ | 工具 | **已删（2026-11-13）**：管线拆成两个独立实现类、运行期不再切档——编辑器固定混合管线（`ProjectLoadingFrame` 显式设 `PipelineProfile.HYBRID`），档位字段只供打包运行时启动期选实现类；原「运行期切档 + 写盘 + AA 守卫」随批次④-B 的机制一并退役 |
| `tools.refresh` | 刷新页面 | 工具 | 重载 CEF 内编辑器 web 页（`CefBrowser.reload`，EDT）——引擎/场景状态不动，面板 pull-on-mount 重新拉取；开发期 web 界面状态异常恢复入口（2026-10-11 落地） |
| `hierarchy.create.empty` | 创建空物体 | 场景树右键 → 创建 | 选中节点下创建空节点（无选中=根级），重名去重 GameObject (n)，创建后选中新节点（2026-09-30 落地，v0.5 入「创建」子菜单） |
| `hierarchy.create.plane` | 创建 Plane | 场景树右键 → 创建 | 选中节点下创建 10×10 XZ 平面物体（无选中=根级），重名去重 Plane (n)，创建后选中新节点；mesh=内置 plane.smesh 共享加载（带 guid 落盘可恢复）、材质=default.shader 运行期实例（Material 序列化 2026-10-04 落地，内嵌数据面重载可恢复；.mat 资产格式仍待）；资产未就绪记 error 不建节点（2026-10-04 落地，Unity 3D Object → Plane 口径） |
| `hierarchy.create.cube` | 立方体 | 场景树右键 → 3D 对象 | 选中节点下创建 1×1×1 居中立方体（无选中=根级），重名去重 Cube (n)，创建后选中新节点；mesh=内置 box.smesh 共享加载（六面外向绕序）、材质=default.shader 运行期实例，挂入/选中/线程语义同 plane 命令；icon=box（2026-10-24 落地 v0.14，替换占位注册，Unity 3D Object → Cube 口径） |
| `hierarchy.create.sphere` | 球 | 场景树右键 → 3D 对象 | 选中节点下创建直径 1 球体（UV 球 24×12 分段，无选中=根级），重名去重 Sphere (n)，创建后选中新节点；mesh=内置 sphere.smesh（旋转体算法离线生成、绕序自检）、材质=default.shader 运行期实例，语义同 plane 命令（2026-10-25 落地 v0.15，替换占位注册，Unity 3D Object → Sphere 口径） |
| `hierarchy.create.capsule` | 胶囊体 | 场景树右键 → 3D 对象 | 选中节点下创建总高 2×直径 1 胶囊（中段圆柱+上下半球盖，无选中=根级），重名去重 Capsule (n)，创建后选中新节点；mesh=内置 capsule.smesh（离线生成同上）、材质=default.shader 运行期实例，语义同 plane 命令（2026-10-25 落地 v0.15，新增菜单项，Unity 3D Object → Capsule 口径） |
| `hierarchy.create.cylinder` | 圆柱体 | 场景树右键 → 3D 对象 | 选中节点下创建高 2×直径 1 圆柱（含端盖，端盖/侧面硬边重复顶点分离法线，无选中=根级），重名去重 Cylinder (n)，创建后选中新节点；mesh=内置 cylinder.smesh（离线生成同上）、材质=default.shader 运行期实例，语义同 plane 命令（2026-10-25 落地 v0.15，新增菜单项，Unity 3D Object → Cylinder 口径） |
| `hierarchy.create.light.directional` | 定向光 | 场景树右键 → 灯光 | 选中节点下创建方向光（无选中=根级），重名去重 Directional Light (n)，创建后选中新节点；节点位姿 Unity 新建默认（原点无旋转——朝局部 +Z）、灯参数取组件默认（intensity 1/color 白）；主光无标志（引擎自动裁决最强激活方向光，对齐 Unity 拍板 2026-10-31）；CreateLightMenuCommand 一类三命令（构造注入 id/baseName/组件子类——灯族子类化拍板 2026-10-31），序列化按子类走保存可恢复（2026-10-31 随三期批次②落地，替换占位注册） |
| `hierarchy.create.light.point` | 点光源 | 场景树右键 → 灯光 | 同上（PointLight 组件，节点名 Point Light）；着色消费归批次③——创建后 F3 行 8 可见在册灯数（2026-10-31 随三期批次②落地） |
| `hierarchy.create.light.spot` | 聚光灯 | 场景树右键 → 灯光 | 同上（SpotLight 组件，节点名 Spot Light，默认 range 10 / 内 30° 外 45°）；收集侧已支持进 lightList，着色消费归批次③（2026-10-31 随灯族子类化一并解锁） |
| `hierarchy.create.camera` | 摄像机 | 场景树右键 → 摄像机 | 选中节点下创建相机（无选中=根级），重名去重 Main Camera (n)，创建后选中新节点；Unity 口径=节点名 Main Camera、原点无旋转、参数全取 Camera 组件默认（fov 60°/near 0.1/far 1000/depth 0/SOLID_COLOR/全屏），main 标志不预设（引擎 main 消费未落地）；相机入用户 game scene——SCENE 模式编辑器视口不受影响（EditorScene 相机 SceneModel 双模式分流）、GAME 模式渲染用户相机，Camera 序列化已通保存可恢复（2026-10-11 落地，替换占位注册） |
| `hierarchy.create.environment` | 环境设置 | 场景树右键 → 环境设置 | 建名为 `Environment` 的节点 + `Environment` 组件（场景级天空盒等环境参数宿主）；**固定挂场景根级**（不随选中节点下走——环境是场景级设置，挂物体下无意义且会随父节点删除消失），创建后自动选中新节点；`@UniqueComponent(scene = true)` 全场景唯一——**菜单项恒可用**，已存在/场景未就绪时点击弹警告说明原因（不置灰）；v0.1 只装天空盒，四期环境类参数随批加字段（2026-11-08 落地） |
| `hierarchy.delete` | 删除 | 场景树右键 | 删除选中子树，无选中置灰，删后清空选中（2026-09-30 落地） |
| `hierarchy.copy` | 复制 | 场景树右键 | 占位置灰——序列化数据面已通（Engine_Scene_Design §9），剩剪贴板子树导出机制（导出须置根 parent=null），随命令落地 |
| `hierarchy.paste` | 粘贴 | 场景树右键 | 占位置灰——同上 |
| `hierarchy.rename` | 重命名 | 场景树右键 | 占位置灰——待树内编辑 UI（scene.nodeRenamed 定点通知已就绪） |
| `build.compile` | 编译 | 构建 | 实而先禁（2026-10-11 拍板）：引擎侧 `GradleProject.compile()`（gradle compileJava）已备，菜单入口恒置灰（enabled 覆写），启用即删覆写接通；execute 记 warn 仅防桥直触发 |
| `build.pack` | 打包 | 构建 | 弹 CEF 模态打包对话框（独立前端页 page/pack.html：构建形态/启动场景/构建模式/窗口元信息表单 + 构建日志区）；「开始打包」接 `/build/pack` 桥（BuildMethod 声明 CefBrowser 参数注入调用者浏览器，buildForm 分流：JAR→`GradleProject.jarBuild`（dumpRuntimeJars→build→jlink 裁剪 JRE 运行时→产物组装到 Builds/jar/），AOT→`GradleProject.aotBuild`（nativeCompile（GraalVM native-image，-PbuildMode 下发构建模式：debug→quickBuild/release→全优化）→产物组装到 Builds/aot/：exe + 外置 lib/*.dll + Game/ + Library/ + bat，AotGameMain 设 org.lwjgl.librarypath=./lib——DLL 不进 exe 防体积膨胀）；ConsoleOut 行经 JavaScriptBridge EDT hop 定向实时推送 build.out.*、前端流式回显自动滚底、响应到达即完成；桥线程池阻塞执行、失败走桥错误协议）；宿主=`window/cef/dialog/Dialog` 通用容器（EDT invokeLater 弹出）——启动场景下拉已接 /build/scenes 真数据；构建模式仅 AOT 形态显示并消费（JAR 形态隐藏——JVM 无优化差异）（v0.9 2026-10-11 落地；v0.10 2026-10-13 构建形态双链 + /build/pack 改名 + Builds/ 产物目录，已实测验收 2026-10-13：AOT/JAR/编辑器三端表现一致） |

顶栏菜单域按 Unity 组织（文件 / 编辑 / 资产 / 游戏对象 / 组件 / 工具 / 窗口 / 帮助），
首期只落有命令的域、空域不渲染——已落「文件」（v0.6，仅保存）、「工具」、
「构建」（v0.9）三域。注：「构建」非 Unity 菜单结构（Unity 的 Build Settings 在
File 菜单下），是用户指定的独立顶级域。
注：Unity 无「视图」顶级菜单，
Scene 视图类开关（如网格）在 Unity 属视图内 Gizmos 工具栏——我们暂以「工具」域承载，
待将来场景视图工具栏落地后再对位迁移（届时注意 §1.1 遮挡约束：工具栏浮进视口矩形
不能走 Web）。

场景树右键域首批命令已随场景树对接落地（2026-09-30）；v0.7（2026-10-11）结构按
用户清单重排为 Unity 顺序：**剪切/复制/粘贴—删除—空对象—3D 对象（Plane、
立方体、球、胶囊、圆柱已实现——原始体 2026-10-25 批次、三角锥占位）/灯光（定向光/点光源
2026-10-31 随三期批次②落地、SPOT 随批次③）/粒子（占位）/摄像机（v0.8 落地）**，
rename
撤出（随树内编辑 UI 后置）、未实现项占位置灰（§4；pasteAsChild/duplicate 暂不注册）。
project 右键域的
命令、域基类与前端选中上报，随 project 对接任务落地（域基类设计已就绪）。

## 9. 预留（明确不做）

- **快捷键全局触发——Web 层通道已落地（2026-10-02，Ctrl+S 验收驱动；同日拍板
  改为前端直接绑定）**：`shortcutService.ts` 前端静态绑定表（不走菜单结构拉取
  ——零竞态零解析；新命令快捷键加一行，命令本体/菜单显示仍归 Java 侧单源），
  window keydown（capture）匹配 → preventDefault（阻断 CEF 默认行为如保存网页
  对话框）→ `/command/execute` 直触发。文本输入目标与 e.repeat 不拦截；置灰
  命令触发为安全 no-op。**覆盖边界：焦点在 Web 面板内**；焦点在视口 Canvas 时
  键盘进引擎输入系统不经 Web——引擎侧全局接入（Input 边沿 → 命令桥）为开放项，
  随「编辑器输入统一」任务设计；
- 菜单/命令动态增删、命令权限过滤、最近文件列表；
- Swing LAF 深度定制；
- 「一动作一类」组织形式（命令量上来后再评估，注册协议不变）。

## 10. 落地清单

**Java（`cn.donting.javelin.editor.menu` + 桥）**

| 件 | 职责 |
|---|---|
| `MenuCommand` + 三域包（`menu.top` / `menu.hierarchy` / `menu.project`，域基类与域命令同包） | §3.1/§3.2 命令类形态契约；`menu` 根包 = 框架件 |
| `Command` / `CommandService` | §3.1 数据内核与注册执行（register(MenuCommand) 适配 + Command.of lambda 通道） |
| `EditorSelection`（`selection` 包） | §3.2 全局选中状态单源（写入方随场景树 / project 任务接线） |
| `MenuRegistry` | §4 声明式结构（含子菜单嵌套） |
| `UnimplementedMenuCommand`（menu.hierarchy） | §4 未实现命令占位注册（enabled 恒 false 置灰，功能落地时替换注册行） |
| `UnimplementedMenuCommand`（menu.project） | 同上，project 域占位（open/创建族/paste/rename/delete，v0.11） |
| `ProjectMenuCommand` 域命令三实做（menu.project） | v0.11：showInExplorer / copyPath / reimport |
| `EditorMenuSetup` | 启动期组装（`JavelinEditor.onInitialize` 调用）：注册命令类 + 搭菜单 |
| `MenuPopupFactory` | §5 JPopupMenu/JMenu 递归构建 + 分层状态求值 + 坐标换算 |
| `SvgIcon`（`editor.swing`）+ `resources/icon/` | §5.3 SVG 图标渲染（JSVG，Document 缓存）与图标资源 |
| `MenuMethod`（`jcef/method`）+ `MenuItemVo`（`domain/vo`） | §6 桥协议 |

**前端（engine-editor-web）**

| 件 | 职责 |
|---|---|
| `MenuBar.vue` 改造 | 拉取顶栏标签 + 点击转发 `/menu/open` |
| `DropdownMenu.vue` | ~~新建（v0.1 落地，v0.4 删除）~~ Web 右键渲染路径随统一 Swing 作废 |
| `shortcutService.ts` | §9 Web 层全局快捷键（前端静态绑定表直接触发 → `/command/execute`，2026-10-02） |

**验证**：跑编辑器 → 顶栏「工具」下拉出现两项且浮于视口之上 → DevTools 可打开（见
ping/pong 回声）→ 网格显示勾选切换生效（视口网格出现/消失）→ 层级面板右键出现
「创建 ▸」子菜单（浮于视口之上），点开执行创建空物体、树自动刷新。
→ 层级面板右键菜单呈 Unity 壳结构：复制/粘贴/重命名置灰不可点、快捷键右对齐显示
（Ctrl+C/Ctrl+V/F2），删除项随选中态置灰/恢复。

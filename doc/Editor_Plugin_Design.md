# 编辑器插件体系与 i18n 设计（v0.3）

> 状态：**首批落地，已验收**（2026-10-20 用户实测通过）——插件静态骨架（契约/装载/账本/
> 隔离）+ editor.core 迁移（编辑器即插件通电）+ i18n 基建（注册中心/scope/门面）+
> 菜单 i18n 首批（label 全链 I18nKey）。
> v0.2（2026-10-27）：**插值改自实现 `{}` 顺位替换（弃 MessageFormat，拍板 2026-10-27
> ——{0} 编辑期不直观 + 单引号转义坑消解）**；新功能文案随批接 i18n（§7.2-0，不再攒批进
> 第二批）。
> v0.3（2026-10-30）：**i18n 全量化批次**——§7.2 后置批次 1~4 一次清账：编辑模块
> ~300 条非日志字面量全迁（异常/弹窗/面板标题/检查器标签/打包日志行），双语言包
> 354 键对齐；异常面收 `I18nKey`（`LangException` 新基类 + TipException/ShaderParseException
> 构造器收键，§4.5-1 落地）；`@InsField/@InsHeader/InsEnumLabel` 的 label 改「语言包
> 局部键」、反射层三解析点（componentLabel/displayName/enumLabel）经 EditorLang 解析
> （§7.1 组件显示名口径拍板落地）；源码机检测试 `CjkStringLiteralCheckTest` 落红线
> （§4.5-3）。引导期文案（插件装载链/启动器两窗/i18n 基建自举）按档内口径留中文白名单。
> 本档为插件体系与 i18n 的**总纲**：契约语义、装载管线、错误分级、i18n 解析链、
> 拍板依据与后置路线。javadoc 只留钩子（AGENTS 红线 14），论证全归本档。

---

## 1. 定位与动因

### 1.1 为什么做插件体系

三注册表先例（MenuRegistry / PanelRegistry / FileTypeRegistry）已验证「集中显式注册 +
fail-fast」模式，但装配入口散在宿主各处（MainFrame.create / JavelinEditor.onInitialize），
没有统一的功能模块接入形态。插件体系把「注册贡献」升级为**一等模块单元**：

- **编辑器即插件**：editor.core 与第三方走同一条 ServiceLoader 装载链，宿主无特权分支
  （IntelliJ bundled plugin / VS Code 内置扩展同模型）——插件 API 从第一天就被它最重的
  消费者（编辑器本体）全量验证，接口缺什么当天暴露；
- **插件生态先决条件**：去 Web 化的核心动因（Editor_Swing_Design §拍板）在插件体系
  兑现——插件与宿主同一对象图直接组合，贡献面板/命令/文件类型/语言包。

### 1.2 为什么 i18n 与插件一起设计

i18n 的核心类形状被插件模型决定：**多语言包必须按插件隔离**（namespace 路由），单例
全局表在插件体系下是错误起点。反向地，i18n 也给插件体系出了硬约束（语言包注册时序、
元数据文案），两者合并设计一次定型。（2026-10-20 用户拍板：合并立档，不拆两份。）

### 1.3 i18n 动因与盘点

编辑器+core 用户可见中文字面量约 550 条（2026-10-20 盘点：editor ~382 含异常消息
~116，core ~163 含 InsField/InsHeader 标签）。**宜早不宜迟**：控制台面板、视口工具栏
未实装，先立机制让新面板直接按 key 产出。

---

## 2. 插件契约

### 2.1 Plugin 接口（`editor.plugin.Plugin`）

```java
public interface Plugin {
    String namespace();               // 点分小写，如 "editor.core"
    I18nKey name();                   // 显示名（i18n）
    default I18nKey description() { return null; }
    String version();                 // 语义化版本 "主.次.修订"（可选预发布后缀）
    default String author() { return ""; }
    default String link() { return ""; }
    void setup(PluginContext context); // 唯一生命周期钩子
}
```

拍板记录：

- **`namespace()` 而非 `id()`**（2026-10-20 用户）：该值的实际职能是**一切注册贡献的
  隔离前缀**（i18n key、命令 id、面板 id），叫 namespace 在调用点自解释，与满地的
  命令 id/面板 id 不混淆；点分小写正则宿主校验，与 i18n key 拼接零归一化。
- **name/description 返回 I18nKey、author/version/link 裸 String**：作者名/版本号/URL
  是 locale 无关的，翻译无意义；名称/描述是插件管理界面给用户看的多语言文本。
  default 方法减样板（小插件只写 namespace/name/version/setup 四项）。
- **语言包注册制**（2026-10-29 拍板，原「约定路径 lang/zh_cn.properties」规范废除）：
  插件 setup 期经 `PluginContext.registerLanguagePack(域, 语言, 文件路径)` 显式注册
  ——文件布局插件自定、一个文件对应一个域，集中式语言包指向其他插件域即可翻译
  对方（同键后注册覆盖先注册，装载序语义）；支持语言另经 `registerLanguage(文件名,
  显示名)` 注册（语言设置菜单选项源）。`lang/` 目录 manifest 审计语义随之作废。
- **version 语义化版本校验 fail-fast**（装载期）：将来兼容性检查
  （「本编辑器要求插件 ≥ x.y」）的数据源，脏版本号早暴露。

### 2.2 PluginContext（注册门面）

`setup(PluginContext)` 唯一入参，插件对宿主扩展点的注册经此收口：

| 方法 | 转发目标 | fail-fast 语义 |
|------|----------|----------------|
| `registerPanel(PanelDescriptor)` | PanelRegistry | id 重复抛 |
| `registerCommand(MenuCommand)` | CommandService | id 重复抛 |
| `registerFileType(FileType)` | FileTypeRegistry | 扩展名重复抛 |
| `registerInspector(InspectorProvider)` | InspectorRegistry | 键重复抛 |
| `registerLanguage(fileName, displayName)` | I18n 语言注册表 | 参数空抛；fileName 重复忽略（先注册者优先，editor.core 恒先装） |
| `registerLanguagePack(namespace, language, path)` | I18n.registerPack | 参数空抛/文件缺失抛（setup 失败 = 弹框 + 确认后关闭编辑器，§3.4）；同键后注册覆盖 |
| `i18n()` | — | 返回命名空间绑定的 I18nScope（§4.2） |

持有 namespace 作宿主归属记录锚点（将来源码 i18n 报告分桶、插件管理界面的数据源）。

**语言注册制 + 语言包注册制**（2026-10-29 拍板，否决 Lang 枚举）：支持语言由插件
setup 期 `registerLanguage` 注册（I18n 注册表 fileName→显示名，注册序），语言包按
`registerLanguagePack(域, 语言, 路径)` 显式注册（`lang/{lang}.properties` 固定路径
规范废除——文件布局插件自定、一文件一域）；editor.core 注册 en_US（默认语言）/
zh_CN 语言 + 两域语言包，新语言包 = 对应插件注册即生效，集中式语言包按域逐文件
注册即可翻译其他插件。语言设置菜单（file.preferences，Editor_Menu_Design v0.18）
选项源即语言注册表。

**纪律口径**：editor.core 的 setup() 经 ctx 注册（三个 Setup 类签名已改 `setup(PluginContext)`，
内部全部 `context.registerXxx`），无特权直调静态门面通道——同模块下这靠约定不靠编译器
（editor.core 与 PanelRegistry 同模块，绕过 ctx 编译得过），CR 把关；注册贡献按 namespace
前缀化后（§7 开放项）此纪律升级为结构性强制。

### 2.3 图标资源约定（2026-10-23 用户拍板）

- **插件供图标走约定路径 `/icon/`、格式 SVG**——与语言包 `lang/` 约定同构：目录即
  manifest，不占接口方法、零注册零声明。插件 jar 根下放 `/icon/<name>.svg`，消费方
  `SvgIcon.of("<name>", size)` 零感知命中（全库图标加载单点收口在 SvgIcon.loadDocument）。
- **解析链两级**（原实现只查宿主 classloader，插件图标查不到——本批修复）：
  宿主 classpath 先查（内置图标恒优先），未命中按插件装载序依次查各 classloader
  （classpath 插件先、jar 名序、FAILED 跳过）——同名图标"先装载者优先"，与
  namespace 冲突裁决同口径。实现 = `PluginManager.findResource(path)` + SvgIcon 兜底链。
- **装载时序安全**：图标请求点（菜单/面板/检查器）均在 `PluginManager.loadAll` 之后的
  编辑器主窗构建期；SvgIcon 缓存命中即缓存、miss 不记 null（后装载插件可再命中）。

---

## 3. 发现与装载

### 3.1 发现机制：ServiceLoader

JDK 标准零依赖；`META-INF/services/com.javelin.editor.plugin.Plugin` 声明入口类——
**看得见、无魔法，与项目「不做注解扫描、集中显式」口径同源**，services 文件本身就是
manifest（不发明 XML/JSON 格式）。无参构造反射实例化（与菜单域 ClassUtil 扫描同口径，
无依赖注入——插件要的一切从 `setup(PluginContext)` 进来）。

**editor.core 走同一条链**：javelin-editor resources 里同样放 services 声明，宿主代码
无 `if (editor.core)` 分支，只有「主 classloader 先扫、插件目录后扫」的顺序保证。

### 3.2 第三方装载管线（PluginInstance.load）

```
File jar → PluginClassLoader(jar, parent) → ServiceLoader.load(Plugin.class, cl)
         → 实例化 → 元数据校验（namespace 正则/version semver/name 非空）
```

- **全程不抛**（用户拍板 2026-10-20）：任一环失败转 FAILED 实例返回，原因在
  `error()`——「第三方 warn 跳过、编辑器照常运行」成为结构性保证；
- **一个 jar 一个插件**：services 声明零个/多于一个均判 FAILED（fail-fast on ambiguity）。
  多声明检测利用 ServiceLoader 惰性：`hasNext()` 只读声明不实例化，多余入口类不会被
  白白构造；
- **失败路径关闭 classloader**：URLClassLoader 不 close 在 Windows 下锁死 jar 文件
  （用户想删坏插件都删不掉）——load 内失败即 `closeQuietly`。

editor.core 无 File 路径：宿主自行 ServiceLoader 主 classloader 发现后经
`PluginInstance.of(source, plugin, cl)` 包装，**元数据校验失败直接抛**（本体 fail-fast）。

### 3.3 PluginClassLoader（parent-first）

继承 URLClassLoader（每 jar 一实例），**委托序沿用默认 parent-first**：宿主已加载的
依赖版本优先，插件不得以冲突版本覆盖宿主库——零代码实现既定决策；代价 = 插件依赖版本
须与宿主兼容，child-first 自由度等真有需求再开。v1 无 unload：插件生命周期与进程同寿。

### 3.4 PluginManager（宿主管线，静态门面）

```
loadAll(pluginsDir)（进程一次，重复调抛）：
  ① classpath 段（editor.core 路径）：ServiceLoader 发现 → of() 校验
     → setup（语言包随 setup registerPack）→ 账本 + info 日志
  ② jar 段：目录下 *.jar 按文件名排序（装载序确定）→ PluginInstance.load
     → namespace 查重 → setup → 账本 + info 日志
  ——任何环失败（本体/第三方、发现/装载/冲突/setup）一律弹错误框显示信息，
  用户确认后关闭编辑器 System.exit（错误显性化拍板 2026-10-29，原「第三方
  warn 跳过」口径作废——静默降级掩盖真问题）
```

拍板与语义细节：

- **任何插件报错 = 弹框 + 确认后关闭编辑器**（2026-10-29 拍板，原「第三方 warn
  跳过/撞名跳过」口径作废）：红线 15 错误显性化推到装载期——静默跳过坏插件会
  让「插件为什么没生效」变成排障难题；弹框（EDT invokeAndWait 阻塞 Engine Main
  至确认）显示 **namespace + 包路径 + 错误信息**（包路径供排障定位，jar 插件显示
  完整路径、classpath 插件标「classpath（本体）」），确认后 System.exit 关闭编辑器。
  文案硬编码中文——装载期语言包未注册（随 setup），i18n 不可用。
- **classpath 内部撞名（本体级）恒 fail-fast**：classpath 恒先装载，editor.core 的
  命名空间结构性不可能被第三方抢占（撞了即报错弹框）。
- **语言包注册随 setup**（2026-10-29 改，原「setup 之前 registerBundle」随固定路径
  规范废除）：registerPack 显式域+路径，查表逐键回退使注册时序自由——setup 失败
  （含语言包路径缺失）即弹框关编辑器。

### 3.5 装载时序与线程（2026-10-20 用户拍板）

```
ProjectFrame 选项目 → ProjectLoadingFrame 后台线程 "Engine Main"：
    GradleProject                          ← 项目根已知，Plugins/ 可寻址
    PluginManager.loadAll(项目根/Plugins)   ← ★ 先于一切资产初始化
    MetaManager → 资产管线 → 内置资源释放
    → editorApp.start() → MainFrame.create（菜单条/面板装配消费注册表）
```

- **Plugins/ 目录 = 项目级**（项目根/Plugins，随项目走，Unity 口径）——与接入点绑定的
  拍板：项目级插件需项目打开后装载，接入点必在加载窗线程；编辑器安装目录级为远期
  备选（届时接入点改 main() 首段）；
- **先于资产管线**：现状 FileType/菜单/面板在 MainFrame.create 才消费，放哪都行；但
  `AssetImporter` SPI 是插件体系将来很可能收编的扩展点（与 FileType 同为按扩展名贡献），
  一旦插件能贡献导入器就必须在 AssetImportPipeline 之前装载——现在放最前将来不用挪；
- **线程口径**：loadAll 在后台线程（loading 窗遮盖 jar 扫描/classloader/反射重活）；
  setup 只写静态注册表不碰 Swing（PanelDescriptor factory 是惰性 Supplier，组件实例化
  仍在 EDT 由 DockRoot 做），后台线程安全；
- **单项目单进程前提**：当前关主窗即退出，loadAll「进程一次」成立；多项目会话化后
  插件变会话级装载（unload 或进程重启），v2+ 模型变更。

---

## 4. i18n 体系

### 4.1 I18nKey（值对象）

三字段：`namespace`（路由键）+ `key`（局部键，语言包文件内键名）+ `args`（{} 顺位插值）。
`fullKey()` = `namespace + "." + key`（显示/采集/报告口径）；`text()` 显示点解析入口
（缺失显示 `!fullKey!` 兜底）。

**namespace 必须构造期烙进 I18nKey**（2026-10-20 用户问出的真缺口）：插件元数据场景
宿主确实知道来源，但 I18nKey 的核心承诺是**跨上下文传递**——`TipException(I18nKey)`
从深层插件代码抛到状态栏，中途没人知道它来自哪个插件；跨线程/跨面板同理。解析期补
namespace 不可行，构造期自持走到哪都能解析。语言包文件内仍是局部键（文件隔离），
全域名是派生。

### 4.2 I18nScope + Lang 门面（零 namespace 书写）

「每个调用点写 namespace 很蠢」的解法（2026-10-20 用户拍板，IntelliJ Bundle 同款）：

```java
// 宿主从 PluginContext 造 scope（namespace 来自已校验的插件身份）
// 插件侧静态门面，setup 期注入一次：
public final class EditorLang {
    static void init(I18nScope scope);              // EditorCorePlugin.setup 首行
    public static I18nKey key(String k, Object... a);
    public static String text(String k, Object... a);
}

// 编辑器任意深处（命令/面板/深层异常）：
throw new TipException(EditorLang.key("error.saveFailed", path));
new JLabel(EditorLang.text("panel.hierarchy.title"));
```

namespace 在整个插件只出现于 `namespace()` 一处。第三方插件照抄：自己的 Lang 门面 +
自己的 `lang/en_US.properties`。**metadata 特例**（name/description 在 setup 前被调，
scope 尚未注入）：`I18nKey.of(namespace(), "plugin.name")` 自引用，每插件两处可接受。

否决的备选：**注册边界自动加 ns**（异常 key 不过注册面，上下文丢失）；**包名反推
namespace**（魔法推导与「namespace 显式声明」拍板冲突，挪包即静默漂移）。

### 4.3 I18n 注册中心（静态门面）

- **装载**：`registerPack(namespace, language, classLoader, path)`——插件 setup 期
  按「域 + 语言 + 文件路径」显式注册（2026-10-29 拍板，`registerBundle` 固定路径
  `lang/{lang}.properties` 规范废除：文件布局插件自定、一文件一域，集中式语言包
  按域逐文件注册即可翻译其他插件，同键后注册覆盖先注册=装载序语义）。路径缺失/
  损坏抛——分级在调用方：editor.core（classpath 段）fail-fast，第三方 setup 失败
  warn 跳过（§4.6）。查表存储 = 域 → 语言 →（局部键 → 文案）三级表。
  **不走 ResourceBundle**：其 `_zh_CN` 大小写命名与父子链回退和注册制不匹配，且
  我们的回退语义本就是定制的；自实现 Properties 加载（**必须 `load(Reader)` 显式
  UTF-8**——`load(InputStream)` 至今按 spec 是 ISO-8859-1，中文必乱码，JDK 9 的
  UTF-8 改动只覆盖 PropertyResourceBundle）。
- **语言切换**：`setLanguage` 启动期一次（二次调用抛——不随注册冻结，重启生效
  口径）；默认 `en_US`（2026-10-29 拍板，原 zh_cn）。editor.settings 语言项已接线
  （JavelinEditor.main 启动解析）。
- **解析**：`get(namespace, key, args)`——**当前语言 → 该域默认语言逐键回退**（域基线
  = 该域第一个注册的语言包，2026-10-29 拍板——英文不特殊，中文作者的插件基线可为
  zh_CN；查表时解析，语言包注册时序自由）；命中走 **{} 顺位插值**（自实现顺位替换，
  **拍板 2026-10-27 弃 MessageFormat**：{0} 编辑期不直观，顺带消解其单引号转义坑；
  参数多于占位符丢弃，占位符多于参数余下 `{}` 原样保留显性可见）；缺失返回
  `!fullKey!` + **按 key 去重 warn**（状态栏/菜单每次 repaint 都走解析，不去重一次
  刷新刷几十条 warn 淹掉信号）——**全程不抛**（2026-10-20 用户拍板，IntelliJ/Rails
  同款）：缺 key 是开发者 bug，key 原样显示在界面上恰是最快定位方式，不该让用户付出
  崩溃代价。
- **采集**：`missKeys()/usedKeys()` 快照——miss 给「缺了补哪」，used 反向差集给
  「死 key」（翻译存在但全程未查——key 改名/功能删除的残留），报告导出命令
  （Tools 域 → `.javelin/i18n-report.txt`，按 namespace 分桶）随后续批次。

### 4.4 菜单 i18n 首批（2026-10-20 落地）

`MenuCommand.label()` → `I18nKey`，全链类型收口：

| 层 | 改动 |
|----|------|
| 契约 | `MenuCommand.label()` 返回 I18nKey；`Command`/`MenuDef`/`MenuEntry` label 字段、`MenuRegistry.menu`/`MenuBuilder.submenu` 签名同步 |
| 约定 | **label key = 命令 id**（`file.save=保存`）：三个域基类提供默认 `label() = EditorLang.key(id())`——12 个实命令类 label 覆写全删、两个 UnimplementedMenuCommand 的 label 字段/构造参数整体消失；id 改名即 key 改名，旧 key 自动进死 key 报告 |
| 渲染 | `MenuBarFactory`（顶栏 JMenu）、`MenuPopupFactory`（命令项 + 子菜单）`.text()` 解析；子菜单日志路径 `.fullKey()` |
| 语言包 | `lang/zh_cn.properties` 36 条（插件元数据 2 + 顶栏域 3 + 顶栏命令 4 + hierarchy 域 15 + project 域 12；2026-10-20 补 create.material 注册+条目，ac8a514） |

刻意不做：**ctx 域标签不进语言包**（右键域不渲染标签，进了反而被死 key 报告标红）；
**日志消息不迁移**（诊断面，Unity 也不 i18n 日志——异常/弹窗消息曾列第二批，
2026-10-30 全量化批次已迁，见 §4.5）。

### 4.5 三层强制（全量目标形态）

1. **出口签名**（✅ 2026-10-30 落地）：异常面收 I18nKey——`LangException` 新基类
   （受检，消息构造期 `key.text()` 解析）+ `TipException`/`ShaderParseException` 构造器
   收键（需要国际化的异常面以收键显式标出）；弹窗/状态栏/导入错误消息经
   `EditorLang.text(...)` 于调用点解析（JDK 异常构造器收不了键，保留调点解析形态）；
2. **注解层启动校验**：InsField/InsHeader 的 key 静态可枚举，装载期聚合 error log
   （不抛，运行时照旧 `!key!` 兜底）——⏳ 后置（label 已 key 化，见 §7.1）；
3. **源码机检**（✅ 2026-10-30 落地）：`CjkStringLiteralCheckTest` 扫
   javelin-editor/src/main/java 全量——词法状态机剥离注释、跨行携带字符串/text block
   态，字符串字面量含 CJK 即测试红。白名单（引导期 + 数据面，扫描器内注明）：
   插件装载链四件（PluginManager/PluginInstance/PluginClassLoader/PluginContext——
   setup 前可能被调，EditorLang 未注入）、启动器两窗（ProjectFrame/ProjectLoadingFrame
   ——先于语言包注册显示）、i18n 基建四件（自举异常不可回查 i18n）、
   EditorCorePlugin（`registerLanguage("zh_CN","中文")` 的语言自书显示名=语言数据）、
   开发者面工具类三件（FileUtil/ClassUtil/StringUtil）。log 调用行豁免（诊断面拍板）。

### 4.6 第三方插件兼容矩阵（2026-10-20 拍，2026-10-29 随注册制修订）

第三方插件的 i18n 是**约定而非门槛**——文本文件缺失不拒载插件本体，只降级文案
能力（2026-10-29 修订：语言包随 setup 注册、缺包路径 setup 抛 = 整插件装载失败
走弹框，不再有「无包降级装载」档；有包缺键仍逐键回退）：

| 场景 | 行为 | 用户所见 |
|------|------|----------|
| 带全量语言包（基线 + 当前语言） | 正常注册 | 各语言正常显示 |
| 只有基线包、缺当前语言包 | 当前语言查表 miss 逐键回退域基线 | 显示基线语言文案（键不黑） |
| **完全无语言包 / 路径缺失** | registerPack 抛 = setup 失败 = **弹框 + 确认后关闭编辑器**（§3.4 错误显性化——想用 i18n 就把包带上） | 错误框指认插件与路径 |
| 有包但缺个别 key | `!ns.key!` 显示 + 去重 warn | 界面直接指认哪个插件缺翻 |
| 有包但翻译不全 | 缺键逐键回退**该域基线**（第一个注册的语言包） | 基线语言补位（键不黑） |

**插件作者的接入姿势**（零耦合）：自带 Lang 静态门面（setup 期 `init(context.i18n())`
一次）+ `registerLanguagePack(namespace(), …)` 注册自己的语言包；渲染面（核心菜单里的
插件命令标签等）经
`label().text()` 解析，注册表不关心 key 属于哪个命名空间——任何 namespace 的键在任何
UI 位置都可渲染。**隔离保证**：namespace 路由分桶，miss/used 采集按 namespace 分桶，
每个作者拿到
自己的缺翻工单，不用翻别人的报告。（修订：集中式语言包经 `registerLanguagePack`
指向其他插件域即可翻译对方，同键后注册覆盖先注册——显式声明域的覆盖行为自负，
这是 2026-10-29 语言包注册制拍板的组成部分。）

### 4.7 插件国际化示例（完整链路）

jar 内布局（语言包注册制：文件布局自定，示例用 `lang/` 目录；一文件一域、
键 = 目标域局部键）：

```
myplugin.jar
 ├─ com/example/myplugin/（MyPlugin 入口 / MyLang 门面 / 功能类）
 ├─ lang/en_US.properties          ← 自己域的英文（注册目标域 = 自己 namespace）
 ├─ lang/zh_CN.properties          ← 自己域的中文（可选）
 └─ META-INF/services/com.javelin.editor.plugin.Plugin → 入口类
```

语言包（key 局部名 + {} 顺位插值）：

```properties
# lang/en_US.properties
plugin.name=我的工具插件
toolbar.title=工具
error.loadFailed=资源 {} 加载失败
```

插件 setup 注册（域 + 语言 + 路径显式声明）：

```java
public void setup(PluginContext ctx) {
    MyLang.init(ctx.i18n());
    ctx.registerLanguage("en_US", "English");          // 语言菜单选项（重复忽略）
    ctx.registerLanguage("zh_CN", "中文");
    ctx.registerLanguagePack(namespace(), "en_US", "lang/en_US.properties");
    ctx.registerLanguagePack(namespace(), "zh_CN", "lang/zh_CN.properties");
    // 集中式语言包（可选）：指向其他插件域即可翻译对方，一文件一域
    // ctx.registerLanguagePack("editor.core", "zh_CN", "lang/editor.core.zh_CN.properties");
}
```

插件侧（namespace 全程一次；MyPlugin.setup 的语言注册与上文示例同款，此处从略）：

```java
public final class MyLang {                       // 静态门面：init 一次，用点零 namespace
    private static volatile I18nScope scope;
    static void init(I18nScope s) { scope = s; }
    public static I18nKey key(String k, Object... a)  { return scope.key(k, a); }
    public static String text(String k, Object... a) { return scope.text(k, a); }
}

public final class MyPlugin implements Plugin {
    public String namespace()    { return "myplugin"; }
    public I18nKey name()        { return I18nKey.of(namespace(), "plugin.name"); }
    public String version()      { return "1.0.0"; }
    public void setup(PluginContext ctx) {
        MyLang.init(ctx.i18n());                 // namespace 唯一交接点
        ctx.registerPanel(PanelDescriptor.of("myplugin.toolbar", MyLang.text("toolbar.title"))
                .factory(ToolbarPanel::new).build());
        ctx.registerCommand(new RefreshCommand());
    }
}

final class RefreshCommand implements MenuCommand {
    public String id()     { return "myplugin.refresh"; }
    public I18nKey label() { return MyLang.key("toolbar.refresh"); }
    public void execute()  { /* ... */ }
}
```

运行时链路（宿主自动）：loadAll 扫 jar → PluginInstance.load（classloader/ServiceLoader/
元数据校验）→ registerBundle 读 lang/ 合并单表 → setup（scope 注入 + 注册）→ 菜单
打开时 `label().text()` 路由到 myplugin 表。

语义细则：面板标题当前 setup 期 `text()` 求值（语言启动期锁定，注册期解析等价；面板
标题 key 化随后续批次）；异常消息 `MyLang.key("error.loadFailed", path)` 通道
**已开通**（2026-10-30，LangException 基类 + TipException/ShaderParseException 收
I18nKey，§4.5-1）；切语言 = 补语言文件 + 启动期 `I18n.setLanguage`
（settings 接线随后续批次）重启生效；`!myplugin.xxx!` = 该插件语言包漏键（日志同名
warn 指认 + 报告分桶）。

---

## 5. editor.core 迁移实况

### 5.1 懒取拍板（2026-10-20 用户：「需要引擎实例的地方都可以懒取」）

**注册期（插件装载，早于建窗）与求值期（execute/checked/factory）分离**，引擎依赖
全部推迟到求值时经单例懒取（`JavelinEditor.getInstance()` / `MainFrame.getInstance()`
本就是项目既有先例）。这解开了迁移的死结——插件装载早于引擎装配，但注册内容不依赖
引擎实例：

| 依赖点 | 迁移前 | 迁移后 |
|--------|--------|--------|
| 网格命令（stateManager/gridState） | `EditorMenuSetup.setup(stateManager, gridState)` 在 onInitialize 构造捕获 | `GridMenuCommand` 无参，`checked()/execute()` 懒取 `JavelinEditor.getStateManager()/getGridState()`（gridState 字段化；场景未就绪 null 安全 no-op 语义不变） |
| 视口面板（canvas） | `EditorPanelSetup.setup(canvas)` 在 MainFrame.create 传入捕获 | factory 改 `() -> new ViewportTabs(MainFrame.getInstance().getCanvas())`——factory 求值发生在 DockRoot 装配（画布已建），时序安全 |
| 文件类型 | `FileTypeSetup.setup()` 在 MainFrame.create | 签名改 `setup(PluginContext)`，无引擎依赖 |

**摘除的旧直调点**：`JavelinEditor.onInitialize` 的 EditorMenuSetup 调用（installMenuBar
保留，菜单注册已早于建窗）；`MainFrame.create` 的两个 Setup 调用。装配链从此单一：
PluginManager.loadAll → EditorCorePlugin.setup → 三个 Setup 经 ctx。

### 5.2 editor.core 不单独提模块（2026-10-20 用户问，拍板不拆）

插件隔离边界在**运行时**（classloader + namespace）不在构建期；editor.core 按设计走
主 classloader，Gradle 模块拆分零隔离收益。拆出去反而伤 API 面：editor.core 与宿主基建
同模块可直用内部能力，拆分则被迫公开大量只有本体碰的类——第三方 API 面变大，与
「小而稳 SDK」反向。边界还在动（面板/工具栏未收口），过早模块化冻结错误切线。
**现在做包纪律**（plugin/i18n 独立干净包）；**将来值得提取的是插件 API/SDK 模块**
（Plugin/PluginContext/I18nKey/I18nScope/MenuCommand/PanelDescriptor/FileType 接口面，
第三方编译用瘦 jar），时机 = 第三方装载器真正落地批。

---

## 6. 坑点存档

1. **`Properties.load(InputStream)` 是 ISO-8859-1**（至今如此，JDK 9 UTF-8 改动只覆盖
   PropertyResourceBundle）——中文 properties 必须 `load(Reader)` 显式 UTF-8。
2. **Windows 下 URLClassLoader 锁 jar**：不 close 的 classloader 让用户删不掉插件
   jar——装载失败路径必须 close；setup 失败路径**不能** close（半注册 factory 引用
   插件类，close 后 NoClassDefFound）。
3. **ServiceLoader 迭代抛 ServiceConfigurationError**（Error 非 Exception）——第三方
   装载的 catch 必须含 Error 分支（`ServiceConfigurationError | RuntimeException`）。
4. ~~**MessageFormat 单引号是转义字符**：文案含 `'` 需双写 `''` 才输出单引号。~~
   **坑已消解（2026-10-27）**：插值改自实现 `{}` 顺位替换后不再有单引号转义语义。
5. **菜单/面板 repaint 期反复解析**：缺失 warn 必须按 key 去重，否则刷屏。

---

## 7. 开放项与后置路线

### 7.1 待拍板（挂账）

| 项 | 现状 | 影响 |
|----|------|------|
| 命令 id 前缀化 | 第三方命令 id 若撞核心域（如 hierarchy.cut）fail-fast 拒绝 | 第三方进公共域的规则 + 菜单结构注入位（Unity 固定注入位口径） |
| FileType 扩展名冲突策略 | 现状 fail-fast（扩展名无法命名空间化） | 第三方接管 .scene 该拒（推荐维持 fail-fast）还是先注册者胜 |
| 默认语言 | ✅ **已拍板（2026-10-29）：默认 en_US**——编辑器启动初始语言（`I18n.DEFAULT_LANGUAGE`）；**语言包注册制后缺键回退基线 = 各域第一个注册的语言包（域基线，英文不特殊）**——editor.core 域基线 = 先注册的 en_US 文件（新建 en_US.properties 全量 50 键英文翻译），第三方插件（如中文作者）先注册 zh_CN 即基线中文；「必备默认文件」概念作废 | 语言设置菜单批的设计前提（首启系统语言 → 不支持逐键回退各域基线兜底成立） |
| 组件显示名口径 | ✅ **已拍板（2026-10-30 用户）：key 化进语言包**——`@InsHeader/@InsField/InsEnumLabel` 的 label 值改「editor.core 语言包局部键」（`ins.transform` / `ins.camera.fov` / `ins.enum.clearFlags.solidColor` 一族），InspectorReflector 三解析点（componentLabel/displayName/enumLabel）经 EditorLang 解析——zh_CN 中文名、en_US 英文名双语齐备 | 后续新增组件/字段：label 写键 + 双语言包补条目；空串兜底语义不变（兜底字段原名/类简单名） |

### 7.2 后置批次

0. ~~**新功能文案随批接 i18n**（2026-10-27 落地）~~——已随全量化批次收口；
1. ~~**异常/弹窗消息 i18n**~~ **✅ 已落地（2026-10-30）**：异常面收 `I18nKey`
   （LangException 基类 + TipException/ShaderParseException 收键）；tinyfd/JOptionPane
   弹窗与状态栏文案全迁（调用点 `EditorLang.text(...)` 解析）；
2. ~~**面板/窗口标题 i18n**~~ **✅ 已落地（2026-10-30）**：PanelDescriptor.title（层级/
   项目/控制台/视口/检查器）+ PackDialog/PreferencesDialog 等窗口文案全迁；
3. ~~**Inspector 注解 label key 化**~~ **✅ 已落地（2026-10-30，§7.1 拍板）**——
   装载期启动校验（§4.5-2）仍后置；
4. ~~**源码机检**~~ **✅ 已落地（2026-10-30，§4.5-3）**——**i18n 报告导出命令**
   （Tools 域，miss/used 分桶工单）仍后置；
5. **插件管理界面（v2）**：列表（name/description/version/author）、启用禁用
   （跳过 ServiceLoader 实例化即免 unload）、i18n 报告按插件分桶展示——
   PluginInstance 账本已为此备好数据源；
6. **AssetImporter SPI 收编 PluginContext**：装载时序已就位（先于资产管线）；
7. **SDK 模块提取**（§5.2）。

### 7.3 远期

引擎自绘 UI（Godot/Unity 形态，Editor_Swing_Design §8.4）：PanelRegistry/命令域/
事件总线/插件契约届时整体平移为引擎内实现，Swing 是过渡宿主——本档全部抽象按可平移
设计。

---

## 8. 验收状态

**✅ 已验收（2026-10-20 用户实测通过，无问题）**——验收清单（留档）：

1. 编辑器启动行为与迁移前完全一致：菜单条五域、右键域命令名逐字一致
   （`!editor.core.xxx!` = properties 漏条目，日志同名 warn 指认）；
2. 启动日志含 `插件已装载: editor.core v0.1.0（classpath）`；registerBundle 无异常
   （`lang/zh_cn.properties` 进 resources）；
3. 网格开关勾选/切换正常（懒取路径）；场景未就绪时置灰 no-op（原语义）；
4. 右键菜单（层级/项目域）结构、置灰、快捷键显示不变；
5. 无 Plugins 目录时静默跳过（正常态）。

涉及文件（新增）：`editor.plugin` 包八件（Plugin/PluginContext/PluginState/PluginInstance/
PluginClassLoader/PluginManager/EditorCorePlugin/EditorLang）、`editor.i18n` 包三件
（I18nKey/I18nScope/I18n）、`META-INF/services/...Plugin`、`lang/zh_cn.properties`；
改动：菜单包全链（MenuCommand/Command/MenuRegistry/MenuDef/MenuBuilder/MenuBarFactory/
MenuPopupFactory/三域基类/全部命令类）、三 Setup、JavelinEditor/MainFrame/
ProjectLoadingFrame。

## 9. i18n 全量化批次（2026-10-30 落地，2026-10-31 用户实测验收销账）

**范围**：编辑模块全部非日志用户可见字面量——异常消息（导入器/解析器/检查器回写/
序列化/工具类）、弹窗与状态栏（打包/场景打开/创建命令/资产拖放）、面板与窗口文案
（Dock 标签/占位/视口/工具栏/层级/项目/状态栏）、检查器全件（材质/纹理/着色器/节点）、
打包日志行（GradleProject out.*）、面板注册与 Dock 骨架校验异常。**日志调用（log.*/
LOGGER.*）留中文**（诊断面，档内既有口径）。

**语言包**：zh_CN / en_US 各 354 键、逐键对齐（域内约定：菜单键 = 命令 id 不变；
新域 inspector.*/ins.*/import.*/shader.parse.*/material.*/texture.*/gradle.*/pack.*/
common.* 等）。

**代码形态**：
- 抛点用 `EditorLang.key(...)`（异常面，键自持、构造期解析）或 `EditorLang.text(...)`
  （JDK 异常/日志形接口/UI 文案，调用点解析）；`LangException` 为受检基类，
  `InspectorReflector.apply` 链声明 `throws TipException`；
- `@InsField/@InsHeader/InsEnumLabel` 的 label=语言包局部键（core 侧存中性键、
  editor 侧解析——core 不依赖 i18n）；
- `CreateShaderMenuCommand` 模板 text block 与注入 GLSL 注释改英文（生成物/language
  neutral，不经语言包）。

**测试**：`EditorLangTestBase`（装 zh_CN 包 + init 门面，幂等）——ShaderParserTest
继承之（断言中文文案不变量）；`CjkStringLiteralCheckTest`（§4.5-3 机检）。

**验收清单（用户实测建议）**：① 编辑器以 zh_CN 启动，菜单/层级/项目/检查器（Transform/
Camera/MeshRenderer 组件头与字段名）/视口标签/工具栏 tooltip 全中文显示；② 以 en_US
启动（首选项切语言重启）同面全英文；③ 触发弹框路径：打包（PackDialog 表单/状态/日志行）、
保存场景失败、拖入未导入文件、导入坏 .shader（解析错误中文案）、检查器非法输入
（状态栏 TipException 文案）；④ 控制台/日志面观察无 `!editor.core.*!` 兜底串；
⑤ 单测面 `:javelin-editor:test` 全绿（含机检与 ShaderParserTest）。

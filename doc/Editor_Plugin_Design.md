# 编辑器插件体系与 i18n 设计（v0.1）

> 状态：**首批落地，已验收**（2026-10-20 用户实测通过）——插件静态骨架（契约/装载/账本/
> 隔离）+ editor.core 迁移（编辑器即插件通电）+ i18n 基建（注册中心/scope/门面）+
> 菜单 i18n 首批（label 全链 I18nKey）。
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
- **语言包走约定路径不声明**（2026-10-20 用户拍板，原 `i18nBaseName()` 移除）：
  插件 jar 根下 `lang/` 一种语言一个文件，`lang/zh_cn.properties` **必备默认**、
  `lang/en.properties` 等可选。`lang/` 目录就是语言包的 manifest——少一个接口方法，
  宿主还能不实例化插件就静态审计 jar 支持哪些语言。
- **version 语义化版本校验 fail-fast**（装载期）：将来兼容性检查
  （「本编辑器要求插件 ≥ x.y」）的数据源，脏版本号早暴露。

### 2.2 PluginContext（注册门面）

`setup(PluginContext)` 唯一入参，插件对宿主扩展点的注册经此收口：

| 方法 | 转发目标 | fail-fast 语义 |
|------|----------|----------------|
| `registerPanel(PanelDescriptor)` | PanelRegistry | id 重复抛 |
| `registerCommand(MenuCommand)` | CommandService | id 重复抛 |
| `registerFileType(FileType)` | FileTypeRegistry | 扩展名重复抛 |
| `i18n()` | — | 返回命名空间绑定的 I18nScope（§4.2） |

持有 namespace 作宿主归属记录锚点（将来源码 i18n 报告分桶、插件管理界面的数据源）。

**纪律口径**：editor.core 的 setup() 经 ctx 注册（三个 Setup 类签名已改 `setup(PluginContext)`，
内部全部 `context.registerXxx`），无特权直调静态门面通道——同模块下这靠约定不靠编译器
（editor.core 与 PanelRegistry 同模块，绕过 ctx 编译得过），CR 把关；注册贡献按 namespace
前缀化后（§7 开放项）此纪律升级为结构性强制。

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
     → I18n.registerBundle → setup → 账本 + info 日志
     ——任一环失败异常直接抛 = 编辑器起不来（fail-fast）
  ② jar 段：目录下 *.jar 按文件名排序（装载序确定）→ PluginInstance.load
     → namespace 查重 → I18n.registerBundle → setup
     ——装载失败/冲突/setup 失败一律 warn 跳过 + FAILED 账本，编辑器照常运行
```

拍板与语义细节：

- **第三方 namespace 冲突 = 后来者跳过 + warn + close，不 fail-fast**（实现期定，修正
  早前「撞名 fail-fast」措辞）：按错误隔离分级推导，撞名的第三方插件属于「坏插件」，
  不该杀编辑器；且 classpath 恒先装载，**editor.core 的命名空间结构性不可能被抢占**
  （撞了必被跳过），防覆盖底线自动成立。classpath 内部撞名（本体级错误）仍 fail-fast。
- **两处 close 语义不同**：撞名跳过 close classloader（setup 未跑、零注册引用，安全）；
  **setup 失败不 close**——部分注册的面板 factory 可能引用插件类，close 会导致后续
  NoClassDefFound，宁可留着句柄。无回滚（半注册贡献保留），账本记 FAILED。
- **语言包注册在 setup 之前**（两段管线一致）：保证元数据 name/description 与 setup
  期文案即有翻译。`I18n.registerBundle` 失败分级随段：classpath 段 fatal / jar 段
  catch 后**降级装载**（i18n 不可用、插件照常，§4.6 兼容矩阵）。

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

三字段：`namespace`（路由键）+ `key`（局部键，语言包文件内键名）+ `args`（{0} 序插值）。
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
自己的 `lang/zh_cn.properties`。**metadata 特例**（name/description 在 setup 前被调，
scope 尚未注入）：`I18nKey.of(namespace(), "plugin.name")` 自引用，每插件两处可接受。

否决的备选：**注册边界自动加 ns**（异常 key 不过注册面，上下文丢失）；**包名反推
namespace**（魔法推导与「namespace 显式声明」拍板冲突，挪包即静默漂移）。

### 4.3 I18n 注册中心（静态门面）

- **装载**：`registerBundle(namespace, classLoader)`——按约定路径读
  `lang/zh_cn.properties`（**必备**，缺失/损坏抛——分级在调用方：classpath 段 fatal，
  jar 段 catch 降级装载，§4.6）+ `lang/{当前语言}.properties`（可选，缺失 warn 回退
  默认）；当前语言文件覆盖默认条目合并为单表。**不走 ResourceBundle**：其 `_zh_CN`
  大小写命名与父子链回退和约定路径不匹配，且我们的回退语义（默认文件 → `!key!`）
  本就是定制的；自实现 Properties 加载（**必须 `load(Reader)` 显式 UTF-8**——
  `load(InputStream)` 至今按 spec 是 ISO-8859-1，中文必乱码，JDK 9 的 UTF-8 改动
  只覆盖 PropertyResourceBundle）。
- **语言切换**：`setLanguage` 启动期一次（注册后锁定抛异常）——**重启生效**口径；
  默认 `zh_cn`。从 editor.settings 读语言项属后续接线。
- **解析**：`get(namespace, key, args)`——命中走 **MessageFormat** 插值
  （**文案中单引号需双写 `''` 转义**，写 properties 时的经典坑）；缺失返回
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
**日志/异常消息不迁移**（诊断面，Unity 也不 i18n 日志——异常/弹窗消息属第二批）。

### 4.5 三层强制（全量目标形态）

1. **出口签名**：弹窗/状态栏/TipException 构造器收 I18nKey（异常消息 ~116 条随第二批）；
2. **注解层启动校验**：InsField/InsHeader 的 key 静态可枚举，装载期聚合 error log
   （不抛，运行时照旧 `!key!` 兜底）；
3. **源码机检**：properties 之外源码无中文字面量（白名单豁免 log）——测试/构建任务
   扫 CJK 字符串字面量即 fail，漏国际化从人眼 CR 变 CI 红线。

### 4.6 第三方插件兼容矩阵（2026-10-20 补拍）

第三方插件的 i18n 是**约定而非门槛**——缺什么都不拒载插件本体，只降级文案能力：

| 场景 | 行为 | 用户所见 |
|------|------|----------|
| 带全量语言包（默认 + 当前语言） | 正常注册 | 各语言正常显示 |
| 只有默认文件、缺当前语言文件 | warn + 回退默认条目 | 显示默认语言文案（键不黑） |
| **完全无语言包 / 包损坏** | warn + **降级装载**（PluginManager jar 段 catch，原「跳过整个插件」口径作废——功能完好不该因文本文件被拒，Unity/IntelliJ 同宽） | 硬编码文案的插件照常工作；用键则 `!ns.key!` 兜底 + miss 进该插件报告桶 |
| 有包但缺个别 key | `!ns.key!` 显示 + 去重 warn | 界面直接指认哪个插件缺翻 |

对照口径：**editor.core 无包/坏包 = fatal**（本体文案必须表驱动，宿主纪律不降级）——
分级由调用方段位决定，I18n 自身恒抛。

**插件作者的接入姿势**（零耦合）：自带 Lang 静态门面（setup 期 `init(context.i18n())`
一次）+ 自己的 `lang/zh_cn.properties`；渲染面（核心菜单里的插件命令标签等）经
`label().text()` 解析，注册表不关心 key 属于哪个命名空间——任何 namespace 的键在任何
UI 位置都可渲染。**隔离保证**：namespace 路由 + 撞名后来者跳过 → 第三方无法覆盖
editor.core 或其他插件的任何文案；miss/used 采集按 namespace 分桶，每个作者拿到
自己的缺翻工单，不用翻别人的报告。

### 4.7 插件国际化示例（完整链路）

jar 内布局（语言包 = 约定路径两个文本文件，无其他）：

```
myplugin.jar
 ├─ com/example/myplugin/（MyPlugin 入口 / MyLang 门面 / 功能类）
 ├─ lang/zh_cn.properties          ← 必备默认
 ├─ lang/en.properties             ← 可选（缺键回退默认条目）
 └─ META-INF/services/com.javelin.editor.plugin.Plugin → 入口类
```

语言包（key 局部名 + {0} 序插值）：

```properties
# lang/zh_cn.properties
plugin.name=我的工具插件
toolbar.title=工具
error.loadFailed=资源 {0} 加载失败
```

插件侧（namespace 全程一次）：

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
标题 key 化随后续批次）；异常消息 `MyLang.key("error.loadFailed", path)` 通道随第二批
（TipException 收 I18nKey）开通；切语言 = 补语言文件 + 启动期 `I18n.setLanguage`
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
4. **MessageFormat 单引号是转义字符**：文案含 `'` 需双写 `''` 才输出单引号。
5. **菜单/面板 repaint 期反复解析**：缺失 warn 必须按 key 去重，否则刷屏。

---

## 7. 开放项与后置路线

### 7.1 待拍板（挂账）

| 项 | 现状 | 影响 |
|----|------|------|
| 命令 id 前缀化 | 第三方命令 id 若撞核心域（如 hierarchy.cut）fail-fast 拒绝 | 第三方进公共域的规则 + 菜单结构注入位（Unity 固定注入位口径） |
| FileType 扩展名冲突策略 | 现状 fail-fast（扩展名无法命名空间化） | 第三方接管 .scene 该拒（推荐维持 fail-fast）还是先注册者胜 |
| 默认语言 | 必备默认文件 = zh_cn（槽位即此拍板落点） | 英文默认则换必备文件名 + I18n.DEFAULT_LANGUAGE |
| 组件显示名口径 | @InsHeader 中文名（「变换/相机」） | Unity 口径即英文类名——若切英文，这批标签免搬运 |

### 7.2 后置批次

1. **异常/弹窗消息 i18n**（~116 条）：TipException/ValidationException 构造器收
   I18nKey + tinyfd 弹窗文案 + 状态栏通道；
2. **面板/窗口标题 i18n**：PanelDescriptor.title（「层级/项目/属性/控制台」）+ 窗口
   标题，随控制台面板批次一并；
3. **Inspector 注解 label key 化**：InsField/InsHeader 存 key，装载期启动校验（§4.5-2）；
4. **源码机检**（§4.5-3）+ **i18n 报告导出命令**（Tools 域，miss/used 分桶工单）；
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

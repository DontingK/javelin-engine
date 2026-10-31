# Javelin Engine（标枪引擎）

> [English](README.md)

**一个专注 PC 的 3D 游戏引擎，自带 Unity 式编辑器——用 Java 写、跑在 Vulkan 上、以原生可执行文件交付。**

Javelin 把你熟悉的 Unity 工作流（层级、检查器、拖放资产、一键打包）带进现代 Java：Vulkan 渲染器 + GraalVM native-image 交付。**刻意只面向桌面 PC**：不受移动端约束、不做最低公约数渲染、不为弱硬件砍特性。项目处于早期（`0.1.0`，Windows 优先），但完整闭环已端到端可用：新建项目 → 搭建场景 → 保存重载 → 打包成可玩的游戏（JAR 或原生 `.exe`）。

## 为什么选 Javelin

### 是完整的编辑器，不只是 SDK

多数 Java 引擎给你一个 `main()` 和一份 wiki。Javelin 带来的是真正的桌面编辑器（Swing + FlatLaf）：**层级 / 项目 / 检查器**停靠面板、可拖拽的 Transform Gizmo、**GPU ID-buffer 拾取**、Unity 式场景飞行相机、世界空间调试线、完整的菜单与命令体系。编辑组件就是编辑它的字段——检查器由反射注解驱动，界面内置英文与简体中文双语。

### Vulkan，不是 OpenGL

渲染后端是基于 LWJGL 3 的 Vulkan RHI：RenderGraph、多相机（逐相机 viewport / 清屏标志 / depth 排序）、透明排序的前向管线、MSAA、FIFO / MAILBOX 呈现模式。调试 overlay 内建——`F3` 切换渲染统计与屏幕调试文本。

### AOT：Java 游戏以原生可执行文件交付

分发一直是 Java 游戏的死穴——捆 JRE、几百兆、脆弱的 classpath。Javelin 把它变成一键：编辑器打包你的游戏为自包含目录（`game.jar` + jlink 裁剪运行时），或 **GraalVM native-image 原生可执行文件**（LWJGL DLL 随 exe 外置）。引擎从第一天就为 AOT 而设计——显式反射注册取代 classpath 扫描、`reflect-config.json` 由注解处理器编译期生成——同一份游戏在 JVM 与原生镜像上行为一致。

### Unity 式资产与材质工作流

GUID + 伴生 `.meta`、拖入即导入的焦点扫描自动刷新（Unity *Auto Refresh*）、参考 ShaderLab 的 `.shader` 文件导入期编译 SPIR-V。材质带 UBO 参数、贴图槽与多子网格多材质；内置原始体（平面/立方体/球/胶囊/圆柱）开箱即摆。坏 shader 回退默认材质不炸帧循环，shader 热重载接进编辑器。

## 更多能力

- **场景模型** — Node / Component / Transform（生命周期帧驱动）、`.scene` 文本序列化
- **物理查询** — 射线/球/盒/圆柱/AABB 纯数学层、场景级 `Physics.raycast` / `raycastAll`（带 layerMask）
- **输入** — 键鼠输入、可绑定动作、边沿检测帧语义
- **贴图** — 三层资产模型：一图多设置零文件复制
- **插件** — `ServiceLoader` 发现；编辑器核心本身就是插件

## 技术栈

| 领域 | 技术 |
|------|------|
| 语言 | Java 25 |
| 渲染 | Vulkan（LWJGL 3：Vulkan / GLFW / shaderc 绑定） |
| 编辑器 UI | Swing + FlatLaf，JSVG 图标 |
| 数学 | JOML |
| 代码生成 | javac 注解处理——编译期反射注册 |
| 构建与打包 | Gradle 9.2、jlink、GraalVM native-image |
| 日志 | slf4j + logback |
| 测试 | JUnit |

## 环境要求

- **GraalVM for JDK 25**——JVM 侧开发与 JAR 打包用任意 JDK 25 均可（jlink 随 JDK 自带）；AOT 原生构建必须 GraalVM，且 Windows 上另需 MSVC / Visual Studio Build Tools
- **Windows 10+**（需支持 Vulkan 的 GPU/驱动；Linux 未测试；macOS 暂不支持——未接 MoltenVK）
- Gradle 9.2（随库附带 wrapper）

## 快速开始

```bash
# 构建全部模块并跑测试
./gradlew build

# 发布引擎构件到 mavenLocal（编辑器创建的游戏项目需要）
./gradlew publishToMavenLocal

# 跑一个渲染 Demo（可换成 javelin-core/src/test 下任意测试类）
./gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.MultiCameraDemo
```

编辑器本身暂无 Gradle run 任务——在 IDE 中运行主类 `cn.donting.javelin.editor.JavelinEditor`。

## 在其他项目中使用引擎

编辑器创建的游戏项目是标准 Gradle 工程，从 `mavenLocal` 消费引擎：

```groovy
dependencies {
    implementation 'cn.donting.javelin:javelin-core:0.1.0-SNAPSHOT'
}
```

## 模块结构

| 模块 | 说明 |
|------|------|
| `javelin-rhi` | Vulkan 渲染后端（RHI）：设备 / 交换链 / RenderGraph / PSO 缓存 / SPIR-V 加载 |
| `javelin-core` | 引擎核心：场景、渲染系统、材质、资产、输入、物理查询、调试 overlay |
| `javelin-ast` | 注解处理器，生成显式反射注册（运行期零 classpath 扫描） |
| `javelin-log` | 日志模块（slf4j + logback） |
| `javelin-tool` | 离线资产生成 CLI（shaderc 编译、调试文本字库光栅化） |
| `javelin-editor` | Swing 编辑器应用 |
| `project-template` | 编辑器创建新游戏项目时复制的 Gradle 模板 |

## 文档与路线

详细设计文档（中文，含拍板记录与坑点存档）在 [`doc/`](doc/)；功能与验收状态见 [`Engine_Features.md`](Engine_Features.md)，阶段路线见 [`doc/Engine_Roadmap.md`](doc/Engine_Roadmap.md)。

当前路线要点：**光照**（clustered Forward+，SSBO 光列表 + compute 着色器 cluster 构建）设计已收口、即将实装；物理与音频随后；长期方向包括探针实时 GI 与 ray query（`VK_KHR_ray_query`）。

## 许可

采用 **MIT License**（见 [LICENSE](LICENSE)）；第三方组件及其许可清单见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

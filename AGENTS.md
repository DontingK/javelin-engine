# Javelin Engine 5 — AI Agent Guide

> 本文档面向 AI 编程助手，提供项目全景、技术栈、构建流程、代码规范与模块划分。项目主要使用中文注释和文档。

## 优先级
代码编辑优先使用IDEA 的 MCP 服务

## 红线
1. 不需要编译验证。 编译交给用户
2. 不允许自己编写不确定的代码
3. 当你不确定要怎么设计时可以询问用户意见
4. 当你有任何不确定的API调用时，特别是lwjgl,vk 相关的，可以提出让用户来给你明确的API签名
5. 不允许自己瞎猜，不确定都要询问用户
6. 无法找到问题时，第一优先级应该是添加日志让用户运行后看日志。
7. **Edit 工具必须验证唯一性**：使用 `Edit` 前，确保 `old_string` 在目标文件中唯一。若存在多处匹配，必须用更长的上下文定位到具体位置，或改用 `Write` 整文件重写。每次 `Edit` 后必须 `Read` 验证文件状态，禁止连续多个 `Edit` 不做验证。

---

## 项目概述

**Javelin Engine 5** 是一个基于 Java 25 + Vulkan 的跨平台游戏引擎，采用模块化 Gradle 多项目结构。核心特性包括：

- **Java 25** 作为开发语言，启用 GraalVM Native Image 支持
- **Vulkan 1.3** 图形 API，通过 LWJGL 3 绑定
- **自研 RHI**（Rendering Hardware Interface）类型安全的 GPU 抽象层
- **自研 Web 编辑器前端**，基于 Vue 3 + Vite，零第三方 UI 框架
- **JCEF 内嵌浏览器** 作为编辑器 UI 容器，JS <-> Java 桥接通信
- **编译时代码生成**：`javelin-ast` 模块提供注解处理器，替代运行时反射

---

## 技术栈

| 层级 | 技术 | 版本 | 说明 |
|------|------|------|------|
| 语言 | Java | 25 | 目标/源码兼容性均为 Java 25 |
| 构建工具 | Gradle | 9.2.0 | 多项目构建 |
| 图形 API | Vulkan | 1.3 | 通过 LWJGL 3.4.2 绑定 |
| 窗口/输入 | GLFW | 3.4.2 | LWJGL 绑定 |
| 数学库 | JOML | 1.10.8 | 3D 数学（向量/矩阵/四元数） |
| 着色器编译 | shaderc | LWJGL 绑定 | GLSL -> SPIR-V 运行时编译 |
| 日志 | SLF4J + Logback | 2.0.18 / 1.5.38 | 彩色日志输出 |
| 原生编译 | GraalVM Native Image | 1.1.0 | `javelin-core` 支持打包为原生可执行文件 |
| 前端框架 | Vue.js | 3.5.38 | 编辑器 Web UI |
| 前端构建 | Vite | 8.1.0 | 多页面构建 |
| 前端语言 | TypeScript | 6.0.2 | 严格模式 |
| 内嵌浏览器 | JCEF | 146.0.10 | Java Chromium Embedded Framework |

---

## 项目结构

```
javelin-engine5/
├── settings.gradle              # 多项目定义
├── build.gradle                 # 根项目：版本常量、仓库配置
├── gradlew / gradlew.bat        # Gradle Wrapper
│
├── javelin-ast/                 # 编译时注解处理器
│   └── src/main/java/com/javelin/ast/
│       ├── annotation/Reflective.java      # @Reflective 注解
│       └── processor/ReflectiveProcessor.java  # 编译时代码生成
│
├── javelin-log/                 # 日志模块（彩色 ANSI 输出）
│   └── src/main/java/com/javelin/log/
│
├── javelin-tool/                # 工具模块（着色器编译器）
│   └── src/main/java/com/javelin/tool/shaderc/
│       ├── ShaderCompiler.java         # GLSL -> SPIR-V 编译
│       ├── ShaderStage.java            # 着色器阶段枚举
│       └── ShaderCompileException.java
│
├── javelin-rhi/                 # 渲染硬件接口（最核心模块，~96 个 Java 文件）
│   └── src/main/java/com/javelin/rhi/
│       ├── api/                 # 用户层接口（Device, Buffer, Texture, ...）
│       ├── types/               # Vulkan 枚举映射（Format, BlendFactor, ...）
│       ├── desc/                # 创建描述符 POJO（BufferDesc, TextureDesc, ...）
│       ├── vulkan/              # Vulkan 后端实现（VulkanDevice, VulkanBuffer, ...）
│       │   └── util/            # Vulkan 辅助工具
│       ├── RHIFactory.java      # 简化初始化工厂
│       ├── RHIException.java    # 异常基类
│       └── VulkanException.java # Vulkan 专用异常
│
├── javelin-core/                # 引擎核心
│   └── src/main/java/com/javelin/core/
│       ├── scene/               # 场景系统（Node/Component/Transform/GameScene/SceneManager）
│       ├── render/              # 渲染抽象（Renderable/RenderList/RenderPass/RenderingPipeline）
│       ├── assets/              # 资产系统（Asset/AssetManager）
│       ├── serialize/           # 序列化（Serializable + 二进制实现）
│       ├── subsystem/           # 子系统框架（Subsystem/SubsystemManager）
│       ├── obj/                 # 对象 ID 体系（ObjectId/ObjectCache）
│       ├── reflective/          # 编译时反射工厂（ReflectiveFactory）
│       ├── window/ settings/ debug/  # 窗口 / 设置 / 调试
│       └── JavelinEngine.java   # 引擎主类（窗口、RHI、主循环）

├── javelin-editor/              # 桌面编辑器（Java Swing + JCEF + Vue 前端）
│   └── src/main/java/com/javelin/editor/
│   └── src/main/resources/builtin-asset-core/  # 内置资源
│
└── engine-editor-web/           # Web 端编辑器前端
    ├── package.json
    ├── vite.config.ts           # 多页面入口配置
    ├── tsconfig.json / tsconfig.app.json
    └── src/
        ├── page/                # 多页面入口
        │   ├── projectManager/  # 项目选择页
        │   ├── projectLoading/  # 加载过渡页
        │   ├── main/            # 编辑器主布局页
        │   └── dialog/          # 对话框页
        ├── components/
        │   ├── input/           # 通用输入控件（Je 前缀）
        │   └── common/          # 基础组件（树、菜单、弹窗）
        ├── service/             # API 服务层（JCEF 桥接）
        ├── bridge/              # JCEF JS <-> Java 桥接封装
        └── types/               # 全局类型声明
```

---

## 模块依赖关系

```
javelin-editor
  ├── javelin-core
  │     ├── javelin-rhi
  │     │     ├── javelin-log
  │     │     └── LWJGL-Vulkan / GLFW / JOML
  │     ├── javelin-log
  │     └── javelin-ast (annotationProcessor)
  ├── javelin-tool
  ├── javelin-rhi
  ├── javelin-log
  └── JCEF / Jackson / JSVG / Gradle Tooling API

engine-editor-web (独立前端，通过 JCEF 桥接与 javelin-editor 通信)
```

---

---

## 代码风格规范

### Java

- **编码**：UTF-8 无 BOM（硬性要求）
- **语言级别**：Java 25，`options.release = 25`
- **包名**：`com.javelin.{module}`
- **接口命名**：无前缀，如 `Device`, `Buffer`, `Texture`
- **Vulkan 实现类**：`Vulkan` 前缀，如 `VulkanDevice`, `VulkanBuffer`
- **描述符类**：`Desc` 后缀，如 `BufferDesc`, `TextureDesc`
- **注解**：`@Reflective` 标记需要编译时注册到反射工厂的类型
- **资源管理**：所有 GPU 资源继承 `GPUResource` 抽象类，GC 自动触发延迟释放；`dispose()` 可手动调用（同样走延迟队列，帧安全），重复调用幂等
- **异常体系**：`RHIException` -> `VulkanException` / `ValidationException`
- **日志**：使用 SLF4J `LoggerFactory.getLogger(Class)`
- **注释语言**：中文为主

### TypeScript / Vue（前端）

- **框架**：Vue 3 Composition API + `<script setup>`
- **类型检查**：`vue-tsc`，严格模式，`noUnusedLocals`, `noUnusedParameters`
- **组件前缀**：
  - `Je`（Javelin Engine）：通用输入控件，v-model 为原生类型
  - `Ins`（Inspector）：属性面板专用控件，v-model 全部为 `string`
- **零第三方 UI 依赖**：不使用 Element Plus / Ant Design / Pinia / Vue Router
- **CSS 主题**：`:root` CSS 变量定义全局浅色主题

---

## 测试策略

- **单元测试**：JUnit Jupiter 5（`javelin-core` 的 `ShaderParserTest` 为典型示例）
- **集成/渲染测试**：通过 `runTest` Gradle 任务运行，继承 `JavelinEngine` 的测试类
- **测试资源**：着色器源码放在 `src/test/resources/shader/` 下
- **运行要求**：渲染测试需要 Vulkan 兼容 GPU 和显示环境

---

## 关键设计约定

### RHI 资源生命周期

```java
public abstract class GPUResource {
    public abstract long handle();          // 原生 Vulkan handle
    protected abstract void doDispose();    // 子类实现释放逻辑
    public boolean isDisposed();            // 基类管理状态
}
```

- **自动释放**：`GPUResource` 内置 `Cleaner`，GC 时自动请求延迟释放
- **延迟释放**：`DelayedResourceDisposer` 延迟 `framesInFlight` 帧后真正释放
- **引擎集成**：`Renderer` 构造时创建并设置 `GPUResource.setDisposer()`，`beginFrame()` 中推进队列，`dispose()` 时 flush
- **直接使用**：引擎侧（如 `RenderCommand`）直接引用 `Buffer`/`Texture`/`GraphicsPipeline`，无需包装

### 线程模型

| 操作 | 线程安全 | 说明 |
|------|---------|------|
| `Device.create*()` | 是 | 内部同步 |
| `CommandBuffer.record*` | 否 | 每线程独立 CommandBuffer |
| `Queue.submit()` | 是 | 内部锁保护 |
| `Buffer.uploadData()` | 否 | 需外部同步 |

推荐模式：每渲染线程一个 `CommandPool`，录制期间无锁。

### 着色器流程

1. 手写 GLSL 源码 -> `ShaderCompiler`（LWJGL shaderc）编译为 SPIR-V 字节码
2. `Shader` 资产包含一个或多个 `ShaderPass`，每个 Pass 含顶点/片段 SPIR-V
3. `Material` 从 `Shader` 自动构建 Pass 列表
4. `MeshRenderer` 在 `onAttach` 时根据 `Mesh` 的 `VertexLayout` + `ShaderPass` 自动创建 `GraphicsPipeline`

### JCEF 桥接协议

- **请求**：前端 `window.cefQuery` 发送 `url::json` 格式请求
- **推送**：后端 `window.__javelinBridge.onMessage(type, data)` 推送事件
- **透明色约定**：视口背景使用 `rgb(255, 0, 0)`，与 Java 端 BGR 蓝色对应
- **运行环境**：前端必须在 JCEF 内嵌浏览器中运行，`window.cefQuery` 在普通浏览器中不可用

---

## 安全与注意事项

1. **文件编码**：所有源文件必须使用 **UTF-8 无 BOM**
2. **GPU 资源**：`GPUResource` 通过 Cleaner 自动延迟释放；`dispose()` 手动释放也走延迟队列，幂等安全
3. **Native Access**：运行时需要 `--enable-native-access=ALL-UNNAMED` JVM 参数
4. **平台限制**：当前 `lwjglNatives` 配置为 `natives-windows`，LWJGL DLL 提取任务也面向 Windows
5. **GraalVM 要求**：Native Image 需要 Oracle GraalVM Java 25

---

## 文档索引

| 文档 | 路径 | 内容 |
|------|------|------|
| RHI 设计文档 | `doc/RHI-DESIGN.md` | Vulkan RHI 完整架构、接口定义、内存分配、帧资源管理 |
| GPU 资源生命周期 | `doc/GPU-RESOURCE-LIFECYCLE.md` | GPU 资源自动释放、Cleaner 延迟释放机制 |
| GameScene 场景设计 | `doc/SCENE-DESIGN.md` | Node/Component/Transform/GameScene/SceneManager 架构、生命周期、销毁安全、主循环时序 |
| 着色器与材质设计 | `doc/SHADER-DESIGN.md` | ShaderLab 风格 .shader DSL、Shader/Material 资产、Uniform 绑定模型、管线缓存、导入管线 |
| 序列化设计文档 | `doc/SERIALIZE-DESIGN.md` | Serializable 胶囊式 API、二进制文件格式、对象图导出/导入、Asset GUID 引用 |
| Web 编辑器指南 | `engine-editor-web/AGENTS.md` | 前端模块详细规范、组件命名、JCEF 桥接协议 |

---

*本文档基于项目实际内容生成，最后更新：2026-09-09*

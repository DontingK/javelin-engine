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


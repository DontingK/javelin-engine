# Engine Editor Web - Agent Guide

> 本文档面向 AI 编程助手，提供 `engine-editor-web` 模块的背景、技术选型、代码规范和开发注意事项。

---

## 项目概述

`engine-editor-web` 是 **Javelin Engine** 的 Web 端编辑器前端，运行在 **JCEF（Java Chromium Embedded Framework）** 内嵌浏览器中，通过 JCEF JS -> Java 桥接与 Java 后端（`javelin-editor`）通信。

核心职责：
- 项目管理（新建/打开/最近项目）
- 编辑器布局（四区停靠布局、可拖拽分隔条）
- 场景层级面板（实体树、右键菜单）
- 实体属性面板（Inspector）
- 场景/游戏视口切换
- 焦点管理（3D 视口透明区域交互）
- JCEF 桥接实时通信（场景变更推送、焦点锁定、确认对话框）

该模块采用 **零第三方 UI 依赖** 策略，不使用 Element Plus / Ant Design / Pinia / Vue Router，全部自研组件。

---

## 技术栈

| 层级 | 技术 | 版本 | 说明 |
|------|------|------|------|
| 框架 | Vue.js | 3.5.38 | 渐进式 JavaScript 框架 |
| 构建工具 | Vite | 8.1.0 | 下一代前端构建工具 |
| 语言 | TypeScript | 6.0.2 | JavaScript 超集，类型安全 |
| 类型检查 | vue-tsc | 3.3.5 | Vue TypeScript 类型检查 |

**特点**：极其精简的依赖——没有任何 UI 框架、路由库、状态管理库，全部自研。

---

## 项目结构

```
engine-editor-web/
├── index.html                      # 入口 HTML
├── package.json                    # 项目配置
├── vite.config.ts                  # Vite 配置
├── tsconfig.json                   # TS 配置
└── src/
    ├── main.ts                     # 应用入口：创建 Vue app，挂载 JCEF 消息监听
    ├── App.vue                     # 根组件：根据项目状态路由三页面 + 全局快捷键
    ├── style.css                   # 全局主题 CSS 变量与基础样式
    │
    ├── pages/                      # 顶层页面
    │   ├── ProjectPage.vue         # 项目选择页：新建/打开项目、最近项目列表
    │   ├── ProjectLoadingPage.vue  # 项目加载过渡页：旋转加载动画 + 进度条
    │   └── EditorLayoutPage.vue    # 编辑器主布局页：四区停靠布局 + 可拖拽分隔条
    │
    ├── components/
    │   ├── layout/                 # 布局组件
    │   │   ├── MenuBar.vue         # 顶部菜单栏（文件/编辑/视图/构建/帮助）
    │   │   ├── StatusBar.vue       # 底部状态栏（JCEF 桥接连接状态指示）
    │   │   ├── DockContainer.vue   # 可复用停靠区容器（标签页切换）
    │   │   └── Resizer.vue         # 可拖拽分隔条（水平/垂直）
    │   │
    │   ├── panels/                 # 编辑器面板
    │   │   ├── HierarchyPanel.vue  # 层级面板：场景实体树、右键菜单
    │   │   ├── EntityInspectorPanel.vue  # 实体属性面板
    │   │   ├── SceneViewportPanel.vue    # 场景视口占位
    │   │   ├── GameViewportPanel.vue     # 游戏视口占位
    │   │   ├── ProjectPanel.vue          # 项目面板占位
    │   │   └── ConsolePanel.vue          # 控制台面板占位
    │   │
    │   ├── common/                 # 通用基础组件
    │   │   ├── CollapsiblePanel.vue    # 可折叠面板容器
    │   │   ├── ConfirmDialog.vue       # 全局确认弹窗
    │   │   ├── PromptDialog.vue        # 全局输入弹窗
    │   │   ├── JeButton.vue            # 统一按钮组件
    │   │   ├── types.ts                # TreeNode 通用树节点类型
    │   │   ├── menu/                   # 右键菜单系统
    │   │   │   ├── ContextMenu.vue     # 右键菜单容器
    │   │   │   ├── MenuItem.vue        # 菜单项
    │   │   │   ├── MenuSubMenu.vue     # 子菜单
    │   │   │   └── MenuDivider.vue     # 菜单分隔线
    │   │   └── tree/                   # 树组件
    │   │       ├── JavelinTree.vue     # 树容器
    │   │       └── JavelinTreeNode.vue # 树节点
    │   │
    │   ├── input/                  # 通用输入控件（Je 前缀，v-model 使用原生类型）
    │   │   ├── JeInput.vue         # 文本输入（v-model: string）
    │   │   ├── JeNumberInput.vue   # 数值输入（v-model: number）
    │   │   ├── JeCheckBox.vue      # 复选框（v-model: boolean）
    │   │   └── JeSelect.vue        # 下拉选择（v-model: string）
    │   │
    │   └── inspector/              # Inspector 模块
    │       ├── InspectorField.vue  # Inspector 字段渲染（根据 valueType 自动选择输入控件）
    │       └── input/              # Inspector 专用输入控件（Ins 前缀，v-model 全部为 string）
    │           ├── InsInt.vue      # 整数输入（v-model: "123"）
    │           ├── InsFloat.vue    # 浮点数输入（v-model: "3.14"）
    │           ├── InsBoolean.vue  # 布尔复选框（v-model: "true"/"false"）
    │           ├── InsString.vue   # 字符串输入（v-model: 原样）
    │           ├── InsEnum.vue     # 枚举选择（v-model: 枚举常量名）
    │           ├── InsVector3.vue  # 三维向量输入（v-model: "x,y,z"）
    │           ├── InsRotation.vue # 旋转输入（v-model: "x,y,z,w" 四元数）
    │           ├── InsRect.vue     # 矩形输入（v-model: "x,y,width,height"）
    │           └── InsLayerMask.vue # 层遮罩输入（v-model: 整数字符串）
    │
    ├── composables/                # Vue 组合式函数
    │   ├── useProject.ts           # 项目状态管理（sessionStorage 持久化）
    │   ├── useEditorLayout.ts      # 编辑器布局管理（四区面板列表 CRUD）
    │   ├── useSelection.ts         # 全局选中实体 ID 状态
    │   ├── useConfirm.ts           # 全局确认弹窗状态
    │   ├── usePrompt.ts            # 全局输入弹窗状态
    │   └── useFocusLock.ts         # 焦点锁定（配合 3D 视口透明区域交互）
    │
    ├── service/                    # API 服务层
    │   ├── types.ts                # 所有 VO/DTO 类型定义
    │   ├── projectService.ts       # 项目 API
    │   ├── projectFileService.ts   # 项目文件 API
    │   ├── sceneService.ts         # 场景 API
    │   ├── entityService.ts        # 实体 API
    │   ├── inspectorService.ts     # Inspector API
    │   ├── directoryService.ts     # 目录 API
    │   ├── cursorService.ts        # 光标服务（JCEF 桥接）
    │   ├── focusService.ts         # 焦点服务（JCEF 桥接）
    │   └── confirmDialogService.ts # 确认对话框结果（JCEF 桥接）
    │
    ├── bridge/                     # JCEF JS <-> Java 桥接
    │   ├── jcefBridge.ts           # CefQuery 请求封装
    │   ├── javaScriptBridge.ts     # Java -> JS 消息监听封装
    │   └── messageTypes.ts         # Java -> JS 消息类型定义
    │
    ├── http/                       # HTTP 请求封装（保留 HttpResult<T> 类型，当前无后端代理）
    │   ├── ApiResponse.ts          # 后端统一响应结构 ApiResponse<T>
    │   └── httpUtil.ts             # 链式 HTTP 工具（HttpResult<T>）
    │
    └── types/                      # 全局类型声明
        └── window.d.ts             # window.javelin 全局 API 类型声明
```

---

## 组件命名规范

### 前缀约定

| 前缀 | 位置 | 说明 | v-model 类型 |
|------|------|------|-------------|
| `Je` | `components/input/` | 通用输入控件（Javelin Engine） | 原生类型（string/number/boolean） |
| `Ins` | `components/inspector/input/` | Inspector 专用组件 | **全部为 string** |

### 通用输入控件（`components/input/`）

可复用的基础输入组件，v-model 使用原生类型：

| 组件 | 说明 | v-model 类型 | 对应后端类型 |
|------|------|-------------|-------------|
| `JeInput` | 文本输入框 | `string` | `STRING` |
| `JeNumberInput` | 数字输入框（整数/浮点） | `number` | `INT`, `FLOAT` |
| `JeCheckBox` | 复选框 | `boolean` | `BOOLEAN` |
| `JeSelect` | 下拉选择 | `string` | `ENUM` |

### Inspector 专用组件（`components/inspector/input/`）

**所有 Ins 组件的 v-model 类型为 `string`**，与后端 `InspectorFieldType` 的序列化格式对应：

| 组件 | 说明 | v-model 格式 | 对应后端类型 |
|------|------|-------------|-------------|
| `InsInt` | 整数输入 | `"123"` | `INT` |
| `InsFloat` | 浮点数输入 | `"3.14"` | `FLOAT` |
| `InsBoolean` | 布尔复选框 | `"true"` / `"false"` | `BOOLEAN` |
| `InsString` | 字符串输入 | 原样 | `STRING` |
| `InsEnum` | 枚举选择 | 枚举常量名 | `ENUM` |
| `InsVector3` | 三维向量输入 | `"x,y,z"` | `VECTOR3` |
| `InsRotation` | 旋转输入（欧拉角↔四元数） | `"x,y,z,w"` | `ROTATION` |
| `InsRect` | 矩形区域输入 | `"x,y,width,height"` | `RECT` |
| `InsLayerMask` | 层掩码输入 | `"整数"` | `LAYER_MASK` |

### 与后端 InspectorFieldType 的对应关系

```
后端 InspectorFieldType    前端组件           v-model 格式
──────────────────────────────────────────────────────────
AUTO                    → 自动推断
INT                     → InsInt            "123"
ENUM                    → InsEnum           枚举常量名
FLOAT                   → InsFloat          "3.14"
BOOLEAN                 → InsBoolean        "true"/"false"
STRING                  → InsString         原样
COLOR                   → ❌ 缺失            string（待定）
VECTOR2                 → ❌ 缺失            "x,y"
VECTOR3                 → InsVector3        "x,y,z"
ROTATION                → InsRotation       "x,y,z,w"
RECT                    → InsRect           "x,y,w,h"
LAYER_MASK              → InsLayerMask      "整数"
```

---

## 核心功能详解

### 1. 通信架构

#### JCEF JS -> Java 桥接
- **请求通道**：`window.cefQuery` 发送 `url::json` 格式请求
- **推送通道**：`window.__javelinBridge.onMessage(type, data)` 接收后端事件
- **前端封装**：`bridge/jcefBridge.ts` 封装请求，`bridge/javaScriptBridge.ts` 封装推送监听
- **业务覆盖**：Project / Directory / ProjectFile / Scene / SceneEntity / Inspector / Cursor / Focus / ConfirmDialog
- **说明**：前端必须在 JCEF 环境中运行，浏览器开发模式下桥接不可用

### 2. 页面路由

```
App.vue
  ├─ 无项目 → ProjectPage（项目选择）
  ├─ 加载中 → ProjectLoadingPage（加载动画）
  └─ 已加载 → EditorLayoutPage（编辑器主布局）
```

状态通过 `sessionStorage` 持久化，刷新后恢复。

### 3. 编辑器布局

- **四区停靠布局**：左（层级）、中（场景/游戏视口）、右（Inspector）、下（项目/控制台）
- **可拖拽分隔条**：`Resizer.vue` 使用 Pointer API 捕获，支持水平/垂直方向
- **面板管理**：`useEditorLayout` 组合式函数管理面板列表 CRUD
- **全局 API**：暴露 `window.javelin`（`javelin.layout` 和 `javelin.project`），供 JCEF 后端 JavaScript 桥接调用

### 4. 面板实现状态

| 面板 | 位置 | 状态 | 说明 |
|------|------|------|------|
| HierarchyPanel | 左 | ✅ 已实现 | 场景实体树、右键菜单（新建/重命名/删除） |
| EntityInspectorPanel | 右 | 🔲 UI 占位 | 实体属性面板，数据未接通 |
| SceneViewportPanel | 中 | 🔲 占位 | 场景编辑视口 |
| GameViewportPanel | 中 | 🔲 占位 | 游戏运行视口 |
| ProjectPanel | 下 | 🔲 占位 | 项目资源浏览器 |
| ConsolePanel | 下 | 🔲 占位 | 控制台输出 |

### 5. 交互特性

- **全局快捷键**：F5 刷新页面，Ctrl+S 保存场景
- **焦点管理**：3D 视口透明区域交互时，通过 JCEF JS -> Java 桥接锁定/解锁焦点防止事件穿透
- **光标管理**：拖拽分隔条时通过 JCEF JS -> Java 桥接通知后端设置对应光标样式
- **JCEF 透明穿透**：视口背景使用纯色（RGB 红 = BGR 蓝），与 Java 端 Vulkan 渲染的透明色对应

### 6. 组件设计原则

- **零第三方 UI 依赖**：不使用 Element Plus / Ant Design / Pinia / Vue Router
- **纯 Composition API + `<script setup>`**：所有组件使用 Vue 3 组合式 API
- **TypeScript 严格模式**：完整的类型定义，含 `window.d.ts` 全局类型声明
- **CSS 变量主题系统**：通过 `:root` CSS 变量定义全局主题（浅色模式）

---

## 开发命令

```powershell
# 安装依赖
npm install

# 启动开发服务器（默认端口 5173）
npm run dev

# 类型检查 + 构建
npm run build

# 预览构建产物
npm run preview
```

---

## 与后端的关系

前端通过两层与后端交互：

1. **`javelin-editor`（Java 后端）**
   - 提供 JCEF JS -> Java 桥接（`com.javelin.editor.jcef` 包下的 `@BridgeMethod` 方法）
   - 负责项目管理、场景操作、实体操作、Inspector 数据、光标/焦点控制
   - 通过 `JavaScriptBridge` 主动推送事件到前端 `window.__javelinBridge.onMessage`

2. **`javelin-core`（引擎核心）**
   - JCEF 后端依赖 javelin-core
   - 通过 **Vulkan** 进行渲染（LWJGL 绑定）
   - 前端视口区域是透明的，Vulkan 渲染画面穿透显示

---

## 注意事项

1. **Node.js 版本**：建议使用 Node.js 20+
2. **运行环境**：前端必须在 JCEF 内嵌浏览器中运行，`window.cefQuery` 不可用
3. **透明色约定**：视口背景使用 `rgb(255, 0, 0)`，与 Java 端的 BGR 蓝色对应
4. **全局 API**：`window.javelin` 对象供 JCEF 后端 JavaScript 桥接调用，修改时需同步更新 `window.d.ts`
5. **JS 桥接协议**：前端通过 `window.cefQuery` 发送 `url::json`；后端通过 `window.__javelinBridge.onMessage` 推送事件
6. **浏览器开发模式**：由于后端 Web/Spring 已移除，`bridge/jcefBridge.ts` 不再降级到 HTTP，浏览器中调用 Java 后端会返回错误

---

## 参考资源

- **根项目指南**：`../AGENTS.md` — 项目全景、模块划分、代码规范
- **RHI 设计文档**：`../doc/RHI-DESIGN.md` — Vulkan RHI 架构
- **着色器设计文档**：`../doc/SHADER-DESIGN.md` — ShaderLab DSL 与导入管线

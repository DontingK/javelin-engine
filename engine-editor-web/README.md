# engine-editor-web

Javelin Engine 3 的可视化编辑器 **Web 前端**，基于 **Vue 3 + TypeScript + Vite** 构建，运行在 **JCEF（Java Chromium Embedded Framework）** 内嵌浏览器中，通过 JCEF JS ↔ Java 桥接与 `javelin-editor`（Java 后端）通信。

## 定位

- 编辑器交互层：项目管理（新建/打开/最近项目）、四区停靠布局、场景层级面板、实体属性（Inspector）面板、场景/游戏视口切换、焦点与光标管理。
- 与后端通过 `window.cefQuery`（请求）与 `window.__javelinBridge.onMessage`（推送）双向通信，前端必须在 JCEF 环境中运行，浏览器开发模式桥接不可用。
- 采用**零第三方 UI 依赖**策略：不引入 Element Plus / Ant Design / Pinia / Vue Router，全部自研组件。

## 技术栈

| 层级 | 技术 | 版本 |
|---|---|---|
| 框架 | Vue.js | 3.5.38 |
| 构建工具 | Vite | 8.1.0 |
| 语言 | TypeScript | 6.0.2 |
| 类型检查 | vue-tsc | 3.3.5 |

## 开发命令

```powershell
npm install      # 安装依赖
npm run dev     # 启动开发服务器（默认端口 5173）
npm run build   # 类型检查 + 构建
npm run preview # 预览构建产物
```

> 详细的模块结构、组件命名规范、JCEF 桥接协议与后端关系，见同目录 `AGENTS.md`。

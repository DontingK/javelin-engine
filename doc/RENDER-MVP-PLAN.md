# Mesh 渲染 MVP 快速验证方案 —— 问题记录与优化计划

## 背景

2026-09-14 在 `EditorJavelinEngine.java` 中创建了一个 Plane mesh，但渲染时屏幕上看不到任何东西。经过排查，发现是 **MVP 矩阵没有传入 GPU** 导致的。已实施临时快速验证方案，mesh 现在可以正常渲染（红色）。

---

## 当前问题清单

### 1. MVP 矩阵传递缺失（已临时修复）

**问题描述**：
- `default.shader` 的 VS 原来直接写死 `gl_Position = vec4(a_pos * 0.1, 1.0)`，没有使用任何变换矩阵
- `ForwardRenderPass.drawCommand` 没有设置任何 uniform 数据（push constant / descriptor set 均未绑定）
- GPU 收到的变换矩阵不存在或全是默认值，导致 mesh 要么在屏幕外，要么被压缩成一个点

**临时修复**：
- 在 `ForwardRenderPass` 中通过 `cmd.pushConstants` 传入 MVP 矩阵
- VS 中使用 `u_mvp` push constant 进行顶点变换
- 修改文件：
  - `ForwardRenderPass.java` — 计算并推送 MVP
  - `RenderCommand.java` — 新增 `pipelineLayout` 字段
  - `MeshRenderer.java` — 在 `collect` 时把 `pipelineLayout` 传给 `RenderCommand`
  - `default.shader` — VS 使用 `u_mvp`

**遗留问题**：
- 只有 `ForwardRenderPass` 实现了 push constant，其他 Pass 没有
- Push constant 是临时方案，应迁移到 UBO / descriptor set

---

### 2. 渲染管线缺少 Depth Buffer

**问题描述**：
- `RenderingPipeline.executePasses` 中 `beginRendering` 只绑定了 color attachment，没有 depth attachment
- 当前 `zWrite` 和 `zTest` 都设为 `Off`，深度测试未启用
- 多物体渲染时无法正确处理遮挡关系

**优化方向**：
- 创建 Depth Texture 并在 `beginRendering` 时绑定为 depth attachment
- 修改 `default.shader` 的 `zWrite` 和 `zTest` 为合理值（如 `zWrite On`, `zTest LessEqual`）
- 在 `RenderingPipeline` 中管理 depth buffer 的创建和清理

---

### 3. Uniform / Descriptor Set 系统未完善

**问题描述**：
- `JavelinPerFrame`（view/proj/cameraPos/time 等）和 `JavelinPerObject`（model/normalMatrix）两个 UBO 声明在 `ShaderPreprocessor` 中，但从未被填充数据
- `ForwardRenderPass` 中 descriptor set 的绑定被注释掉了（`TODO: 需要 PipelineLayout 参数`）
- `MaterialInstance` 的 UBO 只上传了材质参数（如 `_Color`），没有 MVP 数据

**优化方向**：
- 创建全局 PerFrame UBO，每帧更新 view/proj/cameraPos/time
- 创建 PerObject UBO 或继续使用 push constant 传 model 矩阵
- 在 `ForwardRenderPass` 中正确绑定 descriptor set（需要 `PipelineLayout` 参数）
- 考虑使用 dynamic uniform buffer 减少 descriptor set 分配

---

### 4. Fragment Shader 硬编码颜色

**问题描述**：
- `default.shader` 的 FS 直接写死 `o_color = vec4(1.0, 0.0, 0.0, 1.0)`，没有使用材质参数
- `MaterialInstance.uploadUboData()` 虽然上传了材质参数，但 FS 没有读取

**优化方向**：
- 在 FS 中读取 `MaterialParams` UBO 中的 `_Color`
- 完善 `MaterialInstance` 的 UBO 布局和上传逻辑

---

### 5. Shader 预处理与 Push Constant 命名冲突

**问题描述**：
- `ShaderPreprocessor` 会自动注入 `JavelinPushConstants` 块，如果 shader 源码中手动写同名 block 会导致编译失败
- 错误信息：`Cannot reuse block name within the same interface: uniform`

**优化方向**：
- 文档化：用户 shader 中不应手动声明 `JavelinPushConstants`
- 或在 `ShaderPreprocessor` 中检测并去重

---

### 6. Camera 视口计算可能有问题

**问题描述**：
- `Camera.getViewMatrix()` 使用 `setLookAt` 构建视图矩阵，但 forward 向量计算方式可能有问题
- `Camera.getProjectionMatrix()` 中 `m.scale(1.0f, -1.0f, 1.0f)` 是 Vulkan NDC 的 Y 翻转，但需确认与 `setLookAt` 的配合

**优化方向**：
- 验证 view 矩阵和 projection 矩阵的正确性
- 确认 `setLookAt` 的 up 向量在 Y 翻转后仍然正确

---

## 优化计划

### Phase 1: 完善 Depth Buffer（高优先级）

1. 在 `RenderingPipeline` 中创建 Depth Texture
2. 修改 `beginRendering` 绑定 depth attachment
3. 修改 `default.shader` 启用 zWrite/zTest
4. 验证多物体遮挡关系正确

### Phase 2: 完善 Uniform 系统（高优先级）

1. 创建 `PerFrameUniform` 类，管理 view/proj/cameraPos/time 等数据
2. 每帧在 `JavelinEngine.render()` 或 `RenderingPipeline.render()` 中更新 PerFrame UBO
3. 在 `ForwardRenderPass` 中绑定 PerFrame 和 PerObject 的 descriptor set
4. 移除 push constant 临时方案（或保留用于 PerObject）

### Phase 3: 完善材质系统（中优先级）

1. 在 `default.shader` 的 FS 中读取 `_Color`
2. 完善 `MaterialInstance.uploadUboData()` 的 UBO 布局计算
3. 支持更多材质参数类型（float, vec2, vec3, vec4, texture 等）

### Phase 4: 代码清理（低优先级）

1. 移除 `RenderCommand.pipelineLayout` 临时字段（如果 Phase 2 完成）
2. 清理 `ForwardRenderPass` 中的 push constant 代码（如果迁移到 UBO）
3. 添加 shader 预处理冲突检测
4. 补充文档：shader 编写规范、uniform 系统使用方式

---

## 相关文件

| 文件 | 作用 |
|------|------|
| `javelin-core/src/main/java/com/javelin/core/render/pass/ForwardRenderPass.java` | 前向渲染 Pass，临时 push constant 方案 |
| `javelin-core/src/main/java/com/javelin/core/render/RenderCommand.java` | 渲染命令，新增 `pipelineLayout` 字段 |
| `javelin-core/src/main/java/com/javelin/core/scene/component/MeshRenderer.java` | Mesh 渲染组件，传递 `pipelineLayout` |
| `javelin-core/src/main/java/com/javelin/core/render/RenderingPipeline.java` | 渲染管线，需添加 depth buffer |
| `javelin-core/src/main/java/com/javelin/core/scene/component/Camera.java` | 相机组件，view/projection 矩阵 |
| `javelin-editor/src/main/resources/builtin-asset-core/shader/default.shader` | 默认着色器 |
| `javelin-editor/src/main/java/com/javelin/editor/importer/shader/ShaderPreprocessor.java` | 着色器预处理，注入 uniform 块 |

---

## 验证方式

- 运行编辑器，打开项目，确认 Plane mesh 能正常渲染
- 调整相机位置，确认 mesh 随视角变化正确
- 添加第二个 mesh，确认深度测试和遮挡关系正确
- 修改材质颜色，确认 FS 能正确读取材质参数

---

*记录时间：2026-09-14*

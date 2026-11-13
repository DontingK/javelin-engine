# 引擎后处理设计（Engine Post Process Design）

> 版本：v0.6（**步骤一/二已验收；参数可配（常量→push，2026-11-14）**；v0.1 方案件 / v0.2 步骤一落码 / v0.3 管线拆两实现类 + MSAA 移除 + 曲线换装 / v0.4 步骤二 Bloom 独立开关 / v0.5 管线解耦 / v0.6 后处理参数接设置）
> 日期：2026-11-13
> 说明：四期第 1 项（`Engine_Roadmap.md` §4-1）「后处理链（Bloom/Tonemap + HDR 切换点）」的设计方案——HDR 场景颜色、后处理链形态、Tonemap/Bloom 落点、与管线实现/AA（TAA 方向）/多相机的交互。**v0.1 方案件（8 条拍板点见 §7）；v0.2 = 按建议默认落「步骤一（HDR 切换点 + Tonemap）」，实装记录见 §9；v0.3 = 同日管线拆成两个独立实现类（链入共用基类 `AbstractRenderPipeline`、两管线同享）+ MSAA 从引擎/编辑器/设置层移除（AA 空窗至 TAA）+ **Tonemap 曲线由 ACES 换为「高光肩部」**（实机反馈「发灰/不纯」，§9.5），§2/§3.5/§4.2/§7/§8/§9 同步；v0.4 = 步骤二 Bloom（**独立开关**，与 Tonemap 解耦；4 级降采样 + 帐篷核模糊 + 上采样累加，§3.7/§9.6）。**
> 职责边界：
> - 本档管**后处理域**（HDR 容器、链的组成与展开、链内 Pass 形态、Tonemap/Bloom 具体化）；
> - `Engine_RenderPipeline_Design.md` 管 Pass 编排骨架与视口资源表——本档是它 §4.6/§4.7 占位的**具体化**，不重复其机制（有序 Pass 列表、两个管线实现类、图缓存失效规则）；
> - 光照数据通路（cluster/shadow）归 `Engine_Lighting_Design.md`，本档只消费其输出（场景颜色）；
> - SSAO / 天空盒环境光 / 探针 GI / 光追归四期其余项，本档不涉（但链的承载结构为其预留）。

---

## 1. 范围与目标

**目标**：把场景颜色从「直写 backbuffer（LDR）」升级为「写 `lightingRT`（RGBA16F HDR）→ 后处理链 → 显示」，并以两个首件链节点（Tonemap、Bloom）验收链的插拔正确性。

**本期做**：

1. **HDR 切换点**——新增每视口 `lightingRT`（RGBA16F），场景 Pass 的颜色终点由 backbuffer 改为它；
2. **链展开机制**——`PostProcessChain` 由「名单占位」具体化为「有序节点 + 中间 RT + 逐节点录制」；
3. **TonemapPass**——HDR → LDR，链尾直出显示目标（承担原场景 Pass 的清屏/叠加职责）；
4. **BloomPass**——阈值提取 → 多级降采样模糊 → 上采样累加 → 并入 Tonemap 输入；
5. **设置与入口**——开关（默认关，保持现画面）、编辑器工具菜单切换（照 `tools.renderPipeline` 先例）。

**本期不做**：SSAO（P4-02）、UI Pass 组、**TAA**（AA 路线已定取代 MSAA，但 TAA 是独立链上 Pass 项，见 §4.2）、着色模型升级（REND-03）、HDR→LDR 高亮截白真解与 sRGB 线性工作流（AST-03 / P4-09，随天空盒环境光批）、后处理中间 RT 的内存别名（RHI-04）。

---

## 2. 现状（代码实证，2026-11-13）

方案立在现有结构上，先固化几条事实（均可在代码核到；**研发期原两档机制的部分已随 2026-11-13 管线拆分/移除作调整，末条注明**）：

- **管线 = 单条有序 Pass 列表**（2026-11-13 拆分后）：`AbstractRenderPipeline` 持一条列表 + 场景 Pass 身份集（`addScenePass` 登记），`DefaultHybridRenderPipeline` / `ForwardRenderPipeline` 各自装配自己的场景 Pass 序列；`buildGraph` 逐 Pass 调 `addToGraph`，`addPass/removePass` 进当前实现类的唯一列表（编辑器 Gizmo/拾取/调试线走这条）。**启动期由 `JavelinSetting.renderPipeline` 选实现类、运行期不可切**。
- **颜色终点 = `PipelineContext.colorTarget()`**：`view.targetOverride() != null ? targetOverride : backbuffer`（预览视口渲染到纹理、其余写 backbuffer）。
- **清屏归属在场景 Pass**：前向实现是 `OpaqueForwardPass`、混合实现是 `LightingPass`——二者按 `ClearFlags`（SOLID_COLOR / DEPTH_ONLY / DONT_CLEAR）+ `isRegionClear`（分屏区域清屏）决定 `CLEAR/LOAD` 与是否带 clearRegion。`SkyboxPass`、`TransparentForwardPass` 恒 `LOAD` 叠加。
- **空后处理链 = 直通**（v0.2 起已具体化）：`PostProcessChain` 持 `PostProcessNode` 有序表，链空时零展开、逐像素与改造前一致。
- **视口资源模式**：`RenderingSystem.depthFor / gbufferAlbedoFor / lightingRtFor` 一族——`Map<cameraName, Texture>` + `computeIfAbsent` 惰性创建 + resize 换绑（`disposeAll`/`retire`）+ per-view 独立（RHI 19.4-2）。
- **MSAA 已移除（2026-11-13）**：`isMsaaPath`/MSAA 附件族/设置字段/各 Pass MSAA 分支全删，RHI 多采样设施保留休眠；**HDR 与 AA 无冲突**，AA 空窗至 TAA（P4-10）。
- **内置着色器走资产导入链**：`builtin-asset-core/<域>/xxx.vert|.frag` + 伴生 `.meta` → `SpvShader` 产物（`LightingPass` / `SkyboxPass` / `ShadowPass` 为覆盖制先例：自建全屏三角形 VB + push 常量 + 自建描述符集）。

---

## 3. 结构设计

### 3.1 HDR 启用条件（切换点）

**链空 = 现状直通**（场景 Pass 直写 `colorTarget`，零行为变化、零新增 RT）；**链非空 = HDR 生效**（场景 Pass 写 `lightingRT`，链尾 Tonemap 写 `colorTarget`）。

即 HDR 不是一个独立开关，而是「链里有节点」的推论——这正是管线档 §4.6「首个后处理 Pass 引入时」的切换点语义，且保证既有场景画面在未启用后处理时逐像素不变（现画面是已验收基线，不做无谓改动）。

> 备选：恒开 HDR（链空也走 `lightingRT` + Tonemap 直出）。优点是无双路径、图缓存不因开关抖动；缺点是**所有场景画面立刻改变**（LDR 内容过 tonemap 曲线），且与「链空即直通」的既有契约相悖。**建议取链空直通**（§7 拍板点 1）。

### 3.2 `sceneColorTarget` 概念（`PipelineContext` 扩展）

引入「场景颜色终点」这一抽象，把「场景 Pass 画到哪」与「最终显示在哪」分开：

- `colorTarget()` 语义**不变**（最终显示目标：backbuffer 或 targetOverride）——Composite/Tonemap 用；
- 新增 `sceneColorTarget()`：**HDR 生效时返回本视口 `lightingRT`，否则返回 `colorTarget()`**。

四个写颜色的场景 Pass 改取 `sceneColorTarget()`：`OpaqueForwardPass`（前向实现清屏入口）、`LightingPass`（混合实现清屏入口）、`SkyboxPass`、`TransparentForwardPass`。**改动面极小且集中**——每处仅换取值来源，`ClearFlags`/`isRegionClear`/`LOAD` 判定一律照旧（判定基准换成本 Pass 实际写的附件即可，二者同尺寸）。

### 3.3 `lightingRT` 资源

| 项 | 取值 |
|----|------|
| 归属 | `RenderingSystem`，per-view（`Map<cameraName, Texture>`，与 `gbufferAlbedoFor` 同族同模式） |
| 格式 | `R16G16B16A16_SFLOAT`（HDR；与 G-Buffer 法线附件同格式——该格式在本设备已作 MRT 验证可用，零新底座） |
| 尺寸 | 与 `depthFor` 同口径（`depthWidth × depthHeight`，即交换链尺寸）、resize 换绑 |
| usage | `COLOR_ATTACHMENT \| SAMPLED`（Tonemap/Bloom 采样读） |
| 采样器 | 1x，`NEAREST` / `CLAMP_TO_EDGE`（照 `gbufferSampler` 口径；全屏 1:1 采样无插值需求） |
| 生命周期 | 惰性创建（HDR 未启用永不创建）；resize 换绑；`dispose` 走既有 `disposeAll` |
| 命名 | `{viewName}_LightingRT`（对接 `setDebugName`，RenderDoc 可辨认） |
| 创建口 | `RenderingSystem.lightingRtFor(String cameraName)` |

`PipelineContext` 的构造入参增一项（或经 `renderingSystem` 惰性取）——取法沿用 `depthFor` 的「构建期按视口取、惰性创建」既有形态。

**归属澄清**：管线档 §5.1 资源表 `lightingRT` 行现标「❌ 四期第 1 项」——本档落地后该行转 ✅；**内存别名（RHI-04）不在本期**，`lightingRT` 独立分配。

### 3.4 链展开机制（`PostProcessChain` 具体化）

**节点契约**（保留 `RenderPass` 血统，兑现管线档 §4.6「链节点实现 `RenderPass` 进入管线列表」的拍板）：

```java
/** 后处理链节点：读「链输入」写「链输出」，构建期声明 + 回调录制（RenderPass 同规）。 */
public interface PostProcessNode extends RenderPass {
    /** 链内声明：input = 上一节点输出（链首 = lightingRT），output = 本节点输出（链尾 = colorTarget） */
    void addToGraph(RenderGraphBuilder graph, PipelineContext ctx, Texture input, Texture output);
    /** 终节点标志：true = 恒置链尾（Tonemap）；其余保持注册序（2026-11-14 解耦，取代 instanceof 特例） */
    default boolean isTerminal() { return false; }
}
```

**节点归组合根持有**（2026-11-14 解耦）：节点实例由 `PostProcessSetup`（引擎侧组合根）创建、按 id 增删链；
管线只承载「链 + 展开点」，**不认识任何具体后处理类**——新增后期效果（SSAO/TAA…）只改组合根，不改管线基类。

**展开时机与位置**：由 `AbstractRenderPipeline.buildGraph` 在场景 Pass 之后统一展开（不进 `passes()` 列表——链与档位无关，两份列表共用同一链，避免 `addPass` 式双维护）：

```
场景 Pass（写 lightingRT）→ [链节点 0 → 链节点 1 → …] → Tonemap（写 colorTarget）→ overlay（DebugText 等，恒 backbuffer）
```

**中间 RT**：链内非首尾的中间结果各占一张 `{viewName}_Post{idx}`（同尺寸、同 HDR 格式、`COLOR_ATTACHMENT|SAMPLED`）；链首输入 = `lightingRT`，链尾输出 = `colorTarget`。中间 RT 数量 = 节点数 − 1（Tonemap 直出时不产生中间 RT；Bloom+Tonemap 时 Bloom 产一张）。**中间 RT 的内存别名（RHI-04）后置**，本期独立分配、按视口常驻（resize 换绑）。

**链空直通**：`isEmpty()` 时不展开任何链 Pass，`sceneColorTarget()` 即 `colorTarget()`——与 §3.1 同一条规则的两面。

**链变更 = 图缓存正常失效**（组成变化 → `structureHash` 变，RHI 18.5）——与切档同性质，无需特殊优化（管线档 §6 已立此口径）。

### 3.5 `TonemapPass`（链尾，HDR→LDR，直出显示目标）

**形态**：覆盖制全屏三角形（照 `LightingPass` 先例——自建 VB + 自采样描述符集 + push 常量曝光参数），frag 采样链输出 → tonemap 曲线 → 写 `colorTarget`。

- **清屏职责接管**：Tonemap 写 `colorTarget` 时承担原场景 Pass 那套 `CLEAR/LOAD` + `isRegionClear` + `scissor` 语义（**同一段判定逻辑从场景 Pass 平移到 Tonemap**）——多相机分屏（区域清屏）、stacking（LOAD）、单相机全屏（全屏 CLEAR）三态照旧成立；
- **曲线**：v1 实装 = **高光肩部（≤KNEE 逐像素不变、>KNEE 指数肩部压向 1.0）**——**2026-11-13 实机反馈「开了发灰、不纯」后由 ACES 近似换装**（原因与数据见 §9.5）；肩部起点 KNEE 为着色器内常量，后续接设置；
- **位置固定为链尾**：Bloom 必须读**未 tonemap** 的 HDR 缓冲，故 Tonemap 恒最后（`isTerminal()=true`，展开时稳定重排到链尾，不依赖注册顺序）。

### 3.6 `CompositePass`：v1 收窄（不实装）

管线档 §4.7 的 `CompositePass`（链尾 → backbuffer，单相机全屏可省略）在本期**不实装**：Tonemap 是恒定的链尾且全屏直出，其输出即最终显示内容，故 **Tonemap 直接写 `colorTarget`** 即可覆盖 Composite 的全部职责（含 viewport/scissor/清屏语义），省一张 RT 与一次全屏 Pass。

Composite 的回归条件：出现「链尾输出不是最终显示内容」的节点（如需与 UI/叠加体做 alpha 合成的后合成帧），届时按 §4.7 补。**此为相对管线档 §4.7 的有意收窄，列入拍板点**（§7 拍板点 3）。

### 3.7 `BloomPass`（链中，步骤二）

**形态**：阈值提取（HDR 亮度 > threshold）→ 多级降采样（1/2、1/4、1/8、1/16，各级 G 模糊）→ 上采样加权累加 → 结果与 HDR 场景色相加后交 Tonemap。

- **多级模糊用独立 RT，不用纹理 mip**（RHI 无 mip 生成链，AST-01 未开工）——各级 `{viewName}_Bloom{level}`，尺寸递减、同格式；
- 每级一张 RT × 4 级 + 提取级，**独立分配**（别名后置）；
- 输出并入 Tonemap 输入：Tonemap 读到「HDR 场景色 + Bloom」的合成结果，故 Bloom 节点输出 = 中间 RT，Tonemap 读它（而非直接读 `lightingRT`）——链的 input/output 传递天然表达此关系；
- 参数（threshold / intensity / 级数）起步常量，后置接设置。

---

## 4. 边界与交互

### 4.1 两档管线（FORWARD / HYBRID）

链与档位**正交**：后处理消费的是「场景颜色」，两档的场景颜色语义一致（`javelinLighting` 同一函数保证），故链对两档相同、由 `buildGraph` 统一展开。两档差异只在「谁来清屏 `lightingRT`」——FORWARD 是 `OpaqueForwardPass`、HYBRID 是 `LightingPass`（§3.2 已收口为同一 `sceneColorTarget()`）。

### 4.2 AA 路线：MSAA 移除、TAA 取代（2026-11-13 用户拍板）

**结论**：**AA 不走 MSAA**——MSAA 与 G-Buffer/Deferred 本质不合（MSAA 只对光栅化边缘生效、要逐采样附件，而 G-Buffer 是逐像素的）；**取 TAA 一类后处理 AA 取代**（对整幅画面生效，天然适配 Deferred）。据此：

- **P4-01 不受 MSAA 牵制**：HDR 与 AA 解耦（HDR 是色调容器、TAA 是链上 Pass，二者独立）——**MSAA 已从引擎/编辑器/设置层删除（2026-11-13），HDR 无 AA 冲突**；
- **`P4-07`（HYBRID 档 MSAA-GBuffer）作废**，不再做（`Engine_Backlog.md` §3 已改）；
- **TAA 不在本期**：它是一条**独立的链上 Pass**（见下），工作量与 P4-01 同量级，不塞进本批。

**TAA 的形态与前置（供后续立项，本节只登记）**：TAA 挂在本文的链上（对 HDR 场景色做、位于 Bloom/Tonemap **之前**），其所需件：

| 件 | 说明 |
|----|------|
| 相机投影抖动 | 每帧亚像素偏移（Halton 序列，Babylon 参考默认 8 相）；投影矩阵处加抖动项 |
| 逐像素运动矢量 | 混合实现可由 G-Buffer 世界位置 + 上一帧 viewProj **反投影重建**（不必逐物体速度缓冲）；**前向实现无 G-Buffer**，需另用速度附件或按深度重建——**两实现能力差异待拍板** |
| 历史颜色缓冲 | ping-pong 两张（同 `lightingRT` 格式/尺寸），每视口一套 |
| 重投影 + 邻域钳制 | 防拖影（ghosting）；相机/灯快速变化时的收敛策略 |
| 历史失效触发 | **切场景 / 相机跳变 / 资产热重载 / resize 必须重置历史**——编辑器切场景频繁，此点必需（否则残影） |

**代价与取舍**：TAA 会糊（通常配锐化 pass）；静态画面收敛后比 MSAA 干净，但快速运动/细几何（细杆、透明体）有拖影史。参考件 = `doc/Babylon_Rendering_Reference.md`（TAA 段）+ 外部检出 `D:\Workspaces\java\Babylon.js`（`thinTAAPostProcess.ts`）。

> **MSAA 已移除（2026-11-13 同日拍板）**：引擎/编辑器/设置层的 MSAA 全部删除（`AntialiasingMode`/`antialiasing` 字段/MSAA 附件族/`isMsaaPath`/各 Pass MSAA 分支与 3 处 resolve 调用点），RHI 多采样设施保留休眠。**画面的 AA 空窗一直持续到 TAA（P4-10）落地**——这是既定路线的结果，已记 `Engine_Backlog.md`（P4-10）与 `Engine_RenderPipeline_Design.md` §5.3。原「HDR 生效时强制 1x」的过渡边界（v0.2 §9-2 第 2 条）随之**不再需要**。

### 4.3 多相机

- **分屏 / 区域清屏（编辑器 Scene+Game 现状）**：每视口一条独立链、各自 `lightingRT`，Tonemap 带 viewport/scissor 写 `colorTarget` 区域——与现状一致；
- **stacking（`DEPTH_ONLY` / `DONT_CLEAR`）**：相机语义是「只写几何覆盖处、保留上一位画面」。HDR 链下该语义**不成立**——每视口有独立 `lightingRT`，`LOAD` 的是本视口上一帧内容而非其他相机输出；Tonemap 全屏直出会连背景一起覆盖。**建议 v1 边界**：HDR 生效时 stacking 相机按整视口 `CLEAR` 独立输出（放弃跨相机颜色叠加），记入档；若后续需要，再做「场景色带覆盖 alpha + Tonemap 透传 alpha + Composite alpha 混合」的覆盖方案（§7 拍板点 5）。

### 4.4 预览视口（`targetOverride`）

编辑器预览视口渲染到纹理时，终点 = `targetOverride`。HDR 下：场景 Pass 写 `lightingRT` → Tonemap 写 `targetOverride`，语义不变。尺寸口径与 `depthFor`/`gbufferAlbedoFor` 一致（交换链尺寸，`lightingRT` 同口径）——与 HYBRID 档 G-Buffer 对预览视口的既有一致，无新增边界。

### 4.5 图缓存与 structureHash

链组成 / 启停 / 切档均改变 Pass 列表组成 → `structureHash` 变 → 图缓存重建（正常失效，非兼容性破坏）。`lightingRT` 作为新附件进图声明（`addColorOutput` 写 + `addTextureRead` 读），依赖与屏障由 RHI 推导（含 `lightingRT` 写→读的布局转换），**引擎不手写屏障**（RHI 22.4 纪律）。**附带收益**：`lightingRT` 进图后，材质贴图/场景色的跨 Pass 布局由声明覆盖（与 RHI-10「材质贴图读未声明」是两笔账，不混淆）。

---

## 5. 实施步骤

> 逐步验收（先方案后码；每步独立可跑，不叠批）。

**步骤一：HDR 切换点 + Tonemap（结构批）**

1. `RenderingSystem.lightingRtFor` + 资源生命周期（照 `gbufferAlbedoFor` 族）；
2. `PipelineContext.sceneColorTarget()`（§3.2），四个场景 Pass 改取值来源；
3. `PostProcessChain` 具体化：`PostProcessNode` 契约 + 中间 RT 管理 + `buildGraph` 展开（§3.4）；
4. `TonemapPass` + 内置着色器 `builtin-asset-core/postprocess/tonemap.vert/.frag`（+ 伴生 `.meta`）+ 高光肩部曲线（原计划 ACES，实装换装见 §9.5）；
5. 开关：`JavelinSetting` 加后处理/HDR 字段（默认关，旧设置文件缺键保默认——序列化沿既有协议）+ 编辑器工具菜单切换（照 `tools.renderPipeline`：运行期切 + 写盘）。

**验收（步骤一）**：链空时画面与改造前**逐像素一致**（默认关）；开 Tonemap 后高光质感变化合理、不爆白；分屏两视口各自正确；`logs/error` 零 ERROR（dbg-1 校验层前置）。

**步骤二：Bloom（内容批）**——阈值提取 + 多级模糊 + 累加 + Tonemap 合成；参数起步常量。**验收**：亮部（灯/高光/天空盒亮区）溢出发光、暗部不受影响、分屏正确、帧率达标。**2026-11-13 落码、2026-11-14 用户实机验收（独立开关，§9.6）。**

---

## 6. 验收口径（汇总）

| 项 | 口径 |
|----|------|
| 直通不破 | 后处理关闭时，画面 = 现基线（逐像素比对或肉眼对照） |
| HDR 正确 | 开启后 tonemap 曲线生效、亮部有肩部压缩、无爆白截断 |
| 链插拔 | 增删链节点不破坏管线直通路径（管线档 §8 步骤三原话） |
| 分屏/预览 | 编辑器 Scene+Game 双视口各自链正确、区域不串 |
| 校验层 | `logs/error/error.log` 零 ERROR（dbg-1 已就绪） |
| 性能 | 后处理增开销可接受（Tonemap 一次全屏 Pass；Bloom 按级数） |

---

## 7. 拍板点（开工前逐项确认）

> 每项给建议，取默认则按建议落码。
>
> **2026-11-13 开工拍板（用户下令「开工」= 按建议默认）**：拍板点 1/2/3/5/6/7/8 采纳建议；拍板点 4 的 AA 路线已定（TAA 取代 MSAA）——**棋后同日进一步拍板：MSAA 不再「保留冻结」而是从引擎/编辑器/设置层直接删除**（RHI 多采样设施保留休眠）；步骤一已落码（§9），步骤二 Bloom 待步骤一验收后开工。
>
> **2026-11-13 步骤二开工拍板（用户）**：Bloom 走**独立开关**（`JavelinSetting.bloom` + 工具菜单 `tools.bloom`），与后处理（Tonemap）开关**解耦**——两者可分别开关（链四态见 §9.6）；参数（threshold/intensity/级数）起步常量，接设置后置。

| 序 | 拍板点 | 建议 |
|----|--------|------|
| 1 | HDR 启用条件 | **链非空才启用**（默认零变化） |
| 2 | `sceneColorTarget` 抽象 | 采纳（场景颜色终点与显示终点分离，改动集中） |
| 3 | `CompositePass` | **v1 不实装**，Tonemap 直出 `colorTarget`；后合成需求出现时按管线档 §4.7 补 |
| 4 | AA 路线 | **MSAA 已移除、TAA 取代（2026-11-13 用户拍板，已执行）**——引擎/编辑器/设置层 MSAA 全删、RHI 多采样设施保留休眠；AA 空窗至 TAA（P4-10）；P4-07 MSAA-GBuffer 作废 |
| 5 | stacking 相机（`DEPTH_ONLY`/`DONT_CLEAR`） | **v1 整视口 CLEAR 独立输出**，跨相机叠加记边界 |
| 6 | 实施批划分 | **步骤一（HDR+Tonemap）先验收**，Bloom 步骤二 |
| 7 | Tonemap 曲线 | ~~ACES 近似起步（曝光常量）~~ **实装换为「高光肩部」**（≤0.8 逐像素不变）——ACES 中调提亮在当前内容下只留副作用「发灰/不纯」，见 §9.5 |
| 8 | 设置面 | `JavelinSetting` 加字段（默认关）+ 工具菜单切换（照 `tools.renderPipeline`） |

---

## 8. 与邻档衔接

| 概念 | 出处 | 关系 |
|------|------|------|
| Pass 编排骨架 / 双档 / 图缓存 | `Engine_RenderPipeline_Design.md` §4.6/§4.7/§5.1/§6/§8 | 本档是其步骤三的具体化；§4.7 `CompositePass` 收窄见 §3.6 |
| 视口资源表 | 同上 §5.1 | `lightingRT` 行由「❌ 四期第 1 项」转 ✅（落地时） |
| 场景颜色来源（光照） | `Engine_Lighting_Design.md` | `LightingPass`/Forward fs 的输出即链首输入；光照数据通路本档不涉 |
| 四期排序 | `Engine_Roadmap.md` §4-1 | 本档 = 第 1 项；P4-02 SSAO 挂本档链、**TAA 亦挂本档链（替换作废的 P4-07，见 §4.2）**、P4-09 截白随天空盒环境光批 |
| 待办登记 | `Engine_Backlog.md` §3（P4-01/02/07/09） | 状态随落地更新 |
| AA 边界 | `Engine_RenderPipeline_Design.md` §5.3 | §5.3 的 MSAA 路线（2026-10-06 落地）**已移除（2026-11-13）**——AA 改走 TAA（§4.2）；RHI 多采样设施保留休眠；「不自建 blit pass、不动后处理链…FXAA 档」等口径随 MSAA 一并作废，待 TAA 立项时另立 |
| 着色器资产链 | `Engine_Skybox_Design.md` §8 / `Engine_Lighting_Design.md` §9 批次④-A | 内置 `postprocess/*.vert|.frag` 走同一条 `SpvShader` 导入链 |

---

## 9. 实装记录（后处理链，2026-11-13）

> 用户 2026-11-13 下令开工、按 §7 建议默认；本批只做步骤一（HDR 切换点 + Tonemap）。Bloom 归步骤二。

### 9.1 落点（代码实证）

| 件 | 落点 | 形态 |
|----|------|------|
| `lightingRT` / 中间 RT 资源族 | `RenderingSystem.lightingRtFor` / `postRtFor` | 每视口 `Map` + 惰性创建 + resize 换绑（`disposeAll`）+ `removeCamera` 两帧退役；RGBA16F、`COLOR_ATTACHMENT\|SAMPLED`、1x、交换链尺寸（`createHdrViewTexture`） |
| 场景颜色终点抽象 | `PipelineContext.sceneColorTarget()` / `hdrEnabled()` | HDR 生效返回 `lightingRT`，否则同 `colorTarget()`；`hdrSceneTarget` 由帧构建入参（`JavelinEngine.buildFrameGraph` 按 `RenderPipeline.usesHdrSceneTarget()` 取，与深度同类传入） |
| 链承载 | `AbstractRenderPipeline`（2026-11-13 拆分后） | 链、展开、清屏色、Pass 列表全在共用基类——`DefaultHybridRenderPipeline` / `ForwardRenderPipeline` 同享，后处理与管线实现无关 |
| 四场景 Pass 换取值来源 | `OpaqueForwardPass` / `LightingPass` / `SkyboxPass` / `TransparentForwardPass` | 一律取 `sceneColorTarget()`（PSO 颜色格式、区域清屏判定同步）；`isMsaaPath` 的尺寸基准同步 |
| 链机制 | `PostProcessNode`（extends `RenderPass`，四参 `addToGraph(graph, ctx, input, output)`）/ `PostProcessChain`（`addLast/remove/clear/nodes`） | 管线两份 Pass 列表共用同一链；Tonemap 恒置链尾（`orderedChain` 稳定重排，不依赖注册序） |
| 链展开 | `AbstractRenderPipeline.buildGraph` + `expandPostProcess` | 展开点 = **列表最后一个场景 Pass 之后、overlay 制 Pass 之前**（场景 Pass 身份集构造期登记，见 §9.2 第 1 条）；链首输入 `lightingRT`、链尾输出 `colorTarget`、中间节点输出 `postRtFor(view, i)` |
| `TonemapPass` | `core/render/pipeline/pass/TonemapPass` | 覆盖制全屏三角形（`LightingPass` 同款 VB/PSO 缓存）；set0 = 专属单纹理布局（binding0 组合采样器，按视口缓存、纹理换代重建）；无相机块/无 push；恒 CLEAR（含区域清屏/scissor），接管原场景 Pass 的清屏职责 |
| 内置着色器 | `builtin-asset-core/postprocess/tonemap.vert/.frag` + 伴生 `.meta` | 走 `SpvShaderImporter` 导入链（索引由 Gradle 扫目录自动再生成，零登记）；frag = 高光肩部曲线（`KNEE=0.8`，§9.5）；按 `gl_FragCoord` 绝对坐标 1:1 采样 |
| 设置面 | `JavelinSetting.postProcessing`（默认 `false`） | write/read 缺键保默认（关 = 直通，无迁移坑）；启动期 `JavelinEngine.createRenderPipeline` 消费 → `setPostProcessingEnabled(true)` |
| 场景材质 PSO 格式跟随 | `RenderingSystem.setHdrSceneColor`（帧构建每帧调用） | HDR 生效时把 shader Pass 名 `Forward` 的颜色附件登记为 RGBA16F 并**失效材质 PSO 缓存**（旧管线两帧退役）——附件与 PSO 格式必须一致；overlay 制 Pass（`Gizmo` 等未登记名）走回退的交换链格式，不受影响 |
| 菜单 | `tools.postProcessing`（`PostProcessingMenuCommand`，checked = 链非空） | 运行期切换 + 写盘（`Game/javelinSettings.settings`）；装配失败/写盘失败弹错误框（红线 15） |

### 9.2 实装期确定的两处边界（与方案字面有差，记明不静默）

1. **链展开点 = 最后一个场景 Pass 之后，而非列表末尾**。方案 §3.4 的示意是「场景 Pass → 链 → Tonemap → overlay」，但 `Normal` 编排里 overlay 制 Pass（Gizmo 末尾置顶、DebugLine 插 Transparent 后、GBuffer 调试视图/Pick）也在同一列表——若链尾挂到列表末尾，Tonemap 的全屏直出会把 overlay 整屏覆盖（overlay 写 `colorTarget`）。故场景 Pass 以身份集（构造期登记 `Shadow/Opaque/GBuffer/Lighting/Skybox/Transparent`）判定，链展开在最后一个场景 Pass 之后；overlay 随后画在 tonemap 后的显示目标上（深度缓冲不变，DebugLine 咬合语义保持）。
2. ~~**MSAA × HDR 不并置（过渡边界）**~~ **已作废（2026-11-13 同日）**：MSAA 已从引擎/编辑器/设置层删除（`AntialiasingMode`/`antialiasing` 字段/MSAA 附件族/`isMsaaPath`/各 Pass MSAA 分支/3 处 resolve 调用点），`PipelineContext` 不再有 MSAA 附件入参——HDR 与 AA 无冲突，原「开链时若 AA 非 OFF 弹提示」一并删除。**画面 AA 空窗至 TAA（P4-10）**。

### 9.3 未做（步骤一范围外）

`BloomPass`（步骤二）；曝光接设置；`lightingRT` 的 `setDebugName`（RHI 现无该 API，待 RHI 补）；中间 RT 内存别名（RHI-04）；sRGB 线性工作流与天空盒高亮截白真解（AST-03/P4-09）。

### 9.4 验收口径（步骤一，用户实机）

> **重点管线 = 混合实现（2026-11-13 用户方向：混合为主口径，前向「先不管」）**——验收只看混合管线（编辑器已固定）；前向实现（独立类）不经编辑器，只要求顺带不报错、不崩。

- 默认关：画面与改造前**逐像素一致**（链空零新增 RT、零行为变化）；
- 工具菜单开后（**混合管线**）：高光有肩部压缩、不爆白；
- 编辑器 Scene + Game 双视口各自 tonemap、区域不串（分屏区域清屏走 `clearRegion`）；
- Gizmo / 调试线 / G-Buffer 视图开启时不被 tonemap 覆盖（§9.2 第 1 条的验收面）；
- `logs/error/error.log` 零 ERROR（dbg-1 校验层已就绪）；
- 前向实现（非重点，`JavelinSetting.renderPipeline=FORWARD` 打包运行时）：开后画面正常、无校验层报错即可。

### 9.5 曲线换装：ACES → 高光肩部（2026-11-13 实机反馈驱动）

**起因**：步骤一开后用户实机反馈「**发灰、看起来不纯**」。

**诊断**：ACES（Narkowicz 近似）是电影级曲线，其**中调是抬亮的**——按本曲线口径 0.18→0.27（+48%）、0.5→0.62、1.0→0.80，且逐通道压缩会去掉饱和。而当前场景内容几乎全 ≤1、又是**未经 sRGB 线性工作流的显示态值**（sRGB 真解 = AST-03 / P4-09 后置）——于是「压高光」的收益拿不到，「提中调 + 去饱和」的副作用全吃下，观感即发灰、不纯。

**换装**（`tonemap.frag`）：**高光肩部**——`KNEE=0.8`，`≤KNEE` 逐像素**原样输出**（保住既有画面的对比与纯度），`>KNEE` 走指数肩部 `KNEE + (1-KNEE)(1-exp(-(x-KNEE)/(1-KNEE)))` 平滑压向 1.0（在 `KNEE` 处导数连续 = C1，永不硬截白）。取值：0.5→0.5、0.8→0.8、1.0→0.926、2.0→0.9995、4.0→1.0。

**取舍**：这是「只修过曝、不动正常画面」的最小干预口径——代价是失去电影曲线的色偏/肩部造型（当前内容用不上，等真 HDR 内容与 sRGB 工作流落地后（P4-09）可再评估换回电影曲线）。`KNEE` 与曲线形状是着色器内常量，接设置后置。

### 9.6 步骤二 Bloom（2026-11-13 落码，2026-11-14 用户实机验收）

> 用户 2026-11-13 拍板 Bloom 走**独立开关**（与后处理/Tonemap 解耦）；本批只做步骤二（Bloom 节点，参数起步常量）。

**落点（代码实证）**

| 件 | 落点 | 形态 |
|----|------|------|
| `BloomPass` | `core/render/pipeline/pass/BloomPass` | `implements PostProcessNode`；单节点内录制 8 个覆盖制全屏 Pass（1 prefilter + 3 down + 3 up + 1 composite）；PSO 缓存 key=(kind,输出格式)、描述符集 per-view 按纹理身份换代重建；push = `vec4 uvRect` |
| Bloom RT 资源族 | `RenderingSystem.bloomRtFor(cameraName, level)` + `createHdrViewTexture(w,h)` | per-view、尺寸递减（`>> (level+1)`，1/2..1/16）、RGBA16F `COLOR_ATTACHMENT\|SAMPLED`；resize / close / removeCamera 三处随族清理（同 lightingRt/postRt） |
| 内置着色器 | `builtin-asset-core/postprocess/bloom.vert` + `bloom_prefilter.frag` / `bloom_down.frag` / `bloom_up.frag` / `bloom_composite.frag`（各伴生 `.meta`） | 裸 GLSL 走 `SpvShaderImporter`（Gradle 扫目录零登记）；vert 输出 vUV，frag 按 push `uvRect` 换算源坐标；prefilter 软阈值（THRESHOLD=0.8/KNEE=0.4）、down 4-tap 帐篷、up 9-tap 帐篷 + 加性混合、composite `scene + bloom*INTENSITY(0.5)` |
| 开关 | `JavelinSetting.bloom`（默认 false）+ `AbstractRenderPipeline.setBloomEnabled/bloomEnabled` + `tools.bloom`（`BloomMenuCommand`，checked = 链含 Bloom） | 独立于 `postProcessing`（Tonemap）；启动期 `JavelinEngine.createRenderPipeline` 消费；运行期切 + 写盘（失败弹框，红线 15） |
| 开关拆分 | `setPostProcessingEnabled(false)` 由「清空链」改为**只摘 Tonemap**；`postProcessingEnabled()` 语义收为「链含 Tonemap」；`usesHdrSceneTarget()` 仍为「链非空」 | 两开关互不误伤 |

**链四态**

| 后处理(Tonemap) | Bloom | 链 | 结果 |
|---|---|---|---|
| 关 | 关 | `[]` | 直通，零新增 RT（现基线） |
| 开 | 关 | `[Tonemap]` | 步骤一现状 |
| 关 | 开 | `[Bloom]` | 场景色 + Bloom 直写显示目标（无 tonemap，>1 截断的边界） |
| 开 | 开 | `[Bloom, Tonemap]` | 完整 HDR 链（`orderedChain` 保证 Bloom 在 Tonemap 前） |

**实装期确定的边界（与方案字面有差，记明不静默）**

1. **跨视口/多级坐标用 push 常量 `uvRect` + 顶点 `vUV`**，而非 `gl_FragCoord/textureSize`（Tonemap 口径）。原因：Bloom 各级目标 RT 尺寸递减（1/2..1/16），且分屏视口只占帧缓冲一区域——`gl_FragCoord` 与源纹理不在同一坐标空间。取 `uvRect = (viewportX/fullW, viewportY/fullH, viewportW/fullW, viewportH/fullH)`（源 = 全分辨率场景色），片元 `uv = uvRect.xy + vUV*uvRect.zw`；各 Pass 的 viewport/scissor 随级数缩放 `>> (level+1)`。该式对任一级、任视口区域一致成立（各 Bloom RT 的「内容区域」归一化坐标恒等于屏幕归一化坐标）。
2. **Bloom 是单个链节点、内部录制 8 个 Pass**——`PostProcessNode` 契约是单节点「读 input 写 output」，Bloom 的中间级全部写它自管的 Bloom RT（`bloomRtFor`），对链只暴露「input = 场景色、output = 场景色 + Bloom」。

**未做（步骤二范围外）**：参数（threshold/intensity/级数）接设置；Bloom 中间 RT 内存别名（RHI-04）；高亮截白真解（P4-09）；AA（TAA，P4-10）。

**验收口径（用户实机；重点混合管线，前向先不管）**

- 两个开关四态各自正确，关时画面**逐像素不变**；
- Bloom 开后：亮部（灯/高光/天空盒亮区）溢出发光、暗部不受影响，无爆白；
- Scene + Game 双视口各自正确、不串；
- 帧率达标（Bloom 增 8 个半分辨率以下全屏 Pass）；
- `logs/error/error.log` 零 ERROR（dbg-1 校验层已就绪）。

### 9.7 管线与后处理解耦（2026-11-14，零行为变化）

**起因**：步骤二落地后复核「Tonemap/Bloom 是否有必要与管线强绑定」——结论：链的**承载与展开点**必须归管线（每视口装配 + 插入点），但管线**认识具体节点类**（`new TonemapPass`/`new BloomPass`、`orderedChain` 的 `instanceof TonemapPass`、每个效果一对开关方法）不是必需的；后者每加一个后期效果都要改管线基类。

**做法**：
- `RenderPipeline` 接口：删 `setPostProcessingEnabled`/`postProcessingEnabled`/`setBloomEnabled`/`bloomEnabled`，改暴露 `postProcess()`（链）；接口回归纯结构面。
- `AbstractRenderPipeline`：删 `tonemap`/`bloom` 字段与两对开关方法、删对具体类的 import；`orderedChain` 的 `instanceof TonemapPass` → `PostProcessNode.isTerminal()`；`setClearColor` 不再逐节点转发。
- `PostProcessNode`：+ `isTerminal()`（终节点恒置链尾）+ `setClearColor()`（默认 no-op）。
- 新增 `PostProcessSetup`（core 组合根）：持有节点实例、按 id（`tonemap`/`bloom`）惰性创建、`setEnabled`/`isEnabled` 增删链（**`isEnabled` 不触发创建**——菜单 checked 高频求值不得装载着色器）、下发清屏色。
- `JavelinEngine`：`createRenderPipeline` 建 `PostProcessSetup` 并按设置装配链；暴露 `postProcessSetup()`（编辑器经继承的访问面使用）。
- 两个工具菜单命令：`pipeline.setXxxEnabled` → `setup.setEnabled(pipeline.postProcess(), id, next)`，checked = `setup.isEnabled(chain, id)`。

**边界**：链展开 / HDR（链非空）留在管线（本质耦合）；**未做插件式注册表**（就两节点，`PostProcessSetup` 里显式创建足够）。

### 9.8 参数可配：着色器常量 → push 常量（2026-11-14）

**起因**：步骤二参数（Bloom 阈值/软膝/强度、Tonemap 肩部）为着色器内 `const`，无法运行期调；「项目设置」窗口（`Editor_ProjectSettings_Design.md`）需要它们可配且即时生效。

**做法**：
- 新增 `PostProcessSettings`（core `@Reflective Serializable`）：`bloomThreshold`/`bloomKnee`/`bloomIntensity`/`tonemapKnee`——**独立成对象**是因 `InputCapsule` 无「键存在」判定、浮点缺键恒 0，须走 `readSerializable` 的 null 判定保默认（照 backgroundColor 的 Color 先例）。挂 `JavelinSetting.postProcessSettings`。
- 着色器：`bloom_prefilter.frag`/`bloom_composite.frag` 去 `const`、读 `push_constant` 的 `params`（x=threshold / y=knee / z=intensity；四 kind 共用块）；`tonemap.frag` 加 push 块（x=knee）。
- Pass：`BloomPass` push 16B→32B（`uvRect + params`，四 kind 共用）+ `setParams(...)`；`TonemapPass` push 0→16B（`vec4 params`）+ `setKnee(...)`。
- `PostProcessSetup`：持参数 + `setBloomParams`/`setTonemapKnee`（已创建即时、后创建补发——照 `setClearColor`）；`JavelinEngine.createRenderPipeline` 启动期从 `JavelinSetting` 下发。
- 编辑器「项目设置」窗口即时改（见 `Editor_ProjectSettings_Design.md`）；编辑器启动读项目文件（`ProjectLoadingFrame.loadEditorSetting`）。

**边界**：级数（4）仍为常量（改级数 = 改 Pass 数，另议）；参数变更走 push 常量（录制期数据，不改 Pass 组成、无图缓存失效）。

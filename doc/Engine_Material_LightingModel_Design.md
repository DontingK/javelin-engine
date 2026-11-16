# 材质光照模型（LightingModel）设计

> 版本：v0.3（2026-11-16，**值名纠偏：`Lit` → `Lambert`，单一封闭轴**；落码完成，待用户实机验收）
> v0.3 变更（2026-11-16，用户两轮追问后纠偏）：①「枚举叫 Lit 合适吗」——`Lit` 是状态不是模型，且与头名 `LightingModel` 的承诺不符；②「PBR+unlit 也不对吧」——正交两轴（`Lighting` + `ShadingModel`）会造出无效组合，被否决。定稿 = **单一封闭轴**：`LightingModel` 保持头名，值改为 `Lambert`（缺省）/ `Unlit`（"忽略光的模型"），后置 `PBR`/`Toon` 同轴追加；`LIT` 名字只在未编译/未提交的中间态存在过，无存量资产，不保留 `Lit` 别名。注入层随之从布尔判断改为 `lightingStageBlocks(model)` 的 `switch`（无 default，漏值即编译失败）。
> 范围：`.shader` 顶层新增 `LightingModel` 语义头（`Lambert` / `Unlit`）+ 导入/数据/注入/分桶/管线挂点。
> 目的：兑现四期施工序 ⑤（`Engine_Backlog.md` P4-08）——让"不吃光照"的材质有表达手段，替换原「Pass 名兼职」方案。

## 1. 问题（P4-08 现状）

HYBRID 档所有不透明几何走 `GBuffer → LightingPass` 乘光照，`LightingPass` 是**覆盖制全屏、无深度附件**。后果：

- 内置 `default.shader`（`DefaultMaterial` 紫色错误兜底）在**无灯场景全黑**——错误态本应"直读 albedo"，却因被乘光照而看不见（违背红线 15 错误显性化）；
- 任何"不该受光"的材质（自发光/遮罩/调试几何/将来 UI 面片）没有声明手段，只能在 fs 里把光照结果丢掉，像素却仍被 `LightingPass` 覆盖乘一次；
- FORWARD 档同理：这类材质会被塞进前向受光桶。

**拍板（2026-11-16 用户）**：不在 Pass 名上兼职，也不扩 `SurfaceType`，而是给 `.shader` 一个**一等语义头 `LightingModel`**，粒度 = shader 级（材质级开关本质是变体系统，后置）。

## 2. 语法与解析

`.shader` 顶层（`Properties` / `Pass` 同级）新增一行，可选、缺省 `Lambert`：

```
Shader "MyShader"
{
    LightingModel Lambert     // Lambert | Unlit，大小写不敏感；缺省 Lambert
    Properties { ... }
    Pass Forward { ... }
}
```

- **单一封闭轴（2026-11-16 用户纠偏后定稿）**：值 = 表面最终色怎么算，互斥且穷尽；`Unlit` 是**模型值之一**（“忽略光的模型”），不是与公式正交的开关——因此不存在「PBR + Unlit」这类无效组合，也就不需要合法组合表/校验/默认裁决；
- 解析点 = `ShaderParser.parseShader()` 顶层关键字 switch（`Properties` / `LightingModel` / `Pass` 三 case）；值不在枚举内**直接抛 `ShaderParseException`**（带行号，红线 15 显性化）；旧写法 `Lit` **不保留别名**（本批从未编译/提交，无存量资产）；
- `ParsedShader` 加字段带到 `ShaderImporter` → `Shader.Builder.lightingModel(...)`；
- **旧资产零迁移**：无该行 = `Lambert`，现有全部 `.shader` 行为不变。

## 3. 数据面

`Shader`（`javelin-core/.../render/material/Shader.java`）加一个字段：

```java
/** 光照模型（.shader 顶层 LightingModel；缺键/缺省 = LAMBERT） */
private LightingModel lightingModel = LightingModel.LAMBERT;
```

- 新枚举 `render/material/LightingModel`：`LAMBERT("ins.enum.lightingmodel.lambert")` / `UNLIT("ins.enum.lightingmodel.unlit")`（`InsEnumLabel` 双语，供 `.shader` 诊断台只读展示）；
- `write` 增 `output.write("lightingModel", lightingModel)`、`read` 增 `input.readEnum("lightingModel", LightingModel.class, LightingModel.LAMBERT)`（缺键 = LAMBERT，旧产物/旧文本直接加载）；
- Builder 增 `lightingModel(...)` 入口 + `build()` 装配（红线 3：不允许不确定的 API，落码前用 IDEA MCP 复核 `InputCapsule.readEnum` 现签名）；
- 字段**不加 `@InsField`**：模型来自 shader、不可在材质面板改（材质侧只读展示）。

## 4. 导入期注入分支

`ShaderInjector.inject(...)`（材质 Pass 注入）现在无条件拼 `ENGINE_BLOCKS + LIGHTING_BLOCKS + RECEIVE_SHADOW_PUSH + 材质声明`。改为按模型取块集（`switch` 无 default，新增值漏处理即编译失败）：

| 段 | LAMBERT | UNLIT |
|---|---|---|
| `ENGINE_BLOCKS`（相机块 + push 块） | 注入 | **必须注入**（unlit 也要 `cam.viewProj` / `pc.model`） |
| 材质属性声明 | 注入 | 注入 |
| `LIGHTING_BLOCKS`（LightingParamsBlock / lightList / `javelinLighting` 函数） | 注入 | **跳过** |
| `RECEIVE_SHADOW_PUSH`（receiveShadow 标志） | 注入 | **跳过**（unlit 不接收光照，也就无阴影接收） |

- 注入器签名加 `LightingModel` 参数；**生产调用点仅 `ShaderImporter`（vs/fs 两行）**，另 `javelin-editor` 测试 5 处需同步改签名；`injectLightingStage`（全屏 Pass）是独立入口、不吃该参数；
- **全屏光照 Pass 的 `//@javelinLighting` 标记注入不变**（`injectLightingStage` 与材质模型无关）；
- **改注入 = 强制重导**（Pitfalls #29 照旧）：本批落码后所有内置/自带 `.shader` 需 reimport。

## 5. 收集与分桶

`RenderList` 加一个桶（`unlitOpaque`），收集口径（`RenderingSystem.collectFrame`）：

| shader 模型 | surfaceType | 归桶 | HYBRID 绘制 | FORWARD 绘制 |
|---|---|---|---|---|
| `LAMBERT` | OPAQUE | `passes("GBuffer")`（现状） | GBufferPass | 前向主通道 |
| `LAMBERT` | TRANSPARENT | `transparent` + Forward 桶（现状） | TransparentForwardPass | 同 |
| `UNLIT` | OPAQUE | **`unlitOpaque`（新）** | **UnlitForwardPass（新）** | 同 |
| `UNLIT` | TRANSPARENT | `transparent`（排序不变） | TransparentForwardPass（不注入光照，天然直出） | 同 |

- **HYBRID 档收集期 WARN 口径修正**：`LightingModel=UNLIT` 的不透明物**不再**报"未声明 `Pass GBuffer`"（它本就不该进 G-Buffer）；`LAMBERT` 不透明缺 `Pass GBuffer` 仍 WARN（原口径保留）；
- Unlit shader 仍声明 `Pass Forward` 作为其唯一可执行 Pass（状态命令自管：Blend Off / ZWrite On / ZTest LEqual），不需要 `Pass GBuffer`。

## 6. 管线挂点与深度契约

新 `UnlitForwardPass`（`render/pipeline/pass`，与 `TransparentForwardPass` 同构骨架），**覆盖制单画**：

- **实例化一处、两档共用**：内部行为与档位无关（不读光照段、不声明 set1 光照缓冲依赖）；
- **HYBRID 场景 Pass 序**（`DefaultHybridRenderPipeline` 现为 `Shadow→GBuffer→Lighting→Skybox→Transparent`）：

```
Shadow → GBuffer → Lighting → Skybox → Unlit(新) → Transparent
```

  **必须在 `LightingPass` 之后**：`LightingPass` 是覆盖制全屏写整个颜色附件，画在它之前会被整屏盖掉；
- **FORWARD 场景 Pass 序**（现为 `Shadow→OpaqueForward→Skybox→Transparent`）：

```
Shadow → OpaqueForward → Skybox → Unlit(同一实例) → Transparent
```

  与 HYBRID 的相对位置一致（**天空盒之后、透明之前**）；不透明之间次序靠深度测试保证，插在透明之前是硬约束；
- **深度契约**：颜色 `LOAD`、深度 `LOAD` + **ZTest LEqual + ZWrite On**——unlit 不透明既被 lit 几何正确遮挡，也参与遮挡后续透明物；
- 视口 scissor / 空桶零绘制 / set0 相机块与材质 set1 绑定照抄 `TransparentForwardPass` 现有实现（不另立新约定）；
- 天空盒在其之前绘制，清屏与深度由 `SkyboxPass` 既有口径负责，`Unlit` 不改这一层。

## 7. 内置资产与编辑器

- `default.shader` 标 `LightingModel Unlit`：错误紫材质**无灯也可见**（错误显性化归位）；`Lit.shader` 缺省即 `LAMBERT`，不动。
- 材质面板**不加字段**；`.shader` 诊断台展示只读行「光照模型 = Lambert/Unlit」（i18n 三键）；
- 新建材质模板（`CreateMaterialMenuCommand` 用 `Lit`）不变。

## 8. 已知边界（本批不做，登记待办）

1. **TAA 重投影**：TAA 当前帧运动矢量取自 G-Buffer 世界位置，unlit 不写 G-Buffer → 其像素世界位置无效标志（w=0）。**落码时必须验证**：TAA 对无效标志像素应"取当前帧直出、不累积"（若现实现未判，会出现 unlit 物体拖影）——列为验收项，不合格则本批补 `TaaPass` 的无效标志分支。
   - **核验结论（2026-11-16 静态读码，成立）**：`taa.frag:76-80` 读 `gbufferWorldPos` 后 `if (tp.extra.x < 0.5 || wp.w < 0.5) { outColor = vec4(current, 1.0); return; }` —— 无效世界位置即直出当前帧、不累积；`GBufferPass` 每帧把 worldPos 清为 `(0,0,0,0)`（`CLEAR_WORLD_POS`），unlit 像素 w 恒 0。故 **无拖影**。代价：unlit 像素**无 AA**（不累积 → 不收敛），且仍吃相机级 jitter（8 相 1px 抖）→ 边缘细节在帧间有轻微"爬行"感，属已知边界（要治需给 unlit 也补 G-Buffer 世界位置，后置）。
2. **SSAO**：unlit 不进 G-Buffer → 既不吃 AO 也不算遮挡物（属于"够不着"的正确行为，登记口径即可）。
   - **核验结论（2026-11-16 静态读码）**：`ssao.frag:48-53` `wp.w < 0.5` → `outAO = 1.0`（不压暗）；`ssao.frag:83-86` 样本落在 w<0.5 像素时 `continue`（unlit 不当遮挡源）—— 即 unlit 像素 AO 恒 1、其邻居采样时跳过它。行为符合上一条口径，无需改动。
3. **阴影**：unlit 仍参与**投射**（`castShadow` 位；ShadowPass 走引擎自有 depth-only 管线，与材质无关），但**不接收**阴影（不注入光照段）。
4. **材质级开关 / 着色器变体**：不在本批（shader 级已够用）。
5. **扩值规则（单一封闭轴）**：`LightingModel` 是这张表唯一的光照轴——后置模型（`PBR` / `Toon`）以新值追加，**不是**与「受光/不受光」正交的第二根轴（否则会出现「PBR + Unlit」这类无效组合，得再引入合法组合表与默认裁决）。追加值的两处改动点固定：① `ShaderParser.parseLightingModel` 的 case 表；② `ShaderInjector.lightingStageBlocks` 的 switch 块集（无 default，漏值即编译失败）。仍受光者共用 `opaque` 桶 + G-Buffer + LightingPass，**Pass 结构不改**；若新模型需要更多 G-Buffer 通道（PBR 的 roughness/metalness），那是独立的布局批次（`GBufferPass.COLOR_FORMATS` + `Engine_Uniform_Keys.md` 单点），不属扩值。

## 9. 验收清单（已落码，待用户实机执行）

- `Lambert` 资产/场景**逐像素不变**（含双视口、Gizmo/调试线/调试视图 overlay 不受影响）；
- `default.shader` 紫材质在**无灯场景**可见（不再是黑）；
- `Unlit` 材质（内置样例一件）+ `LAMBERT` 材质同场景：unlit 不吃光照、被 lit 物体正确遮挡、ZWrite 后透明物被其遮挡；
- HYBRID 与 FORWARD 两档 unlit 外观一致；
- 收集期日志：unlit 不透明无 WARN、lit 不透明缺 GBuffer 仍 WARN；
- TAA 开/关下 unlit 物体无拖影（§8-1）；
- 拾取（PickPass）对 unlit 物体正常（拾取为覆盖制独立 Pass，不依赖材质 Pass 声明）；
- `logs/error` 零 ERROR；旧场景/旧 `.shader` 无重导需求（缺省 Lambert）。

## 10. 拍板点（2026-11-16 用户「都认可」，已按此落码）

1. **粒度 = shader 级**（材质级开关不做）——用户 2026-11-16 认可；
2. **绘制顺序 = Lighting 之后、Skybox 之后、Transparent 之前**（深度 LOAD/ZWrite On）；
3. **`default.shader` 改标 `Unlit`** 是否认可（改动 = 错误材质从"黑"变"可见紫"）；
4. **UNLIT + TRANSPARENT 归透明桶**（不再单独建 unlit 透明 Pass）。

## 11. 实装记录（2026-11-16，未编译未提交——红线 1/13）

**core 数据面**

- 新 `core/render/material/LightingModel.java`：`LAMBERT`/`UNLIT`（`InsEnumLabel`，键 `ins.enum.lightingmodel.lambert/unlit`）；
- `Shader`：字段 `lightingModel`（默认 `LAMBERT`）+ 私有构造加参 + `Builder.lightingModel(...)` + `write`/`read`（`readEnum` 缺键兜底 `LAMBERT`，旧产物零迁移）+ `getLightingModel()`。

**收集与分桶**

- `RenderList`：新 `unlitOpaque` 列表 + `addUnlitOpaque` + `unlitOpaque()`；
- `RenderingSystem.collectList`：`shader.getLightingModel()==UNLIT` 时不透明进 `unlitOpaque`（不进 `opaque`）、透明照旧归透明桶；**UNLIT 且声明 `Pass GBuffer` 的 pass 直接跳过不入桶**（即便 shader 残留 GBuffer 段也不进 G-Buffer 绘制）；`unlitOpaque` 按 `bucketKey` 排序。

**管线挂点**

- 新 `pipeline/pass/UnlitForwardPass`（`name()="Unlit"`）：color/depth 均 `LOAD`、set0 `frameSet`（uboOffset）+ set1 `lightingSet`（lightingOffset）、消费 `view.list().unlitOpaque()`、复用 `AbstractForwardPass.entryPipeline/drawObject`（PSO 取材质 `Forward` pass）；`viewStorages` + `shadowMapFor` 依赖照 Transparent 口径声明（保结构稳定）；
- `DefaultHybridRenderPipeline`：`Shadow→GBuffer→Lighting→Skybox→Unlit→Transparent`；
- `ForwardRenderPipeline`：`Shadow→OpaqueForward→Skybox→Unlit→Transparent`（同相对位置）。

**导入链**

- `ShaderParser`：顶层 `case "LightingModel" -> parseLightingModel()`（`Lambert`/`Unlit` 大小写不敏感，非法抛 `shader.parse.lightingModelUnknown` 带行号；旧写法 `Lit` 已不合法）；`ParsedShader` 加 `lightingModel` 字段/访问器（缺省 `LAMBERT`）；
- `ShaderInjector.inject` 加 `LightingModel` 形参：改为 `lightingStageBlocks(model)` 私有 `switch`（无 default，新增值漏处理即编译失败）——`LAMBERT` 注入 `LIGHTING_BLOCKS` + `RECEIVE_SHADOW_PUSH`、`UNLIT` 空；`ENGINE_BLOCKS`（相机块 + push）与材质声明两档都注入；`injectLightingStage`（全屏光照独立入口）未动；
- `ShaderImporter.buildShader`：`builder.lightingModel(parsed.lightingModel())` + 两处 `inject(...)` 传模型。

**内置资产 / i18n / 测试**

- `builtin-asset-core/shader/default.shader` 加 `LightingModel Unlit`（错误紫材质无灯也可见）；
- 语言包双语新增 `shader.parse.lightingModelUnknown`、`ins.enum.lightingmodel.lambert/unlit`，`shader.parse.unknownTopBlock` 文案补 `LightingModel`；
- 单测：`ShaderParserTest` 5 处 `inject` 调用改签名 + 新增 `parseLightingModelHeader`（缺省 Lambert / 显式 Lambert / Unlit / 小写 / 非法值 + 旧写法 `Lit` 均抛）与 `injectUnlitSkipsLightingStage`（UNLIT 无 `LightingParamsBlock`/`javelinLighting`/`javelinReceiveShadow`、仍有 `CameraBlock`；对照 Lambert 全量）；`ShaderImporterTest` 2 处调用改签名。

**未做（登记）**

- `.shader` 诊断台「光照模型」只读行（§7 提到）——本批未动检查器，登记后置；
- `.shader` 样例资产（一件 Unlit 示例）——验收时可用新建材质改 shader 头替代；
- `Lit.shader` 不显式写 `LightingModel`（缺省即 `Lambert`，零改动）。

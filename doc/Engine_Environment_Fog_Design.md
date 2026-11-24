# 环境雾（REND-12）落码方案

> 版本：v0.0 暂缓（2026-11-25 用户拍板：L5 曝光/色彩分级/DOF/体积雾与环境雾**均属后期**，主线转光追。本档留作方案记录，未落码、不开工）
> 原：v0.1（2026-11-25，方案件，待拍板）
> 定位：`Engine_Backlog.md` REND-12（UI Pass 组 + 环境雾）中的**环境雾**部分；L5 后处理段首笔。
> 关联：`Engine_Roadmap.md` §4.1 L5 行（REND-18 体积雾与 REND-12 环境雾同源，**雾字段先落**）。

---

## 1. 目标与取舍

L5 五项里，**曝光/测光卡在 RHI-14**（自动测光要读降采样亮度，引擎还没有 mip 生成链），色彩分级要 BRDF/PBR 口径先统一，DOF 要深度。**环境雾是唯一当前无硬前置、且能立刻改善画面纵深的一项**——远处物体不糊在同一个亮度里，场景有「空气感」。

本批只做**均质距离雾（指数平方）**：颜色 + 密度两个参数，按视空间距离混合。体积雾、高度雾、光照散射都后置（体积雾本身要 raymarch，与 RHI-14 之后的降噪链同批更合理）。

## 2. 关键事实

| 事实 | 出处 |
|------|------|
| `Environment` 组件已有 skybox + `ambientMode`（OFF/CONSTANT/DDGI）+ 嵌套 `AmbientSetting`（经 `@InsFieldGroup` 条件内联） | `Environment.java`、`AmbientSetting.java` |
| 嵌套对象的 Inspector 内联**依赖 `@InsFieldGroup` 的 selector 枚举**（`AmbientSetting` 就是这么接的），直接挂一个嵌套对象能否展开未经验证 | 同上 |
| `core/settings/ShadowSettings` 这套「注解生成项目设置面板」路径**已验证可用**（本轮 cutout/PCSS/C2 都走的它） | `ShadowSettings`、设置面板 |
| `lighting.frag` 里已有 `worldPos` 与 `viewPos`，视距离 = `-viewPos.z` 现成 | `lighting.frag` 第 18~22 行 |
| 相机深度附件仍只 `DEPTH_STENCIL_ATTACHMENT`（不可采样）；后处理链也没有深度输入 | `RenderingSystem.depthFor` |
| 天空盒 Pass 在光照之后覆盖背景像素（光照 Pass 对背景 `discard`） | `lighting.frag` 第 15~17 行、SkyboxPass |
| `cam.nearZ/farZ` 已在 `LightingParamsBlock.zParams`（x=near / y=far） | `ShaderInjector` 第 217 行 |

**结论**：雾可以完全在 `lighting.frag` 内做（视距离现成、无深度采样、无新 binding），代价与接触阴影同款——**仅 HYBRID 档生效**。Forward 路要么不做雾，要么改走「独立后处理 Pass + 深度附件加 `SAMPLED`」，那是另一笔的钱。

## 3. 方案

在 `lighting.frag` 算出最终颜色后：

```
dist = -viewPos.z                       // 视空间距离
fogFactor = 1 - exp(-(density * dist)^2)      // 指数平方（Unity 口径）
outColor.rgb = mix(outColor.rgb, fogColor, clamp(fogFactor, 0, 1))
```

- 背景像素本来就在本 Pass `discard`，由天空盒覆盖 → **天空盒天然不受雾影响**，不需要额外判断。
- 参数走 `LightingParamsBlock` 尾部新增 `vec4 fogParams`（x=开关、y=密度、z=预留、w=预留）+ `vec4 fogColor`？——两段合并成一段：`vec4 fogParams`（x 开关 / y 密度 / z..w 颜色 rgb 压不进），故用 **两个 vec4**：`fogParams`（开关/密度/预留/预留）+ `fogColor`（rgb/a）。块实需 672 → **704**，`LIGHTING_STRIDE` 768 仍不变（余量 64B）。
- 关档：`fogParams.x < 0.5` 时早退（一次 uniform 读，零像素成本）。

## 4. 五点拍板（默认值已给，回「嗯」= 全按默认）

| # | 决策点 | 默认（推荐） | 备选 |
|---|--------|--------------|------|
| **P1** | 参数载体 | **项目设置 `core/settings/FogSettings`**（`enabled` / `color` / `density 0~0.2`，注解生成面板——已验证路径） | `Environment` 场景组件嵌套子组（每场景独立配置，但 Inspector 嵌套展开未验证，有返工风险） |
| **P2** | 施加点 | **`lighting.frag` 内**（HYBRID 限定，复用 `viewPos`；不读深度、不加 binding） | 独立后处理 Pass（需深度附件 `SAMPLED` + FogPass 资源，Forward 也能雾，但多一套前置） |
| **P3** | 雾模型 | **指数平方** `1 - exp(-(density*dist)^2)`，dist 取 `-viewPos.z`（不做高度衰减） | 线性 / 指数 / 加高度雾（高度雾要世界 y，参数面翻倍） |
| **P4** | 天空盒 | **不雾化**（靠光照 Pass 的 `discard` + 天空盒后覆盖，天然成立，不加判断） | 雾化到雾色（会让远处天空整片变灰，需额外分支） |
| **P5** | 批内范围 | **仅均质距离雾**；体积雾 / 高度雾 / 散射后置（等 RHI-14 的降采样链） | 同批做体积雾（raymarch + 降噪，量级是这笔的 5 倍以上） |

## 5. 风险与后置

- **R1 近处整体发灰**：指数平方在中近距离就开始起雾，密度缺省给 **0.02**（约 50m 处 factor ≈ 0.63）而不是 0.05；实机先小后调。
- **R2 与 DDGI/接触阴影的叠加顺序**：雾必须在**接触阴影系数之后**施加（雾是距离项，不该被短距遮挡影响），落码时放在 `javelinLightingEx` 调用之后。
- **R3 Forward 路无雾**：与接触阴影同款的不一致（HYBRID 有、Forward 没有）。若用户日后要求两路一致，雾应改成后处理 Pass（那时与「深度附件 `SAMPLED`+`binding12`」一并做）。
- **后置**：体积雾（raymarch + 降噪 + 光照散射）、高度雾、场景级雾参数（若项目设置粒度不够）。

## 6. 自验口径（未编译，交用户）

1. `enabled = false`：画面与当前状态**逐像素一致**（关档零残留）。
2. `enabled = true`、density 0.02：远景明显向雾色过渡、近景基本不受影响；天空盒保持原样（不整片发灰）。
3. 密度拉到 0.1：能看出雾是「按视距」而不是「按世界高度」——抬头看向远处屋顶与低头看地面，同距离处雾浓度一致。
4. 转动相机时雾随距离连续变化，无接缝、无随级联/像素跳变。

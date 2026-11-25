# 阴影镂空支持设计（Alpha Cutout Shadow，REND-21-A）

> v0.3（2026-11-25 用户实机验收通过：`_AlphaCutoff = 0.5` 配 alpha 贴图时表面与地面影子同形镂空、阈值两侧（α 0.40 裁掉 / 0.65 保留）正确，回 0 恢复实心无残留；验收素材 = `test/test/Assets/alpha_cutoff_1024.png`，见 §9.1）｜v0.2（2026-11-24 落码：§4 五点按推荐默认拍板并实装，见 §9 落码记录；未编译待验收）｜v0.1（2026-11-24）｜**方案件 → 落码件**。路线坐标 = `Engine_Roadmap.md` §4.1 **L4 阴影补强首笔**（原文：cutout/alpha-test 阴影，「镂空与植被投影有孔洞」，成本最低、素材相关、优先）。
>
> 一句话缺口：`shadow.frag` 现在只有 `void main(){}`——**零 alpha 采样**，所以任何镂空/植被/铁丝网都投出一整块实心影子；而主画面（Forward / GBuffer）是带贴图 alpha 的，两边天然对不上。

## 1. 现状核实（2026-11-24）

| 事实 | 出处 |
|---|---|
| 阴影 fs 为空体，depth-only，无颜色附件 | `builtin-asset-core/shadow/shadow.frag`（8 行） |
| 阴影 PSO **零描述符集**，push 128B = `lightViewProj@0(64) + model@64` | `ShadowPass`（`SHADOW_PUSH_SIZE=128`、`GraphicsPipelineDesc` 传 `List.of()` 布局） |
| 阴影 caster 集合 = 不透明 + `castShadow`（透明物本来就不投影） | `RenderList.shadowCasters()` + `DdgiVoxelizer` 同口径 |
| 材质面型只有 `OPAQUE / TRANSPARENT`，**没有 CUTOUT** | `SurfaceType` |
| 材质贴图按 **shader 属性名**（`_MainTex`）挂进 set2；`MaterialBlock` 只打包 shader 属性，**不含面型** | `RenderingSystem.createMaterialResources` / `MaterialBlock.pack` |
| 贴图→GPU 纹理可复用 `TextureUpload.upload(device, Texture2D)` | 同上（`materialSet` 内调用） |
| Forward 与 GBuffer 两条路的 albedo 都是 `texture(_MainTex, vUV)`，无 discard | `Lit.shader` 第 45 / 90 行 |

## 2. 目标与验收判据

- 目标：材质声明 alpha 裁剪后，**主画面与阴影**同时按同一 alpha 阈值裁剪；未声明的材质零行为变化。
- 判据：①镂空物投影出现孔洞、且孔洞与主画面镂空位置一致；②普通不透明物影子与现状像素级一致；③`error.log` 零新增 VVL；④阴影 Pass 帧时间增量可忽略（<0.2ms 量级，实测报数）。

## 3. 三条语义载体（§4 P1 待选）

| 案 | 形态 | 代价 |
|---|---|---|
| **A** | 扩 `SurfaceType.CUTOUT` + 材质属性 `_AlphaCutoff` | 动枚举 → 分桶、PSO 覆盖（`pipelineFor` 的面型分支）、检查器下拉、序列化兼容，面最大 |
| **B（推荐）** | **只加 shader 属性 `_AlphaCutoff`（`Range(0,1)`，缺省 0 = 关）**，主渲染注入段与阴影侧都读它 | 面型不动、分桶不动（cutout 仍在不透明桶，利于 ZWrite 与阴影）；unity 的「Alpha Clip」也是 shader 侧概念 |
| **C** | shader 关键字（`#define ALPHA_CLIP`）→ 每材质出变体 | 要引入关键字系统（引擎现在没有），批次外扩 |

推荐 **B**：语义放在 shader 属性上（跟 unity 的 `_Cutoff` 同位），用「cutoff > 0 即启用」做开关，避免动面型枚举。

## 4. 待拍板五点

**v0.2 拍板结论（2026-11-24，用户「动手」= 全默认）**：P1 = **B（`_AlphaCutoff` 属性，0 = 关）**；P2 = **push 128→144B**（`Device.maxPushConstantsSize()` 只读口 + 构造期超限抛错）；P3 = **固定 `_MainTex`**；P4 = **同批开**（Forward + GBuffer 两条 fs）；P5 = **是（L4 首笔）**。

| 编号 | 决策点 | 推荐默认 | 备选 |
|---|---|---|---|
| **P1** | 语义载体 | **B：`_AlphaCutoff` 属性**（0 = 关）✅ 拍板 | A：`SurfaceType.CUTOUT`；C：关键字变体 |
| **P2** | 阴影侧 cutoff 传输 | **push 从 128B 扩到 144B**（末 16B = `cutoff`, `hasAlbedoTex`），设备上限查询后 <144 抛错 ✅ 拍板 | 影子专 set 内挂 16B UBO（多一次 per-material buffer 写） |
| **P3** | 贴图槽名 | **固定 `_MainTex`**；未赋 → 1×1 白图 ✅ 拍板 | 由 shader 首个 TEXTURE_2D 属性自动选槽 |
| **P4** | 主渲染侧是否同批开 | **同批**（Forward 与 GBuffer 两条注入段都加 `discard`）✅ 拍板 | 只做阴影，主渲染后置 |
| **P5** | 是否作为 L4 首笔 | **是** ✅ 拍板 | 先做 L3 关账 / REND-21-B 逐级联剔除 |

## 5. 落码形态（按 B + 同批开）

### 5.1 主渲染侧（`ShaderInjector`）

- `Lit.shader` Properties 增一行 `_AlphaCutoff ("Alpha Cutoff", Range(0, 1)) = 0`；`default.shader` 不动（错误紫不该被裁）。
- 注入段在 albedo 取回处插入统一函数 `javelinAlphaClip(vec4 albedo, float cutoff)`：`if (cutoff > 0.0 && albedo.a < cutoff) discard;`——属性自动进 `MaterialBlock`（FLOAT 走 UBO），**不需要新 PSO**；`_AlphaCutoff` 缺省 0 时分支恒假，成本是一条 uniform 比较。
- Forward / GBuffer 两段都插入（P4 拍板）；`_AlphaCutoff` 非 0 但该 shader 无 `_MainTex` 时注入器 warn（红线 15）。

### 5.2 阴影侧（`ShadowPass`）

| 缺件 | 改法 |
|---|---|
| 阴影 PSO 无 set | 新建 `shadowSetLayout`（`binding0 = COMBINED_IMAGE_SAMPLER`），两档 PSO 的 `descriptorSetLayouts` 传它；`shadow.vert` 不变 |
| 每材质贴图 | `RenderingSystem.albedoTextureOf(material)`：取 `material.getTexture("_MainTex")` → 有 image 走 `TextureUpload.upload`（**按 Texture2D 实例 + revision 缓存**，无图走 `whiteTexture`），返回 `Texture` + 采样器（沿用 `samplerFor(filter, wrap)`） |
| 描述符集 | `ShadowPass` 自持 `Map<材质, DescriptorSet>`（贴图换代/材质版本变即重建；旧集两帧退役口径同 materialSet） |
| cutoff 传递 | push 扩到 144B：`@128 float cutoff`、`@132 float hasAlbedoTex`（其余填充）；`shadow.frag` 读 `pc.cutoff/cutoff`——`cutoff<=0 || hasAlbedoTex<0.5` 时不采样直接返回（不采样也省钱） |
| fs | `shadow.frag` 加 `layout(push_constant)` 尾部字段 + `layout(set=0,binding=0) uniform sampler2D albedoTex`；`vec4 a = texture(albedoTex, vUV); if (a.a < cutoff) discard;`——**需要 vUV**：`shadow.vert` 现在只声明 `in vec3 aPos`（连 UV 入参都没有），要补 `layout(location=3) in vec2 aUV` + `layout(location=0) out vec2 vUV` |

**排序注意**：`ShadowPass` 每帧对每个 caster 现绑 pipeline（`pipelineFor`）+ `MeshDraw.bindBuffers`，新增 `bindDescriptorSet(0, shadowSetFor(ro.material()), 0, null)` 一条，位置在 push 之前。

### 5.3 与既有系统的连带

- `DDGI` 体素化（`DdgiVoxelizer`）不吃 alpha：镂空物在体素图里仍是实心 → GI 间接光会「透不过去」。**本批不做**，记一条后置（`Engine_Backlog.md` 新增 REND-21-A-② 或并入 P4-05 备注），触发 = 见到植被/栏杆 GI 漏光异常时。
- 透明物仍不投影（`shadowCasters` 口径不变）；cutout 仍属不透明桶（ZWrite On），前向排序不变。

## 6. 风险

1. **push 144 的上限**：Vulkan 保底 128，桌面普遍 256；P2 的「<144 抛错」要在 `ShadowPass` 建 PSO 前查（`Device` 暂无 `maxPushConstantsSize` 访问口 → 需加一个只读 getter，或首次创建捕获 `IllegalStateException` 后按红线 15 报错）。
2. **每帧贴图上传**：`TextureUpload.upload` 若无缓存会每帧重传；落码必须按 `(Texture2D 实例, revision)` 缓存（材质热重载走 revision 自动失效）。
3. **`vUV` 语义**：`shadow.vert` 当前直接透传位置，若 Mesh 无 UV（location 3 缺省）→ 采样坐标无意义；无 UV 的 mesh 应 `hasAlbedoTex=0` 跳过（由 `Mesh` 布局判定）。
4. **两档一致**：Forward 与 GBuffer 的 `_AlphaCutoff` 必须同源（都来自 `MaterialBlock`）；`Unlit` 模型是否也裁——按 P4 口径只在 `Lit` 注入段加，Unlit 待定（错紫默认不裁）。

## 7. 不做

- 不做 alpha-to-coverage / D16 阴影（属 REND-21-C 邻域）；
- 不做镂空物的**阴影侧双面**处理（Cull 沿用材质 Pass 声明）；
- 不把体素化 alpha 化（见 §5.3 后置）；
- 不改 `SurfaceType` 枚举（除非 P1 选 A）。

## 8. 落档与关联

- 本档：`doc/Engine_Shadow_Cutout_Design.md`（v0.2 落码件）
- 关联：`Engine_Shadow_Quality_Design.md` §4.5/§4.7（ShadowPass 与 CSM 版面）、`Engine_Material_Design.md`（`MaterialBlock` 属性口径）、`Engine_Roadmap.md` §4.1 L4 行、`Engine_Backlog.md` REND-21

## 9. 落码记录（2026-11-24，未编译待验收）

| 落点 | 改动 |
|---|---|
| `javelin-rhi` `Device` / `VulkanDevice` | 新增只读 `int maxPushConstantsSize()`（`limits::maxPushConstantSize`）——阴影 push 144B 的上限判定依据 |
| `RenderingSystem` | 新增常量 `ALPHA_CUTOFF_PROPERTY="_AlphaCutoff"` / `ALBEDO_TEXTURE_PROPERTY="_MainTex"`；新增 `alphaCutoff(Material)`（未声明属性 → 0）、`shadowAlbedoTexture(Material)`（`TextureUpload.upload`，未赋图 → 1×1 白图）、`shadowAlbedoSampler(Material)` |
| `ShadowPass` | `SHADOW_PUSH_SIZE` 128→**144**（构造期比设备上限，超限抛错）；新建 `shadowSetDesc`（binding0 = COMBINED_IMAGE_SAMPLER @ FRAGMENT）并以 `List.of(shadowSetDesc)` 建 PSO；`alphaBindings`（IdentityHashMap，按 `getTextureRevision()` + shader 身份重建，旧集 `retireResource` 两帧退役）+ `whiteAlphaSet`（不裁剪共用）；`drawCaster` 每 caster 绑 set0、push 尾部写 `cutoff` / `hasAlbedoTex`；`hasUv(layout)` 判 location 3；无 UV 而声明裁剪时告警一次；`close()` 释放集与布局 |
| `builtin-asset-core/shadow/shadow.vert` | push 块补 `cutoff` / `hasAlbedoTex`；新增 `layout(location=3) in vec2 aUV` + `out vec2 vUV` |
| `builtin-asset-core/shadow/shadow.frag` | 空体 → `cutoff > 0 && hasAlbedoTex > 0.5` 时采样 `albedoTex`，`alpha < cutoff` → `discard`；其余路径零采样 |
| `javelin-editor` `ShaderInjector` | 新增常量 `ALPHA_CLIP`（`javelinAlphaClip(vec4 albedo, float cutoff)`，含 `discard` → 仅注入 fs） |
| `builtin-asset-core/shader/Lit.shader` | `Properties` 增 `_AlphaCutoff ("Alpha Cutoff", Range(0,1)) = 0`；Forward fs 与 GBuffer fs 在取回 `tex` 后各加一行 `javelinAlphaClip(tex, material._AlphaCutoff)` |
| test-project 副本 | `core/shader/Lit.shader`、`core/shadow/shadow.vert`、`core/shadow/shadow.frag` 三件已与源字节一致（`.javelin` 不入 git 索引） |

**自验口径（待用户执行）**
1. 重启编辑器触发内置资产 reimport（`Lit.shader` 与 `shadow.vert/.frag` 必须重导，否则旧 SPIR-V 与新 push 布局不符 → 阴影错乱/校验报错）；
2. 建一个镂空贴图材质（`.mat` 上把 `_AlphaCutoff` 调 0.5）→ 主画面与影子同时出现镂空，且孔洞位置一致；
3. 未声明 `_AlphaCutoff` 的材质画面对比改前无差异；
4. `error.log` 无新增 VVL（重点看「push constant range 超出」「descriptor set not bound」两类）。

**未做项**：`DdgiVoxelizer` 不吃 alpha（体素图里镂空物仍是实心）→ 已登记 `Engine_Backlog.md` REND-21 分支说明，触发条件 = 植被/栏杆 GI 漏光异常。

## 9.1 验收记录（2026-11-25，用户实机通过）

- 素材：程序生成 `test/test/Assets/alpha_cutoff_1024.png`（1024²，colortype 6；孔洞 α=0 且 RGB 与主体同色 235，规避 GDI+ 预乘归零与 mip 暗边；含 α 0.40/0.65 两块阈值测试条）。
- 结果：`_AlphaCutoff = 0.5` 时 4 竖条 / 圆孔 / 棋盘交错格在主画面与地面影子上同时镂空、位置一致；α 0.40 块消失、α 0.65 块保留（阈值语义正确）；调回 0 全部恢复实心、影子回到整块，无残留。用户结论「阴影镂空没问题」。
- 期间没有新增 VVL，`push 144B` 与阴影专 set0 工作正常。
- 素材生成用 py 纯标准库脚本（非仓库产物），校验口径 = IHDR colortype=6 + alpha 直方图 `{0:240509, 102:72000, 166:72000, 255:664067}`。

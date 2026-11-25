# 逐级联剔除（REND-21-B）落码记录

> 版本：v0.2（2026-11-25 **用户实机验收通过**，验收记录见 §7；落码件 v0.1 同日）
> 定位：`Engine_Backlog.md` REND-21-B；L4 第四笔（纯性能项）。
> 关联：`Engine_Shadow_Quality_Design.md` §4（CSM 级联拟合）、`Engine_Roadmap.md` §4.1。

---

## 1. 目标

CSM 四个级联各自把**同一份 caster 列表**全量重画一遍（`ShadowPass` 逐级联 for 循环内直接遍历 `view.list().shadowCasters()`）。末级联覆盖范围最大、通常吃满全场景；近处小级联只覆盖几十米，却仍画全部 caster。本批给每个级联加一次 AABB 粗筛，把「本级联根本投影不到」的 caster 摘掉。

收益形态：近处级联 draw call 大幅下降、末级联基本不变；atlas 每象限的顶点量随之下降（带宽/固定功能开销为主，而非像素填充）。

## 2. 关键事实

| 事实 | 出处 |
|------|------|
| 引擎**此前没有任何视锥/剔除基建**：主视图剔除 2026-09-25 由用户拍板「后置」 | `RenderCamera` / `RenderingSystem` 注释 |
| `RenderObject` 无 AABB 字段；包围盒在 `Mesh.getBounds()`（本地空间，POSITION 扫描惰性算并缓存） | `Mesh.getBounds`；`MeshUpload` 上传前钉死 |
| `getBounds()` **可能返回 null**（无 POSITION 且 CPU 副本已释放） | 同上 javadoc |
| `Aabb.transform(Matrix4fc, Aabb)` 是「中心 + 3 次绝对行点积取半宽」的**保守精确盒**（非 8 角点近似），无透视除法（正交矩阵 w 恒 1）| `Aabb.transform` |
| 级联矩阵是正交投影且 **z ∈ [0,1]**（zZeroToOne） | `RenderingSystem` 级联拟合 |
| `shadowCascadeMatrix(view, c)` 直接给 `Matrix4f`，可在绘制期取用 | 同 |

**结论**：不需要引入 JOML `FrustumIntersection`（其平面提取按 z ∈ [-1,1] 约定，与引擎 zZeroToOne 不一致）；用「世界盒 → 光裁剪盒 → 与盒域比较」即可，且复用现成 `Aabb.transform`，零新依赖。

## 3. 判定规则

对每个 caster、每个级联：

1. `local = mesh.getBounds()`；为 null → **恒可见**（宁可多画，不丢影）。
2. `world = local.transform(ro.world())`。
3. `clip = world.transform(lightViewProj)`（光裁剪空间，正交无除 w）。
4. 可见条件（margin 0.02）：
   - `clip.max.x >= -1 && clip.min.x <= 1`（横向重叠）
   - `clip.max.y >= -1 && clip.min.y <= 1`（纵向重叠）
   - `clip.min.z <= 1`（未整体越过远平面）
5. **近平面侧不裁**（`clip.min.z < 0` 不算剔除）：光源与级联之间的遮挡体本身在近平面外，但它的影子照样会投进本级联，裁掉就是漏光。

成本：每 caster 每级联 2 次 `Aabb.transform`（各约 10 次乘加）+ 6 次比较，纯 CPU、无分配。

## 4. 落码点

| 落点 | 改动 |
|------|------|
| `ShadowPass` 字段 | `Matrix4f cascadeMat` + `Aabb worldBox/clipBox`（复用，零分配）+ `cullLogged`（首帧统计只打一次）|
| `ShadowPass` 级联循环 | 取矩阵后 `cascadeMat.set(lightViewProj)`，遍历 caster 前先 `visibleInCascade(ro, cascadeMat)`；末级联打一行 `逐级联剔除: 视口[x] 级联N 末级存活 k/total` |
| `ShadowPass.visibleInCascade` | 新增私有方法，规则见 §3 |
| 新增 import | `core.physics.collision.Aabb`、`core.render.Mesh`、`org.joml.Matrix4fc` |

**没有动的地方**：caster 列表构建（`RenderList.shadowCasters`）、级联拟合、PSO、着色器、任何 binding。主视图剔除仍未做（REND-01 依旧后置，本方法论可直接复用到主视图，届时应把规则抽成公共 culler）。

## 5. 风险与后置

- **R1 精度**：Aabb 保守盒 + 0.02 margin，理论上只可能「多画」，不会「少画」；若实机出现级联边缘影子突然缺一块，先怀疑 margin 不足或 `getBounds()` null 分支（日志里 `k/total` 异常即可看出）。
- **R2 收益不可测**：本批不编译、不 profile，收益只能靠首帧日志 `k/total` 与实机帧时间粗看。若收益 < 5%，不值得为它维护额外分支，可直接回退（改动集中在 `ShadowPass` 一个方法 + 循环两行）。
- **后置**：`REND-21-B` 的完整形态（含主视图剔除、按级联维护持久 caster 列表、静态/动态分层）与 `REND-01` 合并；`RenderObject` 无 `isStatic` 仍是分层的前置缺口。

## 6. 自验口径（未编译，交用户）

1. 开阴影、场景放 20+ 物体：`error.log` 出现 `逐级联剔除: … 末级联存活 k/total`，k 应接近 total（末级联最大），近处级联 k 明显更小（日志只打末级联，需要时可临时改打全部）。
2. 影子**没有缺块**：把摄像机拉到物体附近逐级联走一圈，重点看级联交界带（混合带最容易暴露误剔）。
3. 大型地面/墙（包围盒巨大）始终被保留，不应出现「地面自身不投影导致接缝」。
4. 与上一笔（`71f50ae` 接触阴影）对比帧时间；若无改善但画面正确，属预期（本批主要省近处级联的顶点/固定功能开销）。

## 7. 验收记录（2026-11-25 用户实机通过）

**判据取「两个端点」而非收益数字**——日志只打末级联一行（`cullLogged` 首帧一次），近级联的 `k/total` 看不到，故用「同一份 caster 集合在距离参数两端是否给出不同的存活数」证判定链路在工作：

| 步 | 操作 | 日志 | 结论 |
|----|------|------|------|
| 1 | 距离保持缺省 **80**，重启 | `逐级联剔除: 视口[Editor Camera Node] 级联3 末级存活 9/9` | 末级联覆盖最远、几乎罩全场景 → 9/9 为**预期值**（剔除收益不在末级联） |
| 2 | 距离临时改 **1**，重启 | `… 末级存活 0/9` | 9 个 caster 全被光裁剪盒摘掉 → **判定非恒真**，`clip.z` 与 AABB 变换没写错；同时画面按「超距恒受光」正常无影 |
| 3 | 距离改回 **80**，重启 | `… 末级存活 9/9`，画面与上一批验收一致 | **无缺块**（含级联交界带 / 混合带）→ 保守盒 + 0.02 margin 未误剔 |

用户结论「没问题」。R2（收益不可测）按「两端点 + 画面正确」结案：收益数字仍未测（近级联日志未落），属**观察面缺口**而非功能缺口——如需长期看收益，把首帧统计改成逐级联各打一行（`cullLogged` 改 `boolean[]`），一行改动，未做。

**未做/后置**（同日记录）：① 近级联 `k/total` 不可见（同上，改日志即可）；② 主视图视锥剔除（REND-01）未做，本规则可抽公共 culler 复用；③ 静态/动态分层受 `RenderObject` 无 `isStatic` 阻塞。

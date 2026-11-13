# 引擎待办总账（Engine Backlog）

> 版本：v0.1（2026-11-11 建档）
> 定位：**「已计划、尚未完成」的唯一登记处**——未开工 / 半成品 / 后置项 / 待拍板 / 已知问题（bug 类）全部在此收敛，一项一行。
> 只写「要做什么 + 落点 + 归类 + 依赖」；设计论证、拍板依据、实现细节一律留在各设计档，本文不做转述。

## 0. 与邻档的分工（防止两处维护）

| 文档 | 管什么 | 与本文的关系 |
|------|--------|--------------|
| **本文** | **还没做的**（计划态） | 入口 |
| `Engine_Features.md` §6 | 已落地、等用户实测（待验收挂账） | **不是待办**，本文不收录 |
| `Engine_Features.md` §7 | 已知问题（bug 类） | 已并入本文 §13，原处留指针 |
| `doc/Engine_Roadmap.md` §4 | 四期的范围与排序（立项口径） | 本文 §3 只登记条目，范围以路线档为准 |
| `doc/Engine_Roadmap.md` §6 | 跨期遗留（简版） | 已改为指向本文 |
| `doc/Engine_Roadmap_Progress.md` | **已做完**的批次索引 | 本文条目完成即移入该卷 |

**维护纪律**（红线 12「文档先行」/ 红线 16「台账 ≤50KB」适用）：

1. **开工**：状态列由 `未开工`/`后置项` 改 `进行中`；
2. **完成并验收**：整行**移出**本文 → 进 `Engine_Roadmap_Progress.md` 批次索引 + `Engine_Features.md` 验收状态（编号留空档，**不复用**）；
3. **新增**：设计档新写「后置项 / 挂账 / 已知边界」段时，**同批**在此登记一行（落点写明档名 + 小节号）；
4. **待拍板项拍板后**：归类去掉 `待拍板`，按拍板结论改 `未开工` 或移出。

**归类图例**：`未开工`（方案已定、没动手）｜`半成品`（一部分已落）｜`后置项`(主动推迟)｜`待拍板`（连方案都没定）｜`已知问题`（bug 类，有明确症状）｜`进行中`

**编号**：`域-序号`，建档时一次分配，永久稳定，条目移出后编号作废不复用。

## 0.1 落点简写对照

| 简写 | 文档 |
|------|------|
| 路线 / 进度卷 | `doc/Engine_Roadmap.md` / `doc/Engine_Roadmap_Progress.md` |
| 功能清单 | `Engine_Features.md`（根目录） |
| 实现计划 | `doc/Engine_Implementation_Plan.md` |
| RHI 计划 / RHI 进度 | `doc/RHI_Implementation_Plan.md` / `doc/RHI_Implementation_Progress.md` |
| 管线 / 光照 / 天空盒 / 后处理 | `doc/Engine_RenderPipeline_Design.md` / `doc/Engine_Lighting_Design.md` / `doc/Engine_Skybox_Design.md` / `doc/Engine_PostProcess_Design.md` |
| 贴图 / 资产 / 材质 / 热重载 | `doc/Engine_Texture_Design.md` / `doc/Engine_Assets_Design.md` / `doc/Engine_Material_Design.md` / `doc/Engine_AssetReload_Design.md` |
| 场景 / 碰撞 / 拾取 / 输入 | `doc/Engine_Scene_Design.md` / `doc/Engine_Collision_Design.md` / `doc/Engine_Picking_Design.md` / `doc/Engine_Input_Design.md` |
| 编辑器 / Swing / 菜单 | `doc/Editor_Design.md` / `doc/Editor_Swing_Design.md` / `doc/Editor_Menu_Design.md` |
| 插件 / Inspector / 调试线 | `doc/Editor_Plugin_Design.md` / `doc/Inspector_Design.md` / `doc/Editor_DebugLine_Design.md` |
| 调试工具 / 坑 | `doc/Engine_DebugTools_Plan.md` / `doc/Vulkan_Pitfalls.md` |

---

## 1. 建议近期候选（供拍板，非承诺）

> 建档时的排序建议，依据 = 量级小 / 解除他项阻塞 / 收益直接。**是否开工由用户拍板**，本节随拍板滚动。

| 序 | 候选 | 理由 |
|----|------|------|
| 1 | **REND-01 视锥剔除** | Aabb/Mesh.bounds 地基已备，收益直接（当前每帧全量送渲染） |
| 2 | **RHI-01 Buffer staging** | RHI 地基；动态 Mesh/纹理专项（AST-05）的前置 |
| 3 | **P4-01 后处理链（Bloom/Tonemap + HDR）** | 四期第一项，链式结构已落地、零新底座；主线推进项；**步骤一（HDR + Tonemap）2026-11-13 已落码待验收（`Engine_PostProcess_Design.md` v0.2）** |
| 4 | **BUG 余项** | §13 余 `updateIndex` 热点（BUG-02）、`MainFrame.width()` 语义（BUG-05）、Layer 下拉空（BUG-08，依赖 ED-07 设置管线合流） |
| 5 | **HOT-02 `.mat`/`.texture`/`.smesh` 文件级 reload 恢复** | 外部手改资产自动生效；**须先拍板真值源优先序**（内存即真值 vs 盘面改动），拍板前不动 |

> DBG-01（dbg-1 校验层回调 + engineDebug 开关）2026-11-11 建档时列本表首位，**2026-11-12 落地并验收**，已移出本表。
> HOT-01（运行期 `setImage` 后 GPU 像素重上传）建档时同列首位，**2026-11-12 实机复核确认早已生效**（2026-10-30 随「Texture2D 冻结废除 + revision」落地），已移出本表（详见 §7 移出记录）。
> 本批（2026-11-12 零散债务小批）移出：**BUG 打包小修**与 **ED-01 耗时指示器接线**——BUG-01/03/04 两条过期两条已修、ED-01 落两处调用点（见 §8/§13 移出记录）。

---

## 2. 排障基建（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| DBG-03 | shadowMap 可视化（深度比较采样需专 shader 分支） | 调试工具 §3.3 | 后置项 | — |
| DBG-04 | 校验层 VERBOSE 严重度分支（对象命名/生命周期追踪用） | 调试工具 §2.2 | 后置项 | DBG-01 的一行改动 |
| DBG-05 | 编辑器「首选项」面板接入 engineDebug 开关（现仅能手改设置文件） | 调试工具 §2.4 | 后置项 | DBG-01 |
| DBG-06 | SPIR-V 静态自检（无 SDK 环境下的引擎侧校验） | 调试工具 §4 | 待拍板 | 需另立项 |

## 3. 四期：高质量渲染（未开工，施工前逐项拍板方案）

> 范围与排序口径以 `路线 §4` 为准；本节只登记条目。

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| P4-01 | 后处理链（Bloom/Tonemap + **HDR 切换点**） | 路线 §4-1 / 管线 §4.6 / **后处理档** | 半成品 | **步骤一（HDR 切换点 + Tonemap）2026-11-13 已落码待验收**；步骤二 Bloom 待开工；方案与实装记录见 `Engine_PostProcess_Design.md` v0.2 §9 |
| P4-02 | SSAO（后处理链 Pass，**GI 第一步**） | 路线 §4-2 / 光照 §10 | 未开工 | 零新底座；依赖 P4-01 |
| P4-03 | 阴影质量分级（PCF 软采样 → CSM 级联 → bias 精调 / per-object 开关 / stabilize） | 路线 §4-3 / 光照 §10 | 未开工 | 三期 shadowMap 底座 + 注入段已备 |
| P4-04 | 天空盒环境光照（作 ambient 源：SH9 球谐 / mipmap 降采样，替换 ambient 常量） | 路线 §4-4 / 光照 §10 / 天空盒 §8 | 未开工 | 天空盒视觉面已备；DDGI 前的过渡 |
| P4-05 | 探针实时 GI（DDGI 路线；**烘焙 GI 管线明确不建**） | 路线 §4-5 / 光照 §10 | 未开工 | 三期 SSBO/compute 底座 + 3D 纹理/纹理数组（RHI 现仅 Texture2D） |
| P4-06 | 光线追踪 rayQuery（AS 资源种类/图状态、BDA、`GL_EXT_ray_query` 编译链、蒙皮 BLAS 重构；首范围=静态几何） | 路线 §4-6 / 光照 §10 | 未开工 | 三期批次①「通用性红线」 |
| P4-07 | ~~HYBRID 档 MSAA-GBuffer（现强制 1x）~~ **作废（2026-11-13）**：AA 路线改 TAA，不做 MSAA-GBuffer | 光照 §9 批次④-B | 作废 | 由 P4-10 取代 |
| P4-08 | 非光照材质语义契约（`default.shader` HYBRID 档 albedo 仍乘光照 → 无灯即黑；「直读 albedo」语义待定） | 光照 v0.10 遗留 / 管线 v0.6 | 待拍板 | 归四期材质/着色模型专档 |
| P4-09 | HDR→LDR 高亮截白真解（`.hdr` float 产物） | 天空盒 §1.2/§5 | 后置项 | 随 P4-04 环境光批 |
| P4-10 | **TAA（AA 路线取代 MSAA，2026-11-13 用户拍板）**：相机投影抖动（Halton）+ 逐像素运动矢量（混合实现由 G-Buffer 世界位置 + 上帧 viewProj 反投影重建；前向实现无 G-Buffer 需另定）+ 历史缓冲 ping-pong + 重投影/邻域钳制防拖影 + 历史失效触发（切场景/相机跳变/热重载/resize）+ 锐化。**MSAA 已删（2026-11-13），空窗期**：在 TAA 落地前画面无抗锯齿 | 后处理档 §4.2 / 原 P4-07 | 待拍板 | 挂后处理链（Bloom/Tonemap 之前）；参考 `Babylon_Rendering_Reference` TAA 段 |

## 4. RHI 遗留（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| RHI-01 | **Buffer staging 上传路径**（deviceLocal 顶点/索引/纹理；现仅 hostVisible，deviceLocal 直接 `vkMapMemory` 会失败） | 路线 §6 / RHI 进度 待办 / 实现计划 §4 | 未开工 | FrameArena（RHI-05）是其赎回路径 |
| RHI-02 | 描述符池按「布局 × 帧数」环形复用（替代设备级单池 2048 sets，RHI 15.8 正式方案） | 路线 §6 / RHI 进度 阶段⑥ | 未开工 | — |
| RHI-03 | backbuffer 句柄改以「槽位身份」参与 structureHash（现 image(idx) 帧间交替 → 单槽图缓存每帧全量重编译） | 路线 §6 / RHI 进度 已知问题 | 已知问题 | 性能 |
| RHI-04 | RT 内存别名（RHI 17.4；现所有附件独立分配） | RHI 进度 阶段④ / 管线 §9 | 后置项 | P1；后处理中间 RT 别名 |
| RHI-05 | FrameArena 收口：`VulkanDevice.frameArena()` per-call `new`（帧内误调即每帧 1MB 泄漏）+ 陈旧注释 + 容量配置化（设计默认 64MB，现 1MB/块） | RHI 进度 现状存档 / 坑 #25 | 半成品 | 与 RHI-01 同源 |
| RHI-06 | `VulkanQueue.submit` 等待阶段硬编码 COLOR_ATTACHMENT_OUTPUT（compute 提交须按用途分派） | RHI 进度 已知问题 | 已知问题 | — |
| RHI-07 | RHI 内部封装视口 Y 翻转（吸收测试里的负高度手法，上层不再处理） | RHI 进度 待办 | 未开工 | — |
| RHI-08 | R32_UINT 附件支持查询（未调 `vkGetPhysicalDeviceFormatProperties`，现靠校验层把关） | 拾取 §8 | 后置项 | 拾取附件格式 |
| RHI-09 | P2 包：多线程并行录制/异步加载（27.5）、QueryPool 性能回读、多队列 compute、DebugUtils 对象命名/帧 region、采样器缓存去重、Bindless | RHI 计划 §4 | 后置项 | 明确「不做（P2，实现后置）」 |
| RHI-10 | 图依赖缺「材质贴图读」：前向/光照 Pass 只声明 `shadowMap`，`_MainTex` 等走材质描述符集绑定 → 采样贴图无屏障可插（坑 #37 修后已不报错，但跨帧布局仍是猜的） | 管线 §4.2 / 坑 #37 | 未开工 | 需把材质贴图句柄纳入 PassDef 读声明（structureHash 随之变） |
| RHI-11 | 非 `VulkanTexture` 的纹理绑描述符走 `texture().handle()` 当 imageView（`VulkanDescriptorSet.update` else 分支）——`SwapChainTexture` 等会把 VkImage 当 view 传进去 | RHI 进度 已知问题 / 坑 #37 | 已知问题 | 当前无消费方（无人绑交换链图），属潜伏 |
| RHI-12 | 交换链 per-image `SwapChainTexture` 未释放：`VulkanSwapChain.create` 直接覆盖 `images`、`dispose` 不遍历 → 每次 resize 重建漏一批 VkImageView（且 view 须先于 `vkDestroySwapchainKHR` 销毁，Cleaner 的两帧延迟不适用，需即时销毁口） | 坑 #37 / RHI 进度 已知问题 | 未开工 | 与 BUG-09 泄漏账同源 |

## 5. 渲染 / 管线后置（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| REND-01 | **视锥剔除落地**（RenderingSystem 收集侧；Aabb/Mesh.bounds 地基已备） | 路线 §6 / 功能清单 §2.4 | 未开工 | 一期即后置 |
| REND-02 | 遮挡剔除 / GPU Instancing / LOD | 路线 §6 / 场景 §8 / 管线 §9 | 后置项 | P2 |
| REND-03 | 着色模型升级 **Lambert → BRDF** | 路线 §4 尾注 / 光照 §10 | 后置项 | 不设期，归材质/着色模型专档 |
| REND-04 | cluster 构建整链 GPU 化（count/scan/write 三步 compute + 每帧 grid 清零；CPU 版作迁移 oracle） | 光照 §10 / §6 | 后置项 | 三期有意先落 CPU |
| REND-05 | 脏标记跳过 cluster 重建（相机/灯集合不变时不重跑） | 光照 §10 | 后置项 | — |
| REND-06 | cluster cell 线性索引 **Morton 化**（局部性） | 光照 §5/§10 | 后置项 | — |
| REND-07 | decals / 反射探针并入同一 cluster 索引系统（索引池语义泛化） | 光照 §10 | 后置项 | — |
| REND-08 | 世界位置改**深度重建**（现 G-Buffer 直存 worldPos） | 管线 §4.2 / 光照 §10 | 后置项 | P2 优化 |
| REND-09 | 单 Pass 多视口（RHI 8 节备选） | 管线 §9 | 后置项 | — |
| REND-10 | 按视口分档（预览视口 `FORWARD_SIMPLE`） | 管线 §5.1 | 未开工 | **前向实现非重点（2026-11-13 主口径混合），优先级下调** |
| REND-14 | 前向管线独立类的落地面（`ForwardRenderPipeline` 屏外无人消费：demo/测试现仍直用 `RenderingSystem` 不经管线类；编辑器固定混合、仅打包运行时按 `javelinSettings.renderPipeline` 选用） | 管线 §6.3 | 后置项 | 2026-11-13 拆分产物；需要时再补 demo/测试接线 |
| REND-11 | ~~MSAA 后置族：stacking/预览/分屏区域清屏的 MSAA 路径、运行期切档（PSO/附件失效协议）、FXAA 档、末 pass storeOp DONT_CARE~~ **作废（2026-11-13）**：MSAA 业务面已整体删除（AA 改 TAA，P4-10）；**仅存「末 pass storeOp DONT_CARE 带宽优化」** | 管线 §9 | 后置项 | AA 部分由 P4-10 接管；MSAA 路径族不再做 |
| REND-12 | UI Pass 组 + 环境雾（`Environment` 加雾字段；天空盒已转正） | 管线 §9 / 场景 §8 | 后置项 | — |
| REND-13 | 光照 gizmo/调试显示（编辑器侧 DebugLine 光球线框 + 灯图标） | 光照 §10 / 调试线 | 未开工 | — |

## 6. 资产 / 贴图 / 网格 后置（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| AST-01 | **mip 生成链**（RHI 未实现，导入恒 `mipLevels=1`） | 贴图 §5 | 未开工 | 天空盒 mip（AST-09）同源 |
| AST-02 | 块压缩 BC/ASTC（现 RGBA8 直读；落 Texture2D 层派生多份 GPU image） | 贴图 §5 | 后置项 | — |
| AST-03 | sRGB 口径（现 `R8G8B8A8_UNORM`；线性工作流接交换链 sRGB 变体需视图格式分支 + MUTABLE_FORMAT 规则查证） | 贴图 §5 | 后置项 | 与 P4-01 HDR 相关 |
| AST-04 | 「一图多设置」的 UI 消费（模型已支持多 Texture2D 引同一 Image，设置入口待落） | 贴图 §5 | 未开工 | — |
| AST-05 | **动态 Mesh / 动态纹理专项**（hostVisible 常驻 + 每帧映射写入 / staging 环形；`rhi.Texture.update(Region,ByteBuffer)` 运行期口已有未接） | 路线 §6 / 功能清单 §2.5 / 材质 §6 / 资产 §2.10 | 未开工 | 依赖 RHI-01 |
| AST-06 | `.mesh` 二进制格式（快加载；`.obj` 仅作导入源） | 资产 §2.10 | 后置项 | — |
| AST-07 | 顶点格式/布局扩展：多绑定点（分离布局）、显式 location、U8/U16/HALF | 资产 §2.10 | 后置项 | — |
| AST-08 | 骨骼网格 SkinnedMesh 的对称设计 | 资产 §2.10 | 后置项 | 无动画系统 |
| AST-09 | 天空盒 mip 链（环境项 mipmap） | 天空盒 §3/§8 | 后置项 | 依赖 AST-01 |
| AST-10 | 程序化天空（大气散射，远期） | 天空盒 §8 | 后置项 | — |
| AST-11 | per-camera 天空盒（现多相机统一读环境组件同一 skybox） | 天空盒 §5/§8 | 后置项 | — |
| AST-12 | equirect→cubemap 转换工具（给用户把全景图转六面） | 天空盒 §1/§8 | 后置项 | — |
| AST-13 | cubemap 资产六面缩略面板（现只读行） | 天空盒 §5 / 功能清单 §2.3 | 后置项 | 检查器侧 |
| AST-14 | Cubemap ImportSettings `faceSize` 可调（现固定 512） | 天空盒 §1.2 | 后置项 | — |
| AST-15 | 内置资源同名索引 fat-jar 合并边界（editor+core 若合 fat jar 须改按模块原名或改扫描协议） | 天空盒 §8 | 后置项 | 构建侧 |

## 7. 热重载与资产管线挂账（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| HOT-02 | `.mat`/`.texture`/`.smesh` 文件级 reload 摘除的恢复（收窄只 shader；`targetClass` 加回分支即恢复 → 外部手改这些资产自动生效） | 热重载 §8/§9 | 半成品 | walker/reload/reconcile 基建全保留 |
| HOT-03 | Scene 根豁免（外部改 `.scene` 重导 Library 但场景对象图 warn 跳过、画面不刷新）——批次②剩余 | 热重载 §5.1/§8 | 半成品 | 二期 |
| HOT-04 | Scene 脏时警示形式（日志 warn → 顶部弹条 / 对话框，Unity 式） | 热重载 §10 | 待拍板 | 批次②实测定 |
| HOT-05 | 换 shader 删 Pass 的报错形态（维持渲染侧显式报错 vs 重载前 Pass 兼容预校验、不兼容材质置灰） | 热重载 §6/§10 | 待拍板 | 批次④ |
| HOT-06 | Inspector 材质编辑通道接入 + 内容 hash 替代 mtime（防「时间戳变内容没变」误重导）+ Pass 兼容预校验 | 热重载 §8 批次④/§5.2 | 未开工 | 批次④ |
| HOT-07 | WatchService 后台监听（Windows 多段保存误触发坑；焦点扫描已覆盖主用例） | 热重载 §1/§5.1 | 后置项 | — |
| HOT-08 | 删除资产源文件联动清理（Library 产物残留不失效，Unity 同口径） | 热重载 §7 | 后置项 | — |
| HOT-09 | Walker 递归深度再评估（v0.5 起不再递归；出现「仅经 Asset 内部字段可达的非注册表持有者」时） | 热重载 §10 | 待拍板 | 条件触发 |
| HOT-10 | 旧格式 `.smesh` 产物自动检测（2026-10-04 前产物 guid null，现仅能删产物重导） | 编辑器 §6.3 | 待拍板 | — |
| HOT-11 | 同文件导入任务去重（连续快速保存同文件多 worker 交叠写同一产物） | 编辑器 §6.3 | 未开工 | — |
| HOT-12 | 脚本导入器落地（`.java` 代码树不产 meta、meta 无消费方；头 GUID 脚手架保留） | 编辑器 §6.2 | 待拍板 | 待脚本功能真落地 |
| HOT-13 | std140 排布生成器独立化（Engine_Material §3 P2）——**疑已随 .mat/DefaultMaterial 落地，待复核** | 材质 §3 | 待复核 | 低置信 |

> **移出记录**：HOT-01（运行期 `setImage` 后 GPU 像素重上传）2026-11-12 复核**确认早已生效**——`setImage` 置空句柄 + `revision` 自增 → materialSet 退役重建重传，2026-10-30「Texture2D 冻结废除」时即落地；用户实机面板换图当帧上屏、旧 TEXTURE/IMAGE_VIEW 经 Cleaner 回收。原「半成品 / 红线 17 唯一残留项」归类系 2026-10-25 面板初版注释（「`TextureUpload` 幂等返回旧句柄」）未随改动回收。外部手改 `.texture`/`.png` 的 file-level 重载仍摘除 → 见 HOT-02。

## 8. 编辑器（二期 / 跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| ED-02 | Swing 深色主题（2026-10-17 拍板先改亮色，深色随后置） | Swing | 后置项 | — |
| ED-03 | Dock 拖拽停靠（复杂交互分期后置） | Swing | 后置项 | — |
| ED-04 | 多视口（各自相机各自 swapchain，RHI 侧扩展） | Swing | 后置项 | RHI 联动 |
| ED-05 | Game 视图工具区（显示比例/缩放/Stats 一族，Unity 口径） | 编辑器 / Swing | 后置项 | — |
| ED-06 | Inspector 字段贡献者（插件侧） | Swing | 后置项 | 随插件体系 |
| ED-07 | Inspector 暂缓族：Collider 字段、多 tag 展示与编辑、同节点多实例组件收集形态、可空值「清回 null」UI、订阅 nodeRenamed、设置管线合流（`Game/`→`Settings/`） | Inspector §10 | 后置项 | — |
| ED-08 | Inspector 遗留：project 面板高亮跨域同步（订阅 ProjectSelectEvent）、`.smesh` 等其余资产检查器按需注册、注入后 GLSL 查看（Unity "Show compiled code" 等价物） | Inspector §11 | 后置项 | — |
| ED-09 | Inspector 细节：折叠态重建复位（无记忆）、label 超 96px 截断、候选分组/分类/按类名搜索/顶栏 Component 菜单、AddComponent 菜单项扩容 | Inspector §11/§13 | 后置项 | — |
| ED-10 | 脚本资产检查器 + 脚本功能本体 | Inspector §11 | 后置项 | 与 HOT-12 同域 |
| ED-11 | Gizmo 后置族：双轴平面手柄、环上角度扇形、拖拽箭头变长动画、角度/位移吸附、pivot/center 切换、环不可见 collider、W/E/R 快捷键 | 编辑器 | 后置项 | — |
| ED-12 | 视口聚焦已知边界（F 键依赖渲染窗口 OS 焦点；极窄竖视口按垂直 FOV 口径可能侧向裁边） | 编辑器（视口聚焦段） | 已知问题 | 二期 |

> **移出记录**：ED-01（耗时指示器调用点接入）2026-11-12 落地——场景切换（`SceneFileType.openScene`）+ 焦点扫描（`AssetReloadService.scanAndShow`）两处；原另两候选（`ProjectLoadingFrame` 引导链、`PackDialog` 构建线程）复核后不接（各有常驻指示器，叠浮层是重复）。

## 9. 插件与 i18n（二期 / 跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| PLG-01 | 命令 id 前缀化（现第三方 id 撞核心域 fail-fast 拒绝；规则 + 菜单结构注入位未定） | 插件 §7.1 / 路线 §6 | 待拍板 | — |
| PLG-02 | FileType 扩展名冲突策略（现 fail-fast；第三方接管 `.scene` 该拒还是先注册者胜） | 插件 §7.1 / 路线 §6 | 待拍板 | — |
| PLG-03 | 装载期注解层启动校验（§4.5-2） | 插件 §7.2 | 后置项 | — |
| PLG-04 | i18n 报告导出命令（Tools 域 miss/used 分桶工单） | 插件 §7.2 / 功能清单 §3.8 | 后置项 | — |
| PLG-05 | 插件管理界面 v2（列表 name/description/version/author、启用禁用、i18n 报告按插件分桶） | 插件 §7.2 | 未开工 | — |
| PLG-06 | AssetImporter SPI 收编 `PluginContext` | 插件 §7.2 | 未开工 | — |
| PLG-07 | SDK 模块提取（§5.2） | 插件 §7.2 | 未开工 | — |
| PLG-08 | 第三方 jar 实测（现静态骨架） | 功能清单 §3.8 | 后置项 | — |

## 10. 场景 / 物理 / 输入 / 窗口（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| SYS-01 | **L2 物理**（Rigidbody 组件、MeshCollider、物理库选型；开放项「只做窄相查询 vs 连带选型」） | 功能清单 §2.7 / 碰撞 §2/§6 / 路线 §2.2 | 待拍板 | 选型拍板后立项；对应实现计划阶段⑥ |
| SYS-02 | 物理/查询后置族：broadphase 加速、overlap 族、raycast 命中排序选项、触发器语义 | 碰撞 §10.5/§5 | 后置项 | — |
| SYS-03 | 三角网格拾取 / MeshCollider（编辑器已改 GPU 拾取，归 L2 需求再定） | 碰撞 §7/§10.5 | 后置项 | 依赖 SYS-01 |
| SYS-04 | 音频子系统（音频上下文 + 3D 音源） | 实现计划 阶段⑥ / 路线 §5 | 未开工 | 选型后立项 |
| SYS-05 | Prefab 资产（节点树模板 → 实例化） | 场景 §8 / 实现计划 §5 | 后置项 | — |
| SYS-06 | 组件 Update 顺序优先级 / 依赖排序（P2，对齐 Unity Script Execution Order） | 场景（组件段） | 后置项 | — |
| SYS-07 | 自定义组件分派优化（按接口分派 / 回调缓存；组件量上升后再做） | 场景 §8 | 后置项 | 性能 |
| SYS-08 | 独占全屏 | 功能清单 §2.6 | 未开工 | 窗口 |
| SYS-09 | 输入后置族：文本输入（char 回调 + IME）、剪贴板、鼠标锁/游标模式（编辑器飞行 raw motion 时做）、事件订阅 API、方括号/小键盘回车 AWT 映射（待真机验证）、手柄/触摸 | 输入 §6 | 后置项 | — |

## 11. GPU 拾取边界（二期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| PICK-01 | 多视口拾取（分屏 viewport 偏移换算） | 拾取 §8 | 后置项 | — |
| PICK-02 | hover 变更驱动失效（现仅指针移动时重拾；场景动/鼠标不动则 hover 不刷新） | 拾取 §8 | 后置项 | — |
| PICK-03 | 水平视角下网格平面之下（y<0）被挡物体的拾取为已知边缘 | 拾取 §8 | 已知问题 | 二期 |

## 12. 构建 / 开源（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| BLD-01 | `./gradlew` 对 Windows cmd/PowerShell 不可用（根缺 `gradlew.bat`）；`distributionUrl` 仍走腾讯镜像 | 进度卷 §6 开源前置批 | 已知问题 | — |

## 13. 已知问题（bug 类）

> `Engine_Features.md` §7 原表并入本节，原处留指针。

| ID | 症状 | 落点 | 归类 | 备注 |
|----|------|------|------|------|
| BUG-02 | 导入成功即全量 `updateIndex()` 热点（每任务成功都重扫监听目录重写索引） | 编辑器 §9 | 已知问题 | 性能 |
| BUG-05 | `MainFrame.width()` 语义与 GLFW 不同（逻辑像素 vs framebuffer 像素；按窗口宽高的逻辑须留意 dpi） | 编辑器 §9 | 已知问题 | — |
| BUG-06 | 窗口拖拽 resize 期画面非等比拉伸、松手即愈（2026-10-17 拍板接受） | 编辑器 §9 / 原 功能清单 §7 | 已拍板接受 | 不修，记录 |
| BUG-07 | Gizmo 拖拽中按 Alt 相机环绕与拖拽并动（已知边缘） | 原 功能清单 §7 | 已知问题 | 后调 |
| BUG-08 | Layer 下拉空（设置管线遗留：`Game/` 与其未合，接通前下拉无内容） | Inspector（节点头段） | 已知问题 | 与 ED-07 设置管线合流同源 |
| BUG-09 | 退出期 `vkDestroyDevice` 报 46 个对象未销毁（VVL 05137；首 10 个为 VkDeviceMemory），`CleanerRegistry.flush()` 已跑仍剩 → 逐笔账未查 | 坑 #37 | 已知问题 | 已确认子项 = RHI-12 交换链 view；查账手法 = 抬高 `duplicate_message_limit` 看全列表 + 按 handle 反查创建点 |

> 另有一批存量问题（描述符池 / FrameArena / backbuffer 哈希等）登记在 §4 RHI 遗留，性质同属 bug 类（RHI-03 / RHI-05 / RHI-06），不在此重复登记。

> **移出记录（2026-11-12 零散债务小批）**：**BUG-01**（`loadScene` 失败路径 NPE）**复核为过期条目**——四处 null 守卫早已落地（`JavelinEditor.onInitialize` 场景未就绪跳过装配 + `EditGridAppState` / `TransformGizmoAppState` / `DebugLineAppState`）；**BUG-04**（`SettingsManager` javadoc 悬空引用）同属过期——该引用全仓零命中、现引 `PreferencesDialog`；**BUG-03**（加载页假等待）已修——`ProjectLoadingFrame` 改「补足最短可见时长」（原为在引导链耗时之上额外 +2s）。

## 14. 待复核（疑文档残留，非真待办）

> 建档时发现两处文档残留，**2026-11-11 同批已就地更正**（`路线 §6` 改写为指向本文并标注原条目已落定 / 「插件域待拍板」改 2 项），本节暂空，留位给后续清点发现的存量不一致。

（当前无）

---

## 15. 建档说明

- 本表 v0.1 由一次全量文档清点生成（2026-11-11），扫描范围 = `doc/*.md` + `Engine_Features.md` + `AGENTS.md`，关键词 = 后置/挂账/遗留/待办/未开工/待拍板/已知边界/暂缓等；
- 条目数：**106 条**（DBG 6 / P4 9 / RHI 9 / REND 13 / AST 15 / HOT 13 / ED 12 / PLG 8 / SYS 9 / PICK 3 / BLD 1 / BUG 8），去重后按域归入 §2~§13；
- 建档口径：**只登记「还没做」**；已落地待实测（`功能清单 §6`）与已完成批次（`进度卷`）不收；
- 优先级与「建议下一步」为建档时建议，非承诺。

> **移出记录**：DBG-01（dbg-1 校验层回调 + `engineDebug` 开关）2026-11-12 落地并**验收**（`Engine_Features.md` §6 挂账清零）；DBG-02（dbg-2 G-Buffer 可视化）同日落地并验收（2026-11-12 用户实测）→ 已入 `Engine_Features.md` §3.5 并销账（原归 §6 待验收挂账，当日清）；HOT-01（贴图换绑像素重上传）同日复核确认早已生效（非待办）→ HOT 域余 12 条；同日零散债务小批移出 **ED-01**（耗时指示器接线）与 **BUG-01 / BUG-03 / BUG-04**（两条过期条目 + 一条已修）→ ED 域余 11 条、BUG 域余 6 条。**在建条目 = 103**（建档 106 − DBG-01/02 − HOT-01 − ED-01 − BUG-01/03/04 + 首跑排障新增 RHI-10/11/12、BUG-09 共 4 条）。

> （注：部分内容可能由 AI 生成）

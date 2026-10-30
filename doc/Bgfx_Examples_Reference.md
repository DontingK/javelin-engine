# bgfx 示例参考（bgfx Examples Reference）

> 版本：v0.1（2026-10-31 建档）
> 定位：bgfx 官方示例集（`00-helloworld` ~ `51-gpufont` 共 52 个编号示例 + 三个支撑目录）速查索引——实现某个渲染技术前先查「bgfx 有没有现成参考、在哪个目录」。
> 来源：本地检出 `..\bgfx\examples`（**外部目录，不随本库版本管理**；行号/文件名以该检出为准）+ 官方在线页 <https://bkaradzic.github.io/bgfx/examples.html>。
> 取材口径：「官方描述」列逐字取自各示例源码里的注册字符串（`entry::runApp` / `ENTRY_IMPLEMENT_MAIN`）；「演示内容 / 技术点」列对照源码与着色器文件核实；「参考点」列为对照本引擎当前排期的判断，非保证——真要动手时以示例源码为准。
>
> 注：少数示例的注册名与目录名不一致（官方笔误，未修：`02-metaball`、`28-wirefame`、`51-gpu-font`）。

---

## 1. 示例一览（00~51）

| 编号 / 目录 | 官方描述 | 演示内容 / 技术点 | 与本引擎的参考点 |
|---|---|---|---|
| 00-helloworld | Initialization and debug text. | bgfx 初始化最小闭环、清屏、调试文本（ASCII logo） | 引擎初始化链与 DebugText 对照 |
| 01-cubes | Rendering simple static mesh. | 静态顶点/索引缓冲 + MVP uniform + 顶点色立方体 | RHI 基础绘制面 |
| 02-metaballs | Rendering with transient buffers and embedding shaders. | Metaball（行进立方体）每帧生成几何写 transient 缓冲；着色器内嵌 | 动态 Mesh 上传（后置项） |
| 03-raymarch | Updating shader uniforms. | 每帧更新 uniform 驱动 raymarching SDF | per-draw uniform 更新节奏 |
| 04-mesh | Loading meshes. | 从资产加载多种网格渲染、相机环绕 | 资产加载/多 mesh 场景 |
| 05-instancing | Geometry instancing. | 几何实例化：动态 instance 数据缓冲、实例数自适应、非实例化降级程序 | GPU Instancing（P2）+ draw call 优化 |
| 06-bump | Loading textures. | 纹理加载 + 法线贴图 bump 光照 | 贴图槽/采样链（已落地，可对照） |
| 07-callback | ...callbacks for taking screen shots, caching OpenGL binary shaders, and video capture. | bgfx 回调接口：截图 / GL 二进制 shader 缓存 / 视频录制 | 截图与产物缓存机制 |
| 08-update | Updating textures. | 运行期纹理更新（`updateTexture2D`） | Texture2D 运行期可变（revision 已落地）对照 |
| 09-hdr | Using multiple views with frame buffers, and view order remapping. | 多 view + framebuffer 组 HDR 管线：skybox→亮度链（128→1 逐级降采样）→bright/blur→tonemap | 后处理链/多 view 编排（三期扩展候选） |
| 10-font | Use the font system to display text and styled text. | 位图字体 + 样式文本 | DebugText 之外的富文本形态 |
| 11-fontsdf | Use a single distance field font to render text of various size. | SDF 字体任意尺度渲染 | 编辑器文字若升级可参考 |
| 12-lod | Mesh LOD transitions. | 多级 LOD 网格 + 距离切换 + 过渡帧混合 | 渲染优化（LOD 后置 P2） |
| 13-stencil | Stencil reflections and shadows. | 模板缓冲：平面镜反射 + 模板阴影 | 模板用法面 |
| 14-shadowvolumes | Shadow volumes. | 阴影体（模板算法）完整演示 | 阴影方案备选（三期主推 shadow map） |
| 15-shadowmaps-simple | Shadow maps example | 基础阴影贴图：深度纹理 + 比较采样 + PCF + 光照投射 | **三期 ShadowPass 入门对照** |
| 16-shadowmaps | Shadow maps example. | 进阶矩阵：定向级联 CSM / 全向 omni × Hard/PCF/PCSS/VSM/ESM × InvZ/线性深度，含级联切分（GPU Gems 3）与 VSM blur pass | **ShadowPass 进阶形态参考** |
| 17-drawstress | Draw stress, maximizing number of draw calls. | encoder 压测海量 draw call，可切实例化/非实例化对照 | 性能基线手法（对照 F3/RenderStats） |
| 18-ibl | Image-based lighting. | 预滤波环境贴图 IBL（irradiance/radiance/BRDF LUT） | 三期光照环境项/GI 占位 |
| 19-oit | Weighted, Blended Order Independent Transparency. | 加权混合 OIT：accum/revealage MRT + 合成，免排序透明 | 透明分桶之上若要 OIT 可参考 |
| 20-nanovg | NanoVG is small antialiased vector graphics rendering library. | 矢量图形（抗锯齿路径）库集成 | 参考面低（编辑器走 Swing） |
| 21-deferred | MRT rendering and deferred shading. | MRT G-buffer + 延迟着色 + 光体积 + 调试视图多模式 | **三期批次④ Deferred 复用** |
| 22-windows | Rendering into multiple windows. | 多 OS 窗口渲染 | 参考面低（引擎单窗口） |
| 23-vectordisplay | Rendering lines as oldschool vectors. | 老式矢量显示风格：线条 + 荧光拖尾（后处理积累） | 后处理积累缓冲思路 |
| 24-nbody | N-body simulation with compute shaders using buffers. | compute shader + storage buffer：N 体模拟写缓冲、图形读回 | **三期批次① SSBO/compute 底座直接对照** |
| 25-c99 | Initialization and debug text with C99 API. | 纯 C99 API 版初始化 + 调试文本 | 参考面低（Java/JNI 路线） |
| 26-occlusion | Using occlusion query for conditional rendering. | 遮挡查询 + 条件渲染 | 剔除方案（视锥剔除也仍后置） |
| 27-terrain | Terrain painting example. | 地形笔刷：`updateTexture2D` 实时改高度图、笔刷大小/力度/抬升 | 运行期纹理更新 + 编辑器刷子交互 |
| 28-wireframe | Drawing wireframe mesh. | 重心坐标线框（vs 传 barycentric，防细三角闪烁） | 编辑器网格线框模式若做可参考 |
| 29-debugdraw | Debug draw. | 调试绘制库：线/盒/球/坐标轴/网格 + 文字 | DebugDrawLine 的完整形态对照 |
| 30-picking | Mouse picking via GPU texture readback. | ID 颜色附件 + 纹理读回拾取 | **与 GPU ID-buffer 拾取同路（Engine_Picking_Design），读回手法对照** |
| 31-rsm | Global Illumination with Reflective Shadow Map. | RSM 单次弹射 GI：反射阴影图生成 VPL 虚拟点光、延迟光照累积 | GI 参考（本引擎拍板探针 DDGI，RSM 是另一流派） |
| 32-particles | Particles. | 精灵粒子系统：CPU 发射器模拟、gizmo 编辑、AABB 查询 | 粒子系统留白时的起步参考 |
| 33-pom | Parallax mapping. | 视差贴图（含自阴影等变体） | 材质/贴图进阶 |
| 34-mvs | Multiple vertex streams. | 多顶点流：位置/颜色分两个顶点缓冲、多流绑定 | **RHI 顶点布局多流支持对照** |
| 35-dynamic | Dynamic buffers update. | 动态顶点/索引缓冲每帧重写 | 动态缓冲（含 buffer staging 遗留项） |
| 36-sky | Perez dynamic sky model. | Perez 全天候动态天空 + 景观光照随天空参数 | **Skybox ClearFlags 挂账项 + 光照环境** |
| 37-gpudrivenrendering | GPU-Driven Rendering. | HZB 降采样 + compute 遮挡剔除 + stream compaction + 实例化间接绘制 | GPU 剔除 / indirect 远期路线 |
| 38-bloom | Bloom. | 泛光后处理链（亮度提取/模糊/合成多 pass） | 后处理链（三期扩展候选） |
| 39-assao | Adaptive Screen Space Ambient Occlusion. | ASSAO（Intel 实现移植）集成 | **GI 第一步 SSAO 拍板的直接参考** |
| 40-svt | Sparse Virtual Textures. | 稀疏虚拟纹理：页表 + 反馈缓冲（渲染 tile/mip 反馈 → 按需载页） | 远期纹理流送 |
| 41-tess | Adaptive GPU Tessellation. | compute 算地形 LOD + indirect dispatch + 细分着色器渲染 | GPU 驱动几何 / tessellation |
| 42-bunnylod | Progressive Mesh LOD | CPU 端渐进网格简化（`ProgressiveMesh`）运行时调 LOD | Mesh LOD 路线 |
| 43-denoise | Denoise. | SVGF 风格时空降噪：时域滤波 + 3×3/5×5 A-Trous 空域 + 可选 TXAA；延迟场景噪声图案 | **GI / 光追降噪参考** |
| 44-sss | Screen Space Shadows. | 屏幕空间阴影（软接触阴影、像素/世界单位半径）+ 屏幕空间次表面散射（deferred） | 阴影补充形态 |
| 45-bokeh | Bokeh Depth of Field | 景深散景：单/双 pass（下采样、线性深度、合成） | 后处理链 |
| 46-fsr | AMD FidelityFX Super Resolution (FSR) | FSR 1：EASU 上采样 + RCAS 锐化（16/32 两套 compute） | 分辨率缩放/后处理 + compute 用法 |
| 47-pixelformats | Texture formats. | 各种纹理格式创建/采样展示（含格式能力探测） | RHI 格式面 / 设备能力查询对照 |
| 48-drawindirect | Simple example of indirect rendering to render multiple different meshes with 1 draw call | 间接绘制：一次 submit 画多个不同 mesh | RHI 间接绘制扩展参考 |
| 49-hextile | Hextile example. | 六边形平铺抗重复：三角格 barycentric 混合 + hash 随机偏移 | 地形/大面积贴图抗重复（27 的进阶） |
| 50-headless | This example demonstrates headless initialization. | 无窗口 headless 初始化（0×0 分辨率）+ 离屏 framebuffer 渲染 + 截图输出 | 离屏批渲染 / 无窗口测试思路 |
| 51-gpufont | GPU Font Rendering (Slug Algorithm) | Slug 算法 GPU 字体：曲线/band 数据结构、Eric Lengyel MIT 参考着色器 | 若要做高质量 GPU 文字 |

## 2. 支撑目录

| 目录 | 内容 | 说明 |
|---|---|---|
| `common/` | `entry/` 应用框架（`AppI`/`runApp`）、`camera`、`bgfx_utils`（mesh/image/program 加载）、`imgui`、`nanovg`、`debugdraw`、`font`、`ps`（粒子）、`cube_atlas`、shader 公共头（`common.sh`/`shaderlib.sh`） | 读任何示例前先看它——所有示例共享的地基 |
| `assets/` | 公共资产：`meshes/`（tree/bunny 等）、`shaders/`、`textures/`、`font/` | 示例运行期数据 |
| `runtime/` | 示例浏览器宿主（列表选择 + 切换重启），配合 `makefile` 构建 | 一次编出全部示例的入口 |

## 3. 与当前排期最贴的几行

- **24**（compute + SSBO 最小闭环）→ 三期光照批次① RHI SSBO/compute 底座；
- **15 / 16**（基础 → 进阶阴影贴图）→ 三期 ShadowPass；
- **21 / 43**（Deferred / SVGF 降噪）→ 三期批次④ Deferred 复用与 GI 降噪衔接；
- **39**（ASSAO）→ GI 第一步 SSAO；
- **18 / 36**（IBL / Perez 天空）→ 光照环境项与 ClearFlags Skybox 挂账项；
- **30**（GPU 读回拾取）→ 与已落地的 GPU ID-buffer 拾取互校；
- **34 / 48 / 35**（多顶点流 / 间接绘制 / 动态缓冲）→ RHI 扩展面与 buffer staging 遗留项。

---

> （注：部分内容可能由 AI 生成）

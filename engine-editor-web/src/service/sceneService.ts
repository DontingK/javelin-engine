import { callJava } from '../bridge/jcefBridge.ts'

/**
 * /scene/tree 树节点 VO（对应 Java 侧 SceneNodeVo；结构对齐通用 TreeNode）。
 */
export interface SceneNodeVo {
  /** Node id（ObjectId 全局唯一，选中上报锚点） */
  id: number
  /** 节点名 */
  name: string
  /** 自身激活态（层级生效判定在 Java 侧；inactive 灰显渐进） */
  active: boolean
  /** 子节点 */
  children: SceneNodeVo[]
}

/**
 * /scene/tree 场景树快照（对应 Java 侧 SceneTreeVo）。
 *
 * <p>ready=false = 场景未就绪（启动竞态：引擎装配可能晚于页面挂载），
 * 其余字段无效——消费方节流重试。</p>
 */
export interface SceneTreeVo {
  /** 场景就绪标记 */
  ready: boolean
  /** 场景 id */
  id: number
  /** 场景名（场景根显示名） */
  name: string
  /** 场景脏标记（标题 {@code *} 显示源） */
  dirty: boolean
  /** 当前选中 Node id（拉取后对齐选中态；无选中 null） */
  selectedNodeId: number | null
  /** 根级节点 */
  entities: SceneNodeVo[]
}

/**
 * 拉取场景树快照（Java 侧经引擎主线程任务队列 submitTask 在帧边界组装）。
 *
 * @returns 场景树快照
 */
export function loadSceneTree(): Promise<SceneTreeVo> {
  return callJava<SceneTreeVo>('/scene/tree', {})
}

/**
 * 场景树选中上报（写 Java 侧 EditorSelection 单源）。
 *
 * @param nodeId 选中 Node id；null = 清除（点击场景根/取消选中）
 */
export function selectNode(nodeId: number | null): Promise<void> {
  return callJava<void>('/scene/select', {nodeId})
}

/**
 * 视图模式（对应 Java 侧 SceneModel 枚举）：中央停靠区「场景/游戏」标签页的渲染分流。
 */
export type SceneModel = 'SCENE' | 'GAME'

/**
 * 切换 Scene/Game 视图模式（Java 侧 EditorScene.sceneModel 直写）。
 *
 * <p>GAME：视口只拉用户场景相机与物体（编辑器相机/网格/Gizmo 不进画面）；
 * SCENE：合并编辑子场景辅助对象。面板 onShow 上报触发。</p>
 *
 * @param model 目标模式
 */
export function setSceneModel(model: SceneModel): Promise<void> {
  return callJava<void>('/scene/setModel', {model})
}

import { callJava } from '../bridge/jcefBridge.ts'

/**
 * 文件信息 VO。
 */
export interface FileVo {
  path: string
  name: string
  directory: boolean
}

/**
 * 创建项目请求。
 */
export interface CreateProjectPar {
  name: string
  directory: string
}

/**
 * 文件路径请求。
 */
export interface FilePathPar {
  path: string
}

/**
 * 获取最近项目列表。
 *
 * @returns 最近项目文件列表
 */
export function getRecentProjects(): Promise<FileVo[]> {
  return callJava<FileVo[]>('/project/recentProjects')
}

/**
 * 打开指定路径的项目。
 *
 * @param par 项目路径请求
 * @returns 项目文件信息
 */
export function openProject(par: FilePathPar): Promise<FileVo> {
  return callJava<FileVo>('/project/openProject', par)
}

/**
 * 创建新项目。
 *
 * @param par 创建项目请求
 * @returns 新建项目文件信息
 */
export function createProject(par: CreateProjectPar): Promise<FileVo> {
  return callJava<FileVo>('/project/createProject', par)
}

/**
 * /project/tree 树节点 VO（对应 Java 侧 ProjectTreeNodeVo；path 充当节点 id，
 * 兼作图标映射锚点）。
 */
export interface ProjectTreeNodeVo {
  /** 相对项目根路径（/ 分隔） */
  path: string
  /** 文件名 */
  name: string
  /** 是否目录 */
  directory: boolean
  /** 子节点（文件为空数组） */
  children: ProjectTreeNodeVo[]
}

/**
 * /project/tree 项目文件树 VO（对应 Java 侧 ProjectTreeVo）。
 *
 * <p>ready=false = 编辑器实例未就绪（启动竞态：页面挂载可能早于引擎装配），
 * 其余字段无效——消费方节流重试，对齐 /scene/tree 口径。</p>
 */
export interface ProjectTreeVo {
  /** 就绪标记 */
  ready: boolean
  /** 根节点（Assets、src） */
  roots?: ProjectTreeNodeVo[]
}

/**
 * 拉取项目文件树（仅 Assets、src 两根；隐藏项与伴生 .meta 由 Java 侧过滤）。
 *
 * @returns 项目文件树
 */
export function loadProjectTree(): Promise<ProjectTreeVo> {
  return callJava<ProjectTreeVo>('/project/tree', {})
}

/**
 * 项目面板选中上报（写 Java 侧 EditorSelection 单源——project 域菜单命令的
 * 上下文源，volatile 直写返回即生效）。
 *
 * <p>右键路径须 await 落上报后再 openMenu——Swing 菜单 enabled 求值读选中态，
 * 防求值读到旧态；单击路径 fire-and-forget。Java 侧有边界闸门（越界/不存在
 * 记 warn 忽略）。</p>
 *
 * @param path 相对项目根的树节点路径；null = 清除选中
 */
export function selectProjectEntry(path: string | null): Promise<void> {
  return callJava<void>('/project/select', {path})
}

import { ref, watch } from 'vue'

/**
 * 项目信息。
 */
export interface ProjectInfo {
  /** 项目唯一标识 */
  id: string
  /** 项目名称 */
  name: string
  /** 项目路径 */
  path: string
}

/** sessionStorage 中项目状态的存储键。 */
const STORAGE_KEY = 'javelin-editor-project'

/**
 * 需要持久化的项目状态。
 */
interface ProjectState {
  /** 当前已打开的项目 */
  currentProject: ProjectInfo | null
}

/**
 * 从 sessionStorage 读取项目状态。
 *
 * @returns 已保存的项目状态；读取失败或不存在时返回默认状态
 */
function loadState(): ProjectState {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (raw) {
      return JSON.parse(raw)
    }
  } catch {
    // 忽略解析异常，使用默认状态
  }
  return { currentProject: null }
}

/**
 * 将项目状态写入 sessionStorage。
 *
 * @param state 项目状态
 */
function saveState(state: ProjectState): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    // 忽略存储异常（如隐私模式）
  }
}

const initialState = loadState()

const currentProject = ref<ProjectInfo | null>(initialState.currentProject)
const loadingProject = ref<ProjectInfo | null>(null)

/**
 * 监听项目状态变化并持久化。
 *
 * <p>loadingProject 为临时状态，刷新后不清除会导致界面卡死，因此只持久化 currentProject。</p>
 */
watch(
  currentProject,
  (value) => {
    saveState({ currentProject: value })
  },
  { deep: true }
)

/**
 * 开始加载项目。
 *
 * <p>在调用后端打开/创建接口前设置，用于显示项目加载页面。</p>
 *
 * @param project 项目信息
 */
function startLoadingProject(project: ProjectInfo): void {
  loadingProject.value = project
}

/**
 * 打开指定项目。
 *
 * <p>后端接口返回成功后调用，进入编辑器主界面。</p>
 *
 * @param project 项目信息
 */
function openProject(project: ProjectInfo): void {
  currentProject.value = project
  loadingProject.value = null
}

/**
 * 取消项目加载。
 *
 * <p>后端接口失败时调用，回到项目选择页面。</p>
 */
function cancelLoadingProject(): void {
  loadingProject.value = null
}

/**
 * 关闭当前项目，返回项目选择页面。
 */
function closeProject(): void {
  currentProject.value = null
  loadingProject.value = null
}

/**
 * 项目状态组合式函数。
 */
export function useProject() {
  return {
    currentProject,
    loadingProject,
    startLoadingProject,
    openProject,
    cancelLoadingProject,
    closeProject
  }
}

if (typeof window !== 'undefined') {
  const win = window as unknown as Record<string, unknown>
  if (!win.javelin) {
    win.javelin = {}
  }
  ;(win.javelin as Record<string, unknown>).project = {
    startLoadingProject,
    openProject,
    cancelLoadingProject,
    closeProject,
    currentProject,
    loadingProject
  }
}

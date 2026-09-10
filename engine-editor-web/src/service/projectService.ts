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

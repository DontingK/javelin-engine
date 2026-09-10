import { callJava } from '../bridge/jcefBridge.ts'

/**
 * 打开系统目录选择对话框。
 *
 * <p>调用 Java 后端的文件系统服务，弹出原生目录选择对话框。</p>
 *
 * @returns 用户选择的目录路径，取消时返回空字符串
 */
export function selectDirectory(): Promise<string> {
    return callJava<string>('/filesystem/select/dir', {})
}

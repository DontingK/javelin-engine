/**
 * 后端统一响应结构。
 *
 * <p>对应后端 {@code com.javelin.editor.web.domain.vo.ApiResponse<T>}。</p>
 */
export interface ApiResponse<T> {
  /** 状态码，200 表示成功 */
  code: number
  /** 提示信息 */
  message: string
  /** 响应数据 */
  data: T
}

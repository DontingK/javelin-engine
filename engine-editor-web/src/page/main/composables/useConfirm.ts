import { ref } from 'vue'

/**
 * 确认弹窗选项。
 */
export interface ConfirmOptions {
  /** 弹窗类型：info / error / warning */
  type?: 'info' | 'error' | 'warning'
  /** 标题 */
  title?: string
  /** 提示内容 */
  message?: string
  /** 确认按钮文字 */
  okText?: string
  /** 取消按钮文字 */
  cancelText?: string
  /** 确认回调 */
  ok?: () => void
  /** 取消回调 */
  cancel?: () => void
}

type RequiredConfirmOptions = Required<ConfirmOptions>

const visible = ref(false)
const options = ref<RequiredConfirmOptions>({
  type: 'info',
  title: '提示',
  message: '',
  okText: '确定',
  cancelText: '取消',
  ok: () => {},
  cancel: () => {}
})

/**
 * 显示确认弹窗。
 *
 * @param opts 弹窗选项
 */
function show(opts: ConfirmOptions): void {
  options.value = {
    type: 'info',
    title: '提示',
    message: '',
    okText: '确定',
    cancelText: '取消',
    ok: () => {},
    cancel: () => {},
    ...opts
  }
  visible.value = true
}

/**
 * 隐藏确认弹窗。
 */
function hide(): void {
  visible.value = false
}

/**
 * 处理确认。
 */
function handleOk(): void {
  options.value.ok?.()
  hide()
}

/**
 * 处理取消。
 */
function handleCancel(): void {
  options.value.cancel?.()
  hide()
}

/**
 * 全局确认弹窗对象。
 *
 * <p>调用方式：{@code confirm.show({ type: 'error', title: '错误', message: '...', ok: () => {}, cancel: () => {} })}</p>
 */
export const confirm = {
  show
}

/**
 * 确认弹窗组合式函数。
 *
 * <p>供 {@link ConfirmDialog} 组件使用。</p>
 */
export function useConfirm() {
  return {
    visible,
    options,
    handleOk,
    handleCancel
  }
}

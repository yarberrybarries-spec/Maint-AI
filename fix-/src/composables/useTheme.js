import { ref } from 'vue'

/**
 * 全局主题初始化。
 * 现在产品统一使用青灰墨配色，不再提供运行时换色入口。
 */

export const THEMES = [
  { key: 'cool', name: '青灰墨', color: '#2f7488' },
]

const current = ref('cool')

/** 固定应用唯一的青灰墨主题，清理历史主题偏好。 */
function apply() {
  current.value = 'cool'
  const root = document.documentElement
  root.setAttribute('data-theme', 'cool')
  try { localStorage.removeItem('wx_theme') } catch (e) { /* ignore */ }
}

/** 应用启动时调用，在 mount 前固定主题以避免首屏闪烁。 */
export function initTheme() {
  apply()
}

export function useTheme() {
  return { current, themes: THEMES, setTheme: apply }
}

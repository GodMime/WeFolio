const MENU_WIDTH = 238
const MENU_HEIGHT = 104
const VIEWPORT_MARGIN = 12
const TRIGGER_GAP = 10
const ARROW_SIZE = 8
const ARROW_INSET = 16

/** 页面提供说明文案，避免把正式端的保存语义带入体验版。 */
function buildPreviewVersionOptions({ draftDescription, publishedDescription }) {
  return [
    { scope: 'draft', label: '草稿版', description: draftDescription },
    { scope: 'published', label: '发布版', description: publishedDescription }
  ]
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

/** 使用视口坐标定位 root-portal 菜单；空间不足时不遮盖触发按钮。 */
function calculatePreviewVersionMenuLayout(rect, viewport = {}, { preferAbove = false } = {}) {
  if (!rect) return null
  const { windowWidth, windowHeight } = viewport
  const left = rect.left
  const top = rect.top
  const right = Number.isFinite(rect.right) ? rect.right : left + rect.width
  const bottom = Number.isFinite(rect.bottom) ? rect.bottom : top + rect.height
  if (![windowWidth, windowHeight, left, top, right, bottom].every(Number.isFinite)
    || windowWidth <= 0 || windowHeight <= 0 || right <= left || bottom <= top
    || left >= windowWidth || right <= 0 || top >= windowHeight || bottom <= 0) {
    return null
  }

  const safeArea = viewport.safeArea || {}
  const safeLeft = Number.isFinite(safeArea.left) ? Math.max(0, safeArea.left) : 0
  const safeRight = Number.isFinite(safeArea.right) ? Math.min(windowWidth, safeArea.right) : windowWidth
  const safeTop = Number.isFinite(safeArea.top) ? Math.max(0, safeArea.top) : 0
  const safeBottom = Number.isFinite(safeArea.bottom) ? Math.min(windowHeight, safeArea.bottom) : windowHeight
  const minLeft = safeLeft + VIEWPORT_MARGIN
  const maxRight = safeRight - VIEWPORT_MARGIN
  const minTop = safeTop + VIEWPORT_MARGIN
  const maxBottom = safeBottom - VIEWPORT_MARGIN
  const width = Math.min(MENU_WIDTH, maxRight - minLeft)
  if (width < ARROW_INSET * 2 + ARROW_SIZE) return null

  const aboveTop = top - TRIGGER_GAP - MENU_HEIGHT
  const belowTop = bottom + TRIGGER_GAP
  const fitsAbove = aboveTop >= minTop && top - TRIGGER_GAP <= maxBottom
  const fitsBelow = belowTop >= minTop && belowTop + MENU_HEIGHT <= maxBottom
  if (!fitsAbove && !fitsBelow) return null
  const placement = preferAbove ? (fitsAbove ? 'above' : 'below') : (fitsBelow ? 'below' : 'above')
  const menuTop = placement === 'above' ? aboveTop : belowTop
  const menuLeft = clamp(right - width, minLeft, maxRight - width)
  const arrowLeft = clamp((left + right) / 2 - menuLeft - ARROW_SIZE / 2, ARROW_INSET, width - ARROW_INSET - ARROW_SIZE)
  return {
    placement,
    menuStyle: `left: ${menuLeft}px; top: ${menuTop}px; width: ${width}px;`,
    arrowStyle: `left: ${arrowLeft}px;`
  }
}

module.exports = { buildPreviewVersionOptions, calculatePreviewVersionMenuLayout }

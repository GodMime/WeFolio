const IOS_CONTENT_HEIGHT = 44
const ANDROID_CONTENT_HEIGHT = 48
const PLATFORM_ANDROID = 'android'

function toFiniteNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function toNonNegativeNumber(value, fallback = 0) {
  const number = toFiniteNumber(value)
  return number !== null && number >= 0 ? number : fallback
}

function formatPx(value) {
  const rounded = Math.round(value * 100) / 100
  return `${rounded}px`
}

function resolveStatusBarHeight(windowInfo = {}, systemInfo = {}) {
  const statusBarHeight = toFiniteNumber(windowInfo.statusBarHeight)
  if (statusBarHeight !== null && statusBarHeight >= 0) {
    return statusBarHeight
  }

  const fallbackStatusBarHeight = toFiniteNumber(systemInfo.statusBarHeight)
  if (fallbackStatusBarHeight !== null && fallbackStatusBarHeight >= 0) {
    return fallbackStatusBarHeight
  }

  return toNonNegativeNumber(windowInfo.safeArea && windowInfo.safeArea.top, 0)
}

function resolveMenuButtonMetrics(menuButtonRect = {}, statusBarHeight, defaultContentHeight) {
  const menuTop = toFiniteNumber(menuButtonRect.top)
  const menuHeight = toFiniteNumber(menuButtonRect.height)

  if (menuTop === null || menuHeight === null || menuHeight <= 0) {
    return {
      totalHeight: statusBarHeight + defaultContentHeight,
      contentTop: statusBarHeight
    }
  }

  const verticalGap = Math.max(menuTop - statusBarHeight, 0)
  return {
    totalHeight: statusBarHeight + menuHeight + verticalGap * 2,
    contentTop: statusBarHeight
  }
}

function buildNavigationBarLayout({
  deviceInfo = {},
  menuButtonRect = {},
  windowInfo = {},
  systemInfo = {}
} = {}) {
  const platform = deviceInfo.platform || systemInfo.platform || ''
  const isAndroid = platform === PLATFORM_ANDROID
  const defaultContentHeight = isAndroid ? ANDROID_CONTENT_HEIGHT : IOS_CONTENT_HEIGHT
  const windowWidth = toNonNegativeNumber(windowInfo.windowWidth, toNonNegativeNumber(systemInfo.windowWidth, 0))
  const menuLeft = toNonNegativeNumber(menuButtonRect.left, windowWidth)
  const rightInset = Math.max(windowWidth - menuLeft, 0)
  const statusBarHeight = resolveStatusBarHeight(windowInfo, systemInfo)
  const metrics = resolveMenuButtonMetrics(menuButtonRect, statusBarHeight, defaultContentHeight)

  return {
    ios: !isAndroid,
    innerPaddingRight: `padding-right: ${formatPx(rightInset)}`,
    leftWidth: `width: ${formatPx(rightInset)}`,
    safeAreaTop: `height: ${formatPx(metrics.totalHeight)}; padding-top: ${formatPx(metrics.contentTop)}`
  }
}

module.exports = {
  buildNavigationBarLayout
}

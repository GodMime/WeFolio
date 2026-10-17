const { buildNavigationBarLayout } = require('../../../utils/navigation-bar-layout')
const FONT_OPENING_TIMEOUT_MS = 3000
const GRID_SELECTOR = '.portfolio-font-grid'

/** 保留正文节点供测量，字体注册和最终排版共享一次首屏等待预算。 */
function createPortfolioOpening(page, { setData = page.setData.bind(page), onTimeout = () => {} } = {}) {
  const api = typeof wx === 'undefined' ? {} : wx
  const nextTick = callback => typeof api.nextTick === 'function' ? api.nextTick(callback) : Promise.resolve().then(callback)
  let sequence = 0, timer = null, disposed = false, visible = true, settled = false
  let fallback = false, waitForLayout = false, resumeAudio = false
  const current = ticket => !disposed && ticket === sequence
  const grids = () => typeof page.selectAllComponents === 'function' ? page.selectAllComponents(GRID_SELECTOR) || [] : []
  function notify() {
    if (disposed || !visible || !settled) return
    if (typeof page.onPortfolioFontsReady === 'function') page.onPortfolioFontsReady()
    if (resumeAudio && typeof page.handleToggleBackgroundAudio === 'function') { resumeAudio = false; page.handleToggleBackgroundAudio() }
  }
  function finish(ticket) {
    if (!current(ticket)) return
    clearTimeout(timer)
    settled = true
    setData({ fontOpening: false, fontOpeningVisible: false }, notify)
  }
  function begin(waitForFonts) {
    const ticket = ++sequence
    clearTimeout(timer)
    settled = false
    fallback = false
    waitForLayout = waitForFonts
    if (waitForFonts) {
      setData({ fontOpening: true, fontOpeningVisible: true })
      if (page.browserContext) page.browserContext.setDisplayable(false)
      if (page.data.backgroundAudioPlaying && typeof page.pauseBackgroundAudio === 'function') { resumeAudio = true; page.pauseBackgroundAudio() }
      timer = setTimeout(() => {
        if (!current(ticket)) return
        fallback = true
        onTimeout()
        // 不让迟到的注册覆盖回退字体；测量异常时网格自然展开，保证能够阅读。
        setData({ fontContext: { families: {}, versions: {}, revision: ticket } }, () => nextTick(() => {
          if (!current(ticket)) return
          const tasks = grids().map(grid => typeof grid.useNaturalFontLayout === 'function' ? grid.useNaturalFontLayout() : undefined)
          Promise.all(tasks).then(() => finish(ticket))
        }))
      }, FONT_OPENING_TIMEOUT_MS)
    }
    return ticket
  }
  function commit(ticket, patch) {
    if (!current(ticket) || fallback) return
    setData(patch, () => nextTick(() => {
      if (!current(ticket) || fallback) return
      const tasks = (waitForLayout ? grids() : []).map(grid => typeof grid.waitForFontLayout === 'function' ? grid.waitForFontLayout() : undefined)
      Promise.all(tasks).then(() => { if (!fallback) finish(ticket) })
    }))
  }
  // 与正式导航栏使用同一高度算法，安卓、刘海屏均不遮挡返回入口。
  const layout = buildNavigationBarLayout({
    windowInfo: api.getWindowInfo ? api.getWindowInfo() : {},
    deviceInfo: api.getDeviceInfo ? api.getDeviceInfo() : {},
    systemInfo: (!api.getWindowInfo || !api.getDeviceInfo) && api.getSystemInfoSync ? api.getSystemInfoSync() : {},
    menuButtonRect: api.getMenuButtonBoundingClientRect ? api.getMenuButtonBoundingClientRect() : {}
  })
  setData({ fontOpeningTop: parseFloat(layout.safeAreaTop.slice('height: '.length)), fontOpening: false, fontOpeningVisible: false })
  return { begin, commit, notify, hide() { visible = false; resumeAudio = false }, show() { visible = true; notify() },
    cancel() { sequence++; clearTimeout(timer); settled = false; resumeAudio = false; setData({ fontOpening: false, fontOpeningVisible: false }) },
    dispose() { disposed = true; sequence++; clearTimeout(timer) } }
}

/** 内容可见后才允许背景音频与访客停留计时启动。 */
const portfolioOpeningPageMethods = {
  onPortfolioFontsReady() {
    if (this.data.loading || this.data.errorMessage || this.data.fontOpening) return
    const portfolio = this.data.portfolio || {}
    if (this.browserContext) {
      this.browserContext.setDisplayable(!portfolio.underMaintenance)
      if (this.singleWorkPageVisible || this.visitPageVisible) this.browserContext.show(this)
    }
    if (!portfolio.underMaintenance && this.syncBackgroundAudio) this.syncBackgroundAudio(portfolio.backgroundAudio, true)
  },
  blockPortfolioOpeningTouch() {}
}
module.exports = { createPortfolioOpening, portfolioOpeningPageMethods, FONT_OPENING_TIMEOUT_MS }

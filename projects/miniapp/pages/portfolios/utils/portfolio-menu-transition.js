const PORTFOLIO_MENU_EXIT_DURATION_MS = 120
const PORTFOLIO_MENU_ENTER_DURATION_MS = 220

/**
 * 根据底部菜单顺序生成当前切换的退出、进入动画类。
 *
 * @param {Object} portfolio 当前作品集渲染数据
 * @param {string} menuKey 目标菜单键
 * @returns {{exitClass: string, enterClass: string}|null} 有效切换对应的动画类
 */
function resolvePortfolioMenuTransition(portfolio = {}, menuKey = '') {
  const bottomNav = portfolio.bottomNav || {}
  const items = bottomNav.enabled && Array.isArray(bottomNav.items)
    ? bottomNav.items
    : []
  const targetMenuKey = typeof menuKey === 'string' ? menuKey.trim() : ''
  const currentIndex = items.findIndex((item) => item && item.key === portfolio.activeMenuKey)
  const targetIndex = items.findIndex((item) => item && item.key === targetMenuKey)
  if (currentIndex < 0 || targetIndex < 0 || currentIndex === targetIndex) {
    return null
  }
  const direction = targetIndex > currentIndex ? 'forward' : 'backward'
  return {
    exitClass: `portfolio-menu-exit-${direction}`,
    enterClass: `portfolio-menu-enter-${direction}`
  }
}

/**
 * 清理页面上尚未执行的作品集菜单动画计时器。
 *
 * @param {Object} page 作品集页面实例
 */
function clearPortfolioMenuTransitionTimers(page) {
  if (page.portfolioMenuExitTimer !== null && page.portfolioMenuExitTimer !== undefined) {
    clearTimeout(page.portfolioMenuExitTimer)
  }
  if (page.portfolioMenuEnterTimer !== null && page.portfolioMenuEnterTimer !== undefined) {
    clearTimeout(page.portfolioMenuEnterTimer)
  }
  page.portfolioMenuExitTimer = null
  page.portfolioMenuEnterTimer = null
  page.portfolioMenuTransitionRevision =
    Number(page.portfolioMenuTransitionRevision || 0) + 1
}

/**
 * 在页面当前内容退出后切换菜单，再播放目标内容进入动画。
 *
 * @param {Object} page 作品集页面实例
 * @param {string} menuKey 目标菜单键
 * @param {Object} options 页面切换适配项
 * @returns {boolean} 是否成功启动切换
 */
function startPortfolioMenuTransition(page, menuKey, options = {}) {
  const transition = resolvePortfolioMenuTransition(page.data.portfolio, menuKey)
  if (
    !transition ||
    page.data.portfolioMenuSwitching ||
    typeof options.switchPortfolio !== 'function'
  ) {
    return false
  }

  clearPortfolioMenuTransitionTimers(page)
  const transitionRevision = page.portfolioMenuTransitionRevision
  const isCurrentTransition = () => {
    return page.portfolioMenuTransitionRevision === transitionRevision
  }
  if (typeof options.onBeforeExit === 'function') {
    options.onBeforeExit()
  }
  page.setData(Object.assign({}, options.exitPatch || {}, {
    portfolioMenuSwitching: true,
    portfolioMenuTransitionClass: transition.exitClass
  }), () => {
    if (!isCurrentTransition()) {
      return
    }
    page.portfolioMenuExitTimer = setTimeout(() => {
      page.portfolioMenuExitTimer = null
      if (!isCurrentTransition()) {
        return
      }
      page.setData({
        portfolio: options.switchPortfolio(page.data.portfolio, menuKey),
        portfolioScrollTop: 1,
        portfolioMenuTransitionClass: transition.enterClass
      }, () => {
        if (!isCurrentTransition()) {
          return
        }
        page.setData({ portfolioScrollTop: 0 })
        page.portfolioMenuEnterTimer = setTimeout(() => {
          page.portfolioMenuEnterTimer = null
          if (!isCurrentTransition()) {
            return
          }
          page.setData({
            portfolioMenuSwitching: false,
            portfolioMenuTransitionClass: ''
          })
        }, PORTFOLIO_MENU_ENTER_DURATION_MS)
      })
    }, PORTFOLIO_MENU_EXIT_DURATION_MS)
  })

  return true
}

module.exports = {
  PORTFOLIO_MENU_ENTER_DURATION_MS,
  PORTFOLIO_MENU_EXIT_DURATION_MS,
  clearPortfolioMenuTransitionTimers,
  resolvePortfolioMenuTransition,
  startPortfolioMenuTransition
}

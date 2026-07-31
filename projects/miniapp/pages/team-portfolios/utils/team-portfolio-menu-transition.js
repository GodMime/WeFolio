const TEAM_PORTFOLIO_MENU_EXIT_DURATION_MS = 120
const TEAM_PORTFOLIO_MENU_ENTER_DURATION_MS = 220

function resolveTeamPortfolioMenuTransition(portfolio = {}, menuKey = '') {
  const bottomNav = portfolio.bottomNav || {}
  const items = bottomNav.enabled && Array.isArray(bottomNav.items)
    ? bottomNav.items
    : []
  const targetKey = String(menuKey || '').trim()
  const currentIndex = items.findIndex((item) => item && item.key === portfolio.activeMenuKey)
  const targetIndex = items.findIndex((item) => item && item.key === targetKey)
  if (currentIndex < 0 || targetIndex < 0 || currentIndex === targetIndex) return null
  const direction = targetIndex > currentIndex ? 'forward' : 'backward'
  return {
    exitClass: `team-portfolio-menu-exit-${direction}`,
    enterClass: `team-portfolio-menu-enter-${direction}`
  }
}

function clearTeamPortfolioMenuTransitionTimers(page) {
  if (page.teamPortfolioMenuExitTimer !== null && page.teamPortfolioMenuExitTimer !== undefined) {
    clearTimeout(page.teamPortfolioMenuExitTimer)
  }
  if (page.teamPortfolioMenuEnterTimer !== null && page.teamPortfolioMenuEnterTimer !== undefined) {
    clearTimeout(page.teamPortfolioMenuEnterTimer)
  }
  page.teamPortfolioMenuExitTimer = null
  page.teamPortfolioMenuEnterTimer = null
  page.teamPortfolioMenuTransitionRevision =
    Number(page.teamPortfolioMenuTransitionRevision || 0) + 1
}

function clearTeamPortfolioMenuComponentState(state = {}, components = []) {
  const next = Object.assign({}, state)
  ;(Array.isArray(components) ? components : []).forEach((component) => {
    const componentKey = component && String(component.componentKey || '').trim()
    if (componentKey) delete next[componentKey]
  })
  return next
}

function captureTeamPortfolioMenuInteraction(page) {
  return {
    menuKey: String(
      page && page.data && page.data.portfolio &&
      page.data.portfolio.activeMenuKey || ''
    ),
    transitionRevision: Number(
      page && page.teamPortfolioMenuTransitionRevision || 0
    )
  }
}

function isTeamPortfolioMenuInteractionCurrent(page, interaction = {}) {
  const activeMenuKey = String(
    page && page.data && page.data.portfolio &&
    page.data.portfolio.activeMenuKey || ''
  )
  return activeMenuKey === String(interaction.menuKey || '') &&
    Number(page && page.teamPortfolioMenuTransitionRevision || 0) ===
      Number(interaction.transitionRevision || 0)
}

function startTeamPortfolioMenuTransition(page, menuKey, options = {}) {
  const transition = resolveTeamPortfolioMenuTransition(page.data.portfolio, menuKey)
  if (!transition ||
      page.data.teamPortfolioMenuSwitching ||
      typeof options.switchPortfolio !== 'function') {
    return false
  }
  clearTeamPortfolioMenuTransitionTimers(page)
  const revision = page.teamPortfolioMenuTransitionRevision
  const isCurrent = () => page.teamPortfolioMenuTransitionRevision === revision
  if (typeof options.onBeforeExit === 'function') options.onBeforeExit()
  page.setData(Object.assign({}, options.exitPatch || {}, {
    teamPortfolioMenuSwitching: true,
    teamPortfolioMenuTransitionClass: transition.exitClass
  }), () => {
    if (!isCurrent()) return
    page.teamPortfolioMenuExitTimer = setTimeout(() => {
      page.teamPortfolioMenuExitTimer = null
      if (!isCurrent()) return
      const portfolio = options.switchPortfolio(page.data.portfolio, menuKey)
      const switchPatch = typeof options.buildSwitchPatch === 'function'
        ? options.buildSwitchPatch(portfolio)
        : {}
      page.setData(Object.assign({
        portfolio,
        portfolioScrollTop: 1,
        teamPortfolioMenuTransitionClass: transition.enterClass
      }, switchPatch), () => {
        if (!isCurrent()) return
        page.setData({ portfolioScrollTop: 0 })
        page.teamPortfolioMenuEnterTimer = setTimeout(() => {
          page.teamPortfolioMenuEnterTimer = null
          if (!isCurrent()) return
          page.setData({
            teamPortfolioMenuSwitching: false,
            teamPortfolioMenuTransitionClass: ''
          })
        }, TEAM_PORTFOLIO_MENU_ENTER_DURATION_MS)
      })
    }, TEAM_PORTFOLIO_MENU_EXIT_DURATION_MS)
  })
  return true
}

module.exports = {
  TEAM_PORTFOLIO_MENU_ENTER_DURATION_MS,
  TEAM_PORTFOLIO_MENU_EXIT_DURATION_MS,
  captureTeamPortfolioMenuInteraction,
  clearTeamPortfolioMenuComponentState,
  clearTeamPortfolioMenuTransitionTimers,
  isTeamPortfolioMenuInteractionCurrent,
  resolveTeamPortfolioMenuTransition,
  startTeamPortfolioMenuTransition
}

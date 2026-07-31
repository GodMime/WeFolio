const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MODULE_PATH = path.join(
  __dirname,
  '../pages/team-portfolios/utils/team-portfolio-menu-transition.js'
)

function loadModule() {
  delete require.cache[require.resolve(MODULE_PATH)]
  return require(MODULE_PATH)
}

function portfolio(activeMenuKey = 'nav_home') {
  return {
    activeMenuKey,
    components: [{ componentKey: 'home' }],
    activeComponents: activeMenuKey === 'nav_works'
      ? [{ componentKey: 'works' }]
      : [{ componentKey: 'home' }],
    bottomNav: {
      enabled: true,
      items: [
        { key: 'nav_home', title: '首页' },
        { key: 'nav_works', title: '作品', components: [{ componentKey: 'works' }] }
      ]
    }
  }
}

test('team menu transition is independent and follows menu order', () => {
  const { resolveTeamPortfolioMenuTransition } = loadModule()
  assert.deepEqual(resolveTeamPortfolioMenuTransition(portfolio(), 'nav_works'), {
    exitClass: 'team-portfolio-menu-exit-forward',
    enterClass: 'team-portfolio-menu-enter-forward'
  })
  assert.deepEqual(resolveTeamPortfolioMenuTransition(portfolio('nav_works'), 'nav_home'), {
    exitClass: 'team-portfolio-menu-exit-backward',
    enterClass: 'team-portfolio-menu-enter-backward'
  })
  assert.equal(resolveTeamPortfolioMenuTransition(portfolio(), 'missing'), null)
})

test('team menu transition exits then switches and enters', () => {
  const {
    TEAM_PORTFOLIO_MENU_ENTER_DURATION_MS,
    TEAM_PORTFOLIO_MENU_EXIT_DURATION_MS,
    startTeamPortfolioMenuTransition
  } = loadModule()
  const originalSetTimeout = global.setTimeout
  const timers = []
  global.setTimeout = (handler, delay) => {
    timers.push({ handler, delay })
    return timers.length
  }
  try {
    const page = {
      teamPortfolioMenuExitTimer: null,
      teamPortfolioMenuEnterTimer: null,
      data: {
        portfolio: portfolio(),
        teamPortfolioMenuSwitching: false,
        teamPortfolioMenuTransitionClass: '',
        portfolioScrollTop: 88
      },
      setData(patch, callback) {
        Object.assign(this.data, patch)
        if (callback) callback()
      }
    }
    const started = startTeamPortfolioMenuTransition(page, 'nav_works', {
      switchPortfolio(value, menuKey) {
        return Object.assign({}, value, {
          activeMenuKey: menuKey,
          activeComponents: value.bottomNav.items[1].components
        })
      }
    })
    assert.equal(started, true)
    assert.equal(page.data.teamPortfolioMenuTransitionClass, 'team-portfolio-menu-exit-forward')
    assert.equal(timers[0].delay, TEAM_PORTFOLIO_MENU_EXIT_DURATION_MS)
    timers[0].handler()
    assert.equal(page.data.portfolio.activeMenuKey, 'nav_works')
    assert.equal(page.data.teamPortfolioMenuTransitionClass, 'team-portfolio-menu-enter-forward')
    assert.equal(timers[1].delay, TEAM_PORTFOLIO_MENU_ENTER_DURATION_MS)
    timers[1].handler()
    assert.equal(page.data.teamPortfolioMenuSwitching, false)
  } finally {
    global.setTimeout = originalSetTimeout
  }
})

test('team menu transition uses the fixed mirrored 24rpx displacement', () => {
  const wxss = fs.readFileSync(path.join(
    __dirname,
    '../pages/team-portfolios/styles/team-portfolio-menu-transition.wxss'
  ), 'utf8')
  assert.match(wxss, /translate3d\(-24rpx,\s*0,\s*0\)/)
  assert.match(wxss, /translate3d\(24rpx,\s*0,\s*0\)/)
  assert.doesNotMatch(wxss, /translate3d\((?:-?20|-?28)rpx,/)
})

test('team menu transition clears only state owned by the menu being left', () => {
  const { clearTeamPortfolioMenuComponentState } = loadModule()
  assert.deepEqual(clearTeamPortfolioMenuComponentState({
    home: { value: 1 },
    works: { value: 2 },
    contact: { value: 3 }
  }, [
    { componentKey: 'home' },
    { componentKey: 'contact' }
  ]), {
    works: { value: 2 }
  })
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const TRANSITION_MODULE_PATH = path.join(
  __dirname,
  '../pages/portfolios/utils/portfolio-menu-transition.js'
)

function loadTransitionModule() {
  assert.equal(
    fs.existsSync(TRANSITION_MODULE_PATH),
    true,
    '作品集菜单切换需要共享过渡控制器'
  )
  delete require.cache[require.resolve(TRANSITION_MODULE_PATH)]
  return require(TRANSITION_MODULE_PATH)
}

function readProjectFile(relativePath) {
  return fs.readFileSync(path.join(__dirname, '..', relativePath), 'utf8')
}

function createPortfolio(activeMenuKey = 'home') {
  const homeComponents = [{ componentKey: 'c_home' }]
  const worksComponents = [{ componentKey: 'c_works' }]
  return {
    components: homeComponents,
    activeMenuKey,
    activeComponents: activeMenuKey === 'works' ? worksComponents : homeComponents,
    bottomNav: {
      enabled: true,
      items: [
        { key: 'home', title: '主页' },
        { key: 'works', title: '作品', components: worksComponents }
      ]
    }
  }
}

function switchPortfolio(portfolio, menuKey) {
  const target = portfolio.bottomNav.items.find((item) => item.key === menuKey)
  const first = portfolio.bottomNav.items[0]
  return Object.assign({}, portfolio, {
    activeMenuKey: target.key,
    activeComponents: target.key === first.key
      ? portfolio.components
      : target.components
  })
}

function createPage(activeMenuKey = 'home') {
  return {
    portfolioMenuExitTimer: null,
    portfolioMenuEnterTimer: null,
    data: {
      contactFormModalVisible: true,
      portfolio: createPortfolio(activeMenuKey),
      portfolioMenuSwitching: false,
      portfolioMenuTransitionClass: '',
      portfolioScrollTop: 88
    },
    setData(patch, callback) {
      Object.assign(this.data, patch)
      if (callback) {
        callback()
      }
    }
  }
}

function createQueuedPage(activeMenuKey = 'home') {
  const page = createPage(activeMenuKey)
  const setDataCallbacks = []
  page.setData = function(patch, callback) {
    Object.assign(this.data, patch)
    if (callback) {
      setDataCallbacks.push(callback)
    }
  }
  page.flushNextSetData = function() {
    const callback = setDataCallbacks.shift()
    if (callback) {
      callback()
    }
  }
  return page
}

function installControlledTimers() {
  const originalSetTimeout = global.setTimeout
  const originalClearTimeout = global.clearTimeout
  const timers = []
  const clearedTimerIds = []
  global.setTimeout = (handler, delay) => {
    const timer = {
      handler,
      delay,
      id: `timer-${timers.length + 1}`
    }
    timers.push(timer)
    return timer.id
  }
  global.clearTimeout = (timerId) => {
    clearedTimerIds.push(timerId)
  }
  return {
    timers,
    clearedTimerIds,
    restore() {
      global.setTimeout = originalSetTimeout
      global.clearTimeout = originalClearTimeout
    }
  }
}

test('portfolio menu transition exits before switching and then enters in navigation order', () => {
  const {
    PORTFOLIO_MENU_ENTER_DURATION_MS,
    PORTFOLIO_MENU_EXIT_DURATION_MS,
    startPortfolioMenuTransition
  } = loadTransitionModule()
  const controlledTimers = installControlledTimers()

  try {
    const page = createPage()
    let beforeExitCount = 0

    const started = startPortfolioMenuTransition(page, 'works', {
      onBeforeExit() {
        beforeExitCount += 1
      },
      exitPatch: {
        contactFormModalVisible: false
      },
      switchPortfolio
    })

    assert.equal(started, true)
    assert.equal(beforeExitCount, 1)
    assert.equal(page.data.contactFormModalVisible, false)
    assert.equal(page.data.portfolioMenuSwitching, true)
    assert.equal(page.data.portfolioMenuTransitionClass, 'portfolio-menu-exit-forward')
    assert.equal(page.data.portfolio.activeMenuKey, 'home')
    assert.deepEqual(page.data.portfolio.activeComponents, [{ componentKey: 'c_home' }])
    assert.equal(controlledTimers.timers.length, 1)
    assert.equal(controlledTimers.timers[0].delay, PORTFOLIO_MENU_EXIT_DURATION_MS)

    const duplicateStarted = startPortfolioMenuTransition(page, 'works', {
      switchPortfolio
    })
    assert.equal(duplicateStarted, false)
    assert.equal(controlledTimers.timers.length, 1)

    controlledTimers.timers[0].handler()

    assert.equal(page.data.portfolio.activeMenuKey, 'works')
    assert.deepEqual(page.data.portfolio.activeComponents, [{ componentKey: 'c_works' }])
    assert.equal(page.data.portfolioMenuTransitionClass, 'portfolio-menu-enter-forward')
    assert.equal(page.data.portfolioScrollTop, 0)
    assert.equal(controlledTimers.timers.length, 2)
    assert.equal(controlledTimers.timers[1].delay, PORTFOLIO_MENU_ENTER_DURATION_MS)

    controlledTimers.timers[1].handler()

    assert.equal(page.data.portfolioMenuSwitching, false)
    assert.equal(page.data.portfolioMenuTransitionClass, '')
    assert.equal(page.portfolioMenuExitTimer, null)
    assert.equal(page.portfolioMenuEnterTimer, null)
  } finally {
    controlledTimers.restore()
  }
})

test('portfolio menu transition mirrors its direction and rejects invalid targets', () => {
  const {
    resolvePortfolioMenuTransition,
    startPortfolioMenuTransition
  } = loadTransitionModule()
  const page = createPage('works')

  assert.deepEqual(
    resolvePortfolioMenuTransition(page.data.portfolio, 'home'),
    {
      exitClass: 'portfolio-menu-exit-backward',
      enterClass: 'portfolio-menu-enter-backward'
    }
  )
  assert.equal(resolvePortfolioMenuTransition(page.data.portfolio, 'works'), null)
  assert.equal(resolvePortfolioMenuTransition(page.data.portfolio, 'missing'), null)
  assert.equal(
    resolvePortfolioMenuTransition(
      Object.assign({}, page.data.portfolio, {
        bottomNav: { enabled: false, items: [] }
      }),
      'home'
    ),
    null
  )
  assert.equal(
    startPortfolioMenuTransition(page, 'missing', { switchPortfolio }),
    false
  )
  assert.equal(page.data.portfolioMenuSwitching, false)
})

test('portfolio menu transition clears both pending phase timers', () => {
  const { clearPortfolioMenuTransitionTimers } = loadTransitionModule()
  const controlledTimers = installControlledTimers()

  try {
    const page = createPage()
    page.portfolioMenuExitTimer = 'timer-exit'
    page.portfolioMenuEnterTimer = 'timer-enter'

    clearPortfolioMenuTransitionTimers(page)

    assert.deepEqual(
      controlledTimers.clearedTimerIds,
      ['timer-exit', 'timer-enter']
    )
    assert.equal(page.portfolioMenuExitTimer, null)
    assert.equal(page.portfolioMenuEnterTimer, null)
  } finally {
    controlledTimers.restore()
  }
})

test('portfolio menu transition measures each phase after its view update', () => {
  const {
    PORTFOLIO_MENU_ENTER_DURATION_MS,
    PORTFOLIO_MENU_EXIT_DURATION_MS,
    startPortfolioMenuTransition
  } = loadTransitionModule()
  const controlledTimers = installControlledTimers()

  try {
    const page = createQueuedPage()

    startPortfolioMenuTransition(page, 'works', { switchPortfolio })

    assert.equal(controlledTimers.timers.length, 0)

    page.flushNextSetData()

    assert.equal(controlledTimers.timers.length, 1)
    assert.equal(controlledTimers.timers[0].delay, PORTFOLIO_MENU_EXIT_DURATION_MS)

    controlledTimers.timers[0].handler()

    assert.equal(page.data.portfolio.activeMenuKey, 'works')
    assert.equal(controlledTimers.timers.length, 1)

    page.flushNextSetData()

    assert.equal(page.data.portfolioScrollTop, 0)
    assert.equal(controlledTimers.timers.length, 2)
    assert.equal(controlledTimers.timers[1].delay, PORTFOLIO_MENU_ENTER_DURATION_MS)
  } finally {
    controlledTimers.restore()
  }
})

test('portfolio menu transition invalidates queued render callbacks on unload cleanup', () => {
  const {
    clearPortfolioMenuTransitionTimers,
    startPortfolioMenuTransition
  } = loadTransitionModule()
  const controlledTimers = installControlledTimers()

  try {
    const exitRenderPendingPage = createQueuedPage()
    startPortfolioMenuTransition(exitRenderPendingPage, 'works', { switchPortfolio })

    clearPortfolioMenuTransitionTimers(exitRenderPendingPage)
    exitRenderPendingPage.flushNextSetData()

    assert.equal(controlledTimers.timers.length, 0)

    const enterRenderPendingPage = createQueuedPage()
    startPortfolioMenuTransition(enterRenderPendingPage, 'works', { switchPortfolio })
    enterRenderPendingPage.flushNextSetData()
    controlledTimers.timers[0].handler()

    clearPortfolioMenuTransitionTimers(enterRenderPendingPage)
    enterRenderPendingPage.flushNextSetData()

    assert.equal(enterRenderPendingPage.data.portfolioScrollTop, 1)
    assert.equal(controlledTimers.timers.length, 1)
  } finally {
    controlledTimers.restore()
  }
})

test('preview and visitor pages animate active content while keeping fixed chrome outside', () => {
  const previewWxml = readProjectFile(
    'pages/portfolios/standard-preview/portfolio-standard-preview.wxml'
  )
  const visitorWxml = readProjectFile(
    'pages/portfolios/visitor-portfolio/visitor-portfolio.wxml'
  )
  const previewWxss = readProjectFile(
    'pages/portfolios/standard-preview/portfolio-standard-preview.wxss'
  )
  const visitorWxss = readProjectFile(
    'pages/portfolios/visitor-portfolio/visitor-portfolio.wxss'
  )
  const transitionContainer = /<view class="portfolio-menu-transition-content \{\{portfolioMenuTransitionClass\}\}">/

  assert.match(previewWxml, transitionContainer)
  assert.match(visitorWxml, transitionContainer)
  assert.ok(
    previewWxml.indexOf('class="preview-toolbar"') <
      previewWxml.indexOf('class="portfolio-menu-transition-content'),
    '预览提示栏需要保持在动画容器外'
  )
  ;[previewWxml, visitorWxml].forEach((wxml) => {
    const containerStart = wxml.indexOf('class="portfolio-menu-transition-content')
    assert.ok(
      containerStart < wxml.indexOf('wx:for="{{portfolio.activeComponents}}"'),
      '活动组件需要放在动画容器内'
    )
    assert.ok(
      containerStart < wxml.indexOf('class="folio-brand-footer"'),
      '品牌页脚需要跟随活动内容动画'
    )
    assert.ok(
      wxml.indexOf('class="folio-brand-footer"') <
        wxml.indexOf('</scroll-view>'),
      '品牌页脚需要保留在滚动容器内'
    )
  })
  ;[previewWxss, visitorWxss].forEach((wxss) => {
    assert.match(
      wxss,
      /@import "\.\.\/\.\.\/\.\.\/styles\/portfolio-menu-transition\.wxss";/
    )
  })
})

test('shared portfolio menu styles provide mirrored exit and enter motion', () => {
  const relativePath = 'styles/portfolio-menu-transition.wxss'
  const absolutePath = path.join(__dirname, '..', relativePath)

  assert.equal(
    fs.existsSync(absolutePath),
    true,
    `${relativePath} should exist`
  )
  const wxss = fs.readFileSync(absolutePath, 'utf8')

  assert.match(
    wxss,
    /\.portfolio-menu-transition-content\.portfolio-menu-exit-forward\s*\{[\s\S]*animation:\s*portfolio-menu-exit-forward 120ms ease-in both;/
  )
  assert.match(
    wxss,
    /\.portfolio-menu-transition-content\.portfolio-menu-enter-forward\s*\{[\s\S]*animation:\s*portfolio-menu-enter-forward 220ms cubic-bezier\(0\.22, 1, 0\.36, 1\) both;/
  )
  assert.match(
    wxss,
    /\.portfolio-menu-transition-content\.portfolio-menu-exit-backward\s*\{[\s\S]*animation:\s*portfolio-menu-exit-backward 120ms ease-in both;/
  )
  assert.match(
    wxss,
    /\.portfolio-menu-transition-content\.portfolio-menu-enter-backward\s*\{[\s\S]*animation:\s*portfolio-menu-enter-backward 220ms cubic-bezier\(0\.22, 1, 0\.36, 1\) both;/
  )
  assert.match(
    wxss,
    /@keyframes portfolio-menu-exit-forward[\s\S]*translate3d\(-24rpx, 0, 0\)/
  )
  assert.match(
    wxss,
    /@keyframes portfolio-menu-enter-forward[\s\S]*translate3d\(24rpx, 0, 0\)/
  )
  assert.match(
    wxss,
    /@keyframes portfolio-menu-exit-backward[\s\S]*translate3d\(24rpx, 0, 0\)/
  )
  assert.match(
    wxss,
    /@keyframes portfolio-menu-enter-backward[\s\S]*translate3d\(-24rpx, 0, 0\)/
  )
  assert.match(wxss, /opacity:\s*0\.15;/)
})

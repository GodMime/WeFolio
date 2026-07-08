const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.join(__dirname, '..')
const MOCK_LOGIN_REQUIRED_MESSAGE = '请去“我的”页面注册登录'
const MOCK_AVATAR_URL = 'https://cdn2.we-folio.dingchenyong.top/demo/demo-avatar.png'
const MOCK_PAGE_PATHS = [
  'pages/mock/index/index',
  'pages/mock/schedule/schedule',
  'pages/mock/works/works',
  'pages/mock/portfolios/portfolios',
  'pages/mock/portfolio-standard-edit/portfolio-standard-edit',
  'pages/mock/portfolio-standard-preview/portfolio-standard-preview'
]
const MOCK_PAGE_SOURCE_FILES = MOCK_PAGE_PATHS.flatMap((pagePath) => [
  `${pagePath}.js`,
  `${pagePath}.wxml`
])
const FORBIDDEN_MOCK_SOURCE_PATTERNS = [
  /utils\/request/,
  /utils\/session/,
  /wx\.request/,
  /wx\.uploadFile/,
  /\/api\//,
  /wefolio_token/,
  /setToken/,
  /clearToken/,
  /hasLocalToken/,
  /handleMaintainerAuthRequired/
]

function read(relativePath) {
  assertExists(relativePath)
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

function readJson(relativePath) {
  return JSON.parse(read(relativePath))
}

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matches = Array.from(content.matchAll(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`, 'g')))
  const match = matches[matches.length - 1]
  return match ? match[1] : ''
}

function assertExists(relativePath) {
  assert.equal(
    fs.existsSync(path.join(ROOT, relativePath)),
    true,
    `${relativePath} should exist`
  )
}

function loadMockExperience() {
  assertExists('utils/mock-experience.js')
  const modulePath = path.join(ROOT, 'utils/mock-experience.js')
  delete require.cache[require.resolve(modulePath)]
  return require(modulePath)
}

test('app registers independent mock experience pages', () => {
  const appJson = readJson('app.json')

  MOCK_PAGE_PATHS.forEach((pagePath) => {
    assert.ok(appJson.pages.includes(pagePath), `${pagePath} should be registered`)
    assertExists(`${pagePath}.js`)
    assertExists(`${pagePath}.wxml`)
    assertExists(`${pagePath}.wxss`)
    assertExists(`${pagePath}.json`)
  })
})

test('mock tabbar mirrors the production bottom navigation structure and style', () => {
  const wxml = read('components/mock/tabbar/tabbar.wxml')
  const wxss = read('components/mock/tabbar/tabbar.wxss')
  const activeTabRule = readRule(wxss, '.tab.active')
  const tabbarRule = readRule(wxss, '.tabbar')
  const tabRule = readRule(wxss, '.tab')
  const tabIconRule = readRule(wxss, '.tab-icon')
  const scheduleBeforeRule = readRule(wxss, '.schedule-tab-icon::before')
  const scheduleAfterRule = readRule(wxss, '.schedule-tab-icon::after')
  const workAfterRule = readRule(wxss, '.work-tab-icon::after')
  const portfolioBeforeRule = readRule(wxss, '.portfolio-tab-icon::before')
  const portfolioAfterRule = readRule(wxss, '.portfolio-tab-icon::after')
  const mineBeforeRule = readRule(wxss, '.mine-tab-icon::before')
  const mineAfterRule = readRule(wxss, '.mine-tab-icon::after')

  assert.match(wxml, /<view class="tabbar">/)
  assert.match(wxml, /<button[\s\S]*class="tab \{\{item\.active \? 'active' : ''\}\}"/)
  assert.match(wxml, /class="tab-icon \{\{item\.icon\}\}-tab-icon"/)
  assert.match(wxml, /class="tab-label"/)
  assert.doesNotMatch(wxml, /mock-tab\b/)
  assert.doesNotMatch(wxss, /\.mock-tab/)
  assert.match(tabbarRule, /justify-content:\s*space-around/)
  assert.match(tabRule, /width:\s*25%/)
  assert.match(tabRule, /height:\s*96rpx/)
  assert.match(activeTabRule, /color:\s*#b88a44/)
  assert.match(tabIconRule, /width:\s*42rpx/)
  assert.match(tabIconRule, /height:\s*42rpx/)
  assert.match(scheduleBeforeRule, /border:\s*4rpx\s+solid\s+currentColor/)
  assert.match(scheduleAfterRule, /left:\s*12rpx/)
  assert.match(scheduleAfterRule, /top:\s*18rpx/)
  assert.match(workAfterRule, /transform:\s*rotate\(-45deg\)/)
  assert.match(portfolioBeforeRule, /z-index:\s*2/)
  assert.match(portfolioAfterRule, /z-index:\s*1/)
  assert.match(mineBeforeRule, /border-radius:\s*50%/)
  assert.match(mineAfterRule, /border-bottom-width:\s*3rpx/)
})

test('mock pages never import request/session or backend endpoints', () => {
  MOCK_PAGE_SOURCE_FILES.forEach((relativePath) => {
    assertExists(relativePath)
    const content = read(relativePath)
    FORBIDDEN_MOCK_SOURCE_PATTERNS.forEach((pattern) => {
      assert.doesNotMatch(content, pattern, `${relativePath} contains ${pattern}`)
    })
  })
})

test('mock common data keeps all paths inside mock page tree', () => {
  const mock = loadMockExperience()

  assert.equal(mock.MOCK_LOGIN_REQUIRED_MESSAGE, MOCK_LOGIN_REQUIRED_MESSAGE)
  assert.equal(mock.MOCK_AVATAR_URL, MOCK_AVATAR_URL)
  assert.equal(mock.MOCK_PORTFOLIO_DRAFT_STORAGE_KEY, 'wefolio_mock_portfolio_draft')
  assert.deepEqual(
    mock.MOCK_BOTTOM_TABS.map((item) => item.url),
    [
      '/pages/mock/schedule/schedule',
      '/pages/mock/works/works',
      '/pages/mock/portfolios/portfolios',
      '/pages/mock/index/index'
    ]
  )
  assert.deepEqual(
    mock.getMockTabs('portfolio').map((item) => ({ key: item.key, active: item.active })),
    [
      { key: 'schedule', active: false },
      { key: 'work', active: false },
      { key: 'portfolio', active: true },
      { key: 'mine', active: false }
    ]
  )
})

test('mock mine dashboard uses demo avatar and disables content entries', () => {
  const mock = loadMockExperience()
  const dashboard = mock.MOCK_DASHBOARD

  assert.equal(dashboard.profile.avatarUrl, MOCK_AVATAR_URL)
  assert.equal(dashboard.metrics.workCount, 7)
  assert.equal(dashboard.profile.tags[0].name, '风景作品')
  dashboard.entries.forEach((entry) => {
    assert.equal(entry.clickable, false)
  })
})

test('mock mine return login button uses the regular full-width primary button shape', () => {
  const wxml = read('pages/mock/index/index.wxml')
  const wxss = read('pages/mock/index/index.wxss')
  const buttonRule = readRule(wxss, '.return-login-button')

  assert.match(wxml, /class="primary-button return-login-button"/)
  assert.match(buttonRule, /width:\s*100%/)
  assert.match(buttonRule, /min-height:\s*88rpx/)
  assert.match(buttonRule, /display:\s*flex/)
  assert.match(buttonRule, /align-items:\s*center/)
  assert.match(buttonRule, /justify-content:\s*center/)
  assert.match(buttonRule, /border-radius:\s*16rpx/)
  assert.doesNotMatch(buttonRule, /border-radius:\s*999rpx/)
})

test('mock schedule data contains lunch and dinner slots only', () => {
  const mock = loadMockExperience()

  assert.deepEqual(
    mock.MOCK_SCHEDULE_DATA.slotDefinitions.map((slot) => slot.name),
    ['午宴', '晚宴']
  )
  assert.equal(mock.MOCK_SCHEDULE_DATA.selectedDate.summaryText, '暂无档期')
})

test('mock schedule calendar pads rows and uses stable blank cell keys', () => {
  const mock = loadMockExperience()
  const scheduleWxml = read('pages/mock/schedule/schedule.wxml')
  const month = mock.buildMockCalendarMonth('2026-07', '2026-07-06')
  const blankDays = month.days.filter((day) => !day.date)
  const filledDays = month.days.filter((day) => day.date)

  assert.match(scheduleWxml, /wx:key="key"/)
  assert.equal(month.days.length, 35)
  assert.equal(month.days.length % 7, 0)
  assert.deepEqual(month.days.slice(0, 3).map((day) => day.key), [
    'blank-leading-0',
    'blank-leading-1',
    'blank-leading-2'
  ])
  assert.equal(month.days[3].date, '2026-07-01')
  assert.equal(month.days[8].date, '2026-07-06')
  assert.match(month.days[8].dayClass, /selected/)
  assert.equal(month.days[34].key, 'blank-trailing-34')
  assert.equal(filledDays.length, 31)
  assert.equal(new Set(blankDays.map((day) => day.key)).size, blankDays.length)
})

test('mock work library contains one tag, six images, one video, and correct covers', () => {
  const mock = loadMockExperience()
  const library = mock.MOCK_WORK_LIBRARY

  assert.equal(library.tags.length, 1)
  assert.equal(library.tags[0].name, '风景作品')
  assert.equal(library.works.filter((work) => work.mediaType === 'IMAGE').length, 6)
  assert.equal(library.works.filter((work) => work.mediaType === 'VIDEO').length, 1)
  assert.equal(library.total, 7)
  assert.equal(library.works[0].coverUrl, library.works[0].mediaUrl)
  assert.match(library.works[2].coverUrl, /demo-image-3-thumb\.jpg$/)
  assert.match(library.works[2].mediaUrl, /demo-image-3\.jpg$/)
  assert.match(library.works[6].coverUrl, /demo-video-1-thumb\.jpg$/)
  assert.match(library.works[6].mediaUrl, /demo-video-1\.mp4$/)
})

test('mock portfolio list exposes preview while locking backend actions', () => {
  const mock = loadMockExperience()
  const item = mock.MOCK_PORTFOLIO_LIST.portfolios[0]

  assert.equal(mock.MOCK_PORTFOLIO_LIST.portfolios.length, 1)
  assert.equal(item.ownerType, 'USER')
  assert.equal(item.templateType, 'STANDARD')
  assert.equal(item.actions.cardTap, 'edit')
  assert.equal(item.actions.preview, 'preview')
  assert.equal(item.actions.publish, 'toast-login-required')
  assert.equal(item.actions.delete, 'toast-login-required')
})

test('mock draft portfolio card edits on card tap and renders only preview and publish buttons', () => {
  const wxml = read('pages/mock/portfolios/portfolios.wxml')
  const actionRowMarkup = wxml.match(/<view class="portfolio-action-row">([\s\S]*?)<\/view>/)[1]

  assert.match(wxml, /class="portfolio-item-card"[\s\S]*bindtap="handlePortfolioTap"/)
  assert.match(actionRowMarkup, /showDraftPreview/)
  assert.match(actionRowMarkup, /data-action="preview"[\s\S]*预览/)
  assert.match(actionRowMarkup, /item\.actionType === 'PUBLISH'[\s\S]*data-action="\{\{item\.actionType\}\}"[\s\S]*\{\{item\.actionText\}\}/)
  assert.doesNotMatch(actionRowMarkup, />编辑</)
  assert.doesNotMatch(actionRowMarkup, />分享</)
  assert.doesNotMatch(actionRowMarkup, /data-action="edit"/)
  assert.doesNotMatch(actionRowMarkup, /data-action="share"/)
})

test('mock portfolio edit page mirrors production component orchestration interactions', () => {
  const wxml = read('pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml')
  const js = read('pages/mock/portfolio-standard-edit/portfolio-standard-edit.js')

  assert.match(wxml, /<button class="link-button" bindtap="handleOpenComponentSheet">添加组件<\/button>/)
  assert.match(wxml, /<scroll-view class="edit-scroll" scroll-y type="list" scroll-top="\{\{editScrollTop\}\}">/)
  assert.match(wxml, /class="component-swipe-row[\s\S]*data-index="\{\{index\}\}"[\s\S]*data-key="\{\{item\.componentKey\}\}"[\s\S]*data-type="\{\{item\.componentType\}\}"/)
  assert.match(wxml, /bindtouchstart="handleComponentTouchStart"/)
  assert.match(wxml, /bindlongpress="handleComponentDragStart"/)
  assert.match(wxml, /bindtouchmove="handleComponentTouchMove"/)
  assert.match(wxml, /bindtouchend="handleComponentTouchEnd"/)
  assert.match(wxml, /bindtouchcancel="handleComponentTouchCancel"/)
  assert.match(wxml, /class="component-remove-pane"[\s\S]*handleRemoveComponent/)
  assert.match(wxml, /class="component-row[\s\S]*catchtap="handleComponentTap"/)
  assert.match(wxml, /class="component-picker-mask \{\{componentSheetVisible \? 'visible' : ''\}\}"/)
  assert.match(wxml, /wx:for="\{\{componentOptions\}\}"[\s\S]*data-type="\{\{item\.componentType\}\}"[\s\S]*handleSelectComponent/)
  assert.match(wxml, /class="mock-component-edit-mask component-work-picker-mask \{\{componentEditSheetVisible \? 'visible' : ''\}\}"/)
  assert.match(wxml, /class="mock-component-edit-panel component-work-picker-panel"/)
  assert.match(wxml, /handleCloseComponentEditSheet/)
  assert.match(wxml, /handleConfirmComponentEditSheet/)
  assert.doesNotMatch(wxml, /panel component-panel mock-editor-panel/)
  assert.doesNotMatch(wxml, />重置</)
  assert.match(js, /editScrollTop:\s*0/)
  assert.match(js, /previewReturnPending:\s*false/)
  assert.match(js, /resetEditViewport/)
  assert.match(js, /onShow\(\)[\s\S]*previewReturnPending[\s\S]*loadDraft/)
  assert.match(js, /handlePreview\(\)[\s\S]*previewReturnPending:\s*true/)
  assert.match(js, /componentEditSheetVisible:\s*false/)
  assert.match(js, /componentEditSheetVisible:\s*true/)
  assert.match(js, /addMockComponent/)
  assert.match(js, /removeMockComponent/)
  assert.match(js, /reorderMockComponent/)
  assert.match(js, /MOCK_COMPONENT_OPTIONS/)
})

test('mock portfolio draft helpers add, remove, and reorder components locally', () => {
  const mock = loadMockExperience()
  const original = mock.clone(mock.MOCK_STANDARD_PORTFOLIO)
  const added = mock.addMockComponent(original, mock.COMPONENT_TYPES.CAROUSEL)
  const addedComponent = added.config.components[added.config.components.length - 1]

  assert.equal(original.config.components.length, 5)
  assert.equal(added.config.components.length, 6)
  assert.equal(addedComponent.componentType, mock.COMPONENT_TYPES.CAROUSEL)
  assert.equal(addedComponent.sortOrder, 6000)
  assert.deepEqual(mock.getWorkIdsFromComponent(addedComponent), [101, 102, 103])
  assert.equal(
    new Set(added.config.components.map((component) => component.componentKey)).size,
    added.config.components.length
  )
  assert.equal(added.renderData.components.length, 6)

  const reordered = mock.reorderMockComponent(added, 5, 0)
  assert.equal(reordered.config.components[0].componentKey, addedComponent.componentKey)
  assert.deepEqual(
    reordered.config.components.map((component) => component.sortOrder),
    [1000, 2000, 3000, 4000, 5000, 6000]
  )
  assert.equal(reordered.renderData.components[0].componentKey, addedComponent.componentKey)

  const removed = mock.removeMockComponent(reordered, addedComponent.componentKey)
  assert.equal(removed.config.components.length, 5)
  assert.equal(
    removed.config.components.some((component) => component.componentKey === addedComponent.componentKey),
    false
  )
  assert.equal(
    removed.renderData.components.some((component) => component.componentKey === addedComponent.componentKey),
    false
  )
})

test('mock portfolio add component sheet exposes every production standard component type', () => {
  const mock = loadMockExperience()
  const expectedTypes = [
    'CAROUSEL',
    'PROFILE',
    'SCHEDULE_QUERY',
    'WORK_GRID',
    'WORK_LIST',
    'QR_CONTACT',
    'CONTACT_FORM',
    'TEXT_SECTION',
    'DIVIDER'
  ]

  assert.deepEqual(
    mock.MOCK_COMPONENT_OPTIONS.map((item) => item.componentType),
    expectedTypes
  )
  expectedTypes.forEach((componentType) => {
    assert.equal(mock.COMPONENT_TYPES[componentType], componentType)
    const added = mock.addMockComponent(mock.MOCK_STANDARD_PORTFOLIO, componentType)
    const addedComponent = added.config.components[added.config.components.length - 1]
    const renderComponent = added.renderData.components[added.renderData.components.length - 1]

    assert.equal(addedComponent.componentType, componentType)
    assert.equal(renderComponent.componentType, componentType)
  })
})

test('mock standard portfolio renders complete component json in fixed order', () => {
  const mock = loadMockExperience()
  const portfolio = mock.MOCK_STANDARD_PORTFOLIO
  const expectedOrder = ['CAROUSEL', 'PROFILE', 'WORK_GRID', 'SCHEDULE_QUERY', 'CONTACT_FORM']

  assert.deepEqual(
    portfolio.config.components.map((item) => item.componentType),
    expectedOrder
  )
  assert.deepEqual(
    portfolio.renderData.components.map((item) => item.componentType),
    expectedOrder
  )
  assert.equal(portfolio.config.share.avatarUrl, MOCK_AVATAR_URL)
  assert.equal(portfolio.renderData.share.avatarUrl, MOCK_AVATAR_URL)
  assert.equal(portfolio.renderData.components[1].profile.avatarUrl, MOCK_AVATAR_URL)
  assert.equal(portfolio.renderData.components[2].groups[0].works.length, 7)
})

test('mock page markup exposes required registration prompts and navigation actions', () => {
  const mineWxml = read('pages/mock/index/index.wxml')
  const mineJs = read('pages/mock/index/index.js')
  const scheduleWxml = read('pages/mock/schedule/schedule.wxml')
  const scheduleJs = read('pages/mock/schedule/schedule.js')
  const worksWxml = read('pages/mock/works/works.wxml')
  const portfoliosWxml = read('pages/mock/portfolios/portfolios.wxml')
  const editWxml = read('pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml')
  const editJs = read('pages/mock/portfolio-standard-edit/portfolio-standard-edit.js')
  const previewWxml = read('pages/mock/portfolio-standard-preview/portfolio-standard-preview.wxml')

  assert.match(mineWxml, /返回登录页/)
  assert.match(mineJs, /url:\s*'\/pages\/login\/login'/)
  assert.match(scheduleWxml, /新增档期/)
  assert.match(scheduleJs, /showMockLoginRequiredToast/)
  assert.match(worksWxml, /风景作品/)
  assert.match(portfoliosWxml, /data-action="preview"[\s\S]*预览/)
  assert.match(editWxml, /保存草稿/)
  assert.match(editWxml, /发布/)
  assert.match(editWxml, /预览/)
  assert.match(editJs, /saveMockPortfolioDraft/)
  assert.match(previewWxml, /轮播图/)
  assert.match(previewWxml, /个人资料/)
  assert.match(previewWxml, /双列作品列表/)
  assert.match(previewWxml, /档期查询/)
  assert.match(previewWxml, /预留联系信息/)
})

test('mock pages borrow the corresponding production page visual structure', () => {
  const expectations = [
    {
      page: 'pages/mock/index/index',
      styleImport: '../../index/index.wxss',
      classes: [
        'page-shell mine-page',
        'panel profile-panel',
        'balance-band',
        'metric-grid',
        'panel action-panel'
      ]
    },
    {
      page: 'pages/mock/schedule/schedule',
      styleImport: '../../schedule/schedule.wxss',
      classes: [
        'page-shell schedule-page',
        'schedule-content',
        'schedule-segmented',
        'panel schedule-color-card',
        'calendar-group',
        'day-panel',
        'panel definition-list-panel'
      ]
    },
    {
      page: 'pages/mock/works/works',
      styleImport: '../../works/works.wxss',
      classes: [
        'page-shell works-page',
        'search-panel',
        'filter-panel',
        'library-panel',
        'work-list-scroll',
        'work-swipe-row'
      ]
    },
    {
      page: 'pages/mock/portfolios/portfolios',
      styleImport: '../../portfolios/portfolios.wxss',
      classes: [
        'page-shell portfolios-page',
        'portfolio-content',
        'portfolio-list-panel',
        'portfolio-item-card',
        'portfolio-action-row',
        'create-actions'
      ]
    },
    {
      page: 'pages/mock/portfolio-standard-edit/portfolio-standard-edit',
      styleImport: '../../portfolio-standard-edit/portfolio-standard-edit.wxss',
      classes: [
        'page-shell portfolio-edit-page',
        'edit-content',
        'panel share-panel',
        'panel component-panel',
        'component-row'
      ]
    },
    {
      page: 'pages/mock/portfolio-standard-preview/portfolio-standard-preview',
      styleImport: '../../portfolio-standard-preview/portfolio-standard-preview.wxss',
      classes: [
        'page-shell portfolio-preview-page',
        'preview-scroll',
        'folio-component',
        'profile-section',
        'work-section-title',
        'work-grid'
      ]
    }
  ]

  expectations.forEach((item) => {
    const wxml = read(`${item.page}.wxml`)
    const wxss = read(`${item.page}.wxss`)
    assert.ok(
      wxss.includes(`@import "${item.styleImport}";`),
      `${item.page}.wxss should import ${item.styleImport}`
    )
    item.classes.forEach((className) => {
      assert.ok(
        wxml.includes(className),
        `${item.page}.wxml should include production class "${className}"`
      )
    })
  })
})

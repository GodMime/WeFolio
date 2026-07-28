const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const { getRegisteredPageRoutes } = require('./helpers/app-pages')

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
]).concat([
  'pages/mock/utils/mock-experience.js',
  'components/mock/portfolio-renderer/portfolio-renderer.js',
  'components/mock/portfolio-renderer/portfolio-renderer.wxml'
])
const MOCK_STYLE_FILES = [
  'pages/mock/common.wxss',
  'pages/mock/styles/index.wxss',
  'pages/mock/styles/schedule.wxss',
  'pages/mock/styles/works.wxss',
  'pages/mock/styles/portfolios.wxss',
  'pages/mock/styles/portfolio-standard-edit.wxss',
  'pages/mock/styles/portfolio-standard-preview.wxss'
]
const MOCK_WXML_FILES = MOCK_PAGE_PATHS.map((pagePath) => `${pagePath}.wxml`)
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
  const matches = Array.from(content.matchAll(
    new RegExp(`(?:^|\\n)\\s*${escapedSelector}\\s*\\{([^}]*)\\}`, 'g')
  ))
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
  assertExists('pages/mock/utils/mock-experience.js')
  const modulePath = path.join(ROOT, 'pages/mock/utils/mock-experience.js')
  delete require.cache[require.resolve(modulePath)]
  return require(modulePath)
}

function loadMockPage(relativePath, wxMock) {
  const modulePath = path.join(ROOT, relativePath)
  const previousPage = global.Page
  const previousWx = global.wx
  let definition
  delete require.cache[require.resolve(modulePath)]
  try {
    global.Page = (pageDefinition) => {
      definition = pageDefinition
    }
    global.wx = wxMock
    require(modulePath)
  } finally {
    if (previousPage === undefined) {
      delete global.Page
    } else {
      global.Page = previousPage
    }
    if (previousWx === undefined) {
      delete global.wx
    } else {
      global.wx = previousWx
    }
  }
  return Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, callback) {
      Object.assign(this.data, patch)
      if (callback) {
        callback()
      }
    }
  })
}

test('app registers mock experience pages in the mock subpackage', () => {
  const appJson = readJson('app.json')
  const registeredPageRoutes = getRegisteredPageRoutes(appJson)

  MOCK_PAGE_PATHS.forEach((pagePath) => {
    assert.ok(registeredPageRoutes.includes(pagePath), `${pagePath} should be registered`)
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
  const iconWrapRule = readRule(wxss, '.tab-icon-wrap')
  const activeIconWrapRule = readRule(wxss, '.tab.active .tab-icon-wrap')
  const tabbarRule = readRule(wxss, '.tabbar')
  const tabRule = readRule(wxss, '.tab')
  const tabIconRule = readRule(wxss, '.tab-icon-image')
  const tabLabelRule = readRule(wxss, '.tab-label')

  assert.match(wxml, /<view class="tabbar">/)
  assert.match(wxml, /<button[\s\S]*class="tab \{\{item\.active \? 'active' : ''\}\}"/)
  assert.match(wxml, /class="tab-icon-wrap"[\s\S]*class="tab-icon-image"[\s\S]*\/assets\/system\/tabbar\//)
  assert.match(wxml, /class="tab-label"/)
  assert.doesNotMatch(wxml, /mock-tab\b/)
  assert.doesNotMatch(wxss, /\.mock-tab/)
  assert.match(tabbarRule, /justify-content:\s*space-around/)
  assert.match(tabbarRule, /left:\s*28rpx/)
  assert.match(tabbarRule, /right:\s*28rpx/)
  assert.match(tabbarRule, /bottom:\s*calc\(28rpx \+ env\(safe-area-inset-bottom\)\)/)
  assert.match(tabbarRule, /height:\s*116rpx/)
  assert.match(tabbarRule, /padding:\s*0/)
  assert.match(tabbarRule, /border-radius:\s*999rpx/)
  assert.match(tabbarRule, /background:\s*#ffffff/)
  assert.match(tabbarRule, /border:\s*2rpx solid #e9ecef/)
  assert.match(tabbarRule, /box-shadow:\s*0 24rpx 56rpx rgba\(33,\s*37,\s*41,\s*0\.1\)/)
  assert.match(tabRule, /width:\s*25%/)
  assert.match(tabRule, /height:\s*116rpx/)
  assert.match(tabRule, /color:\s*#868e96/)
  assert.match(activeTabRule, /color:\s*#212529/)
  assert.match(iconWrapRule, /width:\s*68rpx/)
  assert.match(iconWrapRule, /height:\s*52rpx/)
  assert.match(iconWrapRule, /border-radius:\s*999rpx/)
  assert.match(activeIconWrapRule, /background:\s*#212529/)
  assert.match(tabLabelRule, /margin-top:\s*4rpx/)
  assert.match(tabLabelRule, /font-size:\s*18rpx/)
  assert.match(tabLabelRule, /font-weight:\s*500/)
  assert.doesNotMatch(wxss, /\.tab\.active::before/)
  assert.doesNotMatch(wxss, /#b88a44/)
  assert.match(tabIconRule, /width:\s*38rpx/)
  assert.match(tabIconRule, /height:\s*38rpx/)
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

test('mock pages mirror the neutral formal theme without Skyline grid styles', () => {
  for (const relativePath of MOCK_STYLE_FILES) {
    const wxss = read(relativePath)
    assert.doesNotMatch(wxss, /#f5f7fb|#eef6f4|#b88a44/)
    assert.doesNotMatch(wxss, /display:\s*grid|grid-template-/)
  }

  for (const relativePath of MOCK_WXML_FILES) {
    const wxml = read(relativePath)
    if (!wxml.includes('<navigation-bar')) continue
    assert.match(wxml, /color="#212529"/)
  }
})

test('mock schedule slot toggle action matches the neutral design button', () => {
  const wxml = read('pages/mock/schedule/schedule.wxml')
  const wxss = read('pages/mock/styles/schedule.wxss')
  const slotToggleActionRule = readRule(wxss, '.slot-toggle-action')

  assert.match(wxml, /class="mini-action warn slot-toggle-action"[^>]*>停用<\/button>/)
  assert.match(wxml, /class="mini-action"[^>]*>编辑<\/button>/)
  assert.doesNotMatch(wxml, /class="mini-action slot-toggle-action"[^>]*>编辑<\/button>/)
  assert.match(slotToggleActionRule, /width:\s*112rpx/)
  assert.match(slotToggleActionRule, /min-width:\s*112rpx/)
  assert.match(slotToggleActionRule, /max-width:\s*112rpx/)
  assert.match(slotToggleActionRule, /height:\s*56rpx/)
  assert.match(slotToggleActionRule, /color:\s*#212529/)
  assert.match(slotToggleActionRule, /font-size:\s*22rpx/)
  assert.match(slotToggleActionRule, /font-weight:\s*600/)
  assert.match(slotToggleActionRule, /border:\s*0/)
  assert.match(slotToggleActionRule, /border-radius:\s*999rpx/)
  assert.match(slotToggleActionRule, /background:\s*#f5f6f7/)
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

  assert.match(
    wxml,
    /<view\b[^>]*class="primary-button return-login-button"[^>]*aria-role="button"[^>]*bindtap="handleReturnLoginTap"[^>]*>/
  )
  assert.doesNotMatch(wxml, /<button\b[^>]*class="primary-button return-login-button"/)
  assert.match(buttonRule, /width:\s*100%/)
  assert.match(buttonRule, /min-height:\s*88rpx/)
  assert.match(buttonRule, /display:\s*flex/)
  assert.match(buttonRule, /align-items:\s*center/)
  assert.match(buttonRule, /justify-content:\s*center/)
  assert.match(buttonRule, /border-radius:\s*999rpx/)
})

test('mock mine return button matches the action panel outer width', () => {
  const wxss = read('pages/mock/index/index.wxss')
  const actionPanelRule = readRule(wxss, '.mock-mine-page .action-panel')
  const returnButtonRule = readRule(wxss, '.return-login-button')

  assert.match(actionPanelRule, /width:\s*100%/)
  assert.match(actionPanelRule, /box-sizing:\s*border-box/)
  assert.match(returnButtonRule, /width:\s*100%/)
  assert.match(returnButtonRule, /box-sizing:\s*border-box/)
})

test('mock mine recharge button defines production-aligned visual dimensions independently', () => {
  const wxml = read('pages/mock/index/index.wxml')
  const wxss = read('pages/mock/index/index.wxss')
  const buttonRule = readRule(wxss, '.mock-mine-page .light-button')

  assert.match(wxml, /<button\b[^>]*class="light-button"[^>]*>充值<\/button>/)
  assert.match(buttonRule, /min-width:\s*112rpx/)
  assert.match(buttonRule, /height:\s*88rpx/)
  assert.match(buttonRule, /padding:\s*0\s+28rpx/)
  assert.match(buttonRule, /border-radius:\s*999rpx/)
  assert.match(buttonRule, /box-sizing:\s*border-box/)
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

test('mock schedule status independently uses original color dot and text without pill background', () => {
  const scheduleWxss = read('pages/mock/styles/schedule.wxss')
  const statusRule = readRule(scheduleWxss, '.schedule-status')
  const statusDotRule = readRule(scheduleWxss, '.schedule-status::before')
  const tealRule = readRule(scheduleWxss, '.schedule-status.teal')
  const amberRule = readRule(scheduleWxss, '.schedule-status.amber')
  const roseRule = readRule(scheduleWxss, '.schedule-status.rose')
  const mutedRule = readRule(scheduleWxss, '.schedule-status.muted')

  assert.match(statusRule, /display:\s*inline-flex/)
  assert.match(statusRule, /align-items:\s*center/)
  assert.match(statusRule, /gap:\s*8rpx/)
  assert.match(statusRule, /padding:\s*0/)
  assert.match(statusRule, /background:\s*transparent/)
  assert.match(statusDotRule, /width:\s*12rpx/)
  assert.match(statusDotRule, /height:\s*12rpx/)
  assert.match(statusDotRule, /background:\s*currentColor/)
  assert.match(statusDotRule, /border-radius:\s*50%/)
  assert.match(tealRule, /color:\s*#0f766e/)
  assert.match(amberRule, /color:\s*#8a4b09/)
  assert.match(roseRule, /color:\s*#a9354f/)
  assert.match(mutedRule, /color:\s*#66727f/)
  assert.doesNotMatch(tealRule, /background:/)
  assert.doesNotMatch(amberRule, /background:/)
  assert.doesNotMatch(roseRule, /background:/)
  assert.doesNotMatch(mutedRule, /background:/)
})

test('mock work library contains one tag, six images, one video, and correct covers', () => {
  const mock = loadMockExperience()
  const library = mock.MOCK_WORK_LIBRARY

  assert.equal(library.tags.length, 1)
  assert.equal(library.tags[0].name, '风景作品')
  assert.equal(library.works.filter((work) => work.mediaType === 'IMAGE').length, 6)
  assert.equal(library.works.filter((work) => work.mediaType === 'VIDEO').length, 1)
  assert.equal(library.total, 7)
  assert.ok(library.works.every((work) => work.auditStatus === 'PASSED'))
  assert.ok(library.works.every((work) => work.auditStatusText === '审核通过'))
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

test('mock portfolio list inherits wider title layout with visible fixed-width action buttons', () => {
  const wxml = read('pages/mock/portfolios/portfolios.wxml')
  const mockWxss = read('pages/mock/portfolios/portfolios.wxss')
  const listWxss = read('pages/portfolios/portfolios.wxss')
  const actionRowRule = readRule(listWxss, '.portfolio-action-row')
  const actionButtonRule = readRule(listWxss, '.portfolio-action-button')

  assert.match(mockWxss, /@import "\.\.\/styles\/portfolios\.wxss";/)
  assert.match(wxml, /class="page-shell portfolios-page mock-portfolios-page"/)
  assert.match(wxml, /class="portfolio-cover"/)
  assert.match(wxml, /class="portfolio-action-row"/)
  assert.match(listWxss, /\.portfolio-item-card\s*\{[\s\S]*gap:\s*20rpx;/)
  assert.match(listWxss, /\.portfolio-cover\s*\{[\s\S]*width:\s*152rpx;[\s\S]*height:\s*133rpx;/)
  assert.match(actionRowRule, /flex:\s*0 0 auto/)
  assert.match(actionButtonRule, /(?:^|\n)\s*width:\s*108rpx/)
  assert.match(actionButtonRule, /(?:^|\n)\s*min-width:\s*108rpx/)
  assert.match(actionButtonRule, /(?:^|\n)\s*max-width:\s*108rpx/)
  assert.match(actionButtonRule, /height:\s*60rpx/)
  assert.match(actionButtonRule, /padding:\s*0 26rpx/)
  assert.doesNotMatch(mockWxss, /\.portfolio-(item-card|cover|action-button)\s*\{/)
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
    'SINGLE_WORK',
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

test('mock singular work uses local single selection, strict config, and render envelope', () => {
  const mock = loadMockExperience()
  const added = mock.addMockComponent(mock.MOCK_STANDARD_PORTFOLIO, mock.COMPONENT_TYPES.SINGLE_WORK)
  const component = added.config.components[added.config.components.length - 1]

  assert.deepEqual(component.config, { workId: 0, showTitle: true })

  const configured = mock.updateMockSingleWorkConfig(added, component.componentKey, {
    workId: 107,
    showTitle: false,
    workIds: [101],
    unsupported: true
  })
  const configuredComponent = configured.config.components[configured.config.components.length - 1]
  const renderComponent = configured.renderData.components[configured.renderData.components.length - 1]

  assert.deepEqual(configuredComponent.config, { workId: 107, showTitle: false })
  assert.equal(renderComponent.componentType, 'SINGLE_WORK')
  assert.equal(renderComponent.showTitle, false)
  assert.equal(renderComponent.work.workId, 107)
  assert.equal(renderComponent.work.isVideo, true)
  assert.equal(renderComponent.work.aspectRatioStyle, 'height: 399rpx; aspect-ratio: 16 / 9;')
})

test('mock singular work persists through local draft and preview without network calls', () => {
  const mock = loadMockExperience()
  let storedDraft = null
  let networkCalls = 0
  const previews = []
  const navigations = []
  const wxMock = {
    getStorageSync() {
      return storedDraft
    },
    setStorageSync(key, value) {
      assert.equal(key, mock.MOCK_PORTFOLIO_DRAFT_STORAGE_KEY)
      storedDraft = value
    },
    navigateTo(options) {
      navigations.push(options)
    },
    previewImage(options) {
      previews.push(options)
    },
    showToast() {},
    createVideoContext() {
      return { pause() {} }
    },
    request() {
      networkCalls += 1
    }
  }
  global.wx = wxMock

  try {
    const editor = loadMockPage('pages/mock/portfolio-standard-edit/portfolio-standard-edit.js', wxMock)
    editor.handleSelectComponent({ currentTarget: { dataset: { type: 'SINGLE_WORK' } } })
    const componentKey = editor.data.selectedComponentKey
    editor.handleWorkToggle({ currentTarget: { dataset: { id: 107 } } })
    editor.handleWorkToggle({ currentTarget: { dataset: { id: 107 } } })
    editor.handleSingleWorkShowTitleChange({ detail: { value: false } })
    editor.handleConfirmComponentEditSheet()
    editor.handlePreview()

    assert.equal(editor.data.selectedWorkIds.length, 1)
    assert.equal(navigations.length, 1)
    assert.equal(storedDraft.config.components.find((item) => item.componentKey === componentKey).config.workId, 107)
    assert.equal(storedDraft.config.components.find((item) => item.componentKey === componentKey).config.showTitle, false)

    const preview = loadMockPage('pages/mock/portfolio-standard-preview/portfolio-standard-preview.js', wxMock)
    preview.onShow()
    preview.handleSingleWorkTap({
      currentTarget: {
        dataset: {
          componentKey,
          mediaType: 'VIDEO',
          mediaUrl: mock.MOCK_WORK_LIBRARY.works[6].mediaUrl
        }
      }
    })
    assert.equal(preview.data.activeSingleWorkVideoKey, componentKey)

    preview.handleSingleWorkTap({
      currentTarget: {
        dataset: {
          componentKey: `${componentKey}-image`,
          mediaType: 'IMAGE',
          mediaUrl: mock.MOCK_WORK_LIBRARY.works[0].mediaUrl
        }
      }
    })
  } finally {
    delete global.wx
  }

  assert.equal(previews.length, 1)
  assert.equal(networkCalls, 0)
})

test('mock singular work editor and preview expose title switch, width-fix image, and inline video', () => {
  const editWxml = read('pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml')
  const previewWxml = read('pages/mock/portfolio-standard-preview/portfolio-standard-preview.wxml')
  const rendererWxml = read('components/mock/portfolio-renderer/portfolio-renderer.wxml')

  assert.match(editWxml, /selectedComponentType === 'SINGLE_WORK'/)
  assert.match(editWxml, /bindchange="handleSingleWorkShowTitleChange"/)
  assert.match(previewWxml, /<mock-portfolio-renderer[\s\S]*active-single-work-video-key="\{\{activeSingleWorkVideoKey\}\}"/)
  assert.match(rendererWxml, /component\.componentType === 'SINGLE_WORK'/)
  assert.match(rendererWxml, /class="single-work-image"[\s\S]*mode="widthFix"/)
  assert.match(rendererWxml, /id="singleWorkVideo-\{\{component\.componentKey\}\}"/)
  assert.match(rendererWxml, /activeSingleWorkVideoKey === component\.componentKey/)
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

test('mock personal portfolio preview uses its own isolated renderer without editor labels', () => {
  const previewJson = readJson('pages/mock/portfolio-standard-preview/portfolio-standard-preview.json')
  const previewWxml = read('pages/mock/portfolio-standard-preview/portfolio-standard-preview.wxml')
  const rendererWxml = read('components/mock/portfolio-renderer/portfolio-renderer.wxml')
  const rendererWxss = read('components/mock/portfolio-renderer/portfolio-renderer.wxss')

  assert.equal(previewJson.usingComponents['mock-portfolio-renderer'], '/components/mock/portfolio-renderer/portfolio-renderer')
  assert.equal(Object.values(previewJson.usingComponents).some((componentPath) => componentPath.startsWith('/pages/portfolios/')), false)
  assert.match(previewWxml, /<mock-portfolio-renderer[\s\S]*component="\{\{item\}\}"[\s\S]*bindworktap="handleWorkTap"/)
  assert.match(rendererWxml, /class="profile-avatar"[\s\S]*src="\{\{component\.profile\.avatarUrl\}\}"/)
  assert.match(rendererWxml, /class="work-grid"[\s\S]*wx:for="\{\{component\.activeGroup\.works\}\}"/)
  assert.match(rendererWxml, /class="[^"]*contact-entry-button[^"]*"[\s\S]*component\.contactForm\.title/)
  assert.doesNotMatch(rendererWxml, />个人资料</)
  assert.doesNotMatch(rendererWxml, />双列作品列表</)
  assert.match(rendererWxss, /\.profile-avatar\s*\{[\s\S]*width:\s*132rpx;[\s\S]*height:\s*132rpx;[\s\S]*border-radius:\s*50%;/)
  assert.match(rendererWxss, /\.grid-card\s*\{[\s\S]*width:\s*50%;/)
  assert.match(rendererWxss, /\.contact-entry-button\s*\{[\s\S]*border-radius:\s*999rpx;/)
})

test('mock preview handles display component events locally without network calls', () => {
  let networkCalls = 0
  const previews = []
  const toasts = []
  const wxMock = {
    getStorageSync() {
      return null
    },
    previewImage(options) {
      previews.push(options)
    },
    showToast(options) {
      toasts.push(options)
    },
    createVideoContext() {
      return { pause() {} }
    },
    request() {
      networkCalls += 1
    }
  }
  global.wx = wxMock
  try {
    const page = loadMockPage('pages/mock/portfolio-standard-preview/portfolio-standard-preview.js', wxMock)
    const grid = page.data.portfolio.components.find((item) => item.componentType === 'WORK_GRID')
    const work = grid.activeGroup.works[0]

    page.handleDisplayTagTap({
      detail: {
        componentKey: grid.componentKey,
        groupKey: grid.displayTags[0].groupKey
      }
    })
    page.handleWorkTap({
      detail: {
        mediaType: 'IMAGE',
        mediaUrl: work.previewUrl,
        coverUrl: work.thumbnailUrl,
        title: work.title
      }
    })
    page.handleSubmitContact()
  } finally {
    delete global.wx
  }

  assert.equal(previews.length, 1)
  assert.deepEqual(toasts.at(-1), { title: MOCK_LOGIN_REQUIRED_MESSAGE, icon: 'none' })
  assert.equal(networkCalls, 0)
})

test('mock page markup exposes required registration prompts and navigation actions', () => {
  const mineWxml = read('pages/mock/index/index.wxml')
  const mineJs = read('pages/mock/index/index.js')
  const scheduleWxml = read('pages/mock/schedule/schedule.wxml')
  const scheduleJs = read('pages/mock/schedule/schedule.js')
  const worksWxml = read('pages/mock/works/works.wxml')
  const worksWxss = read('pages/mock/styles/works.wxss')
  const portfoliosWxml = read('pages/mock/portfolios/portfolios.wxml')
  const editWxml = read('pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml')
  const editJs = read('pages/mock/portfolio-standard-edit/portfolio-standard-edit.js')
  const previewWxml = read('pages/mock/portfolio-standard-preview/portfolio-standard-preview.wxml')
  const rendererWxml = read('components/mock/portfolio-renderer/portfolio-renderer.wxml')

  assert.match(mineWxml, /返回登录页/)
  assert.match(mineJs, /url:\s*'\/pages\/login\/login'/)
  assert.match(scheduleWxml, /新增档期/)
  assert.match(scheduleJs, /showMockLoginRequiredToast/)
  assert.match(worksWxml, /风景作品/)
  assert.match(worksWxml, /class="reference-pill \{\{item\.referenceCount > 0 \? 'used' : ''\}\}"[\s\S]*引用[\s\S]*item\.referenceCount[\s\S]*未引用/)
  assert.match(worksWxml, /class="status-badge \{\{item\.auditStatusTone\}\}"[\s\S]*class="status-dot"[\s\S]*\{\{item\.auditStatusText\}\}/)
  assert.doesNotMatch(worksWxml, /已使用 1 次/)
  assert.match(worksWxss, /\.reference-pill\s*\{[\s\S]*height:\s*42rpx;[\s\S]*background:\s*#ffffff;/)
  assert.match(worksWxss, /\.status-badge\s*\{[\s\S]*gap:\s*8rpx;/)
  assert.match(worksWxss, /\.status-dot\s*\{[\s\S]*width:\s*12rpx;[\s\S]*height:\s*12rpx;/)
  assert.match(portfoliosWxml, /data-action="preview"[\s\S]*预览/)
  assert.match(editWxml, /保存草稿/)
  assert.match(editWxml, /发布/)
  assert.match(editWxml, /预览/)
  assert.match(editJs, /saveMockPortfolioDraft/)
  assert.match(previewWxml, /<portfolio-carousel/)
  assert.match(previewWxml, /<mock-portfolio-renderer/)
  assert.match(rendererWxml, /component\.componentType === 'PROFILE'/)
  assert.match(rendererWxml, /component\.componentType === 'WORK_GRID'/)
  assert.match(rendererWxml, /档期查询/)
  assert.match(rendererWxml, /component\.componentType === 'CONTACT_FORM'/)
})

test('mock preview video overlay renders in root portal like production preview', () => {
  const previewWxml = read('pages/mock/portfolio-standard-preview/portfolio-standard-preview.wxml')
  const portalStart = previewWxml.indexOf('<root-portal wx:if="{{videoPreviewVisible}}">')
  const maskStart = previewWxml.indexOf('class="work-video-mask {{videoPreviewVisible ? \'visible\' : \'\'}}"')
  const portalEnd = previewWxml.indexOf('</root-portal>', portalStart)

  assert.notEqual(portalStart, -1)
  assert.notEqual(maskStart, -1)
  assert.ok(maskStart > portalStart)
  assert.ok(maskStart < portalEnd)
})

test('mock portfolio preview keeps personal tags in the shared chromatic pill language', () => {
  const previewWxml = read('pages/mock/portfolio-standard-preview/portfolio-standard-preview.wxml')
  const rendererWxml = read('components/mock/portfolio-renderer/portfolio-renderer.wxml')
  const rendererWxss = read('components/mock/portfolio-renderer/portfolio-renderer.wxss')

  assert.match(previewWxml, /<mock-portfolio-renderer[\s\S]*component="\{\{item\}\}"/)
  assert.match(
    rendererWxml,
    /class="profile-tag"[\s\S]*color: \{\{tag\.color \|\| '#0f766e'\}\};[\s\S]*border-color: \{\{tag\.color \|\| '#0f766e'\}\};/
  )
  assert.match(rendererWxml, /class="profile-tag-dot"[\s\S]*background: \{\{tag\.color \|\| '#0f766e'\}\};/)
  assert.match(rendererWxss, /\.profile-tag\s*\{[\s\S]*background:\s*#ffffff;/)
  assert.match(rendererWxss, /\.profile-tag-dot\s*\{[\s\S]*width:\s*12rpx;[\s\S]*height:\s*12rpx;/)
})

test('mock pages borrow the corresponding production page visual structure', () => {
  const expectations = [
    {
      page: 'pages/mock/index/index',
      styleImport: '../styles/index.wxss',
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
      styleImport: '../styles/schedule.wxss',
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
      styleImport: '../styles/works.wxss',
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
      styleImport: '../styles/portfolios.wxss',
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
      styleImport: '../styles/portfolio-standard-edit.wxss',
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
      styleImport: '../styles/portfolio-standard-preview.wxss',
      classes: [
        'page-shell portfolio-preview-page',
        'preview-scroll',
        'folio-component',
        'mock-portfolio-renderer'
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

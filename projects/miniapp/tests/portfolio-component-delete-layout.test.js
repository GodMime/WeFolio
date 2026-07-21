const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MINIAPP_ROOT = path.resolve(__dirname, '..')
const REMOVE_BUTTON_STYLE_PATHS = [
  'pages/portfolios/standard-edit/portfolio-standard-edit.wxss',
  'pages/mock/styles/portfolio-standard-edit.wxss',
  'pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxss'
]

function read(relativePath) {
  return fs.readFileSync(path.join(MINIAPP_ROOT, relativePath), 'utf8')
}

function readCssRule(content, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = content.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
}

function loadTeamEditor(requestFn) {
  const pagePath = path.join(MINIAPP_ROOT, 'pages/team-portfolios/standard-edit/team-portfolio-standard-edit.js')
  const requestPath = path.join(MINIAPP_ROOT, 'utils/request.js')
  const requestCacheKey = require.resolve(requestPath)
  const previousRequest = require.cache[requestCacheKey]
  const previousPage = global.Page
  const previousWx = global.wx
  let definition

  require.cache[requestCacheKey] = {
    id: requestPath,
    filename: requestPath,
    loaded: true,
    exports: { request: requestFn }
  }
  global.Page = (value) => { definition = value }
  global.wx = {
    navigateTo() {},
    redirectTo() {},
    showToast() {},
    stopPullDownRefresh() {},
    getCurrentPages() { return [] }
  }
  delete require.cache[require.resolve(pagePath)]
  try {
    require(pagePath)
  } finally {
    global.Page = previousPage
    if (previousRequest) require.cache[requestCacheKey] = previousRequest
    else delete require.cache[requestCacheKey]
  }

  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch) }
  })
  page.cleanup = () => { global.wx = previousWx }
  return page
}

test('portfolio component delete buttons fill the component row height', () => {
  for (const relativePath of REMOVE_BUTTON_STYLE_PATHS) {
    const rule = readCssRule(read(relativePath), '.component-remove-button')
    assert.match(rule, /(?:^|\n)\s*height:\s*100%;/, relativePath)
    assert.match(rule, /min-height:\s*100%;/, relativePath)
    assert.doesNotMatch(rule, /(?:^|\n)\s*height:\s*64rpx;/, relativePath)
  }
})

test('team portfolio component reveals on left swipe and is removed by delete action', () => {
  const page = loadTeamEditor(async () => ({}))
  page.updateConfig({
    schemaVersion: 'standard-team-v1',
    share: {},
    components: [
      { componentKey: 'text-1', componentType: 'TEXT_SECTION', sortOrder: 0, enabled: true, config: { content: '说明' } },
      { componentKey: 'divider-1', componentType: 'DIVIDER', sortOrder: 1, enabled: true, config: {} }
    ]
  })

  page.handleComponentTouchStart({
    currentTarget: { dataset: { key: 'text-1', index: 0 } },
    touches: [{ clientX: 180, clientY: 80 }]
  })
  page.handleComponentTouchEnd({ changedTouches: [{ clientX: 120, clientY: 82 }] })

  assert.equal(page.data.revealedComponentKey, 'text-1')

  page.handleRemoveComponent({ currentTarget: { dataset: { key: 'text-1' } } })

  assert.deepEqual(page.data.config.components.map((item) => item.componentKey), ['divider-1'])
  assert.deepEqual(page.data.componentList.map((item) => item.componentKey), ['divider-1'])
  assert.equal(page.data.revealedComponentKey, '')
  page.cleanup()
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../pages/portfolios/components/single-work-picker')

function loadComponent() {
  const modulePath = path.join(ROOT, 'single-work-picker.js')
  const previous = global.Component
  let definition
  global.Component = (value) => { definition = value }
  delete require.cache[require.resolve(modulePath)]
  try {
    require(modulePath)
    return definition
  } finally {
    if (previous === undefined) delete global.Component
    else global.Component = previous
  }
}

function createHarness(definition, initialProperties = {}) {
  const events = []
  const properties = {}
  for (const [key, descriptor] of Object.entries(definition.properties || {})) {
    properties[key] = Object.prototype.hasOwnProperty.call(initialProperties, key)
      ? initialProperties[key]
      : descriptor.value
  }
  const instance = {
    properties,
    data: {},
    triggerEvent(name, detail = {}) {
      events.push({ name, detail })
    }
  }
  for (const [name, method] of Object.entries(definition.methods || {})) {
    instance[name] = method.bind(instance)
  }
  return { instance, events }
}

test('personal single work picker emits controlled search selection paging and switch events', () => {
  const definition = loadComponent()
  const { instance, events } = createHarness(definition, { hasMore: true })
  const work = { id: 11, title: '婚礼作品' }

  instance.handleKeywordInput({ detail: { value: '婚礼' } })
  instance.handleSearchConfirm()
  instance.handleClearSearch()
  instance.handleTagTap({ currentTarget: { dataset: { tagId: 7 } } })
  instance.handleWorkTap({ currentTarget: { dataset: { item: work } } })
  instance.handleScrollToLower()
  instance.handleRetryLoadMore()
  instance.handleShowTitleChange({ detail: { value: false } })
  instance.handleShowDescriptionChange({ detail: { value: true } })

  assert.deepEqual(events, [
    { name: 'keywordchange', detail: { value: '婚礼' } },
    { name: 'search', detail: {} },
    { name: 'clearsearch', detail: {} },
    { name: 'tagchange', detail: { tagId: 7 } },
    { name: 'workselect', detail: { work } },
    { name: 'loadmore', detail: {} },
    { name: 'retryloadmore', detail: {} },
    { name: 'showtitlechange', detail: { value: false } },
    { name: 'showdescriptionchange', detail: { value: true } }
  ])
})

test('personal single work picker suppresses duplicate end reached events', () => {
  const definition = loadComponent()
  const { instance, events } = createHarness(definition, {
    hasMore: true,
    loadingMore: true
  })

  instance.handleScrollToLower()
  instance.properties.loadingMore = false
  instance.properties.loading = true
  instance.handleScrollToLower()
  instance.properties.loading = false
  instance.properties.hasMore = false
  instance.handleScrollToLower()

  assert.deepEqual(events, [])
})

test('personal single work picker owns the ordered horizontal radio layout', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'single-work-picker.wxml'), 'utf8')
  const wxss = fs.readFileSync(path.join(ROOT, 'single-work-picker.wxss'), 'utf8')
  const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'single-work-picker.json'), 'utf8'))
  const currentIndex = wxml.indexOf('single-work-picker-current')
  const searchIndex = wxml.indexOf('single-work-picker-search')
  const filterIndex = wxml.indexOf('single-work-picker-filter-scroll')
  const candidatesIndex = wxml.indexOf('single-work-picker-candidate-scroll')
  const switchesIndex = wxml.indexOf('single-work-picker-switches')

  assert.equal(json.component, true)
  assert.ok(currentIndex >= 0)
  assert.ok(currentIndex < searchIndex)
  assert.ok(searchIndex < filterIndex)
  assert.ok(filterIndex < candidatesIndex)
  assert.ok(candidatesIndex < switchesIndex)
  assert.match(wxml, /class="single-work-picker-candidate-scroll"[^>]*scroll-x[^>]*bindscrolltolower="handleScrollToLower"/)
  assert.match(wxml, /class="single-work-picker-radio-dot"/)
  assert.match(wxml, /class="single-work-picker-tail"/)
  assert.match(wxml, /status === 'unavailable'/)
  assert.match(wxml, /status === 'unresolved'/)
  assert.match(wxml, /作品信息加载中/)
  assert.match(wxml, /aria-role="radio"/)
  assert.doesNotMatch(wxml, /✓/)
  assert.match(wxss, /\.single-work-picker-candidate-row\s*\{[^}]*display:\s*flex;[^}]*padding-right:\s*48rpx;/s)
  assert.match(wxss, /\.single-work-picker-radio\s*\{[^}]*position:\s*absolute;[^}]*top:\s*12rpx;[^}]*right:\s*12rpx;/s)
})

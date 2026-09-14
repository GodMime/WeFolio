const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function loadInstance(file, component = true) {
  const registration = component ? 'Component' : 'Page'
  const previous = global[registration]
  let definition
  global[registration] = value => { definition = value }
  const target = require.resolve(`../pages/${file}`)
  delete require.cache[target]
  try { require(target) } finally {
    if (previous) global[registration] = previous
    else delete global[registration]
  }
  return Object.assign({}, component ? definition.methods : definition, {
    data: structuredClone(definition.data),
    properties: Object.fromEntries(Object.entries(definition.properties || {})
      .map(([key, property]) => [key, structuredClone(property.value)])),
    events: [],
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name, detail) { this.events.push({ name, detail }) }
  })
}

const readPage = file => fs.readFileSync(path.join(__dirname, '../pages', file), 'utf8')

for (const directory of ['portfolios', 'team-portfolios']) {
  const pickerPath = `${directory}/components/text-background-editor/text-background-editor`

  for (const structured of [false, true]) {
    test(`${directory} ${structured ? '结构化' : '普通'}文字滚动触底只为展开的背景列表请求下一页`, () => {
      const pageName = directory === 'portfolios' ? 'portfolio-standard-edit' : 'team-portfolio-standard-edit'
      const hostPath = structured
        ? `${directory}/components/structured-text-editor/structured-text-editor`
        : `${directory}/standard-edit/${pageName}`
      const host = loadInstance(hostPath, structured)
      const picker = loadInstance(pickerPath)
      const template = readPage(`${hostPath}.wxml`)
      const scrollClass = structured ? 'structured-scroll' : 'text-section-form'
      const scroll = template.match(new RegExp(`<scroll-view\\b[^>]*class="${scrollClass}[^>]*>`))[0]
      const handler = scroll.match(/bindscrolltolower="([^"]+)"/)[1]
      assert.match(scroll, /lower-threshold="80"/)
      assert.match(template, /<[^\s>]*text-background-editor id="text-background-picker"/)
      host.selectComponent = selector => {
        assert.equal(selector, '#text-background-picker')
        return picker
      }
      host.properties.visible = true
      host.data.tab = 'background'
      host.data.textSectionSheetVisible = true
      picker.properties.config = { backgroundEnabled: true }
      picker.properties.hasMore = true
      picker.data.pickerOpen = true

      host[handler]()
      assert.deepEqual(picker.events, [{ name: 'request', detail: { reset: false } }])
      picker.events = []
      for (const property of [{ loading: true }, { error: '网络异常' }, { hasMore: false },
        { config: { backgroundEnabled: false } }]) {
        const previous = { ...picker.properties }
        Object.assign(picker.properties, property)
        host[handler]()
        picker.properties = previous
      }
      picker.handleClosePicker()
      host[handler]()
      picker.data.pickerOpen = true
      if (structured) {
        host.data.tab = 'content'
        host[handler]()
        host.data.tab = 'background'
        host.data.blockDraft = { block: {} }
        host[handler]()
        host.data.blockDraft = null
        host.data.tabSwitching = true
        host[handler]()
        host.data.tabSwitching = false
        host.properties.visible = false
      } else host.data.textSectionSheetVisible = false
      host[handler]()
      assert.deepEqual(picker.events, [])
    })
  }

  test(`${directory} 续页失败重试保留搜索与候选，首屏失败可以重新搜索`, () => {
    const picker = loadInstance(pickerPath)
    picker.properties.config = { backgroundEnabled: true }
    picker.properties.error = '网络异常'
    picker.properties.hasMore = true
    picker.properties.options = [{ id: 7 }]
    picker.data.pickerOpen = true
    picker.data.keyword = '尚未提交的搜索词'
    picker.handleMore()
    assert.equal(picker.events.length, 0)
    picker.handleRetry()
    assert.deepEqual(picker.events, [{ name: 'request', detail: { reset: false } }])
    assert.deepEqual(picker.properties.options, [{ id: 7 }])
    picker.properties.loading = true
    picker.handleRetry()
    assert.equal(picker.events.length, 1)
    picker.properties.loading = false
    picker.properties.hasMore = false
    picker.properties.options = []
    picker.handleRetry()
    assert.deepEqual(picker.events.at(-1), { name: 'request', detail: { reset: true, keyword: '尚未提交的搜索词' } })
  })

  test(`${directory} 背景列表使用轻量加载状态并保留短列表操作及失败重试入口`, () => {
    const template = readPage(`${pickerPath}.wxml`)
    const styles = readPage(`${pickerPath}.wxss`)
    assert.doesNotMatch(template, /<button[^>]*bindtap="handleMore"/)
    assert.match(template, /class="background-loading-spinner" aria-hidden="true"/)
    assert.match(template, /bindtap="handleRetry" aria-role="button"/)
    assert.match(template, /bindtap="handleMore" aria-role="button"/)
    assert.match(template, /已显示全部作品/)
    assert.match(styles, /\.background-list-status\s*\{[^}]*min-height: 88rpx;[^}]*font-size: 24rpx;/)
    assert.match(styles, /@keyframes background-loading-spin/)
  })
}

test('个人和团队背景加载状态样式保持一致', () => {
  assert.equal(readPage('portfolios/components/text-background-editor/text-background-editor.wxss'),
    readPage('team-portfolios/components/text-background-editor/text-background-editor.wxss'))
})

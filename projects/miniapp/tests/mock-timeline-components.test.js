const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const fixture = require('../../../tests/fixtures/portfolio-timeline-contract.json')
const api = require('../pages/mock/utils/mock-portfolio-timeline')
const clone = value => JSON.parse(JSON.stringify(value))
const read = file => fs.readFileSync(path.join(__dirname, '..', file), 'utf8')
const event = (dataset = {}, value) => ({ currentTarget: { dataset }, detail: { value } })
const config = () => api.normalizeTimeline({ title: '我的历程', nodes: [
  { nodeKey: 'first', datePrecision: 'DAY', date: '2024-02-29', title: '启程', description: '第一段经历' },
  { nodeKey: 'second', datePrecision: 'CUSTOM', date: '至今', title: '成长', description: '故事仍在继续' }
] })

function editor(properties = {}) {
  const filename = require.resolve('../components/mock/timeline-editor/timeline-editor')
  let definition
  const previous = global.Component
  global.Component = value => { definition = value }
  delete require.cache[filename]
  try { require(filename) } finally { global.Component = previous }
  const defaults = Object.fromEntries(Object.entries(definition.properties).map(([key, item]) => [key, clone(item.value)]))
  const instance = { ...definition.methods, properties: { ...defaults, visible: true, config: config(), ...properties }, data: clone(definition.data), events: [],
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback() },
    triggerEvent(name, detail) { this.events.push({ name, detail }) },
    createSelectorQuery() { return { selectAll() { return this }, boundingClientRect(callback) { this.callback = callback; return this }, exec() { this.callback([{ dataset: { key: 'first' }, top: 100, bottom: 150 }, { dataset: { key: 'second' }, top: 170, bottom: 220 }]) } } }
  }
  definition.observers.visible.call(instance, true)
  return instance
}

test('mock 时间轴独立规则符合相同配置与校验契约', () => {
  for (const item of fixture.cases) {
    const before = clone(item.config)
    const normalized = api.normalizeTimeline(item.config)
    assert.equal(!api.validateTimeline(normalized), item.valid, item.name)
    assert.deepEqual(item.config, before)
    if (item.valid) assert.deepEqual(api.normalizeTimeline(normalized), normalized)
  }
  for (const [key, value] of Object.entries(fixture.limits)) assert.equal(api[key], value)
})

test('mock 时间轴展示、编辑布局和纯规则与正式版本保持一致', () => {
  for (const component of ['timeline', 'timeline-editor']) {
    for (const extension of ['wxml', 'wxss', 'json']) {
      const expected = read(`pages/portfolios/components/${component}/${component}.${extension}`).replace('../../../../styles/', '../../../styles/')
      assert.equal(read(`components/mock/${component}/${component}.${extension}`), expected)
    }
  }
  const stripImport = value => value.slice(value.indexOf('\n') + 1)
  assert.equal(stripImport(read('components/mock/timeline/timeline-config.js')), stripImport(read('pages/portfolios/utils/portfolio-timeline.js')))
  assert.equal(api, require('../components/mock/timeline/timeline-config'))
})

test('mock 时间轴单行输入框不叠加纵向内边距，避免 WebView 原生文字居中后再次下移', () => {
  const css = read('components/mock/timeline-editor/timeline-editor.wxss')
  for (const selector of ['.timeline-input', '.timeline-date-part .timeline-input']) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const rule = css.match(new RegExp(`(?:^|\\n)${escaped}\\s*\\{([^}]*)\\}`))[1]
    const padding = rule.match(/(?:^|;)\s*padding:\s*([^;]+)/)[1].split(/\s+/)
    assert.equal(parseFloat(padding[0]), 0, `${selector} 不得在原生居中的输入内容上增加上内边距`)
    assert.equal(parseFloat(padding[2] || padding[0]), 0, `${selector} 不得压缩单行输入的下方空间`)
    assert.ok(parseFloat(padding[1]) > 0, `${selector} 保留左右留白`)
  }
  const multiline = css.match(/\.timeline-textarea\s*\{([^}]*)\}/)[1]
  assert.match(multiline, /padding:\s*20rpx 24rpx;/, '多行描述保留原有文字留白')
})

test('mock 时间轴取消丢弃候选，完成只发送一次完整配置', () => {
  const instance = editor(), before = clone(instance.properties.config)
  instance.handleField(event({ field: 'title' }, '本地候选'))
  assert.deepEqual(instance.properties.config, before)
  instance.handleCancel()
  instance.handleField(event({ field: 'title' }, '迟到事件'))
  assert.equal(instance.data.draft.title, '本地候选')
  assert.equal(instance.events.some(item => item.name === 'confirm'), false)
  const confirmed = editor()
  confirmed.handleField(event({ field: 'direction', value: 'VERTICAL' }))
  confirmed.handleConfirm(); confirmed.handleConfirm()
  assert.equal(confirmed.events.filter(item => item.name === 'confirm').length, 1)
  assert.equal(confirmed.events.find(item => item.name === 'confirm').detail.direction, 'VERTICAL')
})

test('mock 时间轴节点拖动保留身份与日期缓存，日期校验阻止非法完成', () => {
  const instance = editor()
  instance.handleDragStart({ currentTarget: { dataset: { key: 'first' } }, touches: [{ identifier: 1, clientY: 125 }] })
  instance.handleDragMove({ touches: [{ identifier: 1, clientY: 200 }] })
  instance.handleDragEnd()
  assert.deepEqual(instance.data.draft.nodes.map(node => node.nodeKey), ['second', 'first'])
  assert.equal(instance._dates.first.day, '29')
  instance.handleDateBlur(event({ nodeKey: 'first', field: 'year' }, '2023'))
  assert.equal(instance._dates.first.day, '')
  instance.handleConfirm()
  assert.equal(instance.events.some(item => item.name === 'confirm'), false)
  assert.match(instance.data.error, /有效日期/)
})

test('mock 时间轴字体使用页面提供的静态可用性与预览，不依赖正式字体服务', () => {
  const instance = editor({ fontAvailability: { SYSTEM: true, WECHAT_SANS_SS: true }, remoteFontAvailable: true, fontEditSessionId: 8,
    fontOptions: [{ value: 'SYSTEM' }, { value: 'WECHAT_SANS_SS' }, { value: 'ALLURA', remote: true, available: true, sampleUrl: 'https://example.com/sample.png' }] })
  assert.equal(instance.data.fonts.find(item => item.value === 'WECHAT_SANS_SS').available, true)
  assert.equal(instance.data.fonts.find(item => item.value === 'ALLURA').previewImageUrl, 'https://example.com/sample.png')
  instance.handleEditTextStyle(event({ scope: 'node', role: 'title' }))
  instance.handleFont(event({ value: 'ALLURA' }))
  assert.equal(instance.data.draft.nodes[0].textStyles.title.fontId, 'ALLURA')
  assert.deepEqual(instance.events.find(item => item.name === 'fontselect').detail, { fontId: 'ALLURA', sessionId: 8 })
  instance.handleCancelTextStyle()
  assert.equal(instance.data.draft.nodes[0].textStyles, undefined)
})

test('mock 时间轴三种样式和两个方向保留深色主题与尾线布局', () => {
  for (const variant of ['MINIMAL', 'DATE_COLUMN', 'MILESTONE_CARD']) for (const direction of ['HORIZONTAL', 'VERTICAL']) {
    const value = { ...config(), variant, direction }
    const view = api.presentTimeline(value, 'TEAM', 'dark')
    assert.equal(view.theme, 'dark')
    assert.equal(view.kicker, 'OUR STORY')
    assert.equal(view.nodes[1].displayDate, '至今')
    const measurements = value.nodes.map(() => ({ dateWidth: 80, dateHeight: 30, dateFirstLine: 30, bodyWidth: 240, bodyHeight: 120, height: 180 }))
    const layout = api.layoutTimeline(value, 350, 0.5, measurements)
    assert.ok(layout.nodes[1].tailLength >= 48)
    assert.equal(layout.nodes[1].last, true)
    assert.equal(layout.nodes[0].last, false)
  }
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const fixture = require('../../../tests/fixtures/portfolio-timeline-contract.json')
const clone = value => JSON.parse(JSON.stringify(value))
const valid = () => ({ variant: 'MINIMAL', direction: 'HORIZONTAL', title: '历程', showKicker: true, minGapRpx: { horizontal: 320, vertical: 200 }, textStyles: {},
  nodes: [{ nodeKey: 'first', datePrecision: 'DAY', date: '2024-02-29', title: '启程', description: '第一行\n第二行' }] })

for (const pkg of ['portfolios', 'team-portfolios']) {
  const api = require(`../pages/${pkg}/utils/portfolio-timeline`)
  for (const item of fixture.cases) test(`${pkg} 时间轴跨语言契约 ${item.name}`, () => {
    const before = clone(item.config)
    const normalized = api.normalizeTimeline(item.config)
    assert.equal(!api.validateTimeline(normalized), item.valid, api.validateTimeline(normalized))
    assert.deepEqual(item.config, before)
    if (item.valid) assert.deepEqual(api.normalizeTimeline(normalized), normalized)
  })
  test(`${pkg} 时间轴硬限制与后端夹具相同`, () => { Object.entries(fixture.limits).forEach(([key, value]) => assert.equal(api[key], value)) })
  test(`${pkg} 新增节点不虚构经历，显式空标题与英文关闭保持原值`, () => {
    const initial = api.createTimeline(pkg === 'portfolios' ? 'USER' : 'TEAM')
    assert.equal(initial.nodes.length, 1)
    assert.equal(initial.nodes[0].date, '')
    assert.equal(initial.nodes[0].description, '')
    assert.ok(api.validateTimeline(initial))
    const configured = api.normalizeTimeline({ ...valid(), title: '', showKicker: false })
    assert.equal(configured.title, '')
    assert.equal(configured.showKicker, false)
  })
  test(`${pkg} 字体原子继承、局部样式与实际字形需求分离`, () => {
    const config = valid()
    config.textStyles = { date: { fontId: 'ALLURA', fontFamily: 'SYSTEM' }, title: { fontId: 'MANROPE', fontFamily: 'SYSTEM' } }
    config.nodes[0].textStyles = { date: { color: '#112233', fontSizeRpx: 40 }, title: { fontFamily: 'SYSTEM' } }
    assert.equal(api.resolveTimelineStyle(config, 'date', config.nodes[0]).fontId, 'ALLURA')
    assert.equal(api.resolveTimelineStyle(config, 'title', config.nodes[0]).fontId, undefined)
    const demands = api.timelineFontDemands(config)
    assert.equal(demands.find(item => item.fontId === 'ALLURA').content, '2024.02.29')
    assert.equal(demands.find(item => item.fontId === 'ALLURA').fontWeight, 'BOLD')
    assert.equal(demands.some(item => item.fontId === 'MANROPE'), false)
    assert.equal(api.timelineFontSelections(config).some(item => item.fontId === 'MANROPE'), true)
    config.variant = 'DATE_COLUMN'
    assert.equal(api.timelineFontDemands(config).find(item => item.fontId === 'ALLURA').fontWeight, 'NORMAL')
    delete config.nodes[0].textStyles.title
    assert.ok(api.timelineFontDemands(config).some(item => item.fontId === 'MANROPE'))
    assert.equal(config.nodes[0].fontId, undefined)
  })
  test(`${pkg} 字形加载器使用展示日期、继承字体和正确字重`, () => {
    const { collectFontNodes, collectFontSelections } = require(`../pages/${pkg}/utils/portfolio-remote-font-session`)
    const config = valid(); config.textStyles.date = { fontId: 'ALLURA' }
    const components = [{ componentType: 'TIMELINE', config }]
    const demands = collectFontNodes(components)
    assert.equal(demands.find(item => item.fontId).content, '2024.02.29')
    assert.equal(demands.find(item => item.fontId).fontWeight, 'BOLD')
    assert.equal(collectFontSelections(components).length, 1)
    config.nodes[0].textStyles = { date: { fontFamily: 'SYSTEM' } }
    assert.equal(collectFontNodes(components).some(item => item.fontId), false)
    assert.equal(collectFontSelections(components).some(item => item.fontId), true)
  })
  test(`${pkg} 主题合法优先、背景推导与固定色保持`, () => {
    assert.equal(api.timelineTheme('dark', '#FFFFFF'), 'dark')
    assert.equal(api.timelineTheme('injected', '#000000'), 'dark')
    assert.equal(api.timelineTheme('', 'bad'), 'light')
    const config = valid(); config.textStyles.title = { color: '#123456' }
    assert.match(api.presentTimeline(config, 'TEAM', 'dark').nodes[0].titleStyle, /#123456/)
    assert.equal(api.presentTimeline(config, 'TEAM').kicker, 'OUR STORY')
    assert.equal(api.presentTimeline(config, 'USER').kicker, 'MY JOURNEY')
  })
  test(`${pkg} 实测布局对齐圆点、长日期完整、纵向不重叠`, () => {
    const config = valid(); config.nodes.push({ ...config.nodes[0], nodeKey: 'second' })
    const measurements = [{ dateWidth: 650, dateHeight: 35, dateFirstLine: 35, bodyWidth: 280, bodyHeight: 180, height: 245 },
      { dateWidth: 120, dateHeight: 70, dateFirstLine: 70, bodyWidth: 280, bodyHeight: 300, height: 395 }]
    const horizontal = api.layoutTimeline(config, 350, 0.5, measurements)
    assert.equal(horizontal.nodes[0].dotOffset, horizontal.nodes[1].dotOffset)
    assert.ok(horizontal.nodes[0].width >= 650)
    assert.ok(horizontal.nodes[1].left > horizontal.nodes[0].width)
    assert.equal(horizontal.nodes[1].lineLength, 0)
    assert.equal(horizontal.overflow, true)
    config.direction = 'VERTICAL'; config.minGapRpx.vertical = 640
    const vertical = api.layoutTimeline(config, 350, 0.5, measurements)
    assert.ok(vertical.nodes[1].top >= vertical.nodes[0].height + 16)
    assert.ok(vertical.nodes[1].dot - vertical.nodes[0].dot >= 320)
    assert.equal(vertical.overflow, false)
  })
  test(`${pkg} 空配置安全展示，异常测量和间距回退自然布局`, () => {
    assert.deepEqual(api.presentTimeline(null).nodes, [])
    const measured = [{ dateWidth: 40, dateHeight: 20, bodyWidth: 120, bodyHeight: 30 }]
    assert.equal(api.layoutTimeline(null, 350, 0.5, measured), null)
    assert.equal(api.layoutTimeline(valid(), Infinity, 0.5, measured), null)
    assert.equal(api.layoutTimeline(valid(), 350, 0.5, [{ ...measured[0], bodyHeight: NaN }]), null)
    assert.equal(api.layoutTimeline({ ...valid(), minGapRpx: null }, 350, 0.5, measured), null)
    assert.equal(api.layoutTimeline({ ...valid(), minGapRpx: { horizontal: 'bad' } }, 350, 0.5, measured), null)
  })
}

function renderer(pkg) {
  const filename = require.resolve(`../pages/${pkg}/components/timeline/timeline`)
  let definition
  const previous = global.Component
  global.Component = value => { definition = value }
  delete require.cache[filename]
  try { require(filename) } finally { global.Component = previous }
  const queries = []
  const properties = Object.fromEntries(Object.entries(definition.properties).map(([key, item]) => [key, clone(item.value)]))
  const instance = { ...definition.methods, _alive: true, properties: { ...properties, config: valid() }, data: clone(definition.data),
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback() },
    createSelectorQuery() {
      const query = { select() { return this }, selectAll() { return this }, boundingClientRect(callback) { if (callback) this.callback = callback; return this },
        exec(callback) { queries.push(callback || this.callback) } }
      return query
    } }
  return { instance, definition, queries }
}
for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg} 横向时间轴实测后滚动视口显式覆盖完整节点高度`, () => {
    const previous = global.wx
    global.wx = { getWindowInfo: () => ({ windowWidth: 375 }), nextTick: callback => callback() }
    try {
      const { instance, queries } = renderer(pkg)
      instance.properties.config.nodes.push({ ...valid().nodes[0], nodeKey: 'second' })
      instance.scheduleLayout()
      queries.shift()({ width: 350, height: 0 })
      queries.shift()([
        [{ width: 100, height: 22 }, { width: 100, height: 22 }],
        [{ width: 400, height: 60 }, { width: 400, height: 60 }],
        [{ width: 400, height: 106 }, { width: 400, height: 106 }]
      ])
      queries.shift()([
        [{ width: 100, height: 22 }, { width: 100, height: 22 }],
        [{ width: 200, height: 180 }, { width: 200, height: 60 }],
        [{ width: 200, height: 226 }, { width: 200, height: 106 }]
      ])
      assert.equal(instance.data.natural, false)
      assert.equal(instance.data.overflow, true)
      const wxml = fs.readFileSync(path.join(__dirname, '../pages', pkg, 'components/timeline/timeline.wxml'), 'utf8')
      const scroll = wxml.match(/<scroll-view\b[^>]*class="timeline-scroll"[^>]*>/)[0]
      const binding = scroll.match(/style="\{\{(\w+)\}\}"/)
      assert.ok(binding, '真机滚动视口不能只依赖内部绝对定位画布撑高')
      const style = instance.data[binding[1]]
      const viewportHeight = Number((style.match(/(?:^|;)height:([\d.]+)px/) || [])[1])
      const contentHeight = Number(instance.data.frameStyle.match(/height:([\d.]+)px/)[1])
      assert.ok(contentHeight >= 226, '最长描述必须计入可见区域')
      assert.equal(viewportHeight, contentHeight)
      assert.doesNotMatch(style, /(?:^|;)width:/, '画布横向超出时视口宽度仍跟随页面，保留滑动能力')
    } finally { global.wx = previous }
  })
  test(`${pkg} 纵向与测量回退节点使用普通容器，避免原生滚动视口裁切`, () => {
    const base = path.join(__dirname, '../pages', pkg, 'components/timeline/timeline')
    const wxml = fs.readFileSync(base + '.wxml', 'utf8')
    assert.match(wxml, /<scroll-view\b[^>]*wx:if="\{\{horizontal && !natural\}\}"/)
    assert.match(wxml, /<view\b[^>]*wx:else[^>]*class="timeline-scroll"[^>]*>\s*<template\b/)
    const css = fs.readFileSync(base + '.wxss', 'utf8')
    assert.match(css, /\.timeline-natural\.timeline-horizontal \.timeline-frame\s*\{[^}]*display:block/)
    assert.match(css, /\.timeline-natural\.timeline-horizontal \.timeline-node\s*\{[^}]*min-width:0;[^}]*width:100%/)
  })
  test(`${pkg} 旧测量回调与页面销毁回调失效，测量失败只重试一次`, async () => {
    const previous = global.wx
    global.wx = { getWindowInfo: () => ({ windowWidth: 375 }), nextTick: callback => callback() }
    try {
      const { instance, definition, queries } = renderer(pkg)
      instance.scheduleLayout()
      const staleWidth = queries.shift()
      instance.properties.config = { ...valid(), title: '新标题' }
      instance.scheduleLayout()
      staleWidth({ width: 350 })
      assert.equal(instance.data.configView.title, '新标题')
      assert.equal(queries.length, 1)
      queries.shift()({ width: 350 })
      queries.shift()([])
      assert.equal(queries.length, 1)
      queries.shift()([])
      assert.equal(queries.length, 0)
      assert.equal(instance.data.natural, true)
      assert.equal(instance.data.probes.length, 0)
      const waiting = instance.waitForFontLayout()
      const detachedWidth = queries.shift()
      definition.lifetimes.detached.call(instance)
      detachedWidth({ width: 350 })
      await waiting
      assert.equal(queries.length, 0)
    } finally { global.wx = previous }
  })
  test(`${pkg} 开屏超时主动自然回退并结束等待，旧探针不能覆盖`, async () => {
    const { instance, queries } = renderer(pkg)
    const waiting = instance.waitForFontLayout()
    const staleWidth = queries.shift()
    await instance.useNaturalFontLayout()
    await waiting
    staleWidth({ width: 350 })
    assert.equal(instance.data.natural, true)
    assert.equal(instance.data.frameStyle, '')
    assert.equal(instance.data.nodes[0].description, valid().nodes[0].description)
    assert.equal(queries.length, 0)
  })
}

function editor(pkg) {
  const filename = require.resolve(`../pages/${pkg}/components/timeline-editor/timeline-editor`)
  let definition
  const previous = global.Component
  global.Component = value => { definition = value }
  delete require.cache[filename]
  require(filename)
  global.Component = previous
  const properties = Object.fromEntries(Object.entries(definition.properties).map(([key, item]) => [key, clone(item.value)]))
  const instance = { ...definition.methods, properties: { ...properties, config: valid(), visible: true }, data: clone(definition.data), events: [],
    setData(patch) { Object.assign(this.data, patch) }, triggerEvent(name, detail) { this.events.push({ name, detail }) } }
  definition.observers.visible.call(instance, true)
  return { instance, definition }
}
for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg} 编辑器样式页完整预览随外层纵向滚动，不限制长内容高度`, () => {
    const base = path.join(__dirname, '../pages', pkg, 'components/timeline-editor/timeline-editor')
    const css = fs.readFileSync(base + '.wxss', 'utf8')
    const wxml = fs.readFileSync(base + '.wxml', 'utf8')
    const previewRules = [...css.matchAll(/\.timeline-preview\s*\{([^}]*)\}/g)].map(match => match[1]).join(';')
    assert.doesNotMatch(previewRules, /(?:^|;)\s*(?:max-)?height\s*:/, '预览须按完整内容增高，不能截断纵向节点或长描述')
    assert.match(wxml, /<scroll-view[^>]*class="timeline-editor-scroll"[^>]*scroll-y[^>]*>[\s\S]*class="timeline-preview"[\s\S]*<portfolio-timeline[^>]*>[\s\S]*<\/scroll-view>/)
  })
  test(`${pkg} 时间轴取消不修改配置，完成发送一次完整候选`, () => {
    const { instance } = editor(pkg), before = clone(instance.properties.config)
    instance.handleField({ currentTarget: { dataset: { field: 'title' } }, detail: { value: '新标题' } })
    assert.equal(instance.data.draft.title, '新标题')
    assert.deepEqual(instance.properties.config, before)
    instance.handleCancel()
    instance.handleField({ currentTarget: { dataset: { field: 'title' } }, detail: { value: '迟到' } })
    assert.equal(instance.data.draft.title, '新标题')
    assert.equal(instance.events.filter(item => item.name === 'confirm').length, 0)
    const another = editor(pkg).instance
    another.handleConfirm()
    assert.deepEqual(another.events.find(item => item.name === 'confirm').detail, valid())
  })
  test(`${pkg} 日期精度在本次会话记忆隐藏月日，闰日失效明确清空日`, () => {
    const { instance } = editor(pkg)
    const precision = value => instance.handlePrecision({ currentTarget: { dataset: { value } } })
    precision('YEAR'); assert.equal(instance.data.draft.nodes[0].date, '2024')
    precision('DAY'); assert.equal(instance.data.draft.nodes[0].date, '2024-02-29')
    instance.handleDate({ currentTarget: { dataset: { field: 'year', nodeKey: 'first' } }, detail: { value: '2023' } })
    assert.equal(instance.data.dates.day, '29')
    instance.handleDateBlur({ currentTarget: { dataset: { field: 'year', nodeKey: 'first' } }, detail: { value: '2023' } })
    assert.equal(instance.data.dates.day, '')
    assert.match(instance.data.error, /重新选择日/)
    instance.handleConfirm()
    assert.equal(instance.events.some(item => item.name === 'confirm'), false)
  })
  test(`${pkg} 文字样式只改当前角色，恢复字体不清空颜色`, () => {
    const { instance } = editor(pkg)
    instance.handleEditTextStyle({ currentTarget: { dataset: { scope: 'node', role: 'title' } } })
    instance.handleColor({ detail: { color: '#123456' } })
    instance.styleEdit(patch => { patch.fontId = 'ALLURA'; patch.fontFamily = 'SYSTEM' })
    instance.handleResetStyle({ currentTarget: { dataset: { field: 'font' } } })
    assert.deepEqual(instance.data.draft.nodes[0].textStyles.title, { color: '#123456' })
    assert.deepEqual(instance.data.draft.textStyles, {})
  })
}

test('时间轴分包规则、展示与编辑组件逐字节一致，真实页面已注册', () => {
  const files = ['utils/portfolio-timeline.js', ...['timeline', 'timeline-editor'].flatMap(name => ['js', 'json', 'wxml', 'wxss'].map(ext => `components/${name}/${name}.${ext}`))]
  files.forEach(file => assert.equal(fs.readFileSync(path.join(__dirname, '../pages/portfolios', file), 'utf8'), fs.readFileSync(path.join(__dirname, '../pages/team-portfolios', file), 'utf8'), file))
  const routes = ['portfolios/standard-preview/portfolio-standard-preview', 'portfolios/visitor-portfolio/visitor-portfolio', 'team-portfolios/standard-preview/team-portfolio-standard-preview', 'team-portfolios/visitor-portfolio/team-visitor-portfolio', 'team-portfolios/advanced-visitor/team-visitor-portfolio']
  routes.forEach(route => {
    assert.ok(JSON.parse(fs.readFileSync(path.join(__dirname, '../pages', route + '.json'))).usingComponents['portfolio-timeline'])
    const wxml = fs.readFileSync(path.join(__dirname, '../pages', route + '.wxml'), 'utf8')
    assert.match(wxml, /<portfolio-timeline[^>]*theme-mode=/)
    assert.match(wxml, /<portfolio-timeline[^>]*font-context=/)
  })
})

for (const pkg of ['portfolios', 'team-portfolios']) {
  const api = require(`../pages/${pkg}/utils/portfolio-timeline`)
  test(`${pkg} 自定义节点原文、码点边界与单行限制`, () => {
    const config = valid(), node = config.nodes[0]
    node.datePrecision = 'CUSTOM'; node.date = ' 阶段  v1.0-至今 '
    assert.equal(api.validateTimeline(config), '')
    assert.equal(api.formatTimelineDate(node.datePrecision, node.date), node.date)
    const view = api.presentTimeline(config).nodes[0]
    assert.equal(view.displayDate, node.date)
    assert.equal(view.customDate, true)
    assert.equal(view.year, '')
    assert.equal(view.monthDay, '')
    node.date = '😀'.repeat(20)
    assert.equal(api.validateTimeline(config), '')
    node.date += '😀'
    assert.match(api.validateTimeline(config), /20/)
    for (const text of ['', ' \u00a0\ufeff', '换\n行', '制\t表', '段\u2028落', '删\u007f除', '新\u0085行']) {
      node.date = text
      assert.ok(api.validateTimeline(config), JSON.stringify(text))
    }
  })
  test(`${pkg} 自定义节点计入总文字和原样字体需求`, () => {
    const config = valid(); config.nodes[0].datePrecision = 'CUSTOM'; config.nodes[0].date = '未来-v1.0'
    config.textStyles.date = { fontId: 'ALLURA' }
    const count = Array.from(config.title + config.nodes[0].title + config.nodes[0].description + config.nodes[0].date).length
    assert.equal(api.countTimelineText(config), count)
    assert.equal(api.timelineFontDemands(config).find(item => item.fontId === 'ALLURA').content, '未来-v1.0')
  })
  test(`${pkg} 自定义与日期临时切换保留输入，完成只写当前标记`, () => {
    const { instance } = editor(pkg)
    const precision = value => instance.handlePrecision({ currentTarget: { dataset: { value } } })
    precision('CUSTOM')
    assert.equal(instance.data.draft.nodes[0].date, '')
    assert.match(instance.data.datePreview, /自定义标签/)
    instance.handleCustomDate({ currentTarget: { dataset: { nodeKey: 'first' } }, detail: { value: '筹备期' } })
    assert.equal(instance.data.datePreview, '筹备期')
    precision('DAY'); assert.equal(instance.data.datePreview, '2024.02.29')
    precision('CUSTOM'); assert.equal(instance.data.draft.nodes[0].date, '筹备期')
    instance.handleConfirm()
    const node = instance.events.find(item => item.name === 'confirm').detail.nodes[0]
    assert.equal(node.datePrecision, 'CUSTOM')
    assert.equal(node.date, '筹备期')
    assert.equal(node.customLabel, undefined)
    assert.equal(node.year, undefined)
    assert.equal(instance.properties.config.nodes[0].date, '2024-02-29')
  })
  test(`${pkg} 日期预览实时更新，年份编辑中保留闰日`, () => {
    const { instance } = editor(pkg)
    const input = value => instance.handleDate({ currentTarget: { dataset: { field: 'year', nodeKey: 'first' } }, detail: { value } })
    input('202'); assert.equal(instance.data.dates.day, '29')
    input('2028'); assert.equal(instance.data.datePreview, '2028.02.29')
    instance.handleDateBlur({ currentTarget: { dataset: { field: 'year', nodeKey: 'first' } }, detail: { value: '2028' } })
    assert.equal(instance.data.dates.day, '29')
    input(''); assert.match(instance.data.datePreview, /填写年份/)
    input('2024')
    instance.handlePrecision({ currentTarget: { dataset: { value: 'MONTH' } } })
    instance.handleDate({ currentTarget: { dataset: { field: 'month', nodeKey: 'first' } }, detail: { value: '01' } })
    assert.equal(instance.data.datePreview, '2024.01')
    assert.equal(instance.data.datePreviewValid, true)
  })
  test(`${pkg} 自定义预览为普通文字且分栏不拆分标签`, () => {
    const base = path.join(__dirname, '../pages', pkg, 'components')
    const editorWxml = fs.readFileSync(path.join(base, 'timeline-editor/timeline-editor.wxml'), 'utf8')
    const editorCss = fs.readFileSync(path.join(base, 'timeline-editor/timeline-editor.wxss'), 'utf8')
    assert.match(editorWxml, /bindinput="handleCustomDate"/)
    assert.match(editorWxml, /bindblur="handleDateBlur"/)
    assert.match(editorWxml, /<view class="timeline-date-preview[^>]*>[\s\S]*?{{datePreview}}/)
    const previewRules = [...editorCss.matchAll(/\.timeline-date-preview\s*\{([^}]*)\}/g)].map(match => match[1]).join(';')
    assert.doesNotMatch(previewRules, /background|border-radius|border:/)
    const rendererWxml = fs.readFileSync(path.join(base, 'timeline/timeline.wxml'), 'utf8')
    assert.equal((rendererWxml.match(/!item.customDate/g) || []).length, 2)
  })
}

for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg} 迟到日期回调不改写切换后的节点或自定义标签`, () => {
    const { instance } = editor(pkg)
    const late = { currentTarget: { dataset: { field: 'year', nodeKey: 'first' } }, detail: { value: '2023' } }
    instance.handleAddNode()
    const newNode = clone(instance.data.draft.nodes[1])
    instance.handleDate(late); instance.handleDateBlur(late)
    assert.deepEqual(instance.data.draft.nodes[1], newNode)
    instance.handleNode({ currentTarget: { dataset: { index: 0 } } })
    instance.handlePrecision({ currentTarget: { dataset: { value: 'CUSTOM' } } })
    instance.handleCustomDate({ currentTarget: { dataset: { nodeKey: 'first' } }, detail: { value: '筹备期' } })
    instance.handleDate(late); instance.handleDateBlur(late)
    assert.equal(instance.data.draft.nodes[0].date, '筹备期')
    assert.equal(instance.data.dates.day, '29')
  })
  test(`${pkg} 自定义取消和重新打开不带入未保存日期缓存`, () => {
    const { instance, definition } = editor(pkg)
    instance.handlePrecision({ currentTarget: { dataset: { value: 'CUSTOM' } } })
    instance.handleCustomDate({ currentTarget: { dataset: { nodeKey: 'first' } }, detail: { value: '未来' } })
    instance.handleCancel()
    definition.observers.visible.call(instance, true)
    assert.equal(instance.data.datePreview, '2024.02.29')
    assert.equal(instance.data.customDate, '')
    instance.properties.config = { ...valid(), nodes: [{ ...valid().nodes[0], datePrecision: 'CUSTOM', date: '未来' }] }
    definition.observers.visible.call(instance, true)
    assert.equal(instance.data.customDate, '未来')
    assert.equal(instance.data.dates.year, '')
    instance.handlePrecision({ currentTarget: { dataset: { value: 'MONTH' } } })
    assert.equal(instance.data.datePreview, '请先填写年份')
  })
  test(`${pkg} 自定义长标签实测分栏限宽，换行高度推开后续节点`, () => {
    const previous = global.wx
    global.wx = { getWindowInfo: () => ({ windowWidth: 375 }), nextTick: callback => callback() }
    try {
      const { instance, queries } = renderer(pkg)
      instance.properties.config = { ...valid(), direction: 'VERTICAL', variant: 'DATE_COLUMN', nodes: [
        { ...valid().nodes[0], datePrecision: 'CUSTOM', date: '一段需要完整换行展示的自定义阶段标签' },
        { ...valid().nodes[0], nodeKey: 'second' }
      ] }
      instance.scheduleLayout(); queries.shift()({ width: 350 })
      queries.shift()([[{ width: 500, height: 28 }, { width: 70, height: 45 }], [{ width: 200, height: 80 }, { width: 200, height: 80 }], [{ width: 500, height: 80 }, { width: 270, height: 80 }]])
      assert.equal(instance.data.compact, false)
      assert.equal(instance.data.probes[0].dateColumnStyle, 'width:126px;')
      assert.equal(instance.data.probes[0].bodyStyle, 'width:196px;')
      queries.shift()([[{ width: 126, height: 140 }, { width: 126, height: 45 }], [{ width: 196, height: 80 }, { width: 196, height: 80 }], [{ width: 350, height: 140 }, { width: 350, height: 80 }]])
      assert.equal(instance.data.natural, false)
      assert.match(instance.data.nodes[1].style, /top:156px/)
      assert.equal(instance.data.nodes[0].displayDate, '一段需要完整换行展示的自定义阶段标签')
      assert.equal(instance.data.nodes[0].monthDay, '')
    } finally { global.wx = previous }
  })
}

for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg} 已完成布局重新测量失败后自然展开，再次成功恢复滚动视口高度`, () => {
    const previous = global.wx
    global.wx = { getWindowInfo: () => ({ windowWidth: 375 }), nextTick: callback => callback() }
    try {
      const { instance, queries } = renderer(pkg)
      const completeMeasurement = bodyHeight => {
        queries.shift()({ width: 350 })
        queries.shift()([
          [{ width: 100, height: 22 }],
          [{ width: 400, height: 60 }],
          [{ width: 400, height: 106 }]
        ])
        queries.shift()([
          [{ width: 100, height: 22 }],
          [{ width: 200, height: bodyHeight }],
          [{ width: 200, height: bodyHeight + 46 }]
        ])
      }
      instance.scheduleLayout()
      completeMeasurement(180)
      assert.equal(instance.data.natural, false)
      assert.equal(instance.data.viewportStyle, 'height:226px;')

      instance.scheduleLayout()
      assert.equal(instance.data.viewportStyle, '')
      assert.equal(instance.data.frameStyle, '')
      queries.shift()({ width: 350 })
      queries.shift()([])
      queries.shift()([])
      assert.equal(queries.length, 0)
      assert.equal(instance.data.natural, true)
      assert.equal(instance.data.viewportStyle, '')
      assert.equal(instance.data.frameStyle, '')
      assert.equal(instance.data.nodes[0].description, valid().nodes[0].description)

      instance.scheduleLayout()
      completeMeasurement(260)
      assert.equal(instance.data.natural, false)
      assert.equal(instance.data.viewportStyle, 'height:306px;')
      assert.match(instance.data.frameStyle, /height:306px;/)
      assert.equal(queries.length, 0)
    } finally { global.wx = previous }
  })
}

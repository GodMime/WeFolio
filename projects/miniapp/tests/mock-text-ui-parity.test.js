const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const text = require('../pages/mock/utils/mock-portfolio-text')
const grid = require('../pages/mock/utils/mock-portfolio-text-grid')

function loadComponentDefinition(relativePath) {
  const absolutePath = require.resolve(relativePath)
  delete require.cache[absolutePath]
  let definition
  const previousComponent = global.Component
  global.Component = value => { definition = value }
  try {
    require(absolutePath)
  } finally {
    if (previousComponent === undefined) delete global.Component
    else global.Component = previousComponent
  }
  return definition
}

function componentInstance(definition, properties = {}) {
  const data = JSON.parse(JSON.stringify(definition.data || {}))
  const events = []
  const instance = {
    properties,
    data: Object.assign(data, properties),
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name, detail) { events.push({ name, detail }) }
  }
  Object.assign(instance, definition.methods || {})
  return { instance, events }
}

test('结构化文字新区块沿用正式版各类型的字号与间距预设', () => {
  const expected = {
    EYEBROW: { fontSizeRpx: 24, fontWeight: 'NORMAL', marginTopRpx: 0, marginBottomRpx: 16 },
    TITLE: { fontSizeRpx: 44, fontWeight: 'BOLD', marginTopRpx: 24, marginBottomRpx: 16 },
    PARAGRAPH: { fontSizeRpx: 28, fontWeight: 'NORMAL', marginTopRpx: 0, marginBottomRpx: 16 },
    LIST: { fontSizeRpx: 26, fontWeight: 'NORMAL', marginTopRpx: 0, marginBottomRpx: 16 },
    HINT: { fontSizeRpx: 24, fontWeight: 'NORMAL', marginTopRpx: 32, marginBottomRpx: 0 }
  }

  Object.entries(expected).forEach(([type, style]) => {
    const block = text.createMockStructuredBlock(type, `block_${type}`)
    assert.deepEqual({
      fontSizeRpx: block.fontSizeRpx,
      fontWeight: block.fontWeight,
      marginTopRpx: block.marginTopRpx,
      marginBottomRpx: block.marginBottomRpx
    }, style)
  })
})

test('浅色页面启用文字背景时 AUTO 文字改用浅色且显式颜色保持不变', () => {
  const works = [{ id: 101, mediaType: 'IMAGE', mediaUrl: 'demo.jpg', width: 1000, height: 500 }]
  const auto = text.buildMockTextViewModel({
    content: '背景文字',
    color: 'AUTO',
    backgroundEnabled: true,
    backgroundWorkId: 101
  }, works, { themeMode: 'light' })
  const explicit = text.buildMockTextViewModel({
    content: '指定颜色',
    color: '#123456',
    backgroundEnabled: true,
    backgroundWorkId: 101
  }, works, { themeMode: 'light' })

  assert.match(auto.blocks[0].style, /color:#f8f9fa;/i)
  assert.match(explicit.blocks[0].style, /color:#123456;/i)
  assert.equal(auto.frameStyle, 'min-height:375rpx;')
})

test('文字网格默认段落居中并使用正式版浅色默认边线', () => {
  const value = grid.createMockTextGrid()
  assert.equal(value.cells[0].blocks[0].alignment, 'CENTER')
  assert.equal(value.cellBorderColor, '#D7DADD')
})

test('新建文字网格必须至少填写一处文字才能确认', () => {
  const value = grid.createMockTextGrid()
  assert.equal(grid.validateMockTextGrid(value, { requireText: true }), '请至少添加一处文字')
  value.cells[0].blocks[0].runs[0].text = '已填写'
  assert.equal(grid.validateMockTextGrid(value, { requireText: true }), '')
})

test('混合字号网格为整行提供最大字号行高基准', () => {
  const value = grid.createMockTextGrid(1, 1)
  const block = value.cells[0].blocks[0]
  block.lineHeight = 2
  block.runs[0].text = '小字'
  block.runs.push({ ...grid.createMockGridRun(), text: '大字', fontSizeRpx: 40 })

  const presentation = grid.buildMockGridViewModel(value)
  assert.match(presentation.cells[0].blocks[0].lineStyle, /font-size:40rpx;/)
  assert.match(presentation.cells[0].blocks[0].lineStyle, /line-height:2;/)
})

test('文字背景编辑器使用正式文案并默认收起素材列表', () => {
  const definition = loadComponentDefinition('../components/mock/text-background-editor/text-background-editor.js')
  const { instance } = componentInstance(definition, { config: { backgroundEnabled: true }, works: [] })
  definition.observers.config.call(instance, instance.properties.config)

  assert.deepEqual(Array.from(instance.data.treatmentLabels), ['原图直出', '全局暗色遮罩', '固定渐进遮罩'])
  assert.deepEqual(Array.from(instance.data.alignmentLabels), ['置顶', '居中', '下沉'])
  assert.equal(instance.data.pickerOpen, false)
  instance.handleTogglePicker()
  assert.equal(instance.data.pickerOpen, true)
})

test('文字背景编辑器在本地按作品名称搜索并提供正式收起入口', () => {
  const definition = loadComponentDefinition('../components/mock/text-background-editor/text-background-editor.js')
  const works = [
    { id: 101, title: '海边婚礼', mediaType: 'IMAGE', mediaUrl: 'sea.jpg' },
    { id: 102, title: '云端仪式', mediaType: 'VIDEO', mediaUrl: 'cloud.mp4' }
  ]
  const { instance, events } = componentInstance(definition, { config: { backgroundEnabled: true }, works })
  definition.observers.config.call(instance, instance.properties.config)

  instance.handleTogglePicker()
  instance.handleKeyword({ detail: { value: ' 云端 ' } })
  assert.deepEqual(Array.from(instance.data.displayWorks, item => item.id), [102])
  assert.deepEqual(events, [])
  instance.handleSearch()
  assert.deepEqual(Array.from(instance.data.displayWorks, item => item.id), [102])
  instance.handleClosePicker()
  assert.equal(instance.data.pickerOpen, false)

  const wxml = fs.readFileSync(path.join(__dirname, '../components/mock/text-background-editor/text-background-editor.wxml'), 'utf8')
  assert.match(wxml, /placeholder="搜索作品名称"/)
  assert.match(wxml, /bindtap="handleSearch">搜索/)
  assert.match(wxml, /bindtap="handleClosePicker">收起/)
  assert.match(wxml, /没有符合条件的图片、动图或视频/)
})

test('Mock 字体注册在本地返回真实可用性', async () => {
  let callbackCapability
  const capability = await text.registerMockTextFont({
    loadBuiltInFontFace(options) { options.success() }
  }, value => { callbackCapability = value })

  assert.deepEqual(capability, {
    apiAvailable: true,
    loadedFamilies: { WECHAT_SANS_SS: true }
  })
  assert.deepEqual(callbackCapability, capability)
  assert.equal(text.isMockTextFontAvailable('SYSTEM', capability), true)
  assert.equal(text.isMockTextFontAvailable('WECHAT_SANS_SS', capability), true)
  const unavailable = await text.registerMockTextFont({}, () => {})
  assert.equal(text.isMockTextFontAvailable('WECHAT_SANS_SS', unavailable), false)
})

test('结构化文字编辑器用 tab、摘要列表和单区块焦点编辑', () => {
  const definition = loadComponentDefinition('../components/mock/structured-text-editor/structured-text-editor.js')
  const config = { blocks: [
    { ...text.createMockStructuredBlock('TITLE', 'title'), content: '主标题' },
    { ...text.createMockStructuredBlock('PARAGRAPH', 'body'), content: '正文内容' }
  ] }
  const { instance, events } = componentInstance(definition, { config })
  definition.observers.config.call(instance, config)

  assert.equal(instance.data.tab, 'content')
  assert.deepEqual(Array.from(instance.data.blockSummaries, item => item.summary), ['主标题', '正文内容'])
  assert.equal(instance.data.selectedBlock.blockKey, 'title')
  instance.handleSelectBlock({ currentTarget: { dataset: { index: 1 } } })
  assert.equal(instance.data.selectedBlock.blockKey, 'body')
  instance.handleTab({ currentTarget: { dataset: { value: 'background' } } })
  assert.equal(instance.data.tab, 'background')
  assert.deepEqual(events.at(-1), { name: 'tabchange', detail: { tab: 'background' } })
})

test('文字网格编辑器分为布局内容外观并只编辑当前焦点', () => {
  const definition = loadComponentDefinition('../components/mock/text-grid-editor/text-grid-editor.js')
  const config = grid.createMockTextGrid()
  const { instance, events } = componentInstance(definition, { config, previewViewModel: { cells: [] } })
  definition.observers.config.call(instance, config)

  assert.equal(instance.data.tab, 'layout')
  assert.equal(instance.data.focusedCell.cellKey, config.cells[0].cellKey)
  instance.handleFocusCell({ currentTarget: { dataset: { index: 2 } } })
  assert.equal(instance.data.focusedCell.cellKey, config.cells[2].cellKey)
  instance.handleTab({ currentTarget: { dataset: { value: 'content' } } })
  assert.equal(instance.data.tab, 'content')
  assert.deepEqual(events.at(-1), { name: 'tabchange', detail: { tab: 'content' } })

  const wxml = fs.readFileSync(path.join(__dirname, '../components/mock/text-grid-editor/text-grid-editor.wxml'), 'utf8')
  const json = JSON.parse(fs.readFileSync(path.join(__dirname, '../components/mock/text-grid-editor/text-grid-editor.json'), 'utf8'))
  assert.match(wxml, /tab === 'layout'/)
  assert.match(wxml, /tab === 'content'/)
  assert.match(wxml, /tab === 'appearance'/)
  assert.match(wxml, /<mock-text-grid[^>]+view-model="\{\{previewViewModel\}\}"/)
  assert.equal(json.usingComponents['mock-text-grid'], '/components/mock/text-grid/text-grid')
})

test('结构化文字阻止选择不可用字体并使用统一颜色编辑器', () => {
  const definition = loadComponentDefinition('../components/mock/structured-text-editor/structured-text-editor.js')
  const config = { blocks: [{ ...text.createMockStructuredBlock('TITLE', 'title'), content: '标题' }] }
  const { instance, events } = componentInstance(definition, { config, fontAvailability: { SYSTEM: true, WECHAT_SANS_SS: false } })
  definition.observers.config.call(instance, config)
  definition.observers.fontAvailability.call(instance, instance.properties.fontAvailability)

  assert.equal(instance.data.fontOptions[1].available, false)
  instance.handleFontTap({ currentTarget: { dataset: { value: 'WECHAT_SANS_SS' } } })
  assert.equal(events.length, 0)
  instance.handleBlockColor({ detail: { color: '#123456' } })
  assert.equal(events.at(-1).detail.config.blocks[0].color, '#123456')

  const wxml = fs.readFileSync(path.join(__dirname, '../components/mock/structured-text-editor/structured-text-editor.wxml'), 'utf8')
  const json = JSON.parse(fs.readFileSync(path.join(__dirname, '../components/mock/structured-text-editor/structured-text-editor.json'), 'utf8'))
  assert.match(wxml, /当前设备不可用/)
  assert.match(wxml, /<mock-text-color-editor/)
  assert.equal(json.usingComponents['mock-text-color-editor'], '/components/mock/text-color-editor/text-color-editor')
})

test('文字网格字体、颜色和数值控件遵循正式编辑语义', () => {
  const definition = loadComponentDefinition('../components/mock/text-grid-editor/text-grid-editor.js')
  const config = grid.createMockTextGrid()
  const { instance, events } = componentInstance(definition, { config, fontAvailability: { SYSTEM: true, WECHAT_SANS_SS: false } })
  definition.observers.config.call(instance, config)
  definition.observers.fontAvailability.call(instance, instance.properties.fontAvailability)

  instance.handleFontTap({ currentTarget: { dataset: { value: 'WECHAT_SANS_SS' } } })
  assert.equal(events.length, 0)
  instance.handleRunColor({ detail: { color: '#123456' } })
  assert.equal(events.at(-1).detail.config.cells[0].blocks[0].runs[0].color, '#123456')
  instance.handleStep({ currentTarget: { dataset: { scope: 'run', field: 'fontSizeRpx', delta: 1 } } })
  assert.equal(events.at(-1).detail.config.cells[0].blocks[0].runs[0].fontSizeRpx, 29)
  instance.handleStep({ currentTarget: { dataset: { scope: 'grid', field: 'gapRpx', delta: -1 } } })
  assert.equal(events.at(-1).detail.config.gapRpx, 15)
  instance.handleStep({ currentTarget: { dataset: { scope: 'block', field: 'lineHeight', delta: 0.1 } } })
  instance.handleStep({ currentTarget: { dataset: { scope: 'block', field: 'lineHeight', delta: 0.1 } } })
  assert.equal(events.at(-1).detail.config.cells[0].blocks[0].lineHeight, 1.7)

  const wxml = fs.readFileSync(path.join(__dirname, '../components/mock/text-grid-editor/text-grid-editor.wxml'), 'utf8')
  const json = JSON.parse(fs.readFileSync(path.join(__dirname, '../components/mock/text-grid-editor/text-grid-editor.json'), 'utf8'))
  assert.match(wxml, /class="stepper"/)
  assert.match(wxml, /grid-border-width-slider/)
  assert.match(wxml, /palette="border"/)
  assert.equal(json.usingComponents['mock-text-color-editor'], '/components/mock/text-color-editor/text-color-editor')
})

test('文字网格编辑器原样转发预览测量结果', () => {
  const definition = loadComponentDefinition('../components/mock/text-grid-editor/text-grid-editor.js')
  const { instance, events } = componentInstance(definition, { config: grid.createMockTextGrid() })
  const detail = { widthPx: 320, heightsPx: { cell_1: 48 } }

  instance.handlePreviewMeasure({ detail })

  assert.deepEqual(events, [{ name: 'previewmeasure', detail }])
  const wxml = fs.readFileSync(path.join(__dirname, '../components/mock/text-grid-editor/text-grid-editor.wxml'), 'utf8')
  assert.match(wxml, /<mock-text-grid[^>]+bindmeasure="handlePreviewMeasure"/)
})

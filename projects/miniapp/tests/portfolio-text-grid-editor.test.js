const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const gridApi = require('../pages/portfolios/utils/portfolio-text-grid')
const clone = value => JSON.parse(JSON.stringify(value))
const contactDefaults = { contactBorder: false, horizontalMarginRpx: 0, verticalMarginRpx: 0, contactBorderWidthRpx: 1, contactBorderColor: 'AUTO' }
function component(scope, name, properties = {}, platform = {}, wxApi = {}) {
  const filename = path.join(__dirname, '../pages', scope, 'components', name, `${name}.js`)
  let definition
  const events = []
  const wx = { nextTick: callback => callback(), getWindowInfo: () => ({ windowWidth: 375 }), ...wxApi }
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { Component: value => { definition = value }, wx, setTimeout, clearTimeout,
    require(relative) { return relative.includes('portfolio-component-platform') ? { ...require(path.resolve(path.dirname(filename), relative)), getPortfolioFontCapability: () => ({}), isPortfolioFontAvailable: () => true, loadPortfolioFonts: () => Promise.resolve(), ...platform } : require(path.resolve(path.dirname(filename), relative)) } }, { filename })
  const props = Object.fromEntries(Object.entries(definition.properties).map(([key, value]) => [key, clone(value.value)]))
  Object.assign(props, properties)
  const instance = { properties: props, data: { ...clone(definition.data || {}), ...clone(props) }, updates: [], events,
    setData(values, callback) { Object.assign(this.data, values); this.updates.push(values); if (callback) callback() },
    triggerEvent(name, detail) { events.push({ name, detail: clone(detail || {}) }) } }
  Object.assign(instance, definition.methods)
  return { instance, definition }
}
const event = (dataset, value) => ({ currentTarget: { dataset }, detail: { value } })
function styleFor(source, selector) {
  const declarations = {}
  for (const match of source.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    if (!match[1].split(',').some(value => value.trim() === selector)) continue
    for (const declaration of match[2].split(';')) {
      const colon = declaration.indexOf(':')
      if (colon > 0) declarations[declaration.slice(0, colon).trim()] = declaration.slice(colon + 1).trim()
    }
  }
  return declarations
}
function buttonClasses(source) {
  return Array.from(source.matchAll(/<button\b[^>]*>/g), match => {
    const attribute = match[0].match(/\bclass="([^"]*)"/)
    return attribute ? attribute[1].split(/\s+/) : []
  })
}
for (const scope of ['portfolios', 'team-portfolios']) {
  test(`${scope}: paragraph line height input and steps preserve defaults, per-block values, undo and reopen`, () => {
    const config = gridApi.createTextGrid(1, 1), original = clone(config)
    config.cells[0].blocks[0].runs[0].text = '行间距'
    original.cells[0].blocks[0].runs[0].text = '行间距'
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config })
    definition.observers.visible.call(editor, true)
    assert.equal(Object.hasOwn(editor.data.block, 'lineHeight'), false)
    assert.equal(editor.data.lineHeightEditor.value, '')
    assert.match(editor.data.lineHeightEditor.placeholder, /1.5/)
    editor.handleConfirm()
    assert.equal(Object.hasOwn(editor.events.at(-1).detail.cells[0].blocks[0], 'lineHeight'), false)
    definition.observers.visible.call(editor, true)
    editor.handleTab(event({ value: 'content' }))
    editor.handleLineHeightStep(event({ delta: 0.1 }))
    assert.equal(editor.data.block.lineHeight, 1.6)
    editor.handleUndo()
    assert.equal(Object.hasOwn(editor.data.block, 'lineHeight'), false)
    editor.handleLineHeightStep(event({ delta: -0.1 }))
    assert.equal(editor.data.block.lineHeight, 1.4)
    for (const [value, delta, expected] of [[0.5, -0.1, 0.5], [0.5, 0.1, 0.6], [2.9, 0.1, 3], [3, 0.1, 3], [3, -0.1, 2.9]]) {
      editor.handleLineHeightInput(event({}, String(value)))
      editor.handleLineHeightStep(event({ delta }))
      assert.equal(editor.data.block.lineHeight, expected)
    }
    editor.handleLineHeightInput(event({}, '0.5'))
    assert.equal(editor.data.lineHeightEditor.decreaseDisabled, true)
    editor.handleAddRun(); editor.handleField(event({ field: 'text' }, '第二片段'))
    assert.equal(editor.data.block.lineHeight, 0.5)
    assert.ok(editor.data.block.runs.every(run => !Object.hasOwn(run, 'lineHeight')))
    editor.handleAddBlock(); editor.handleField(event({ field: 'text' }, '第二段'))
    assert.equal(Object.hasOwn(editor.data.block, 'lineHeight'), false)
    editor.handleLineHeightInput(event({}, '3'))
    assert.equal(editor.data.lineHeightEditor.increaseDisabled, true)
    editor.handleConfirm()
    const saved = editor.events.at(-1).detail
    assert.deepEqual(saved.cells[0].blocks.map(block => block.lineHeight), [0.5, 3])
    editor.properties.config = saved; definition.observers.visible.call(editor, true)
    assert.equal(editor.data.block.lineHeight, 0.5)
    editor.handleTab(event({ value: 'content' })); editor.handleSelectBlock(event({ index: 1 }))
    assert.equal(editor.data.block.lineHeight, 3)
    editor.handleLineHeightInput(event({}, '1.2')); editor.handleCancel()
    assert.equal(saved.cells[0].blocks[1].lineHeight, 3)
    assert.deepEqual(config, original)
  })
  test(`${scope}: incomplete or invalid line height remains visible, blocks confirmation and can be undone`, () => {
    const config = gridApi.createTextGrid(1, 1)
    config.cells[0].blocks[0].runs[0].text = '行间距'
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config })
    definition.observers.visible.call(editor, true)
    editor.handleTab(event({ value: 'content' }))
    for (const value of ['', '0.', '0.4', '3.1', '1.55', 'abc']) {
      editor.handleLineHeightInput(event({}, value))
      assert.equal(editor.data.lineHeightEditor.value, value)
      assert.equal(editor.data.block.lineHeight, value)
      assert.equal(editor.data.hasInvalidDraft, true)
      assert.equal(editor.data.lineHeightEditor.decreaseDisabled, true)
      assert.equal(editor.data.lineHeightEditor.increaseDisabled, true)
      assert.equal(editor.data.undoCount, 0)
      editor.handleConfirm()
      assert.equal(editor.events.length, 0)
      assert.match(editor.data.error, /行间距/)
      editor.handleLineHeightStep(event({ delta: 0.1 }))
      assert.equal(editor.data.block.lineHeight, value)
      editor.handleUndo()
      assert.equal(Object.hasOwn(editor.data.block, 'lineHeight'), false)
      assert.equal(editor.data.hasInvalidDraft, false)
    }
    editor.handleLineHeightInput(event({}, '1.7'))
    editor.handleLineHeightInput(event({}, ''))
    editor.handleUndo(); assert.equal(editor.data.block.lineHeight, 1.7)
    editor.handleUndo(); assert.equal(Object.hasOwn(editor.data.block, 'lineHeight'), false)
    editor.handleLineHeightInput(event({}, '0.5')); editor.handleConfirm()
    assert.equal(editor.events.at(-1).detail.cells[0].blocks[0].lineHeight, 0.5)
    const snapshot = clone(editor.data.draft)
    editor.handleLineHeightInput(event({}, '2')); editor.handleLineHeightStep(event({ delta: 0.1 }))
    assert.deepEqual(editor.data.draft, snapshot)
    definition.observers.visible.call(editor, true)
    editor.handleLineHeightInput(event({}, '2')); editor.handleLineHeightStep(event({ delta: 0.1 }))
    assert.equal(Object.hasOwn(editor.data.block, 'lineHeight'), false, 'hidden content tab ignores delayed events')
  })
  test(`${scope}: line height rendering is identical in measurement, visible cells and natural fallback`, () => {
    const config = gridApi.createTextGrid(1, 1)
    config.cells[0].blocks[0].lineHeight = 0.5
    config.cells[0].blocks[0].runs = [{ ...gridApi.createRun(), text: '10\\n20', fontSizeRpx: 64 }, { ...gridApi.createRun(), text: '年', fontSizeRpx: 24 }]
    let measureCount = 0, failMeasurement = false
    const { instance: renderer, definition } = component(scope, 'text-grid', { config })
    renderer._alive = true
    renderer.createSelectorQuery = () => {
      let many = false, callback
      return { select() { return this }, selectAll() { many = true; return this }, boundingClientRect(fn) { callback = fn; return this }, exec() {
        if (!many) { callback({ width: 375 }); return }
        measureCount++
        const block = renderer.data.measuringCells[0].blocks[0]
        const multiplier = renderer.properties.config.cells[0].blocks[0].lineHeight
        assert.match(block.lineStyle, new RegExp(`line-height: ${multiplier};`))
        assert.ok(block.runs.every(run => run.style.includes(`line-height: ${multiplier};`)))
        callback(failMeasurement ? [] : [{ height: 120 * multiplier }])
      } }
    }
    renderer.scheduleLayout()
    assert.equal(measureCount, 1)
    const oldHeight = renderer.data.height
    renderer.properties.config = clone(config); renderer.properties.config.cells[0].blocks[0].lineHeight = 3
    definition.observers['config, themeMode'].call(renderer)
    assert.equal(measureCount, 2)
    assert.ok(renderer.data.height > oldHeight)
    assert.match(renderer.data.cells[0].blocks[0].lineStyle, /line-height: 3;/)
    const visible = clone(renderer.data.cells[0].blocks)
    failMeasurement = true; renderer.scheduleLayout()
    assert.equal(renderer.data.natural, true)
    assert.deepEqual(clone(renderer.data.cells[0].blocks), visible)
    const wxml = fs.readFileSync(path.join(__dirname, '../pages', scope, 'components/text-grid/text-grid.wxml'), 'utf8')
    assert.match(wxml, /class="text-grid-line" style="\{\{block.lineStyle\}\}"/)
    assert.equal((wxml.match(/<template is="copy"/g) || []).length, 2)
    const editorWxml = fs.readFileSync(path.join(__dirname, '../pages', scope, 'components/text-grid-editor/text-grid-editor.wxml'), 'utf8')
    assert.match(editorWxml, /type="digit"[^>]*bindinput="handleLineHeightInput"/)
    assert.match(editorWxml, /disabled="\{\{!undoCount && !hasInvalidDraft\}\}"/)
  })
  test(`${scope}: outer spacing edits validate, undo and reopen without changing the source config`, () => {
    const config = gridApi.createTextGrid(), original = clone(config)
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config })
    definition.observers.visible.call(editor, true)
    editor.handleTab(event({ value: 'appearance' }))
    editor.handleAppearance(event({ field: 'horizontalMarginRpx' }, '32'))
    editor.handleAppearance(event({ field: 'verticalMarginRpx' }, '24'))
    assert.equal(editor.data.error, '')
    assert.equal(editor.data.draft.horizontalMarginRpx, 32); assert.equal(editor.data.draft.verticalMarginRpx, 24)
    const undoCount = editor.data.undoCount
    editor.handleAppearance(event({ field: 'horizontalMarginRpx' }, '97'))
    assert.equal(editor.data.draft.horizontalMarginRpx, 32); assert.equal(editor.data.undoCount, undoCount)
    assert.match(editor.data.error, /外侧留白.*未应用/)
    editor.handleUndo(); assert.equal(editor.data.draft.verticalMarginRpx, 0)
    editor.handleAppearance(event({ field: 'verticalMarginRpx' }, '24'))
    editor.handleTab(event({ value: 'content' })); editor.handleField(event({ field: 'text' }, '留白内容'))
    editor.handleConfirm()
    const saved = editor.events.at(-1)
    assert.equal(saved.name, 'confirm')
    assert.equal(saved.detail.horizontalMarginRpx, 32); assert.equal(saved.detail.verticalMarginRpx, 24)
    editor.properties.config = saved.detail; definition.observers.visible.call(editor, true)
    assert.equal(editor.data.draft.horizontalMarginRpx, 32); assert.equal(editor.data.draft.verticalMarginRpx, 24)
    editor.handleCancel(); assert.deepEqual(config, original)
  })
  test(`${scope}: narrow columns are checked against the published width after outer spacing`, () => {
    const config = gridApi.createTextGrid(2, 5)
    config.columnWeights = [1, 4, 4, 4, 1]
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config })
    definition.observers.visible.call(editor, true)
    editor.handleAppearance(event({ field: 'horizontalMarginRpx' }, '96'))
    assert.match(editor.data.error, /宽度不足.*未应用/)
    assert.equal(editor.data.draft.horizontalMarginRpx, 0); assert.equal(editor.data.undoCount, 0)
    editor.handleField(event({ field: 'text' }, '有效文字'))
    editor.data.draft.horizontalMarginRpx = 96
    editor.handleConfirm(); assert.equal(editor.events.length, 0)
    assert.match(editor.data.error, /宽度不足/)
  })
  test(`${scope}: renderer applies spacing before measuring inner width and reflows without double deduction`, () => {
    const config = gridApi.createTextGrid()
    config.horizontalMarginRpx = 32; config.verticalMarginRpx = 24
    const { instance: renderer } = component(scope, 'text-grid', { config })
    renderer._alive = true
    renderer.createSelectorQuery = () => {
      let many = false, callback
      return { select() { return this }, selectAll() { many = true; return this }, boundingClientRect(fn) { callback = fn; return this }, exec() {
        assert.equal(renderer.data.spacingStyle, `padding:${renderer.properties.config.verticalMarginRpx}rpx ${renderer.properties.config.horizontalMarginRpx}rpx;`)
        if (many) callback(config.cells.map(() => ({ height: 40 })))
        else callback({ width: 375 - renderer.properties.config.horizontalMarginRpx })
      } }
    }
    renderer.scheduleLayout()
    assert.equal(renderer._lastLayout.width, 343)
    assert.equal(renderer.data.cells[0].width, 167.5)
    renderer.properties.config = { ...config, horizontalMarginRpx: 96 }
    renderer.scheduleLayout()
    assert.equal(renderer._lastLayout.width, 279)
    assert.equal(renderer.data.cells[0].width, 135.5)
    const wxml = fs.readFileSync(path.join(__dirname, '../pages', scope, 'components/text-grid/text-grid.wxml'), 'utf8')
    assert.match(wxml, /class="text-grid-spacing" style="\{\{spacingStyle\}\}"/)
  })
  test(`${scope}: eight-row editing confirms and reopens the last cell while rejecting overflow`, () => {
    const original = gridApi.createTextGrid()
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config: original })
    definition.observers.visible.call(editor, true)
    editor.handleResize(event({ field: 'rows' }, '8'))
    editor.handleResize(event({ field: 'columns' }, '5'))
    assert.equal(editor.data.error, '')
    assert.equal(editor.data.cells.length, 40)
    assert.equal(editor.data.cells.at(-1).label, '8行 5列')
    editor.handleTab(event({ value: 'content' }))
    editor.handleCell(event({ index: 39 }))
    editor.handleField(event({ field: 'text' }, '第八行第五列'))
    const snapshot = clone(editor.data.draft), undoCount = editor.data.undoCount
    for (const [field, value] of [['rows', '9'], ['columns', '6']]) {
      editor.handleResize(event({ field }, value))
      assert.deepEqual(editor.data.draft, snapshot)
      assert.equal(editor.data.undoCount, undoCount)
      assert.match(editor.data.error, /行数须为 1–8，列数须为 1–5，此次调整未应用/)
    }
    editor.handleConfirm()
    assert.equal(editor.events.at(-1).name, 'confirm')
    assert.deepEqual(editor.events.at(-1).detail, snapshot)
    editor.properties.config = editor.events.at(-1).detail
    definition.observers.visible.call(editor, true)
    assert.equal(editor.data.draft.rows, 8)
    assert.equal(editor.data.draft.cells[39].blocks[0].runs[0].text, '第八行第五列')
    assert.equal(original.rows, 2)
    const wxml = fs.readFileSync(path.join(__dirname, '../pages', scope, 'components/text-grid-editor/text-grid-editor.wxml'), 'utf8')
    assert.match(wxml, /最多 8 行、5 列/)
  })
  test(`${scope}: border controls collapse together and custom color remains in the same panel`, () => {
    const base = path.join(__dirname, '../pages', scope, 'components/text-grid-editor/text-grid-editor')
    const wxml = fs.readFileSync(base + '.wxml', 'utf8'), css = fs.readFileSync(base + '.wxss', 'utf8')
    const settings = wxml.slice(wxml.indexOf('<view class="grid-border-settings'), wxml.indexOf('<view class="grid-label">格子底色'))
    assert.match(settings, /draft\.cellBorder \? 'is-visible' : ''/)
    assert.match(settings, /aria-hidden="\{\{!draft.cellBorder\}\}"/)
    assert.match(settings, /value="\{\{draft.cellBorderWidthRpx\}\}"[^>]*bindblur="handleBorderWidth"[^>]*disabled="\{\{!draft.cellBorder\}\}"/)
    assert.match(settings, /<slider[^>]*min="1" max="12" step="1"[^>]*bindchange="handleBorderWidth"/)
    assert.match(settings, /<text-color-editor inline="\{\{true\}\}" palette="border"[^>]*active="\{\{visible && tab === 'appearance' && draft.cellBorder\}\}"[^>]*bindchange="handleBorderColor"/)
    assert.doesNotMatch(settings, /root-portal|wx:if/)
    assert.equal(styleFor(css, '.grid-border-settings')['max-height'], '0')
    assert.equal(styleFor(css, '.grid-border-settings')['pointer-events'], 'none')
    assert.equal(styleFor(css, '.grid-mask.is-visible .grid-border-settings.is-visible')['pointer-events'], 'auto')
  })
  for (const name of ['text-grid-editor', 'contact-info-editor']) for (const action of ['cancel', 'confirm']) {
    test(`${scope}: ${name} releases expanded border hit areas after ${action} and restores them on reopen`, () => {
      const grid = name === 'text-grid-editor'
      const borderField = grid ? 'cellBorder' : 'contactBorder'
      const config = grid ? gridApi.createTextGrid(1, 1) : { contactWechat: 'example-wechat' }
      config[borderField] = true
      if (grid) config.cells[0].blocks[0].runs[0].text = '边框测试'
      const { instance: editor, definition } = component(scope, name, { visible: true, config })
      const css = fs.readFileSync(path.join(__dirname, '../pages', scope, 'components/text-grid-editor/text-grid-editor.wxss'), 'utf8')
      const collapsed = styleFor(css, '.grid-border-settings')
      const expanded = { ...collapsed, ...styleFor(css, '.grid-border-settings.is-visible') }
      const interactive = { ...expanded, ...styleFor(css, '.grid-mask.is-visible .grid-border-settings.is-visible') }
      const open = () => {
        editor.properties.visible = true
        editor.data.visible = true
        definition.observers.visible.call(editor, true)
        if (grid) editor.handleTab(event({ value: 'appearance' }))
      }
      open()
      assert.equal(interactive['pointer-events'], 'auto')
      assert.equal(collapsed['pointer-events'], 'none')
      editor.handleScroll({ detail: { scrollTop: 360 } })
      editor[action === 'cancel' ? 'handleCancel' : 'handleConfirm']()
      assert.equal(editor.events.at(-1).name, action)
      editor.properties.visible = false
      editor.data.visible = false
      definition.observers.visible.call(editor, false)
      // 关闭时草稿和展开布局仍保留，透明区域必须独立释放点击，不能依赖清空配置。
      assert.equal(editor.data.draft[borderField], true)
      if (grid) assert.equal(editor.data.tab, 'appearance')
      assert.equal(styleFor(css, '.grid-mask')['pointer-events'], 'none')
      assert.equal(expanded['pointer-events'], 'none', 'closed expanded settings must not override the mask')
      open()
      assert.equal(editor.data.draft[borderField], true)
      assert.equal(editor.data.scrollTop, 0)
      assert.equal(interactive['pointer-events'], 'auto')
    })
  }
  test(`${scope}: border edits validate, survive toggles and roundtrip confirmation without altering the original`, () => {
    const original = gridApi.createTextGrid(1, 1)
    original.cells[0].blocks[0].runs[0].text = '线框'
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config: original })
    definition.observers.visible.call(editor, true)
    editor.handleTab(event({ value: 'appearance' }))
    editor.handleAppearance(event({ field: 'cellBorder' }, true))
    editor.handleBorderWidth(event({}, 6)); editor.handleBorderColor({ detail: { color: '#CC3366' } })
    assert.equal(editor.data.draft.cellBorderWidthRpx, 6); assert.equal(editor.data.borderColor, '#CC3366')
    editor.handleBorderWidth(event({}, 13))
    assert.equal(editor.data.draft.cellBorderWidthRpx, 6); assert.match(editor.data.error, /未应用/)
    editor.handleAppearance(event({ field: 'cellBorder' }, false))
    editor.handleBorderWidth(event({}, 8)); editor.handleBorderColor({ detail: { color: '#FFFFFF' } })
    assert.equal(editor.data.draft.cellBorderWidthRpx, 6); assert.equal(editor.data.draft.cellBorderColor, '#CC3366')
    assert.match(gridApi.presentTextGrid(editor.data.draft, gridApi.layoutTextGrid(editor.data.draft, 375, 0.5))[0].style, /border:0rpx/)
    editor.handleAppearance(event({ field: 'cellBorder' }, true))
    assert.equal(editor.data.draft.cellBorderWidthRpx, 6); assert.equal(editor.data.borderColor, '#CC3366')
    editor.handleTab(event({ value: 'content' }))
    editor.handleBorderColor({ detail: { color: '#FFFFFF' } })
    assert.equal(editor.data.draft.cellBorderColor, '#CC3366')
    editor.handleConfirm()
    const saved = editor.events.at(-1).detail
    assert.equal(saved.cellBorder, true); assert.equal(saved.cellBorderWidthRpx, 6); assert.equal(saved.cellBorderColor, '#CC3366')
    assert.equal(original.cellBorder, false); assert.equal(original.cellBorderWidthRpx, 1)
    editor.properties.config = saved; definition.observers.visible.call(editor, true)
    assert.equal(editor.data.draft.cellBorderWidthRpx, 6); assert.equal(editor.data.borderColor, '#CC3366')
    editor.handleCancel()
    assert.equal(saved.cellBorderWidthRpx, 6)
  })
  test(`${scope}: old theme borders show their effective color without rewriting their stored value`, () => {
    const original = gridApi.createTextGrid(1, 1)
    delete original.cellBorderWidthRpx; delete original.cellBorderColor
    original.cellBorder = true; original.cellBackground = '#000000'
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config: original })
    definition.observers.visible.call(editor, true)
    assert.equal(editor.data.draft.cellBorderWidthRpx, 1)
    assert.equal(editor.data.draft.cellBorderColor, 'AUTO')
    assert.equal(editor.data.borderColor, '#45464A')
    editor.handleBackground({ detail: { color: '#FFFFFF' } })
    assert.equal(editor.data.borderColor, '#D7DADD'); assert.equal(editor.data.draft.cellBorderColor, 'AUTO')
  })
  test(`${scope}: every unified color survives text, background and border editing`, () => {
    for (const color of ['#000000', '#FFFFFF', '#CED4DA', '#ADB5BD', '#F5F6F8', '#123456']) {
      const original = gridApi.createTextGrid(1, 1), snapshot = clone(original)
      const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config: original })
      definition.observers.visible.call(editor, true)
      editor.handleTab(event({ value: 'content' })); editor.handleField(event({ field: 'text' }, '颜色'))
      editor.handleRunColor({ detail: { color } })
      editor.handleTab(event({ value: 'appearance' })); editor.handleAppearance(event({ field: 'cellBorder' }, true))
      editor.handleBorderColor({ detail: { color } }); editor.handleBackground({ detail: { color } })
      editor.handleConfirm()
      const saved = editor.events.at(-1)
      assert.equal(saved.name, 'confirm')
      assert.equal(saved.detail.cells[0].blocks[0].runs[0].color, color)
      assert.equal(saved.detail.cellBackground, color); assert.equal(saved.detail.cellBorderColor, color)
      assert.deepEqual(original, snapshot)
      editor.properties.config = saved.detail; definition.observers.visible.call(editor, true)
      assert.equal(editor.data.run.color, color); assert.equal(editor.data.borderColor, color)
      assert.equal(editor.data.draft.cellBackground, color)
    }
  })
  test(`${scope}: opening and cancelling preserves legacy text, background and border colors`, () => {
    for (const color of ['AUTO', '#212529', '#D7DADD']) {
      const original = gridApi.createTextGrid(1, 1)
      original.cells[0].blocks[0].runs[0].color = color
      original.cellBackground = color; original.cellBorderColor = color
      const snapshot = clone(original)
      const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config: original })
      definition.observers.visible.call(editor, true)
      assert.equal(editor.data.run.color, color); assert.equal(editor.data.draft.cellBackground, color)
      assert.equal(editor.data.draft.cellBorderColor, color); assert.equal(editor.data.undoCount, 0)
      editor.handleCancel()
      assert.deepEqual(original, snapshot)
      assert.deepEqual(editor.events, [{ name: 'cancel', detail: {} }])
    }
  })
  test(`${scope}: entering content selects only the focused cell without changing content or undo history`, () => {
    for (const intermediateTab of [null, 'appearance']) {
      const config = gridApi.createTextGrid()
      config.cells.forEach((cell, index) => { cell.blocks[0].runs[0].text = `单元格 ${index}` })
      const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config })
      definition.observers.visible.call(editor, true)
      editor.handleCell(event({ index: 0 })); editor.handleCell(event({ index: 2 }))
      assert.equal(editor.data.selectedKeys.length, 2)
      const draft = clone(editor.data.draft), undoCount = editor.data.undoCount
      if (intermediateTab) editor.handleTab(event({ value: intermediateTab }))
      editor.handleTab(event({ value: 'content' }))
      assert.deepEqual(clone(editor.data.selectedKeys), [draft.cells[2].cellKey])
      assert.deepEqual(clone(editor.data.cells.filter(cell => cell.selected).map(cell => cell.index)), [2])
      assert.equal(editor.data.cellIndex, 2); assert.equal(editor.data.run.text, '单元格 2')
      assert.deepEqual(editor.data.draft, draft); assert.equal(editor.data.undoCount, undoCount)
      editor.handleField(event({ field: 'text' }, '仅修改当前格'))
      assert.equal(editor.data.draft.cells[2].blocks[0].runs[0].text, '仅修改当前格')
      assert.equal(editor.data.draft.cells[0].blocks[0].runs[0].text, '单元格 0')
      editor.handleTab(event({ value: 'layout' }))
      editor.handleCell(event({ index: 0 }))
      assert.equal(editor.data.selectedKeys.length, 2, 'layout still supports selecting multiple cells')
    }
  })
  test(`${scope}: changing tabs and reopening return a scrolled panel to the beginning`, () => {
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config: gridApi.createTextGrid() })
    let nativeScrollTop = 0
    const originalSetData = editor.setData
    editor.setData = function (values, callback) {
      // 模拟原生视图忽略未变化的属性：data 仍为 0 时单写 0 不能清除用户滚动。
      if (Object.hasOwn(values, 'scrollTop') && values.scrollTop !== this.data.scrollTop) nativeScrollTop = values.scrollTop
      originalSetData.call(this, values, callback)
    }
    const scrollTo = value => { nativeScrollTop = value; editor.handleScroll({ detail: { scrollTop: value } }) }
    definition.observers.visible.call(editor, true)
    scrollTo(248)
    editor.handleTab(event({ value: 'layout' }))
    assert.equal(nativeScrollTop, 248, 'clicking the current tab keeps the reading position')
    editor.handleTab(event({ value: 'content' }))
    assert.equal(editor.data.tab, 'content'); assert.equal(nativeScrollTop, 0)
    scrollTo(482)
    editor.handleTab(event({ value: 'appearance' }))
    assert.equal(nativeScrollTop, 0)
    scrollTo(320)
    editor.properties.visible = false; definition.observers.visible.call(editor, false)
    editor.properties.visible = true; definition.observers.visible.call(editor, true)
    assert.equal(editor.data.tab, 'layout'); assert.equal(nativeScrollTop, 0); assert.equal(editor.data.scrollTop, 0)
  })
  test(`${scope}: editor buttons declare their touch geometry after the global reset`, () => {
    const folder = path.join(__dirname, '../pages', scope, 'components')
    const wxml = fs.readFileSync(path.join(folder, 'text-grid-editor/text-grid-editor.wxml'), 'utf8')
    const wxss = fs.readFileSync(path.join(folder, 'text-grid-editor/text-grid-editor.wxss'), 'utf8')
    const controls = buttonClasses(wxml)
    assert.ok(controls.length > 4); assert.ok(controls.every(classes => classes.includes('grid-button')))
    const geometry = styleFor(wxss, '.grid-button')
    assert.equal(geometry.display, 'flex'); assert.equal(geometry['align-items'], 'center'); assert.equal(geometry['justify-content'], 'center')
    assert.ok(parseFloat(geometry['min-height']) >= 80); assert.match(geometry['min-height'], /rpx$/)
    assert.ok(parseFloat(geometry.padding) >= 20); assert.ok(parseFloat(geometry['line-height']) >= 1.5)
    assert.equal(geometry.width, 'auto'); assert.equal(parseFloat(geometry['min-width']), 0)
    for (const name of ['text-grid-editor', 'contact-info-editor']) {
      const markup = fs.readFileSync(path.join(folder, name, `${name}.wxml`), 'utf8')
      const footer = markup.slice(markup.lastIndexOf('class="grid-actions"'))
      const buttons = buttonClasses(footer)
      assert.equal(buttons.length, 2)
      assert.ok(buttons.every(classes => classes.includes('grid-button') && classes.includes('grid-action-button')), `${name}: shared footer geometry`)
    }
  })
  test(`${scope}: fixed header, tabs and actions surround the only scrollable editor body`, () => {
    const folder = path.join(__dirname, '../pages', scope, 'components/text-grid-editor')
    const wxml = fs.readFileSync(path.join(folder, 'text-grid-editor.wxml'), 'utf8')
    const wxss = fs.readFileSync(path.join(folder, 'text-grid-editor.wxss'), 'utf8')
    const scrollStart = wxml.indexOf('<scroll-view'), scrollEnd = wxml.indexOf('</scroll-view>')
    assert.ok(wxml.indexOf('class="grid-header"') < scrollStart)
    assert.ok(wxml.indexOf('class="grid-tabs"') < scrollStart)
    assert.ok(wxml.lastIndexOf('class="grid-actions"') > scrollEnd)
    const scrollTag = wxml.slice(scrollStart, wxml.indexOf('>', scrollStart))
    assert.match(scrollTag, /scroll-top="\{\{scrollTop\}\}"/); assert.match(scrollTag, /bindscroll="handleScroll"/)
    assert.match(wxml, /class="grid-tab(?:\s|\{)/)
    for (const selector of ['.grid-header', '.grid-tabs', '.grid-actions']) assert.equal(styleFor(wxss, selector)['flex-shrink'], '0', selector)
    const scrollStyle = styleFor(wxss, '.grid-scroll')
    assert.equal(scrollStyle.flex, '1'); assert.equal(parseFloat(scrollStyle['min-height']), 0); assert.equal(parseFloat(scrollStyle.height), 0)
    const panel = styleFor(wxss, '.grid-panel')
    assert.equal(panel.display, 'flex'); assert.equal(panel['flex-direction'], 'column')
  })
  test(`${scope}: merge, split and undo share one equal-width row that can shrink on narrow screens`, () => {
    const folder = path.join(__dirname, '../pages', scope, 'components/text-grid-editor')
    const wxml = fs.readFileSync(path.join(folder, 'text-grid-editor.wxml'), 'utf8')
    const wxss = fs.readFileSync(path.join(folder, 'text-grid-editor.wxss'), 'utf8')
    const rows = Array.from(wxml.matchAll(/<view\b[^>]*class="([^"]*\bgrid-layout-actions\b[^"]*)"[^>]*>([\s\S]*?)<\/view>/g))
    assert.equal(rows.length, 1)
    assert.ok(rows[0][1].split(/\s+/).includes('grid-options'))
    const buttons = Array.from(rows[0][2].matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/g))
    assert.equal(buttons.length, 3)
    assert.deepEqual(buttons.map(button => button[1].match(/\bbindtap="([^"]+)"/)[1]), ['handleMerge', 'handleSplit', 'handleUndo'])
    assert.deepEqual(buttons.map(button => button[2].replace(/\{\{[^}]*\}\}/g, '').trim()), ['合并', '拆分当前格', '撤销'])
    assert.ok(buttonClasses(rows[0][2]).every(classes => classes.includes('grid-button')))
    const rowStyle = { ...styleFor(wxss, '.grid-options'), ...styleFor(wxss, '.grid-layout-actions') }
    assert.equal(rowStyle.display, 'flex'); assert.equal(rowStyle['flex-wrap'], 'nowrap')
    const buttonStyle = { ...styleFor(wxss, '.grid-button'), ...styleFor(wxss, '.grid-layout-actions .grid-button') }
    assert.equal(buttonStyle.flex, '1', 'all three buttons divide the available row width equally')
    assert.equal(buttonStyle.width, '0'); assert.equal(buttonStyle['min-width'], '0')
    assert.equal(buttonStyle['white-space'], 'nowrap')
    assert.equal(buttonStyle.padding, '20rpx 8rpx', 'compact horizontal padding leaves space for the longest label')
  })
  test(`${scope}: split is available only for one selected merged cell and ignored after deselection`, () => {
    const source = gridApi.createTextGrid()
    const config = gridApi.mergeCells(source, source.cells.slice(0, 2).map(cell => cell.cellKey))
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config })
    definition.observers.visible.call(editor, true)
    const expectSplitIgnored = () => {
      const draft = clone(editor.data.draft), undoCount = editor.data.undoCount
      editor.handleSplit()
      assert.deepEqual(editor.data.draft, draft); assert.equal(editor.data.undoCount, undoCount)
    }
    assert.equal(editor.data.canSplitCell, false, 'opening a merged grid does not select the focused cell')
    expectSplitIgnored()
    editor.handleCell(event({ index: 0 })); assert.equal(editor.data.canSplitCell, true)
    editor.handleCell(event({ index: 0 })); assert.equal(editor.data.canSplitCell, false)
    assert.equal(editor.data.selectedKeys.length, 0); expectSplitIgnored()
    editor.handleCell(event({ index: 1 })); assert.equal(editor.data.canSplitCell, false)
    expectSplitIgnored()
    editor.handleCell(event({ index: 0 })); assert.equal(editor.data.canSplitCell, false)
    assert.equal(editor.data.selectedKeys.length, 2); expectSplitIgnored()
    const wxml = fs.readFileSync(path.join(__dirname, '../pages', scope, 'components/text-grid-editor/text-grid-editor.wxml'), 'utf8')
    const splitButton = Array.from(wxml.matchAll(/<button\b[^>]*>/g)).find(match => /bindtap="handleSplit"/.test(match[0]))
    assert.ok(splitButton); assert.match(splitButton[0], /disabled="\{\{!canSplitCell\}\}"/)
  })
  test(`${scope}: split follows the sole selected merged cell even when focus remains on a deselected ordinary cell`, () => {
    const source = gridApi.createTextGrid()
    const config = gridApi.mergeCells(source, source.cells.slice(0, 2).map(cell => cell.cellKey))
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config })
    definition.observers.visible.call(editor, true)
    const mergedKey = editor.data.draft.cells[0].cellKey, ordinary = clone(editor.data.draft.cells[1])
    editor.handleCell(event({ index: 0 })); editor.handleCell(event({ index: 1 }))
    assert.equal(editor.data.canSplitCell, false)
    editor.handleCell(event({ index: 1 }))
    assert.equal(editor.data.cellIndex, 1); assert.equal(editor.data.cell.cellKey, ordinary.cellKey)
    assert.deepEqual(clone(editor.data.selectedKeys), [mergedKey]); assert.equal(editor.data.canSplitCell, true)
    editor.handleSplit()
    assert.equal(editor.data.draft.cells.length, 4)
    assert.ok(editor.data.draft.cells.every(cell => cell.rowSpan === 1 && cell.columnSpan === 1))
    assert.deepEqual(editor.data.draft.cells.find(cell => cell.cellKey === ordinary.cellKey), ordinary)
    assert.equal(editor.data.undoCount, 1); assert.equal(editor.data.canSplitCell, false)
  })
  test(`${scope}: merge, split and undo recalculate split availability from the surviving selection`, () => {
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config: gridApi.createTextGrid() })
    definition.observers.visible.call(editor, true)
    editor.handleCell(event({ index: 0 })); editor.handleCell(event({ index: 1 }))
    assert.equal(editor.data.canSplitCell, false)
    editor.handleMerge()
    assert.equal(editor.data.draft.cells.length, 3); assert.equal(editor.data.selectedKeys.length, 1)
    assert.equal(editor.data.canSplitCell, true)
    editor.handleSplit()
    assert.equal(editor.data.draft.cells.length, 4); assert.equal(editor.data.canSplitCell, false)
    editor.handleUndo()
    assert.equal(editor.data.draft.cells.length, 3); assert.equal(editor.data.canSplitCell, true)
    editor.handleUndo()
    assert.equal(editor.data.draft.cells.length, 4); assert.equal(editor.data.canSplitCell, false)
  })
  test(`${scope}: undo respects cleared selection and a closed panel cannot split a selected merged cell`, () => {
    const source = gridApi.createTextGrid()
    const config = gridApi.mergeCells(source, source.cells.slice(0, 2).map(cell => cell.cellKey))
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config })
    definition.observers.visible.call(editor, true)
    editor.handleCell(event({ index: 0 })); editor.handleSplit()
    editor.handleCell(event({ index: 0 })); assert.equal(editor.data.selectedKeys.length, 0)
    editor.handleUndo()
    assert.equal(editor.data.draft.cells.length, 3); assert.equal(editor.data.selectedKeys.length, 0)
    assert.equal(editor.data.canSplitCell, false, 'undo restores geometry without restoring a cleared selection')
    editor.handleCell(event({ index: 0 })); assert.equal(editor.data.canSplitCell, true)
    const draft = clone(editor.data.draft), undoCount = editor.data.undoCount
    // visible 属性先变化、关闭 observer 尚未清历史时也不能响应迟到的拆分事件。
    editor.properties.visible = false; editor.handleSplit()
    assert.deepEqual(editor.data.draft, draft); assert.equal(editor.data.undoCount, undoCount)
    definition.observers.visible.call(editor, false); editor.handleSplit()
    assert.deepEqual(editor.data.draft, draft); assert.equal(editor.data.undoCount, 0)
    editor.properties.visible = true; definition.observers.visible.call(editor, true)
    assert.equal(editor.data.selectedKeys.length, 0); assert.equal(editor.data.canSplitCell, false)
  })
  test(`${scope}: blank content shows style controls and mixed fragments survive confirm without changing source`, () => {
    const config = gridApi.createTextGrid(), original = clone(config)
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config })
    definition.observers.visible.call(editor, true)
    assert.equal(editor.data.run.fontSizeRpx, 28); assert.equal(editor.data.run.fontWeight, 'NORMAL')
    editor.handleTab(event({ value: 'content' })); editor.handleField(event({ field: 'text' }, '10'))
    editor.handleField(event({ field: 'fontSizeRpx' }, '64')); editor.handleField(event({ field: 'fontWeight', value: 'BOLD' }))
    editor.handleAddRun(); editor.handleField(event({ field: 'text' }, '年')); editor.handleField(event({ field: 'fontSizeRpx' }, '24'))
    editor.handleRunColor({ detail: { color: '#AABBCC' } }); editor.handleConfirm()
    const saved = editor.events.at(-1)
    assert.equal(saved.name, 'confirm'); const runs = saved.detail.cells[0].blocks[0].runs
    assert.deepEqual(runs.map(run => [run.text, run.fontSizeRpx, run.fontWeight, run.color]), [['10', 64, 'BOLD', 'AUTO'], ['年', 24, 'NORMAL', '#AABBCC']])
    assert.deepEqual(config, original)
    editor.handleCancel(); assert.deepEqual(config, original)
  })
  test(`${scope}: font size steps by 1 within 10–96 and boundary values can be saved and reopened`, () => {
    const config = gridApi.createTextGrid(), original = clone(config)
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config })
    definition.observers.visible.call(editor, true)
    editor.handleTab(event({ value: 'content' })); editor.handleField(event({ field: 'text' }, '字号范围'))
    assert.equal(editor.data.rpxStep, 1)
    for (const [size, delta, expected] of [[10, -1, 10], [10, 1, 11], [95, 1, 96], [96, 1, 96], [96, -1, 95]]) {
      editor.handleField(event({ field: 'fontSizeRpx' }, String(size)))
      editor.handleRpxStep(event({ field: 'fontSizeRpx', delta }))
      assert.equal(editor.data.run.fontSizeRpx, expected)
      assert.equal(editor.data.run.fontWeight, 'NORMAL')
    }
    for (const size of [10, 96]) {
      editor.handleField(event({ field: 'fontSizeRpx' }, String(size)))
      editor.handleConfirm()
      const saved = editor.events.at(-1)
      assert.equal(saved.name, 'confirm')
      assert.equal(saved.detail.cells[0].blocks[0].runs[0].fontSizeRpx, size)
      editor.properties.config = saved.detail; definition.observers.visible.call(editor, true)
      assert.equal(editor.data.run.fontSizeRpx, size)
      editor.handleTab(event({ value: 'content' }))
    }
    assert.deepEqual(config, original)
  })
  test(`${scope}: column checks use published width and reject genuinely impossible widths atomically`, () => {
    const config = gridApi.createTextGrid(2, 5)
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config })
    definition.observers.visible.call(editor, true)
    editor._layoutWidth = { widthPx: 150, rpxScale: 0.5 }
    editor.handleArray(event({ field: 'columnWeights', index: 1 }, '4'))
    assert.equal(editor.data.error, ''); assert.equal(editor.data.draft.columnWeights[1], 4)
    editor.handleArray(event({ field: 'columnWeights', index: 2 }, '4'))
    editor.handleArray(event({ field: 'columnWeights', index: 3 }, '4'))
    const snapshot = clone(editor.data.draft), undoCount = editor.data.undoCount
    editor.handleArray(event({ field: 'columnWeights', index: 4 }, '4'))
    assert.match(editor.data.error, /宽度不足/); assert.deepEqual(editor.data.draft, snapshot); assert.equal(editor.data.undoCount, undoCount)
    assert.equal(editor.data.inputRenderKeys[0], 1)
  })
  test(`${scope}: invalid numeric controls repaint their saved values and do not change history`, () => {
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config: gridApi.createTextGrid() })
    definition.observers.visible.call(editor, true)
    for (const action of [() => editor.handleResize(event({ field: 'rows' }, '9')), () => editor.handleResize(event({ field: 'columns' }, '0')),
      () => editor.handleArray(event({ field: 'columnWeights', index: 0 }, '5')), () => editor.handleAppearance(event({ field: 'cellPaddingRpx' }, '-1')),
      ...['9', '97', '10.5'].map(value => () => editor.handleField(event({ field: 'fontSizeRpx' }, value)))]) {
      const before = clone(editor.data.draft), inputKey = editor.data.inputRenderKeys[0]
      action(); assert.deepEqual(editor.data.draft, before); assert.equal(editor.data.undoCount, 0)
      assert.equal(editor.data.inputRenderKeys[0], inputKey + 1); assert.match(editor.data.error, /未应用/)
    }
    const wxml = fs.readFileSync(path.join(__dirname, '../pages', scope, 'components/text-grid-editor/text-grid-editor.wxml'), 'utf8')
    assert.equal((wxml.match(/<input\b/g) || []).length, (wxml.match(/wx:for="\{\{inputRenderKeys\}\}"/g) || []).length)
  })
  test(`${scope}: overlong Unicode input remains visible and cannot confirm an older saved value`, () => {
    const config = gridApi.createTextGrid(), original = clone(config)
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config })
    definition.observers.visible.call(editor, true)
    editor.handleField(event({ field: 'text' }, '😀'.repeat(2000)))
    const undoCount = editor.data.undoCount
    editor.handleField(event({ field: 'text' }, '😀'.repeat(2001)))
    assert.equal(editor.data.run.text, '😀'.repeat(2001)); assert.equal(editor.data.count, 2001)
    assert.equal(editor.data.draft.cells[0].blocks[0].runs[0].text, editor.data.run.text)
    assert.equal(editor.data.undoCount, undoCount); assert.match(editor.data.error, /2000/)
    editor.handleConfirm(); assert.equal(editor.events.length, 0)
    editor.handleUndo(); assert.equal(editor.data.count, 2000); assert.equal(editor.data.run.text, '😀'.repeat(2000))
    editor.handleField(event({ field: 'text' }, '😀'.repeat(1999) + '好'))
    editor.handleConfirm(); assert.equal(editor.events.at(-1).name, 'confirm'); assert.equal(editor.data.undoCount, 0)
    assert.equal(Array.from(editor.events.at(-1).detail.cells[0].blocks[0].runs[0].text).length, 2000)
    assert.deepEqual(config, original)
  })
  test(`${scope}: unavailable fonts preserve the previous run and history`, () => {
    const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config: gridApi.createTextGrid() }, { isPortfolioFontAvailable: value => value === 'SYSTEM' })
    definition.observers.visible.call(editor, true)
    editor.handleField(event({ field: 'fontFamily', value: 'WECHAT_SANS_SS' }))
    assert.equal(editor.data.run.fontFamily, 'SYSTEM'); assert.equal(editor.data.undoCount, 0); assert.match(editor.data.error, /暂不支持此字体/)
  })
  test(`${scope}: confirm, cancel, closing and detaching clear undo history`, () => {
    for (const action of ['confirm', 'cancel', 'close', 'detach']) {
      const { instance: editor, definition } = component(scope, 'text-grid-editor', { visible: true, config: gridApi.createTextGrid() })
      definition.observers.visible.call(editor, true); editor.handleField(event({ field: 'text' }, '新文字'))
      const history = editor._history; assert.equal(history.size(), 1)
      if (action === 'confirm') editor.handleConfirm()
      if (action === 'cancel') editor.handleCancel()
      if (action === 'close') definition.observers.visible.call(editor, false)
      if (action === 'detach') definition.lifetimes.detached.call(editor)
      assert.equal(history.size(), 0); assert.equal(editor._history, null); assert.equal(editor.data.undoCount, 0)
      editor.handleField(event({ field: 'text' }, '😀'.repeat(2001))); assert.equal(editor._history, null)
      definition.observers.visible.call(editor, true)
      assert.equal(editor.data.undoCount, 0); assert.equal(editor.data.run.text, '')
    }
  })
  test(`${scope}: component measurement retries only twice then falls back to natural flow and recovers`, () => {
    const config = gridApi.createTextGrid(), { instance: renderer } = component(scope, 'text-grid', { config })
    let valid = false, measurements = 0
    renderer._alive = true
    renderer.createSelectorQuery = () => {
      let selected, callback
      return { select() { selected = 'width'; return this }, selectAll() { selected = 'height'; return this }, boundingClientRect(fn) { callback = fn; return this },
        exec() { if (selected === 'width') callback({ width: 350 }); else { measurements++; callback(valid ? config.cells.map(() => ({ height: 40 })) : []) } } }
    }
    renderer.scheduleLayout(); assert.equal(measurements, 2); assert.equal(renderer.data.natural, true)
    assert.equal(renderer.data.cells.length, 4)
    valid = true; renderer.scheduleLayout(); assert.equal(measurements, 3); assert.equal(renderer.data.natural, false)
    const geometryWrites = renderer.updates.filter(item => item.height).length
    renderer.scheduleLayout(); assert.equal(renderer.updates.filter(item => item.height).length, geometryWrites)
  })
  test(`${scope}: natural fallback keeps configured gap and spanned row minimum heights`, () => {
    const source = gridApi.createTextGrid(); source.rowMinHeightsRpx = [240, 360]; source.gapRpx = 32
    source.cellBorder = true; source.cellBorderWidthRpx = 8; source.cellBorderColor = '#123456'
    const config = gridApi.mergeCells(source, [source.cells[0].cellKey, source.cells[2].cellKey])
    const { instance: renderer } = component(scope, 'text-grid', { config })
    const cells = renderer.naturalCells(config)
    assert.ok(cells.every(cell => cell.style.includes('border:8rpx solid #123456;')))
    assert.match(cells[0].style, /min-height:632rpx;margin-bottom:32rpx;/)
    assert.match(cells[1].style, /min-height:240rpx;margin-bottom:32rpx;/)
    assert.match(cells[2].style, /min-height:360rpx;margin-bottom:0rpx;/)
    config.gapRpx = 0
    assert.ok(renderer.naturalCells(config).every(cell => cell.style.includes('margin-bottom:0rpx;')))
    const wxss = fs.readFileSync(path.join(__dirname, '../pages', scope, 'components/text-grid/text-grid.wxss'), 'utf8')
    assert.doesNotMatch(wxss, /gap:16rpx|min-height:80rpx/)
  })
  test(`${scope}: obsolete width callbacks never overwrite the current text`, () => {
    const config = gridApi.createTextGrid(), { instance: renderer } = component(scope, 'text-grid', { config })
    const widths = []; renderer._alive = true
    renderer.createSelectorQuery = () => { let many = false, callback; return { select() { return this }, selectAll() { many = true; return this }, boundingClientRect(fn) { callback = fn; return this }, exec() { if (many) callback(config.cells.map(() => ({ height: 20 }))); else widths.push(callback) } } }
    renderer.scheduleLayout(); renderer.properties.config = clone(config); renderer.properties.config.cells[0].blocks[0].runs[0].text = '最新内容'; renderer.scheduleLayout()
    widths[1]({ width: 350 }); widths[0]({ width: 300 })
    assert.equal(renderer.data.cells[0].blocks[0].runs[0].text, '最新内容'); assert.equal(renderer._lastLayout.width, 350)
  })
  test(`${scope}: contact refill preserves each edited field and still fills the untouched field`, async () => {
    for (const editedField of ['contactPhone', 'contactWechat']) {
      const config = { contactPhone: '13800000000', contactWechat: 'old' }, original = clone(config)
      const profile = { contactPhone: '13900000000', contactWechat: 'remote' }
      let resolve
      const { instance: editor, definition } = component(scope, 'contact-info-editor', { config, visible: true }, { loadContactProfile: () => new Promise(done => { resolve = done }) })
      definition.observers.visible.call(editor, true)
      const pending = editor.handleRefill()
      editor.handleInput(event({ field: editedField }, 'edited'))
      resolve(profile); await pending
      assert.deepEqual(clone(editor.data.draft), { ...profile, [editedField]: 'edited', ...contactDefaults })
      assert.equal(editor.data.loading, false); assert.deepEqual(config, original)
      const explicitRefill = editor.handleRefill(); resolve(profile); await explicitRefill
      assert.deepEqual(clone(editor.data.draft), { ...profile, ...contactDefaults }, 'explicit refill replaces edits made before that request')
      assert.deepEqual(config, original)
    }
  })
  test(`${scope}: contact refill preserves deliberate clears in both fields`, async () => {
    let resolve
    const { instance: editor, definition } = component(scope, 'contact-info-editor', { visible: true, config: { contactPhone: '13800000000', contactWechat: 'old' } }, { loadContactProfile: () => new Promise(done => { resolve = done }) })
    definition.observers.visible.call(editor, true); const pending = editor.handleRefill()
    editor.handleInput(event({ field: 'contactPhone' }, ''))
    editor.handleInput(event({ field: 'contactWechat' }, ''))
    resolve({ contactPhone: '13900000000', contactWechat: 'remote' }); await pending
    assert.deepEqual(clone(editor.data.draft), { contactPhone: '', contactWechat: '', ...contactDefaults })
    assert.equal(editor.data.loading, false)
  })
  test(`${scope}: only the latest contact refill can update fields, errors or loading`, async () => {
    const requests = []
    const config = { contactPhone: '13800000000', contactWechat: 'old' }
    const { instance: editor, definition } = component(scope, 'contact-info-editor', { visible: true, config }, { loadContactProfile: () => new Promise((resolve, reject) => requests.push({ resolve, reject })) })
    definition.observers.visible.call(editor, true)
    const older = editor.handleRefill(), newer = editor.handleRefill()
    requests[0].resolve({ contactPhone: '000', contactWechat: 'stale' }); await older
    assert.deepEqual(clone(editor.data.draft), { ...config, ...contactDefaults }); assert.equal(editor.data.loading, true)
    requests[1].resolve({ contactPhone: '13900000000', contactWechat: 'latest' }); await newer
    assert.equal(editor.data.draft.contactWechat, 'latest'); assert.equal(editor.data.loading, false)

    const staleFailure = editor.handleRefill(), latestFailure = editor.handleRefill()
    requests[2].reject(new Error('obsolete error')); await staleFailure
    assert.equal(editor.data.error, ''); assert.equal(editor.data.loading, true)
    requests[3].reject(new Error('current error')); await latestFailure
    assert.equal(editor.data.error, 'current error'); assert.equal(editor.data.loading, false)

    const staleSuccess = editor.handleRefill(), latestSuccess = editor.handleRefill()
    requests[5].resolve({ contactPhone: '13700000000', contactWechat: 'newest' }); await latestSuccess
    requests[4].resolve({ contactPhone: '000', contactWechat: 'late stale' }); await staleSuccess
    assert.equal(editor.data.draft.contactWechat, 'newest'); assert.equal(editor.data.loading, false)
    assert.deepEqual(config, { contactPhone: '13800000000', contactWechat: 'old' })
  })
  test(`${scope}: cancelled, closed and detached contact panels discard pending results across reopen`, async () => {
    for (const close of ['cancel', 'visible', 'detached']) {
      const requests = [], config = { contactPhone: '13800000000', contactWechat: 'old' }
      const { instance: editor, definition } = component(scope, 'contact-info-editor', { config, visible: true }, { loadContactProfile: () => new Promise(resolve => requests.push(resolve)) })
      definition.observers.visible.call(editor, true); const pending = editor.handleRefill()
      editor.handleInput(event({ field: 'contactWechat' }, 'edited'))
      if (close === 'cancel') editor.handleCancel()
      else if (close === 'visible') { editor.properties.visible = false; definition.observers.visible.call(editor, false) }
      else definition.lifetimes.detached.call(editor)
      const writesAfterClose = editor.updates.length
      requests[0]({ contactPhone: '000', contactWechat: 'late' }); await pending
      assert.equal(editor.updates.length, writesAfterClose, 'closed panels do not receive any asynchronous writes')
      assert.equal(editor.data.draft.contactWechat, 'edited'); assert.deepEqual(config, { contactPhone: '13800000000', contactWechat: 'old' })

      editor.properties.visible = true; definition.observers.visible.call(editor, true)
      const previousSession = editor.handleRefill()
      editor.properties.visible = false; definition.observers.visible.call(editor, false)
      editor.properties.visible = true; definition.observers.visible.call(editor, true)
      const currentSession = editor.handleRefill()
      requests[1]({ contactPhone: '000', contactWechat: 'obsolete session' }); await previousSession
      assert.equal(editor.data.draft.contactWechat, 'old'); assert.equal(editor.data.loading, true)
      requests[2]({ contactPhone: '13900000000', contactWechat: 'current session' }); await currentSession
      assert.equal(editor.data.draft.contactWechat, 'current session'); assert.equal(editor.data.loading, false)
    }
  })
  test(`${scope}: contact viewer copies the requested field only, with success and failure feedback`, () => {
    const copied = [], notices = []
    const { instance: viewer } = component(scope, 'contact-info', { config: { contactPhone: '13800000000', contactWechat: '微信号' } }, {}, {
      setClipboardData(options) { copied.push(options.data); copied.length === 1 ? options.success() : options.fail() }, showToast(options) { notices.push(options.title) } })
    viewer.handleCopy(event({ field: 'contactPhone' })); viewer.handleCopy(event({ field: 'contactWechat' })); viewer.handleCopy(event({ field: 'secret' }))
    assert.deepEqual(copied, ['13800000000', '微信号']); assert.deepEqual(notices, ['已复制', '复制失败，请重试'])
  })
}
test('contact snapshots enforce Unicode lengths, trim and control-character limits in both subpackages', () => {
  for (const scope of ['portfolios', 'team-portfolios']) {
    const api = require(`../pages/${scope}/utils/portfolio-contact-info`)
    assert.deepEqual(api.normalizeContactInfo({ contactPhone: ' 123 ', contactWechat: ' wx ', phoneNumber: 'not copied' }), { contactPhone: '123', contactWechat: 'wx', ...contactDefaults })
    assert.equal(api.validateContactInfo({ contactPhone: '', contactWechat: '😀'.repeat(64) }), '')
    assert.match(api.validateContactInfo({ contactPhone: '', contactWechat: '😀'.repeat(65) }), /64/)
    assert.match(api.validateContactInfo({ contactPhone: '123\n456', contactWechat: '' }), /换行/)
    assert.equal(api.validateContactInfo({ contactPhone: '', contactWechat: '' }), '')
    assert.match(api.validateContactInfo({ contactPhone: '', contactWechat: '' }, true), /至少/)
  }
})

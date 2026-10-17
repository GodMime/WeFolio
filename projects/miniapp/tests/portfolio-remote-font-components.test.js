const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const clone = value => JSON.parse(JSON.stringify(value))
const { PORTFOLIO_TEXT_SELECTION_OPTIONS } = require('../utils/portfolio-text-typography')
function component(pkg, name, config) {
  const filename = path.join(__dirname, `../pages/${pkg}/components/${name}/${name}.js`)
  let definition
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { Component(value) { definition = value }, require(id) { return require(path.resolve(path.dirname(filename), id)) }, setTimeout, clearTimeout, wx: { nextTick(fn) { fn() } } }, { filename })
  const properties = Object.fromEntries(Object.entries(definition.properties).map(([key, value]) => [key, clone(value.value)]))
  Object.assign(properties, { visible: true, config, fontEditSessionId: 23, remoteFontAvailable: true, fontOptions: PORTFOLIO_TEXT_SELECTION_OPTIONS.map(option => ({ ...option, available: option.value === 'ALLURA' || option.value === 'SYSTEM', previewImageUrl: option.value === 'ALLURA' ? 'https://sample.example/allura.png' : '' })) })
  const events = []
  const instance = { properties, data: clone(definition.data), ...definition.methods, triggerEvent(name, detail) { events.push({ name, detail: clone(detail || {}) }) },
    setData(patch, callback) {
      Object.assign(this.data, patch)
      if (name === 'text-grid-editor' && Object.hasOwn(patch, 'draft')) definition.observers.draft.call(this, patch.draft)
      if (name === 'structured-text-editor' && ['draft', 'blockDraft', 'detailClosing'].some(key => Object.hasOwn(patch, key))) definition.observers['draft, blockDraft, detailClosing'].call(this, this.data.draft, this.data.blockDraft, this.data.detailClosing)
      if (callback) callback()
    } }
  return { definition, instance, events }
}
for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg}: 结构化未确认区块通过候选事件进入 prepare，取消区块恢复原文`, () => {
    const sections = require(`../pages/${pkg}/utils/portfolio-text-sections`)
    const config = { blocks: [{ ...sections.createStructuredBlock('TITLE', 'title'), content: 'saved' }] }
    const { instance: editor, events } = component(pkg, 'structured-text-editor', config)
    editor.beginSession()
    editor.handleEditBlock({ currentTarget: { dataset: { key: 'title' } } })
    editor.handleBlockInput({ currentTarget: { dataset: { field: 'content' } }, detail: { value: 'unconfirmed text' } })
    editor.handleBlockOption({ currentTarget: { dataset: { field: 'fontFamily', value: 'ALLURA' } } })
    const selected = events.find(event => event.name === 'fontselect')
    assert.deepEqual(selected.detail, { fontId: 'ALLURA', sessionId: 23 })
    const candidate = events.filter(event => event.name === 'fontdraft').at(-1).detail
    assert.equal(candidate.config.blocks[0].content, 'unconfirmed text')
    assert.equal(candidate.config.blocks[0].fontId, 'ALLURA')
    assert.equal(candidate.sessionId, 23)
    assert.equal(config.blocks[0].content, 'saved')
    editor.handleCancelBlock()
    const cancelled = events.filter(event => event.name === 'fontdraft').at(-1).detail
    assert.equal(cancelled.config.blocks[0].content, 'saved')
    assert.equal(cancelled.config.blocks[0].fontId, undefined)
    editor.clearTransitions()
  })
  test(`${pkg}: 网格每次历史变化上报会话身份，撤销标识随该次候选传递`, () => {
    const grid = require(`../pages/${pkg}/utils/portfolio-text-grid`).createTextGrid(1, 1)
    grid.cells[0].blocks[0].runs[0].text = 'grid'
    const { definition, instance: editor, events } = component(pkg, 'text-grid-editor', grid)
    definition.observers.fontOptions.call(editor, editor.properties.fontOptions)
    definition.observers.visible.call(editor, true)
    editor.handleField({ currentTarget: { dataset: { field: 'fontFamily', value: 'ALLURA' } }, detail: {} })
    assert.equal(events.filter(event => event.name === 'fontdraft').at(-1).detail.config.cells[0].blocks[0].runs[0].fontId, 'ALLURA')
    editor.handleUndo()
    const undo = events.filter(event => event.name === 'fontdraft').at(-1).detail
    assert.equal(undo.operation, 'undo')
    assert.equal(undo.sessionId, 23)
    assert.equal(undo.config.cells[0].blocks[0].runs[0].fontId, undefined)
    editor.clearHistory()
  })
  for (const name of ['structured-text-editor', 'text-grid-editor']) {
    test(`${pkg}: ${name} 目录元数据按字体控制可选性，整体能力不启用缺失项`, () => {
      const { definition, instance: editor } = component(pkg, name, { blocks: [] })
      definition.observers.fontOptions.call(editor, editor.properties.fontOptions)
      definition.observers.remoteFontAvailable.call(editor, true)
      assert.equal(editor.data.fonts.find(font => font.value === 'ALLURA').available, true)
      assert.equal(editor.data.fonts.find(font => font.value === 'MANROPE').available, false)
      assert.equal(editor.data.fonts.find(font => font.value === 'ALLURA').previewImageUrl, 'https://sample.example/allura.png')
    })
  }
}

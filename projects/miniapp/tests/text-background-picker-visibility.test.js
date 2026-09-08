const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function createEditor(directory, config, structured) {
  const target = require.resolve(`../pages/${directory}/components/text-background-editor/text-background-editor`)
  let definition
  const previous = global.Component
  global.Component = value => { definition = value }
  delete require.cache[target]
  try { require(target) } finally {
    if (previous) global.Component = previous
    else delete global.Component
  }
  return Object.assign({}, definition.methods, {
    data: JSON.parse(JSON.stringify(definition.data)),
    properties: { config, structured },
    events: [],
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name, detail) { this.events.push({ name, detail }) }
  })
}

for (const directory of ['portfolios', 'team-portfolios']) {
  for (const structured of [false, true]) {
    test(`${directory} ${structured ? '结构化' : '普通'}背景入口再次点击收起列表，不重复请求或改写配置`, () => {
      const config = { backgroundEnabled: true, backgroundWorkId: 7, backgroundMemberUserId: 9,
        backgroundTreatment: 'GRADIENT', verticalAlignment: 'BOTTOM' }
      const before = JSON.stringify(config)
      const editor = createEditor(directory, config, structured)
      editor.handleOpenPicker()
      assert.equal(editor.data.pickerOpen, true)
      assert.equal(editor.events.filter(event => event.name === 'request').length, 1)
      editor.handleOpenPicker()
      assert.equal(editor.data.pickerOpen, false)
      assert.equal(editor.events.filter(event => event.name === 'request').length, 1)
      assert.equal(JSON.stringify(config), before)
      assert.equal(editor.events.some(event => event.name === 'change'), false)
    })
  }

  test(`${directory} 作品列表紧随选择入口，展开反馈不被后续背景配置推到屏幕外`, () => {
    const template = fs.readFileSync(path.join(__dirname, `../pages/${directory}/components/text-background-editor/text-background-editor.wxml`), 'utf8')
    const selection = template.indexOf('class="background-selection"')
    const picker = template.indexOf('class="background-picker"')
    const treatment = template.indexOf('>背景处理</view>')
    assert.ok(selection >= 0 && picker > selection && treatment > picker,
      '作品列表应直接展开在入口下方，不应位于背景处理和上下对齐之后')
    assert.match(template, /aria-expanded="\{\{pickerOpen\}\}"/)
    assert.match(template, /pickerOpen\s*\?\s*'收起作品列表'/)
    assert.match(template.slice(picker, treatment), /<block wx:if="\{\{!pickerOpen\}\}">/,
      '展开选择时暂时隐藏背景处理及对齐配置，收起后恢复')
  })
}

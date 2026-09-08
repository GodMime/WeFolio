const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')

for (const directory of ['portfolios', 'team-portfolios']) {
  test(`${directory} 色相轨道的色标与滑条实际 HSV 色相一致`, () => {
    const base = path.join(__dirname, `../pages/${directory}/components/text-color-editor/text-color-editor`)
    const css = fs.readFileSync(base + '.wxss', 'utf8')
    const template = fs.readFileSync(base + '.wxml', 'utf8')
    const max = Number(template.match(/<slider min="0" max="(\d+)"/)[1])
    const track = css.match(/\.text-color-hue-slider\s*\{[^}]*background:\s*linear-gradient\(to right,([^)]*)\)/)[1]
    const stops = track.split(',').map((stop, index, all) => {
      const [color, percent] = stop.trim().split(/\s+/)
      return { color: color.toUpperCase(), position: percent ? parseFloat(percent) / 100 : index / (all.length - 1) }
    })
    const { hsvToHex } = require('../utils/portfolio-color')
    for (const stop of stops) {
      const actual = hsvToHex({ hue: stop.position * max, saturation: 1, value: 1 })
      for (const offset of [1, 3, 5]) {
        assert.ok(Math.abs(parseInt(stop.color.slice(offset, offset + 2), 16) - parseInt(actual.slice(offset, offset + 2), 16)) <= 1,
          `${stop.position}: 轨道 ${stop.color}，实际 ${actual}`)
      }
    }
  })
}

// 仅替换微信宿主和时间，执行真实组件方法并检查对外事件与可见状态。
function editor(directory, color = 'AUTO') {
  const file = path.join(__dirname, '..', 'pages', directory, 'components/text-color-editor/text-color-editor.js')
  assert.ok(fs.existsSync(file), '文字颜色组件应已实现')
  let definition
  let timerId = 0
  const timers = new Map()
  const queries = []
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Component(value) { definition = value }, require: createRequire(file),
    setTimeout(callback, delay) { timers.set(++timerId, { callback, delay }); return timerId },
    clearTimeout(id) { timers.delete(id) }
  }, { filename: file })
  const instance = Object.assign({}, definition.methods, {
    data: structuredClone(definition.data), properties: { color, active: true }, events: [],
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback() },
    triggerEvent(name, detail) { this.events.push({ name, detail: JSON.parse(JSON.stringify(detail)) }) },
    createSelectorQuery() {
      return { select() { return this }, boundingClientRect(callback) { queries.push(callback); return this }, exec() {} }
    }
  })
  definition.lifetimes.attached.call(instance)
  return {
    instance, definition, queries, timers,
    tick() { const pending = [...timers.values()]; timers.clear(); pending.forEach(timer => timer.callback()) },
    property(name, value) {
      instance.properties[name] = value
      definition.observers[name].call(instance, value)
    }
  }
}

const choice = color => ({ currentTarget: { dataset: { color } } })
const input = value => ({ detail: { value } })
const touch = (clientX, clientY) => ({ touches: [{ clientX, clientY }] })
const rect = { left: 10, top: 20, width: 100, height: 200 }

for (const directory of ['portfolios', 'team-portfolios']) {
  test(`${directory} 接近色相边界的有效 HEX 打开与确认保持原值`, () => {
    const harness = editor(directory, '#FF0001')
    const view = harness.instance
    view.handleOpenCustom()
    harness.tick()
    assert.equal(view.data.draftColor, '#FF0001')
    view.handleHexInput(input('#ff0002'))
    assert.equal(view.data.draftColor, '#FF0002')
    view.handleConfirmCustom()
    assert.deepEqual(view.events, [{ name: 'change', detail: { color: '#FF0002' } }])
  })

  test(`${directory} 预设只发送合法颜色，自定义初始值继承当前颜色`, () => {
    const harness = editor(directory)
    const view = harness.instance
    assert.equal(view.data.selectedColor, 'AUTO')
    view.handlePreset(choice('#FFFFFF'))
    assert.deepEqual(view.events, [{ name: 'change', detail: { color: '#FFFFFF' } }])
    view.handlePreset(choice('red;position:fixed'))
    assert.equal(view.events.length, 1)
    harness.property('color', '#abcdef')
    assert.equal(view.data.selectedColor, '#ABCDEF')
    assert.equal(view.data.customSelected, true)
    view.handleOpenCustom()
    assert.equal(view.data.draftColor, '#ABCDEF')
    assert.equal(view.data.pickerVisible, false)
    harness.tick()
    assert.equal(view.data.pickerVisible, true)
    assert.equal(view.events.length, 1)
  })

  test(`${directory} 自定义编辑与取消不改原颜色，退场保留内容并可重新打开`, () => {
    const harness = editor(directory)
    const view = harness.instance
    view.handleOpenCustom()
    harness.tick()
    assert.equal(view.data.draftColor, '#212529')
    view.handleHexInput(input('#abcdef'))
    assert.equal(view.data.draftColor, '#ABCDEF')
    assert.equal(view.data.selectedColor, 'AUTO')
    view.handleCancelCustom()
    assert.equal(view.data.pickerVisible, false)
    assert.equal(view.data.pickerMounted, true)
    assert.equal(view.data.draftColor, '#ABCDEF')
    assert.deepEqual(view.events, [])
    const exitTimer = [...harness.timers.values()][0]
    view.handleCancelCustom()
    assert.equal([...harness.timers.values()][0], exitTimer, '退场重复点击不能延长遮罩时间')
    harness.tick()
    assert.equal(view.data.pickerMounted, false)
    view.handleOpenCustom()
    harness.tick()
    assert.equal(view.data.draftColor, '#212529')
    assert.equal(view.data.pickerVisible, true)
  })

  test(`${directory} HEX 错误不会写入或污染预览，修正后确认仅发一次事件`, () => {
    const harness = editor(directory, '#AABBCC')
    const view = harness.instance
    view.handleOpenCustom()
    harness.tick()
    for (const invalid of ['', '#123', '#ZZZZZZ', '#fff;xx']) {
      view.handleHexInput(input(invalid))
      view.handleHexBlur()
      view.handleConfirmCustom()
      assert.equal(view.data.hexInput, invalid.toUpperCase())
      assert.equal(view.data.draftColor, '#AABBCC')
      assert.equal(view.data.pickerVisible, true)
      assert.ok(view.data.errorMessage)
      assert.deepEqual(view.events, [])
    }
    view.handleHexInput(input('#ffffff'))
    view.handleConfirmCustom()
    view.handleConfirmCustom()
    assert.deepEqual(view.events, [{ name: 'change', detail: { color: '#FFFFFF' } }])
    assert.equal(view.data.selectedColor, '#FFFFFF')
    assert.equal(view.data.customSelected, false)
  })

  test(`${directory} 色相与二维拖动生成 HSV 颜色，越界坐标钳制且尚未确认不提交`, () => {
    const harness = editor(directory, '#FF0000')
    const view = harness.instance
    view.handleOpenCustom()
    harness.tick()
    view.handleHueChange(input(120))
    assert.equal(view.data.draftColor, '#00FF00')
    view.handleColorPadTouch(touch(60, 120))
    harness.queries.shift()(rect)
    assert.equal(view.data.draftColor, '#408040')
    view.handleColorPadTouch(touch(500, -100))
    harness.queries.shift()(rect)
    assert.equal(view.data.draftColor, '#00FF00')
    assert.deepEqual(view.events, [])
  })

  test(`${directory} 晚到的手势查询不得覆盖新手势或 HEX 输入`, () => {
    const harness = editor(directory, '#FF0000')
    const view = harness.instance
    view.handleOpenCustom()
    harness.tick()
    view.handleColorPadTouch(touch(10, 20))
    view.handleColorPadTouch(touch(110, 220))
    harness.queries[1](rect)
    harness.queries[0](rect)
    assert.equal(view.data.draftColor, '#000000')
    view.handleColorPadTouch(touch(110, 20))
    view.handleHexInput(input('#AABBCC'))
    harness.queries[2](rect)
    assert.equal(view.data.draftColor, '#AABBCC')
  })

  test(`${directory} 失活与卸载取消定时器和查询，旧回调不得复活弹层或写回`, () => {
    const harness = editor(directory, '#FF0000')
    const view = harness.instance
    view.handleOpenCustom()
    const oldEnter = [...harness.timers.values()][0].callback
    harness.property('active', false)
    oldEnter()
    assert.equal(view.data.pickerMounted, false)
    assert.equal(view.data.pickerVisible, false)
    assert.equal(harness.timers.size, 0)
    view.handleOpenCustom()
    view.handlePreset(choice('#FFFFFF'))
    assert.deepEqual(view.events, [])
    harness.property('active', true)
    view.handleOpenCustom()
    harness.tick()
    view.handleColorPadTouch(touch(10, 20))
    const oldQuery = harness.queries.shift()
    harness.property('active', false)
    harness.property('active', true)
    harness.property('color', '#112233')
    view.handleOpenCustom()
    harness.tick()
    oldQuery(rect)
    assert.equal(view.data.draftColor, '#112233')
    view.handleCancelCustom()
    const oldExit = [...harness.timers.values()][0].callback
    view.handleOpenCustom()
    harness.tick()
    oldExit()
    assert.equal(view.data.pickerVisible, true)
    harness.definition.lifetimes.detached.call(view)
    assert.equal(harness.timers.size, 0)
    view.handleConfirmCustom()
    assert.deepEqual(view.events, [])
  })
}

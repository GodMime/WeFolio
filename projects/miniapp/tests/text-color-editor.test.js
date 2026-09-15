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
    const { hsvToHex } = require('../pages/portfolios/utils/portfolio-color')
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
function editor(directory, color = 'AUTO', properties = {}) {
  const file = path.join(__dirname, '..', 'pages', directory, 'components/text-color-editor/text-color-editor.js')
  assert.ok(fs.existsSync(file), '文字颜色组件应已实现')
  let definition
  let timerId = 0
  const timers = new Map()
  const queries = []
  const clipboard = []
  const notices = []
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Component(value) { definition = value }, require: createRequire(file),
    wx: { setClipboardData(options) { clipboard.push(options) }, showToast(options) { notices.push(JSON.parse(JSON.stringify(options))) } },
    setTimeout(callback, delay) { timers.set(++timerId, { callback, delay }); return timerId },
    clearTimeout(id) { timers.delete(id) }
  }, { filename: file })
  const instance = Object.assign({}, definition.methods, {
    data: structuredClone(definition.data), properties: { color, active: true, ...properties }, events: [],
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback() },
    triggerEvent(name, detail) { this.events.push({ name, detail: JSON.parse(JSON.stringify(detail)) }) },
    createSelectorQuery() {
      return { select() { return this }, boundingClientRect(callback) { queries.push(callback); return this }, exec() {} }
    }
  })
  definition.lifetimes.attached.call(instance)
  return {
    instance, definition, queries, timers, clipboard, notices,
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
  test(`${directory} 弹层与内联复用可编辑色值和同行复制按钮，窄面板允许输入框缩小`, () => {
    const base = path.join(__dirname, `../pages/${directory}/components/text-color-editor/text-color-editor`)
    const template = fs.readFileSync(base + '.wxml', 'utf8')
    const css = fs.readFileSync(base + '.wxss', 'utf8')
    const content = template.slice(template.indexOf('<template name="colorPickerContent">'), template.indexOf('</template>'))
    const row = content.match(/<view class="text-color-hex-row">([\s\S]*?)<\/view>/)[1]
    assert.match(row, /<input[^>]*type="text"[^>]*value="\{\{hexInput\}\}"[^>]*bindinput="handleHexInput"[^>]*aria-label="十六进制\{\{label\}\}"/)
    assert.match(row, /<button[^>]*catchtap="handleCopyHex"[^>]*disabled="\{\{!pickerVisible \|\| !active\}\}"[^>]*aria-label="复制\{\{label\}\}色值">复制<\/button>/)
    assert.equal((template.match(/<template is="colorPickerContent"/g) || []).length, 2)
    assert.match(css, /\.text-color-hex-row\s*\{[^}]*display:\s*flex;[^}]*gap:\s*12rpx;/)
    assert.match(css, /\.text-color-hex-input\s*\{[^}]*flex:\s*1;[^}]*min-width:\s*0;[^}]*height:\s*76rpx;/)
    assert.match(css, /\.text-color-copy\s*\{[^}]*width:\s*112rpx;[^}]*height:\s*76rpx;/)
  })

  test(`${directory} 填写色值同步色板，复制当前大写色值且不确认或保存颜色`, () => {
    for (const inline of [false, true]) {
      const harness = editor(directory, '#AABBCC', { inline })
      const view = harness.instance
      view.handleOpenCustom(); harness.tick()
      view.handleHexInput(input('#00ff00'))
      assert.equal(view.data.hexInput, '#00FF00'); assert.equal(view.data.draftColor, '#00FF00')
      assert.deepEqual(JSON.parse(JSON.stringify(view.data.colorHsv)), { hue: 120, saturation: 1, value: 1 })
      view.handleCopyHex()
      assert.equal(harness.clipboard[0].data, '#00FF00')
      assert.equal(harness.clipboard[0].success, undefined, '成功使用微信自带提示，不重复弹出 toast')
      assert.equal(view.data.selectedColor, '#AABBCC'); assert.equal(view.data.pickerVisible, true)
      assert.deepEqual(view.events, []); assert.deepEqual(harness.notices, [])
      view.handleHueChange(input(240)); view.handleCopyHex()
      assert.equal(view.data.hexInput, '#0000FF'); assert.equal(harness.clipboard[1].data, '#0000FF')
      view.handleCancelCustom(); harness.tick()
      assert.equal(view.data.selectedColor, '#AABBCC'); assert.deepEqual(view.events, [])
      view.handleOpenCustom(); harness.tick()
      assert.equal(view.data.hexInput, '#AABBCC', '复制不影响下次打开的已保存颜色')
      view.handleHexInput(input('#12abEF')); view.handleCopyHex(); view.handleConfirmCustom()
      assert.equal(harness.clipboard[2].data, '#12ABEF')
      assert.deepEqual(view.events, [{ name: 'change', detail: { color: '#12ABEF' } }])
    }
  })

  test(`${directory} 非法色值不可复制旧预览值，修正输入后可复制`, () => {
    for (const inline of [false, true]) {
      const harness = editor(directory, '#AABBCC', { inline })
      const view = harness.instance
      view.handleOpenCustom(); harness.tick()
      for (const invalid of ['', '#123', '#ZZZZZZ', 'AUTO', 'TRANSPARENT', '#ABCDEF;display:none']) {
        view.handleHexInput(input(invalid)); view.handleCopyHex()
        assert.equal(view.data.errorMessage, '请输入正确的颜色值')
        assert.equal(view.data.draftColor, '#AABBCC'); assert.equal(harness.clipboard.length, 0)
        assert.deepEqual(view.events, [])
      }
      view.handleHexInput(input('#f5f6f8')); view.handleCopyHex()
      assert.equal(view.data.errorMessage, ''); assert.equal(harness.clipboard[0].data, '#F5F6F8')
      assert.deepEqual(view.events, [])
    }
  })

  test(`${directory} 未打开、退场、失活和卸载后不复制，当前复制失败提示重试`, () => {
    for (const inline of [false, true]) {
      const harness = editor(directory, '#AABBCC', { inline })
      const view = harness.instance
      view.handleCopyHex(); view.handleOpenCustom(); view.handleCopyHex()
      assert.equal(harness.clipboard.length, 0)
      harness.tick(); view.handleCopyHex()
      harness.clipboard[0].fail()
      assert.deepEqual(harness.notices, [{ title: '复制失败，请重试', icon: 'none' }])
      assert.equal(view.data.pickerVisible, true); assert.deepEqual(view.events, [])
      view.handleCancelCustom(); view.handleCopyHex(); harness.tick(); view.handleCopyHex()
      assert.equal(harness.clipboard.length, 1)
      view.handleOpenCustom(); harness.tick(); view.handleCopyHex()
      const pendingCopy = harness.clipboard[1]
      harness.property('active', false); view.handleCopyHex()
      harness.property('active', true); view.handleOpenCustom(); harness.tick()
      pendingCopy.fail()
      assert.equal(harness.notices.length, 1, '旧会话的失败回调不干扰重新打开的取色器')
      harness.definition.lifetimes.detached.call(view); view.handleCopyHex()
      assert.equal(harness.clipboard.length, 2); assert.deepEqual(view.events, [])
    }
  })

  test(`${directory} 边框与文字色板统一为黑白浅灰，自定义在原配置区确认并支持取消`, () => {
    const harness = editor(directory, '#D7DADD', { inline: true, palette: 'border', label: '线框颜色' })
    const view = harness.instance
    assert.deepEqual(JSON.parse(JSON.stringify(view.data.presets)), [
      { color: '#000000', label: '黑' }, { color: '#FFFFFF', label: '白' }, { color: '#CED4DA', label: '浅灰' }
    ])
    for (const color of ['#000000', '#FFFFFF', '#CED4DA']) {
      view.handlePreset(choice(color))
      assert.deepEqual(view.events.at(-1), { name: 'change', detail: { color } })
      harness.property('color', color)
    }
    for (const color of ['AUTO', 'TRANSPARENT', '#D7DADD', '#212529']) view.handlePreset(choice(color))
    assert.equal(view.events.length, 3, '入口不能选择未展示的旧预设')
    view.handleOpenCustom(); harness.tick()
    view.handleHexInput(input('#cc3366')); view.handleCancelCustom(); harness.tick()
    assert.equal(view.events.length, 3); assert.equal(view.data.selectedColor, '#CED4DA')
    view.handleOpenCustom(); harness.tick()
    view.handleHexInput(input('#cc3366')); view.handleConfirmCustom()
    assert.deepEqual(view.events.at(-1), { name: 'change', detail: { color: '#CC3366' } })
    harness.property('color', '#CC3366'); harness.tick()
    assert.equal(view.data.customSelected, true)
    view.handleOpenCustom(); harness.tick()
    harness.property('active', false)
    view.handleConfirmCustom()
    assert.equal(view.data.pickerMounted, false); assert.equal(view.events.length, 4)
    harness.property('active', true)
    assert.equal(view.data.pickerMounted, false, '重新启用边框不会恢复上次取色区')
    harness.property('palette', 'text')
    assert.deepEqual(JSON.parse(JSON.stringify(view.data.presets)), [
      { color: '#000000', label: '黑' }, { color: '#FFFFFF', label: '白' }, { color: '#CED4DA', label: '浅灰' }
    ], '文字与边框使用同一色板')
  })

  test(`${directory} 网格底色黑色预设输出纯黑，已保存的深灰保持原色`, () => {
    const harness = editor(directory, '#212529', { blackColor: '#000000' })
    const view = harness.instance
    const black = view.data.presets.find(item => item.label === '黑')
    assert.equal(black.color, '#000000')
    assert.equal(view.data.selectedColor, '#212529', '不得把已保存的深灰配置自动改写为纯黑')
    assert.equal(view.data.customSelected, true)
    view.handlePreset(choice(black.color))
    assert.deepEqual(view.events, [{ name: 'change', detail: { color: '#000000' } }])
    harness.property('color', '#000000')
    assert.equal(view.data.customSelected, false)
    const gridApi = require(`../pages/${directory}/utils/portfolio-text-grid`)
    const grid = gridApi.createTextGrid(1, 1)
    grid.cellBackground = view.events[0].detail.color
    const cells = gridApi.presentTextGrid(grid, gridApi.layoutTextGrid(grid, 375, 0.5), 'dark')
    assert.match(cells[0].style, /background:#000000;/)
    const defaultEditor = editor(directory).instance
    assert.equal(defaultEditor.data.presets.find(item => item.label === '黑').color, '#000000', '文字颜色入口也使用纯黑预设')
    harness.property('blackColor', '#212529')
    assert.equal(view.data.presets.find(item => item.label === '黑').color, '#000000', '旧入口属性不能覆盖统一色板')
  })

  test(`${directory} 旧主题色和旧预设仅回显，打开或取消自定义均不改写配置`, () => {
    for (const color of ['AUTO', '#212529', '#D7DADD', '#45464A', '#F5F6F8', '#ADB5BD']) {
      const harness = editor(directory, color)
      const view = harness.instance
      assert.equal(view.data.selectedColor, color)
      assert.equal(view.data.customSelected, color !== 'AUTO', '旧主题色不展示成自定义 AUTO 或无效颜色样本')
      view.handleOpenCustom(); harness.tick()
      assert.equal(view.data.draftColor, color === 'AUTO' ? '#000000' : color)
      view.handleHexInput(input('#123456')); view.handleCancelCustom(); harness.tick()
      assert.equal(view.data.selectedColor, color); assert.deepEqual(view.events, [])
      view.handleOpenCustom(); harness.tick()
      assert.equal(view.data.draftColor, color === 'AUTO' ? '#000000' : color)
      harness.property('active', false)
      assert.equal(view.data.selectedColor, color); assert.deepEqual(view.events, [])
    }
  })

  test(`${directory} 网格外观在颜色选项下展开配置，内联模式不挂载第二层弹窗`, () => {
    const base = path.join(__dirname, `../pages/${directory}/components`)
    const template = fs.readFileSync(path.join(base, 'text-color-editor/text-color-editor.wxml'), 'utf8')
    const css = fs.readFileSync(path.join(base, 'text-color-editor/text-color-editor.wxss'), 'utf8')
    const grid = fs.readFileSync(path.join(base, 'text-grid-editor/text-grid-editor.wxml'), 'utf8')
    const { definition } = editor(directory)
    assert.equal(definition.properties.inline.value, false, '既有颜色入口默认保留原交互')
    assert.equal(definition.properties.label.value, '文字颜色')
    const inlineIndex = template.indexOf('<view wx:if="{{inline}}"')
    const portalIndex = template.indexOf('<root-portal wx:else>')
    assert.ok(inlineIndex > template.indexOf('class="text-color-options'), '内联配置放在颜色选项下方')
    assert.ok(portalIndex > inlineIndex, '弹窗仅存在于非内联分支')
    assert.doesNotMatch(template.slice(inlineIndex, portalIndex), /root-portal|text-color-mask/)
    assert.match(css, /\.text-color-inline\s*\{[^}]*max-height:\s*0;/)
    assert.match(css, /\.text-color-inline\s*\{[^}]*pointer-events:\s*none;/)
    const backgroundEditor = grid.match(/<text-color-editor\b[^>]*bindchange="handleBackground"[^>]*\/>/)[0]
    assert.match(backgroundEditor, /inline="\{\{true\}\}"/)
    assert.match(backgroundEditor, /label="格子底色"/)
    assert.match(backgroundEditor, /black-color="#000000"/)
    assert.match(backgroundEditor, /active="\{\{visible && tab === 'appearance'\}\}"/)
    const textEditor = grid.match(/<text-color-editor\b[^>]*bindchange="handleRunColor"[^>]*\/>/)[0]
    assert.doesNotMatch(textEditor, /inline=/, '本次仅修改外观的格子底色入口')
  })

  test(`${directory} 内联自定义颜色取消不提交，确认后可再次编辑并切回预设`, () => {
    const harness = editor(directory, '#123456', { inline: true, label: '格子底色' })
    const view = harness.instance
    view.handleOpenCustom()
    harness.tick()
    assert.equal(view.data.draftColor, '#123456', '已有自定义颜色用于初始化配置')
    view.handleHexInput(input('#abcdef'))
    view.handleCancelCustom()
    assert.equal(view.data.pickerVisible, false)
    harness.tick()
    assert.equal(view.data.pickerMounted, false)
    assert.equal(view.data.selectedColor, '#123456')
    assert.deepEqual(view.events, [], '取消不得修改网格底色')

    view.handleOpenCustom()
    harness.tick()
    assert.equal(view.data.draftColor, '#123456', '再次展开应丢弃上次取消的临时颜色')
    view.handleHexInput(input('#abcdef'))
    view.handleConfirmCustom()
    assert.deepEqual(view.events, [{ name: 'change', detail: { color: '#ABCDEF' } }])
    harness.property('color', '#ABCDEF')
    harness.tick()
    assert.equal(view.data.pickerMounted, false)
    view.handleOpenCustom()
    harness.tick()
    assert.equal(view.data.draftColor, '#ABCDEF', '再次编辑继承已确认并回传的颜色')
    view.handlePreset(choice('#CED4DA'))
    assert.equal(view.data.pickerMounted, false)
    assert.equal(view.data.pickerVisible, false)
    assert.equal(harness.timers.size, 0)
    assert.deepEqual(view.events.at(-1), { name: 'change', detail: { color: '#CED4DA' } })
  })

  test(`${directory} 内联颜色失活后收起，重新进入不得恢复未确认输入或晚到手势`, () => {
    const harness = editor(directory, '#123456', { inline: true, label: '格子底色' })
    const view = harness.instance
    view.handleOpenCustom()
    harness.tick()
    view.handleHexInput(input('#ABCDEF'))
    view.handleColorPadTouch(touch(110, 220))
    const oldQuery = harness.queries.shift()
    harness.property('active', false)
    assert.equal(view.data.pickerMounted, false)
    assert.equal(view.data.pickerVisible, false)
    assert.equal(harness.timers.size, 0)
    harness.property('active', true)
    assert.equal(view.data.pickerMounted, false, '返回外观页时保持收起')
    harness.property('color', '#654321')
    view.handleOpenCustom()
    harness.tick()
    oldQuery(rect)
    assert.equal(view.data.draftColor, '#654321', '重新打开使用最新配置，旧查询不得写入')
    harness.definition.lifetimes.detached.call(view)
    view.handleConfirmCustom()
    assert.deepEqual(view.events, [])
  })

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
    assert.equal(view.data.draftColor, '#000000')
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
    assert.equal(view.data.draftColor, '#000000')
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

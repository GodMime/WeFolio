const test = require('node:test')
const assert = require('node:assert/strict')

const event = (dataset = {}, value) => ({ currentTarget: { dataset }, detail: { value } })

function editorFor(directory) {
  let definition
  global.Component = value => { definition = value }
  const target = require.resolve(`../pages/${directory}/components/structured-text-editor/structured-text-editor`)
  delete require.cache[target]
  try { require(target) } finally { delete global.Component }
  const editor = Object.assign({}, definition.methods, {
    data: structuredClone(definition.data),
    properties: { visible: true, config: { blocks: [{ blockKey: 'a', type: 'TITLE', content: '原内容' }] } },
    events: [],
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name, detail) { this.events.push({ name, detail }) },
    definition
  })
  editor.beginSession()
  return editor
}

for (const directory of ['portfolios', 'team-portfolios']) {
  test(`${directory} 拖动落在行间空隙时靠近相邻行，不跳至末尾`, () => {
    for (const [y, expected] of [[50, 'a'], [159, 'a'], [162, 'b'], [190, 'b'], [222, 'b'], [224, 'c'], [260, 'c'], [287, 'd'], [400, 'd']]) {
      const editor = editorFor(directory)
      editor.properties.config = { blocks: ['a', 'b', 'c', 'd'].map(blockKey => ({ blockKey, type: 'PARAGRAPH', content: blockKey })) }
      editor.beginSession()
      editor.data.draggingKey = 'a'
      editor.data.dragTargetKey = 'a'
      editor.dragStartY = 125
      editor.rowBounds = [{ top: 100, bottom: 156 }, { top: 163, bottom: 219 }, { top: 226, bottom: 282 }, { top: 289, bottom: 345 }]
      editor.handleDragMove({ touches: [{ clientX: 30, clientY: y }] })
      assert.equal(editor.data.dragTargetKey, expected, `y=${y}`)
      if (y === 222) {
        editor.handleDragEnd()
        assert.deepEqual(editor.data.draft.blocks.map(block => block.blockKey), ['b', 'a', 'c', 'd'])
      }
    }
  })

  test(`${directory} 取色确认仅写详情副本，取消后还原，退场中拒绝迟到颜色`, t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const editor = editorFor(directory)
    editor.handleEditBlock(event({ key: 'a' }))
    editor.handleColorChange({ detail: { color: '#ab12cd' } })
    assert.equal(editor.data.blockDraft.block.color, '#AB12CD')
    assert.equal(editor.data.draft.blocks[0].color, 'AUTO')
    editor.handleColorChange({ detail: { color: 'red;position:fixed' } })
    assert.equal(editor.data.blockDraft.block.color, '#AB12CD')
    editor.handleCancelBlock()
    editor.handleColorChange({ detail: { color: '#FFFFFF' } })
    assert.equal(editor.data.blockDraft.block.color, '#AB12CD')
    t.mock.timers.tick(200)
    editor.handleEditBlock(event({ key: 'a' }))
    assert.equal(editor.data.blockDraft.block.color, 'AUTO')
    editor.handleColorChange({ detail: { color: '#ab12cd' } })
    editor.handleSaveBlock()
    t.mock.timers.tick(200)
    assert.equal(editor.data.draft.blocks[0].color, '#AB12CD')
  })
  test(`${directory} 字号步进保留独立样式并限制在上下限内`, () => {
    const editor = editorFor(directory)
    editor.handleEditBlock(event({ key: 'a' }))
    const before = structuredClone(editor.data.blockDraft.block)
    editor.handleFontSizeInput(event({}, '20'))
    editor.handleFontSizeStep(event({ delta: -2 }))
    assert.equal(editor.data.blockDraft.block.fontSizeRpx, 20)
    assert.equal(editor.data.fontDecreaseDisabled, true)
    editor.handleFontSizeStep(event({ delta: 2 }))
    assert.equal(editor.data.blockDraft.block.fontSizeRpx, 22)
    editor.handleFontSizeInput(event({}, '95'))
    editor.handleFontSizeStep(event({ delta: 2 }))
    assert.equal(editor.data.blockDraft.block.fontSizeRpx, 96)
    assert.equal(editor.data.fontIncreaseDisabled, true)
    editor.handleFontSizeStep(event({ delta: 2 }))
    assert.equal(editor.data.blockDraft.block.fontSizeRpx, 96)
    editor.handleFontSizeStep(event({ delta: -2 }))
    assert.equal(editor.data.blockDraft.block.fontSizeRpx, 94)
    assert.deepEqual({ ...editor.data.blockDraft.block, fontSizeRpx: before.fontSizeRpx }, before)
  })

  test(`${directory} 手输非法字号不截断且阻止保存，修正后可保存`, t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const editor = editorFor(directory)
    editor.handleEditBlock(event({ key: 'a' }))
    for (const value of ['', '19', '97', '24.5', 'abc']) {
      editor.handleFontSizeInput(event({}, value))
      assert.equal(editor.data.blockDraft.block.fontSizeRpx, value)
      assert.ok(editor.data.fontSizeError)
      assert.equal(editor.data.fontDecreaseDisabled, true)
      assert.equal(editor.data.fontIncreaseDisabled, true)
      editor.handleSaveBlock()
      assert.equal(editor.data.detailClosing, false)
      assert.equal(editor.data.draft.blocks[0].fontSizeRpx, 44)
    }
    editor.handleFontSizeInput(event({}, '61'))
    assert.equal(editor.data.fontSizeError, '')
    editor.handleSaveBlock()
    t.mock.timers.tick(200)
    assert.equal(editor.data.draft.blocks[0].fontSizeRpx, 61)
    assert.equal(editor.data.blockDraft, null)
  })

  test(`${directory} 取消详情保留退场内容，阻止重复导航且不保存草稿`, t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const editor = editorFor(directory)
    editor.handleEditBlock(event({ key: 'a' }))
    editor.handleBlockInput(event({ field: 'content' }, '未保存'))
    editor.handleCancelBlock()
    assert.equal(editor.data.detailClosing, true)
    assert.equal(editor.data.blockDraft.block.content, '未保存')
    editor.handleAddType(event({ value: 'LIST' }))
    editor.handleSaveBlock()
    editor.handleConfirm()
    assert.equal(editor.data.blockDraft.block.type, 'TITLE')
    assert.equal(editor.events.length, 0)
    t.mock.timers.tick(199)
    assert.ok(editor.data.blockDraft)
    t.mock.timers.tick(1)
    assert.equal(editor.data.blockDraft, null)
    assert.equal(editor.data.draft.blocks[0].content, '原内容')
  })

  test(`${directory} 重复点击新增和保存仅产生一个区块`, t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const editor = editorFor(directory)
    editor.handleAddType(event({ value: 'TITLE' }))
    const key = editor.data.blockDraft.block.blockKey
    editor.handleAddType(event({ value: 'LIST' }))
    assert.equal(editor.data.blockDraft.block.blockKey, key)
    editor.handleBlockInput(event({ field: 'content' }, '新增内容'))
    editor.handleSaveBlock()
    editor.handleSaveBlock()
    assert.equal(editor.data.draft.blocks.length, 2)
    t.mock.timers.tick(200)
    assert.equal(editor.data.blockDraft, null)
    assert.equal(editor.data.draft.blocks.length, 2)
  })

  test(`${directory} 页签先退场再切换，过渡期间重复点击不插队`, t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const editor = editorFor(directory)
    editor.handleTab(event({ value: 'background' }))
    assert.equal(editor.data.tab, 'content')
    assert.equal(editor.data.tabLeaving, true)
    editor.handleTab(event({ value: 'content' }))
    t.mock.timers.tick(100)
    assert.equal(editor.data.tab, 'background')
    assert.equal(editor.data.tabLeaving, false)
    t.mock.timers.tick(100)
    assert.equal(editor.data.tabSwitching, false)
  })

  test(`${directory} 重开或卸载清除旧动画回调，不覆盖新会话`, t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const editor = editorFor(directory)
    editor.handleEditBlock(event({ key: 'a' }))
    editor.handleCancelBlock()
    editor.beginSession()
    editor.handleEditBlock(event({ key: 'a' }))
    t.mock.timers.tick(200)
    assert.equal(editor.data.blockDraft.block.blockKey, 'a')
    editor.handleCancelBlock()
    editor.handleCancel()
    t.mock.timers.tick(200)
    assert.equal(editor.data.blockDraft, null)
    editor.beginSession()
    editor.handleTab(event({ value: 'background' }))
    editor.definition.lifetimes.detached.call(editor)
    t.mock.timers.tick(200)
    assert.equal(editor.data.tab, 'content')
  })
}

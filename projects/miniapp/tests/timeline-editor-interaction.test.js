const test = require('node:test')
const assert = require('node:assert/strict')

const event = (dataset = {}, value) => ({ currentTarget: { dataset }, detail: { value } })
const touch = (y, key = 'a', identifier = 7) => ({ currentTarget: { dataset: { key } }, touches: [{ identifier, clientY: y }] })
const nodeKeys = editor => editor.data.draft.nodes.map(node => node.nodeKey)
const bounds = ['a', 'b', 'c', 'd'].map((key, index) => ({ dataset: { key }, top: 100 + index * 70, bottom: 160 + index * 70 }))

function editorFor(directory, deferred = false) {
  let definition
  const previous = global.Component
  global.Component = value => { definition = value }
  const filename = require.resolve(`../pages/${directory}/components/timeline-editor/timeline-editor`)
  delete require.cache[filename]
  try { require(filename) } finally { global.Component = previous }
  const properties = Object.fromEntries(Object.entries(definition.properties).map(([key, item]) => [key, structuredClone(item.value)]))
  const editor = Object.assign({}, definition.methods, {
    data: structuredClone(definition.data),
    properties: { ...properties, visible: true, config: { title: '历程', nodes: [
      { nodeKey: 'a', datePrecision: 'DAY', date: '2024-02-29', title: '起点', description: '最初的描述', textStyles: { title: { color: '#123456' } } },
      { nodeKey: 'b', datePrecision: 'CUSTOM', date: '筹备期', title: '筹备', description: '' },
      { nodeKey: 'c', datePrecision: 'YEAR', date: '2025', title: '成长', description: '' },
      { nodeKey: 'd', datePrecision: 'MONTH', date: '2026-10', title: '未来', description: '' }
    ] } },
    definition, events: [], queries: [], patches: [],
    setData(patch, callback) { this.patches.push(patch); Object.assign(this.data, patch); if (callback) callback() },
    triggerEvent(name, detail) { this.events.push({ name, detail }) },
    createSelectorQuery() {
      const query = {
        selectAll(selector) { assert.equal(selector, '.timeline-node-row'); return this },
        boundingClientRect(callback) { editor.queries.push(callback); return this },
        exec() { if (!deferred) editor.queries.shift()(bounds) }
      }
      return query
    }
  })
  definition.observers.visible.call(editor, true)
  return editor
}

for (const directory of ['portfolios', 'team-portfolios']) {
  test(`${directory} 时间轴拖动按身份跨节点排序，保留内容、样式、日期缓存及选中节点`, () => {
    const editor = editorFor(directory)
    editor.handlePrecision(event({ value: 'YEAR' }))
    editor.handleNode(event({ key: 'b' }))
    editor.handleBackNodes()
    const before = structuredClone(editor.data.draft)
    const dates = structuredClone(editor._dates)
    editor.handleDragStart(touch(125))
    editor.handleDragMove(touch(375))
    assert.equal(editor.data.dragTargetKey, 'd')
    assert.equal(editor.data.dragStyle, 'transform:translateY(250px);')
    assert.deepEqual(editor.data.draft, before, '松手前只更新拖动反馈')
    editor.handleDragEnd()
    assert.deepEqual(nodeKeys(editor), ['b', 'c', 'd', 'a'])
    assert.equal(editor.data.node.nodeKey, 'b')
    assert.equal(editor.data.nodeIndex, 0)
    assert.deepEqual(editor.data.draft.nodes[3], before.nodes[0])
    assert.deepEqual(editor._dates, dates)
    assert.equal(editor.data.draggingKey, '')
    assert.deepEqual(editor.properties.config.nodes.map(node => node.nodeKey), ['a', 'b', 'c', 'd'])
    editor.handleConfirm()
    assert.deepEqual(editor.events.find(item => item.name === 'confirm').detail.nodes.map(node => node.nodeKey), ['b', 'c', 'd', 'a'])
  })

  test(`${directory} 时间轴拖动行间空隙归邻近节点，上下越界钳制首尾`, () => {
    for (const [y, key] of [[50, 'a'], [164, 'a'], [166, 'b'], [234, 'b'], [236, 'c'], [304, 'c'], [306, 'd'], [500, 'd']]) {
      const editor = editorFor(directory)
      editor.handleDragStart(touch(195, 'b'))
      editor.handleDragMove(touch(y))
      assert.equal(editor.data.dragTargetKey, key, `y=${y}`)
    }
    const editor = editorFor(directory)
    editor.handleDragStart(touch(335, 'd'))
    editor.handleDragEnd({ changedTouches: [{ identifier: 7, clientY: 90 }] })
    assert.deepEqual(nodeKeys(editor), ['d', 'a', 'b', 'c'], '松手最后坐标参与排序')
  })

  test(`${directory} 时间轴轻触不移动，拖动后产生的点击不误开节点`, t => {
    let now = 1000
    t.mock.method(Date, 'now', () => now)
    const editor = editorFor(directory)
    editor.handleDragStart(touch(125))
    editor.handleDragMove(touch(127))
    editor.handleDragEnd()
    assert.deepEqual(nodeKeys(editor), ['a', 'b', 'c', 'd'])
    editor.handleNode(event({ key: 'a' }))
    assert.equal(editor.data.nodeDetail, false)
    now += 301
    editor.handleNode(event({ key: 'a' }))
    assert.equal(editor.data.nodeDetail, true)
    editor.handleBackNodes()
    editor.handleDragStart(touch(125))
    editor.handleDragMove(touch(260))
    editor.handleDragEnd()
    editor.handleNode(event({ key: 'a' }))
    assert.equal(editor.data.nodeDetail, false)
    now += 301
    editor.handleNode(event({ key: 'a' }))
    assert.equal(editor.data.nodeIndex, 2)
  })

  test(`${directory} 时间轴取消、多指或手指身份变化结束拖动且不重排`, () => {
    for (const cancel of [
      editor => editor.handleDragCancel(),
      editor => editor.handleDragMove({ touches: [{ identifier: 7, clientY: 310 }, { identifier: 8, clientY: 320 }] }),
      editor => editor.handleDragMove(touch(310, 'a', 8)),
      editor => editor.handleDragStart(touch(310, 'b', 8)),
      editor => editor.handleTab(event({ value: 'style' }))
    ]) {
      const editor = editorFor(directory)
      editor.handleDragStart(touch(125))
      editor.handleDragMove(touch(310))
      cancel(editor)
      editor.handleDragEnd()
      assert.deepEqual(nodeKeys(editor), ['a', 'b', 'c', 'd'])
      assert.equal(editor.data.draggingKey, '')
      assert.equal(editor.data.dragTargetKey, '')
      assert.equal(editor.data.dragStyle, '')
    }
  })

  test(`${directory} 时间轴测量等待期间记录最新位置，迟到回调不影响新拖动或重开会话`, () => {
    const editor = editorFor(directory, true)
    editor.handleDragStart(touch(125))
    const stale = editor.queries.shift()
    editor.handleDragMove(touch(280))
    assert.equal(editor.data.dragTargetKey, 'a')
    stale(bounds)
    assert.equal(editor.data.dragTargetKey, 'c')
    editor.handleDragCancel()
    editor.handleDragStart(touch(195, 'b'))
    editor.handleDragMove(touch(350))
    stale(bounds)
    assert.equal(editor.data.dragTargetKey, 'b')
    const closed = editor.queries.shift()
    editor.handleCancel()
    editor.definition.observers.visible.call(editor, true)
    closed(bounds)
    editor.handleDragEnd()
    assert.deepEqual(nodeKeys(editor), ['a', 'b', 'c', 'd'])
    assert.equal(editor.data.draggingKey, '')
    assert.equal(editor.data.styleDetail, false)
  })

  test(`${directory} 时间轴按行删除保留当前节点身份，详情删除回到列表且可继续添加`, () => {
    const editor = editorFor(directory)
    editor.handleNode(event({ index: 2 }))
    editor.handleRemoveNode(event({ key: 'a' }))
    assert.deepEqual(nodeKeys(editor), ['b', 'c', 'd'])
    assert.equal(editor.data.node.nodeKey, 'c')
    assert.equal(editor.data.nodeIndex, 1)
    assert.equal(editor._dates.a, undefined)
    assert.equal(editor.data.nodeDetail, false)
    editor.handleNode(event({ key: 'c' }))
    editor.handleRemoveNode()
    assert.deepEqual(nodeKeys(editor), ['b', 'd'])
    assert.equal(editor.data.node.nodeKey, 'd')
    editor.handleRemoveNode()
    editor.handleRemoveNode()
    assert.deepEqual(nodeKeys(editor), [])
    assert.equal(editor.data.nodeDetail, false)
    editor.handleAddNode()
    assert.equal(editor.data.draft.nodes.length, 1)
    assert.equal(editor.data.nodeDetail, true)
  })

  test(`${directory} 时间轴文字样式进入独立详情，只修改目标角色且返回原上下文`, () => {
    const editor = editorFor(directory)
    assert.deepEqual(editor.data.styleEntries.map(item => item.value), ['heading', 'date', 'title', 'description'])
    assert.deepEqual(editor.data.nodeStyleEntries.map(item => item.value), ['date', 'title', 'description'])
    editor.handleTab(event({ value: 'style' }))
    editor.handleEditTextStyle(event({ scope: 'component', role: 'date' }))
    assert.equal(editor.data.styleDetail, true)
    editor.handleColor({ detail: { color: '#ABCDEF' } })
    editor.handleSaveTextStyle()
    assert.equal(editor.data.styleDetail, false)
    assert.equal(editor.data.tab, 'style')
    assert.deepEqual(editor.data.draft.textStyles, { date: { color: '#ABCDEF' } })
    editor.handleTab(event({ value: 'content' }))
    editor.handleNode(event({ key: 'a' }))
    editor.handleEditTextStyle(event({ scope: 'node', role: 'title' }))
    editor.handleFontSize(event({}, 35))
    assert.match(editor.data.nodeStyleEntries.find(item => item.value === 'title').summary, /35 rpx.*单独设置/)
    editor.handleSaveTextStyle()
    assert.equal(editor.data.nodeDetail, true)
    assert.equal(editor.data.tab, 'content')
    assert.deepEqual(editor.data.draft.nodes[0].textStyles.title, { color: '#123456', fontSizeRpx: 35 })
    editor.handleCancel()
    editor.handleEditTextStyle(event({ scope: 'node', role: 'date' }))
    assert.equal(editor.data.styleDetail, false)
  })

  test(`${directory} 时间轴文字详情取消或返回只回滚当前作用域，迟到颜色不再写入`, () => {
    for (const close of ['handleCancelTextStyle', 'handleBackTextStyle']) {
      const editor = editorFor(directory)
      editor.handleField(event({ field: 'title' }, '已编辑标题'))
      editor.handleEditTextStyle(event({ scope: 'component', role: 'date' }))
      editor.handleColor({ detail: { color: '#ABCDEF' } })
      editor.handleStyleRole(event({ value: 'heading' }))
      editor.handleFontSize(event({}, 66))
      editor[close]()
      assert.deepEqual(editor.data.draft.textStyles, {})
      assert.equal(editor.data.draft.title, '已编辑标题')
      assert.equal(editor.data.styleDetail, false)
      assert.deepEqual(editor.events.at(-1).detail.config.textStyles, {}, '字体需求也恢复到原草稿')
      editor.handleColor({ detail: { color: '#000000' } })
      assert.deepEqual(editor.data.draft.textStyles, {})
      editor.handleNode(event({ key: 'b' }))
      editor.handleNodeField(event({ field: 'description' }, '已编辑正文'))
      editor.handleEditTextStyle(event({ scope: 'node', role: 'title' }))
      editor.handleColor({ detail: { color: '#123456' } })
      editor[close]()
      assert.equal(Object.hasOwn(editor.data.draft.nodes[1], 'textStyles'), false, '未设置过的节点恢复真正继承')
      assert.equal(editor.data.draft.nodes[1].description, '已编辑正文')
      assert.deepEqual(editor.data.draft.nodes[0].textStyles.title, { color: '#123456' })
      assert.equal(editor.events.some(item => item.name === 'confirm'), false)
    }
  })

  test(`${directory} 时间轴保存文字样式仅校验样式，不要求先填完节点，也不提交整组件`, () => {
    const editor = editorFor(directory)
    editor.handleAddNode()
    const key = editor.data.node.nodeKey
    editor.handleEditTextStyle(event({ scope: 'node', role: 'title' }))
    editor.handleFontSize(event({}, 9))
    editor.handleSaveTextStyle()
    assert.equal(editor.data.styleDetail, true)
    assert.match(editor.data.error, /10–96/)
    editor.handleFontSize(event({}, 38))
    editor.handleSaveTextStyle()
    assert.equal(editor.data.styleDetail, false)
    assert.equal(editor.data.nodeDetail, true)
    assert.equal(editor.data.node.nodeKey, key)
    assert.equal(editor.data.node.title, '')
    assert.equal(editor.data.node.date, '')
    assert.equal(editor.data.node.textStyles.title.fontSizeRpx, 38)
    assert.equal(editor.events.some(item => item.name === 'confirm'), false)
    editor.handleEditTextStyle(event({ scope: 'node', role: 'title' }))
    editor.handleFontSize(event({}, 42))
    editor.handleCancelTextStyle()
    assert.equal(editor.data.node.textStyles.title.fontSizeRpx, 38, '再次进入以最近保存样式为快照')
    editor.handleConfirm()
    assert.equal(editor.events.some(item => item.name === 'confirm'), false, '整组件完成仍要求节点完整')
  })

  test(`${directory} 时间轴进入详情与切换页签回顶，返回恢复入口滚动位置`, () => {
    const editor = editorFor(directory)
    const scroll = top => editor.handleEditorScroll({ detail: { scrollTop: top } })
    scroll(480)
    editor.patches.length = 0
    editor.handleNode(event({ key: 'c' }))
    assert.deepEqual(editor.patches.filter(patch => Object.hasOwn(patch, 'editorScrollTop')).map(patch => patch.editorScrollTop), [480, 0], '先同步实际滚动再复位，避免同值失效')
    scroll(260)
    editor.handleEditTextStyle(event({ scope: 'node', role: 'title' }))
    assert.equal(editor.data.editorScrollTop, 0)
    scroll(330)
    editor.handleSaveTextStyle()
    assert.equal(editor.data.editorScrollTop, 260)
    editor.handleBackNodes()
    assert.equal(editor.data.editorScrollTop, 480)
    editor.handleTab(event({ value: 'style' }))
    assert.equal(editor.data.editorScrollTop, 0)
    scroll(620)
    editor.handleEditTextStyle(event({ scope: 'component', role: 'heading' }))
    assert.equal(editor.data.editorScrollTop, 0)
    scroll(310)
    editor.handleCancelTextStyle()
    assert.equal(editor.data.editorScrollTop, 620)
    editor.handleTab(event({ value: 'content' }))
    scroll(420)
    editor.handleAddNode()
    assert.equal(editor.data.editorScrollTop, 0)
    editor.handleBackNodes()
    assert.equal(editor.data.editorScrollTop, 420)
  })

  test(`${directory} 时间轴旧滚动复位回调不能覆盖新导航或关闭后的会话`, () => {
    const editor = editorFor(directory)
    const callbacks = []
    editor.setData = function (patch, callback) { Object.assign(this.data, patch); if (callback) callbacks.push(callback) }
    editor.handleEditorScroll({ detail: { scrollTop: 600 } })
    editor.handleNode(event({ key: 'b' }))
    const first = callbacks.shift()
    editor.handleBackNodes()
    callbacks.shift()()
    assert.equal(editor.data.editorScrollTop, 600)
    first()
    assert.equal(editor.data.editorScrollTop, 600)
    editor.handleNode(event({ key: 'c' }))
    const closed = callbacks.shift()
    editor.handleCancel()
    editor.definition.observers.visible.call(editor, true)
    callbacks.shift()()
    closed()
    assert.equal(editor.data.editorScrollTop, 0)
  })
}

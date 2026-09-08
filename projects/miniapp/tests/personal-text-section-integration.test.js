const test = require('node:test')
const assert = require('node:assert/strict')
const base = '../pages/portfolios/'
function harness(file, component = true, requestFn) {
  let definition
  global[component ? 'Component' : 'Page'] = value => { definition = value }
  const target = require.resolve(base + file)
  delete require.cache[target]
  const requestPath = require.resolve('../utils/request')
  const originalRequest = require.cache[requestPath]
  if (requestFn) require.cache[requestPath] = { id: requestPath, filename: requestPath, loaded: true, exports: { request: requestFn } }
  try { require(target) } finally {
    if (requestFn) {
      if (originalRequest) require.cache[requestPath] = originalRequest
      else delete require.cache[requestPath]
    }
  }
  delete global[component ? 'Component' : 'Page']
  const instance = Object.assign({}, component ? definition.methods : definition, {
    data: JSON.parse(JSON.stringify(definition.data || {})), properties: {}, events: [],
    setData(patch) {
      Object.entries(patch).forEach(([key, value]) => {
        const fields = key.replace(/\[(\d+)\]/g, '.$1').split('.')
        let current = this.data
        fields.slice(0, -1).forEach(field => { current = current[field] })
        current[fields.at(-1)] = value
      })
    },
    triggerEvent(name, detail) { this.events.push({ name, detail }) }
  })
  instance.definition = definition
  return instance
}
const evt = (dataset = {}, value) => ({ currentTarget: { dataset }, detail: { value } })
const block = key => ({ blockKey: key, type: 'TITLE', content: key })

test('个人普通文字颜色只在完成后写回，取消和迟到取色不改变原配置', () => {
  const page = harness('standard-edit/portfolio-standard-edit', false)
  const utils = require('../utils/portfolios')
  page.data.config = utils.addComponent(page.data.config, 'TEXT_SECTION')
  const key = page.data.config.components.at(-1).componentKey
  page.openTextSectionSheet(key)
  assert.equal(page.data.textSectionForm.color, 'AUTO')
  page.handleTextSectionColorChange({ detail: { color: '#abcdef' } })
  assert.equal(page.data.textSectionForm.color, '#ABCDEF')
  assert.equal(page.data.config.components.at(-1).config.color, 'AUTO')
  page.handleCloseTextSectionSheet()
  page.handleTextSectionColorChange({ detail: { color: '#FFFFFF' } })
  assert.equal(page.data.textSectionForm.color, 'AUTO')
  page.openTextSectionSheet(key)
  page.handleTextSectionInput(evt({}, '正文'))
  page.handleTextSectionColorChange({ detail: { color: '#abcdef' } })
  page.handleConfirmTextSectionConfig()
  assert.equal(page.data.config.components.at(-1).config.color, '#ABCDEF')
  page.openTextSectionSheet(key)
  assert.equal(page.data.textSectionForm.color, '#ABCDEF')
  page.handleCloseTextSectionSheet()
})

test('结构化组件详情与整体副本隔离，取消新增不留组件，完成才写回', () => {
  const page = harness('standard-edit/portfolio-standard-edit', false)
  const before = JSON.stringify(page.data.config)
  page.handleSelectComponent(evt({ type: 'STRUCTURED_TEXT_SECTION' }))
  assert.equal(JSON.stringify(page.data.config), before)
  assert.equal(page.data.structuredTextSheetVisible, true)
  page.handleCancelStructuredText()
  assert.equal(JSON.stringify(page.data.config), before)
  page.openStructuredTextSheet()
  page.handleConfirmStructuredText({ detail: { blocks: [block('a')] } })
  const components = require('../utils/portfolios').getMenuComponentList(page.data.config, page.data.activeMenuKey)
  assert.equal(components.at(-1).componentType, 'STRUCTURED_TEXT_SECTION')
  assert.equal(components.at(-1).config.blocks[0].color, 'AUTO')
})

test('区块详情取消、类型缓存、排序和删除都不污染传入配置', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const editor = harness('components/structured-text-editor/structured-text-editor')
  const original = { blocks: [block('a'), block('b')] }
  editor.properties = { visible: true, config: original }
  editor.beginSession()
  editor.handleEditBlock(evt({ key: 'a' }))
  editor.handleBlockInput(evt({ field: 'content' }, '甲\n乙'))
  editor.handleBlockType(evt({ value: 'LIST' }))
  assert.deepEqual(editor.data.blockDraft.block.items, ['甲', '乙'])
  editor.handleBlockType(evt({ value: 'SPACER' }))
  editor.handleBlockType(evt({ value: 'TITLE' }))
  assert.equal(editor.data.blockDraft.block.content, '甲\n乙')
  editor.handleCancelBlock()
  t.mock.timers.tick(200)
  assert.equal(editor.data.draft.blocks[0].content, 'a')
  editor.handleEditBlock(evt({ key: 'a' }))
  editor.handleBlockInput(evt({ field: 'content' }, '修改'))
  editor.handleSaveBlock()
  t.mock.timers.tick(200)
  assert.equal(editor.data.draft.blocks[0].content, '修改')
  editor.reorderBlock('a', 'b')
  editor.handleDeleteBlock(evt({ key: 'b' }))
  editor.handleCancel()
  assert.deepEqual(original, { blocks: [block('a'), block('b')] })
  assert.equal(editor.events.at(-1).name, 'cancel')
})

test('左滑仅展开，纵向滚动不删除，超量内容不截断', () => {
  const editor = harness('components/structured-text-editor/structured-text-editor')
  editor.properties = { visible: true, config: { blocks: [block('a'), block('b')] } }
  editor.beginSession()
  const touch = (key, x, y) => ({ currentTarget: { dataset: { key } }, changedTouches: [{ clientX: x, clientY: y }], touches: [{ clientX: x, clientY: y }] })
  editor.handleRowTouchStart(touch('a', 100, 0))
  editor.handleRowTouchEnd(touch('a', 10, 4))
  assert.equal(editor.data.revealedKey, 'a')
  assert.equal(editor.data.draft.blocks.length, 2)
  editor.handleRowTouchStart(touch('b', 100, 0))
  editor.handleRowTouchEnd(touch('b', 80, 150))
  assert.equal(editor.data.draft.blocks.length, 2)
  editor.handleOutsideTap()
  editor.handleEditBlock(evt({ key: 'a' }))
  editor.handleBlockInput(evt({ field: 'content' }, '😀'.repeat(2001)))
  editor.handleSaveBlock()
  assert.equal(editor.data.blockDraft.block.content.length, 4002)
  assert.equal(editor.data.draft.blocks[0].content, 'a')
  assert.ok(editor.data.error)
})

test('普通背景副本关闭取消恢复，保存关闭清除背景 ID', () => {
  const page = harness('standard-edit/portfolio-standard-edit', false)
  const utils = require('../utils/portfolios')
  page.data.config = utils.addComponent(page.data.config, 'TEXT_SECTION')
  const item = page.data.config.components.at(-1)
  Object.assign(item.config, { content: '文字', backgroundEnabled: true, backgroundWorkId: 7, backgroundTreatment: 'ORIGINAL' })
  const before = JSON.stringify(page.data.config)
  page.openTextSectionSheet(item.componentKey)
  page.handleTextBackgroundChange({ detail: { ...page.data.textSectionForm, backgroundEnabled: false } })
  page.handleCloseTextSectionSheet()
  assert.equal(JSON.stringify(page.data.config), before)
  page.openTextSectionSheet(item.componentKey)
  page.handleTextBackgroundChange({ detail: { ...page.data.textSectionForm, backgroundEnabled: false } })
  page.handleConfirmTextSectionConfig()
  const saved = utils.getMenuComponentList(page.data.config, '').at(-1).config
  assert.equal(saved.backgroundEnabled, false)
  assert.equal(saved.backgroundWorkId, undefined)
  assert.equal(saved.backgroundTreatment, 'ORIGINAL')
})

test('文字 renderer 保留动画 URL、深色兜底和 AUTO，错误仅影响展示状态', () => {
  const view = harness('components/structured-text-section/structured-text-section')
  const config = { backgroundEnabled: true, backgroundWorkId: 7, backgroundWork: { workId: 7, mediaType: 'ANIMATION', url: 'https://example.test/a.gif', width: 750, height: 1000 }, blocks: [block('a')] }
  view.properties = { structuredTextSection: config, themeMode: 'light', repairMode: true }
  view.refreshPresentation()
  assert.equal(view.data.background.imageUrl, 'https://example.test/a.gif')
  assert.equal(view.data.presentation.blocks[0].displayColor, '#F8F9FA')
  view.handleBackgroundError()
  assert.equal(view.data.backgroundFailed, true)
  assert.equal(config.backgroundEnabled, true)
  assert.equal(config.backgroundWorkId, 7)
})

test('背景请求分页跨过视频页、搜索使用审核通过且忽略关闭和新会话响应', async () => {
  const pending = []
  const page = harness('standard-edit/portfolio-standard-edit', false, params => new Promise((resolve, reject) => pending.push({ params, resolve, reject })))
  page.openStructuredTextSheet()
  const old = page.handleTextBackgroundRequest({ detail: { keyword: '旧搜索' } })
  page.handleCancelStructuredText()
  page.openStructuredTextSheet()
  const fresh = page.handleTextBackgroundRequest({ detail: { keyword: '新搜索' } })
  pending[0].resolve({ works: [{ id: 1, mediaType: 'IMAGE', mediaUrl: 'old' }], page: 1, hasMore: false })
  await old
  assert.deepEqual(page.data.textBackgroundOptions, [])
  assert.equal(page.data.textBackgroundLoading, true)
  assert.equal(pending[1].params.data.keyword, '新搜索')
  assert.equal(pending[1].params.data.auditStatus, 'PASSED')
  pending[1].resolve({ works: [{ id: 2, mediaType: 'VIDEO', mediaUrl: 'video' }], page: 1, hasMore: true })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(pending[2].params.data.page, 2)
  pending[2].resolve({ works: [{ id: 3, mediaType: 'ANIMATION', mediaUrl: 'https://example.test/raw.gif', coverUrl: 'static.jpg' }], page: 2, hasMore: true })
  await fresh
  assert.equal(page.data.textBackgroundOptions[0].url, 'https://example.test/raw.gif')
  const more = page.handleTextBackgroundRequest({ detail: { reset: false } })
  assert.equal(pending[3].params.data.page, 3)
  pending[3].reject(new Error('网络失败'))
  await more
  assert.equal(page.data.textBackgroundOptions.length, 1)
  assert.equal(page.data.textBackgroundError, '网络失败')
  const unloaded = page.handleTextBackgroundRequest({ detail: { reset: true } })
  page.onUnload()
  pending[4].resolve({ works: [{ id: 8, mediaType: 'IMAGE', mediaUrl: 'gone' }], page: 1, hasMore: false })
  await unloaded
  assert.deepEqual(page.data.textBackgroundOptions, [])
})

test('更换背景同步替换资源与失效标识，关闭再开启仍回显选择', () => {
  const picker = harness('components/text-background-editor/text-background-editor')
  const source = { backgroundEnabled: true, backgroundWorkId: 1, backgroundInvalid: true,
    backgroundWork: { workId: 1, mediaType: 'IMAGE', url: 'old' } }
  picker.properties = { config: source, options: [{ id: 2, mediaType: 'ANIMATION', url: 'new.gif', width: 100, height: 200 }, { id: 3, mediaType: 'VIDEO', url: 'video' }] }
  picker.handleSelect(evt({ id: 3 }))
  assert.equal(picker.events.length, 0)
  picker.handleSelect(evt({ id: 2 }))
  const config = picker.events.at(-1).detail
  assert.equal(config.backgroundWorkId, 2)
  assert.equal(config.backgroundInvalid, false)
  assert.deepEqual(config.backgroundWork, { workId: 2, mediaType: 'ANIMATION', url: 'new.gif', width: 100, height: 200 })
  picker.properties.config = config
  picker.handleToggle(evt({}, false))
  assert.equal(picker.events.at(-1).detail.backgroundWorkId, 2)
  assert.equal(source.backgroundWorkId, 1)
})

test('独立字号字重颜色对齐与间距不互相覆盖，详情保存列表及整体完成剔除缓存', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const editor = harness('components/structured-text-editor/structured-text-editor')
  editor.properties = { config: { blocks: [block('a')] } }
  editor.beginSession()
  editor.handleEditBlock(evt({ key: 'a' }))
  editor.handleBlockInput(evt({ field: 'fontSizeRpx' }, '61'))
  editor.handleBlockOption(evt({ field: 'fontWeight', value: 'NORMAL' }))
  editor.handleBlockOption(evt({ field: 'alignment', value: 'RIGHT' }))
  editor.handleBlockOption(evt({ field: 'color', value: '#FFFFFF' }))
  editor.handleSpacingInput(evt({ field: 'marginTopRpx' }, '27'))
  editor.handleSpacingStep(evt({ field: 'marginBottomRpx', delta: 4 }))
  editor.handleBlockType(evt({ value: 'LIST' }))
  editor.handleListInput(evt({ index: 0 }, ' 列表 😀 '))
  editor.handleAddListItem()
  editor.handleListInput(evt({ index: 1 }, '第二项'))
  editor.handleSaveBlock()
  editor.handleConfirm()
  assert.equal(editor.events.length, 0)
  t.mock.timers.tick(200)
  editor.handleConfirm()
  const saved = editor.events.at(-1).detail.blocks[0]
  assert.equal(saved.fontSizeRpx, 61)
  assert.equal(saved.fontWeight, 'NORMAL')
  assert.equal(saved.color, '#FFFFFF')
  assert.equal(saved.alignment, 'RIGHT')
  assert.equal(saved.marginTopRpx, 28)
  assert.equal(saved.marginBottomRpx, 20)
  assert.deepEqual(saved.items, [' 列表 😀 ', '第二项'])
  assert.equal(saved.content, undefined)
  assert.equal(saved.payloads, undefined)
})

test('下拉关闭组件丢弃全部，下拉关闭详情只丢弃局部', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const editor = harness('components/structured-text-editor/structured-text-editor')
  editor.properties = { config: { blocks: [block('a')] } }
  editor.beginSession()
  editor.handleEditBlock(evt({ key: 'a' }))
  const down = (layer, y) => ({ currentTarget: { dataset: { layer } }, changedTouches: [{ clientY: y }], touches: [{ clientY: y }] })
  editor.handleSheetTouchStart(down('detail', 20))
  editor.handleSheetTouchEnd(down('detail', 160))
  assert.equal(editor.data.detailClosing, true)
  t.mock.timers.tick(200)
  assert.equal(editor.data.blockDraft, null)
  assert.equal(editor.events.length, 0)
  editor.handleSheetTouchStart(down('component', 20))
  editor.handleSheetTouchEnd(down('component', 160))
  assert.equal(editor.events.at(-1).name, 'cancel')
})

test('页面草稿保存和发布前拦截失效背景或非法结构化内容并定位组件', async () => {
  const page = harness('standard-edit/portfolio-standard-edit', false)
  const utils = require('../utils/portfolios')
  page.data.config = utils.addComponent(page.data.config, 'STRUCTURED_TEXT_SECTION')
  const item = page.data.config.components.at(-1)
  item.config = { blocks: [block('a')], backgroundEnabled: true, backgroundWorkId: 7, backgroundInvalid: true }
  let requested = false
  page.ensureDraftPortfolio = () => { requested = true; return Promise.resolve(1) }
  const previousWx = global.wx
  global.wx = { showToast() {} }
  try {
    await page.handleSaveDraft()
    assert.equal(requested, false)
    assert.equal(page.data.validationComponentKey, item.componentKey)
    await page.handlePublish()
    assert.equal(requested, false)
    assert.equal(page.data.validationComponentKey, item.componentKey)
  } finally { global.wx = previousWx }
})

test('拖动按稳定身份重排且越过底部落到最后一项', () => {
  const editor = harness('components/structured-text-editor/structured-text-editor')
  editor.properties = { config: { blocks: [block('a'), block('b'), block('c')] } }
  editor.beginSession()
  editor.createSelectorQuery = () => ({ selectAll: () => ({ boundingClientRect: callback => {
    callback([{ top: 0, bottom: 100 }, { top: 100, bottom: 200 }, { top: 200, bottom: 300 }])
    return { exec() {} }
  } }) })
  editor.handleDragStart({ currentTarget: { dataset: { key: 'a' } }, touches: [{ clientY: 50 }] })
  editor.handleDragMove({ touches: [{ clientY: 350 }] })
  editor.handleDragEnd()
  assert.deepEqual(editor.data.draft.blocks.map(item => item.blockKey), ['b', 'c', 'a'])
  assert.equal(editor.data.draggingKey, '')
})

test('组件列表以可编辑按钮呈现结构化文字入口', () => {
  const rows = require('../pages/portfolios/standard-edit/component-rows.wxs')
  assert.equal(rows.isEditable('STRUCTURED_TEXT_SECTION'), true)
  assert.equal(rows.resolveAriaRole('STRUCTURED_TEXT_SECTION'), 'button')
})

test('重复列表条目展示不丢失，删除中间编辑条目保留后续输入身份', () => {
  const config = { blocks: [{ blockKey: 'list', type: 'LIST', items: ['重复', '重复', '最后'] }] }
  const view = harness('components/structured-text-section/structured-text-section')
  view.properties = { structuredTextSection: config, themeMode: 'light' }
  view.refreshPresentation()
  assert.deepEqual(view.data.presentation.blocks[0].listItems.map(row => row.content), ['重复', '重复', '最后'])
  assert.equal(new Set(view.data.presentation.blocks[0].listItems.map(row => row.key)).size, 3)
  const editor = harness('components/structured-text-editor/structured-text-editor')
  editor.properties = { config }
  editor.beginSession()
  editor.handleEditBlock(evt({ key: 'list' }))
  const lastKey = editor.data.listRows[2].key
  editor.handleDeleteListItem(evt({ index: 1 }))
  assert.equal(editor.data.listRows[1].key, lastKey)
  editor.handleListInput(evt({ index: 1 }, '保留此输入'))
  editor.handleSaveBlock()
  assert.deepEqual(editor.data.draft.blocks[0].items, ['重复', '保留此输入'])
})

test('持久化背景按 ID 恢复，不依赖作品第一页，失效与过期响应不清理原ID', async () => {
  const pending = []
  const page = harness('standard-edit/portfolio-standard-edit', false, params => new Promise(resolve => pending.push({ params, resolve })))
  const utils = require('../utils/portfolios')
  page.data.config = utils.addComponent(page.data.config, 'TEXT_SECTION')
  const item = page.data.config.components.at(-1)
  Object.assign(item.config, { content: '介绍', backgroundEnabled: true, backgroundWorkId: 99 })
  const first = page.openTextSectionSheet(item.componentKey)
  assert.equal(pending[0].params.url, '/api/mine/works/99')
  assert.equal(page.data.textSectionForm.backgroundLoading, true)
  pending[0].resolve({ work: { id: 99, mediaType: 'ANIMATION', mediaUrl: 'original.gif', coverUrl: 'still.jpg', width: 800, height: 1200, auditStatus: 'PASSED', status: 'ACTIVE' } })
  await first
  assert.equal(page.data.textSectionForm.backgroundWork.url, 'original.gif')
  assert.equal(page.data.textSectionForm.backgroundLoading, false)
  page.handleCloseTextSectionSheet()
  const missing = page.openTextSectionSheet(item.componentKey)
  pending[1].resolve({ work: null })
  await missing
  assert.equal(page.data.textSectionForm.backgroundInvalid, true)
  assert.equal(page.data.textSectionForm.backgroundWorkId, 99)
  assert.equal(item.config.backgroundInvalid, undefined)
  page.handleCloseTextSectionSheet()
  const old = page.openTextSectionSheet(item.componentKey)
  page.handleCloseTextSectionSheet()
  page.openStructuredTextSheet()
  pending[2].resolve({ work: null })
  await old
  assert.deepEqual(page.data.textBackgroundSelection, {})
})

test('异步恢复结构化背景只更新等待中的匹配资源，不覆盖文字或重选作品', () => {
  const editor = harness('components/structured-text-editor/structured-text-editor')
  editor.properties = { config: { backgroundEnabled: true, backgroundWorkId: 5, backgroundLoading: true, blocks: [block('a')] } }
  editor.beginSession()
  editor.data.draft.blocks[0].content = '会话文字'
  editor.applyBackgroundSelection({ workId: 5, work: { workId: 5, mediaType: 'IMAGE', url: 'restored', width: 100, height: 200 }, error: '' })
  assert.equal(editor.data.draft.blocks[0].content, '会话文字')
  assert.equal(editor.data.draft.backgroundWork.url, 'restored')
  editor.handleBackgroundChange({ detail: { ...editor.data.draft, backgroundWorkId: 6, backgroundWork: { workId: 6, mediaType: 'IMAGE', url: 'new' }, backgroundLoading: false } })
  editor.applyBackgroundSelection({ workId: 5, work: null, error: '加载失败' })
  assert.equal(editor.data.draft.backgroundWorkId, 6)
  assert.equal(editor.data.draft.backgroundWork.url, 'new')
})

test('当前背景详情401执行维护者登录处理，取消后的401忽略且不伪装为背景失效', async () => {
  const utils = require('../utils/portfolios')
  const previousWx = global.wx
  const redirects = []
  const removed = []
  global.wx = { showToast() {}, redirectTo: params => redirects.push(params.url), removeStorageSync: key => removed.push(key) }
  try {
    for (const structured of [false, true]) {
      const pending = []
      const page = harness('standard-edit/portfolio-standard-edit', false, () => new Promise((resolve, reject) => pending.push({ reject })))
      page.data.config = utils.addComponent(page.data.config, structured ? 'STRUCTURED_TEXT_SECTION' : 'TEXT_SECTION')
      const component = page.data.config.components.at(-1)
      component.config = { content: '说明', blocks: [block('a')], backgroundEnabled: true, backgroundWorkId: 7 }
      const open = () => structured ? page.openStructuredTextSheet(component.componentKey) : page.openTextSectionSheet(component.componentKey)
      const close = () => structured ? page.handleCancelStructuredText() : page.handleCloseTextSectionSheet()
      const request = open()
      const count = redirects.length
      pending[0].reject({ authRequired: true, message: '登录已过期，请重新登录' })
      await request
      assert.equal(redirects.length, count + 1)
      assert.equal(redirects.at(-1), '/pages/login/login')
      assert.equal(page.data.textBackgroundSelection.work, undefined)
      assert.equal(page.data.textSectionForm.backgroundInvalid, undefined)
      const expired = open()
      close()
      pending[1].reject({ authRequired: true, message: '过期请求401' })
      await expired
      assert.equal(redirects.length, count + 1)
    }
    assert.equal(removed.length, 2)
  } finally { global.wx = previousWx }
})

function createBackgroundRetryHarness(structured) {
  const pending = []
  const page = harness('standard-edit/portfolio-standard-edit', false, params => new Promise((resolve, reject) => pending.push({ params, resolve, reject })))
  const utils = require('../utils/portfolios')
  page.data.config = utils.addComponent(page.data.config, structured ? 'STRUCTURED_TEXT_SECTION' : 'TEXT_SECTION')
  const component = page.data.config.components.at(-1)
  component.config = { content: '初始说明', blocks: [block('a'), block('b')], backgroundEnabled: true, backgroundWorkId: 7 }
  const initial = structured ? page.openStructuredTextSheet(component.componentKey) : page.openTextSectionSheet(component.componentKey)
  let editor
  if (structured) {
    editor = harness('components/structured-text-editor/structured-text-editor')
    editor.properties = { config: page.data.structuredTextConfig }
    editor.beginSession()
    const setPageData = page.setData.bind(page)
    page.setData = patch => {
      setPageData(patch)
      if (patch.textBackgroundSelection) editor.applyBackgroundSelection(patch.textBackgroundSelection)
    }
    const emit = editor.triggerEvent.bind(editor)
    editor.triggerEvent = (name, detail) => {
      emit(name, detail)
      if (name === 'backgroundchange') page.handleStructuredTextBackgroundChange({ detail })
    }
  }
  const current = () => structured ? editor.data.draft : page.data.textSectionForm
  const change = detail => structured ? editor.handleBackgroundChange({ detail }) : page.handleTextBackgroundChange({ detail })
  const retry = () => {
    const picker = harness('components/text-background-editor/text-background-editor')
    picker.properties = { config: current(), structured }
    picker.handleRetrySelected()
    const request = picker.events.find(event => event.name === 'request')
    assert.equal(request.detail.restore, true)
    assert.equal(request.detail.workId, 7)
    if (structured) {
      editor.handleBackgroundRequest(request)
      const forwarded = editor.events.at(-1)
      return page.handleTextBackgroundRequest({ detail: forwarded.detail })
    }
    return page.handleTextBackgroundRequest(request)
  }
  return { page, editor, pending, initial, current, change, retry }
}

const restoredWork = url => ({ work: { id: 7, mediaType: 'IMAGE', mediaUrl: url, status: 'ACTIVE', auditStatus: 'PENDING', width: 750, height: 1000 } })

test('普通与结构化背景可原地重试，保留说明、区块排序与未保存详情并忽略重试乱序', async () => {
  for (const structured of [false, true]) {
    const context = createBackgroundRetryHarness(structured)
    context.pending[0].reject(new Error('暂时无法连接'))
    await context.initial
    assert.equal(context.current().backgroundInvalid, true)
    if (structured) {
      context.editor.reorderBlock('a', 'b')
      context.editor.handleEditBlock(evt({ key: 'a' }))
      context.editor.handleBlockInput(evt({ field: 'content' }, '未保存的详情'))
    } else context.page.handleTextSectionInput(evt({}, '未保存的说明'))
    const editing = JSON.stringify(structured ? context.editor.data.blockDraft : context.current().content)
    const retryOne = context.retry()
    assert.equal(context.current().backgroundLoading, true)
    assert.equal(context.pending[1].params.url, '/api/mine/works/7')
    const retryTwo = context.retry()
    assert.equal(context.pending[2].params.url, '/api/mine/works/7')
    context.pending[1].resolve(restoredWork('outdated.jpg'))
    await retryOne
    assert.equal(context.current().backgroundLoading, true)
    assert.equal(context.current().backgroundWork, null)
    context.pending[2].resolve(restoredWork('current.jpg'))
    await retryTwo
    assert.equal(context.current().backgroundLoading, false)
    assert.equal(context.current().backgroundInvalid, false)
    assert.equal(context.current().backgroundWork.url, 'current.jpg')
    assert.equal(JSON.stringify(structured ? context.editor.data.blockDraft : context.current().content), editing)
    if (structured) assert.deepEqual(context.current().blocks.map(item => item.blockKey), ['b', 'a'])
    assert.equal(context.page.data.config.components.at(-1).config.backgroundWork, undefined)
  }
})

test('重试后重新选择同ID、关闭背景、取消或卸载均阻止迟到背景回写', async () => {
  for (const structured of [false, true]) {
    for (const action of ['select', 'disable', 'cancel', 'unload']) {
      const context = createBackgroundRetryHarness(structured)
      context.pending[0].reject(new Error('失败'))
      await context.initial
      const retry = context.retry()
      if (action === 'select') context.change({ ...context.current(), backgroundLoading: false, backgroundInvalid: false,
        backgroundWork: { workId: 7, mediaType: 'IMAGE', url: 'new-selection.jpg' } })
      if (action === 'disable') context.change({ ...context.current(), backgroundEnabled: false })
      if (action === 'cancel') {
        if (structured) context.page.handleCancelStructuredText()
        else context.page.handleCloseTextSectionSheet()
      }
      if (action === 'unload') context.page.onUnload()
      const snapshot = JSON.stringify(context.current())
      context.pending[1].resolve(restoredWork('late.jpg'))
      await retry
      assert.equal(JSON.stringify(context.current()), snapshot, `${structured}/${action}`)
    }
  }
})

test('同会话较早重试的401不终止更新的有效恢复', async () => {
  const context = createBackgroundRetryHarness(true)
  context.pending[0].reject(new Error('失败'))
  await context.initial
  const older = context.retry()
  const current = context.retry()
  const previousWx = global.wx
  const redirects = []
  global.wx = { showToast() {}, removeStorageSync() {}, redirectTo: params => redirects.push(params.url) }
  try {
    context.pending[1].reject({ authRequired: true, message: '旧请求401' })
    await older
    assert.deepEqual(redirects, [])
    context.pending[2].resolve(restoredWork('latest.jpg'))
    await current
    assert.equal(context.current().backgroundWork.url, 'latest.jpg')
  } finally { global.wx = previousWx }
})

test('恢复期间关闭背景取消等待，再开启可以重试且保留作品ID', () => {
  const picker = harness('components/text-background-editor/text-background-editor')
  picker.properties = { config: { backgroundEnabled: true, backgroundWorkId: 7, backgroundLoading: true } }
  picker.handleToggle(evt({}, false))
  const disabled = picker.events.at(-1).detail
  assert.equal(disabled.backgroundLoading, false)
  assert.equal(disabled.backgroundWorkId, 7)
  picker.properties.config = disabled
  picker.handleToggle(evt({}, true))
  const enabled = picker.events.at(-1).detail
  assert.equal(enabled.backgroundLoading, false)
  assert.equal(enabled.backgroundInvalid, true)
  assert.ok(enabled.backgroundLoadError)
  picker.properties.config = enabled
  picker.handleRetrySelected()
  assert.deepEqual(picker.events.at(-1), { name: 'request', detail: { restore: true, workId: 7 } })
})

const candidatePage = (page = 1) => ({ page, hasMore: true, works: [
  { id: 7, mediaType: 'IMAGE', mediaUrl: 'candidate-7.jpg', auditStatus: 'PASSED', status: 'ACTIVE', width: 750, height: 1000 },
  { id: 8, mediaType: 'IMAGE', mediaUrl: 'candidate-8.jpg', auditStatus: 'PASSED', status: 'ACTIVE', width: 750, height: 1000 }
] })

function connectCandidatePicker(context) {
  const picker = harness('components/text-background-editor/text-background-editor')
  picker.requestPromises = []
  picker.properties = { config: context.current(), options: context.page.data.textBackgroundOptions, structured: !!context.editor }
  const emit = picker.triggerEvent.bind(picker)
  picker.triggerEvent = (name, detail) => {
    emit(name, detail)
    if (name === 'change') context.change(detail)
    if (name === 'request') {
      if (context.editor) {
        context.editor.handleBackgroundRequest({ detail })
        picker.requestPromises.push(context.page.handleTextBackgroundRequest(context.editor.events.at(-1)))
      } else picker.requestPromises.push(context.page.handleTextBackgroundRequest({ detail }))
    }
  }
  return picker
}

async function prepareCandidatePagination(context) {
  context.pending[0].resolve(restoredWork('original.jpg'))
  await context.initial
  const firstPage = context.page.handleTextBackgroundRequest({ detail: { reset: true } })
  context.pending[1].resolve(candidatePage())
  await firstPage
  return { pagination: context.page.handleTextBackgroundRequest({ detail: { reset: false } }) }
}

test('候选分页在普通或结构化关闭背景及重选后忽略迟到成功、失败和401', async () => {
  const previousWx = global.wx
  const redirects = []
  global.wx = { showToast() {}, removeStorageSync() {}, redirectTo: params => redirects.push(params.url) }
  const cases = [false, true].flatMap(structured => ['disable', 'select-same', 'select-new'].flatMap(action =>
    ['success', 'failure', '401'].map(outcome => ({ structured, action, outcome }))))
  try {
    for (const scenario of cases) {
      const context = createBackgroundRetryHarness(scenario.structured)
      const { pagination } = await prepareCandidatePagination(context)
      assert.equal(context.pending[2].params.url, '/api/mine/works')
      assert.equal(context.pending[2].params.data.page, 2)
      const picker = connectCandidatePicker(context)
      if (scenario.action === 'disable') picker.handleToggle(evt({}, false))
      else picker.handleSelect(evt({ id: scenario.action === 'select-same' ? 7 : 8 }))
      assert.equal(context.page.data.textBackgroundLoading, false, JSON.stringify(scenario))
      assert.equal(context.page.data.textBackgroundError, '')
      const before = JSON.stringify(context.page.data)
      if (scenario.outcome === 'success') context.pending[2].resolve({ ...candidatePage(2), works: [{ id: 9, mediaType: 'IMAGE', mediaUrl: 'late.jpg' }] })
      else context.pending[2].reject(scenario.outcome === '401' ? { authRequired: true, message: '旧候选401' } : new Error('旧候选失败'))
      await pagination
      assert.equal(JSON.stringify(context.page.data), before, JSON.stringify(scenario))
      assert.equal(Object.prototype.hasOwnProperty.call(context.current(), 'cancelCandidates'), false)
    }
    assert.deepEqual(redirects, [])
  } finally { global.wx = previousWx }
})

test('当前有效的普通和结构化候选分页401仍按维护者登录处理', async () => {
  const previousWx = global.wx
  const redirects = []
  global.wx = { showToast() {}, removeStorageSync() {}, redirectTo: params => redirects.push(params.url) }
  try {
    for (const structured of [false, true]) {
      const context = createBackgroundRetryHarness(structured)
      const { pagination } = await prepareCandidatePagination(context)
      context.pending[2].reject({ authRequired: true, message: '当前候选401' })
      await pagination
    }
    assert.deepEqual(redirects, ['/pages/login/login', '/pages/login/login'])
  } finally { global.wx = previousWx }
})

test('仅修改背景处理及上下对齐不取消当前候选列表或已选背景恢复', async () => {
  for (const structured of [false, true]) {
    const context = createBackgroundRetryHarness(structured)
    const candidates = context.page.handleTextBackgroundRequest({ detail: { reset: true } })
    const picker = connectCandidatePicker(context)
    picker.handleOption(evt({ field: 'backgroundTreatment', value: 'DARK_MASK' }))
    if (!structured) {
      picker.properties.config = context.current()
      picker.handleOption(evt({ field: 'verticalAlignment', value: 'BOTTOM' }))
    }
    assert.equal(context.current().backgroundLoading, true)
    assert.equal(context.page.data.textBackgroundLoading, true)
    context.pending[0].resolve(restoredWork('restored.jpg'))
    await context.initial
    context.pending[1].resolve(candidatePage())
    await candidates
    assert.equal(context.current().backgroundWork.url, 'restored.jpg')
    assert.equal(context.current().backgroundTreatment, 'DARK_MASK')
    if (!structured) assert.equal(context.current().verticalAlignment, 'BOTTOM')
    assert.equal(context.page.data.textBackgroundOptions.length, 2)
    assert.equal(context.page.data.textBackgroundLoading, false)
  }
})

test('取消新关键词第一页后关闭选择器，再次打开从第一页查询且不复用旧游标', async () => {
  for (const structured of [false, true]) {
    const context = createBackgroundRetryHarness(structured)
    const { pagination } = await prepareCandidatePagination(context)
    context.pending[2].resolve({ ...candidatePage(2), works: [{ id: 9, mediaType: 'IMAGE', mediaUrl: 'old-page-two.jpg' }] })
    await pagination
    assert.equal(context.page.textBackgroundPage, 2)
    if (structured) {
      context.editor.reorderBlock('a', 'b')
      context.editor.handleEditBlock(evt({ key: 'a' }))
      context.editor.handleBlockInput(evt({ field: 'content' }, '待保存的详情'))
    } else context.page.handleTextSectionInput(evt({}, '待保存的说明'))
    const beforeBlocks = JSON.stringify(context.current().blocks)
    const beforeDetail = JSON.stringify(structured ? context.editor.data.blockDraft : context.current().content)
    const treatment = context.current().backgroundTreatment
    const picker = connectCandidatePicker(context)
    picker.setData({ pickerOpen: true, keyword: '新关键词' })
    picker.handleSearch()
    const search = picker.requestPromises.at(-1)
    assert.equal(context.pending[3].params.data.page, 1)
    assert.equal(context.pending[3].params.data.keyword, '新关键词')
    picker.handleToggle(evt({}, false))
    assert.equal(picker.data.pickerOpen, false)
    assert.equal(context.page.textBackgroundPage, 0)
    assert.equal(context.page.data.textBackgroundHasMore, false)
    picker.properties.config = context.current()
    picker.handleToggle(evt({}, true))
    picker.properties.loading = context.page.data.textBackgroundLoading
    picker.properties.hasMore = context.page.data.textBackgroundHasMore
    picker.handleMore()
    assert.equal(context.pending.length, 4)
    picker.handleOpenPicker()
    const reopened = picker.requestPromises.at(-1)
    assert.equal(context.pending[4].params.data.page, 1)
    context.pending[3].resolve({ ...candidatePage(), works: [{ id: 99, mediaType: 'IMAGE', mediaUrl: 'stale-search.jpg' }] })
    await search
    assert.equal(context.page.data.textBackgroundLoading, true)
    assert.deepEqual(context.page.data.textBackgroundOptions, [])
    context.pending[4].resolve(candidatePage())
    await reopened
    assert.equal(context.page.textBackgroundPage, 1)
    assert.equal(context.current().backgroundWorkId, 7)
    assert.equal(context.current().backgroundWork.url, 'original.jpg')
    assert.equal(context.current().backgroundTreatment, treatment)
    assert.equal(JSON.stringify(context.current().blocks), beforeBlocks)
    assert.equal(JSON.stringify(structured ? context.editor.data.blockDraft : context.current().content), beforeDetail)
  }
})

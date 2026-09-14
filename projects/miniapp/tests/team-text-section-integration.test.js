const test = require('node:test')
const assert = require('node:assert/strict')
const base = '../pages/team-portfolios/'
function harness(file, component = true, requestFn) {
  let definition
  const register = component ? 'Component' : 'Page'
  const previousRegister = global[register]
  global[register] = value => { definition = value }
  const target = require.resolve(base + file)
  const requestPath = require.resolve('../utils/request')
  const requestExports = require(requestPath)
  const originalRequest = require.cache[requestPath]
  if (requestFn) require.cache[requestPath] = { id: requestPath, filename: requestPath, loaded: true, exports: Object.assign({}, requestExports, { request: requestFn }) }
  delete require.cache[target]
  try { require(target) } finally {
    if (previousRegister) global[register] = previousRegister
    else delete global[register]
    if (requestFn) {
      if (originalRequest) require.cache[requestPath] = originalRequest
      else delete require.cache[requestPath]
    }
  }
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
const pageHarness = request => harness('standard-edit/team-portfolio-standard-edit', false, request)

test('团队普通文字行距缺省不落盘，非法输入阻止保存，取消与重开保留已存值', t => {
  const previousWx = global.wx, toasts = []
  global.wx = { showToast(value) { toasts.push(value) } }
  t.after(() => { global.wx = previousWx })
  const page = pageHarness()
  const utils = require('../pages/team-portfolios/utils/team-portfolios')
  page.data.canMaintain = true
  page.data.config = utils.addTeamComponent(page.data.config, 'TEXT_SECTION')
  const key = page.data.config.components.at(-1).componentKey
  page.openTextSectionSheet(key)
  page.handleTextSectionInput(evt({}, '第一行\n第二行'))
  page.handleConfirmTextSectionConfig()
  assert.equal(Object.hasOwn(page.data.config.components.at(-1).config, 'lineHeight'), false)
  page.openTextSectionSheet(key)
  page.handleTextSectionLineHeightStep(evt({ delta: -0.1 }))
  assert.equal(page.data.textSectionForm.lineHeight, 1.7)
  page.handleCloseTextSectionSheet()
  assert.equal(Object.hasOwn(page.data.config.components.at(-1).config, 'lineHeight'), false)
  page.openTextSectionSheet(key)
  for (const value of ['', '0.4', '3.1', '1.75']) {
    page.handleTextSectionLineHeightInput(evt({}, value))
    page.handleConfirmTextSectionConfig()
    assert.equal(page.data.textSectionSheetVisible, true)
    assert.match(toasts.at(-1).title, /行间距/)
  }
  for (const value of ['0.5', '3.0']) {
    page.handleTextSectionLineHeightInput(evt({}, value))
    page.handleConfirmTextSectionConfig()
    assert.equal(page.data.config.components.at(-1).config.lineHeight, Number(value))
    page.openTextSectionSheet(key)
    assert.equal(page.data.textSectionForm.lineHeight, Number(value))
  }
})

test('团队普通文字颜色缺省AUTO，局部取消不提交，完成与重开保留颜色', () => {
  const page = pageHarness()
  const utils = require('../pages/team-portfolios/utils/team-portfolios')
  page.data.canMaintain = true
  page.data.config = utils.addTeamComponent(page.data.config, 'TEXT_SECTION')
  const key = page.data.config.components.at(-1).componentKey
  page.openTextSectionSheet(key)
  assert.equal(page.data.textSectionForm.color, 'AUTO')
  page.handleTextSectionColorChange({ detail: { color: '#ab12cd' } })
  page.handleCloseTextSectionSheet()
  page.handleTextSectionColorChange({ detail: { color: '#FFFFFF' } })
  page.openTextSectionSheet(key)
  assert.equal(page.data.textSectionForm.color, 'AUTO')
  page.handleTextSectionInput(evt({}, '正文'))
  page.handleTextSectionColorChange({ detail: { color: '#ab12cd' } })
  page.handleConfirmTextSectionConfig()
  assert.equal(page.data.config.components.at(-1).config.color, '#AB12CD')
  page.openTextSectionSheet(key)
  assert.equal(page.data.textSectionForm.color, '#AB12CD')
  page.handleCloseTextSectionSheet()
})

test('团队新增结构化文字完成前不进入页面，取消丢弃草稿，完成才插入当前菜单', () => {
  const page = pageHarness()
  const before = JSON.stringify(page.data.config)
  page.handleSelectComponent(evt({ type: 'STRUCTURED_TEXT_SECTION' }))
  assert.equal(JSON.stringify(page.data.config), before)
  assert.equal(page.data.structuredTextSheetVisible, true)
  page.handleCancelStructuredText()
  assert.equal(JSON.stringify(page.data.config), before)
  page.openStructuredTextSheet()
  page.handleConfirmStructuredText({ detail: { blocks: [block('a')] } })
  const item = page.data.config.components.at(-1)
  assert.equal(item.componentType, 'STRUCTURED_TEXT_SECTION')
  assert.equal(item.config.blocks[0].color, 'AUTO')
  assert.equal(page.data.componentValidation[item.componentKey], true)
})

test('团队区块局部取消和整体取消均隔离原配置，保存详情、排序、删除只改编辑副本', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const editor = harness('components/structured-text-editor/structured-text-editor')
  const original = { blocks: [block('a'), block('b')], backgroundEnabled: true, backgroundMemberUserId: 9, backgroundWorkId: 7 }
  editor.properties = { visible: true, config: original }
  editor.beginSession()
  editor.handleEditBlock(evt({ key: 'a' }))
  editor.handleBlockInput(evt({ field: 'content' }, '未保存'))
  editor.handleCancelBlock()
  t.mock.timers.tick(200)
  assert.equal(editor.data.draft.blocks[0].content, 'a')
  editor.handleEditBlock(evt({ key: 'a' }))
  editor.handleBlockInput(evt({ field: 'content' }, '已保存'))
  editor.handleSaveBlock()
  t.mock.timers.tick(200)
  assert.equal(editor.data.draft.blocks[0].content, '已保存')
  editor.reorderBlock('a', 'b')
  assert.deepEqual(editor.data.draft.blocks.map(item => item.blockKey), ['b', 'a'])
  editor.handleDeleteBlock(evt({ key: 'b' }))
  editor.handleCancel()
  assert.deepEqual(original, { blocks: [block('a'), block('b')], backgroundEnabled: true, backgroundMemberUserId: 9, backgroundWorkId: 7 })
  assert.equal(editor.events.at(-1).name, 'cancel')
})

test('团队普通背景取消不改变原配置，确认关闭清空作品和成员但保留处理和垂直偏好', () => {
  const page = pageHarness()
  const key = page.addComponent('TEXT_SECTION')
  const item = page.data.config.components.at(-1)
  Object.assign(item.config, { content: '原文字', backgroundEnabled: true, backgroundMemberUserId: 9, backgroundWorkId: 7, backgroundTreatment: 'ORIGINAL', verticalAlignment: 'BOTTOM' })
  const before = JSON.stringify(page.data.config)
  page.openTextSectionSheet(key)
  page.handleTextBackgroundChange({ detail: { ...page.data.textSectionForm, backgroundEnabled: false } })
  page.handleCloseTextSectionSheet()
  assert.equal(JSON.stringify(page.data.config), before)
  page.openTextSectionSheet(key)
  page.handleTextBackgroundChange({ detail: { ...page.data.textSectionForm, backgroundEnabled: false } })
  page.handleConfirmTextSectionConfig()
  const saved = page.data.config.components.at(-1).config
  assert.equal(saved.backgroundWorkId, undefined)
  assert.equal(saved.backgroundMemberUserId, undefined)
  assert.equal(saved.backgroundTreatment, 'ORIGINAL')
  assert.equal(saved.verticalAlignment, 'BOTTOM')
  assert.equal(saved.fontSizeRpx, 28)
})

test('团队背景选择始终保存作品实际成员，切换浏览成员不能篡改已选作品归属', () => {
  const editor = harness('components/text-background-editor/text-background-editor')
  editor.properties = { config: { backgroundEnabled: true, backgroundMemberUserId: 9, backgroundWorkId: 7 }, members: [{ memberUserId: 9 }, { memberUserId: 10 }], memberUserId: 9, options: [] }
  editor.handleMember(evt({ id: 10 }))
  assert.equal(editor.events.at(-1).name, 'request')
  assert.equal(editor.events.at(-1).detail.memberUserId, 10)
  assert.equal(editor.properties.config.backgroundMemberUserId, 9)
  editor.properties.options = [{ id: 8, memberUserId: 10, mediaType: 'ANIMATION', url: 'https://example.test/raw.gif', width: 750, height: 900 }]
  editor.handleSelect(evt({ id: 8 }))
  const selected = editor.events.find(event => event.name === 'change').detail
  assert.equal(selected.backgroundMemberUserId, 10)
  assert.equal(selected.backgroundWorkId, 8)
  assert.equal(selected.backgroundWork.url, 'https://example.test/raw.gif')
  assert.equal(selected.backgroundInvalid, false)
})

test('团队普通展示保留文字与原比例动图，加载失败只显示修复提示，不改配置', () => {
  const view = harness('components/text-section/text-section')
  const config = { content: '  原文字\n第二行', alignment: 'RIGHT', backgroundEnabled: true, verticalAlignment: 'BOTTOM', backgroundWorkId: 7, backgroundWork: { workId: 7, mediaType: 'ANIMATION', url: 'https://example.test/raw.gif', width: 750, height: 1000 } }
  view.properties = { config, repairMode: true }
  view.updatePresentation()
  assert.equal(view.data.normalizedConfig.content, '  原文字\n第二行')
  assert.equal(view.data.background.imageUrl, 'https://example.test/raw.gif')
  assert.equal(view.data.frameStyle, 'min-height:1000rpx;')
  assert.equal(view.data.verticalClass, 'vertical-BOTTOM')
  view.handleBackgroundError()
  assert.equal(view.data.showRepairHint, true)
  assert.equal(config.backgroundWorkId, 7)
  view.properties.config = { ...config, backgroundEnabled: false }
  view.updatePresentation()
  assert.equal(view.data.frameStyle, '')
  assert.equal(view.data.background.enabled, false)
})

test('团队背景恢复已选跨页动图，成员无权限时保留 ID 标记失效，关闭后的旧请求不能回写', async () => {
  const pending = []
  const page = pageHarness(params => new Promise((resolve, reject) => pending.push({ params, resolve, reject })))
  page.data.teamId = 12
  const key = page.addComponent('TEXT_SECTION')
  Object.assign(page.data.config.components.at(-1).config, { content: '文字', backgroundEnabled: true, backgroundMemberUserId: 9, backgroundWorkId: 77 })
  page.openTextSectionSheet(key)
  assert.match(pending[0].params.url, /single-work\/members$/)
  pending[0].resolve([{ memberUserId: 9, displayName: '成员甲' }])
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(pending[1].params.data.selectedWorkId, 77)
  pending[1].resolve({ works: [], selectedWork: { workId: 77, mediaType: 'ANIMATION', mediaUrl: 'https://example.test/selected.gif', coverUrl: 'static.jpg', width: 750, height: 1000 }, page: 1, pageSize: 20, hasMore: false })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(page.data.textSectionForm.backgroundWork.url, 'https://example.test/selected.gif')
  assert.equal(page.data.textSectionForm.backgroundMemberUserId, 9)
  page.handleCloseTextSectionSheet()
  page.openTextSectionSheet(key)
  pending[2].resolve([])
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(page.data.textSectionForm.backgroundInvalid, true)
  assert.equal(page.data.textSectionForm.backgroundWorkId, 77)
  page.handleCloseTextSectionSheet()
  page.openTextSectionSheet(key)
  const old = pending[3]
  page.handleCloseTextSectionSheet()
  page.openStructuredTextSheet()
  old.resolve([{ memberUserId: 9, displayName: '旧成员' }])
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(page.data.textBackgroundMembers, [])
  assert.equal(page.data.textBackgroundResource.backgroundWorkId, undefined)
})

test('团队作品搜索跨过音频和不匹配页面，切换成员的旧响应不能污染当前候选', async () => {
  const pending = []
  const page = pageHarness(params => new Promise((resolve, reject) => pending.push({ params, resolve, reject })))
  page.data.teamId = 12
  page.openStructuredTextSheet()
  const first = page.handleTextBackgroundRequest({ detail: { keyword: '婚礼' } })
  pending[0].resolve([{ memberUserId: 9, displayName: '甲' }, { memberUserId: 10, displayName: '乙' }])
  await new Promise(resolve => setImmediate(resolve))
  pending[1].resolve({ works: [{ workId: 1, title: '婚礼', mediaType: 'AUDIO', mediaUrl: 'audio' }, { workId: 2, title: '风景', mediaType: 'IMAGE', mediaUrl: 'image' }], page: 1, hasMore: true })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(pending[2].params.data.page, 2)
  pending[2].resolve({ works: [{ workId: 3, title: '婚礼动图', mediaType: 'ANIMATION', mediaUrl: 'https://example.test/raw.gif', coverUrl: 'static', width: 900, height: 1600 }], page: 2, hasMore: true })
  await first
  assert.equal(page.data.textBackgroundOptions[0].id, 3)
  assert.equal(page.data.textBackgroundOptions[0].memberUserId, 9)
  const old = page.handleTextBackgroundRequest({ detail: { reset: false } })
  await new Promise(resolve => setImmediate(resolve))
  const next = page.handleTextBackgroundRequest({ detail: { reset: true, memberUserId: 10, keyword: '' } })
  await new Promise(resolve => setImmediate(resolve))
  pending[3].resolve({ works: [{ workId: 4, mediaType: 'IMAGE', mediaUrl: 'old' }], page: 3, hasMore: false })
  await old
  assert.equal(page.data.textBackgroundMemberUserId, 10)
  assert.deepEqual(page.data.textBackgroundOptions, [])
  pending[4].resolve({ works: [{ workId: 5, mediaType: 'IMAGE', mediaUrl: 'new' }], page: 1, hasMore: false })
  await next
  assert.equal(page.data.textBackgroundOptions[0].id, 5)
  assert.equal(page.data.textBackgroundOptions[0].memberUserId, 10)
})

test('团队结构化背景恢复失败后可重试，旧恢复不能覆盖本次新选作品或不同成员归属', () => {
  const editor = harness('components/structured-text-editor/structured-text-editor')
  editor.properties = { visible: true, config: { blocks: [block('a')], backgroundEnabled: true, backgroundMemberUserId: 9, backgroundWorkId: 7, backgroundLoading: true } }
  editor.beginSession()
  editor.applyBackgroundResource({ backgroundMemberUserId: 9, backgroundWorkId: 7, backgroundWork: null, backgroundLoading: false, backgroundLoadError: '失败' })
  const restored = { backgroundMemberUserId: 9, backgroundWorkId: 7, backgroundWork: { workId: 7, mediaType: 'IMAGE', url: 'https://example.test/a.jpg' }, backgroundLoading: false, backgroundInvalid: false, backgroundLoadError: '' }
  editor.applyBackgroundResource(restored)
  assert.equal(editor.data.draft.backgroundWork.url, 'https://example.test/a.jpg')
  editor.handleBackgroundChange({ detail: { backgroundMemberUserId: 10, backgroundWorkId: 8, backgroundWork: { workId: 8, mediaType: 'ANIMATION', url: 'https://example.test/new.gif' }, backgroundLoading: false, backgroundInvalid: false, backgroundLoadError: '' } })
  editor.applyBackgroundResource(restored)
  assert.equal(editor.data.draft.backgroundWorkId, 8)
  assert.equal(editor.data.draft.backgroundMemberUserId, 10)
  editor.data.draft.backgroundLoading = true
  editor.applyBackgroundResource({ ...restored, backgroundWorkId: 8, backgroundMemberUserId: 9 })
  assert.equal(editor.data.draft.backgroundMemberUserId, 10)
  assert.equal(editor.data.draft.backgroundWork.url, 'https://example.test/new.gif')
})

test('团队普通背景来源网络失败可重试恢复，页面配置未完成始终不被异步查询写入', async () => {
  let attempts = 0
  const page = pageHarness(async ({ url }) => {
    if (url.endsWith('/members')) return [{ memberUserId: 9, displayName: '甲' }]
    attempts += 1
    if (attempts === 1) throw new Error('网络断开')
    return { works: [], selectedWork: { workId: 7, mediaType: 'IMAGE', mediaUrl: 'https://example.test/retry.jpg' }, page: 1, hasMore: false }
  })
  page.data.teamId = 12
  const key = page.addComponent('TEXT_SECTION')
  Object.assign(page.data.config.components.at(-1).config, { content: '文字', backgroundEnabled: true, backgroundMemberUserId: 9, backgroundWorkId: 7 })
  const before = JSON.stringify(page.data.config)
  page.openTextSectionSheet(key)
  await new Promise(resolve => setImmediate(resolve))
  assert.ok(page.data.textSectionForm.backgroundLoadError)
  await page.handleTextBackgroundRequest({ detail: { restoreSelection: true } })
  assert.equal(page.data.textSectionForm.backgroundWork.url, 'https://example.test/retry.jpg')
  assert.equal(page.data.textSectionForm.backgroundLoadError, '')
  assert.equal(JSON.stringify(page.data.config), before)
})

for (const prefix of ['', '../portfolios/']) {
  test(`${prefix ? '个人' : '团队'}结构化编辑器独立样式、间距步进与手势遵循同一交互契约`, t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const editor = harness(prefix + 'components/structured-text-editor/structured-text-editor')
    editor.properties = { visible: true, config: { blocks: [block('a'), block('b')] } }
    editor.beginSession()
    editor.handleEditBlock(evt({ key: 'a' }))
    editor.handleBlockOption(evt({ field: 'fontSizeRpx', value: 56 }))
    editor.handleBlockOption(evt({ field: 'fontWeight', value: 'NORMAL' }))
    editor.handleBlockOption(evt({ field: 'color', value: '#FFFFFF' }))
    editor.handleBlockOption(evt({ field: 'alignment', value: 'RIGHT' }))
    editor.handleSpacingInput(evt({ field: 'marginTopRpx' }, 27))
    editor.handleSpacingStep(evt({ field: 'marginBottomRpx', delta: -4 }))
    editor.handleSaveBlock()
    t.mock.timers.tick(200)
    const saved = editor.data.draft.blocks[0]
    assert.deepEqual([saved.fontSizeRpx, saved.fontWeight, saved.color, saved.alignment, saved.marginTopRpx, saved.marginBottomRpx], [56, 'NORMAL', '#FFFFFF', 'RIGHT', 28, 12])
    const touch = (key, x, y) => ({ currentTarget: { dataset: { key } }, changedTouches: [{ clientX: x, clientY: y }], touches: [{ clientX: x, clientY: y }] })
    editor.handleRowTouchStart(touch('a', 100, 0))
    editor.handleRowTouchEnd(touch('a', 10, 4))
    assert.equal(editor.data.revealedKey, 'a')
    assert.equal(editor.data.draft.blocks.length, 2)
    editor.handleRowTouchStart(touch('b', 100, 0))
    editor.handleRowTouchEnd(touch('b', 10, 4))
    assert.equal(editor.data.revealedKey, 'b')
    editor.handleRowTouchStart(touch('b', 10, 0))
    editor.handleRowTouchEnd(touch('b', 100, 4))
    assert.equal(editor.data.revealedKey, '')
    editor.handleRowTouchStart(touch('a', 100, 0))
    editor.handleRowTouchEnd(touch('a', 60, 140))
    assert.equal(editor.data.revealedKey, '')
    editor.createSelectorQuery = () => ({ selectAll: () => ({ boundingClientRect: fn => { fn([{ top: 0, bottom: 60 }, { top: 70, bottom: 130 }]); return { exec() {} } } }) })
    editor.handleDragStart(touch('a', 0, 20))
    editor.handleDragMove(touch('a', 0, 100))
    editor.handleDragEnd()
    assert.deepEqual(editor.data.draft.blocks.map(item => item.blockKey), ['b', 'a'])
    assert.equal(editor.data.draft.blocks[1].fontSizeRpx, 56)
    editor.handleEditBlock(evt({ key: 'a' }))
    editor.handleSpacingInput(evt({ field: 'marginTopRpx' }, 200))
    assert.equal(editor.data.blockDraft.block.marginTopRpx, 128)
    editor.handleSpacingStep(evt({ field: 'marginTopRpx', delta: 4 }))
    assert.equal(editor.data.blockDraft.block.marginTopRpx, 128)
    editor.handleSpacingInput(evt({ field: 'marginBottomRpx' }, -9))
    assert.equal(editor.data.blockDraft.block.marginBottomRpx, 0)
    editor.handleBlockInput(evt({ field: 'content' }, '甲\n乙'))
    editor.handleBlockType(evt({ value: 'LIST' }))
    assert.deepEqual(editor.data.blockDraft.block.items, ['甲', '乙'])
    editor.handleBlockType(evt({ value: 'SPACER' }))
    editor.handleBlockType(evt({ value: 'TITLE' }))
    assert.equal(editor.data.blockDraft.block.content, '甲\n乙')
    assert.equal(editor.data.blockDraft.block.fontSizeRpx, 56)
  })
}

test('团队背景当前401遵循既有退出登录流程，取消或卸载后的迟到401不影响新页面', async () => {
  const previousWx = global.wx
  const removed = [], redirects = []
  global.wx = { removeStorageSync: key => removed.push(key), redirectTo: options => redirects.push(options), showToast() {} }
  const expired = () => Object.assign(new Error('登录已过期'), { authRequired: true })
  try {
    for (const stage of ['members', 'works']) {
      const page = pageHarness(async ({ url }) => {
        if (stage === 'works' && url.endsWith('/members')) return [{ memberUserId: 9 }]
        throw expired()
      })
      page.data.teamId = 12
      page.openStructuredTextSheet()
      await page.handleTextBackgroundRequest({ detail: { reset: true } })
    }
    assert.deepEqual(removed, ['wefolio_token', 'wefolio_token'])
    assert.deepEqual(redirects, [{ url: '/pages/login/login' }, { url: '/pages/login/login' }])
    for (const dismiss of ['handleCancelStructuredText', 'onUnload']) {
      let rejectRequest
      const page = pageHarness(() => new Promise((resolve, reject) => { rejectRequest = reject }))
      page.data.teamId = 12
      page.openStructuredTextSheet()
      const pending = page.handleTextBackgroundRequest({ detail: { reset: true } })
      page[dismiss]()
      rejectRequest(expired())
      await pending
    }
    assert.deepEqual(removed, ['wefolio_token', 'wefolio_token'])
    assert.equal(redirects.length, 2)
  } finally {
    if (previousWx === undefined) delete global.wx
    else global.wx = previousWx
  }
})

test('团队组件完成不重复插入，缺失背景成员的历史配置可明确标记失效', () => {
  const page = pageHarness()
  page.openStructuredTextSheet()
  page.handleConfirmStructuredText({ detail: { blocks: [block('a')] } })
  page.handleConfirmStructuredText({ detail: { blocks: [block('a')] } })
  assert.equal(page.data.config.components.length, 1)
  const editor = harness('components/structured-text-editor/structured-text-editor')
  editor.properties = { config: { blocks: [block('a')], backgroundEnabled: true, backgroundWorkId: 7, backgroundLoading: true } }
  editor.beginSession()
  editor.applyBackgroundResource({ backgroundMemberUserId: 0, backgroundWorkId: 7, backgroundWork: null, backgroundInvalid: true, backgroundLoading: false })
  assert.equal(editor.data.draft.backgroundLoading, false)
  assert.equal(editor.data.draft.backgroundInvalid, true)
  editor.handleConfirm()
  assert.ok(editor.data.error)
  assert.equal(editor.events.length, 0)
})

test('团队已添加结构化组件在真实行模板中显示中文名称与编辑读屏标签', () => {
  const fs = require('node:fs')
  const vm = require('node:vm')
  const path = require('node:path')
  const context = { module: { exports: {} } }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, base, 'standard-edit/component-rows.wxs'), 'utf8'), context)
  assert.equal(context.module.exports.resolveName('STRUCTURED_TEXT_SECTION'), '结构化文字说明')
  assert.equal(context.module.exports.resolveAriaLabel('STRUCTURED_TEXT_SECTION'), '编辑结构化文字说明')
})

test('团队浏览其他成员不打断已选背景恢复，显式重试以最新恢复结果为准', async () => {
  const pending = []
  const page = pageHarness(params => new Promise((resolve, reject) => pending.push({ params, resolve, reject })))
  page.data.teamId = 12
  const key = page.addComponent('TEXT_SECTION')
  Object.assign(page.data.config.components.at(-1).config, { content: '文字', backgroundEnabled: true, backgroundMemberUserId: 9, backgroundWorkId: 7 })
  page.openTextSectionSheet(key)
  pending[0].resolve([{ memberUserId: 9 }, { memberUserId: 10 }])
  await new Promise(resolve => setImmediate(resolve))
  const browse = page.handleTextBackgroundRequest({ detail: { memberUserId: 10, reset: true } })
  await new Promise(resolve => setImmediate(resolve))
  pending[2].resolve({ works: [{ workId: 8, mediaType: 'IMAGE', mediaUrl: 'https://example.test/other.jpg' }], page: 1, hasMore: false })
  await browse
  pending[1].resolve({ works: [], selectedWork: { workId: 7, mediaType: 'ANIMATION', mediaUrl: 'https://example.test/selected.gif' }, page: 1, hasMore: false })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(page.data.textSectionForm.backgroundWork.url, 'https://example.test/selected.gif')
  assert.equal(page.data.textBackgroundMemberUserId, 10)
  assert.equal(page.data.textBackgroundOptions[0].id, 8)
  const old = page.handleTextBackgroundRequest({ detail: { restoreSelection: true } })
  await new Promise(resolve => setImmediate(resolve))
  const next = page.handleTextBackgroundRequest({ detail: { restoreSelection: true } })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(pending[3].params.data.selectedWorkId, 7)
  assert.match(pending[4].params.url, /members\/9\/works\/page$/)
  pending[4].resolve({ works: [], selectedWork: null, page: 1, hasMore: false })
  await next
  pending[3].resolve({ works: [], selectedWork: { workId: 7, mediaType: 'IMAGE', mediaUrl: 'https://example.test/stale.jpg' }, page: 1, hasMore: false })
  await old
  assert.equal(page.data.textSectionForm.backgroundInvalid, true)
  assert.equal(page.data.textSectionForm.backgroundWork, null)
  const picker = harness('components/text-background-editor/text-background-editor')
  picker.properties = { config: page.data.textSectionForm }
  picker.handleRetrySelection()
  assert.deepEqual(picker.events.at(-1), { name: 'request', detail: { restoreSelection: true } })
})

test('团队重选或关闭背景后旧恢复401不退出登录，结构化事件同步新的实际归属', async () => {
  const previousWx = global.wx
  const removed = []
  global.wx = { removeStorageSync: key => removed.push(key), redirectTo() {}, showToast() {} }
  try {
    for (const close of [false, true]) {
      let rejectRequest
      const page = pageHarness(({ url }) => url.endsWith('/members') ? Promise.resolve([{ memberUserId: 9 }, { memberUserId: 10 }]) : new Promise((resolve, reject) => { rejectRequest = reject }))
      page.data.teamId = 12
      const key = page.addComponent('TEXT_SECTION')
      Object.assign(page.data.config.components.at(-1).config, { content: '文字', backgroundEnabled: true, backgroundMemberUserId: 9, backgroundWorkId: 7 })
      page.openTextSectionSheet(key)
      await new Promise(resolve => setImmediate(resolve))
      const next = { ...page.data.textSectionForm, backgroundEnabled: !close, backgroundLoading: false, backgroundWorkId: close ? 7 : 8, backgroundMemberUserId: close ? 9 : 10 }
      page.handleTextBackgroundChange({ detail: next })
      rejectRequest(Object.assign(new Error('登录已过期'), { authRequired: true }))
      await new Promise(resolve => setImmediate(resolve))
      assert.equal(page.data.textSectionForm.backgroundWorkId, close ? 7 : 8)
    }
    assert.deepEqual(removed, [])
    const editor = harness('components/structured-text-editor/structured-text-editor')
    editor.properties = { config: { blocks: [block('a')] } }
    editor.beginSession()
    editor.handleBackgroundChange({ detail: { backgroundEnabled: true, backgroundMemberUserId: 10, backgroundWorkId: 8 } })
    assert.deepEqual(editor.events.at(-1), { name: 'backgroundchange', detail: { backgroundEnabled: true, backgroundMemberUserId: 10, backgroundWorkId: 8 } })
  } finally {
    if (previousWx === undefined) delete global.wx
    else global.wx = previousWx
  }
})

function wireBackgroundEditor(page, structured) {
  const picker = harness('components/text-background-editor/text-background-editor')
  let editor
  if (structured) {
    page.openStructuredTextSheet()
    editor = harness('components/structured-text-editor/structured-text-editor')
    editor.properties = { visible: true, config: page.data.structuredTextConfig }
    editor.beginSession()
    editor.triggerEvent = (name, detail) => {
      if (name === 'backgroundchange') page.handleStructuredTextBackgroundChange({ detail })
      if (name === 'backgroundrequest') page.handleTextBackgroundRequest({ detail })
    }
  } else {
    const key = page.addComponent('TEXT_SECTION')
    page.openTextSectionSheet(key)
  }
  picker.properties = { config: {}, structured, options: [], members: [], memberUserId: 9 }
  picker.triggerEvent = (name, detail) => {
    if (name === 'change') {
      if (structured) editor.handleBackgroundChange({ detail })
      else page.handleTextBackgroundChange({ detail })
      picker.properties.config = structured ? editor.data.draft : page.data.textSectionForm
    }
    if (name === 'request') {
      if (structured) editor.handleBackgroundRequest({ detail })
      else page.handleTextBackgroundRequest({ detail })
    }
  }
  picker.triggerEvent('change', { backgroundEnabled: true, backgroundMemberUserId: 9, backgroundWorkId: 7,
    backgroundWork: { workId: 7, mediaType: 'IMAGE', url: 'https://example.test/selected.jpg' } })
  return picker
}

for (const structured of [false, true]) {
  for (const outcome of ['success', 'error', '401']) {
    test(`${structured ? '结构化' : '普通'}团队共享成员请求连续搜索${outcome}后当前调用结束加载`, async () => {
      const previousWx = global.wx
      const removed = [], redirects = [], pending = []
      global.wx = { removeStorageSync: key => removed.push(key), redirectTo: options => redirects.push(options), showToast() {} }
      try {
        const page = pageHarness(params => new Promise((resolve, reject) => pending.push({ params, resolve, reject })))
        page.data.teamId = 12
        wireBackgroundEditor(page, structured)
        const old = page.handleTextBackgroundRequest({ detail: { reset: true, keyword: 'a' } })
        const current = page.handleTextBackgroundRequest({ detail: { reset: true, keyword: 'b' } })
        assert.equal(pending.length, 1, '连续搜索应共用尚未结束的成员查询')
        if (outcome === 'success') {
          pending[0].resolve([{ memberUserId: 9 }])
          await new Promise(resolve => setImmediate(resolve))
          assert.equal(pending.length, 2, '仅当前搜索应继续查询作品')
          pending[1].resolve({ works: [], page: 1, hasMore: false })
        } else {
          pending[0].reject(Object.assign(new Error('成员加载失败'), outcome === '401' ? { authRequired: true } : {}))
        }
        await Promise.all([old, current])
        assert.equal(page.data.textBackgroundMembersLoading, false)
        assert.equal(page.textBackgroundMembersPromise, null)
        assert.deepEqual(removed, outcome === '401' ? ['wefolio_token'] : [])
        assert.equal(redirects.length, outcome === '401' ? 1 : 0)
        if (outcome !== '401') {
          assert.equal(page.data.textBackgroundLoading, false)
          assert.equal(page.data.textBackgroundKeyword, 'b')
          assert.equal(page.data.textBackgroundError, outcome === 'error' ? '背景作品加载失败，请重试' : '')
        }
      } finally {
        if (previousWx === undefined) delete global.wx
        else global.wx = previousWx
      }
    })
  }
}

test('团队关闭背景同时收起作品选择器，保留已选身份和背景偏好', () => {
  const picker = harness('components/text-background-editor/text-background-editor')
  picker.properties = { config: { backgroundEnabled: true, backgroundWorkId: 7, backgroundMemberUserId: 9, backgroundTreatment: 'ORIGINAL' } }
  picker.data.pickerOpen = true
  picker.handleToggle(evt({}, false))
  assert.equal(picker.data.pickerOpen, false)
  const config = picker.events.find(event => event.name === 'change').detail
  assert.equal(config.backgroundWorkId, 7)
  assert.equal(config.backgroundMemberUserId, 9)
  assert.equal(config.backgroundTreatment, 'ORIGINAL')
})

for (const structured of [false, true]) {
  for (const action of ['close', 'same', 'different']) {
    for (const outcome of ['success', 'error', '401']) {
      test(`${structured ? '结构化' : '普通'}团队背景${action}后忽略旧候选${outcome}并立即清理加载态`, async () => {
        const previousWx = global.wx
        const removed = [], redirects = []
        global.wx = { removeStorageSync: key => removed.push(key), redirectTo: options => redirects.push(options), showToast() {} }
        const pending = []
        try {
          const page = pageHarness(params => new Promise((resolve, reject) => pending.push({ params, resolve, reject })))
          page.data.teamId = 12
          const picker = wireBackgroundEditor(page, structured)
          const initial = page.handleTextBackgroundRequest({ detail: { reset: true, memberUserId: 9 } })
          pending[0].resolve([{ memberUserId: 9 }])
          await new Promise(resolve => setImmediate(resolve))
          pending[1].resolve({ works: [{ workId: 7, mediaType: 'IMAGE', mediaUrl: 'https://example.test/selected.jpg' }, { workId: 8, mediaType: 'ANIMATION', mediaUrl: 'https://example.test/new.gif' }], page: 1, hasMore: true })
          await initial
          picker.properties.options = page.data.textBackgroundOptions
          const before = JSON.stringify(page.data.textBackgroundOptions)
          const more = page.handleTextBackgroundRequest({ detail: { reset: false } })
          await new Promise(resolve => setImmediate(resolve))
          if (action === 'close') picker.handleToggle(evt({}, false))
          else picker.handleSelect(evt({ id: action === 'same' ? 7 : 8 }))
          const loadingAfterAction = page.data.textBackgroundLoading
          if (outcome === 'success') pending[2].resolve({ works: [{ workId: 99, mediaType: 'IMAGE', mediaUrl: 'https://example.test/stale.jpg' }], page: 2, hasMore: false })
          else pending[2].reject(Object.assign(new Error('过期响应'), outcome === '401' ? { authRequired: true } : {}))
          await more
          assert.deepEqual(removed, [])
          assert.deepEqual(redirects, [])
          assert.equal(page.data.textBackgroundError, '')
          assert.equal(JSON.stringify(page.data.textBackgroundOptions), before)
          assert.equal(loadingAfterAction, false)
          assert.equal(page.data.textBackgroundLoading, false)
          assert.equal(picker.properties.config.cancelCandidates, undefined)
        } finally {
          if (previousWx === undefined) delete global.wx
          else global.wx = previousWx
        }
      })
    }
  }
  for (const outcome of ['success', 'error', '401']) {
    test(`${structured ? '结构化' : '普通'}团队关闭背景后旧成员查询${outcome}不能重新写入候选状态`, async () => {
      const previousWx = global.wx
      const removed = []
      global.wx = { removeStorageSync: key => removed.push(key), redirectTo() {}, showToast() {} }
      const pending = []
      try {
        const page = pageHarness(params => new Promise((resolve, reject) => pending.push({ params, resolve, reject })))
        page.data.teamId = 12
        const picker = wireBackgroundEditor(page, structured)
        const members = page.handleTextBackgroundRequest({ detail: { reset: true } })
        picker.handleToggle(evt({}, false))
        const loadingAfterAction = page.data.textBackgroundMembersLoading
        if (outcome === 'success') pending[0].resolve([{ memberUserId: 9, displayName: '过期成员' }])
        else pending[0].reject(Object.assign(new Error('过期响应'), outcome === '401' ? { authRequired: true } : {}))
        await new Promise(resolve => setImmediate(resolve))
        if (pending[1]) pending[1].resolve({ works: [], page: 1, hasMore: false })
        await members
        assert.deepEqual(removed, [])
        assert.deepEqual(page.data.textBackgroundMembers, [])
        assert.equal(page.data.textBackgroundError, '')
        assert.equal(loadingAfterAction, false)
        assert.equal(page.data.textBackgroundMembersLoading, false)
        assert.equal(page.data.textBackgroundLoading, false)
        assert.equal(pending.length, 1)
      } finally {
        if (previousWx === undefined) delete global.wx
        else global.wx = previousWx
      }
    })
  }
}

test('团队视频背景候选保留当前页，切换成员后仍使用候选实际归属及封面', async () => {
  const calls = []
  const page = pageHarness(async params => {
    calls.push(params)
    if (params.url.endsWith('/members')) return [{ memberUserId: 9 }, { memberUserId: 10 }]
    if (params.data.page > 1) return { works: [], page: 2, hasMore: false }
    return { works: [{ workId: 7, title: '婚礼视频', mediaType: 'VIDEO', mediaUrl: 'video.mp4', coverUrl: 'poster.jpg' },
      { workId: 8, title: '婚礼音频', mediaType: 'AUDIO', mediaUrl: 'audio.mp3' }], page: 1, hasMore: true }
  })
  page.data.teamId = 12
  page.openStructuredTextSheet()
  await page.handleTextBackgroundRequest({ detail: { memberUserId: 10, keyword: '婚礼' } })
  assert.equal(calls.length, 2)
  assert.match(calls[1].url, /members\/10\/works\/page$/)
  assert.equal(page.data.textBackgroundHasMore, true)
  assert.deepEqual(page.data.textBackgroundOptions.map(work => work.id), [7])
  assert.equal(page.data.textBackgroundOptions[0].memberUserId, 10)
  assert.equal(page.data.textBackgroundOptions[0].url, 'video.mp4')
  assert.equal(page.data.textBackgroundOptions[0].posterUrl, 'poster.jpg')
})

test('团队背景续页跳过仅含重复作品的页面，直到出现新候选或列表结束', async () => {
  const requestedPages = []
  const page = pageHarness(async params => {
    if (params.url.endsWith('/members')) return [{ memberUserId: 9 }]
    requestedPages.push(params.data.page)
    const workId = params.data.page < 3 ? 7 : 8
    return { works: [{ workId, title: '婚礼影像', mediaType: 'IMAGE', mediaUrl: `${workId}.jpg` }],
      page: params.data.page, hasMore: params.data.page < 3 }
  })
  page.data.teamId = 12
  page.openStructuredTextSheet()
  await page.handleTextBackgroundRequest({ detail: { reset: true } })
  await page.handleTextBackgroundRequest({ detail: { reset: false } })
  assert.deepEqual(requestedPages, [1, 2, 3])
  assert.deepEqual(page.data.textBackgroundOptions.map(work => work.id), [7, 8])
  assert.equal(page.data.textBackgroundPage, 3)
  assert.equal(page.data.textBackgroundHasMore, false)
  assert.equal(page.data.textBackgroundLoading, false)
})

test('团队普通与结构化背景续页失败保留候选，重试继续请求失败页及当前成员', async () => {
  for (const structured of [false, true]) {
    const pending = []
    const page = pageHarness(params => {
      if (params.url.endsWith('/members')) return Promise.resolve([{ memberUserId: 9 }])
      return new Promise((resolve, reject) => pending.push({ params, resolve, reject }))
    })
    page.data.teamId = 12
    if (structured) page.openStructuredTextSheet()
    else page.openTextSectionSheet(page.addComponent('TEXT_SECTION'))
    const initial = page.handleTextBackgroundRequest({ detail: { reset: true, keyword: '婚礼', memberUserId: 9 } })
    await new Promise(resolve => setImmediate(resolve))
    pending[0].resolve({ works: [{ workId: 7, title: '婚礼影像', mediaType: 'IMAGE', mediaUrl: 'first.jpg' }], page: 1, hasMore: true })
    await initial
    const loaded = JSON.stringify(page.data.textBackgroundOptions)
    const next = page.handleTextBackgroundRequest({ detail: { reset: false } })
    await new Promise(resolve => setImmediate(resolve))
    pending[1].reject(new Error('网络暂时不可用'))
    await next
    assert.equal(JSON.stringify(page.data.textBackgroundOptions), loaded)
    assert.equal(page.data.textBackgroundPage, 1)
    assert.equal(page.data.textBackgroundHasMore, true)
    assert.equal(page.data.textBackgroundLoading, false)
    assert.ok(page.data.textBackgroundError)
    const retry = page.handleTextBackgroundRequest({ detail: { reset: false } })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(JSON.stringify(page.data.textBackgroundOptions), loaded)
    assert.equal(page.data.textBackgroundError, '')
    assert.equal(page.data.textBackgroundKeyword, '婚礼')
    assert.deepEqual(pending.map(item => item.params.data.page), [1, 2, 2])
    assert.ok(pending.every(item => item.params.url.endsWith('/members/9/works/page')))
    pending[2].resolve({ works: [{ workId: 8, title: '婚礼视频', mediaType: 'VIDEO', mediaUrl: 'next.mp4' }], page: 2, hasMore: false })
    await retry
    assert.deepEqual(page.data.textBackgroundOptions.map(work => work.id), [7, 8])
    assert.equal(page.data.textBackgroundPage, 2)
    assert.equal(page.data.textBackgroundHasMore, false)
    assert.equal(page.data.textBackgroundLoading, false)
  }
})

test('团队普通和结构化视频背景可恢复保存重开，成员撤权后保留身份提示失效', async () => {
  for (const structured of [false, true]) {
    let authorized = true
    const page = pageHarness(async ({ url }) => {
      if (url.endsWith('/members')) return authorized ? [{ memberUserId: 9 }] : []
      return { works: [], selectedWork: { workId: 7, mediaType: 'VIDEO', mediaUrl: 'video.mp4',
        coverUrl: 'poster.jpg', width: 1920, height: 1080 }, page: 1, hasMore: false }
    })
    page.data.teamId = 12
    const key = page.addComponent(structured ? 'STRUCTURED_TEXT_SECTION' : 'TEXT_SECTION')
    page.data.config.components.at(-1).config = { content: '介绍', blocks: [block('a')], backgroundEnabled: true,
      backgroundWorkId: 7, backgroundMemberUserId: 9 }
    const open = () => structured ? page.openStructuredTextSheet(key) : page.openTextSectionSheet(key)
    open()
    await new Promise(resolve => setImmediate(resolve))
    const resource = page.data.textBackgroundResource
    assert.equal(resource.backgroundInvalid, false)
    assert.equal(resource.backgroundWork.url, 'video.mp4')
    assert.equal(resource.backgroundWork.posterUrl, 'poster.jpg')
    if (structured) page.handleConfirmStructuredText({ detail: { ...page.data.structuredTextConfig, ...resource } })
    else page.handleConfirmTextSectionConfig()
    const saved = page.data.config.components.at(-1).config
    assert.equal(saved.backgroundWorkId, 7)
    assert.equal(saved.backgroundMemberUserId, 9)
    assert.equal(saved.backgroundWork, undefined)
    authorized = false
    open()
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(page.data.textBackgroundResource.backgroundInvalid, true)
    assert.equal(page.data.textBackgroundResource.backgroundWorkId, 7)
    assert.equal(page.data.textBackgroundResource.backgroundMemberUserId, 9)
  }
})

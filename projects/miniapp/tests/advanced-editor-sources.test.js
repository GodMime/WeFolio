const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { normalizeContext, captureBasis } = require('../pages/portfolios/utils/advanced-portfolios')

function createEditor(team, requestFn = async () => ({})) {
  const api = require(`../pages/${team ? 'team-portfolios' : 'portfolios'}/utils/advanced-editor-page`)
  const listeners = {}
  const page = { ...api.advancedEditorPageMethods,
    data: { config: { components: [] }, portfolioId: '91', draftRevision: 3 },
    setData(patch) { Object.assign(this.data, patch) },
    getOpenerEventChannel() { return { on(name, listener) { listeners[name] = listener } } },
    applyEditorConfig(config) { this.setData({ config }) },
    updateConfig(config) { this.setData({ config }) },
    advancedRequest: requestFn,
    onUnload() { this.originalUnloadCalled = true }
  }
  api.installAdvancedEditor(page, { templateType: 'ADVANCED', ownerId: '12', teamId: team ? '12' : undefined }, team)
  return { page, listeners, api }
}

for (const team of [false, true]) {
  const label = team ? '团队' : '个人'
  test(`${label}初次采用的缩略图只进入展示状态，编号仍来自稳定上下文`, () => {
    const { page, listeners } = createEditor(team)
    const aiContext = normalizeContext({ nextSourceNo: 5, sources: [{ sourceNo: 3, kind: 'WORK', sourceId: '7' }] })
    listeners.advancedInitial({ ownerId: '12', aiContext, prompt: '简洁展示', localEditRevision: 2,
      config: { components: [{ componentKey: 'text' }] }, sourceItems: [{ sourceNo: 99, sourceId: '7', kind: 'WORK',
        title: '温柔瞬间', thumbnailUrl: 'https://example.com/cover.jpg' }] })
    assert.equal(page.data.advancedSourceItems[0].sourceNo, 3)
    assert.equal(page.data.advancedSourceItems[0].title, '温柔瞬间')
    assert.equal(page.data.advancedSourceItems[0].coverUrl, 'https://example.com/cover.jpg')
    assert.deepEqual(page.data.aiContext, aiContext)
    assert.deepEqual(Object.keys(page.data.aiContext.sources[0]), ['sourceNo', 'kind', 'sourceId'])
  })

  test(`${label}AI 修改只传完整页面和整体描述，不携带组件级编辑目标`, () => {
    const { page } = createEditor(team)
    const oldWx = global.wx
    let navigation
    let initialized
    global.wx = { navigateTo(options) { navigation = options; options.success({ eventChannel: { emit(name, detail) { initialized = detail } } }) } }
    try {
      page.setData({ aiPrompt: '整体简洁，作品放在介绍前', activeComponentKey: 'text', activeMenuKey: 'home', activeMenuTitle: '首页' })
      page.handleAdvancedAi({ currentTarget: { dataset: { mode: 'MODIFY_PAGE' } } })
      assert.match(navigation.url, /mode=MODIFY_PAGE$/)
      assert.equal(initialized.mode, 'MODIFY_PAGE')
      assert.deepEqual(Object.keys(initialized).sort(), ['aiContext', 'config', 'draftRevision', 'localEditRevision', 'mode',
        'ownerId', 'ownerType', 'portfolioId', 'prompt', 'sourceItems', 'targetMenuKey'])
      assert.equal(initialized.targetMenuKey, 'home')
      assert.equal(initialized.prompt, '整体简洁，作品放在介绍前')
      assert.deepEqual(initialized.config, page.data.config)
    } finally { global.wx = oldWx }
  })

  test(`${label}管理内容返回会话摘要并保留断号，不把摘要写入保存载荷`, () => {
    const { page, api } = createEditor(team)
    const oldWx = global.wx
    let navigation
    global.wx = { navigateTo(options) { navigation = options } }
    try {
      page.handleAdvancedAi({ currentTarget: { dataset: { mode: 'MANAGE_SOURCES' } } })
      navigation.events.contextChange({ aiContext: { nextSourceNo: 7, sources: [{ sourceNo: 6, kind: 'WORK', sourceId: '8' }] },
        prompt: '', localEditRevision: 1, sourceItems: [{ sourceId: '8', title: '新选择', coverUrl: 'https://example.com/new.jpg' }] })
      assert.equal(page.data.advancedSourceItems[0].sourceNo, 6)
      assert.equal(page.data.advancedSourceItems[0].title, '新选择')
      assert.equal(page.data.advancedSourceItems[0].coverUrl, 'https://example.com/new.jpg')
      const payload = page.advancedDraftPayload({ config: page.data.config })
      assert.equal(payload.advancedSourceItems, undefined)
      assert.deepEqual(payload.aiContext.sources, [{ sourceNo: 6, kind: 'WORK', sourceId: '8' }])
      api.markAdvancedSaved(page)
      const basis = captureBasis(api.editorState(page))
      navigation.events.contextChange({ aiContext: payload.aiContext, prompt: '', localEditRevision: page.data.localEditRevision,
        sourceItems: [{ sourceId: '8', title: '已刷新标题', coverUrl: 'https://example.com/refreshed.jpg' }] })
      assert.equal(page.data.advancedSourceItems[0].title, '已刷新标题')
      assert.equal(page.data.advancedDirty, false)
      assert.equal(captureBasis(api.editorState(page)), basis)
    } finally { global.wx = oldWx }
  })
}

test('个人重开草稿按真实详情包装恢复封面，失效来源保留编号且不影响草稿状态', async () => {
  const calls = []
  const { page, api } = createEditor(false, async options => {
    calls.push(options)
    if (options.url.endsWith('/8')) throw new Error('来源已删除')
    return { work: { id: '7', title: '仪式瞬间', coverUrl: 'https://example.com/7.jpg', auditStatus: 'PASSED', status: 'NORMAL' } }
  })
  const aiContext = normalizeContext({ nextSourceNo: 9, sources: [
    { sourceNo: 2, kind: 'WORK', sourceId: '7' }, { sourceNo: 8, kind: 'WORK', sourceId: '8' }
  ] })
  const hydration = api.loadAdvancedMetadata(page, { ownerId: '12', aiContext, aiPrompt: '描述保留' })
  const basis = captureBasis(api.editorState(page))
  await hydration
  assert.deepEqual(calls.map(item => item.url), ['/api/mine/works/7', '/api/mine/works/8'])
  assert.equal(page.data.advancedSourceItems[0].title, '仪式瞬间')
  assert.equal(page.data.advancedSourceItems[0].coverUrl, 'https://example.com/7.jpg')
  assert.equal(page.data.advancedSourceItems[0].available, true)
  assert.equal(page.data.advancedSourceItems[1].sourceNo, 8)
  assert.equal(page.data.advancedSourceItems[1].available, false)
  assert.equal(page.data.advancedDirty, false)
  assert.deepEqual(page.data.aiContext, aiContext)
  assert.equal(captureBasis(api.editorState(page)), basis)
})

test('团队重开按有权来源分页恢复混合摘要，同ID不同类型不混淆，失效编号保留', async () => {
  const calls = []
  const { page, api } = createEditor(true, async options => {
    calls.push(options)
    return options.data.page === 1 ? { records: [
      { kind: 'WORK', sourceId: '7', title: '成员作品', thumbnailUrl: 'https://example.com/work.jpg' }
    ], hasMore: true } : { records: [
      { kind: 'MEMBER_PORTFOLIO', sourceId: '7', title: '成员作品集', coverUrl: 'https://example.com/portfolio.jpg' }
    ], hasMore: false }
  })
  const aiContext = normalizeContext({ nextSourceNo: 7, sources: [
    { sourceNo: 1, kind: 'WORK', sourceId: '7' }, { sourceNo: 3, kind: 'MEMBER_PORTFOLIO', sourceId: '7' },
    { sourceNo: 6, kind: 'WORK', sourceId: '99' }
  ] })
  const hydration = api.loadAdvancedMetadata(page, { ownerId: '12', aiContext })
  const basis = captureBasis(api.editorState(page))
  await hydration
  assert.deepEqual(calls.map(item => item.data.page), [1, 2])
  assert.equal(calls.every(item => item.url === '/api/mine/advanced-portfolios/sources' && item.data.ownerType === 'TEAM'
    && item.data.ownerId === '12' && item.data.pageSize === 100), true)
  assert.deepEqual(page.data.advancedSourceItems.map(item => [item.sourceNo, item.title]), [[1, '成员作品'], [3, '成员作品集'], [6, '6号作品']])
  assert.equal(page.data.advancedSourceItems[2].available, false)
  assert.equal(page.data.advancedDirty, false)
  assert.deepEqual(page.data.aiContext, aiContext)
  assert.equal(captureBasis(api.editorState(page)), basis)
})

test('编辑器关闭后忽略在途来源摘要，保留原卸载行为', async () => {
  let resolveRequest
  const { page, api } = createEditor(false, () => new Promise(resolve => { resolveRequest = resolve }))
  const hydration = api.loadAdvancedMetadata(page, { ownerId: '12', aiContext: {
    nextSourceNo: 2, sources: [{ sourceNo: 1, kind: 'WORK', sourceId: '7' }]
  } })
  const before = JSON.stringify(page.data)
  page.onUnload()
  resolveRequest({ work: { id: '7', title: '晚到摘要', coverUrl: 'https://example.com/7.jpg', auditStatus: 'PASSED' } })
  await hydration
  assert.equal(page.originalUnloadCalled, true)
  assert.equal(JSON.stringify(page.data), before)
})

test('个人与团队高级编辑会话工具保持本地实现一致', () => {
  const root = path.join(__dirname, '..', 'pages')
  assert.equal(fs.readFileSync(path.join(root, 'portfolios/utils/advanced-editor-page.js'), 'utf8'),
    fs.readFileSync(path.join(root, 'team-portfolios/utils/advanced-editor-page.js'), 'utf8'))
})

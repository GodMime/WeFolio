const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { normalizeContext, addSource } = require('../pages/portfolios/utils/advanced-portfolios')
const { confirmAdvancedPortfolioPublish } = require('../pages/portfolios/utils/advanced-editor-page')

for (const team of [false, true]) {
  test(`${team ? '团队' : '个人'}无内容修改仍保存升级，并回填服务端高级版本`, async () => {
    const calls = []
    const page = loadEditor(team, async options => {
      calls.push(JSON.parse(JSON.stringify(options)))
      return { portfolioId: '90', draftRevision: 8, config: { ...options.data.config, advancedPortfolioVersion: 2 } }
    })
    try {
      page.setData({ portfolioId: '90', draftRevision: 7, advancedDirty: false, config: { ...page.data.config, advancedPortfolioVersion: 1 } })
      const before = JSON.parse(JSON.stringify(page.data.config.components))
      const saved = team ? await page.saveDraft() : await page.saveDraftForPortfolio('90', undefined, { returnToList: false })
      assert.equal(calls.length, 1)
      assert.equal(calls[0].data.clientAdvancedVersion, 2)
      assert.equal(calls[0].data.config.advancedPortfolioVersion, 1)
      assert.deepEqual(calls[0].data.clientCapabilities, { portfolioRemoteFont: 1 })
      assert.equal(page.data.config.advancedPortfolioVersion, 2)
      assert.equal(page.data.savedAdvancedVersion, 2)
      assert.equal(page.data.draftRevision, 8)
      assert.equal(saved.editorChanged, false)
      assert.deepEqual(page.data.config.components, before)
    } finally { page.cleanup() }
  })
  test(`${team ? '团队' : '个人'}保存期间新正文不被响应覆盖，高级版本和修订仍回填`, async () => {
    let respond, request
    const page = loadEditor(team, options => { request = JSON.parse(JSON.stringify(options)); return new Promise(resolve => { respond = resolve }) })
    try {
      page.setData({ portfolioId: '90', draftRevision: 7, config: { ...page.data.config, advancedPortfolioVersion: 1 }, advancedDirty: true })
      const saving = team ? page.saveDraft() : page.saveDraftForPortfolio('90', undefined, { returnToList: false })
      for (let index = 0; index < 30 && !respond; index++) await Promise.resolve()
      assert.ok(respond)
      const config = JSON.parse(JSON.stringify(page.data.config)); config.components[0].config.content = '保存期间的新文字'
      if (team) page.updateConfig(config); else page.applyEditorConfig(config)
      respond({ portfolioId: '90', draftRevision: 8, config: { ...request.data.config, advancedPortfolioVersion: 2 } })
      const saved = await saving
      assert.equal(saved.editorChanged, true)
      assert.equal(page.data.config.components[0].config.content, '保存期间的新文字')
      assert.equal(page.data.config.advancedPortfolioVersion, 2)
      assert.equal(page.data.draftRevision, 8)
      assert.equal(page.data.advancedDirty, true)
    } finally { page.cleanup() }
  })
}

function loadEditor(team, requestFn) {
  const requestKey = require.resolve('../utils/request.js')
  const oldRequest = require.cache[requestKey]
  const oldPage = global.Page
  const oldWx = global.wx
  const oldPages = global.getCurrentPages
  const requestsModule = require(requestKey)
  require.cache[requestKey] = { id: requestKey, filename: requestKey, loaded: true, exports: { ...requestsModule, request: requestFn } }
  global.wx = { showToast() {}, navigateBack() {}, redirectTo() {}, showModal({ success }) { success({ confirm: true }) } }
  global.getCurrentPages = () => [{ route: 'pages/portfolios/portfolios' }, { route: 'editor' }]
  let definition
  global.Page = value => { definition = value }
  const pagePath = path.join(__dirname, '..', 'pages', team ? 'team-portfolios/standard-edit/team-portfolio-standard-edit.js' : 'portfolios/standard-edit/portfolio-standard-edit.js')
  delete require.cache[require.resolve(pagePath)]
  require(pagePath)
  global.Page = oldPage
  require.cache[requestKey] = oldRequest
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData(patch) { Object.assign(this.data, patch) } }
  page.setData({ advancedEditing: true, advancedOwnerType: team ? 'TEAM' : 'USER', advancedOwnerId: team ? '3' : '1', teamId: team ? '3' : null,
    canMaintain: true, portfolioId: null, draftRevision: 0, aiContext: normalizeContext(), aiPrompt: '精简文字', localEditRevision: 1 })
  const config = { schemaVersion: team ? 'standard-team-v1' : 'standard-personal-v1', share: { title: '测试高级作品集' },
    components: [{ componentKey: 'text', componentType: 'TEXT_SECTION', enabled: true, sortOrder: 1000, config: { content: '欢迎了解作品' } }] }
  if (team) page.updateConfig(config)
  else page.applyEditorConfig(config)
  page.cleanup = () => { global.wx = oldWx; global.getCurrentPages = oldPages }
  return page
}

for (const team of [false, true]) {
  const name = team ? '团队' : '个人'
  test(`${name}高级首次保存先建可见基础草稿，再携真实修订PUT完整草稿`, async () => {
    const calls = []
    const page = loadEditor(team, async options => {
      calls.push(JSON.parse(JSON.stringify(options)))
      return options.url === '/api/mine/advanced-portfolios' ? { portfolioId: '90', draftRevision: 4 }
        : { portfolioId: '90', draftRevision: 5 }
    })
    try {
      if (team) await page.saveDraft()
      else await page.performSaveDraft()
      assert.equal(calls.length, 2)
      assert.equal(calls[0].url, '/api/mine/advanced-portfolios')
      assert.equal(calls[0].method, 'POST')
      assert.deepEqual(calls[0].data, { ownerType: team ? 'TEAM' : 'USER', ownerId: team ? '3' : '1', clientAdvancedVersion: 2 })
      assert.equal(calls[1].url, '/api/mine/advanced-portfolios/90/draft')
      assert.equal(calls[1].method, 'PUT')
      assert.deepEqual(Object.keys(calls[1].data).sort(), ['aiContext', 'aiPrompt', 'clientAdvancedVersion', 'clientCapabilities', 'clientRevision', 'config', 'idempotencyKey'])
      assert.deepEqual(calls[1].data.clientCapabilities, { portfolioRemoteFont: 1 })
      assert.equal(calls[1].data.clientRevision, 4)
      assert.equal(calls[1].data.aiPrompt, '精简文字')
      assert.deepEqual(calls[1].data.aiContext, normalizeContext())
      assert.equal(calls[1].data.portfolioId, undefined)
      assert.equal(page.data.draftRevision, 5)
    } finally { page.cleanup() }
  })
  test(`${name}高级保存响应丢失后重发同一来源和描述快照，保留新的本地来源`, async () => {
    const calls = []
    let saved = false
    const page = loadEditor(team, async options => {
      calls.push(JSON.parse(JSON.stringify(options)))
      if (options.url === '/api/mine/advanced-portfolios') return { portfolioId: '90', draftRevision: 4 }
      if (!saved) { saved = true; throw new Error('timeout') }
      return { portfolioId: '90', draftRevision: 5 }
    })
    try {
      if (team) await page.saveDraft()
      else await page.performSaveDraft()
      const original = calls[1].data
      page.setData({ aiContext: addSource(page.data.aiContext, { kind: 'WORK', sourceId: '100' }), aiPrompt: '新描述' })
      const result = team ? await page.saveDraft() : await page.saveDraftForPortfolio('90', undefined, { returnToList: false })
      assert.equal(calls.filter(item => item.url === '/api/mine/advanced-portfolios').length, 1)
      assert.deepEqual(calls[2].data, original)
      assert.equal(result.editorChanged, true)
      assert.equal(page.data.aiPrompt, '新描述')
      assert.equal(page.data.aiContext.sources[0].sourceId, '100')
    } finally { page.cleanup() }
  })
}

test('高级发布费用来自生效试算响应，取消确认不自动发布', async () => {
  const calls = []
  let content
  const confirmed = await confirmAdvancedPortfolioPublish(async options => { calls.push(options); return { pointsChange: -37 } },
    { showModal(options) { content = options.content; options.success({ confirm: false }) } })
  assert.equal(confirmed, false)
  assert.match(content, /37 积分/)
  assert.equal(calls[0].data.sceneCode, 'MAINTAIN_ADVANCED_PORTFOLIO')
  assert.equal(calls.length, 1)
})

for (const team of [false, true]) {
  const name = team ? '团队' : '个人'
  const firstReference = { attachmentId: 'a'.repeat(32), referenceNo: 'A2', mediaType: 'IMAGE', note: '参考配色', attachmentToken: 'receipt', status: 'READY' }
  test(`${name}高级首次保存把参考清单写入最终草稿，创建请求不携附件`, async () => {
    const calls = []
    const page = loadEditor(team, async options => { calls.push(JSON.parse(JSON.stringify(options))); return { portfolioId: '90', draftRevision: calls.length + 3 } })
    try {
      page.setData({ aiReferenceAttachments: [firstReference], aiReferenceNextNo: 4 })
      if (team) await page.saveDraft(); else await page.performSaveDraft()
      assert.equal(calls[0].data.aiReferenceAttachments, undefined)
      assert.deepEqual(calls[1].data.aiReferenceAttachments, [{ attachmentId: firstReference.attachmentId, referenceNo: 'A2', note: '参考配色', attachmentToken: 'receipt' }])
      assert.equal(calls[1].data.aiReferenceNextNo, 4)
      assert.equal(calls[1].data.aiReferenceAttachments[0].mediaType, undefined)
      assert.equal(page.data.portfolioId, team ? 90 : '90')
    } finally { page.cleanup() }
  })
  test(`${name}高级未知保存重试冻结附件，新编辑仍被标记为未保存`, async () => {
    const calls = []; let attempted = false
    const page = loadEditor(team, async options => {
      calls.push(JSON.parse(JSON.stringify(options)))
      if (options.url === '/api/mine/advanced-portfolios') return { portfolioId: '90', draftRevision: 4 }
      if (!attempted) { attempted = true; throw new Error('timeout') }
      return { portfolioId: '90', draftRevision: 5 }
    })
    try {
      page.setData({ aiReferenceAttachments: [firstReference], aiReferenceNextNo: 4 })
      if (team) await page.saveDraft(); else await page.performSaveDraft()
      const original = calls[1].data
      page.setData({ aiReferenceAttachments: [], aiReferenceNextNo: 5 })
      const response = team ? await page.saveDraft() : await page.saveDraftForPortfolio('90', undefined, { returnToList: false })
      assert.deepEqual(calls[2].data, original)
      assert.equal(response.editorChanged, true)
      assert.deepEqual(page.data.aiReferenceAttachments, [])
      assert.equal(page.data.aiReferenceNextNo, 5)
    } finally { page.cleanup() }
  })
  test(`${name}原本无附件的未知保存重试不会混入后来添加的附件`, async () => {
    const calls = []; let attempted = false
    const page = loadEditor(team, async options => {
      calls.push(JSON.parse(JSON.stringify(options)))
      if (options.url === '/api/mine/advanced-portfolios') return { portfolioId: '90', draftRevision: 4 }
      if (!attempted) { attempted = true; throw new Error('timeout') }
      return { portfolioId: '90', draftRevision: 5 }
    })
    try {
      if (team) await page.saveDraft(); else await page.performSaveDraft()
      page.setData({ aiReferenceAttachments: [firstReference], aiReferenceNextNo: 4 })
      const response = team ? await page.saveDraft() : await page.saveDraftForPortfolio('90', undefined, { returnToList: false })
      assert.deepEqual(calls[2].data, calls[1].data)
      assert.equal(calls[2].data.aiReferenceAttachments, undefined)
      assert.equal(response.editorChanged, true)
    } finally { page.cleanup() }
  })
}

for (const team of [false, true]) {
  for (const referenceState of ['UPLOADING', 'EXPIRED', 'SAVE_WINDOW_ELAPSED']) {
    test(`${team ? '团队' : '个人'}${referenceState}附件不阻断正文保存，也不发送删除附件指令`, async () => {
      const calls = []
      const page = loadEditor(team, async options => {
        calls.push(JSON.parse(JSON.stringify(options)))
        return { portfolioId: '90', draftRevision: 5 }
      })
      try {
        const item = referenceState === 'UPLOADING'
          ? { localKey: 'local-A1', referenceNo: 'A1', status: 'UPLOADING' }
          : { attachmentId: 'a'.repeat(32), referenceNo: 'A1', status: 'READY', available: referenceState !== 'EXPIRED', attachmentToken: 'last-receipt',
              ...(referenceState === 'SAVE_WINDOW_ELAPSED' ? { referenceSaveUntil: '2000-01-01T00:00:00' } : {}) }
        page.setData({ portfolioId: '90', aiReferenceAttachments: [item], aiReferenceNextNo: 2, advancedDirty: true })
        const result = team ? await page.saveDraft() : await page.saveDraftForPortfolio('90', undefined, { returnToList: false })
        assert.equal(calls.length, 1)
        assert.equal(calls[0].method, 'PUT')
        assert.equal(calls[0].data.aiReferenceAttachments, undefined, '字段省略要求后端保留已存清单')
        assert.equal(calls[0].data.aiReferenceNextNo, undefined)
        assert.equal(calls[0].data.aiPrompt, '精简文字')
        assert.equal(result.editorChanged, true, '附件仍待保存，不得静默离开编辑器')
        assert.equal(page.data.advancedDirty, true)
        assert.deepEqual(page.data.aiReferenceAttachments, [item])
      } finally { page.cleanup() }
    })
  }
}

for (const team of [false, true]) {
  test(`${team ? '团队' : '个人'}附件已随旧快照存成后，新编辑保持未保存但不再受旧任务期限限制`, async () => {
    let resolveSave
    const calls = []
    const page = loadEditor(team, options => {
      calls.push(options)
      return new Promise(resolve => { resolveSave = resolve })
    })
    try {
      const item = { attachmentId: 'a'.repeat(32), referenceNo: 'A1', status: 'READY', attachmentToken: 'receipt', referenceSaveUntil: '2099-01-01T00:00:00' }
      page.setData({ portfolioId: '90', aiReferenceAttachments: [item], aiReferenceNextNo: 2 })
      const saving = team ? page.saveDraft() : page.saveDraftForPortfolio('90', undefined, { returnToList: false })
      for (let index = 0; index < 20 && !resolveSave; index++) await Promise.resolve()
      assert.ok(resolveSave)
      page.setData({ aiPrompt: '新的本地描述' })
      resolveSave({ portfolioId: '90', draftRevision: 5 })
      const result = await saving
      assert.equal(result.editorChanged, true)
      assert.equal(page.data.aiReferenceAttachments[0].saved, true)
      assert.equal(page.data.aiReferenceAttachments[0].saveDeadlineText, '')
      assert.equal(page.data.aiPrompt, '新的本地描述')
    } finally { page.cleanup() }
  })
}

const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { normalizeContext, addSource } = require('../pages/portfolios/utils/advanced-portfolios')
const { confirmAdvancedPortfolioPublish } = require('../pages/portfolios/utils/advanced-editor-page')

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
      assert.deepEqual(calls[0].data, { ownerType: team ? 'TEAM' : 'USER', ownerId: team ? '3' : '1' })
      assert.equal(calls[1].url, '/api/mine/advanced-portfolios/90/draft')
      assert.equal(calls[1].method, 'PUT')
      assert.deepEqual(Object.keys(calls[1].data).sort(), ['aiContext', 'aiPrompt', 'clientCapabilities', 'clientRevision', 'config', 'idempotencyKey'])
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

const test = require('node:test')
const assert = require('node:assert/strict')
const {
  fetchTeamComponentLibrary,
  normalizeTeamPortfolioConfig,
  validateTeamPortfolioForPublish
} = require('../pages/team-portfolios/utils/team-portfolios')

function pageConfig(componentType, config) {
  return normalizeTeamPortfolioConfig({ components: [
    { componentKey: 'text-1', componentType, sortOrder: 1000, enabled: true, config }
  ] })
}

test('团队组件库请求声明支持结构化文字的编辑版本', async () => {
  const calls = []
  await fetchTeamComponentLibrary(async request => { calls.push(request); return [] })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].data.editorSchemaRevision, 4)
})

test('团队发布前拒绝空结构化组件、非法区块和缺失背景成员', () => {
  const invalidConfigs = [
    { blocks: [] },
    { blocks: [{ blockKey: 't', type: 'TITLE', content: '有效', fontSizeRpx: 97 }] },
    { backgroundEnabled: true, backgroundWorkId: 71, blocks: [{ blockKey: 't', type: 'TITLE', content: '有效' }] }
  ]
  for (const config of invalidConfigs) {
    const result = validateTeamPortfolioForPublish(pageConfig('STRUCTURED_TEXT_SECTION', config))
    assert.equal(result.valid, false)
    assert.equal(result.componentKey, 'text-1')
    assert.ok(result.message)
  }
})

test('团队普通文字只追加背景校验，关闭背景保留原发布行为', () => {
  const enabled = validateTeamPortfolioForPublish(pageConfig('TEXT_SECTION', { content: '说明', backgroundEnabled: true }))
  assert.equal(enabled.valid, false)
  assert.equal(enabled.componentKey, 'text-1')
  const disabled = validateTeamPortfolioForPublish(pageConfig('TEXT_SECTION', { content: '说明', backgroundEnabled: false }))
  assert.equal(disabled.valid, true)
})

test('团队发布合法独立区块样式不强制要求作品背景', () => {
  const result = validateTeamPortfolioForPublish(pageConfig('STRUCTURED_TEXT_SECTION', {
    blocks: [{ blockKey: 't', type: 'TITLE', content: '有效', fontSizeRpx: 80, color: '#123456', alignment: 'RIGHT' }]
  }))
  assert.equal(result.valid, true)
})

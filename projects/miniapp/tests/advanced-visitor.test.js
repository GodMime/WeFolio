const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { openTeamVisitorSession, requestWithTeamVisitorSessionRefresh } = require('../pages/team-portfolios/utils/team-visitor-session')
const { normalizePendingQueue } = require('../pages/team-portfolios/utils/visit-activity')
const ADVANCED_PREFIX = '/api/visitor/advanced-team-portfolios'

test('高级团队匿名open使用独立前缀，普通团队仍使用原路径', async () => {
  const calls = []
  const requestFn = async options => { calls.push(options); return { token: 'test', visitorKey: 'v1' } }
  await openTeamVisitorSession({ shareCode: 'team-advanced', anonymousSessionId: 'anonymous', apiPrefix: ADVANCED_PREFIX, persistToken: false, requestFn })
  await openTeamVisitorSession({ shareCode: 'team-standard', anonymousSessionId: 'anonymous', persistToken: false, requestFn })
  assert.equal(calls[0].url, `${ADVANCED_PREFIX}/team-advanced/open`)
  assert.equal(calls[1].url, '/api/visitor/team-portfolios/team-standard/open')
  assert.equal(calls[0].authMode, 'none')
  assert.equal(calls[0].data.anonymousSessionId, 'anonymous')
})

test('高级团队401续期沿用高级open并重放原请求', async () => {
  const calls = []
  const result = await requestWithTeamVisitorSessionRefresh({ shareCode: 'advanced', anonymousSessionId: 'anonymous', persistToken: false,
    requestOptions: { url: `${ADVANCED_PREFIX}/advanced/schedule-query`, method: 'POST', data: { idempotencyKey: 'original' } },
    requestFn: async options => {
      calls.push(options)
      if (calls.length === 1) throw { authRequired: true }
      return options.url.endsWith('/open') ? { token: 'test', visitorKey: 'v1' } : { available: true }
    } })
  assert.equal(calls[1].url, `${ADVANCED_PREFIX}/advanced/open`)
  assert.equal(calls[2].url, calls[0].url)
  assert.deepEqual(calls[2].data, calls[0].data)
  assert.equal(result.available, true)
})

test('高级停留补报标记真实API归属但保留TEAM scope，拒绝任意前缀', () => {
  const now = Date.now()
  const entry = { portfolioType: 'TEAM', shareCode: 's1', clientSessionKey: 'client', idempotencyKey: 'open', visitorKey: 'v1',
    trackingSessionId: 'session', updatedAt: now, activeDurationMs: 100, visitorApiPrefix: ADVANCED_PREFIX }
  assert.equal(normalizePendingQueue([entry], now)[0].visitorApiPrefix, ADVANCED_PREFIX)
  assert.equal(normalizePendingQueue([entry], now)[0].portfolioType, 'TEAM')
  assert.equal(normalizePendingQueue([{ ...entry, visitorApiPrefix: 'https://untrusted.invalid' }], now)[0].visitorApiPrefix, undefined)
})

test('高级团队扫码、卡片与再分享统一落到注册的独立访客页', () => {
  const root = path.join(__dirname, '..')
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
  assert.equal(app.subPackages.find(item => item.root === 'pages/team-portfolios').pages.includes('advanced-visitor/team-visitor-portfolio'), true)
  const page = fs.readFileSync(path.join(root, 'pages/team-portfolios/advanced-visitor/team-visitor-portfolio.js'), 'utf8')
  assert.match(page, /apiPrefix: ADVANCED_TEAM_VISITOR_PREFIX/)
  assert.match(page, /advanced-visitor\/team-visitor-portfolio\?shareCode=/)
  assert.match(page, /\/api\/mine\/advanced-portfolios\/\$\{portfolioId\}\/share-records/)
  assert.match(page, /fromTeamPortfolio=1&sourceType=TEAM_PORTFOLIO/)
  assert.doesNotMatch(page, /advancedEditingVersion|eval\(|new Function/)
})

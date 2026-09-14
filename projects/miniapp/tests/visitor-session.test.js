const test = require('node:test')
const assert = require('node:assert/strict')
const { openVisitorSession, requestWithVisitorSessionRefresh } = require('../utils/visitor-session')
const { openTeamVisitorSession, requestWithTeamVisitorSessionRefresh } = require('../pages/team-portfolios/utils/team-visitor-session')
const { createVisitActivityContext } = require('../pages/portfolios/utils/visit-activity')
const { visitActivityLifecycle } = require('../utils/visit-activity-lifecycle')

function wxApi() { return { login: ({ success }) => success({ code: 'test-login' }), setStorageSync() {}, getStorageSync() { return [] }, removeStorageSync() {} } }

for (const type of ['PERSONAL', 'TEAM']) {
  test(`${type}首页、档期、资料同时401只刷新一次，复用原双键并替换资料令牌`, async () => {
    const api = wxApi()
    const context = createVisitActivityContext({ portfolioType: type, shareCode: 'shared', wxApi: api, setTimer: () => 1, clearTimer() {} })
    const prefix = type === 'TEAM' ? '/api/visitor/team-portfolios/shared' : '/api/visitor/portfolios/shared'
    const openRequests = []
    const attempts = new Map()
    const successful = []
    const session = { visitorKey: 'visitor', trackingSessionId: 7, trackingActiveDurationMs: 45000, visitorProfileToken: 'refreshed-profile', token: 'visitor-token' }
    const requestFn = async (options) => {
      if (options.url.endsWith('/open')) { openRequests.push(options); return session }
      const count = attempts.get(options.url) || 0
      attempts.set(options.url, count + 1)
      if (count === 0) throw { authRequired: true, authMode: 'visitor' }
      successful.push(options)
      return 'ok'
    }
    const open = () => type === 'TEAM' ? openTeamVisitorSession({ shareCode: 'shared', browserContext: context, wxApi: api, requestFn })
      : openVisitorSession('shared', { browserContext: context, wxApi: api, requestFn })
    await open()
    const original = openRequests[0].data
    let refreshed = 0
    context.onRefresh = () => { refreshed += 1 }
    context.setDisplayable(true); context.show({})
    const execute = (suffix) => {
      const requestOptions = { url: `${prefix}/${suffix}`, authMode: 'visitor', data: { visitorProfileToken: 'expired-profile', visitorKey: 'visitor' } }
      // 不显式传上下文，验证档期和资料工具确实通过共享注册表找到原双键。
      return type === 'TEAM' ? requestWithTeamVisitorSessionRefresh({ requestOptions, shareCode: 'shared', wxApi: api, requestFn })
        : requestWithVisitorSessionRefresh(requestOptions, { shareCode: 'shared', wxApi: api, requestFn })
    }
    await Promise.all(['events', 'schedule-query', 'visitor-profile'].map(execute))
    assert.equal(openRequests.length, 2)
    assert.equal(refreshed, 1)
    assert.equal(openRequests[1].data.idempotencyKey, original.idempotencyKey)
    assert.equal(openRequests[1].data.tracking.clientSessionKey, original.tracking.clientSessionKey)
    if (type === 'PERSONAL') successful.forEach((options) => assert.equal(options.data.visitorProfileToken, 'refreshed-profile'))
    context.invalidate()
    const reentry = createVisitActivityContext({ portfolioType: type, shareCode: 'shared', wxApi: api, setTimer: () => 1, clearTimer() {} })
    assert.notEqual(reentry.idempotencyKey, original.idempotencyKey)
    assert.notEqual(reentry.clientSessionKey, original.tracking.clientSessionKey)
    reentry.invalidate()
  })
}

test('无活动上下文的旧调用不新增tracking，显式旧幂等键保留', async () => {
  visitActivityLifecycle.invalidateAll()
  let payload
  await openVisitorSession('legacy', { wxApi: wxApi(), idempotencyKey: 'legacy-open', requestFn: async (options) => { payload = options.data; return {} } })
  assert.equal(payload.idempotencyKey, 'legacy-open')
  assert.equal('tracking' in payload, false)
})

for (const type of ['PERSONAL', 'TEAM']) {
  test(`${type}补报open可以使用独立令牌，不能覆盖当前作品集全局令牌`, async () => {
    const writes = [], api = { ...wxApi(), setStorageSync: (key, value) => writes.push({ key, value }) }
    const options = { shareCode: 'old-share', persistToken: false, wxApi: api,
      requestFn: async () => ({ token: 'old-scope-token', visitorKey: 'visitor', trackingSessionId: 8 }) }
    const response = type === 'TEAM' ? await openTeamVisitorSession(options) : await openVisitorSession('old-share', options)
    assert.equal(response.token, 'old-scope-token'); assert.deepEqual(writes, [])
  })
  test(`${type}页面销毁后迟到open和失效上下文不能写入全局令牌`, async () => {
    const writes = [], api = { ...wxApi(), setStorageSync: (key, value) => writes.push({ key, value }) }
    let finish
    const context = createVisitActivityContext({ portfolioType: type, shareCode: 'shared', wxApi: api, setTimer: () => 1, clearTimer() {} })
    const requestFn = () => new Promise(resolve => { finish = resolve })
    const options = { shareCode: 'shared', browserContext: context, wxApi: api, requestFn }
    const promise = type === 'TEAM' ? openTeamVisitorSession(options) : openVisitorSession('shared', options)
    await Promise.resolve(); await Promise.resolve(); context.dispose()
    finish({ token: 'late-token', visitorKey: 'visitor', trackingSessionId: 7 })
    await assert.rejects(promise, error => error.contextDisposed === true)
    assert.deepEqual(writes, [])
    context.invalidate()
    const invalid = type === 'TEAM' ? openTeamVisitorSession(options) : openVisitorSession('shared', options)
    await assert.rejects(invalid, error => error.statusCode === 403)
  })
}

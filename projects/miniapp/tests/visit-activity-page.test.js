const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const path = require('node:path')
function loadAdapter(scope, requestFn, wxApi) {
  const filename = path.join(__dirname, '../pages', scope, 'utils/visit-activity-page.js')
  let options
  const sandbox = { module: { exports: {} }, require(relative) {
    if (/\/visit-activity$/.test(relative)) return { createVisitActivityContext(value) { options = value; return {} } }
    return require(path.resolve(path.dirname(filename), relative))
  } }
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), sandbox, { filename })
  sandbox.module.exports.createPortfolioVisitActivity({ data: { shareCode: 'current-share', portfolio: { activeMenuKey: '' } }, applySession() {}, applyVisitorOpenResponse() {} }, { wxApi, requestFn })
  return options
}
for (const scope of ['portfolios', 'team-portfolios']) {
  test(`${scope}补报使用原scope专用Authorization，不落全局存储，并严格验证响应`, async () => {
    const requests = [], writes = []
    let accepted = 5000
    const wxApi = { login: ({ success }) => success({ code: 'code' }), setStorageSync: (key, value) => writes.push({ key, value }) }
    const options = loadAdapter(scope, async request => {
      requests.push(request)
      return request.url.endsWith('/open') ? { visitorKey: 'visitor', trackingSessionId: 9, token: 'old-scope-token' } : accepted
    }, wxApi)
    const entry = { shareCode: 'old-share', idempotencyKey: 'old-open', clientSessionKey: 'old-client', trackingSessionId: 9, activeDurationMs: 5000 }
    assert.equal(await options.recoverPending(entry, 'visitor', () => true), 5000)
    assert.deepEqual(writes, [])
    assert.equal(requests[0].data.idempotencyKey, 'old-open'); assert.equal(requests[0].data.tracking.clientSessionKey, 'old-client')
    assert.equal(requests[1].authMode, 'none'); assert.equal(requests[1].header.Authorization, 'Bearer old-scope-token')
    assert.match(requests[1].url, /old-share\/visit-sessions\/9\/activity$/)
    accepted = undefined; await assert.rejects(options.recoverPending(entry, 'visitor', () => true), /未确认/)
    accepted = null; await assert.rejects(options.recoverPending(entry, 'visitor', () => true), /未确认/)
  })
  test(`${scope}补报open之后身份退出或上下文关闭不再发送activity`, async () => {
    const requests = [], wxApi = { login: ({ success }) => success({ code: 'code' }), setStorageSync() { throw new Error('should not write') } }
    const options = loadAdapter(scope, async request => { requests.push(request); return { visitorKey: 'visitor', trackingSessionId: 9, token: 'private-token' } }, wxApi)
    await assert.rejects(options.recoverPending({ shareCode: 'old', idempotencyKey: 'open', clientSessionKey: 'client', trackingSessionId: 9, activeDurationMs: 1000 }, 'visitor', () => false), /已关闭/)
    assert.equal(requests.length, 1)
  })
}

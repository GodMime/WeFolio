const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createVisitActivityLifecycle, VISIT_ACTIVITY_STORAGE_KEY } = require('../utils/visit-activity-lifecycle')
const { createVisitActivityContext, deviceSnapshot, normalizePendingQueue, QUEUE_RETENTION_MS } = require('../pages/portfolios/utils/visit-activity')

function harness(extra = {}) {
  let instant = 0
  const tasks = new Map()
  let nextTask = 0
  const storage = {}
  const lifecycle = createVisitActivityLifecycle()
  const sent = []
  const wxApi = { getStorageSync: (key) => storage[key], setStorageSync: (key, value) => { storage[key] = value }, removeStorageSync: (key) => { delete storage[key] } }
  const context = createVisitActivityContext({
    shareCode: 'share', portfolioType: 'PERSONAL', lifecycle, wxApi,
    now: () => instant, setTimer: (callback, delay) => { tasks.set(++nextTask, { callback, delay }); return nextTask }, clearTimer: (id) => tasks.delete(id),
    sendActivity: async (id, value) => { sent.push({ id, value }); return value }, ...extra
  })
  const owner = {}
  context.acceptSession({ visitorKey: 'visitor-1', trackingSessionId: 1, trackingActiveDurationMs: 0 })
  context.setDisplayable(true)
  context.show(owner)
  return { context, owner, lifecycle, storage, wxApi, sent, tasks, advance: (ms) => { instant += ms } }
}

test('两分包活动算法完全一致', () => {
  const base = path.resolve(__dirname, '../pages')
  assert.equal(fs.readFileSync(path.join(base, 'portfolios/utils/visit-activity.js'), 'utf8'), fs.readFileSync(path.join(base, 'team-portfolios/utils/visit-activity.js'), 'utf8'))
})

test('首次0必须上报；前后台与详情移交不重叠，重复hide不累计', async () => {
  const h = harness()
  await h.context.flush()
  assert.deepEqual(h.sent, [{ id: 1, value: 0 }])
  h.advance(5000); h.lifecycle.onHide(); h.advance(20000); h.lifecycle.onHide()
  h.lifecycle.onShow(); h.advance(3000)
  const detail = {}
  h.context.show(detail)
  h.context.hide(h.owner)
  h.advance(2000)
  h.context.hide(detail); h.context.hide(detail)
  await h.context.flush()
  assert.equal(h.context.snapshot().activeDurationMs, 10000)
  h.context.dispose()
})

for (const [local, server, expected] of [[60000, 45000, 70000], [0, 45000, 55000], [30000, 60000, 70000]]) {
  test(`恢复 max(${local},${server})+10000=${expected}`, () => {
    const h = harness()
    h.advance(local); h.context.beginRecovery(); h.advance(10000)
    h.context.acceptSession({ visitorKey: 'visitor-1', trackingSessionId: 1, trackingActiveDurationMs: server })
    assert.equal(h.context.snapshot().activeDurationMs, expected)
    h.context.acceptSession({ visitorKey: 'visitor-1', trackingSessionId: 1, trackingActiveDurationMs: server })
    assert.equal(h.context.snapshot().activeDurationMs, expected)
    h.context.dispose()
  })
}

test('迟到响应只确认旧值，不重复叠加恢复等待时间', async () => {
  let finish
  const h = harness({ sendActivity: () => new Promise((resolve) => { finish = resolve }) })
  h.advance(10000)
  const pending = h.context.flush(); await Promise.resolve()
  h.context.beginRecovery(); h.advance(5000)
  h.context.acceptSession({ visitorKey: 'visitor-1', trackingSessionId: 1, trackingActiveDurationMs: 10000 })
  finish(10000); await pending
  assert.equal(h.context.snapshot().activeDurationMs, 15000)
  assert.equal(h.context.snapshot().confirmedMs, 10000)
  h.context.invalidate()
})

test('回拨及异常跳变不记入有效前台时长', () => {
  const h = harness()
  h.advance(2000); h.context.snapshot()
  h.advance(-1000); h.context.snapshot()
  h.advance(3600000); h.context.snapshot()
  h.advance(2000)
  assert.equal(h.context.snapshot().activeDurationMs, 4000)
  h.context.invalidate()
})

test('失败保留最高累计且以15/30/60秒退避，隐藏退出后持久化不含令牌', async () => {
  const h = harness({ sendActivity: async () => { throw new Error('offline') } })
  h.advance(1000); await h.context.flush()
  assert.equal(Array.from(h.tasks.values()).at(-1).delay, 15000)
  h.advance(15000); await h.context.flush()
  assert.equal(Array.from(h.tasks.values()).at(-1).delay, 15000)
  assert.equal(h.context.snapshot().retryAt, 46000)
  h.advance(30000); await h.context.flush()
  assert.equal(Array.from(h.tasks.values()).at(-1).delay, 15000)
  assert.equal(h.context.snapshot().retryAt, 106000)
  h.context.dispose()
  const queue = h.storage[VISIT_ACTIVITY_STORAGE_KEY]
  assert.equal(queue.length, 1)
  assert.equal(queue[0].activeDurationMs, 46000)
  assert.equal('token' in queue[0], false)
  assert.equal('device' in queue[0], false)
})

test('设备采集默认关闭、能力失败仍可计时，设备清洗及限长', () => {
  let calls = 0
  const wxApi = { getDeviceInfo() { calls += 1; return { brand: 'A\nB', model: '测'.repeat(150), system: 'iOS', platform: 'ios' } } }
  assert.equal(deviceSnapshot(wxApi), undefined)
  assert.equal(calls, 0)
  assert.deepEqual(deviceSnapshot(wxApi, true), { brand: 'AB', model: '测'.repeat(128), system: 'iOS', platform: 'ios' })
  assert.equal(deviceSnapshot({ getDeviceInfo() { throw new Error('unavailable') } }, true), undefined)
})

test('补报队列只保留50条7天内记录并去除令牌，访客身份主动失效清理队列和计时', () => {
  const instant = QUEUE_RETENTION_MS + 100
  const entries = Array.from({ length: 60 }, (_, i) => ({ portfolioType: 'PERSONAL', shareCode: 'share', clientSessionKey: `c${i}`, idempotencyKey: `o${i}`, visitorKey: 'visitor-1', trackingSessionId: i + 1, updatedAt: instant - i, activeDurationMs: i, token: 'must-not-persist' }))
  entries.push({ ...entries[0], updatedAt: 0 })
  const queue = normalizePendingQueue(entries, instant)
  assert.equal(queue.length, 50)
  assert.equal(queue.some((entry) => 'token' in entry), false)
  const h = harness()
  h.lifecycle.invalidateAll(h.wxApi)
  assert.equal(h.context.snapshot().invalid, true)
  assert.equal(h.storage[VISIT_ACTIVITY_STORAGE_KEY], undefined)
  assert.equal(h.tasks.size, 0)
})

test('身份变化或后端未返回会话不采集、不恢复旧归属', () => {
  const h = harness()
  h.advance(1000)
  h.context.acceptSession({ visitorKey: 'other', trackingSessionId: 1, trackingActiveDurationMs: 0 })
  assert.equal(h.context.isInvalid(), true)
  const h2 = harness()
  h2.context.acceptSession({ visitorKey: 'visitor-1', underMaintenance: true })
  h2.advance(10000)
  assert.equal(h2.context.snapshot().sessionId, null)
  assert.equal(h2.tasks.size, 0)
  h2.context.dispose()
})

test('回拨后时钟追上原基准不会重复累计同一段时间，补报队列也不会被小幅回拨删除', async () => {
  const h = harness({ sendActivity: async () => { throw new Error('offline') } })
  h.advance(2000); await h.context.flush(); h.advance(-1000)
  assert.equal(h.context.snapshot().activeDurationMs, 2000)
  h.advance(1000); assert.equal(h.context.snapshot().activeDurationMs, 2000)
  assert.equal(normalizePendingQueue(h.storage[VISIT_ACTIVITY_STORAGE_KEY], 1000)[0].activeDurationMs, 2000)
  h.context.invalidate()
})

test('长网络请求期间心跳继续采样；恢复超过60秒仍保留所有有效前台时间', async () => {
  let finish
  const h = harness({ sendActivity: () => new Promise(resolve => { finish = resolve }) })
  const runHeartbeat = async () => {
    const [id, task] = Array.from(h.tasks.entries()).at(-1); h.tasks.delete(id)
    h.advance(task.delay); task.callback(); await Promise.resolve()
  }
  await runHeartbeat()
  assert.equal(h.context.snapshot().activeDurationMs, 15000)
  h.context.beginRecovery()
  for (let i = 0; i < 6; i++) await runHeartbeat()
  assert.equal(h.context.snapshot().waitingMs, 90000)
  assert.equal(h.storage[VISIT_ACTIVITY_STORAGE_KEY][0].activeDurationMs, 105000)
  h.context.acceptSession({ visitorKey: 'visitor-1', trackingSessionId: 1, trackingActiveDurationMs: 10000 })
  finish(15000); await Promise.resolve(); await Promise.resolve()
  assert.equal(h.context.snapshot().activeDurationMs, 105000)
  h.context.invalidate()
})

test('在恢复等待中退出将等待期前台时间一起持久化，迟到open不能继续计时', () => {
  const h = harness({ sendActivity: async () => { throw new Error('offline') } })
  h.advance(10000); h.context.beginRecovery(); h.advance(7000); h.context.dispose()
  assert.equal(h.storage[VISIT_ACTIVITY_STORAGE_KEY][0].activeDurationMs, 17000)
  assert.equal(h.context.isDisposed(), true)
  h.context.acceptSession({ visitorKey: 'visitor-1', trackingSessionId: 1, trackingActiveDurationMs: 60000 })
  assert.equal(h.context.snapshot().activeDurationMs, 17000)
})

function pendingEntry(overrides = {}) {
  return { portfolioType: 'PERSONAL', shareCode: 'old-share', clientSessionKey: 'old-client', idempotencyKey: 'old-open',
    visitorKey: 'visitor-1', trackingSessionId: 9, updatedAt: 0, activeDurationMs: 10000, ...overrides }
}

test('补报缺失或非法确认不删队列，并使用15/30/60秒退避', async () => {
  let accepted, calls = 0
  const h = harness({ recoverPending: async () => { calls++; return accepted } })
  h.storage[VISIT_ACTIVITY_STORAGE_KEY] = [pendingEntry()]
  await h.context.drainPending(); assert.equal(calls, 1); assert.equal(h.context.snapshot().pendingRetryAt, 15000)
  await h.context.drainPending(); assert.equal(calls, 1)
  h.advance(15000); accepted = NaN; await h.context.drainPending(); assert.equal(h.context.snapshot().pendingRetryAt, 45000)
  h.advance(30000); accepted = 9999; await h.context.drainPending(); assert.equal(h.context.snapshot().pendingRetryAt, 105000)
  assert.equal(h.storage[VISIT_ACTIVITY_STORAGE_KEY].some(entry => entry.clientSessionKey === 'old-client'), true)
  h.advance(60000); accepted = 10000; await h.context.drainPending()
  assert.equal(h.storage[VISIT_ACTIVITY_STORAGE_KEY].some(entry => entry.clientSessionKey === 'old-client'), false)
  h.context.invalidate()
})

test('旧补报确认不能清除等待期间写入的更高累计，身份退出后不再提交下一条', async () => {
  let finish, guard, calls = 0
  const h = harness({ recoverPending: async (entry, identity, isCurrent) => { calls++; guard = isCurrent; return new Promise(resolve => { finish = resolve }) } })
  h.storage[VISIT_ACTIVITY_STORAGE_KEY] = [pendingEntry()]
  const first = h.context.drainPending(); await Promise.resolve()
  h.storage[VISIT_ACTIVITY_STORAGE_KEY] = [pendingEntry({ activeDurationMs: 20000 })]
  finish(10000); await first
  assert.equal(h.storage[VISIT_ACTIVITY_STORAGE_KEY].find(entry => entry.clientSessionKey === 'old-client').activeDurationMs, 20000)
  const second = h.context.drainPending(); await Promise.resolve(); h.lifecycle.invalidateAll(h.wxApi)
  assert.equal(guard(), false); finish(20000); await second
  assert.equal(calls, 2); assert.equal(h.storage[VISIT_ACTIVITY_STORAGE_KEY], undefined)
})

test('恢复成功之后迟到的旧拒绝不会使新会话失效或设置新退避', async () => {
  let reject
  const h = harness({ sendActivity: () => new Promise((resolve, fail) => { reject = fail }) })
  h.advance(10000); const pending = h.context.flush(); await Promise.resolve(); h.context.beginRecovery(); h.advance(5000)
  h.context.acceptSession({ visitorKey: 'visitor-1', trackingSessionId: 1, trackingActiveDurationMs: 10000 })
  reject({ statusCode: 403 }); await pending
  assert.equal(h.context.isInvalid(), false); assert.equal(h.context.snapshot().activeDurationMs, 15000); assert.equal(h.context.snapshot().retryAt, 0)
  h.context.invalidate()
})

test('HTTP200业务失败按服务端准确文案区分永久失效与可重试失败', async () => {
  const { isPermanentActivityError } = require('../pages/portfolios/utils/visit-activity')
  for (const message of ['访问活动采集参数不合法', '访问活动会话不可用', '访问活动幂等键已用于其他业务', '作品集暂不可访问', '已发布团队作品集不可用']) {
    assert.equal(isPermanentActivityError({ statusCode: 200, message }), true)
    const h = harness({ sendActivity: async () => { throw { statusCode: 200, message } } })
    await h.context.flush(); assert.equal(h.context.isInvalid(), true); assert.equal(h.storage[VISIT_ACTIVITY_STORAGE_KEY].length, 0)
  }
  for (const message of ['前台停留时长不合法', '访问活动记录保存失败', '网络请求失败']) assert.equal(isPermanentActivityError({ statusCode: 200, message }), false)
  const h = harness({ recoverPending: async () => { throw { statusCode: 200, message: '访问活动会话不可用' } } })
  h.storage[VISIT_ACTIVITY_STORAGE_KEY] = [pendingEntry()]
  await h.context.drainPending(); assert.equal(h.storage[VISIT_ACTIVITY_STORAGE_KEY].some(entry => entry.clientSessionKey === 'old-client'), false)
  assert.equal(h.context.isInvalid(), false); h.context.invalidate()
})

test('可信原双键恢复同时取本地未确认高水位；新双键不会继承其它浏览的累计', () => {
  const h = harness({ clientSessionKey: 'old-client', idempotencyKey: 'old-open' })
  h.storage[VISIT_ACTIVITY_STORAGE_KEY] = [pendingEntry({ shareCode: 'share', trackingSessionId: 1, activeDurationMs: 60000 })]
  h.context.acceptSession({ visitorKey: 'visitor-1', trackingSessionId: 1, trackingActiveDurationMs: 45000 })
  h.advance(10000); assert.equal(h.context.snapshot().activeDurationMs, 70000); h.context.invalidate()
  const fresh = harness()
  fresh.storage[VISIT_ACTIVITY_STORAGE_KEY] = [pendingEntry({ shareCode: 'share', trackingSessionId: 1, activeDurationMs: 60000 })]
  fresh.context.acceptSession({ visitorKey: 'visitor-1', trackingSessionId: 1, trackingActiveDurationMs: 0 })
  assert.equal(fresh.context.snapshot().activeDurationMs, 0); fresh.context.invalidate()
})

test('页面已销毁的在途失败也受访客身份代次保护，失效清空后不会重新写入旧身份队列', async () => {
  let reject
  const h = harness({ sendActivity: () => new Promise((resolve, failure) => { reject = failure }) })
  h.advance(1000); const pending = h.context.flush(); await Promise.resolve(); h.context.dispose()
  assert.equal(h.context.isDisposed(), true)
  h.lifecycle.invalidateAll(h.wxApi)
  reject(new Error('old offline result')); await pending
  assert.equal(h.context.isInvalid(), true); assert.equal(h.storage[VISIT_ACTIVITY_STORAGE_KEY], undefined)
})

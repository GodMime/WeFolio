const { VISIT_ACTIVITY_STORAGE_KEY, visitActivityLifecycle } = require('../../../utils/visit-activity-lifecycle.js')

const REPORT_INTERVAL_MS = 15000
const MAX_CLOCK_GAP_MS = 60000
const MAX_QUEUE_SIZE = 50
const QUEUE_RETENTION_MS = 7 * 24 * 60 * 60 * 1000
const RETRY_DELAYS_MS = [15000, 30000, 60000]
// BusinessException保持HTTP200包裹，按已核实的服务端文案识别永久失效。
const PERMANENT_ACTIVITY_MESSAGES = [
  '访问活动采集参数不合法', '访问活动会话不可用', '访问活动幂等键已用于其他业务',
  '作品集暂不可访问', '已发布团队作品集不可用'
]
const DEVICE_LIMITS = { brand: 64, model: 128, system: 128, platform: 32 }
let sequence = 0

function createSessionKey(prefix) {
  sequence += 1
  return `${prefix}-${Date.now().toString(36)}-${sequence.toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

function nonnegative(value) {
  const number = Number(value)
  return Number.isSafeInteger(number) && number >= 0 ? number : 0
}

function deviceSnapshot(wxApi, enabled = false) {
  if (!enabled || !wxApi) return undefined
  try {
    const raw = wxApi.getDeviceInfo ? wxApi.getDeviceInfo() : wxApi.getSystemInfoSync ? wxApi.getSystemInfoSync() : {}
    const device = {}
    Object.keys(DEVICE_LIMITS).forEach((field) => {
      const value = Array.from(String(raw[field] || '').replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]/gu, '').trim()).slice(0, DEVICE_LIMITS[field]).join('')
      if (value) device[field] = value
    })
    return Object.keys(device).length ? device : undefined
  } catch (error) { return undefined }
}

function isPermanentActivityError(error) {
  return Boolean(error && ([403, 404, 410].includes(error.statusCode) || PERMANENT_ACTIVITY_MESSAGES.includes(error.message)))
}

function queueKey(item) { return `${item.portfolioType}:${item.shareCode}:${item.clientSessionKey}` }

// 只允许持久化补报所需归属和累计值，令牌、设备快照及页面数据均不入队。
function normalizePendingQueue(items, now) {
  return (Array.isArray(items) ? items : []).filter((item) => item && ['PERSONAL', 'TEAM'].includes(item.portfolioType)
    && typeof item.shareCode === 'string' && item.shareCode && typeof item.clientSessionKey === 'string' && item.clientSessionKey
    && typeof item.idempotencyKey === 'string' && item.idempotencyKey && item.visitorKey && item.trackingSessionId
    && Number.isFinite(item.updatedAt) && Math.abs(now - item.updatedAt) <= QUEUE_RETENTION_MS)
    .map((item) => ({
      portfolioType: item.portfolioType, shareCode: item.shareCode, clientSessionKey: item.clientSessionKey,
      idempotencyKey: item.idempotencyKey, visitorKey: String(item.visitorKey), trackingSessionId: item.trackingSessionId,
      sourceType: String(item.sourceType || ''), anonymousSessionId: String(item.anonymousSessionId || ''),
      activeDurationMs: nonnegative(item.activeDurationMs), updatedAt: Math.min(item.updatedAt, now)
    })).sort((a, b) => a.updatedAt - b.updatedAt).slice(-MAX_QUEUE_SIZE)
}

function createVisitActivityContext(options = {}) {
  const runtimeWx = options.wxApi || (typeof wx !== 'undefined' ? wx : null)
  const now = options.now || Date.now
  const schedule = options.setTimer || setTimeout
  const cancel = options.clearTimer || clearTimeout
  const lifecycle = options.lifecycle || visitActivityLifecycle
  const identityRevision = lifecycle.getIdentityRevision ? lifecycle.getIdentityRevision() : 0
  const device = deviceSnapshot(runtimeWx, options.deviceCollectionEnabled === true)
  let invalid = false
  let disposed = false
  let visible = false
  let displayable = false
  let sessionId = null
  let visitorKey = ''
  let accumulated = 0
  let confirmed = -1
  let anchor = null
  let recovery = null
  let timer = null
  let inFlight = null
  let retryCount = 0
  let retryAt = 0
  let draining = false
  let pendingRetryAt = 0
  let pendingRetryCount = 0
  let generation = 0
  let latestSession = null

  // 已退出页面的网络闭包仍受访客身份代次约束，防止身份失效后重新写回旧身份队列。
  function isInvalid() { return Boolean(invalid || (lifecycle.getIdentityRevision && lifecycle.getIdentityRevision() !== identityRevision)) }
  function loadQueue() {
    try { return normalizePendingQueue(runtimeWx && runtimeWx.getStorageSync ? runtimeWx.getStorageSync(VISIT_ACTIVITY_STORAGE_KEY) : [], now()) } catch (error) { return [] }
  }
  function saveQueue(items) {
    try { if (runtimeWx && runtimeWx.setStorageSync) runtimeWx.setStorageSync(VISIT_ACTIVITY_STORAGE_KEY, normalizePendingQueue(items, now())) } catch (error) { /* 存储失败时仍保留当前会话内存累计。 */ }
  }
  function removePending(key, accepted = Infinity) {
    const queue = loadQueue()
    // 补报等待期间可能写入更高累计；旧确认只能清理已经覆盖的值。
    const remaining = queue.filter((item) => queueKey(item) !== key || item.activeDurationMs > accepted)
    if (remaining.length !== queue.length) saveQueue(remaining)
  }
  function enqueue() {
    const pendingDuration = recovery ? recovery.localMs + recovery.waitingMs : accumulated
    if (isInvalid() || !displayable || !sessionId || !visitorKey || confirmed >= pendingDuration) return
    const entry = Object.assign(context.openOptions(), {
      portfolioType: context.portfolioType, shareCode: context.shareCode, clientSessionKey: context.clientSessionKey,
      trackingSessionId: sessionId, visitorKey, activeDurationMs: pendingDuration, updatedAt: now()
    })
    const entries = loadQueue()
    const previous = entries.find((item) => queueKey(item) === queueKey(entry))
    if (previous) entry.activeDurationMs = Math.max(previous.activeDurationMs, entry.activeDurationMs)
    saveQueue(entries.filter((item) => queueKey(item) !== queueKey(entry)).concat(entry))
  }
  function sample() {
    const instant = now()
    if (anchor !== null && visible && displayable && sessionId && !isInvalid()) {
      const delta = instant - anchor
      if (delta >= 0 && delta <= MAX_CLOCK_GAP_MS) {
        if (recovery) recovery.waitingMs += delta
        else accumulated += delta
      }
    }
    // 回拨不能降低计时基准，否则时钟追上原值时会重复累计同一段时间。
    anchor = visible && displayable && sessionId && !isInvalid() ? (anchor !== null && instant < anchor ? anchor : instant) : null
  }
  function stopTimer() { if (timer !== null) cancel(timer); timer = null }
  function armTimer() {
    stopTimer()
    if (disposed || isInvalid() || !displayable || !sessionId) return
    if (!visible && retryAt === 0 && pendingRetryAt === 0) return
    // 采样心跳独立于网络完成时间；恢复与退避期间仍每15秒保存前台计时基准。
    const due = [retryAt, pendingRetryAt].filter(value => value > now())
    const delay = due.length ? Math.max(1, Math.min(REPORT_INTERVAL_MS, ...due.map(value => value - now()))) : REPORT_INTERVAL_MS
    timer = schedule(() => {
      timer = null
      sample()
      context.flush().catch(() => {})
      context.drainPending().catch(() => {})
      armTimer()
    }, delay)
  }
  function checkIdentity(response) {
    const nextIdentity = String(response && response.visitorKey || '')
    if (visitorKey && nextIdentity !== visitorKey) { context.invalidate(); return false }
    return true
  }

  const context = {
    portfolioType: options.portfolioType === 'TEAM' ? 'TEAM' : 'PERSONAL',
    shareCode: String(options.shareCode || ''),
    idempotencyKey: options.idempotencyKey || createSessionKey('open'),
    clientSessionKey: options.clientSessionKey || createSessionKey('activity'),
    isInvalid,
    isDisposed: () => disposed,
    getSession: () => latestSession,
    openOptions() {
      return {
        shareCode: context.shareCode, sourceType: options.sourceType, anonymousSessionId: options.anonymousSessionId,
        idempotencyKey: context.idempotencyKey,
        tracking: Object.assign({ version: 1, clientSessionKey: context.clientSessionKey }, device ? { device } : {})
      }
    },
    acceptSession(response = {}) {
      if (isInvalid() || disposed || !checkIdentity(response)) return
      latestSession = response
      if (!response.trackingSessionId || response.underMaintenance) {
        sample(); sessionId = null; anchor = null; recovery = null; stopTimer()
        removePending(queueKey(context))
        return
      }
      if (sessionId && String(sessionId) !== String(response.trackingSessionId)) { context.invalidate(); return }
      sample()
      sessionId = response.trackingSessionId
      visitorKey = String(response.visitorKey || '')
      const stored = nonnegative(response.trackingActiveDurationMs)
      const pending = loadQueue().find(entry => queueKey(entry) === queueKey(context))
      const trustedPending = pending && pending.visitorKey === visitorKey && pending.idempotencyKey === context.idempotencyKey
        && String(pending.trackingSessionId) === String(sessionId) ? pending : null
      if (pending && !trustedPending) removePending(queueKey(context))
      if (recovery) {
        accumulated = Math.max(recovery.localMs, stored) + recovery.waitingMs
        recovery = null
      } else {
        // 只有经open重新验证的原双键、身份、会话均匹配，才恢复本地未确认高水位。
        // 活跃恢复中的队列可能已包含D，不能再加到localMs后重复计算等待期。
        accumulated = Math.max(accumulated, stored, trustedPending ? trustedPending.activeDurationMs : 0)
      }
      // 首次0样本也必须提交，使服务端历史NULL与实际0区分。
      confirmed = Math.max(confirmed, stored > 0 ? stored : -1)
      anchor = visible && displayable ? now() : null
      retryAt = 0; retryCount = 0
      enqueue(); armTimer()
      context.drainPending().catch(() => {})
    },
    beginRecovery() {
      if (isInvalid() || recovery || !sessionId) return
      sample(); recovery = { localMs: accumulated, waitingMs: 0 }; generation += 1; armTimer()
    },
    recoveryFailed(error) {
      if (isInvalid() || !recovery) return
      sample()
      accumulated = recovery.localMs + recovery.waitingMs
      recovery = null
      if (isPermanentActivityError(error)) { context.invalidate(); return }
      enqueue(); armTimer()
    },
    setDisplayable(value) {
      sample()
      displayable = Boolean(value) && !isInvalid()
      anchor = visible && displayable && sessionId ? now() : null
      if (displayable) { enqueue(); armTimer() } else stopTimer()
    },
    setVisible(value) {
      sample()
      visible = Boolean(value) && !isInvalid() && !disposed
      anchor = visible && displayable && sessionId ? now() : null
      if (!visible) { stopTimer(); context.flush().catch(() => {}) } else armTimer()
    },
    show(page) { if (!isInvalid() && !disposed) lifecycle.claim(context, page) },
    hide(page) { lifecycle.release(context, page) },
    async flush() {
      sample()
      if (isInvalid() || !displayable || !sessionId || typeof options.sendActivity !== 'function') return
      enqueue()
      if (recovery) return
      if (confirmed >= accumulated) { retryAt = 0; retryCount = 0; return }
      if (inFlight || now() < retryAt) return inFlight
      const sent = accumulated
      const revision = generation
      const promise = Promise.resolve().then(() => options.sendActivity(sessionId, sent, context))
      inFlight = promise
      try {
        const accepted = await promise
        if (isInvalid()) return
        const number = accepted
        if (!Number.isSafeInteger(number) || number < sent) throw new Error('停留上报响应无效')
        // 旧请求响应只确认已发送值，不修改恢复等待时间或计时基准。
        confirmed = Math.max(confirmed, Math.min(sent, number))
        if (revision === generation) { retryCount = 0; retryAt = 0 }
        if (confirmed >= accumulated) removePending(queueKey(context), confirmed)
        else enqueue()
      } catch (error) {
        if (!isInvalid()) {
          if (revision === generation && isPermanentActivityError(error)) context.invalidate()
          else {
            if (revision === generation) {
              retryAt = now() + RETRY_DELAYS_MS[Math.min(retryCount, RETRY_DELAYS_MS.length - 1)]
              retryCount += 1
            }
            enqueue()
          }
        }
      } finally {
        if (inFlight === promise) inFlight = null
        armTimer()
      }
    },
    async drainPending() {
      if (draining || disposed || isInvalid() || !visitorKey || now() < pendingRetryAt || typeof options.recoverPending !== 'function') return
      draining = true
      let failed = false
      const identity = visitorKey
      const current = () => !isInvalid() && !disposed && visitorKey === identity
      try {
        for (const entry of loadQueue()) {
          if (!current()) return
          if (entry.visitorKey !== identity) { removePending(queueKey(entry)); continue }
          if (entry.portfolioType !== context.portfolioType || queueKey(entry) === queueKey(context)) continue
          try {
            // 先经open校验原双键与当前身份，再向原会话提交最高累计。
            const accepted = await options.recoverPending(entry, identity, current)
            if (!current()) return
            if (!Number.isSafeInteger(accepted) || accepted < entry.activeDurationMs) throw new Error('停留补报未确认')
            removePending(queueKey(entry), accepted)
          } catch (error) {
            if (!current()) return
            if (isPermanentActivityError(error)) removePending(queueKey(entry))
            else failed = true
          }
        }
      } finally {
        draining = false
        if (current()) {
          pendingRetryAt = failed ? now() + RETRY_DELAYS_MS[Math.min(pendingRetryCount++, RETRY_DELAYS_MS.length - 1)] : 0
          if (!failed) pendingRetryCount = 0
          armTimer()
        }
      }
    },
    invalidate() {
      invalid = true; generation += 1; visible = false; anchor = null; recovery = null
      stopTimer(); removePending(queueKey(context)); lifecycle.unregister(context)
    },
    dispose() {
      sample()
      // 退出发生在open恢复等待中时，仍把等待期有效前台时间写进补报最高值。
      if (recovery) { accumulated = recovery.localMs + recovery.waitingMs; recovery = null; generation += 1 }
      visible = false; anchor = null; enqueue()
      context.flush().catch(() => {})
      disposed = true; stopTimer(); lifecycle.unregister(context)
    },
    snapshot() { sample(); return { sessionId, visitorKey, activeDurationMs: accumulated, confirmedMs: confirmed, recovering: Boolean(recovery), waitingMs: recovery ? recovery.waitingMs : 0, visible, invalid: isInvalid(), retryAt, pendingRetryAt } }
  }
  lifecycle.register(context)
  return context
}

module.exports = { isPermanentActivityError, createVisitActivityContext, deviceSnapshot, normalizePendingQueue, REPORT_INTERVAL_MS, MAX_QUEUE_SIZE, QUEUE_RETENTION_MS }

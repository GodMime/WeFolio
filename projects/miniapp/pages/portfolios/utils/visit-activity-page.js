const { request } = require('../../../utils/request.js')
const { openVisitorSession, requestWithVisitorSessionRefresh } = require('../../../utils/visitor-session')
const { createVisitActivityContext } = require('./visit-activity')

const API_PREFIX = '/api/visitor/portfolios'

function deviceCollectionEnabled() {
  try { const app = typeof getApp === 'function' ? getApp() : null; return Boolean(app && app.globalData && app.globalData.visitDeviceCollectionEnabled) } catch (error) { return false }
}

function createPortfolioVisitActivity(page, options = {}) {
  const wxApi = options.wxApi || (typeof wx !== 'undefined' ? wx : null)
  const requestFn = options.requestFn || request
  const activity = createVisitActivityContext({
    portfolioType: 'PERSONAL', shareCode: page.data.shareCode, sourceType: page.visitorSourceType,
    anonymousSessionId: page.anonymousSessionId, wxApi, deviceCollectionEnabled: deviceCollectionEnabled(),
    sendActivity: (sessionId, activeDurationMs, browserContext) => requestWithVisitorSessionRefresh({
      url: `${API_PREFIX}/${encodeURIComponent(page.data.shareCode)}/visit-sessions/${sessionId}/activity`,
      method: 'PUT', authMode: 'visitor', data: { activeDurationMs }
    }, { shareCode: page.data.shareCode, browserContext, wxApi, requestFn }),
    recoverPending: async (entry, currentIdentity, isCurrent) => {
      const session = await openVisitorSession(entry.shareCode, {
        ...entry, tracking: { version: 1, clientSessionKey: entry.clientSessionKey }, persistToken: false, wxApi, requestFn
      })
      if (typeof isCurrent === 'function' && !isCurrent()) throw new Error('访问上下文已关闭')
      if (!session.token) throw new Error('原访问会话令牌缺失')
      if (String(session.visitorKey || '') !== currentIdentity || String(session.trackingSessionId || '') !== String(entry.trackingSessionId)) {
        throw Object.assign(new Error('原访问会话不可恢复'), { statusCode: 403 })
      }
      const accepted = await requestFn({
        url: `${API_PREFIX}/${encodeURIComponent(entry.shareCode)}/visit-sessions/${entry.trackingSessionId}/activity`,
        method: 'PUT', authMode: 'none', header: { Authorization: `Bearer ${session.token}` }, data: { activeDurationMs: entry.activeDurationMs }
      })
      if (!Number.isSafeInteger(accepted) || accepted < entry.activeDurationMs) throw new Error('停留补报未确认')
      return accepted
    }
  })
  activity.onRefresh = (response) => page.applyVisitorOpenResponse(response, page.data.portfolio.activeMenuKey)
  return activity
}

module.exports = { createPortfolioVisitActivity }

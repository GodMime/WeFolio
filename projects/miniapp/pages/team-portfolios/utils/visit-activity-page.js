const { request } = require('../../../utils/request.js')
const { ADVANCED_TEAM_VISITOR_PREFIX, openTeamVisitorSession, requestWithTeamVisitorSessionRefresh } = require('./team-visitor-session')
const { createVisitActivityContext } = require('./visit-activity')

const API_PREFIX = '/api/visitor/team-portfolios'

function deviceCollectionEnabled() {
  try { const app = typeof getApp === 'function' ? getApp() : null; return Boolean(app && app.globalData && app.globalData.visitDeviceCollectionEnabled) } catch (error) { return false }
}

function createPortfolioVisitActivity(page, options = {}) {
  const wxApi = options.wxApi || (typeof wx !== 'undefined' ? wx : null)
  const requestFn = options.requestFn || request
  const apiPrefix = options.apiPrefix === ADVANCED_TEAM_VISITOR_PREFIX ? ADVANCED_TEAM_VISITOR_PREFIX : API_PREFIX
  const activity = createVisitActivityContext({
    portfolioType: 'TEAM', shareCode: page.data.shareCode, sourceType: page.data.sourceType,
    ...(apiPrefix === ADVANCED_TEAM_VISITOR_PREFIX ? { visitorApiPrefix: apiPrefix } : {}),
    anonymousSessionId: page.anonymousSessionId, idempotencyKey: page.data.pendingOpenKey || undefined,
    wxApi, deviceCollectionEnabled: deviceCollectionEnabled(),
    sendActivity: (sessionId, activeDurationMs, browserContext) => requestWithTeamVisitorSessionRefresh({
      shareCode: page.data.shareCode, browserContext, wxApi, requestFn,
      requestOptions: { url: `${apiPrefix}/${encodeURIComponent(page.data.shareCode)}/visit-sessions/${sessionId}/activity`,
        method: 'PUT', data: { activeDurationMs } }
    }),
    recoverPending: async (entry, currentIdentity, isCurrent) => {
      const entryPrefix = entry.visitorApiPrefix === ADVANCED_TEAM_VISITOR_PREFIX ? ADVANCED_TEAM_VISITOR_PREFIX : API_PREFIX
      const session = await openTeamVisitorSession({
        ...entry, tracking: { version: 1, clientSessionKey: entry.clientSessionKey }, persistToken: false, wxApi, requestFn
      })
      if (typeof isCurrent === 'function' && !isCurrent()) throw new Error('访问上下文已关闭')
      if (!session.token) throw new Error('原访问会话令牌缺失')
      if (String(session.visitorKey || '') !== currentIdentity || String(session.trackingSessionId || '') !== String(entry.trackingSessionId)) {
        throw Object.assign(new Error('原访问会话不可恢复'), { statusCode: 403 })
      }
      const accepted = await requestFn({
        url: `${entryPrefix}/${encodeURIComponent(entry.shareCode)}/visit-sessions/${entry.trackingSessionId}/activity`,
        method: 'PUT', authMode: 'none', header: { Authorization: `Bearer ${session.token}` }, data: { activeDurationMs: entry.activeDurationMs }
      })
      if (!Number.isSafeInteger(accepted) || accepted < entry.activeDurationMs) throw new Error('停留补报未确认')
      return accepted
    }
  })
  activity.onRefresh = (response) => page.applySession(response, page.data.portfolio.activeMenuKey)
  return activity
}

module.exports = { createPortfolioVisitActivity }

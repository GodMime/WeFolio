const { request } = require('../../../utils/request.js')
const { normalizeId } = require('../../../utils/id.js')

const TEAM_VISITOR_PREFIX = '/api/visitor/team-portfolios'
const QR_EVENT_TYPE = 'QR_CODE_INTERACTED'
const QR_ACTIONS = Object.freeze(['CLICK', 'LONG_PRESS'])
const IDEMPOTENCY_KEY_MAX_LENGTH = 64

function text(value) {
  return String(value || '').trim()
}

function requireIdempotencyKey(value) {
  const normalized = text(value)
  if (!normalized || normalized.length > IDEMPOTENCY_KEY_MAX_LENGTH) throw new Error('幂等键必须为1至64个字符')
  return normalized
}

function normalizeTeamVisitorPortfolio(payload = {}) {
  const renderData = payload.renderData && typeof payload.renderData === 'object' ? payload.renderData : {}
  const source = Array.isArray(renderData.components) ? renderData.components : []
  return {
    shareCode: text(payload.shareCode || renderData.shareCode),
    portfolioId: normalizeId(payload.portfolioId || renderData.portfolioId),
    teamId: normalizeId(payload.teamId || renderData.teamId),
    teamName: text(payload.teamName || renderData.teamName),
    title: text(payload.title || renderData.title),
    publishedRevision: Number(payload.publishedRevision) || 0,
    visitRecordId: normalizeId(payload.visitRecordId || renderData.visitRecordId),
    visitorKey: text(payload.visitorKey),
    needVisitorProfile: payload.needVisitorProfile === true,
    visitorProfileToken: text(payload.visitorProfileToken),
    preview: renderData.preview === true,
    underMaintenance: renderData.underMaintenance === true,
    share: renderData.share && typeof renderData.share === 'object' ? renderData.share : {},
    components: source.map((item = {}, index) => ({
      componentKey: text(item.componentKey),
      componentType: text(item.componentType),
      name: text(item.name),
      sortOrder: Number.isFinite(Number(item.sortOrder)) ? Number(item.sortOrder) : index,
      data: item.data && typeof item.data === 'object' ? item.data : {}
    })).filter((item) => item.componentKey && item.componentType)
      .sort((left, right) => left.sortOrder - right.sortOrder)
  }
}

function teamVisitorEndpoint(shareCode, suffix) {
  const code = text(shareCode)
  if (!code) throw new Error('分享码无效')
  return `${TEAM_VISITOR_PREFIX}/${encodeURIComponent(code)}${suffix}`
}

function submitTeamVisitorEvent(requestFn = request, shareCode, payload = {}) {
  if (payload.eventType === QR_EVENT_TYPE && !QR_ACTIONS.includes(payload.action)) {
    return Promise.reject(new Error('二维码交互动作无效'))
  }
  let idempotencyKey
  try { idempotencyKey = requireIdempotencyKey(payload.idempotencyKey) } catch (error) { return Promise.reject(error) }
  return requestFn({ url: teamVisitorEndpoint(shareCode, '/events'), method: 'POST', authMode: 'visitor', data: Object.assign({}, payload, { idempotencyKey }) })
}

function fetchTeamVisitorScheduleOptions(requestFn = request, shareCode, componentKey) {
  return requestFn({
    url: teamVisitorEndpoint(shareCode, '/schedule-options'),
    authMode: 'visitor',
    data: { componentKey: text(componentKey) }
  })
}

function queryTeamVisitorSchedule(requestFn = request, shareCode, payload = {}) {
  let idempotencyKey
  try { idempotencyKey = requireIdempotencyKey(payload.idempotencyKey) } catch (error) { return Promise.reject(error) }
  return requestFn({ url: teamVisitorEndpoint(shareCode, '/schedule-query'), method: 'POST', authMode: 'visitor', data: Object.assign({}, payload, { idempotencyKey }) })
}

module.exports = {
  QR_ACTIONS,
  QR_EVENT_TYPE,
  TEAM_VISITOR_PREFIX,
  fetchTeamVisitorScheduleOptions,
  normalizeTeamVisitorPortfolio,
  queryTeamVisitorSchedule,
  submitTeamVisitorEvent
}

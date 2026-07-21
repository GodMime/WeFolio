const { request } = require('../../../utils/request.js')
const { normalizeId } = require('../../../utils/id.js')

const TEAM_CONTACT_CONSENT_VERSION = 'v1'
const TEAM_CONTACT_SUCCESS_TEXT = '已提交给团队'
const IDEMPOTENCY_KEY_MAX_LENGTH = 64

function text(value) {
  return String(value || '').trim()
}

function createTeamContactLeadForm(source = {}) {
  return {
    contactName: text(source.contactName),
    phone: text(source.phone),
    wechat: text(source.wechat),
    desiredSchedule: text(source.desiredSchedule),
    needs: text(source.needs)
  }
}

function requireIdempotencyKey(value) {
  const normalized = text(value)
  if (!normalized || normalized.length > IDEMPOTENCY_KEY_MAX_LENGTH) throw new Error('线索提交幂等键必须为1至64个字符')
  return normalized
}

function validateTeamContactLeadForm(form = {}) {
  const normalized = createTeamContactLeadForm(form)
  if (!normalized.contactName) return { valid: false, message: '请填写称呼' }
  if (!normalized.phone && !normalized.wechat) return { valid: false, message: '请至少填写手机号或微信号' }
  return { valid: true, message: '' }
}

function buildTeamContactLeadPayload(form = {}, context = {}) {
  return Object.assign(createTeamContactLeadForm(form), {
    visitRecordId: normalizeId(context.visitRecordId),
    sourceType: text(context.sourceType),
    consentVersion: text(context.consentVersion) || TEAM_CONTACT_CONSENT_VERSION,
    idempotencyKey: requireIdempotencyKey(context.idempotencyKey)
  })
}

function submitTeamContactLead(requestFn = request, shareCode, form, context) {
  const validation = validateTeamContactLeadForm(form)
  if (!validation.valid) return Promise.reject(new Error(validation.message))
  let payload
  try { payload = buildTeamContactLeadPayload(form, context) } catch (error) { return Promise.reject(error) }
  return requestFn({
    url: `/api/visitor/team-portfolios/${encodeURIComponent(text(shareCode))}/contact-leads`,
    method: 'POST',
    authMode: 'visitor',
    data: payload
  })
}

function normalizeLead(item = {}) {
  return {
    leadId: normalizeId(item.leadId),
    teamId: normalizeId(item.teamId),
    teamName: text(item.teamName),
    portfolioId: normalizeId(item.portfolioId),
    portfolioTitle: text(item.portfolioTitle),
    portfolioShareCode: text(item.portfolioShareCode),
    portfolioRevision: Number(item.portfolioRevision) || 0,
    visitRecordId: normalizeId(item.visitRecordId),
    contactName: text(item.contactName),
    phone: text(item.phone),
    wechat: text(item.wechat),
    desiredSchedule: text(item.desiredSchedule),
    needs: text(item.needs),
    sourceType: text(item.sourceType),
    sourceText: text(item.sourceText),
    consentVersion: text(item.consentVersion),
    followStatus: text(item.followStatus),
    followStatusText: text(item.followStatusText),
    followNote: text(item.followNote),
    submittedAt: text(item.submittedAt)
  }
}

function normalizeTeamContactLeadPage(payload = {}) {
  return {
    page: Math.max(1, Number(payload.page) || 1),
    pageSize: Math.max(1, Number(payload.pageSize) || 20),
    hasMore: payload.hasMore === true,
    items: (Array.isArray(payload.items) ? payload.items : []).map(normalizeLead)
  }
}

async function fetchTeamContactLeads(requestFn = request, teamId, pageOptions = {}) {
  const id = normalizeId(teamId)
  if (!id) throw new Error('团队 ID 无效')
  const data = { page: Math.max(1, Number(pageOptions.page) || 1), pageSize: Math.max(1, Number(pageOptions.pageSize) || 20) }
  return normalizeTeamContactLeadPage(await requestFn({ url: `/api/mine/teams/${id}/contact-leads`, data }))
}

function updateTeamContactLeadFollowStatus(requestFn = request, teamId, leadId, followStatus, followNote) {
  const normalizedTeamId = normalizeId(teamId)
  const normalizedLeadId = normalizeId(leadId)
  if (!normalizedTeamId || !normalizedLeadId) return Promise.reject(new Error('线索 ID 无效'))
  const query = `followStatus=${encodeURIComponent(text(followStatus))}&followNote=${encodeURIComponent(text(followNote))}`
  return requestFn({
    url: `/api/mine/teams/${normalizedTeamId}/contact-leads/${normalizedLeadId}/follow-status?${query}`,
    method: 'POST'
  })
}

module.exports = {
  TEAM_CONTACT_CONSENT_VERSION,
  TEAM_CONTACT_SUCCESS_TEXT,
  buildTeamContactLeadPayload,
  createTeamContactLeadForm,
  fetchTeamContactLeads,
  normalizeTeamContactLeadPage,
  submitTeamContactLead,
  updateTeamContactLeadFollowStatus,
  validateTeamContactLeadForm
}

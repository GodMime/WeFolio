const { request } = require('../../../utils/request.js')
const { normalizeId } = require('../../../utils/id.js')
const { handleMaintainerAuthRequired } = require('../../../utils/session.js')

const TEAM_PORTFOLIOS_ENDPOINT = '/api/mine/team-portfolios'
const MAINTAINABLE_TEAMS_ENDPOINT = `${TEAM_PORTFOLIOS_ENDPOINT}/maintainable-teams`
const TEAM_SELECT_URL = '/pages/team-portfolios/team-select/team-select'
const TEAM_PORTFOLIO_UNAVAILABLE_MESSAGES = Object.freeze({
  FEATURE_DISABLED: '团队作品集功能暂未开放',
  TEAM_UNAVAILABLE: '当前团队不可用',
  INVALID: '暂无权限访问该团队作品集'
})

function text(value) {
  return String(value || '').trim()
}

function nonNegativeInteger(value) {
  const number = Number(value)
  return Number.isInteger(number) && number >= 0 ? number : 0
}

function normalizeTeamPortfolioSummary(item = {}) {
  return {
    portfolioId: normalizeId(item.portfolioId),
    shareCode: text(item.shareCode),
    title: text(item.title),
    coverUrl: text(item.coverUrl),
    teamId: normalizeId(item.teamId),
    teamName: text(item.teamName),
    currentRole: text(item.currentRole),
    canMaintain: item.canMaintain === true,
    canShare: item.canShare === true,
    publicationStatus: text(item.publicationStatus),
    draftRevision: nonNegativeInteger(item.draftRevision),
    publishedRevision: nonNegativeInteger(item.publishedRevision),
    updatedAt: text(item.updatedAt)
  }
}

function normalizeTeamPortfolioList(payload) {
  const source = Array.isArray(payload) ? payload : (payload && Array.isArray(payload.items) ? payload.items : [])
  return source.map(normalizeTeamPortfolioSummary).filter((item) => item.portfolioId && item.teamId)
}

function normalizeMaintainableTeams(payload) {
  const source = Array.isArray(payload) ? payload : []
  return source.map((item = {}) => ({
    teamId: normalizeId(item.teamId),
    teamName: text(item.teamName),
    avatarUrl: text(item.avatarUrl),
    currentRole: text(item.currentRole)
  })).filter((item) => item.teamId && (!item.currentRole || item.currentRole === 'OWNER' || item.currentRole === 'MANAGER'))
}

function resolveTeamCreateRoute(teams) {
  const normalized = normalizeMaintainableTeams(teams)
  if (normalized.length === 0) return { action: 'UNAVAILABLE', teamId: null, url: '' }
  if (normalized.length === 1) return { action: 'CREATE', teamId: normalized[0].teamId, url: '' }
  return { action: 'SELECT', teamId: null, url: TEAM_SELECT_URL }
}

async function fetchTeamPortfolioList(requestFn = request) {
  return normalizeTeamPortfolioList(await requestFn({ url: TEAM_PORTFOLIOS_ENDPOINT }))
}

async function fetchMaintainableTeams(requestFn = request) {
  return normalizeMaintainableTeams(await requestFn({ url: MAINTAINABLE_TEAMS_ENDPOINT }))
}

function teamPortfolioEndpoint(portfolioId, suffix = '') {
  const id = normalizeId(portfolioId)
  if (!id) throw new Error('作品集 ID 无效')
  return `${TEAM_PORTFOLIOS_ENDPOINT}/${id}${suffix}`
}

function createStandardTeamPortfolio(requestFn = request, teamId, config) {
  const id = normalizeId(teamId)
  if (!id) return Promise.reject(new Error('请选择可维护团队'))
  return requestFn({ url: `/api/mine/teams/${id}/portfolios/standard`, method: 'POST', data: { config: config || null } })
}

function deleteTeamPortfolio(requestFn = request, portfolioId) {
  return requestFn({ url: teamPortfolioEndpoint(portfolioId, '/delete'), method: 'POST' })
}

function publishTeamPortfolio(requestFn = request, portfolioId, draftRevision, idempotencyKey) {
  const normalizedKey = text(idempotencyKey)
  if (!normalizedKey || normalizedKey.length > 64) {
    return Promise.reject(new Error('发布幂等键必须为1至64个字符'))
  }
  return requestFn({
    url: teamPortfolioEndpoint(portfolioId, '/publish'),
    method: 'POST',
    data: {
      draftRevision: nonNegativeInteger(draftRevision),
      idempotencyKey: normalizedKey
    }
  })
}

function recordTeamPortfolioShare(requestFn = request, portfolioId, shareChannel, shareScene) {
  return requestFn({
    url: teamPortfolioEndpoint(portfolioId, '/share-records'),
    method: 'POST',
    data: { shareChannel: text(shareChannel), shareScene: text(shareScene) }
  })
}

function resolveTeamPortfolioUnavailableReason(error = {}) {
  const message = text(error.message || error.errMsg)
  if (message === '团队作品集功能暂未开放') return 'FEATURE_DISABLED'
  if (message === '当前作品集暂未开放访问' || message === '团队作品集不存在或无访问权限' || message === '团队不存在或无访问权限' || message === '分享码无效') return 'INVALID'
  if (message === '团队当前不可用' || message === '团队不可用') return 'TEAM_UNAVAILABLE'
  return ''
}

function resolveTeamPortfolioUnavailableMessage(error = {}) {
  return TEAM_PORTFOLIO_UNAVAILABLE_MESSAGES[resolveTeamPortfolioUnavailableReason(error)] || ''
}

function showTeamPortfolioUnavailableToast(error = {}, wxApi = wx) {
  const title = resolveTeamPortfolioUnavailableMessage(error)
  if (!title) return false
  wxApi.showToast({ title, icon: 'none' })
  return true
}

function handleTeamMaintainerAuthError(error = {}) {
  if (!error || error.authRequired !== true) return false
  handleMaintainerAuthRequired(error.message)
  return true
}

module.exports = {
  MAINTAINABLE_TEAMS_ENDPOINT, TEAM_PORTFOLIOS_ENDPOINT, createStandardTeamPortfolio,
  deleteTeamPortfolio, fetchMaintainableTeams, fetchTeamPortfolioList, handleTeamMaintainerAuthError,
  nonNegativeInteger, normalizeMaintainableTeams, normalizeTeamPortfolioList, normalizeTeamPortfolioSummary,
  publishTeamPortfolio, recordTeamPortfolioShare, resolveTeamCreateRoute, resolveTeamPortfolioUnavailableMessage,
  resolveTeamPortfolioUnavailableReason, showTeamPortfolioUnavailableToast, teamPortfolioEndpoint, text
}

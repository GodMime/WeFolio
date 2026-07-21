const { request } = require('../../../utils/request.js')
const { normalizeId } = require('../../../utils/id.js')
const teamPortfolioList = require('./team-portfolio-list.js')
const {
  TEAM_PORTFOLIOS_ENDPOINT,
  nonNegativeInteger,
  teamPortfolioEndpoint,
  text
} = teamPortfolioList

const COMPONENT_LIBRARY_ENDPOINT = `${TEAM_PORTFOLIOS_ENDPOINT}/component-library`
const STANDARD_TEAM_SCHEMA_VERSION = 'standard-team-v1'
const IDEMPOTENCY_KEY_MAX_LENGTH = 64

function requireIdempotencyKey(value) {
  const normalized = text(value)
  if (!normalized || normalized.length > IDEMPOTENCY_KEY_MAX_LENGTH) throw new Error('幂等键必须为1至64个字符')
  return normalized
}

function normalizeTeamPortfolioConfig(payload = {}) {
  const share = payload.share && typeof payload.share === 'object' ? payload.share : {}
  const components = Array.isArray(payload.components) ? payload.components : []
  return {
    schemaVersion: text(payload.schemaVersion) || STANDARD_TEAM_SCHEMA_VERSION,
    share: {
      title: text(share.title),
      description: text(share.description),
      coverUrl: text(share.coverUrl)
    },
    components: components.map((item = {}, index) => ({
      componentKey: text(item.componentKey),
      componentType: text(item.componentType),
      sortOrder: Number.isFinite(Number(item.sortOrder)) ? Number(item.sortOrder) : index,
      enabled: item.enabled !== false,
      config: item.config && typeof item.config === 'object' ? item.config : {}
    })).filter((item) => item.componentKey && item.componentType)
      .sort((left, right) => left.sortOrder - right.sortOrder)
  }
}

function normalizeTeamPortfolioDetail(payload = {}) {
  return {
    portfolioId: normalizeId(payload.portfolioId),
    shareCode: text(payload.shareCode),
    ownerType: text(payload.ownerType),
    ownerId: normalizeId(payload.ownerId),
    templateType: text(payload.templateType),
    status: text(payload.status),
    publicationStatus: text(payload.publicationStatus),
    draftRevision: nonNegativeInteger(payload.draftRevision),
    publishedRevision: nonNegativeInteger(payload.publishedRevision),
    config: normalizeTeamPortfolioConfig(payload.config),
    renderData: payload.renderData && typeof payload.renderData === 'object' ? payload.renderData : null
  }
}

function fetchTeamComponentLibrary(requestFn = request) {
  return requestFn({ url: COMPONENT_LIBRARY_ENDPOINT })
}

function fetchTeamPortfolioDetail(requestFn = request, portfolioId) {
  return requestFn({ url: teamPortfolioEndpoint(portfolioId) }).then(normalizeTeamPortfolioDetail)
}

function saveTeamPortfolioDraft(requestFn = request, portfolioId, config, clientRevision, idempotencyKey) {
  let normalizedIdempotencyKey
  try { normalizedIdempotencyKey = requireIdempotencyKey(idempotencyKey) } catch (error) { return Promise.reject(error) }
  return requestFn({
    url: teamPortfolioEndpoint(portfolioId, '/draft'),
    method: 'POST',
    data: { config, clientRevision: nonNegativeInteger(clientRevision), idempotencyKey: normalizedIdempotencyKey }
  })
}

function publishTeamPortfolio(requestFn = request, portfolioId, draftRevision, idempotencyKey) {
  let normalizedIdempotencyKey
  try { normalizedIdempotencyKey = requireIdempotencyKey(idempotencyKey) } catch (error) { return Promise.reject(error) }
  return requestFn({
    url: teamPortfolioEndpoint(portfolioId, '/publish'),
    method: 'POST',
    data: { draftRevision: nonNegativeInteger(draftRevision), idempotencyKey: normalizedIdempotencyKey }
  })
}

function previewTeamPortfolio(requestFn = request, portfolioId, published = false) {
  return requestFn({ url: teamPortfolioEndpoint(portfolioId, published ? '/published-preview' : '/preview') })
}

module.exports = Object.assign({}, teamPortfolioList, {
  COMPONENT_LIBRARY_ENDPOINT,
  STANDARD_TEAM_SCHEMA_VERSION,
  fetchTeamComponentLibrary,
  fetchTeamPortfolioDetail,
  normalizeTeamPortfolioConfig,
  normalizeTeamPortfolioDetail,
  previewTeamPortfolio,
  publishTeamPortfolio,
  saveTeamPortfolioDraft
})

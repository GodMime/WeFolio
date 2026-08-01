const { request } = require('../../../utils/request.js')
const { normalizeId } = require('../../../utils/id.js')
const {
  LEGACY_TEAM_FONT_SIZE_RPX,
  buildPortfolioTextTypography
} = require('../../../utils/portfolio-text-typography.js')

const TEAM_VISITOR_PREFIX = '/api/visitor/team-portfolios'
const QR_EVENT_TYPE = 'QR_CODE_INTERACTED'
const QR_ACTIONS = Object.freeze(['CLICK', 'LONG_PRESS'])
const IDEMPOTENCY_KEY_MAX_LENGTH = 64
const DEFAULT_BACKGROUND_COLOR = '#FFFFFF'
const SINGLE_WORK_VIEW_MEDIA_TYPES = Object.freeze(['IMAGE', 'ANIMATION'])

function text(value) {
  return String(value || '').trim()
}

function requireIdempotencyKey(value) {
  const normalized = text(value)
  if (!normalized || normalized.length > IDEMPOTENCY_KEY_MAX_LENGTH) throw new Error('幂等键必须为1至64个字符')
  return normalized
}

function normalizeRenderComponent(item = {}, index = 0) {
  const componentType = text(item.componentType)
  const sourceData = item.data && typeof item.data === 'object' ? item.data : {}
  const data = componentType === 'TEXT_SECTION'
    ? Object.assign(
        {},
        sourceData,
        buildPortfolioTextTypography(
          sourceData,
          LEGACY_TEAM_FONT_SIZE_RPX
        )
      )
    : sourceData
  return {
    componentKey: text(item.componentKey),
    componentType,
    name: text(item.name),
    sortOrder: Number.isFinite(Number(item.sortOrder)) ? Number(item.sortOrder) : index,
    data
  }
}

function normalizeRenderComponents(items = []) {
  return (Array.isArray(items) ? items : [])
    .map(normalizeRenderComponent)
    .filter((item) => item.componentKey && item.componentType)
    .sort((left, right) => left.sortOrder - right.sortOrder)
}

function normalizeBackgroundColor(value) {
  const color = text(value).toUpperCase()
  return /^#[0-9A-F]{6}$/.test(color) ? color : DEFAULT_BACKGROUND_COLOR
}

function themeModeFromColor(backgroundColor) {
  const color = normalizeBackgroundColor(backgroundColor)
  const red = Number.parseInt(color.slice(1, 3), 16)
  const green = Number.parseInt(color.slice(3, 5), 16)
  const blue = Number.parseInt(color.slice(5, 7), 16)
  return (red * 299 + green * 587 + blue * 114) / 1000 < 128 ? 'dark' : 'light'
}

function normalizeBottomNavigation(bottomNav = {}) {
  if (!bottomNav || bottomNav.enabled !== true || !Array.isArray(bottomNav.items) || bottomNav.items.length < 2) {
    return { enabled: false, items: [] }
  }
  return {
    enabled: true,
    items: bottomNav.items.slice(0, 4).map((item = {}, index) => {
      const normalized = {
        key: text(item.key),
        title: text(item.title)
      }
      if (index > 0) normalized.components = normalizeRenderComponents(item.components)
      return normalized
    }).filter((item) => item.key && item.title)
  }
}

function normalizeTeamVisitorPortfolio(payload = {}) {
  const renderData = payload.renderData && typeof payload.renderData === 'object' ? payload.renderData : {}
  const components = normalizeRenderComponents(renderData.components)
  const backgroundColor = normalizeBackgroundColor(renderData.style && renderData.style.backgroundColor)
  const themeMode = ['light', 'dark'].includes(text(renderData.style && renderData.style.themeMode))
    ? text(renderData.style.themeMode)
    : themeModeFromColor(backgroundColor)
  const bottomNav = normalizeBottomNavigation(renderData.bottomNav)
  const activeMenuKey = bottomNav.enabled ? bottomNav.items[0].key : ''
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
    style: { backgroundColor, themeMode },
    themeMode,
    components,
    bottomNav,
    activeMenuKey,
    activeComponents: components
  }
}

function switchTeamPortfolioMenu(portfolio = {}, menuKey = '') {
  const bottomNav = portfolio.bottomNav || { enabled: false, items: [] }
  if (!bottomNav.enabled || !Array.isArray(bottomNav.items) || !bottomNav.items.length) {
    return Object.assign({}, portfolio, {
      activeMenuKey: '',
      activeComponents: Array.isArray(portfolio.components) ? portfolio.components : []
    })
  }
  const target = bottomNav.items.find((item) => item.key === text(menuKey)) || bottomNav.items[0]
  return Object.assign({}, portfolio, {
    activeMenuKey: target.key,
    activeComponents: target === bottomNav.items[0]
      ? (Array.isArray(portfolio.components) ? portfolio.components : [])
      : (Array.isArray(target.components) ? target.components : [])
  })
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

/**
 * 构造团队单作品查看事件，动图沿用普通作品查看语义。
 *
 * @param {object} detail 单作品组件事件详情
 * @returns {object|null} 合法的查看事件，非法媒体类型返回空
 */
function buildTeamSingleWorkViewEvent(detail = {}) {
  const work = detail.work || {}
  const mediaType = text(work.mediaType)
  if (!SINGLE_WORK_VIEW_MEDIA_TYPES.includes(mediaType)) {
    return null
  }
  return {
    eventType: 'WORK_VIEWED',
    componentKey: text(detail.componentKey),
    workId: normalizeId(work.workId),
    mediaType
  }
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
  buildTeamSingleWorkViewEvent,
  fetchTeamVisitorScheduleOptions,
  normalizeTeamVisitorPortfolio,
  queryTeamVisitorSchedule,
  submitTeamVisitorEvent,
  switchTeamPortfolioMenu
}

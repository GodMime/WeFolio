const { confirmAdvancedPortfolioPublish } = require('./utils/advanced-editor-page')
const { isAdvanced, advancedQuery, advancedEndpoint } = require('./utils/advanced-portfolios')
const { buildPortfolioMiniappCodePath } = require('./utils/portfolio-miniapp-code')
const { request } = require('../../utils/request.js')
const { normalizeId } = require('../../utils/id')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const { buildPublishPayload } = require('./utils/portfolios')
const {
  deleteTeamPortfolio,
  fetchMaintainableTeams,
  fetchTeamPortfolioList,
  handleTeamMaintainerAuthError,
  publishTeamPortfolio,
  recordTeamPortfolioShare,
  resolveTeamCreateRoute,
  showTeamPortfolioUnavailableToast
} = require('./utils/team-portfolio-list.js')
const { confirmPortfolioPublishDisclaimer } = require('./utils/portfolio-publish-disclaimer')
const { buildPreviewVersionOptions, calculatePreviewVersionMenuLayout } = require('./utils/preview-version-menu')

const PORTFOLIOS_API_URL = '/api/mine/portfolios'
const PORTFOLIO_DELETE_API_PREFIX = '/api/mine/portfolios/delete'
const EDIT_PAGE_URL = '/pages/portfolios/standard-edit/portfolio-standard-edit'
const PREVIEW_PAGE_URL = '/pages/portfolios/standard-preview/portfolio-standard-preview'
const VISITOR_PORTFOLIO_SHARE_PATH_PREFIX = '/pages/portfolios/visitor-portfolio/visitor-portfolio?shareCode='
const SCHEDULE_PAGE_URL = '/pages/schedule/schedule'
const WORKS_PAGE_URL = '/pages/works/works'
const MINE_PAGE_URL = '/pages/index/index'
const DEFAULT_COVER_URL = '/assets/system/work-logo-100kb.jpg'
const UNAVAILABLE_TOAST_TITLE = '暂未开放，即将发布'
const SHARE_CHANNEL_WECHAT_CARD = 'WECHAT_CARD'
const SHARE_SCENE_PORTFOLIO_LIST = 'PORTFOLIO_LIST'
const SWIPE_REVEAL_THRESHOLD = -32
const SWIPE_CLOSE_THRESHOLD = 24
const SWIPE_VERTICAL_TOLERANCE = 48
const DELETE_CONFIRM_COLOR = '#a9354f'
const PUBLICATION_STATUS_PUBLISHED = 'PUBLISHED'
const PUBLICATION_STATUS_OFFLINE = 'OFFLINE'
const ACTION_TYPE_SHARE = 'SHARE'
const ACTION_TYPE_EDIT = 'EDIT'
const ACTION_TYPE_PUBLISH = 'PUBLISH'
const IDEMPOTENCY_PREFIX_PUBLISH = 'publish'
const PORTFOLIO_TITLE_SCROLL_MIN_LENGTH = 7
const OWNER_TYPE_USER = 'USER'
const OWNER_TYPE_TEAM = 'TEAM'
const OWNER_SWITCH_DURATION_MS = 240
const TEAM_SELECT_SHEET_ROW_HEIGHT = 112
const TEAM_SELECT_SHEET_ROW_GAP = 14
const TEAM_SELECT_SHEET_MAX_HEIGHT = 520
const TEAM_EDIT_URL = '/pages/team-portfolios/standard-edit/team-portfolio-standard-edit'
const TEAM_PREVIEW_URL = '/pages/team-portfolios/standard-preview/team-portfolio-standard-preview'
const TEAM_VISITOR_SHARE_PATH_PREFIX = '/pages/team-portfolios/visitor-portfolio/team-visitor-portfolio?shareCode='
const SHARE_SCENE_TEAM_PORTFOLIO_LIST = 'TEAM_PORTFOLIO_LIST'
const IDEMPOTENCY_PREFIX_TEAM_PUBLISH = 'team-publish'
const SHARE_UNAVAILABLE_MESSAGE = '当前作品集暂不可分享'
const PERSONAL_PORTFOLIO_REFERENCED_ERROR_CODE = 'PERSONAL_PORTFOLIO_REFERENCED'
const PREVIEW_SCOPE_DRAFT = 'draft'
const PREVIEW_SCOPE_PUBLISHED = 'published'
const PREVIEW_DRAFT_DESCRIPTION = '最近保存的内容'
const PREVIEW_PUBLISHED_DESCRIPTION = '访客当前看到的内容'
const PERSONAL_PREVIEW_ANCHOR_PREFIX = '#personal-preview-'
const TEAM_PREVIEW_ANCHOR_PREFIX = '#team-preview-'

function makeIdempotencyKey(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

function defaultString(value, fallback = '') {
  const text = String(value || '').trim()
  return text || fallback
}

function shouldScrollPortfolioTitle(title) {
  return defaultString(title).length >= PORTFOLIO_TITLE_SCROLL_MIN_LENGTH
}

function resolveStatus(item = {}) {
  if (item.publicationStatus === PUBLICATION_STATUS_PUBLISHED) {
    return {
      statusText: '已发布',
      statusTone: 'published',
      actionText: '分享',
      actionType: ACTION_TYPE_SHARE
    }
  }
  if (item.publicationStatus === PUBLICATION_STATUS_OFFLINE) {
    return {
      statusText: '已下线',
      statusTone: 'muted',
      actionText: '编辑',
      actionType: ACTION_TYPE_EDIT
    }
  }
  return {
    statusText: '草稿',
    statusTone: 'draft',
    actionText: '发布',
    actionType: ACTION_TYPE_PUBLISH
  }
}

function normalizePortfolioItem(item = {}) {
  const status = resolveStatus(item)
  const isPublished = item.publicationStatus === PUBLICATION_STATUS_PUBLISHED
  const isDraft = status.actionType === ACTION_TYPE_PUBLISH
  return Object.assign({}, item, status, {
    title: defaultString(item.title, '未命名作品集'),
    titleScrollable: shouldScrollPortfolioTitle(item.title),
    coverUrl: defaultString(item.coverUrl, DEFAULT_COVER_URL),
    updatedText: item.updatedAt ? `最近更新 ${String(item.updatedAt).slice(5, 10)}` : '最近更新',
    coverAlt: defaultString(item.title, '作品集封面'),
    showPublishedPreview: isPublished,
    showDraftPreview: isDraft
  })
}

function normalizeTeamPortfolioItem(item = {}) {
  const normalized = normalizePortfolioItem(item)
  const isPublished = item.publicationStatus === PUBLICATION_STATUS_PUBLISHED
  const canShare = isPublished && item.canShare === true
  return Object.assign({}, normalized, {
    title: defaultString(item.title || item.teamName, '未命名团队作品集'),
    teamName: defaultString(item.teamName, '团队'),
    canShare,
    canPreviewDraft: !isPublished,
    canPublishedPreview: canShare,
    canDelete: item.canMaintain === true
  })
}

function buildSummary(portfolios = []) {
  const publishedCount = portfolios.filter((item) => item.publicationStatus === PUBLICATION_STATUS_PUBLISHED).length
  const draftCount = portfolios.filter((item) => item.publicationStatus !== PUBLICATION_STATUS_PUBLISHED).length
  return {
    publishedText: `${publishedCount} 已发布`,
    draftText: `${draftCount} 草稿`
  }
}

function buildSharePath(shareCode) {
  return `${VISITOR_PORTFOLIO_SHARE_PATH_PREFIX}${encodeURIComponent(defaultString(shareCode))}`
}

function buildTeamSharePath(shareCode, templateType = '') {
  const prefix = templateType === 'ADVANCED' ? '/pages/team-portfolios/advanced-visitor/team-visitor-portfolio?shareCode=' : TEAM_VISITOR_SHARE_PATH_PREFIX
  return `${prefix}${encodeURIComponent(defaultString(shareCode))}`
}

function requestForPortfolio(item) {
  return options => {
    if (!isAdvanced(item)) return request(options)
    const suffix = options.url.replace(/^\/api\/mine\/(?:portfolios|team-portfolios)\/[^/]+/, '')
    return request({ ...options, url: advancedEndpoint(item.portfolioId, suffix) })
  }
}

function buildTimelineGuidePath(ownerType, portfolioId, shareCode, templateType) {
  const sharePath = ownerType === OWNER_TYPE_TEAM
    ? buildTeamSharePath(shareCode, templateType)
    : buildSharePath(shareCode)
  return `${sharePath}&shareGuide=timeline&sharePortfolioId=${encodeURIComponent(normalizeId(portfolioId))}`
}

function buildTeamSelectSheetListHeight(teams) {
  const count = Array.isArray(teams) ? teams.length : 0
  if (count <= 0) {
    return 0
  }
  return Math.min(
    TEAM_SELECT_SHEET_MAX_HEIGHT,
    count * TEAM_SELECT_SHEET_ROW_HEIGHT + (count - 1) * TEAM_SELECT_SHEET_ROW_GAP
  )
}

Page({
  data: {
    ownerType: OWNER_TYPE_USER,
    ownerTitle: '个人作品集',
    loading: true,
    // 首次成功后保留列表或空态，后续刷新不再用骨架遮住已有内容。
    loaded: false,
    pullDownRefreshing: false,
    errorMessage: '',
    portfolios: [],
    displayPortfolios: [],
    summary: buildSummary([]),
    revealedPortfolioId: null,
    portfolioTouchStart: null,
    deletingPortfolioId: null,
    switching: false,
    teamLoading: false,
    teamLoaded: false,
    teamErrorMessage: '',
    teamPullDownRefreshing: false,
    teamPortfolios: [],
    teamDisplayPortfolios: [],
    teamSummary: buildSummary([]),
    revealedTeamPortfolioId: null,
    teamPortfolioTouchStart: null,
    maintainableTeams: [],
    maintainableTeamsLoaded: false,
    noMaintainableTeam: false,
    creatingTeamPortfolio: false,
    teamSelectSheetVisible: false,
    selectedCreateTeamId: null,
    teamSelectSheetListHeight: 0,
    deletingTeamPortfolioId: null,
    publishingTeamPortfolioId: null,
    sharingTeamPortfolioId: null,
    shareSheetVisible: false,
    shareActionPending: false,
    shareTarget: null,
    previewVersionMenu: null,
    tabs: [
      { key: 'schedule', label: '档期', icon: 'schedule' },
      { key: 'work', label: '作品', icon: 'work' },
      { key: 'portfolio', label: '作品集', icon: 'portfolio', active: true },
      { key: 'mine', label: '我的', icon: 'mine' }
    ]
  },

  onLoad(options = {}) {
    if (options.ownerType === OWNER_TYPE_TEAM) {
      this.setData({
        ownerType: OWNER_TYPE_TEAM,
        ownerTitle: '团队作品集',
        // 直接进入团队页时，个人列表留到首次切回后再请求。
        loading: false,
        teamLoading: true,
        switching: false
      })
    }
  },

  onShow() {
    if (this.data.ownerType === OWNER_TYPE_TEAM) {
      return this.bootstrapTeam()
    }
    return this.bootstrap()
  },

  onUnload() {
    this.closePreviewVersionMenu()
    if (this.ownerSwitchTimer) {
      clearTimeout(this.ownerSwitchTimer)
      this.ownerSwitchTimer = null
    }
  },

  onHide() {
    this.closePreviewVersionMenu()
  },

  onPullDownRefresh() {
    if (this.data.ownerType === OWNER_TYPE_TEAM) {
      return this.handleTeamPullDownRefresh()
    }
    return this.handlePullDownRefresh()
  },

  handlePullDownRefresh() {
    this.closePreviewVersionMenu()
    if (this.data.pullDownRefreshing) {
      if (wx.stopPullDownRefresh) {
        wx.stopPullDownRefresh()
      }
      return Promise.resolve()
    }
    this.setData({ pullDownRefreshing: true })
    return Promise.resolve()
      .then(() => this.bootstrap())
      .finally(() => {
        this.setData({ pullDownRefreshing: false })
        if (wx.stopPullDownRefresh) {
          wx.stopPullDownRefresh()
        }
      })
  },

  handleTeamPullDownRefresh() {
    this.closePreviewVersionMenu()
    if (this.data.teamPullDownRefreshing) {
      if (wx.stopPullDownRefresh) {
        wx.stopPullDownRefresh()
      }
      return Promise.resolve()
    }
    this.setData({ teamPullDownRefreshing: true })
    return Promise.resolve()
      .then(() => this.bootstrapTeam())
      .finally(() => {
        this.setData({ teamPullDownRefreshing: false })
        if (wx.stopPullDownRefresh) {
          wx.stopPullDownRefresh()
        }
      })
  },

  bootstrap() {
    this.closePreviewVersionMenu()
    this.resetShareSheetState()
    if (!hasLocalToken()) {
      wx.navigateTo({ url: '/pages/login/login' })
      return
    }
    this.setData({ loading: true, errorMessage: '', revealedPortfolioId: null })
    return request({
      url: PORTFOLIOS_API_URL,
      data: { ownerType: this.data.ownerType }
    }).then((response) => {
      const portfolios = response && Array.isArray(response.portfolios) ? response.portfolios : []
      this.setData({
        portfolios,
        displayPortfolios: portfolios.map(normalizePortfolioItem),
        summary: buildSummary(portfolios),
        loaded: true
      })
    }).catch((error) => {
      if (error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({ errorMessage: error.message || '作品集加载失败' })
    }).finally(() => {
      this.setData({ loading: false })
    })
  },

  async bootstrapTeam({ onlyIfNeeded = false } = {}) {
    this.closePreviewVersionMenu()
    if (onlyIfNeeded && (this.data.teamLoaded || this.data.teamLoading)) {
      return
    }
    this.resetShareSheetState()
    this.setData({
      teamLoading: true,
      teamErrorMessage: '',
      noMaintainableTeam: false,
      revealedTeamPortfolioId: null,
      teamPortfolioTouchStart: null,
      teamSelectSheetVisible: false,
      selectedCreateTeamId: null,
      teamSelectSheetListHeight: 0
    })
    try {
      const teamResults = await Promise.all([
        fetchTeamPortfolioList(request),
        fetchMaintainableTeams(request)
      ])
      const portfolios = teamResults[0]
      const maintainableTeams = teamResults[1]
      this.setData({
        teamPortfolios: portfolios,
        teamDisplayPortfolios: portfolios.map(normalizeTeamPortfolioItem),
        teamSummary: buildSummary(portfolios),
        maintainableTeams,
        maintainableTeamsLoaded: true,
        noMaintainableTeam: maintainableTeams.length === 0,
        teamLoaded: true
      })
    } catch (error) {
      if (handleTeamMaintainerAuthError(error) || showTeamPortfolioUnavailableToast(error)) {
        return
      }
      this.setData({ teamErrorMessage: '团队作品集加载失败，请重试' })
    } finally {
      this.setData({ teamLoading: false, teamPullDownRefreshing: false })
    }
  },

  handleOwnerTypeTap(event) {
    this.closePreviewVersionMenu()
    const ownerType = event.currentTarget.dataset.type === OWNER_TYPE_TEAM ? OWNER_TYPE_TEAM : OWNER_TYPE_USER
    if (ownerType === this.data.ownerType || this.data.switching) {
      return
    }
    this.setData({
      ownerType,
      ownerTitle: ownerType === OWNER_TYPE_TEAM ? '团队作品集' : '个人作品集',
      switching: true,
      shareSheetVisible: false,
      shareActionPending: false,
      shareTarget: null,
      revealedPortfolioId: null,
      portfolioTouchStart: null,
      revealedTeamPortfolioId: null,
      teamPortfolioTouchStart: null,
      teamSelectSheetVisible: false,
      selectedCreateTeamId: null,
      teamSelectSheetListHeight: 0
    })
    if (ownerType === OWNER_TYPE_TEAM) {
      this.bootstrapTeam({ onlyIfNeeded: true })
    } else if (!this.data.loaded && !this.data.loading) {
      // 团队直达入口尚未加载个人列表，首次切回时补齐请求。
      this.bootstrap()
    }
    if (this.ownerSwitchTimer) {
      clearTimeout(this.ownerSwitchTimer)
    }
    this.ownerSwitchTimer = setTimeout(() => {
      this.setData({ switching: false })
      this.ownerSwitchTimer = null
    }, OWNER_SWITCH_DURATION_MS)
  },

  handleCreateAdvancedPersonal() {
    wx.navigateTo({ url: '/pages/portfolios/advanced-ai/advanced-ai' })
  },

  handleCreateAdvancedTeam() {
    this.setData({ advancedCreating: true })
    const route = resolveTeamCreateRoute(this.data.maintainableTeams)
    if (route.action === 'UNAVAILABLE') { this.setData({ noMaintainableTeam: true }); return }
    if (route.action === 'SELECT') {
      this.setData({ teamSelectSheetVisible: true, selectedCreateTeamId: null, teamSelectSheetListHeight: buildTeamSelectSheetListHeight(this.data.maintainableTeams) })
      return
    }
    wx.navigateTo({ url: `/pages/team-portfolios/advanced-ai/advanced-ai?teamId=${route.teamId}` })
  },

  handleCreateStandardPersonal() {
    wx.navigateTo({ url: EDIT_PAGE_URL })
  },

  handleCreateStandardTeam() {
    this.setData({ advancedCreating: false })
    if (this.data.creatingTeamPortfolio) {
      return
    }
    const route = resolveTeamCreateRoute(this.data.maintainableTeams)
    if (route.action === 'UNAVAILABLE') {
      this.setData({ noMaintainableTeam: true })
      return
    }
    if (route.action === 'SELECT') {
      this.setData({
        teamSelectSheetVisible: true,
        selectedCreateTeamId: null,
        teamSelectSheetListHeight: buildTeamSelectSheetListHeight(this.data.maintainableTeams)
      })
      return
    }
    wx.navigateTo({ url: `${TEAM_EDIT_URL}?teamId=${route.teamId}` })
  },

  noop() {},

  handleTeamSelectTap(event) {
    const teamId = normalizeId(event.currentTarget.dataset.id)
    const selectedTeam = (this.data.maintainableTeams || [])
      .find((team) => normalizeId(team.teamId) === teamId)
    if (!selectedTeam) {
      return
    }
    this.setData({ selectedCreateTeamId: teamId })
  },

  handleCloseTeamSelectSheet() {
    this.setData({
      teamSelectSheetVisible: false,
      selectedCreateTeamId: null,
      teamSelectSheetListHeight: 0
    })
  },

  handleConfirmTeamSelect() {
    if (this.data.creatingTeamPortfolio) {
      return
    }
    const teamId = normalizeId(this.data.selectedCreateTeamId)
    const selectedTeam = (this.data.maintainableTeams || [])
      .find((team) => normalizeId(team.teamId) === teamId)
    if (!selectedTeam) {
      return
    }
    this.handleCloseTeamSelectSheet()
    wx.navigateTo({ url: this.data.advancedCreating ? `/pages/team-portfolios/advanced-ai/advanced-ai?teamId=${teamId}` : `${TEAM_EDIT_URL}?teamId=${teamId}` })
  },

  handleTeamPortfolioCardTap(event) {
    const item = event.currentTarget.dataset.item
    if (!item) {
      return
    }
    if (this.data.revealedTeamPortfolioId === item.portfolioId) {
      this.setData({ revealedTeamPortfolioId: null })
      return
    }
    if (item.canMaintain) {
      wx.navigateTo({ url: `${TEAM_EDIT_URL}?portfolioId=${item.portfolioId}${advancedQuery(item)}` })
    }
  },

  handleTeamPreviewTap(event) {
    const item = event.currentTarget.dataset.item
    const scope = event.currentTarget.dataset.scope || 'published'
    if (!item || (scope === 'published' && !item.canShare)) {
      return
    }
    if (this.data.revealedTeamPortfolioId === item.portfolioId) {
      this.setData({ revealedTeamPortfolioId: null })
      return
    }
    if (item.publicationStatus === PUBLICATION_STATUS_PUBLISHED) {
      if (!item.canShare) return
      this.openPreviewVersionMenu(OWNER_TYPE_TEAM, item)
      return
    }
    wx.navigateTo({ url: `${TEAM_PREVIEW_URL}?portfolioId=${item.portfolioId}&scope=${scope}${advancedQuery(item)}` })
  },

  findTeamPortfolioById(portfolioId) {
    return (this.data.teamDisplayPortfolios || []).find((item) => normalizeId(item.portfolioId) === portfolioId) || null
  },

  handleTeamPortfolioTouchStart(event) {
    if (this.data.deletingTeamPortfolioId || this.data.ownerType !== OWNER_TYPE_TEAM) {
      this.setData({ teamPortfolioTouchStart: null })
      return
    }
    const portfolioId = normalizeId(event.currentTarget.dataset.id)
    const portfolio = this.findTeamPortfolioById(portfolioId)
    if (!portfolio || !portfolio.canDelete) {
      this.setData({ teamPortfolioTouchStart: null })
      return
    }
    const touch = (event.touches && event.touches[0]) || {}
    this.setData({
      teamPortfolioTouchStart: {
        portfolioId,
        x: touch.clientX || 0,
        y: touch.clientY || 0
      }
    })
  },

  handleTeamPortfolioTouchMove() {
    this.closePreviewVersionMenu()
  },

  handleTeamPortfolioTouchEnd(event) {
    const start = this.data.teamPortfolioTouchStart
    if (!start || !start.portfolioId) {
      return
    }
    const touch = (event.changedTouches && event.changedTouches[0]) || {}
    const deltaX = (touch.clientX || start.x) - start.x
    const deltaY = Math.abs((touch.clientY || start.y) - start.y)
    if (deltaY <= SWIPE_VERTICAL_TOLERANCE && deltaX < SWIPE_REVEAL_THRESHOLD) {
      this.setData({
        revealedTeamPortfolioId: start.portfolioId,
        teamPortfolioTouchStart: null
      })
      return
    }
    if (deltaX > SWIPE_CLOSE_THRESHOLD || Math.abs(deltaX) < 8) {
      this.setData({
        revealedTeamPortfolioId: null,
        teamPortfolioTouchStart: null
      })
      return
    }
    this.setData({ teamPortfolioTouchStart: null })
  },

  handleTeamPortfolioTouchCancel() {
    this.setData({ teamPortfolioTouchStart: null })
  },

  async handleTeamPublishTap(event) {
    const item = event.currentTarget.dataset.item
    if (!item || !item.canMaintain || this.data.publishingTeamPortfolioId) {
      return
    }
    if (this.data.revealedTeamPortfolioId === item.portfolioId) {
      this.setData({ revealedTeamPortfolioId: null })
      return
    }
    const confirmed = isAdvanced(item) ? await confirmAdvancedPortfolioPublish(request) : await confirmPortfolioPublishDisclaimer()
    if (!confirmed) {
      return
    }
    this.setData({ publishingTeamPortfolioId: item.portfolioId })
    try {
      await publishTeamPortfolio(
        requestForPortfolio(item),
        item.portfolioId,
        item.draftRevision,
        makeIdempotencyKey(IDEMPOTENCY_PREFIX_TEAM_PUBLISH)
      )
      wx.showToast({ title: '已发布', icon: 'success' })
      await this.bootstrapTeam()
    } catch (error) {
      if (handleTeamMaintainerAuthError(error) || showTeamPortfolioUnavailableToast(error)) {
        return
      }
      wx.showToast({ title: '发布失败，请重试', icon: 'none' })
    } finally {
      this.setData({ publishingTeamPortfolioId: null })
    }
  },

  async handleTeamDeleteTap(event) {
    const item = event.currentTarget.dataset.item
    if (!item || !item.canMaintain || this.data.deletingTeamPortfolioId) {
      return
    }
    const confirmed = await new Promise((resolve) => {
      wx.showModal({
        title: '删除团队作品集',
        content: '删除后不可恢复，确认继续吗？',
        success: (result) => resolve(result.confirm === true),
        fail: () => resolve(false)
      })
    })
    if (!confirmed) {
      return
    }
    this.setData({ deletingTeamPortfolioId: item.portfolioId })
    try {
      await deleteTeamPortfolio(requestForPortfolio(item), item.portfolioId)
      await this.bootstrapTeam()
    } catch (error) {
      if (handleTeamMaintainerAuthError(error) || showTeamPortfolioUnavailableToast(error)) {
        return
      }
      wx.showToast({ title: '删除失败，请重试', icon: 'none' })
    } finally {
      this.setData({
        deletingTeamPortfolioId: null,
        revealedTeamPortfolioId: null,
        teamPortfolioTouchStart: null
      })
    }
  },

  handleUnavailableTap() {
    wx.showToast({ title: UNAVAILABLE_TOAST_TITLE, icon: 'none' })
  },

  findPortfolioById(portfolioId) {
    return (this.data.displayPortfolios || []).find((item) => normalizeId(item.portfolioId) === portfolioId) || null
  },

  handlePortfolioTouchStart(event) {
    if (this.data.deletingPortfolioId || this.data.ownerType !== 'USER') {
      this.setData({ portfolioTouchStart: null })
      return
    }
    const portfolioId = normalizeId(event.currentTarget.dataset.id)
    const portfolio = this.findPortfolioById(portfolioId)
    if (!portfolio) {
      this.setData({ portfolioTouchStart: null })
      return
    }
    const touch = (event.touches && event.touches[0]) || {}
    this.setData({
      portfolioTouchStart: {
        portfolioId,
        x: touch.clientX || 0,
        y: touch.clientY || 0
      }
    })
  },

  handlePortfolioTouchMove() {
    this.closePreviewVersionMenu()
  },

  handlePortfolioTouchEnd(event) {
    const start = this.data.portfolioTouchStart
    if (!start || !start.portfolioId) {
      return
    }
    const touch = (event.changedTouches && event.changedTouches[0]) || {}
    const deltaX = (touch.clientX || start.x) - start.x
    const deltaY = Math.abs((touch.clientY || start.y) - start.y)
    if (deltaY <= SWIPE_VERTICAL_TOLERANCE && deltaX < SWIPE_REVEAL_THRESHOLD) {
      this.setData({
        revealedPortfolioId: start.portfolioId,
        portfolioTouchStart: null
      })
      return
    }
    if (deltaX > SWIPE_CLOSE_THRESHOLD || Math.abs(deltaX) < 8) {
      this.setData({
        revealedPortfolioId: null,
        portfolioTouchStart: null
      })
      return
    }
    this.setData({ portfolioTouchStart: null })
  },

  handlePortfolioTouchCancel() {
    this.setData({ portfolioTouchStart: null })
  },

  handlePortfolioCardTap(event) {
    const targetDataset = event.target && event.target.dataset ? event.target.dataset : {}
    if (targetDataset.action) {
      return
    }
    const portfolioId = normalizeId(event.currentTarget.dataset.id)
    if (portfolioId) {
      if (this.data.revealedPortfolioId === portfolioId) {
        this.setData({ revealedPortfolioId: null })
        return
      }
      wx.navigateTo({ url: `${EDIT_PAGE_URL}?portfolioId=${portfolioId}${advancedQuery(this.findPortfolioById(portfolioId))}` })
    }
  },

  handlePrimaryActionTap(event) {
    const portfolioId = normalizeId(event.currentTarget.dataset.id)
    const actionType = event.currentTarget.dataset.action
    if (!portfolioId) {
      return
    }
    if (this.data.revealedPortfolioId === portfolioId) {
      this.setData({ revealedPortfolioId: null })
      return
    }
    if (actionType === ACTION_TYPE_PUBLISH) {
      return this.publishPortfolioFromList(portfolioId)
    }
    wx.navigateTo({ url: `${EDIT_PAGE_URL}?portfolioId=${portfolioId}${advancedQuery(this.findPortfolioById(portfolioId))}` })
  },

  resolveShareTarget(target = this.data.shareTarget) {
    const ownerType = target && target.ownerType
    const portfolioId = normalizeId(target && target.portfolioId)
    if (!portfolioId) {
      return null
    }
    if (ownerType === OWNER_TYPE_TEAM) {
      const portfolio = this.findTeamPortfolioById(portfolioId)
      if (!portfolio || portfolio.publicationStatus !== PUBLICATION_STATUS_PUBLISHED || !portfolio.canShare || !defaultString(portfolio.shareCode)) {
        return null
      }
      return { ownerType, portfolio }
    }
    if (ownerType === OWNER_TYPE_USER) {
      const portfolio = this.findPortfolioById(portfolioId)
      if (!portfolio || portfolio.publicationStatus !== PUBLICATION_STATUS_PUBLISHED || !defaultString(portfolio.shareCode)) {
        return null
      }
      return { ownerType, portfolio }
    }
    return null
  },

  resetShareSheetState() {
    this.setData({
      shareSheetVisible: false,
      shareActionPending: false,
      shareTarget: null
    })
  },

  handleShareTap(event) {
    if (this.data.shareActionPending) {
      return
    }
    const dataset = event.currentTarget && event.currentTarget.dataset ? event.currentTarget.dataset : {}
    const ownerType = dataset.ownerType === OWNER_TYPE_TEAM ? OWNER_TYPE_TEAM : dataset.ownerType === OWNER_TYPE_USER ? OWNER_TYPE_USER : ''
    const portfolioId = normalizeId(dataset.id)
    if (ownerType === OWNER_TYPE_TEAM && this.data.revealedTeamPortfolioId === portfolioId) {
      this.setData({ revealedTeamPortfolioId: null })
      return
    }
    if (ownerType === OWNER_TYPE_USER && this.data.revealedPortfolioId === portfolioId) {
      this.setData({ revealedPortfolioId: null })
      return
    }
    const shareTarget = { ownerType, portfolioId }
    if (!this.resolveShareTarget(shareTarget)) {
      wx.showToast({ title: SHARE_UNAVAILABLE_MESSAGE, icon: 'none' })
      return
    }
    this.setData({
      shareSheetVisible: true,
      shareActionPending: false,
      shareTarget
    })
  },

  handleCloseShareSheet() {
    if (this.data.shareActionPending) {
      return
    }
    this.resetShareSheetState()
  },

  handleQrCodeShare() {
    if (this.data.shareActionPending) return
    const resolved = this.resolveShareTarget()
    if (!resolved) {
      const hadTarget = Boolean(this.data.shareTarget)
      this.resetShareSheetState()
      if (hadTarget) wx.showToast({ title: SHARE_UNAVAILABLE_MESSAGE, icon: 'none' })
      return
    }
    const url = buildPortfolioMiniappCodePath(resolved.ownerType, resolved.portfolio.portfolioId, resolved.portfolio.templateType)
    this.setData({ shareSheetVisible: false, shareActionPending: true, shareTarget: null })
    wx.navigateTo({
      url,
      fail: () => wx.showToast({ title: '页面打开失败，请重试', icon: 'none' }),
      complete: () => this.setData({ shareActionPending: false })
    })
  },

  handleTimelineShare() {
    if (this.data.shareActionPending) {
      return
    }
    const resolved = this.resolveShareTarget()
    if (!resolved) {
      const hadTarget = Boolean(this.data.shareTarget)
      this.resetShareSheetState()
      if (hadTarget) {
        wx.showToast({ title: SHARE_UNAVAILABLE_MESSAGE, icon: 'none' })
      }
      return
    }
    const url = buildTimelineGuidePath(
      resolved.ownerType,
      resolved.portfolio.portfolioId,
      resolved.portfolio.shareCode,
      resolved.portfolio.templateType
    )
    this.setData({
      shareSheetVisible: false,
      shareActionPending: true,
      shareTarget: null
    })
    wx.navigateTo({
      url,
      fail: () => {
        wx.showToast({ title: '页面打开失败，请重试', icon: 'none' })
      },
      complete: () => {
        this.setData({ shareActionPending: false })
      }
    })
  },

  handlePublishedPreviewTap(event) {
    const portfolioId = normalizeId(event.currentTarget.dataset.id)
    const item = this.findPortfolioById(portfolioId)
    if (!portfolioId || !item || item.publicationStatus !== PUBLICATION_STATUS_PUBLISHED) {
      return
    }
    if (this.data.revealedPortfolioId === portfolioId) {
      this.setData({ revealedPortfolioId: null })
      return
    }
    this.openPreviewVersionMenu(OWNER_TYPE_USER, item)
  },

  /** 定位回调只对本次目标生效，收起或换目标后不得重新打开菜单。 */
  openPreviewVersionMenu(ownerType, item) {
    const portfolioId = normalizeId(item.portfolioId)
    const currentTarget = this.previewVersionTarget
    if (currentTarget && currentTarget.ownerType === ownerType && currentTarget.portfolioId === portfolioId) {
      this.closePreviewVersionMenu()
      return
    }
    this.closePreviewVersionMenu()
    if (!portfolioId || !wx.createSelectorQuery) return
    const token = this.previewVersionMeasureToken
    const target = { ownerType, portfolioId, templateType: item.templateType || '' }
    this.previewVersionTarget = target
    const selector = `${ownerType === OWNER_TYPE_TEAM ? TEAM_PREVIEW_ANCHOR_PREFIX : PERSONAL_PREVIEW_ANCHOR_PREFIX}${portfolioId}`
    try {
      const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
      wx.createSelectorQuery().in(this).select(selector).boundingClientRect((rect) => {
        if (token !== this.previewVersionMeasureToken) return
        const layout = calculatePreviewVersionMenuLayout(rect, windowInfo, { preferAbove: false })
        if (!layout) {
          this.closePreviewVersionMenu()
          return
        }
        this.setData({
          previewVersionMenu: {
            ...target,
            ...layout,
            options: buildPreviewVersionOptions({
              draftDescription: PREVIEW_DRAFT_DESCRIPTION,
              publishedDescription: PREVIEW_PUBLISHED_DESCRIPTION
            })
          }
        })
      }).exec()
    } catch (error) {
      this.closePreviewVersionMenu()
    }
  },

  closePreviewVersionMenu() {
    this.previewVersionMeasureToken = (this.previewVersionMeasureToken || 0) + 1
    this.previewVersionTarget = null
    if (this.data.previewVersionMenu) this.setData({ previewVersionMenu: null })
  },

  handlePreviewVersionSelect(event) {
    const menu = this.data.previewVersionMenu
    const scope = event.currentTarget.dataset.scope
    if (!menu || (scope !== PREVIEW_SCOPE_DRAFT && scope !== PREVIEW_SCOPE_PUBLISHED)) return
    this.closePreviewVersionMenu()
    const teamPreview = menu.ownerType === OWNER_TYPE_TEAM
    const scopeQuery = teamPreview || scope === PREVIEW_SCOPE_PUBLISHED ? `&scope=${scope}` : ''
    wx.navigateTo({ url: `${teamPreview ? TEAM_PREVIEW_URL : PREVIEW_PAGE_URL}?portfolioId=${menu.portfolioId}${scopeQuery}${advancedQuery(menu)}` })
  },

  handleDraftPreviewTap(event) {
    const portfolioId = normalizeId(event.currentTarget.dataset.id)
    if (!portfolioId) {
      return
    }
    if (this.data.revealedPortfolioId === portfolioId) {
      this.setData({ revealedPortfolioId: null })
      return
    }
    wx.navigateTo({ url: `${PREVIEW_PAGE_URL}?portfolioId=${portfolioId}${advancedQuery(this.findPortfolioById(portfolioId))}` })
  },

  publishPortfolioFromList(portfolioId) {
    const portfolio = this.findPortfolioById(portfolioId)
    if (!portfolio) {
      return Promise.resolve()
    }
    return (isAdvanced(portfolio) ? confirmAdvancedPortfolioPublish(request) : confirmPortfolioPublishDisclaimer()).then((confirmed) => {
      if (!confirmed) {
        return
      }
      return request({
        url: isAdvanced(portfolio) ? advancedEndpoint(portfolioId, '/publish') : `${PORTFOLIOS_API_URL}/${portfolioId}/publish`,
        method: 'POST',
        data: buildPublishPayload(portfolio.draftRevision || 0, makeIdempotencyKey(IDEMPOTENCY_PREFIX_PUBLISH))
      }).then(() => {
        wx.showToast({ title: '已发布', icon: 'success' })
        return this.bootstrap()
      }).catch((error) => {
        if (error && error.authRequired) {
          handleMaintainerAuthRequired(error.message)
          return
        }
        wx.showToast({ title: error && error.message ? error.message : '发布失败', icon: 'none' })
      })
    })
  },

  async handleDeletePortfolioTap(event) {
    const portfolioId = normalizeId(event.currentTarget.dataset.id)
    const portfolio = this.findPortfolioById(portfolioId)
    if (!portfolio || this.data.deletingPortfolioId) {
      return
    }
    wx.showModal({
      title: '删除作品集',
      content: `确认删除“${portfolio.title || '该作品集'}”？删除后该作品集链接将不可访问。`,
      confirmText: '删除',
      confirmColor: DELETE_CONFIRM_COLOR,
      success: async (result) => {
        if (!result.confirm) {
          this.setData({ revealedPortfolioId: null })
          return
        }
        this.setData({ deletingPortfolioId: portfolioId })
        try {
          await request({
            url: isAdvanced(portfolio) ? advancedEndpoint(portfolioId, '/delete') : `${PORTFOLIO_DELETE_API_PREFIX}/${portfolioId}`,
            method: 'POST'
          })
          this.setData({
            deletingPortfolioId: null,
            revealedPortfolioId: null
          })
          wx.showToast({
            title: '作品集已删除',
            icon: 'success'
          })
          await this.bootstrap()
        } catch (error) {
          if (error && error.authRequired) {
            this.setData({
              deletingPortfolioId: null,
              revealedPortfolioId: null
            })
            handleMaintainerAuthRequired(error.message)
            return
          }
          this.setData({
            deletingPortfolioId: null,
            revealedPortfolioId: null
          })
          const errorCode = error && (error.errorCode || (error.data && error.data.errorCode))
          if (errorCode === PERSONAL_PORTFOLIO_REFERENCED_ERROR_CODE) {
            wx.showModal({
              title: '无法删除作品集',
              content: error.message || '作品集正在被其他个人作品集使用，请先移除引用',
              showCancel: false,
              confirmText: '知道了'
            })
            return
          }
          wx.showToast({
            title: error && error.message ? error.message : '作品集删除失败',
            icon: 'none',
            duration: 2600
          })
        }
      }
    })
  },

  onShareAppMessage() {
    const resolved = this.resolveShareTarget()
    if (!resolved) {
      this.resetShareSheetState()
      return undefined
    }
    const portfolio = resolved.portfolio
    let shareConfig
    if (resolved.ownerType === OWNER_TYPE_TEAM) {
      shareConfig = {
        title: portfolio.title || portfolio.teamName,
        path: buildTeamSharePath(portfolio.shareCode, portfolio.templateType),
        imageUrl: portfolio.coverUrl || DEFAULT_COVER_URL
      }
      if (this.data.sharingTeamPortfolioId !== portfolio.portfolioId) {
        this.setData({ sharingTeamPortfolioId: portfolio.portfolioId })
        recordTeamPortfolioShare(
          requestForPortfolio(portfolio),
          portfolio.portfolioId,
          SHARE_CHANNEL_WECHAT_CARD,
          SHARE_SCENE_TEAM_PORTFOLIO_LIST
        ).catch((error) => {
          if (handleTeamMaintainerAuthError(error)) {
            return
          }
          showTeamPortfolioUnavailableToast(error)
        }).finally(() => {
          if (this.data.sharingTeamPortfolioId === portfolio.portfolioId) {
            this.setData({ sharingTeamPortfolioId: null })
          }
        })
      }
    } else {
      shareConfig = {
        title: portfolio.title,
        path: buildSharePath(portfolio.shareCode),
        imageUrl: portfolio.coverUrl
      }
      request({
        url: isAdvanced(portfolio) ? advancedEndpoint(portfolio.portfolioId, '/share-records') : `${PORTFOLIOS_API_URL}/${portfolio.portfolioId}/share-records`,
        method: 'POST',
        data: {
          shareChannel: SHARE_CHANNEL_WECHAT_CARD,
          shareScene: SHARE_SCENE_PORTFOLIO_LIST
        }
      }).catch(() => {})
    }
    this.resetShareSheetState()
    return shareConfig
  },

  handleTabTap(event) {
    const label = event.currentTarget.dataset.label
    if (label === '作品集') {
      return
    }
    if (label === '档期') {
      wx.redirectTo({ url: SCHEDULE_PAGE_URL })
      return
    }
    if (label === '作品') {
      wx.redirectTo({ url: WORKS_PAGE_URL })
      return
    }
    if (label === '我的') {
      wx.redirectTo({ url: MINE_PAGE_URL })
    }
  }
})

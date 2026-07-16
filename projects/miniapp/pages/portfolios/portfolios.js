const { request } = require('../../utils/request')
const { normalizeId } = require('../../utils/id')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const { buildPublishPayload } = require('../../utils/portfolios')
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
const SHARE_CHANNEL_WECHAT_MINIAPP = 'WECHAT_MINIAPP'
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
const TEAM_SELECT_URL = '/pages/team-portfolios/team-select/team-select'
const TEAM_EDIT_URL = '/pages/team-portfolios/standard-edit/team-portfolio-standard-edit'
const TEAM_PREVIEW_URL = '/pages/team-portfolios/standard-preview/team-portfolio-standard-preview'
const TEAM_VISITOR_SHARE_PATH_PREFIX = '/pages/team-portfolios/visitor-portfolio/team-visitor-portfolio?shareCode='
const SHARE_CHANNEL_WECHAT_CARD = 'WECHAT_CARD'
const SHARE_SCENE_TEAM_PORTFOLIO_LIST = 'TEAM_PORTFOLIO_LIST'
const IDEMPOTENCY_PREFIX_TEAM_PUBLISH = 'team-publish'

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
    canPreviewDraft: !isPublished && item.canMaintain === true,
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

function buildTeamSharePath(shareCode) {
  return `${TEAM_VISITOR_SHARE_PATH_PREFIX}${encodeURIComponent(defaultString(shareCode))}`
}

Page({
  data: {
    ownerType: OWNER_TYPE_USER,
    ownerTitle: '个人作品集',
    loading: false,
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
    deletingTeamPortfolioId: null,
    publishingTeamPortfolioId: null,
    sharingTeamPortfolioId: null,
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
    if (this.ownerSwitchTimer) {
      clearTimeout(this.ownerSwitchTimer)
      this.ownerSwitchTimer = null
    }
  },

  onPullDownRefresh() {
    if (this.data.ownerType === OWNER_TYPE_TEAM) {
      return this.handleTeamPullDownRefresh()
    }
    return this.handlePullDownRefresh()
  },

  handlePullDownRefresh() {
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
        loading: false
      })
    }).catch((error) => {
      if (error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({ loading: false, errorMessage: error.message || '作品集加载失败' })
    })
  },

  async bootstrapTeam({ onlyIfNeeded = false } = {}) {
    if (onlyIfNeeded && (this.data.teamLoaded || this.data.teamLoading)) {
      return
    }
    this.setData({
      teamLoading: true,
      teamErrorMessage: '',
      noMaintainableTeam: false,
      revealedTeamPortfolioId: null,
      teamPortfolioTouchStart: null
    })
    try {
      const [portfolios, maintainableTeams] = await Promise.all([
        fetchTeamPortfolioList(request),
        fetchMaintainableTeams(request)
      ])
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
    const ownerType = event.currentTarget.dataset.type === OWNER_TYPE_TEAM ? OWNER_TYPE_TEAM : OWNER_TYPE_USER
    if (ownerType === this.data.ownerType || this.data.switching) {
      return
    }
    this.setData({
      ownerType,
      ownerTitle: ownerType === OWNER_TYPE_TEAM ? '团队作品集' : '个人作品集',
      switching: true,
      revealedPortfolioId: null,
      portfolioTouchStart: null,
      revealedTeamPortfolioId: null,
      teamPortfolioTouchStart: null
    })
    if (ownerType === OWNER_TYPE_TEAM) {
      this.bootstrapTeam({ onlyIfNeeded: true })
    }
    if (this.ownerSwitchTimer) {
      clearTimeout(this.ownerSwitchTimer)
    }
    this.ownerSwitchTimer = setTimeout(() => {
      this.setData({ switching: false })
      this.ownerSwitchTimer = null
    }, OWNER_SWITCH_DURATION_MS)
  },

  handleCreateStandardPersonal() {
    wx.navigateTo({ url: EDIT_PAGE_URL })
  },

  handleCreateStandardTeam() {
    if (this.data.creatingTeamPortfolio) {
      return
    }
    const route = resolveTeamCreateRoute(this.data.maintainableTeams)
    if (route.action === 'UNAVAILABLE') {
      this.setData({ noMaintainableTeam: true })
      return
    }
    if (route.action === 'SELECT') {
      wx.navigateTo({
        url: TEAM_SELECT_URL,
        success: (result) => {
          const channel = result && result.eventChannel
          if (channel && typeof channel.emit === 'function') {
            channel.emit('maintainableTeams', this.data.maintainableTeams)
          }
        }
      })
      return
    }
    wx.navigateTo({ url: `${TEAM_EDIT_URL}?teamId=${route.teamId}` })
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
      wx.navigateTo({ url: `${TEAM_EDIT_URL}?portfolioId=${item.portfolioId}` })
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
    wx.navigateTo({ url: `${TEAM_PREVIEW_URL}?portfolioId=${item.portfolioId}&scope=${scope}` })
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

  handleTeamShareTap(event) {
    const item = event.currentTarget.dataset.item
    if (item && this.data.revealedTeamPortfolioId === item.portfolioId) {
      this.setData({ revealedTeamPortfolioId: null })
    }
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
    const confirmed = await confirmPortfolioPublishDisclaimer()
    if (!confirmed) {
      return
    }
    this.setData({ publishingTeamPortfolioId: item.portfolioId })
    try {
      await publishTeamPortfolio(
        request,
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
      await deleteTeamPortfolio(request, item.portfolioId)
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
      wx.navigateTo({ url: `${EDIT_PAGE_URL}?portfolioId=${portfolioId}` })
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
    if (actionType === ACTION_TYPE_SHARE) {
      return
    }
    if (actionType === ACTION_TYPE_PUBLISH) {
      return this.publishPortfolioFromList(portfolioId)
    }
    wx.navigateTo({ url: `${EDIT_PAGE_URL}?portfolioId=${portfolioId}` })
  },

  handlePublishedPreviewTap(event) {
    const portfolioId = normalizeId(event.currentTarget.dataset.id)
    if (!portfolioId) {
      return
    }
    if (this.data.revealedPortfolioId === portfolioId) {
      this.setData({ revealedPortfolioId: null })
      return
    }
    wx.navigateTo({ url: `${PREVIEW_PAGE_URL}?portfolioId=${portfolioId}&scope=published` })
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
    wx.navigateTo({ url: `${PREVIEW_PAGE_URL}?portfolioId=${portfolioId}` })
  },

  publishPortfolioFromList(portfolioId) {
    const portfolio = this.findPortfolioById(portfolioId)
    if (!portfolio) {
      return Promise.resolve()
    }
    return confirmPortfolioPublishDisclaimer().then((confirmed) => {
      if (!confirmed) {
        return
      }
      return request({
        url: `${PORTFOLIOS_API_URL}/${portfolioId}/publish`,
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
            url: `${PORTFOLIO_DELETE_API_PREFIX}/${portfolioId}`,
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
          wx.showToast({
            title: error && error.message ? error.message : '作品集删除失败',
            icon: 'none',
            duration: 2600
          })
        }
      }
    })
  },

  onShareAppMessage(event = {}) {
    const dataset = event.target && event.target.dataset ? event.target.dataset : {}
    if (dataset.ownerType === OWNER_TYPE_TEAM) {
      const item = dataset.item
      if (!item || item.publicationStatus !== PUBLICATION_STATUS_PUBLISHED || !item.canShare || !item.shareCode) {
        return undefined
      }
      if (this.data.sharingTeamPortfolioId !== item.portfolioId) {
        this.setData({ sharingTeamPortfolioId: item.portfolioId })
        recordTeamPortfolioShare(
          request,
          item.portfolioId,
          SHARE_CHANNEL_WECHAT_CARD,
          SHARE_SCENE_TEAM_PORTFOLIO_LIST
        ).catch((error) => {
          if (handleTeamMaintainerAuthError(error)) {
            return
          }
          showTeamPortfolioUnavailableToast(error)
        }).finally(() => {
          if (this.data.sharingTeamPortfolioId === item.portfolioId) {
            this.setData({ sharingTeamPortfolioId: null })
          }
        })
      }
      return {
        title: item.title || item.teamName,
        path: buildTeamSharePath(item.shareCode),
        imageUrl: item.coverUrl || DEFAULT_COVER_URL
      }
    }
    const portfolioId = dataset.id || ''
    const portfolio = this.data.displayPortfolios.find((item) => String(item.portfolioId) === String(portfolioId))
    if (!portfolio) {
      return {
        title: '作品集',
        path: '/pages/portfolios/portfolios',
        imageUrl: DEFAULT_COVER_URL
      }
    }
    request({
      url: `${PORTFOLIOS_API_URL}/${portfolio.portfolioId}/share-records`,
      method: 'POST',
      data: {
        shareChannel: SHARE_CHANNEL_WECHAT_MINIAPP,
        shareScene: SHARE_SCENE_PORTFOLIO_LIST
      }
    }).catch(() => {})
    return {
      title: portfolio.title,
      path: buildSharePath(portfolio.shareCode),
      imageUrl: portfolio.coverUrl
    }
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

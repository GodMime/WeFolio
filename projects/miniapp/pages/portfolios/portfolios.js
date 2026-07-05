const { request } = require('../../utils/request')
const { normalizeId } = require('../../utils/id')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const { buildPublishPayload } = require('../../utils/portfolios')
const { confirmPortfolioPublishDisclaimer } = require('../../utils/portfolio-publish-disclaimer')

const PORTFOLIOS_API_URL = '/api/mine/portfolios'
const PORTFOLIO_DELETE_API_PREFIX = '/api/mine/portfolios/delete'
const EDIT_PAGE_URL = '/pages/portfolio-standard-edit/portfolio-standard-edit'
const PREVIEW_PAGE_URL = '/pages/portfolio-standard-preview/portfolio-standard-preview'
const VISITOR_PORTFOLIO_SHARE_PATH_PREFIX = '/pages/visitor-portfolio/visitor-portfolio?shareCode='
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

function makeIdempotencyKey(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

function defaultString(value, fallback = '') {
  const text = String(value || '').trim()
  return text || fallback
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
    coverUrl: defaultString(item.coverUrl, DEFAULT_COVER_URL),
    updatedText: item.updatedAt ? `最近更新 ${String(item.updatedAt).slice(5, 10)}` : '最近更新',
    coverAlt: defaultString(item.title, '作品集封面'),
    showPublishedPreview: isPublished,
    showDraftPreview: isDraft
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

Page({
  data: {
    ownerType: 'USER',
    ownerTitle: '个人作品集',
    loading: false,
    errorMessage: '',
    portfolios: [],
    displayPortfolios: [],
    summary: buildSummary([]),
    revealedPortfolioId: null,
    portfolioTouchStart: null,
    deletingPortfolioId: null,
    tabs: [
      { key: 'schedule', label: '档期', icon: 'schedule' },
      { key: 'work', label: '作品', icon: 'work' },
      { key: 'portfolio', label: '作品集', icon: 'portfolio', active: true },
      { key: 'mine', label: '我的', icon: 'mine' }
    ]
  },

  onShow() {
    this.bootstrap()
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
        handleAuthRequired(error.message)
        return
      }
      this.setData({ loading: false, errorMessage: error.message || '作品集加载失败' })
    })
  },

  handleOwnerTypeTap(event) {
    const ownerType = event.currentTarget.dataset.type || 'USER'
    this.setData({
      ownerType,
      ownerTitle: ownerType === 'TEAM' ? '团队作品集' : '个人作品集',
      revealedPortfolioId: null,
      portfolioTouchStart: null
    })
    this.bootstrap()
  },

  handleCreateStandardPersonal() {
    wx.navigateTo({ url: EDIT_PAGE_URL })
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
          handleAuthRequired(error.message)
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
            handleAuthRequired(error.message)
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
    const portfolioId = event.target && event.target.dataset ? event.target.dataset.id : ''
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

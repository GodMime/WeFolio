const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')

const PORTFOLIOS_API_URL = '/api/mine/portfolios'
const STANDARD_PERSONAL_API_URL = '/api/mine/portfolios/standard-personal'
const EDIT_PAGE_URL = '/pages/portfolio-standard-edit/portfolio-standard-edit'
const VISITOR_PORTFOLIO_SHARE_PATH_PREFIX = '/pages/visitor-portfolio/visitor-portfolio?shareCode='
const UNAVAILABLE_PAGE_URL = '/pages/portfolio-unavailable/portfolio-unavailable'
const SCHEDULE_PAGE_URL = '/pages/schedule/schedule'
const WORKS_PAGE_URL = '/pages/works/works'
const MINE_PAGE_URL = '/pages/index/index'
const DEFAULT_COVER_URL = '/assets/system/work-logo-100kb.jpg'
const SHARE_CHANNEL_WECHAT_MINIAPP = 'WECHAT_MINIAPP'
const SHARE_SCENE_PORTFOLIO_LIST = 'PORTFOLIO_LIST'

function defaultString(value, fallback = '') {
  const text = String(value || '').trim()
  return text || fallback
}

function resolveTemplateText(item = {}) {
  const ownerText = item.ownerType === 'TEAM' ? '团队' : '个人'
  const templateText = item.templateType === 'ADVANCED' ? '高级' : '标准'
  return `${templateText}${ownerText}作品集`
}

function resolveStatus(item = {}) {
  if (item.publicationStatus === 'PUBLISHED') {
    return {
      statusText: '已发布',
      statusTone: 'published',
      actionText: '分享',
      actionType: 'SHARE'
    }
  }
  if (item.publicationStatus === 'OFFLINE') {
    return {
      statusText: '已下线',
      statusTone: 'muted',
      actionText: '编辑',
      actionType: 'EDIT'
    }
  }
  return {
    statusText: '草稿',
    statusTone: 'draft',
    actionText: '发布',
    actionType: 'EDIT'
  }
}

function normalizePortfolioItem(item = {}) {
  const status = resolveStatus(item)
  return Object.assign({}, item, status, {
    title: defaultString(item.title, '未命名作品集'),
    templateText: resolveTemplateText(item),
    coverUrl: defaultString(item.coverUrl, DEFAULT_COVER_URL),
    updatedText: item.updatedAt ? `最近更新 ${String(item.updatedAt).slice(5, 10)}` : '最近更新',
    coverAlt: defaultString(item.title, '作品集封面')
  })
}

function buildSummary(portfolios = []) {
  const publishedCount = portfolios.filter((item) => item.publicationStatus === 'PUBLISHED').length
  const draftCount = portfolios.filter((item) => item.publicationStatus !== 'PUBLISHED').length
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
    this.setData({ loading: true, errorMessage: '' })
    request({
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
      ownerTitle: ownerType === 'TEAM' ? '团队作品集' : '个人作品集'
    })
    this.bootstrap()
  },

  handleCreateStandardPersonal() {
    request({
      url: STANDARD_PERSONAL_API_URL,
      method: 'POST',
      data: {}
    }).then((response) => {
      wx.navigateTo({ url: `${EDIT_PAGE_URL}?portfolioId=${response.portfolioId}` })
    }).catch((error) => {
      wx.showToast({ title: error.message || '创建失败', icon: 'none' })
    })
  },

  handleUnavailableTap(event) {
    const type = event.currentTarget.dataset.type || ''
    wx.navigateTo({ url: `${UNAVAILABLE_PAGE_URL}?type=${type}` })
  },

  handleEditTap(event) {
    const portfolioId = event.currentTarget.dataset.id
    if (portfolioId) {
      wx.navigateTo({ url: `${EDIT_PAGE_URL}?portfolioId=${portfolioId}` })
    }
  },

  handlePrimaryActionTap(event) {
    const portfolioId = event.currentTarget.dataset.id
    const actionType = event.currentTarget.dataset.action
    if (!portfolioId) {
      return
    }
    if (actionType === 'SHARE') {
      return
    }
    wx.navigateTo({ url: `${EDIT_PAGE_URL}?portfolioId=${portfolioId}` })
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

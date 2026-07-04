const { request } = require('../../utils/request')
const { buildContactLeadPayload, createContactLeadForm, validateContactLeadForm } = require('../../utils/contact-lead')
const { normalizeVisitorPortfolio } = require('../../utils/visitor-portfolio')

const VISITOR_PORTFOLIO_API_PREFIX = '/api/visitor/portfolios'
const VISITOR_KEY_STORAGE = 'wefolio_visitor_key'
const WX_LOGIN_EMPTY_MESSAGE = '微信登录凭证为空'
const WX_LOGIN_FAILED_MESSAGE = '微信登录失败'
const WX_LOGIN_TIMEOUT_MESSAGE = '微信登录超时，请重试'
const WX_LOGIN_TIMEOUT_MS = 5000

function idempotencyKey(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
}

function getVisitorKey() {
  const existing = wx.getStorageSync(VISITOR_KEY_STORAGE)
  if (existing) {
    return existing
  }
  const key = `visitor-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
  wx.setStorageSync(VISITOR_KEY_STORAGE, key)
  return key
}

function wxLogin() {
  return new Promise((resolve, reject) => {
    let settled = false
    const finish = (handler, value, timer) => {
      if (settled) {
        return
      }
      settled = true
      clearTimeout(timer)
      handler(value)
    }
    const timer = setTimeout(() => {
      if (settled) {
        return
      }
      settled = true
      reject(new Error(WX_LOGIN_TIMEOUT_MESSAGE))
    }, WX_LOGIN_TIMEOUT_MS)
    wx.login({
      success(response) {
        if (response && response.code) {
          finish(resolve, response.code, timer)
          return
        }
        finish(reject, new Error(WX_LOGIN_EMPTY_MESSAGE), timer)
      },
      fail(error) {
        finish(reject, new Error(error && error.errMsg ? error.errMsg : WX_LOGIN_FAILED_MESSAGE), timer)
      }
    })
  })
}

Page({
  data: {
    shareCode: '',
    visitorKey: '',
    portfolio: normalizeVisitorPortfolio({}),
    contactForm: createContactLeadForm({})
  },

  onLoad(options = {}) {
    const shareCode = options.shareCode || options.scene || ''
    const visitorKey = getVisitorKey()
    this.setData({ shareCode, visitorKey })
    return this.bootstrap()
  },

  async bootstrap() {
    if (!this.data.shareCode) {
      return
    }
    try {
      const loginCode = await wxLogin()
      const response = await request({
        url: `${VISITOR_PORTFOLIO_API_PREFIX}/${this.data.shareCode}`,
        data: {
          visitorKey: this.data.visitorKey,
          loginCode,
          sourceType: 'WECHAT_SHARE_CARD',
          idempotencyKey: idempotencyKey('open')
        }
      })
      this.setData({ portfolio: normalizeVisitorPortfolio(response) })
    } catch (error) {
      wx.showToast({ title: error.message || '作品集加载失败', icon: 'none' })
    }
  },

  handleContactInput(event) {
    const field = event.currentTarget.dataset.field
    const contactForm = Object.assign({}, this.data.contactForm, { [field]: event.detail.value })
    this.setData({ contactForm: createContactLeadForm(contactForm) })
  },

  handleSubmitContact() {
    const validation = validateContactLeadForm(this.data.contactForm)
    if (!validation.valid) {
      wx.showToast({ title: validation.message, icon: 'none' })
      return
    }
    request({
      url: `${VISITOR_PORTFOLIO_API_PREFIX}/${this.data.shareCode}/contact-leads`,
      method: 'POST',
      data: buildContactLeadPayload(this.data.contactForm, {
        visitorKey: this.data.visitorKey,
        visitRecordId: this.data.portfolio.visitRecordId,
        sourceType: 'WECHAT_SHARE_CARD',
        idempotencyKey: idempotencyKey('lead')
      })
    }).then(() => {
      wx.showToast({ title: '已提交', icon: 'success' })
      this.setData({ contactForm: createContactLeadForm({}) })
    }).catch((error) => {
      wx.showToast({ title: error.message || '提交失败', icon: 'none' })
    })
  },

  handlePreviewQr(event) {
    const url = event.currentTarget.dataset.url
    if (!url) {
      return
    }
    wx.previewImage({ current: url, urls: [url] })
  },

  handleDisplayTagTap(event) {
    const componentKey = event.currentTarget.dataset.componentKey
    const groupKey = event.currentTarget.dataset.groupKey
    this.setData({
      portfolio: switchDisplayGroup(this.data.portfolio, componentKey, groupKey)
    })
  },

  onShareAppMessage() {
    return {
      title: this.data.portfolio.share.title || this.data.portfolio.title || '个人作品集',
      path: `/pages/visitor-portfolio/visitor-portfolio?shareCode=${this.data.shareCode}`,
      imageUrl: this.data.portfolio.share.coverUrl
    }
  }
})

function switchDisplayGroup(portfolio, componentKey, groupKey) {
  const sourcePortfolio = portfolio || {}
  const components = (Array.isArray(sourcePortfolio.components) ? sourcePortfolio.components : []).map((component) => {
    if (!component || component.componentKey !== componentKey) {
      return component
    }
    const groups = Array.isArray(component.groups) ? component.groups : []
    const activeGroup = groups.find((group) => group.groupKey === groupKey)
      || component.activeGroup
      || groups[0]
      || { groupKey: '', name: '', sortOrder: 0, works: [] }
    const displayTags = Array.isArray(component.displayTags)
      ? component.displayTags
      : groups.map((group) => ({ groupKey: group.groupKey, name: group.name, active: false }))
    return Object.assign({}, component, {
      activeGroupKey: activeGroup.groupKey,
      activeGroup,
      displayTags: displayTags.map((tag) => Object.assign({}, tag, {
        active: tag.groupKey === activeGroup.groupKey
      }))
    })
  })
  return Object.assign({}, sourcePortfolio, { components })
}

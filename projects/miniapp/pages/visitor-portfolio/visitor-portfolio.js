const { request } = require('../../utils/request')
const { buildContactLeadPayload, createContactLeadForm, validateContactLeadForm } = require('../../utils/contact-lead')
const { normalizeVisitorPortfolio } = require('../../utils/visitor-portfolio')

const VISITOR_PORTFOLIO_API_PREFIX = '/api/visitor/portfolios'
const VISITOR_KEY_STORAGE = 'wefolio_visitor_key'

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
    this.bootstrap()
  },

  bootstrap() {
    if (!this.data.shareCode) {
      return
    }
    request({
      url: `${VISITOR_PORTFOLIO_API_PREFIX}/${this.data.shareCode}`,
      data: {
        visitorKey: this.data.visitorKey,
        sourceType: 'WECHAT_SHARE_CARD',
        idempotencyKey: idempotencyKey('open')
      }
    }).then((response) => {
      this.setData({ portfolio: normalizeVisitorPortfolio(response) })
    }).catch((error) => {
      wx.showToast({ title: error.message || '作品集加载失败', icon: 'none' })
    })
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
  }
})

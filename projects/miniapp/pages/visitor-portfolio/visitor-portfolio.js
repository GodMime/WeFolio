const { request } = require('../../utils/request')
const { buildContactLeadPayload, createContactLeadForm, validateContactLeadForm } = require('../../utils/contact-lead')
const {
  createActiveContactFormComponent,
  findContactFormComponent
} = require('../../utils/portfolio-contact-form')
const { buildVisitorEventPayload, normalizeVisitorPortfolio, switchDisplayGroup } = require('../../utils/visitor-portfolio')

const VISITOR_PORTFOLIO_API_PREFIX = '/api/visitor/portfolios'
const VISITOR_KEY_STORAGE = 'wefolio_visitor_key'
const WX_LOGIN_EMPTY_MESSAGE = '微信登录凭证为空'
const WX_LOGIN_FAILED_MESSAGE = '微信登录失败'
const WX_LOGIN_TIMEOUT_MESSAGE = '微信登录超时，请重试'
const WX_LOGIN_TIMEOUT_MS = 5000
const MEDIA_TYPE_VIDEO = 'VIDEO'
const WORK_VIEWED_EVENT_TYPE = 'WORK_VIEWED'
const VIDEO_PLAYED_EVENT_TYPE = 'VIDEO_PLAYED'
const IMAGE_MISSING_MESSAGE = '图片地址缺失'
const VIDEO_MISSING_MESSAGE = '视频地址缺失'
const DEFAULT_VIDEO_TITLE = '视频作品'

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
    contactForm: createContactLeadForm({}),
    contactFormModalVisible: false,
    activeContactFormComponent: createActiveContactFormComponent(),
    videoPreviewVisible: false,
    videoPreview: null
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
    const field = (event.detail && event.detail.field) || (event.currentTarget && event.currentTarget.dataset.field)
    if (!field) {
      return
    }
    const value = event.detail && Object.prototype.hasOwnProperty.call(event.detail, 'value')
      ? event.detail.value
      : ''
    const contactForm = Object.assign({}, this.data.contactForm, { [field]: value })
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
      this.handleCloseContactFormModal()
    }).catch((error) => {
      wx.showToast({ title: error.message || '提交失败', icon: 'none' })
    })
  },

  handleOpenContactFormModal(event) {
    const componentKey = (event.detail && event.detail.componentKey) ||
      (event.currentTarget && event.currentTarget.dataset.componentKey)
    const component = findContactFormComponent(this.data.portfolio, componentKey)
    if (!component) {
      return
    }
    this.setData({
      contactFormModalVisible: true,
      activeContactFormComponent: component
    })
  },

  handleCloseContactFormModal() {
    this.setData({
      contactFormModalVisible: false,
      activeContactFormComponent: createActiveContactFormComponent()
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
  },

  handleWorkTap(event) {
    const work = normalizeWorkTapDataset(event.currentTarget.dataset)
    if (!work.previewUrl) {
      wx.showToast({
        title: work.mediaType === MEDIA_TYPE_VIDEO ? VIDEO_MISSING_MESSAGE : IMAGE_MISSING_MESSAGE,
        icon: 'none'
      })
      return Promise.resolve(false)
    }
    return this.recordWorkEvent(work)
      .then(() => this.openWorkMedia(work))
      .catch((error) => {
        wx.showToast({ title: error.message || '作品打开失败', icon: 'none' })
        return false
      })
  },

  recordWorkEvent(work) {
    if (!this.data.shareCode) {
      return Promise.resolve()
    }
    const eventType = work.mediaType === MEDIA_TYPE_VIDEO ? VIDEO_PLAYED_EVENT_TYPE : WORK_VIEWED_EVENT_TYPE
    return request({
      url: `${VISITOR_PORTFOLIO_API_PREFIX}/${this.data.shareCode}/events`,
      method: 'POST',
      data: buildVisitorEventPayload({
        visitorKey: this.data.visitorKey,
        eventType,
        workId: work.workId,
        mediaType: work.mediaType
      }, idempotencyKey('work'))
    })
  },

  openWorkMedia(work) {
    if (work.mediaType === MEDIA_TYPE_VIDEO) {
      this.setData({
        videoPreviewVisible: true,
        videoPreview: {
          src: work.previewUrl,
          poster: work.coverUrl,
          title: work.title || DEFAULT_VIDEO_TITLE
        }
      })
      return true
    }
    wx.previewImage({ current: work.previewUrl, urls: [work.previewUrl] })
    return true
  },

  handleCloseVideoPreview() {
    this.setData({
      videoPreviewVisible: false,
      videoPreview: null
    })
  },

  handleVideoPreviewPanelTap() {
  }
})

function normalizeWorkTapDataset(dataset = {}) {
  const previewUrl = dataset.mediaUrl || dataset.previewUrl || dataset.coverUrl || ''
  return {
    workId: Number(dataset.workId),
    mediaType: dataset.mediaType || '',
    previewUrl,
    coverUrl: dataset.coverUrl || '',
    title: dataset.title || ''
  }
}

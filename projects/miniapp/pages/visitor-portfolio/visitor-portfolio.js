const { request } = require('../../utils/request')
const { buildContactLeadPayload, createContactLeadForm, validateContactLeadForm } = require('../../utils/contact-lead')
const {
  createActiveContactFormComponent,
  findContactFormComponent
} = require('../../utils/portfolio-contact-form')
const { clearDisplaySwitchingTimer, markDisplaySwitching } = require('../../utils/display-switching')
const { buildVisitorEventPayload, normalizeVisitorPortfolio, switchDisplayGroup } = require('../../utils/visitor-portfolio')
const { uploadVisitorAvatarProfile } = require('../../utils/visitor-profile')

const VISITOR_PORTFOLIO_API_PREFIX = '/api/visitor/portfolios'
const WX_LOGIN_EMPTY_MESSAGE = '微信登录凭证为空'
const WX_LOGIN_FAILED_MESSAGE = '微信登录失败'
const WX_LOGIN_TIMEOUT_MESSAGE = '微信登录超时，请重试'
const WX_LOGIN_TIMEOUT_MS = 5000
const MEDIA_TYPE_VIDEO = 'VIDEO'
const WORK_VIEWED_EVENT_TYPE = 'WORK_VIEWED'
const VIDEO_PLAYED_EVENT_TYPE = 'VIDEO_PLAYED'
const QR_CODE_INTERACTED_EVENT_TYPE = 'QR_CODE_INTERACTED'
const SOURCE_TYPE_WECHAT_SHARE_CARD = 'WECHAT_SHARE_CARD'
const QR_ACTION_PREVIEW = 'PREVIEW_QR'
const IMAGE_MISSING_MESSAGE = '图片地址缺失'
const VIDEO_MISSING_MESSAGE = '视频地址缺失'
const DEFAULT_VIDEO_TITLE = '视频作品'
const VISITOR_PROFILE_REQUIRED_MESSAGE = '请授权头像和昵称'

function idempotencyKey(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
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
    videoPreview: null,
    visitorProfileAuthVisible: false,
    visitorProfileToken: '',
    visitorProfileForm: {
      avatarUrl: '',
      nickname: ''
    },
    visitorProfileSaving: false,
    displaySwitchingComponentKey: ''
  },

  onLoad(options = {}) {
    const shareCode = options.shareCode || options.scene || ''
    this.setData({ shareCode, visitorKey: '' })
    return this.bootstrap()
  },

  async bootstrap() {
    if (!this.data.shareCode) {
      return
    }
    try {
      const loginCode = await wxLogin()
      const response = await request({
        url: `${VISITOR_PORTFOLIO_API_PREFIX}/${this.data.shareCode}/open`,
        method: 'POST',
        data: {
          loginCode,
          sourceType: SOURCE_TYPE_WECHAT_SHARE_CARD,
          idempotencyKey: idempotencyKey('open')
        }
      })
      const portfolio = normalizeVisitorPortfolio(response)
      this.setData({
        portfolio,
        visitorKey: portfolio.visitorKey || '',
        visitorProfileToken: portfolio.visitorProfileToken || '',
        visitorProfileAuthVisible: Boolean(
          portfolio.needVisitorProfile && portfolio.visitorProfileToken && !portfolio.underMaintenance
        )
      })
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
        sourceType: SOURCE_TYPE_WECHAT_SHARE_CARD,
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
      return Promise.resolve(false)
    }
    wx.previewImage({ current: url, urls: [url] })
    return this.recordQrEvent()
  },

  recordQrEvent() {
    if (!this.data.shareCode || !this.data.visitorKey) {
      return Promise.resolve(false)
    }
    return request({
      url: `${VISITOR_PORTFOLIO_API_PREFIX}/${this.data.shareCode}/events`,
      method: 'POST',
      data: buildVisitorEventPayload({
        visitorKey: this.data.visitorKey,
        eventType: QR_CODE_INTERACTED_EVENT_TYPE,
        metadata: {
          action: QR_ACTION_PREVIEW
        }
      }, idempotencyKey('qr'))
    }).then(() => true).catch(() => false)
  },

  handleDisplayTagTap(event) {
    const componentKey = event.currentTarget.dataset.componentKey
    const groupKey = event.currentTarget.dataset.groupKey
    if (!componentKey) {
      return
    }
    this.setData({
      portfolio: switchDisplayGroup(this.data.portfolio, componentKey, groupKey)
    }, () => {
      markDisplaySwitching(this, componentKey)
    })
  },

  onUnload() {
    clearDisplaySwitchingTimer(this)
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
  },

  handleVisitorProfileMaskTap() {
  },

  handleVisitorProfilePanelTap() {
  },

  handleVisitorProfileMaskTouchMove() {
  },

  handleVisitorAvatarChoose(event) {
    const avatarUrl = event.detail && event.detail.avatarUrl
    if (!avatarUrl) {
      return
    }
    this.setData({
      'visitorProfileForm.avatarUrl': avatarUrl
    })
  },

  handleVisitorNicknameInput(event) {
    const value = event.detail && Object.prototype.hasOwnProperty.call(event.detail, 'value')
      ? event.detail.value
      : ''
    this.setData({
      'visitorProfileForm.nickname': value
    })
  },

  handleVisitorProfileSkip() {
    this.setData({
      visitorProfileAuthVisible: false
    })
  },

  async handleVisitorProfileSubmit() {
    const form = this.data.visitorProfileForm || {}
    if (!form.avatarUrl || !String(form.nickname || '').trim()) {
      wx.showToast({ title: VISITOR_PROFILE_REQUIRED_MESSAGE, icon: 'none' })
      return
    }
    if (!this.data.visitorProfileToken) {
      this.setData({ visitorProfileAuthVisible: false })
      return
    }
    this.setData({ visitorProfileSaving: true })
    try {
      await uploadVisitorAvatarProfile({
        shareCode: this.data.shareCode,
        visitorProfileToken: this.data.visitorProfileToken,
        nickname: form.nickname,
        avatarFilePath: form.avatarUrl
      })
      this.setData({
        visitorProfileAuthVisible: false,
        visitorProfileSaving: false
      })
    } catch (error) {
      this.setData({ visitorProfileSaving: false })
      wx.showToast({ title: error.message || '资料保存失败', icon: 'none' })
    }
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

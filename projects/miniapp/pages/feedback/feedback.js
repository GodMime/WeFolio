const {
  MAX_ATTACHMENT_COUNT,
  addChosenFeedbackMedia,
  createFeedbackDraft,
  fetchFeedbackCreationState,
  submitFeedbackDraft,
  validateFeedbackDescription
} = require('../../utils/feedback')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')

const FEEDBACK_HISTORY_PAGE_URL = '/pages/feedback-history/feedback-history'
const LOGIN_PAGE_URL = '/pages/login/login'

function emptyCreationState() {
  return {
    activeCount: 0,
    maxActiveCount: 3,
    canCreate: false,
    hintText: ''
  }
}

function normalizeCreationState(raw = {}) {
  return {
    activeCount: Math.max(0, Number(raw.activeCount) || 0),
    maxActiveCount: Math.max(1, Number(raw.maxActiveCount) || 3),
    canCreate: Boolean(raw.canCreate),
    hintText: String(raw.hintText || '')
  }
}

function buildAttachmentSlots(attachments = []) {
  return Array.from({ length: MAX_ATTACHMENT_COUNT }, (_, index) => ({
    slotIndex: index,
    file: attachments[index] || null
  }))
}

function isFeedbackSubmitDisabled(draft, creationState, submitting) {
  return Boolean(submitting)
    || !creationState.canCreate
    || !validateFeedbackDescription(draft.description).valid
}

function isChooseMediaCancel(error) {
  return Boolean(error && /cancel/i.test(String(error.errMsg || error.message || '')))
}

function chooseFeedbackMedia(count) {
  return new Promise((resolve, reject) => wx.chooseMedia({
    count,
    mediaType: ['image', 'video'],
    sourceType: ['album', 'camera'],
    maxDuration: 600,
    success: resolve,
    fail: reject
  }))
}

Page({
  data: {
    loading: true,
    errorMessage: '',
    submitting: false,
    creationState: emptyCreationState(),
    draft: createFeedbackDraft(),
    descriptionLength: 0,
    attachmentSlots: buildAttachmentSlots(),
    submitDisabled: true,
    uploadProgress: {},
    uploadProgressPercent: 0
  },

  // 每次进入新建页生成新的提交幂等键，从历史页返回时只静默刷新可创建额度。
  onLoad() {
    const draft = createFeedbackDraft()
    this.skipNextShowRefresh = true
    this.setData({
      draft,
      descriptionLength: 0,
      attachmentSlots: buildAttachmentSlots(draft.attachments),
      submitDisabled: true,
      uploadProgress: {},
      uploadProgressPercent: 0
    })
    this.bootstrap()
  },

  onShow() {
    if (this.skipNextShowRefresh) {
      this.skipNextShowRefresh = false
      return
    }
    if (hasLocalToken()) {
      this.loadCreationState({ silent: true })
    }
  },

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadCreationState()
  },

  async loadCreationState(options = {}) {
    if (!options.silent) {
      this.setData({ loading: true, errorMessage: '' })
    }
    try {
      const creationState = normalizeCreationState(await fetchFeedbackCreationState())
      this.setData({
        loading: false,
        creationState,
        submitDisabled: isFeedbackSubmitDisabled(
          this.data.draft, creationState, this.data.submitting
        )
      })
    } catch (error) {
      if (this.handleAuthError(error)) {
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '反馈额度加载失败'
      })
    }
  },

  redirectToLogin() {
    wx.redirectTo({ url: LOGIN_PAGE_URL })
  },

  handleAuthError(error) {
    if (!error || !error.authRequired) {
      return false
    }
    handleMaintainerAuthRequired(error.message)
    return true
  },

  handleRetry() {
    this.bootstrap()
  },

  handleHistoryTap() {
    wx.navigateTo({ url: FEEDBACK_HISTORY_PAGE_URL })
  },

  handleDescriptionInput(event) {
    const sourceValue = String(event.detail.value || '')
    const description = Array.from(sourceValue).slice(0, 200).join('')
    const descriptionLength = Array.from(description).length
    const draft = Object.assign({}, this.data.draft, { description })
    this.setData({
      draft,
      descriptionLength,
      submitDisabled: isFeedbackSubmitDisabled(
        draft, this.data.creationState, this.data.submitting
      )
    })
  },

  async handleChooseMedia() {
    if (this.data.submitting) {
      return
    }
    const remainingCount = MAX_ATTACHMENT_COUNT - this.data.draft.attachments.length
    if (remainingCount <= 0) {
      wx.showToast({ title: '每轮最多添加 3 个附件', icon: 'none' })
      return
    }
    try {
      // 选择阶段只保留本地临时文件，真正上传统一延迟到用户提交时。
      const result = await chooseFeedbackMedia(remainingCount)
      const draft = addChosenFeedbackMedia(this.data.draft, result.tempFiles || [])
      this.setDraft(draft)
    } catch (error) {
      if (!isChooseMediaCancel(error)) {
        wx.showToast({
          title: error && error.message ? error.message : '附件选择失败',
          icon: 'none'
        })
      }
    }
  },

  handlePreviewAttachment(event) {
    const index = Number(event.currentTarget.dataset.index)
    const file = this.data.draft.attachments[index]
    if (!file || !file.tempFilePath) {
      return
    }
    if (typeof wx.previewMedia === 'function') {
      wx.previewMedia({
        current: 0,
        sources: [{
          url: file.tempFilePath,
          type: file.mediaType === 'VIDEO' ? 'video' : 'image'
        }]
      })
      return
    }
    if (file.mediaType === 'IMAGE') {
      wx.previewImage({ current: file.tempFilePath, urls: [file.tempFilePath] })
    }
  },

  handleRemoveAttachment(event) {
    if (this.data.submitting) {
      return
    }
    const index = Number(event.currentTarget.dataset.index)
    const draft = Object.assign({}, this.data.draft, {
      attachments: this.data.draft.attachments.filter((item, itemIndex) => itemIndex !== index)
    })
    this.setDraft(draft)
  },

  setDraft(draft) {
    this.setData({
      draft,
      attachmentSlots: buildAttachmentSlots(draft.attachments),
      submitDisabled: isFeedbackSubmitDisabled(
        draft, this.data.creationState, this.data.submitting
      )
    })
  },

  handleUploadProgress(clientId, progress = {}) {
    const uploadProgress = Object.assign({}, this.data.uploadProgress, {
      [clientId]: Number(progress.progress) || 0
    })
    const uploadedPercent = this.data.draft.attachments.reduce((total, attachment) => (
      total + (Number(uploadProgress[attachment.clientId]) || 0)
    ), 0)
    const attachmentCount = Math.max(1, this.data.draft.attachments.length)
    this.setData({
      uploadProgress,
      uploadProgressPercent: Math.round(uploadedPercent / attachmentCount)
    })
  },

  async handleSubmit() {
    if (this.data.submitting || !this.data.creationState.canCreate) {
      return
    }
    const validation = validateFeedbackDescription(this.data.draft.description)
    if (!validation.valid) {
      wx.showToast({ title: validation.message, icon: 'none' })
      return
    }
    this.setData({
      submitting: true,
      submitDisabled: true,
      uploadProgress: {},
      uploadProgressPercent: 0
    })
    try {
      // 工具层负责签票与上传，页面只维护提交态和失败后可继续编辑的草稿。
      await submitFeedbackDraft({
        draft: this.data.draft,
        wxApi: wx,
        onProgress: this.handleUploadProgress.bind(this)
      })
      const draft = createFeedbackDraft()
      this.setData({
        submitting: false,
        draft,
        descriptionLength: 0,
        attachmentSlots: buildAttachmentSlots(),
        submitDisabled: true,
        uploadProgress: {},
        uploadProgressPercent: 0
      })
      wx.showToast({ title: '问题已提交', icon: 'success' })
      await this.loadCreationState({ silent: true })
    } catch (error) {
      if (this.handleAuthError(error)) {
        return
      }
      const draft = error && error.draft ? error.draft : this.data.draft
      this.setData({
        submitting: false,
        submitDisabled: isFeedbackSubmitDisabled(
          draft, this.data.creationState, false
        ),
        draft,
        attachmentSlots: buildAttachmentSlots(draft.attachments)
      })
      wx.showToast({
        title: error && error.message ? error.message : '问题提交失败',
        icon: 'none'
      })
    }
  }
})

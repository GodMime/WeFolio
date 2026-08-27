const {
  MAX_ATTACHMENT_COUNT,
  addChosenFeedbackMedia,
  createFeedbackDraft,
  fetchFeedbackDetail,
  fetchFeedbackList,
  mergeFeedbackPage,
  normalizeFeedbackDetail,
  normalizeFeedbackPage,
  submitFeedbackDraft,
  validateFeedbackDescription
} = require('../../utils/feedback')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')

const LOGIN_PAGE_URL = '/pages/login/login'
const FEEDBACK_PAGE_URL = '/pages/feedback/feedback'
const PAGE_SIZE = 20
const DETAIL_SHEET_MAX_HEIGHT_RATIO = 0.88
const DETAIL_SCROLL_MIN_HEIGHT_PX = 1
const DETAIL_APPEND_TRANSITION_MS = 280
const DETAIL_OVERLAY_SELECTOR = '.detail-overlay'
const DETAIL_SHEET_SELECTOR = '.detail-sheet'
const DETAIL_SCROLL_SELECTOR = '.detail-scroll'
const DETAIL_SCROLL_CONTENT_SELECTOR = '.detail-scroll-content'

function buildAttachmentSlots(attachments = []) {
  return Array.from({ length: MAX_ATTACHMENT_COUNT }, (_, index) => ({
    slotIndex: index,
    file: attachments[index] || null
  }))
}

function unicodeSlice(value, maxLength) {
  return Array.from(String(value || '')).slice(0, maxLength).join('')
}

function isAppendSubmitDisabled(draft, canAppendRound, submitting) {
  return Boolean(submitting)
    || !canAppendRound
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

function calculateDetailScrollHeight(overlayRect, sheetRect, scrollRect, contentRect) {
  const overlayHeight = Number(overlayRect && overlayRect.height)
  const sheetHeight = Number(sheetRect && sheetRect.height)
  const scrollHeight = Number(scrollRect && scrollRect.height)
  const contentHeight = Number(contentRect && contentRect.height)
  if (
    ![overlayHeight, sheetHeight, scrollHeight, contentHeight].every(Number.isFinite)
    || overlayHeight <= 0
    || sheetHeight <= 0
    || scrollHeight < 0
    || contentHeight < 0
  ) {
    return null
  }
  const chromeHeight = Math.max(0, sheetHeight - scrollHeight)
  const maxScrollHeight = Math.max(
    DETAIL_SCROLL_MIN_HEIGHT_PX,
    Math.floor(overlayHeight * DETAIL_SHEET_MAX_HEIGHT_RATIO - chromeHeight)
  )
  return Math.max(
    DETAIL_SCROLL_MIN_HEIGHT_PX,
    Math.min(Math.ceil(contentHeight), maxScrollHeight)
  )
}

Page({
  data: {
    loading: true,
    loadingMore: false,
    refresherTriggered: false,
    errorMessage: '',
    feedbackPage: normalizeFeedbackPage({}),
    detailVisible: false,
    detailLoading: false,
    detailErrorMessage: '',
    detail: normalizeFeedbackDetail({}),
    detailScrollHeightPx: DETAIL_SCROLL_MIN_HEIGHT_PX,
    appendFormVisible: false,
    appendSubmitting: false,
    appendDraft: createFeedbackDraft(),
    appendDescriptionLength: 0,
    appendAttachmentSlots: buildAttachmentSlots(),
    appendSubmitDisabled: true,
    appendUploadProgress: {},
    appendUploadProgressPercent: 0,
    videoPreviewVisible: false,
    videoPreviewUrl: ''
  },

  onLoad() {
    this.bootstrap()
  },

  onUnload() {
    this.invalidateDetailLayoutMeasurement()
    this.stopPreviewVideo()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      wx.redirectTo({ url: LOGIN_PAGE_URL })
      return
    }
    this.loadFeedbacks()
  },

  handleAuthError(error) {
    if (!error || !error.authRequired) {
      return false
    }
    handleMaintainerAuthRequired(error.message)
    return true
  },

  async loadFeedbacks(options = {}) {
    const append = Boolean(options.append)
    const background = Boolean(options.background)
    const pageNo = append ? this.data.feedbackPage.pageNo + 1 : 1
    const requestId = (this.feedbackListRequestId || 0) + 1
    this.feedbackListRequestId = requestId
    // 请求序号避免下拉刷新与触底分页乱序返回后覆盖较新的列表。
    if (!append) {
      this.feedbackFirstPageRefreshing = true
    }
    if (append) {
      this.setData({ loadingMore: true })
    } else if (!background && !this.data.refresherTriggered) {
      this.setData({ loading: true, errorMessage: '' })
    }

    try {
      const response = await fetchFeedbackList(undefined, { pageNo, pageSize: PAGE_SIZE })
      if (requestId !== this.feedbackListRequestId) {
        return
      }
      this.setData({
        feedbackPage: mergeFeedbackPage(append ? this.data.feedbackPage : {}, response),
        loading: false,
        loadingMore: false,
        refresherTriggered: false,
        errorMessage: ''
      })
    } catch (error) {
      if (requestId !== this.feedbackListRequestId || this.handleAuthError(error)) {
        return
      }
      if (append || background || this.data.refresherTriggered) {
        this.setData({ loadingMore: false, refresherTriggered: false })
        wx.showToast({
          title: error && error.message ? error.message : '历史问题刷新失败',
          icon: 'none'
        })
        return
      }
      this.setData({
        loading: false,
        loadingMore: false,
        errorMessage: error && error.message ? error.message : '历史问题加载失败'
      })
    } finally {
      if (!append && requestId === this.feedbackListRequestId) {
        this.feedbackFirstPageRefreshing = false
      }
    }
  },

  handleRetry() {
    this.bootstrap()
  },

  handleSubmitFeedbackTap() {
    wx.navigateBack({
      delta: 1,
      fail: () => {
        wx.redirectTo({ url: FEEDBACK_PAGE_URL })
      }
    })
  },

  handleLoadMore() {
    if (
      this.data.loading ||
      this.data.loadingMore ||
      this.data.refresherTriggered ||
      this.feedbackFirstPageRefreshing ||
      !this.data.feedbackPage.hasMore
    ) {
      return
    }
    this.loadFeedbacks({ append: true })
  },

  handleRefresh() {
    if (this.data.loadingMore || this.data.refresherTriggered) {
      return
    }
    this.setData({ refresherTriggered: true })
    this.loadFeedbacks()
  },

  handleFeedbackTap(event) {
    const feedbackId = Number(event.currentTarget.dataset.feedbackId)
    if (!feedbackId) {
      return
    }
    this.invalidateDetailLayoutMeasurement()
    this.setData({
      detailVisible: true,
      detailLoading: true,
      detailErrorMessage: '',
      detail: normalizeFeedbackDetail({ id: feedbackId }),
      detailScrollHeightPx: DETAIL_SCROLL_MIN_HEIGHT_PX,
      appendFormVisible: false,
      appendDraft: createFeedbackDraft(),
      appendDescriptionLength: 0,
      appendAttachmentSlots: buildAttachmentSlots(),
      appendSubmitDisabled: true,
      appendUploadProgress: {},
      appendUploadProgressPercent: 0
    })
    this.loadFeedbackDetail(feedbackId)
  },

  async loadFeedbackDetail(feedbackId) {
    const requestId = (this.feedbackDetailRequestId || 0) + 1
    this.feedbackDetailRequestId = requestId
    try {
      const response = await fetchFeedbackDetail(undefined, feedbackId)
      if (
        requestId !== this.feedbackDetailRequestId ||
        !this.data.detailVisible ||
        Number(this.data.detail.id) !== Number(feedbackId)
      ) {
        return
      }
      this.setData({
        detailLoading: false,
        detailErrorMessage: '',
        detail: normalizeFeedbackDetail(response)
      }, () => {
        this.scheduleDetailScrollMeasurement()
      })
    } catch (error) {
      if (
        requestId !== this.feedbackDetailRequestId ||
        !this.data.detailVisible ||
        Number(this.data.detail.id) !== Number(feedbackId)
      ) {
        return
      }
      if (this.handleAuthError(error)) {
        return
      }
      this.setData({
        detailLoading: false,
        detailErrorMessage: error && error.message ? error.message : '反馈详情加载失败'
      })
    }
  },

  handleRetryDetail() {
    const feedbackId = this.data.detail.id
    if (!feedbackId) {
      return
    }
    this.invalidateDetailLayoutMeasurement()
    this.setData({
      detailLoading: true,
      detailErrorMessage: '',
      detailScrollHeightPx: DETAIL_SCROLL_MIN_HEIGHT_PX
    })
    this.loadFeedbackDetail(feedbackId)
  },

  handleCloseDetail() {
    if (this.data.appendSubmitting) {
      return
    }
    this.feedbackDetailRequestId = (this.feedbackDetailRequestId || 0) + 1
    this.invalidateDetailLayoutMeasurement()
    this.stopPreviewVideo()
    this.setData({
      detailVisible: false,
      appendFormVisible: false
    })
  },

  noop() {},

  measureDetailScrollHeight() {
    const feedbackId = Number(this.data.detail.id)
    if (
      !this.data.detailVisible
      || this.data.detailLoading
      || !feedbackId
      || typeof this.createSelectorQuery !== 'function'
    ) {
      return
    }
    const measureId = (this.detailLayoutMeasureId || 0) + 1
    this.detailLayoutMeasureId = measureId
    const query = this.createSelectorQuery()
    query.select(DETAIL_OVERLAY_SELECTOR).boundingClientRect()
    query.select(DETAIL_SHEET_SELECTOR).boundingClientRect()
    query.select(DETAIL_SCROLL_SELECTOR).boundingClientRect()
    query.select(DETAIL_SCROLL_CONTENT_SELECTOR).boundingClientRect()
    query.exec((results = []) => {
      if (
        measureId !== this.detailLayoutMeasureId
        || !this.data.detailVisible
        || Number(this.data.detail.id) !== feedbackId
      ) {
        return
      }
      const height = calculateDetailScrollHeight(...results)
      if (height !== null && height !== this.data.detailScrollHeightPx) {
        this.setData({ detailScrollHeightPx: height })
      }
    })
  },

  scheduleDetailScrollMeasurement(remeasureAfterTransition = false) {
    this.measureDetailScrollHeight()
    if (!remeasureAfterTransition) {
      return
    }
    if (this.detailLayoutMeasureTimer) {
      clearTimeout(this.detailLayoutMeasureTimer)
    }
    const feedbackId = Number(this.data.detail.id)
    this.detailLayoutMeasureTimer = setTimeout(() => {
      this.detailLayoutMeasureTimer = null
      if (!this.data.detailVisible || Number(this.data.detail.id) !== feedbackId) {
        return
      }
      this.measureDetailScrollHeight()
    }, DETAIL_APPEND_TRANSITION_MS)
  },

  invalidateDetailLayoutMeasurement() {
    this.detailLayoutMeasureId = (this.detailLayoutMeasureId || 0) + 1
    if (this.detailLayoutMeasureTimer) {
      clearTimeout(this.detailLayoutMeasureTimer)
      this.detailLayoutMeasureTimer = null
    }
  },

  handlePreviewImage(event) {
    const current = String(event.currentTarget.dataset.url || '')
    if (!current) {
      return
    }
    const urls = this.data.detail.rounds.flatMap((round) => (
      round.attachments.filter((attachment) => attachment.isImage).map((attachment) => attachment.url)
    )).filter(Boolean)
    wx.previewImage({ current, urls: urls.length ? urls : [current] })
  },

  handleOpenVideo(event) {
    const url = String(event.currentTarget.dataset.url || '')
    if (!url) {
      wx.showToast({ title: '视频地址不可用', icon: 'none' })
      return
    }
    this.setData({ videoPreviewVisible: true, videoPreviewUrl: url })
  },

  handleCloseVideo() {
    this.stopPreviewVideo()
    this.setData({ videoPreviewVisible: false, videoPreviewUrl: '' })
  },

  stopPreviewVideo() {
    if (typeof wx.createVideoContext !== 'function') {
      return
    }
    const context = wx.createVideoContext('feedbackPreviewVideo', this)
    if (context && typeof context.stop === 'function') {
      context.stop()
    }
  },

  handleAppendToggle() {
    if (!this.data.detail.canAppendRound || this.data.appendSubmitting) {
      return
    }
    this.setData({ appendFormVisible: !this.data.appendFormVisible }, () => {
      this.scheduleDetailScrollMeasurement(true)
    })
  },

  handleAppendDescriptionInput(event) {
    const description = unicodeSlice(event.detail.value, 200)
    const appendDraft = Object.assign({}, this.data.appendDraft, { description })
    this.setData({
      appendDraft,
      appendDescriptionLength: Array.from(description).length,
      appendSubmitDisabled: isAppendSubmitDisabled(
        appendDraft, this.data.detail.canAppendRound, this.data.appendSubmitting
      )
    })
  },

  async handleAppendChooseMedia() {
    if (this.data.appendSubmitting) {
      return
    }
    const remainingCount = MAX_ATTACHMENT_COUNT - this.data.appendDraft.attachments.length
    if (remainingCount <= 0) {
      wx.showToast({ title: '每轮最多添加 3 个附件', icon: 'none' })
      return
    }
    try {
      // 补充轮次同样只选择本地文件，用户确认提交后才申请票据并上传。
      const result = await chooseFeedbackMedia(remainingCount)
      this.setAppendDraft(addChosenFeedbackMedia(this.data.appendDraft, result.tempFiles || []))
    } catch (error) {
      if (!isChooseMediaCancel(error)) {
        wx.showToast({
          title: error && error.message ? error.message : '附件选择失败',
          icon: 'none'
        })
      }
    }
  },

  handleAppendPreviewAttachment(event) {
    const index = Number(event.currentTarget.dataset.index)
    const file = this.data.appendDraft.attachments[index]
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

  handleAppendRemoveAttachment(event) {
    if (this.data.appendSubmitting) {
      return
    }
    const index = Number(event.currentTarget.dataset.index)
    this.setAppendDraft(Object.assign({}, this.data.appendDraft, {
      attachments: this.data.appendDraft.attachments.filter((item, itemIndex) => itemIndex !== index)
    }))
  },

  setAppendDraft(appendDraft) {
    this.setData({
      appendDraft,
      appendAttachmentSlots: buildAttachmentSlots(appendDraft.attachments),
      appendSubmitDisabled: isAppendSubmitDisabled(
        appendDraft, this.data.detail.canAppendRound, this.data.appendSubmitting
      )
    })
  },

  handleAppendUploadProgress(clientId, progress = {}) {
    const appendUploadProgress = Object.assign({}, this.data.appendUploadProgress, {
      [clientId]: Number(progress.progress) || 0
    })
    const uploadedPercent = this.data.appendDraft.attachments.reduce((total, attachment) => (
      total + (Number(appendUploadProgress[attachment.clientId]) || 0)
    ), 0)
    const attachmentCount = Math.max(1, this.data.appendDraft.attachments.length)
    this.setData({
      appendUploadProgress,
      appendUploadProgressPercent: Math.round(uploadedPercent / attachmentCount)
    })
  },

  async handleAppendSubmit() {
    if (this.data.appendSubmitting || !this.data.detail.canAppendRound) {
      return
    }
    const validation = validateFeedbackDescription(this.data.appendDraft.description)
    if (!validation.valid) {
      wx.showToast({ title: validation.message, icon: 'none' })
      return
    }
    this.setData({
      appendSubmitting: true,
      appendSubmitDisabled: true,
      appendUploadProgress: {},
      appendUploadProgressPercent: 0
    }, () => {
      this.scheduleDetailScrollMeasurement()
    })
    try {
      // 历史轮次不可编辑；这里始终创建下一轮，并在失败时保留当前补充草稿。
      const result = await submitFeedbackDraft({
        feedbackId: this.data.detail.id,
        draft: this.data.appendDraft,
        wxApi: wx,
        onProgress: this.handleAppendUploadProgress.bind(this)
      })
      this.setData({
        appendSubmitting: false,
        appendFormVisible: false,
        appendDraft: createFeedbackDraft(),
        appendDescriptionLength: 0,
        appendAttachmentSlots: buildAttachmentSlots(),
        appendSubmitDisabled: true,
        appendUploadProgress: {},
        appendUploadProgressPercent: 0,
        detail: result.detail
      }, () => {
        this.scheduleDetailScrollMeasurement(true)
      })
      wx.showToast({ title: '补充反馈已提交', icon: 'success' })
      this.loadFeedbacks({ background: true })
    } catch (error) {
      if (this.handleAuthError(error)) {
        return
      }
      const appendDraft = error && error.draft ? error.draft : this.data.appendDraft
      this.setData({
        appendSubmitting: false,
        appendDraft,
        appendAttachmentSlots: buildAttachmentSlots(appendDraft.attachments),
        appendSubmitDisabled: isAppendSubmitDisabled(
          appendDraft, this.data.detail.canAppendRound, false
        )
      }, () => {
        this.scheduleDetailScrollMeasurement()
      })
      wx.showToast({
        title: error && error.message ? error.message : '补充反馈提交失败',
        icon: 'none'
      })
    }
  }
})

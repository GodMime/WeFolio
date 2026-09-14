// 详情只消费本次合法渲染结果的快照；不提供作品查询或分享直达回退。
const DETAIL_EVENT = 'portfolioWorkDetail'
const MEDIA_TYPES = ['IMAGE', 'ANIMATION', 'VIDEO']
const CONTEXT_WAIT_MS = 1000
function copy(value) { return JSON.parse(JSON.stringify(value)) }
function normalizeDetailOptions(raw = {}) {
  return { showTitle: raw.showTitle !== false, showDescription: raw.showDescription !== false }
}
function createWorkDetailPayload(options = {}) {
  const component = (Array.isArray(options.components) ? options.components : []).find(item =>
    item && item.componentKey === options.componentKey && item.componentType === 'SINGLE_WORK')
  if (!component) return null
  const data = component.data || component
  if (data.openMode !== 'DETAIL_PAGE') return null
  const work = data.work
  if (!work || !work.workId || !MEDIA_TYPES.includes(work.mediaType) || !(work.previewUrl || work.mediaUrl)) return null
  const snapshot = copy(work)
  snapshot.mediaUrl = work.previewUrl || work.mediaUrl
  snapshot.coverUrl = work.thumbnailUrl || work.coverUrl || snapshot.mediaUrl
  const backgroundColor = /^#[0-9A-Fa-f]{6}$/.test(options.theme && options.theme.backgroundColor)
    ? options.theme.backgroundColor : '#FFFFFF'
  const channels = backgroundColor.slice(1).match(/../g).map(value => parseInt(value, 16))
  const inferredMode = channels[0] * 0.299 + channels[1] * 0.587 + channels[2] * 0.114 < 128 ? 'dark' : 'light'
  const payload = { componentKey: component.componentKey, work: snapshot, detailOptions: normalizeDetailOptions(data.detailOptions),
    theme: { backgroundColor, themeMode: options.theme && options.theme.themeMode || inferredMode },
    preview: options.preview === true,
    browserContext: options.preview === true ? null : options.browserContext || null,
    contextId: options.preview === true ? 'preview' : options.browserContext && options.browserContext.idempotencyKey || '',
    onWorkEvent: options.preview === true ? null : options.onWorkEvent }
  return validPayload(payload) ? payload : null
}
function validPayload(payload) {
  if (!payload || !payload.componentKey || !payload.work || !payload.work.workId
    || !MEDIA_TYPES.includes(payload.work.mediaType) || !payload.work.mediaUrl || !payload.theme) return false
  if (payload.preview === true) return payload.contextId === 'preview'
  const context = payload.browserContext
  return Boolean(typeof payload.contextId === 'string' && payload.contextId.trim()
    && context && payload.contextId === context.idempotencyKey
    && !(typeof context.isInvalid === 'function' && context.isInvalid())
    && !(typeof context.isDisposed === 'function' && context.isDisposed()))
}
function openWorkDetail(page, options = {}) {
  if (page._openingWorkDetail) return false
  const payload = createWorkDetailPayload(options)
  if (!payload) { wx.showToast({ title: '作品暂不可用', icon: 'none' }); return false }
  page._openingWorkDetail = true
  wx.navigateTo({ url: options.route,
    success(result) { if (result.eventChannel) result.eventChannel.emit(DETAIL_EVENT, payload) },
    fail() { wx.showToast({ title: '作品打开失败，请重试', icon: 'none' }) },
    complete() { page._openingWorkDetail = false }
  })
  return true
}
// 页实例将会话引用留在实例上，不能写入 setData 或存储。
function createWorkDetailPage({ fallbackUrl, setTimer = setTimeout, clearTimer = clearTimeout }) {
  return {
    data: { loadingContext: true, available: false, work: {}, detailOptions: { showTitle: true, showDescription: true },
      backgroundColor: '#FFFFFF', themeMode: 'light', navigationColor: '#212529', imageFailed: false },
    onLoad() {
      this._detailAlive = true; this._detailVisible = false; this._detailEventSent = false; this._detailEventPending = false
      this._contextResolved = false
      if (wx.hideShareMenu) wx.hideShareMenu({ menus: ['shareAppMessage', 'shareTimeline'] })
      const channel = this.getOpenerEventChannel && this.getOpenerEventChannel()
      this._detailChannel = channel
      this._detailReceiver = payload => {
        if (!this._detailAlive || this._contextResolved) return
        if (!validPayload(payload)) { this.finishMissingContext(); return }
        this._contextResolved = true
        this.clearContextWait()
        this._detailPayload = payload
        const context = payload.browserContext
        if (context && !payload.preview) {
          this._sourceRefresh = context.onRefresh
          this._detailRefresh = (...args) => {
            try { return typeof this._sourceRefresh === 'function' ? this._sourceRefresh(...args) : undefined }
            finally { this.expireDetailContext() }
          }
          context.onRefresh = this._detailRefresh
        }
        this.setData({ loadingContext: false, available: true, work: payload.work, detailOptions: payload.detailOptions,
          backgroundColor: payload.theme.backgroundColor, themeMode: payload.theme.themeMode,
          navigationColor: payload.theme.themeMode === 'dark' ? '#FFFFFF' : '#212529' }, () => {
          if (this._detailAlive && this._detailVisible) this.acquireDetailActivity()
        })
      }
      if (channel && typeof channel.on === 'function') {
        // 事件通道可在导航成功回调才发送，先显示等待态，超时后明确返回不可用态。
        this._contextWait = setTimer(() => this.finishMissingContext(), CONTEXT_WAIT_MS)
        channel.on(DETAIL_EVENT, this._detailReceiver)
      } else this.finishMissingContext()
    },
    clearContextWait() {
      if (this._contextWait !== undefined && this._contextWait !== null) clearTimer(this._contextWait)
      this._contextWait = null
    },
    finishMissingContext() {
      this.clearContextWait()
      if (!this._detailAlive || this._contextResolved) return
      this._contextResolved = true
      this.setData({ loadingContext: false, available: false })
    },
    onShow() {
      this._detailVisible = true
      if (this._requiresDetailReturn) { this.handleBack(); return }
      if (!this.isDetailContextCurrent()) return
      this.acquireDetailActivity()
      if (this._detailImageLoaded) this.recordDetailWorkEvent()
    },
    // 来源授权刷新后必须由来源页重新选作品，不能继续消费旧快照。
    expireDetailContext() {
      if (!this._detailAlive || this._requiresDetailReturn) return
      this._requiresDetailReturn = true
      this.releaseDetailActivity(); this.pauseDetailVideo()
      this.setData({ loadingContext: false, available: false })
      if (this._detailVisible) this.handleBack()
    },
    isDetailContextCurrent() {
      const context = this._detailPayload && this._detailPayload.browserContext
      if (context && ((context.isInvalid && context.isInvalid()) || (context.isDisposed && context.isDisposed()))) {
        this.expireDetailContext(); return false
      }
      return !this._requiresDetailReturn
    },
    acquireDetailActivity() {
      const payload = this._detailPayload
      if (!payload || !this._detailVisible || !this.data.available || payload.preview) return
      if (payload.browserContext && typeof payload.browserContext.show === 'function') payload.browserContext.show(this)
    },
    releaseDetailActivity() {
      const context = this._detailPayload && this._detailPayload.browserContext
      if (context && typeof context.hide === 'function') context.hide(this)
    },
    pauseDetailVideo() {
      if (!wx.createVideoContext) return
      const video = wx.createVideoContext('portfolioDetailVideo', this)
      if (video && video.pause) video.pause()
    },
    onHide() {
      this._detailVisible = false
      this.releaseDetailActivity(); this.pauseDetailVideo()
    },
    onUnload() {
      this._detailAlive = false; this._detailVisible = false
      this.clearContextWait()
      this.releaseDetailActivity(); this.pauseDetailVideo()
      if (this._detailChannel && this._detailChannel.off) this._detailChannel.off(DETAIL_EVENT, this._detailReceiver)
      const context = this._detailPayload && this._detailPayload.browserContext
      if (context && context.onRefresh === this._detailRefresh) context.onRefresh = this._sourceRefresh
      this._detailPayload = null
    },
    recordDetailWorkEvent() {
      const payload = this._detailPayload
      if (!this.data.available || !this._detailAlive || !this._detailVisible || !payload || payload.preview || this._detailEventSent || this._detailEventPending || typeof payload.onWorkEvent !== 'function' || !this.isDetailContextCurrent()) return
      this._detailEventPending = true
      Promise.resolve().then(() => {
        this._detailEventPending = false
        // 媒体回调与页面切换可能同帧发生，真正派发前再次核验页面和授权上下文。
        if (!this._detailAlive || !this._detailVisible || !this.data.available || payload !== this._detailPayload || !this.isDetailContextCurrent()) return
        this._detailEventSent = true
        return payload.onWorkEvent(copy(payload.work))
      }).catch(() => {})
    },
    handleImageLoaded() { if (!this._detailAlive) return; this._detailImageLoaded = true; this.recordDetailWorkEvent() },
    handleImageError() { if (this._detailAlive) this.setData({ imageFailed: true }) },
    handleVideoPlay() { this.recordDetailWorkEvent() },
    handleVideoError() { if (this._detailAlive && this._detailVisible) wx.showToast({ title: '视频暂不可用', icon: 'none' }) },
    handlePreviewImage() {
      if (!this.data.available || !this.isDetailContextCurrent()) return
      const url = this.data.imageFailed ? this.data.work.coverUrl : this.data.work.mediaUrl
      if (url) wx.previewImage({ current: url, urls: [url] })
    },
    handleBack() {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
      if (pages.length > 1) wx.navigateBack()
      else wx.reLaunch({ url: fallbackUrl })
    }
  }
}
module.exports = { DETAIL_EVENT, normalizeDetailOptions, createWorkDetailPayload, validPayload, openWorkDetail, createWorkDetailPage }

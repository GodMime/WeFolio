const BACKGROUND_ERROR_MESSAGE = '背景加载失败'
const BACKGROUND_RETRY_LABEL = '重新加载背景'
const BACKGROUND_VIDEO_ID = 'mockStructuredTextBackgroundVideo'
Component({
  properties: {
    viewModel: { type: Object, value: { blocks: [] } },
    active: { type: Boolean, value: true }
  },
  data: {
    mediaFailed: false,
    mediaReady: false,
    mediaAttempt: 0,
    videoPosterUrl: '',
    backgroundErrorMessage: BACKGROUND_ERROR_MESSAGE,
    backgroundRetryLabel: BACKGROUND_RETRY_LABEL
  },
  observers: {
    active(value) {
      if (!value) this.pauseBackgroundVideo()
    },
    viewModel(value) {
      const background = value && value.background
      const identity = background ? `${background.mediaUrl || ''}:${Boolean(background.isVideo)}` : ''
      const videoPosterUrl = background && background.isVideo
        ? background.posterUrl || background.coverUrl || background.thumbnailUrl || '' : ''
      if (identity === this._backgroundIdentity) {
        if (videoPosterUrl !== this.data.videoPosterUrl) this.setData({ videoPosterUrl })
        return
      }
      if (this._backgroundIsVideo) this.pauseBackgroundVideo()
      this._backgroundIdentity = identity
      this._backgroundIsVideo = Boolean(background && background.isVideo)
      this._videoContext = null
      this.setData({ mediaFailed: false, mediaReady: false, videoPosterUrl, mediaAttempt: this.data.mediaAttempt + 1 })
    }
  },
  lifetimes: {
    detached() {
      this.pauseBackgroundVideo()
      this._videoContext = null
    }
  },
  pageLifetimes: {
    hide() {
      this._pageHidden = true
      this.pauseBackgroundVideo()
    },
    show() { this._pageHidden = false }
  },
  methods: {
    /** 背景视频失活、退到后台或卸载时必须显式暂停。 */
    pauseBackgroundVideo() {
      const background = this.data.viewModel && this.data.viewModel.background
      if (!this._backgroundIsVideo && !(background && background.isVideo)) return
      if (!this._videoContext && typeof wx !== 'undefined' && wx.createVideoContext) {
        this._videoContext = wx.createVideoContext(BACKGROUND_VIDEO_ID, this)
      }
      if (this._videoContext && typeof this._videoContext.pause === 'function') this._videoContext.pause()
    },
    /** 首个有效播放进度出现前保留静态封面，避免视频首帧闪烁。 */
    handleVideoTimeUpdate(event) {
      const dataset = event && event.currentTarget && event.currentTarget.dataset
      if (dataset && dataset.attempt !== undefined && Number(dataset.attempt) !== this.data.mediaAttempt) return
      if (this.data.mediaFailed || this.data.mediaReady || !(event && event.detail && event.detail.currentTime > 0)) return
      this.setData({ mediaReady: true })
    },
    handleVideoPlay() {
      if (!this.data.active || this._pageHidden || this.data.mediaFailed) this.pauseBackgroundVideo()
    },
    /** 迟到的旧背景错误不能覆盖新资源或重试后的状态。 */
    handleMediaError(event) {
      const dataset = event && event.currentTarget && event.currentTarget.dataset
      if (dataset && dataset.attempt !== undefined && Number(dataset.attempt) !== this.data.mediaAttempt) return
      if (!this.data.viewModel.background || this.data.mediaFailed) return
      if (this.data.viewModel.background.isVideo) this.pauseBackgroundVideo()
      this.setData({ mediaFailed: true, mediaReady: false })
      this.triggerEvent('backgrounderror')
    },
    /** 错误时已卸载媒体节点；清除错误后重新挂载原资源，不改动文字或地址。 */
    handleRetryBackground() {
      if (!this.data.active || !this.data.mediaFailed || !this.data.viewModel.background) return
      this._videoContext = null
      this.setData({ mediaFailed: false, mediaReady: false, mediaAttempt: this.data.mediaAttempt + 1 })
    }
  }
})

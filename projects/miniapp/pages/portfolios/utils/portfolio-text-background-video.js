const BACKGROUND_VIDEO_ID = 'textBackgroundVideo'
const BACKGROUND_VIDEO_SELECTOR = '.text-background-video'

/** 文字背景独立静音播放；生命周期与视口共同控制，避免离屏视频持续解码。 */
const textBackgroundVideo = {
  properties: { backgroundVideoPaused: { type: Boolean, value: false } },
  data: { backgroundVideoVisible: false, backgroundVideoFailed: false, backgroundVideoStarted: false },
  observers: {
    backgroundVideoPaused(paused) {
      if (paused) {
        this.disconnectBackgroundVideoObserver()
        this.pauseBackgroundVideo()
      } else this.syncBackgroundVideo()
    }
  },
  lifetimes: {
    ready() {
      this._textBackgroundVideoReady = true
      this.syncBackgroundVideo()
    },
    detached() {
      this._textBackgroundVideoReady = false
      this.disconnectBackgroundVideoObserver()
      this.pauseBackgroundVideo()
      this._textBackgroundVideoContext = null
    }
  },
  pageLifetimes: {
    hide() {
      this._textBackgroundPageHidden = true
      this.disconnectBackgroundVideoObserver()
      this.pauseBackgroundVideo()
    },
    show() {
      this._textBackgroundPageHidden = false
      this.syncBackgroundVideo()
    }
  },
  methods: {
    /** 内容刷新复用同一视频；换资源后废弃旧观察回调和失败状态。 */
    syncBackgroundVideo() {
      const background = this.data.background || {}
      const url = background.enabled ? background.videoUrl || '' : ''
      if (url !== this._textBackgroundVideoUrl) {
        this.disconnectBackgroundVideoObserver()
        this.pauseBackgroundVideo()
        this._textBackgroundVideoUrl = url
        this._textBackgroundVideoContext = null
        // 等待故障节点恢复挂载后再观察，避免换视频时观察到旧节点或空节点。
        this.setData({ backgroundVideoFailed: false, backgroundVideoStarted: false }, () => this.observeBackgroundVideo())
        return
      }
      this.observeBackgroundVideo()
    },
    /** 宿主视频弹层不改变背景的几何位置，需额外通过属性显式暂停。 */
    observeBackgroundVideo() {
      const url = this._textBackgroundVideoUrl
      if (!this._textBackgroundVideoReady || !url || this._textBackgroundPageHidden || this.properties.backgroundVideoPaused
          || this.data.backgroundVideoFailed || this._textBackgroundVideoObserver) return
      this._textBackgroundVideoContext = wx.createVideoContext(BACKGROUND_VIDEO_ID, this)
      const observer = this.createIntersectionObserver({ thresholds: [0, 0.01] })
      this._textBackgroundVideoObserver = observer
      observer.relativeToViewport().observe(BACKGROUND_VIDEO_SELECTOR, result => {
        if (this._textBackgroundVideoObserver !== observer || !this._textBackgroundVideoReady
            || this._textBackgroundPageHidden || this.properties.backgroundVideoPaused || this.data.backgroundVideoFailed
            || this.data.background.videoUrl !== url) return
        const visible = result.intersectionRatio > 0
        if (visible === this.data.backgroundVideoVisible) return
        this.setData({ backgroundVideoVisible: visible })
        if (visible) this._textBackgroundVideoContext.play()
        else this.pauseBackgroundVideo()
      })
    },
    /** 暂停保留进度，再次可见时继续由原生 loop 属性循环。 */
    pauseBackgroundVideo() {
      if (this._textBackgroundVideoContext) this._textBackgroundVideoContext.pause()
      if (this.data.backgroundVideoVisible) this.setData({ backgroundVideoVisible: false })
    },
    /** 先清除身份再断开监听，迟到回调不得操作已经隐藏或替换的视频。 */
    disconnectBackgroundVideoObserver() {
      const observer = this._textBackgroundVideoObserver
      this._textBackgroundVideoObserver = null
      if (observer) observer.disconnect()
    },
    /** 原生自动播放事件也受页面和可见状态约束。 */
    handleBackgroundVideoPlay() {
      if (!this._textBackgroundVideoReady || this._textBackgroundPageHidden
          || this.properties.backgroundVideoPaused || !this.data.backgroundVideoVisible || this.data.backgroundVideoFailed) this.pauseBackgroundVideo()
    },
    /** controls=false 时微信不显示原生 poster；播放进度产生后才收起独立封面。 */
    handleBackgroundVideoTimeUpdate(event) {
      const url = event && event.currentTarget && event.currentTarget.dataset && event.currentTarget.dataset.src
      if (!url || url !== this.data.background.videoUrl || this.data.backgroundVideoFailed
          || this.data.backgroundVideoStarted || !(event.detail && event.detail.currentTime > 0)) return
      this.setData({ backgroundVideoStarted: true })
    },
    /** 播放失败后保留授权封面与文字，不自动反复请求故障视频。 */
    handleBackgroundVideoError(event) {
      const url = event && event.currentTarget && event.currentTarget.dataset && event.currentTarget.dataset.src
      if (!url || url !== this.data.background.videoUrl) return
      this.disconnectBackgroundVideoObserver()
      this.pauseBackgroundVideo()
      this.setData({ backgroundVideoFailed: true })
    }
  }
}

module.exports = { textBackgroundVideo }

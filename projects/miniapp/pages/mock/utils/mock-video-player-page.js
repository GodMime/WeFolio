const { VIDEO_PLAYER_EVENT } = require('./mock-video-player')

const VIDEO_ID = 'mockVideoPlayer'
const CONTEXT_WAIT_MS = 1000
const FULLSCREEN_WAIT_MS = 3000
const FULLSCREEN_DIRECTION = 90
const FALLBACK_ROUTE = '/pages/mock/works/works'
const SHARE_MENUS = ['shareAppMessage', 'shareTimeline']
const VIDEO_UNAVAILABLE = '视频暂不可用'
const FULLSCREEN_UNAVAILABLE = '全屏打开失败，请重试'
const FULLSCREEN_EXIT_FAILED = '请先退出全屏，再返回作品'
const TOAST_ICON = 'none'
const MEDIA_URL_PATTERN = /^https?:\/\/\S+$/i

// 原生视频放入独立 webview 页面，避免与体验页的 Skyline 弹窗及底栏争抢全屏层级。
function createMockVideoPlayerPage({ wxApi, setTimer = setTimeout, clearTimer = clearTimeout }) {
  return {
    data: { loadingContext: true, available: false, url: '', poster: '', title: '', playing: false, fullscreen: false },
    onLoad() {
      this._playerAlive = true
      this._playerVisible = false
      this._playerShown = false
      this._resumeInitialPlayback = false
      this._contextResolved = false
      this._pendingBack = false
      this._backNavigating = false
      this._fullscreenRequested = false
      if (wxApi.hideShareMenu) wxApi.hideShareMenu({ menus: SHARE_MENUS })
      this._playerChannel = this.getOpenerEventChannel && this.getOpenerEventChannel()
      this._playerReceiver = payload => {
        if (!this._playerAlive || this._contextResolved) return
        if (!payload || typeof payload.url !== 'string' || !MEDIA_URL_PATTERN.test(payload.url.trim())) {
          this.finishMissingContext()
          return
        }
        this._contextResolved = true
        this.clearContextWait()
        this.setData({ loadingContext: false, available: true, url: payload.url.trim(),
          poster: typeof payload.poster === 'string' ? payload.poster : '',
          title: typeof payload.title === 'string' ? payload.title : '' })
      }
      if (this._playerChannel && typeof this._playerChannel.on === 'function') {
        this._contextWait = setTimer(() => this.finishMissingContext(), CONTEXT_WAIT_MS)
        this._playerChannel.on(VIDEO_PLAYER_EVENT, this._playerReceiver)
      } else this.finishMissingContext()
    },
    clearContextWait() {
      if (this._contextWait !== undefined && this._contextWait !== null) clearTimer(this._contextWait)
      this._contextWait = null
    },
    finishMissingContext() {
      this.clearContextWait()
      if (!this._playerAlive || this._contextResolved) return
      this._contextResolved = true
      this.setData({ loadingContext: false, available: false })
    },
    onShow() {
      if (!this._playerAlive) return
      this._playerVisible = true
      this._playerShown = true
      if (!this.isPlayerAvailable()) return
      // 自动播放可能早于首次 onShow；仅补偿首次播放，后台返回时不擅自恢复声音。
      if (this._resumeInitialPlayback) {
        this._resumeInitialPlayback = false
        this.callPlayerVideo('play')
      }
    },
    onHide() {
      if (!this._playerAlive) return
      this._playerVisible = false
      this.callPlayerVideo('pause')
    },
    onUnload() {
      this._playerAlive = false
      this._playerVisible = false
      this.clearContextWait()
      this.clearFullscreenWait()
      // 原生导航也会卸载页面；分别兜底，退出全屏异常不能阻断停止播放。
      this.callPlayerVideo('exitFullScreen')
      this.callPlayerVideo('stop')
      if (this._playerChannel && typeof this._playerChannel.off === 'function') {
        this._playerChannel.off(VIDEO_PLAYER_EVENT, this._playerReceiver)
      }
      this._playerVideo = null
      this._playerChannel = null
      this._playerReceiver = null
    },
    isPlayerAvailable() {
      return Boolean(this._playerAlive && this.data.available && !this._pendingBack)
    },
    callPlayerVideo(method, options) {
      try {
        if (!this._playerVideo && this.data.available && wxApi.createVideoContext) {
          this._playerVideo = wxApi.createVideoContext(VIDEO_ID, this)
        }
        const video = this._playerVideo
        if (!video || typeof video[method] !== 'function') return false
        video[method](options)
        return true
      } catch (_) {
        return false
      }
    },
    handleEnterFullscreen() {
      if (!this._playerVisible || !this.isPlayerAvailable() || this.data.fullscreen || this._fullscreenRequested) return
      this._fullscreenRequested = true
      // 原生全屏 API 无失败回调，超时后恢复返回能力，避免一直等待进入事件。
      this._fullscreenWait = setTimer(() => {
        if (!this._playerAlive) return
        this.clearFullscreenWait()
        this._fullscreenRequested = false
        if (this._pendingBack && !this.data.fullscreen) this.navigatePlayerBack()
        else if (this._playerVisible && !this.data.fullscreen) wxApi.showToast({ title: FULLSCREEN_UNAVAILABLE, icon: TOAST_ICON })
      }, FULLSCREEN_WAIT_MS)
      if (!this.callPlayerVideo('requestFullScreen', { direction: FULLSCREEN_DIRECTION })) {
        this.clearFullscreenWait()
        this._fullscreenRequested = false
        wxApi.showToast({ title: FULLSCREEN_UNAVAILABLE, icon: TOAST_ICON })
      }
    },
    clearFullscreenWait() {
      if (this._fullscreenWait !== undefined && this._fullscreenWait !== null) clearTimer(this._fullscreenWait)
      this._fullscreenWait = null
    },
    handleTogglePlayback() {
      if (!this._playerVisible || !this.isPlayerAvailable()) return
      this.callPlayerVideo(this.data.playing ? 'pause' : 'play')
    },
    handleVideoPlay() {
      if (!this._playerAlive) return
      if (!this._playerVisible) {
        if (!this._playerShown && this.isPlayerAvailable()) this._resumeInitialPlayback = true
        this.callPlayerVideo('pause')
        return
      }
      if (!this.isPlayerAvailable()) { this.callPlayerVideo('pause'); return }
      this.setData({ playing: true })
    },
    handleVideoPause() {
      if (this._playerAlive) this.setData({ playing: false })
    },
    handleVideoEnded() {
      if (this._playerAlive) this.setData({ playing: false })
    },
    handleVideoError() {
      if (this._playerAlive && this._playerVisible) wxApi.showToast({ title: VIDEO_UNAVAILABLE, icon: TOAST_ICON })
    },
    handleFullscreenChange(event) {
      if (!this._playerAlive || !event || !event.detail || typeof event.detail.fullScreen !== 'boolean') return
      const fullscreen = event.detail.fullScreen
      this.clearFullscreenWait()
      this._fullscreenRequested = false
      this.setData({ fullscreen })
      if (!this._pendingBack) return
      if (fullscreen) this.exitPlayerFullscreen()
      else this.navigatePlayerBack()
    },
    exitPlayerFullscreen() {
      if (!this.callPlayerVideo('exitFullScreen') && this._playerAlive) {
        wxApi.showToast({ title: FULLSCREEN_EXIT_FAILED, icon: TOAST_ICON })
      }
    },
    handleBack() {
      if (!this._playerAlive || this._backNavigating) return
      this._pendingBack = true
      this.callPlayerVideo('pause')
      if (this.data.fullscreen || this._fullscreenRequested) this.exitPlayerFullscreen()
      else this.navigatePlayerBack()
    },
    navigatePlayerBack() {
      if (!this._playerAlive || !this._pendingBack || this._backNavigating) return
      this._backNavigating = true
      wxApi.navigateBack({ delta: 1, fail: () => {
        if (!this._playerAlive) return
        wxApi.reLaunch({ url: FALLBACK_ROUTE, fail: () => {
          if (this._playerAlive) this._backNavigating = false
        } })
      } })
    }
  }
}

module.exports = { createMockVideoPlayerPage }

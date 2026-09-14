// 原生视频独立于作品集的 Skyline 布局，授权和地址只通过本次导航的内存通道传递。
const PLAYER_EVENT = 'portfolioVideoPlayer'
const VIDEO_ID = 'portfolioVideoPlayer'
const CONTEXT_WAIT_MS = 1000
const FULLSCREEN_WAIT_MS = 3000
const FULLSCREEN_DIRECTION = 90
const FALLBACK_ROUTE = '/pages/index/index'
const SHARE_MENUS = ['shareAppMessage', 'shareTimeline']
const VIDEO_UNAVAILABLE = '视频暂不可用'
const FULLSCREEN_UNAVAILABLE = '全屏打开失败，请重试'
const FULLSCREEN_EXIT_FAILED = '请先退出全屏，再返回作品集'
const TOAST_ICON = 'none'
const MEDIA_URL_PATTERN = /^https?:\/\/\S+$/i

function validPayload(payload) {
  if (!payload || typeof payload.url !== 'string' || !MEDIA_URL_PATTERN.test(payload.url.trim())) return false
  const context = payload.browserContext
  if (context === null) return payload.contextId === ''
  if (!context || typeof payload.contextId !== 'string' || !payload.contextId.trim()
    || payload.contextId !== context.idempotencyKey) return false
  return !(typeof context.isInvalid === 'function' && context.isInvalid())
    && !(typeof context.isDisposed === 'function' && context.isDisposed())
}

function createVideoPlayerPage({ wxApi, setTimer = setTimeout, clearTimer = clearTimeout }) {
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
      this._requiresPlayerReturn = false
      this._fullscreenRequested = false
      if (wxApi.hideShareMenu) wxApi.hideShareMenu({ menus: SHARE_MENUS })
      this._playerChannel = this.getOpenerEventChannel && this.getOpenerEventChannel()
      this._playerReceiver = payload => {
        if (!this._playerAlive || this._contextResolved) return
        if (!validPayload(payload)) { this.finishMissingContext(); return }
        this._contextResolved = true
        this.clearContextWait()
        this._playerPayload = payload
        const context = payload.browserContext
        if (context) {
          const sourceRefresh = context.onRefresh
          this._sourceRefresh = sourceRefresh
          this._playerRefresh = (...args) => {
            if (!this._playerAlive) return undefined
            try { return typeof sourceRefresh === 'function' ? sourceRefresh.apply(context, args) : undefined }
            finally { this.expirePlayerContext() }
          }
          context.onRefresh = this._playerRefresh
        }
        this.setData({ loadingContext: false, available: true, url: payload.url.trim(),
          poster: typeof payload.poster === 'string' ? payload.poster : '',
          title: typeof payload.title === 'string' ? payload.title : '' }, () => {
          if (this._playerAlive && this._playerVisible && this.isPlayerContextCurrent()) this.acquirePlayerActivity()
        })
      }
      if (this._playerChannel && typeof this._playerChannel.on === 'function') {
        this._contextWait = setTimer(() => this.finishMissingContext(), CONTEXT_WAIT_MS)
        this._playerChannel.on(PLAYER_EVENT, this._playerReceiver)
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
      if (this._requiresPlayerReturn) { this.handleBack(); return }
      if (!this.isPlayerContextCurrent()) return
      this.acquirePlayerActivity()
      // 自动播放可能早于首次 onShow；只补偿这次首次播放，不在从后台返回时擅自恢复声音。
      if (this._resumeInitialPlayback) {
        this._resumeInitialPlayback = false
        this.callPlayerVideo('play')
      }
    },
    onHide() {
      if (!this._playerAlive) return
      this._playerVisible = false
      this.releasePlayerActivity()
      this.callPlayerVideo('pause')
    },
    onUnload() {
      this._playerAlive = false
      this._playerVisible = false
      this.clearContextWait()
      this.clearFullscreenWait()
      this.releasePlayerActivity()
      // 原生导航也会卸载页面；退出全屏和停止播放必须分别兜底，不能让一个异常阻断其余清理。
      this.callPlayerVideo('exitFullScreen')
      this.callPlayerVideo('stop')
      if (this._playerChannel && typeof this._playerChannel.off === 'function') {
        this._playerChannel.off(PLAYER_EVENT, this._playerReceiver)
      }
      const context = this._playerPayload && this._playerPayload.browserContext
      if (context && context.onRefresh === this._playerRefresh) context.onRefresh = this._sourceRefresh
      this._playerPayload = null
      this._playerVideo = null
      this._playerChannel = null
      this._playerReceiver = null
    },
    isPlayerContextCurrent() {
      if (!this._playerAlive || this._requiresPlayerReturn || this._pendingBack) return false
      if (this._playerPayload && !validPayload(this._playerPayload)) {
        this.expirePlayerContext()
        return false
      }
      return Boolean(this._playerPayload)
    },
    acquirePlayerActivity() {
      const context = this._playerPayload && this._playerPayload.browserContext
      if (!this._playerVisible || !this.data.available || !context || typeof context.show !== 'function') return
      context.show(this)
    },
    releasePlayerActivity() {
      const context = this._playerPayload && this._playerPayload.browserContext
      if (context && typeof context.hide === 'function') context.hide(this)
    },
    expirePlayerContext() {
      if (!this._playerAlive || this._requiresPlayerReturn) return
      this._requiresPlayerReturn = true
      this.releasePlayerActivity()
      // 失效时先暂停，保持原生 video 挂载，等待 fullscreenchange(false) 再返回来源页。
      if (this._playerVisible) this.handleBack()
      else this.callPlayerVideo('pause')
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
      if (!this._playerVisible || !this.data.available || this.data.fullscreen || this._fullscreenRequested
        || !this.isPlayerContextCurrent()) return
      this._fullscreenRequested = true
      // VideoContext 的全屏 API 没有失败回调，若始终没有进入事件，需要有界恢复返回按钮。
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
      if (!this._playerVisible || !this.data.available || !this.isPlayerContextCurrent()) return
      this.callPlayerVideo(this.data.playing ? 'pause' : 'play')
    },
    handleVideoPlay() {
      if (!this._playerAlive) return
      if (!this._playerVisible) {
        if (!this._playerShown && this.isPlayerContextCurrent()) this._resumeInitialPlayback = true
        this.callPlayerVideo('pause')
        return
      }
      if (!this.isPlayerContextCurrent()) { this.callPlayerVideo('pause'); return }
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

module.exports = { createVideoPlayerPage }

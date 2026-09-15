const DEFAULT_AUDIO_COVER_URL = 'https://cdn2.we-folio.dingchenyong.top/system/default-audio-cover-v1-200kb.png'
const AUDIO_STYLE_OPTIONS = [
  { value: 'DISC', label: '轻量唱片' },
  { value: 'SLEEVE', label: '封套抽盘' },
  { value: 'MINI_PLAYER', label: '迷你播放器' }
]

function normalizeAudioResource(raw) {
  const source = raw && typeof raw === 'object' ? raw : {}
  return {
    workId: Number(source.workId || source.id) || null,
    enabled: source.enabled === true,
    displayStyle: AUDIO_STYLE_OPTIONS.some((item) => item.value === source.displayStyle) ? source.displayStyle : 'DISC',
    title: source.title || '背景音频',
    coverUrl: source.coverUrl || DEFAULT_AUDIO_COVER_URL,
    mediaUrl: source.mediaUrl || '',
    durationMs: Number(source.durationMs) || 0
  }
}

// 一个页面只持有一个实例；换音频时销毁旧实例，旧事件不影响新资源。
function createPortfolioAudio({ wxApi, onPlaying = () => {}, beforePlay = () => {}, onError = () => {} }) {
  let resource = normalizeAudioResource(), context = null
  let visible = true, disposed = false, wanted = false, attemptedAuto = false
  function stopContext() {
    const old = context
    context = null
    if (old) old.destroy()
    wanted = false
    if (!disposed) onPlaying(false)
  }
  function pause() {
    attemptedAuto = true
    wanted = false
    if (context) context.pause()
    if (!disposed) onPlaying(false)
  }
  function play() {
    if (disposed || !visible || !resource.mediaUrl) return
    beforePlay()
    wanted = true
    try {
      if (!context) {
        const current = wxApi.createInnerAudioContext()
        context = current
        current.onPlay(() => {
          if (disposed || current !== context) return
          if (!visible || !wanted) { current.pause(); return }
          onPlaying(true)
        })
        const stopped = () => {
          if (disposed || current !== context) return
          wanted = false
          onPlaying(false)
        }
        current.onPause(stopped)
        current.onStop(stopped)
        current.onEnded(stopped)
        current.onError(() => { if (current !== context || disposed) return; stopped(); onError() })
        current.src = resource.mediaUrl
      }
      context.play()
    } catch (error) {
      wanted = false
      onPlaying(false)
      onError()
    }
  }
  return {
    setResource(raw, autoplay = false) {
      if (disposed) return
      const next = normalizeAudioResource(raw)
      if (next.mediaUrl !== resource.mediaUrl || next.workId !== resource.workId) stopContext()
      else if (resource.enabled && !next.enabled) pause()
      resource = next
      if (autoplay && !attemptedAuto) {
        attemptedAuto = true
        if (resource.enabled && resource.mediaUrl && visible) play()
      }
    },
    toggle() { attemptedAuto = true; if (wanted) pause(); else play() },
    pause,
    hide() { visible = false; pause() },
    show() { if (!disposed) visible = true },
    destroy() { disposed = true; stopContext() }
  }
}

// 六个页面复用薄的音频操作；生命周期仍在页面原有入口显式连接。
const portfolioAudioPageMethods = {
  ensureBackgroundAudio() {
    if (!this.backgroundAudioPlayer && !this.backgroundAudioDisposed) {
      this.backgroundAudioPlayer = createPortfolioAudio({
        wxApi: wx,
        onPlaying: (playing) => this.setData({ backgroundAudioPlaying: playing }),
        beforePlay: () => {
          if (this.stopActiveSingleWorkVideo) this.stopActiveSingleWorkVideo()
          if (this.stopSingleWorkVideos) this.stopSingleWorkVideos()
          if (this.clearVideoPreview) this.clearVideoPreview()
        },
        onError: () => wx.showToast && wx.showToast({ title: '音频播放失败，请点击重试', icon: 'none' })
      })
    }
    return this.backgroundAudioPlayer
  },
  syncBackgroundAudio(raw, autoplay = false) {
    if (this.backgroundAudioDisposed) return
    const resource = normalizeAudioResource(raw)
    this.setData({ backgroundAudioResource: resource })
    this.ensureBackgroundAudio().setResource(resource, autoplay)
  },
  handleToggleBackgroundAudio() {
    if (this.backgroundAudioDisposed) return
    this.ensureBackgroundAudio().toggle()
  },
  pauseBackgroundAudio() {
    if (!this.backgroundAudioDisposed) this.ensureBackgroundAudio().pause()
  },
  hideBackgroundAudio() {
    if (!this.backgroundAudioDisposed) this.ensureBackgroundAudio().hide()
  },
  showBackgroundAudio() {
    if (!this.backgroundAudioDisposed) this.ensureBackgroundAudio().show()
  },
  destroyBackgroundAudio() {
    this.backgroundAudioDisposed = true
    if (this.backgroundAudioPlayer) this.backgroundAudioPlayer.destroy()
  },
  positionBackgroundAudio() {
    const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : {}
    const capsule = wx.getMenuButtonBoundingClientRect ? wx.getMenuButtonBoundingClientRect() : {}
    this.setData({ backgroundAudioTop: Math.max(Number(capsule.bottom) || 0, (Number(windowInfo.statusBarHeight) || 24) + 44) + 8 })
  }
}

module.exports = { DEFAULT_AUDIO_COVER_URL, AUDIO_STYLE_OPTIONS, normalizeAudioResource, createPortfolioAudio, portfolioAudioPageMethods }

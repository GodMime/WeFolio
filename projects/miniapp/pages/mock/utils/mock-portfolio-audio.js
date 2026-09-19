const MOCK_DEFAULT_AUDIO_WORK_ID = 108
const MOCK_AUDIO_MEDIA_TYPE = 'AUDIO'
const MOCK_AUDIO_DISPLAY_STYLES = ['DISC', 'SLEEVE', 'MINI_PLAYER']
const MOCK_AUDIO_UNAVAILABLE = '音频暂不可播放，请重试'

/** 草稿仅保留作品引用和外观，关闭背景音乐不清除用户选择。 */
function normalizeMockBackgroundAudio(input, works) {
  const source = input && typeof input === 'object' ? input : {}
  const rawId = source.workId === undefined ? MOCK_DEFAULT_AUDIO_WORK_ID : source.workId
  let workId = rawId === null || rawId === '' ? null : Number(rawId)
  if (!Number.isSafeInteger(workId) || workId <= 0) workId = null
  if (Array.isArray(works) && !works.some(work => Number(work.id || work.workId) === workId && work.mediaType === MOCK_AUDIO_MEDIA_TYPE)) workId = null
  return {
    enabled: source.enabled === true && workId !== null,
    workId,
    displayStyle: MOCK_AUDIO_DISPLAY_STYLES.includes(source.displayStyle) ? source.displayStyle : MOCK_AUDIO_DISPLAY_STYLES[0]
  }
}

/** 只从传入的本地作品库解析播放资源，不信任草稿中的媒体地址。 */
function resolveMockBackgroundAudio(input, works) {
  const config = normalizeMockBackgroundAudio(input, works)
  if (!config.enabled || !Array.isArray(works)) return null
  const work = works.find(item => Number(item.id || item.workId) === config.workId && item.mediaType === MOCK_AUDIO_MEDIA_TYPE)
  if (!work || !work.mediaUrl) return null
  return { workId: config.workId, mediaUrl: work.mediaUrl, enabled: true, title: work.title || '', coverUrl: work.coverUrl || '', displayStyle: config.displayStyle, loop: true }
}

/** 页面独占一个音频上下文，实际播放状态只由平台回调确认。 */
function createMockAudioController(options = {}) {
  const wxApi = options.wxApi || (typeof wx !== 'undefined' ? wx : {})
  let context = null
  let resource = null
  let generation = 0
  let playing = false
  let requested = false
  let hidden = false
  let destroyed = false

  function publish(value) {
    if (playing === value) return
    playing = value
    if (typeof options.onPlaying === 'function') options.onPlaying(value)
  }

  function release() {
    const previous = context
    context = null
    generation += 1
    requested = false
    if (previous) {
      try { previous.pause() } catch (_) { /* 销毁仍需继续，避免平台暂停异常留下实例。 */ }
      try { previous.destroy() } catch (_) { /* 页面卸载不因平台已释放实例而中断。 */ }
    }
    publish(false)
  }

  function fail(error) {
    release()
    if (typeof options.onError === 'function') options.onError(error || new Error(MOCK_AUDIO_UNAVAILABLE))
  }

  function createContext() {
    const current = wxApi.createInnerAudioContext()
    context = current
    const token = ++generation
    const active = () => !destroyed && context === current && generation === token
    current.onPlay(() => {
      if (!active()) return
      if (!requested || hidden || !resource || !resource.enabled) {
        try { current.pause() } catch (error) { fail(error) }
        return
      }
      publish(true)
    })
    const onStopped = () => {
      if (!active()) return
      requested = false
      publish(false)
    }
    current.onPause(onStopped)
    current.onStop(onStopped)
    current.onEnded(onStopped)
    current.onError(error => { if (active()) fail(error) })
    current.autoplay = false
    current.loop = resource.loop === undefined ? options.loop === true : resource.loop === true
    current.src = resource.mediaUrl
    return active() ? current : null
  }

  function pause() {
    requested = false
    if (context) {
      try { context.pause() } catch (error) { fail(error) }
    }
    publish(false)
  }

  function play() {
    if (destroyed || hidden || !resource || !resource.enabled || !resource.mediaUrl) return false
    if (requested || playing) return true
    try {
      if (typeof options.beforePlay === 'function') options.beforePlay()
      const current = context || createContext()
      if (!current) return false
      requested = true
      current.play()
      return context === current && requested
    } catch (error) {
      fail(error)
      return false
    }
  }

  return {
    setResource(value) {
      if (destroyed) return
      const next = value && typeof value.mediaUrl === 'string' && value.mediaUrl
        ? { workId: value.workId, mediaUrl: value.mediaUrl, enabled: value.enabled !== false, loop: value.loop }
        : null
      if (!next || !resource || resource.workId !== next.workId || resource.mediaUrl !== next.mediaUrl || resource.loop !== next.loop) release()
      resource = next
      if (!resource || !resource.enabled) pause()
    },
    play,
    pause,
    toggle() { if (requested || playing) pause(); else play() },
    hide() { hidden = true; pause() },
    show() { if (!destroyed) hidden = false },
    destroy() { if (destroyed) return; destroyed = true; release(); resource = null },
    getState() { return { playing, pending: requested && !playing, hidden, destroyed } }
  }
}

module.exports = { normalizeMockBackgroundAudio, resolveMockBackgroundAudio, createMockAudioController }

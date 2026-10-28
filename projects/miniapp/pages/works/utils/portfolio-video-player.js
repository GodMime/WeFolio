const VIDEO_PLAYER_EVENT = 'portfolioVideoPlayer'
const VIDEO_OPEN_FAILED_MESSAGE = '视频打开失败，请重试'
const TOAST_ICON_NONE = 'none'

// 资源与访问上下文只通过本次导航的内存通道传递，不生成可分享的播放地址。
function openVideoPlayer({ url, poster = '', title = '', route, browserContext = null } = {}, wxApi) {
  const showError = () => {
    if (wxApi && typeof wxApi.showToast === 'function') {
      wxApi.showToast({ title: VIDEO_OPEN_FAILED_MESSAGE, icon: TOAST_ICON_NONE })
    }
  }
  if (typeof url !== 'string' || !url.trim() || typeof route !== 'string' || !route.trim() ||
      !wxApi || typeof wxApi.navigateTo !== 'function') {
    showError()
    return Promise.resolve(false)
  }
  const payload = { url, poster, title, browserContext,
    contextId: browserContext && browserContext.idempotencyKey || '' }
  return new Promise((resolve) => {
    const fail = (error) => {
      if (!/cancel/i.test(error && error.errMsg || '')) showError()
      resolve(false)
    }
    try {
      wxApi.navigateTo({
        url: route,
        success(result) {
          const channel = result && result.eventChannel
          if (!channel || typeof channel.emit !== 'function') { fail(); return }
          try {
            channel.emit(VIDEO_PLAYER_EVENT, payload)
            resolve(true)
          } catch (error) {
            fail(error)
          }
        },
        fail
      })
    } catch (error) {
      fail(error)
    }
  })
}

module.exports = { VIDEO_PLAYER_EVENT, openVideoPlayer }

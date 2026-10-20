const VIDEO_PLAYER_EVENT = 'mockVideoPlayer'
const VIDEO_PLAYER_ROUTE = '/pages/mock/video-player/video-player'
const VIDEO_OPEN_FAILED_MESSAGE = '视频打开失败，请重试'
const TOAST_ICON_NONE = 'none'

// 体验素材仅通过本次导航的内存通道传递，保持在 mock 分包内播放。
function openMockVideoPlayer({ url, poster = '', title = '' } = {}, wxApi) {
  const showError = () => {
    if (wxApi && typeof wxApi.showToast === 'function') {
      wxApi.showToast({ title: VIDEO_OPEN_FAILED_MESSAGE, icon: TOAST_ICON_NONE })
    }
  }
  if (typeof url !== 'string' || !url.trim() || !wxApi || typeof wxApi.navigateTo !== 'function') {
    showError()
    return Promise.resolve(false)
  }
  const payload = { url, poster, title }
  return new Promise((resolve) => {
    const fail = (error) => {
      if (!/cancel/i.test(error && error.errMsg || '')) showError()
      resolve(false)
    }
    try {
      wxApi.navigateTo({
        url: VIDEO_PLAYER_ROUTE,
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

module.exports = { VIDEO_PLAYER_EVENT, openMockVideoPlayer }

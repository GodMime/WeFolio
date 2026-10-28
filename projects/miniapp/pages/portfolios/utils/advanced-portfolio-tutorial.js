const TUTORIAL_ROUTE = '/pages/portfolios/tutorial/advanced-portfolio-tutorial'
const TUTORIAL_VIDEO_URL = 'https://cdn2.we-folio.dingchenyong.top/system/wefolio-ai-portfolio-v11.mp4'
const TUTORIAL_TITLE = '高级作品集使用教程'
const TUTORIAL_CODE_URL = 'https://cdn2.we-folio.dingchenyong.top/system/yingqi-folio-teach-vedio.png'
const VIDEO_ID = 'advancedPortfolioTutorial'
const SHARE_MENUS = ['shareAppMessage', 'shareTimeline']

/** 公开教程使用固定资源；扫码和转发均不依赖创建流程或作品集访问身份。 */
function createAdvancedPortfolioTutorialPage({ wxApi } = {}) {
  return {
    data: { url: TUTORIAL_VIDEO_URL, title: TUTORIAL_TITLE, videoError: false, videoMounted: true,
      codeUrl: TUTORIAL_CODE_URL, codeVisible: false, codeLoading: false, codeError: false, codeImageMounted: true },

    onLoad() {
      this._alive = true
      this._visible = true
      if (wxApi.showShareMenu) wxApi.showShareMenu({ menus: SHARE_MENUS })
    },
    onShow() { this._visible = true },
    onHide() {
      this._visible = false
      this.callVideo('pause')
      this.handleCloseCode()
    },
    onUnload() {
      this._alive = false
      this._visible = false
      this.callVideo('exitFullScreen')
      this.callVideo('stop')
      this._video = null
    },
    callVideo(method) {
      try {
        if (!this._video && wxApi.createVideoContext) this._video = wxApi.createVideoContext(VIDEO_ID, this)
        if (this._video && typeof this._video[method] === 'function') this._video[method]()
      } catch (_) {
        // 原生播放器尚未创建或已销毁时，不阻断页面分享和离开。
      }
    },
    handleVideoPlay() {
      if (!this._alive || !this._visible || this.data.codeVisible) this.callVideo('pause')
    },
    handleVideoError() {
      if (this._alive) this.setData({ videoError: true })
    },
    handleRetryVideo() {
      if (!this._alive || !this._visible) return
      // 重新挂载原生组件，使网络错误后也能重新加载同一教程地址。
      this.setData({ videoMounted: false }, () => {
        if (!this._alive) return
        this._video = null
        this.setData({ videoMounted: true, videoError: false })
      })
    },
    onShareAppMessage() {
      this.callVideo('pause')
      return { title: TUTORIAL_TITLE, path: TUTORIAL_ROUTE }
    },
    onShareTimeline() {
      this.callVideo('pause')
      return { title: TUTORIAL_TITLE, query: '' }
    },
    handleShowCode() {
      if (!this._alive || !this._visible || this.data.codeVisible) return
      this.callVideo('pause')
      this.setData({ codeVisible: true, codeLoading: true, codeError: false, codeImageMounted: true })
    },
    handleCloseCode() {
      if (this._alive && this.data.codeVisible) this.setData({ codeVisible: false, codeLoading: false })
    },
    // 卡片点击和滑动只在弹层内处理，避免触发遮罩关闭或底层视频操作。
    handleCodeCardTap() {},
    handleCodeLoad() {
      if (this._alive && this._visible && this.data.codeVisible) this.setData({ codeLoading: false, codeError: false })
    },
    handleCodeError() {
      if (this._alive && this._visible && this.data.codeVisible) this.setData({ codeLoading: false, codeError: true })
    },
    handleRetryCode() {
      if (!this._alive || !this._visible || !this.data.codeVisible || !this.data.codeError) return
      // 重新挂载图片，网络恢复后可直接重载同一 CDN 资源。
      this.setData({ codeImageMounted: false }, () => {
        if (!this._alive || !this._visible || !this.data.codeVisible) return
        this.setData({ codeImageMounted: true, codeLoading: true, codeError: false })
      })
    }
  }
}

module.exports = { createAdvancedPortfolioTutorialPage }

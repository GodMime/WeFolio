const CLICK_ICON_URL = 'https://cdn2.we-folio.dingchenyong.top/system/click-tap-icon.gif'
const CLICK_ICON_DARK_URL = 'https://cdn2.we-folio.dingchenyong.top/system/click-tap-icon-dark.gif'
const CLICK_ICON_POSITION_STATES = {
  OVERLAY: { inside: true, className: 'overlay-bottom-right' },
  OVERLAY_BOTTOM_CENTER: { inside: true, className: 'overlay-bottom-center' },
  OVERLAY_CENTER: { inside: true, className: 'overlay-center' },
  BELOW: { inside: false, className: 'below' }
}

Component({
  properties: {
    config: { type: Object, value: {} },
    work: { type: Object, value: null },
    themeMode: { type: String, value: 'light' }
  },
  data: {
    imageFailed: false,
    imageUrl: '',
    iconLoadFailed: false,
    clickIconInside: true,
    clickIconClass: 'overlay-bottom-right',
    clickIconUrl: CLICK_ICON_URL
  },
  observers: {
    work(work) {
      const imageUrl = work && (work.mediaType === 'ANIMATION' ? work.mediaUrl : (work.previewUrl || work.mediaUrl)) || ''
      this.setData({ imageFailed: false, imageUrl })
    },
    'config.showClickIcon, config.iconPosition, themeMode'(showClickIcon, iconPosition, themeMode) {
      const positionState = CLICK_ICON_POSITION_STATES[iconPosition] || CLICK_ICON_POSITION_STATES.OVERLAY
      this.setData({
        iconLoadFailed: false,
        clickIconInside: positionState.inside,
        clickIconClass: positionState.className,
        clickIconUrl: themeMode === 'dark' ? CLICK_ICON_DARK_URL : CLICK_ICON_URL
      })
    }
  },
  methods: {
    handleTap() { this.triggerEvent('hyperlink', { config: Object.assign({}, this.data.config) }) },
    handleImageError() { this.setData({ imageFailed: true }) },
    handleRetry() { this.setData({ imageFailed: false }) },
    handleIconError() { this.setData({ iconLoadFailed: true }) }
  }
})

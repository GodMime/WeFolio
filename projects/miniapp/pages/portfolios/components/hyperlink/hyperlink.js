const CLICK_ICON_URL = 'https://cdn2.we-folio.dingchenyong.top/system/click-tap-icon.gif'
const CLICK_ICON_DARK_URL = 'https://cdn2.we-folio.dingchenyong.top/system/click-tap-icon-dark.gif'
const CLICK_ICON_POSITION_STATES = {
  OVERLAY: { inside: true, className: 'overlay-bottom-right' },
  OVERLAY_BOTTOM_CENTER: { inside: true, className: 'overlay-bottom-center' },
  OVERLAY_CENTER: { inside: true, className: 'overlay-center' },
  BELOW: { inside: false, className: 'below' }
}

Component({
  options: {
    styleIsolation: 'isolated'
  },

  properties: {
    componentKey: {
      type: String,
      value: ''
    },
    hyperlink: {
      type: Object,
      value: null
    },
    repairMode: {
      type: Boolean,
      value: false
    },
    themeMode: {
      type: String,
      value: 'light'
    }
  },

  data: {
    animationLoadFailed: false,
    iconLoadFailed: false,
    clickIconInside: true,
    clickIconClass: 'overlay-bottom-right',
    clickIconUrl: CLICK_ICON_URL
  },

  observers: {
    'hyperlink.displayWork.previewUrl'() {
      this.setData({ animationLoadFailed: false })
    },
    'hyperlink.showClickIcon, hyperlink.iconPosition, themeMode'(showClickIcon, iconPosition, themeMode) {
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
    handleHyperlinkTap() {
      const hyperlink = this.data.hyperlink || {}
      this.triggerEvent('hyperlinktap', {
        componentKey: this.data.componentKey || '',
        actionType: hyperlink.actionType || '',
        targetPortfolioId: Number(hyperlink.targetPortfolioId) || 0,
        targetTitle: hyperlink.targetTitle || '',
        targetAvailable: hyperlink.targetAvailable === true,
        targetShareCode: hyperlink.targetShareCode || '',
        externalContent: typeof hyperlink.externalContent === 'string' ? hyperlink.externalContent : '',
        promptText: typeof hyperlink.promptText === 'string' ? hyperlink.promptText : ''
      })
    },

    handleAnimationLoadError() {
      this.setData({ animationLoadFailed: true })
    },

    handleIconLoadError() {
      this.setData({ iconLoadFailed: true })
    }
  }
})

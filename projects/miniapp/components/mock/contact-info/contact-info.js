const AUTO_COLOR = 'AUTO'
const DEFAULT_BORDER_COLOR = '#D7DADD'
const DARK_BORDER_COLOR = '#45464A'
const COLOR_PATTERN = /^#[\da-f]{6}$/i

Component({
  properties: {
    config: { type: Object, value: {} },
    copiedField: { type: String, value: '' },
    themeMode: { type: String, value: 'light' }
  },
  data: { spacingStyle: '', borderStyle: '' },
  observers: {
    'config, themeMode'(config, themeMode) {
      const safe = config || {}
      const margin = value => Number.isInteger(value) && value >= 0 && value <= 96 ? value : 0
      const width = Number.isInteger(safe.contactBorderWidthRpx) && safe.contactBorderWidthRpx >= 1 && safe.contactBorderWidthRpx <= 12 ? safe.contactBorderWidthRpx : 1
      const color = safe.contactBorderColor !== AUTO_COLOR && COLOR_PATTERN.test(safe.contactBorderColor || '') ? safe.contactBorderColor : (themeMode === 'dark' ? DARK_BORDER_COLOR : DEFAULT_BORDER_COLOR)
      this.setData({
        spacingStyle: safe.contactBorder ? `padding:${margin(safe.verticalMarginRpx)}rpx ${margin(safe.horizontalMarginRpx)}rpx;` : '',
        borderStyle: safe.contactBorder ? `border:${width}rpx solid ${color};` : ''
      })
    }
  },
  methods: {
    handleCopy(event) {
      const field = event.currentTarget.dataset.field
      const value = (this.data.config || {})[field]
      if (value) this.triggerEvent('copy', { field, value })
    }
  }
})

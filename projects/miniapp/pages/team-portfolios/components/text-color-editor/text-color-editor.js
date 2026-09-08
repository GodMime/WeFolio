const { hexToHsv, hsvToHex } = require('../../utils/team-portfolio-color')
const AUTO_COLOR = 'AUTO'
const WHITE_COLOR = '#FFFFFF'
const BLACK_COLOR = '#212529'
const HEX_PATTERN = /^#[0-9A-F]{6}$/
const CHANGE_EVENT = 'change'
const INVALID_HEX_MESSAGE = '请输入正确的颜色值'
const ENTER_DELAY = 16
const TRANSITION_DURATION = 200
const PAD_SELECTOR = '.text-color-pad'
const PRESETS = [
  { color: AUTO_COLOR, label: '跟随主题' },
  { color: WHITE_COLOR, label: '白色' },
  { color: BLACK_COLOR, label: '黑色' }
]

/** 外部配置仅接受主题或完整十六进制颜色，防止无效样式进入预览。 */
function normalizeColor(value) {
  const color = String(value || '').trim().toUpperCase()
  return HEX_PATTERN.test(color) ? color : AUTO_COLOR
}

/** 色板与输入使用独立状态，非法输入不会污染最后一次有效预览。 */
function pickerState(hsv) {
  const colorHsv = {
    // HEX 转出的色相可能落在 359 到 360 之间，保留小数才能无损往返。
    hue: ((Number(hsv.hue) || 0) % 360 + 360) % 360,
    saturation: Math.min(1, Math.max(0, Number(hsv.saturation) || 0)),
    value: Math.min(1, Math.max(0, Number(hsv.value) || 0))
  }
  const draftColor = hsvToHex(colorHsv)
  return {
    colorHsv, draftColor, hexInput: draftColor, errorMessage: '',
    hueColor: hsvToHex({ hue: colorHsv.hue, saturation: 1, value: 1 }),
    padDotStyle: 'left: ' + colorHsv.saturation * 100 + '%; top: ' + (1 - colorHsv.value) * 100 + '%;',
    huePosition: Math.min(100, colorHsv.hue / 3.59)
  }
}

Component({
  properties: {
    color: { type: String, value: AUTO_COLOR },
    active: { type: Boolean, value: true }
  },
  data: {
    presets: PRESETS, selectedColor: AUTO_COLOR, customSelected: false,
    pickerMounted: false, pickerVisible: false,
    ...pickerState(hexToHsv(BLACK_COLOR))
  },
  observers: {
    color(value) {
      const color = normalizeColor(value)
      if (this.data.pickerMounted && color !== this.data.selectedColor) this.resetPicker()
      this.updateSelection(color)
    },
    active(value) { if (!value) this.resetPicker() }
  },
  lifetimes: {
    attached() {
      this._detached = false
      this.updateSelection(normalizeColor(this.properties.color))
      if (!this.properties.active) this.resetPicker()
    },
    detached() {
      this._detached = true
      this.clearPickerAsync()
    }
  },
  methods: {
    /** 选中态与属性回传同步，不让正在试色的草稿影响外部颜色。 */
    updateSelection(color) {
      this.setData({ selectedColor: color, customSelected: !PRESETS.some(item => item.color === color) })
    },
    isPickerInteractive() {
      return !this._detached && this.properties.active && this.data.pickerVisible
    },
    /** 每个弹层会话及手势都有版本号，平台晚到的布局查询必须失效。 */
    clearPickerAsync() {
      clearTimeout(this._pickerTimer)
      this._pickerTimer = null
      this._pickerClosing = false
      this._pickerRevision = (this._pickerRevision || 0) + 1
      this._gestureRevision = (this._gestureRevision || 0) + 1
    },
    resetPicker() {
      this.clearPickerAsync()
      if (this._detached) return
      const color = normalizeColor(this.properties.color)
      this.setData({
        pickerMounted: false, pickerVisible: false,
        ...pickerState(hexToHsv(color === AUTO_COLOR ? BLACK_COLOR : color))
      })
    },
    handlePreset(event) {
      if (this._detached || !this.properties.active) return
      const color = event.currentTarget.dataset.color
      if (!PRESETS.some(item => item.color === color)) return
      this.resetPicker()
      this.updateSelection(color)
      this.triggerEvent(CHANGE_EVENT, { color })
    },
    handleOpenCustom() {
      if (this._detached || !this.properties.active) return
      this.clearPickerAsync()
      const revision = this._pickerRevision
      const color = this.data.selectedColor === AUTO_COLOR ? BLACK_COLOR : this.data.selectedColor
      this.setData({
        pickerMounted: true, pickerVisible: false, ...pickerState(hexToHsv(color))
      }, () => {
        if (this._detached || !this.properties.active || revision !== this._pickerRevision) return
        this._pickerTimer = setTimeout(() => {
          if (this._detached || !this.properties.active || revision !== this._pickerRevision) return
          this._pickerTimer = null
          this.setData({ pickerVisible: true })
        }, ENTER_DELAY)
      })
    },
    /** 退场期间保持内容，完成动画后再隐藏；父层关闭时由 active 立即清理。 */
    handleCancelCustom() {
      if (this._detached || !this.properties.active || !this.data.pickerMounted || this._pickerClosing) return
      this.clearPickerAsync()
      this._pickerClosing = true
      const revision = this._pickerRevision
      this.setData({ pickerVisible: false })
      this._pickerTimer = setTimeout(() => {
        if (this._detached || revision !== this._pickerRevision) return
        this._pickerTimer = null
        this.setData({ pickerMounted: false })
        this._pickerClosing = false
      }, TRANSITION_DURATION)
    },
    handleConfirmCustom() {
      if (!this.isPickerInteractive()) return
      if (!HEX_PATTERN.test(this.data.hexInput)) {
        this.setData({ errorMessage: INVALID_HEX_MESSAGE })
        return
      }
      const color = this.data.hexInput
      this.handleCancelCustom()
      this.updateSelection(color)
      this.triggerEvent(CHANGE_EVENT, { color })
    },
    handleHueChange(event) {
      if (!this.isPickerInteractive()) return
      this._gestureRevision = (this._gestureRevision || 0) + 1
      this.setData(pickerState(Object.assign({}, this.data.colorHsv, { hue: event.detail.value })))
    },
    handleColorPadTouch(event) {
      if (!this.isPickerInteractive()) return
      const touch = event && event.touches && event.touches[0]
      if (!touch || !Number.isFinite(Number(touch.clientX)) || !Number.isFinite(Number(touch.clientY))) return
      const x = Number(touch.clientX)
      const y = Number(touch.clientY)
      const revision = this._pickerRevision
      const gesture = this._gestureRevision = (this._gestureRevision || 0) + 1
      this.createSelectorQuery().select(PAD_SELECTOR).boundingClientRect(rect => {
        if (!this.isPickerInteractive() || revision !== this._pickerRevision || gesture !== this._gestureRevision) return
        if (!rect || !(rect.width > 0) || !(rect.height > 0)) return
        this.setData(pickerState(Object.assign({}, this.data.colorHsv, {
          saturation: (x - rect.left) / rect.width,
          value: 1 - (y - rect.top) / rect.height
        })))
      }).exec()
    },
    handleHexInput(event) {
      if (!this.isPickerInteractive()) return
      this._gestureRevision = (this._gestureRevision || 0) + 1
      const hexInput = String(event.detail.value || '').trim().toUpperCase()
      if (HEX_PATTERN.test(hexInput)) {
        this.setData(pickerState(hexToHsv(hexInput)))
      } else {
        this.setData({ hexInput, errorMessage: INVALID_HEX_MESSAGE })
      }
    },
    handleHexBlur() {
      if (this.isPickerInteractive() && !HEX_PATTERN.test(this.data.hexInput)) {
        this.setData({ errorMessage: INVALID_HEX_MESSAGE })
      }
    },
    noop() {}
  }
})

const DEFAULT_COVER = 'https://cdn2.we-folio.dingchenyong.top/system/default-audio-cover-v1-200kb.png'
const DRAG_THRESHOLD = 6
const EDGE_GAP = 12
// 与三款控件的 WXSS 尺寸保持一致，拖动时保证整个控件留在可视区域。
const CONTROL_SIZES = {
  DISC: { width: 46, height: 46 },
  SLEEVE: { width: 68, height: 48 },
  MINI_PLAYER: { width: 180, height: 48 }
}

function findTouch(touches, identifier) {
  const touch = (touches || []).find((item) => identifier === undefined || item.identifier === identifier)
  return touch && Number.isFinite(touch.clientX) && Number.isFinite(touch.clientY) ? touch : null
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

Component({
  properties: {
    displayStyle: { type: String, value: 'DISC' },
    title: { type: String, value: '背景音频' },
    playing: { type: Boolean, value: false },
    sample: { type: Boolean, value: false },
    draggable: { type: Boolean, value: false },
    anchorTop: { type: Number, value: 0 },
    coverUrl: {
      type: String, value: '',
      observer(value) { this.setData({ coverSource: value || DEFAULT_COVER, coverFailed: false }) }
    }
  },
  data: { coverSource: DEFAULT_COVER, coverFailed: false, offsetX: 0, offsetY: 0, dragging: false },
  methods: {
    handleTap() { if (!this.data.sample && !this._suppressTap) this.triggerEvent('toggle') },
    handleTouchStart(event) {
      // 第二根手指落下也会触发 touchstart，先结束当前拖动，避免悬停在半路。
      if (this._dragTouch) { this.handleTouchCancel(); return }
      this._suppressTap = false
      this._dragTouch = null
      if (!this.data.draggable || this.data.sample || !event.touches || event.touches.length !== 1) return
      const touch = findTouch(event.touches)
      if (!touch) return
      const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync ? wx.getSystemInfoSync() : {}
      const width = Number(windowInfo.windowWidth)
      const height = Number(windowInfo.windowHeight)
      if (!(width > 0 && height > 0)) return
      const size = CONTROL_SIZES[this.data.displayStyle] || CONTROL_SIZES.DISC
      const safeBottom = windowInfo.safeArea && Number(windowInfo.safeArea.bottom)
      const bottom = safeBottom > 0 ? Math.min(height, safeBottom - (Number(windowInfo.screenTop) || 0)) : height
      this._dragTouch = {
        identifier: touch.identifier,
        x: touch.clientX,
        y: touch.clientY,
        offsetY: this.data.offsetY,
        minX: Math.min(0, size.width + EDGE_GAP * 2 - width),
        maxY: Math.max(0, bottom - EDGE_GAP - size.height - this.data.anchorTop)
      }
    },
    handleTouchMove(event) {
      const drag = this._dragTouch
      if (!drag) return
      if (event.touches && event.touches.length > 1) { this.handleTouchCancel(); return }
      const touch = findTouch(event.touches, drag.identifier)
      if (!touch) return
      const deltaX = touch.clientX - drag.x
      const deltaY = touch.clientY - drag.y
      // 小幅手抖仍按点击处理；一旦开始拖动，即使回到原点也不切换播放。
      if (!this._suppressTap && deltaX * deltaX + deltaY * deltaY < DRAG_THRESHOLD * DRAG_THRESHOLD) return
      this._suppressTap = true
      this.setData({
        offsetX: clamp(deltaX, drag.minX, 0),
        offsetY: clamp(drag.offsetY + deltaY, 0, drag.maxY),
        dragging: true
      })
    },
    handleTouchEnd(event) {
      if (!this._dragTouch) return
      // 快速划动可能只在 touchend 提供最后坐标，结束前补齐位置。
      if (event.changedTouches && event.changedTouches.length) {
        const touch = findTouch(event.changedTouches, this._dragTouch.identifier)
        if (!touch) return
        this.handleTouchMove({ touches: [touch] })
      }
      this._dragTouch = null
      this.setData({ offsetX: 0, dragging: false })
    },
    handleTouchCancel() {
      if (!this._dragTouch) return
      this._dragTouch = null
      this._suppressTap = true
      this.setData({ offsetX: 0, dragging: false })
    },
    handleCoverError() {
      if (this.data.coverSource !== DEFAULT_COVER) this.setData({ coverSource: DEFAULT_COVER })
      else this.setData({ coverFailed: true })
    }
  }
})

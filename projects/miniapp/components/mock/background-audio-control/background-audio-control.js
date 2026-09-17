const DEFAULT_COVER = 'https://cdn2.we-folio.dingchenyong.top/system/default-audio-cover-v1-200kb.png'
const DISPLAY_DISC = 'DISC'
const DISPLAY_SLEEVE = 'SLEEVE'
const DISPLAY_MINI = 'MINI_PLAYER'
const CONTROL_SIZES = {
  DISC: { width: 46, height: 46 },
  SLEEVE: { width: 68, height: 48 },
  MINI_PLAYER: { width: 180, height: 48 }
}
const SCREEN_EDGE_PX = 12
const DRAG_THRESHOLD_PX = 6
const TOGGLE_EVENT = 'toggle'

function findTouch(touches, identifier) {
  const touch = (touches || []).find(item => identifier === undefined || item.identifier === identifier)
  return touch && Number.isFinite(touch.clientX) && Number.isFinite(touch.clientY) ? touch : null
}

/** Mock 控件沿用正式尺寸和交互，只向页面发出播放意图。 */
Component({
  properties: {
    displayStyle: { type: String, value: DISPLAY_DISC },
    playing: { type: Boolean, value: false },
    title: { type: String, value: '背景音频' },
    sample: { type: Boolean, value: false },
    draggable: { type: Boolean, value: true },
    coverUrl: {
      type: String,
      value: '',
      observer(value) { this.setData({ coverSource: value || DEFAULT_COVER, coverFailed: false }) }
    },
    floating: { type: Boolean, value: true },
    topInset: { type: Number, value: 96 },
    bottomInset: { type: Number, value: 96 }
  },
  data: {
    left: 0,
    top: 0,
    offsetX: 0,
    offsetY: 0,
    controlWidth: CONTROL_SIZES.DISC.width,
    controlHeight: CONTROL_SIZES.DISC.height,
    positionStyle: '',
    resolvedStyle: DISPLAY_DISC,
    coverSource: DEFAULT_COVER,
    coverFailed: false,
    dragging: false
  },
  observers: {
    'displayStyle, floating, topInset, bottomInset'() {
      if (this._attached) this.refreshPosition()
    }
  },
  lifetimes: {
    attached() {
      this._attached = true
      this.setData({ coverSource: this.data.coverUrl || DEFAULT_COVER, coverFailed: false })
      this.refreshPosition()
    },
    detached() {
      this._attached = false
      this._touch = null
      this._suppressTap = true
    }
  },
  pageLifetimes: {
    hide() { this.handleTouchCancel() },
    show() { if (this._attached) this.refreshPosition() },
    resize() { if (this._attached) this.refreshPosition() }
  },
  methods: {
    refreshPosition() {
      let info = { windowWidth: 375, windowHeight: 667 }
      try {
        if (typeof wx !== 'undefined' && wx.getWindowInfo) info = wx.getWindowInfo()
      } catch (_) { /* 平台窗口尚未就绪时使用保守初始边界。 */ }
      const width = Number(info.windowWidth) || 375
      const height = Number(info.windowHeight) || 667
      const resolvedStyle = [DISPLAY_DISC, DISPLAY_SLEEVE, DISPLAY_MINI].includes(this.data.displayStyle)
        ? this.data.displayStyle
        : DISPLAY_DISC
      const size = CONTROL_SIZES[resolvedStyle]
      const minLeft = Math.min(SCREEN_EDGE_PX, Math.max(0, width - size.width))
      const maxLeft = Math.max(minLeft, width - size.width - SCREEN_EDGE_PX)
      const safeBottom = info.safeArea ? Math.max(0, height - Number(info.safeArea.bottom || height)) : 0
      const maxTop = Math.max(0, height - size.height - Math.max(Number(this.data.bottomInset) || 0, safeBottom, SCREEN_EDGE_PX))
      const minTop = Math.min(maxTop, Math.max(SCREEN_EDGE_PX, Number(this.data.topInset) || 0))
      this._bounds = { minLeft, maxLeft, minTop, maxTop }
      this.setData({ resolvedStyle, controlWidth: size.width, controlHeight: size.height })
      this.setPosition(maxLeft, this._positioned ? this.data.top : height * 0.55)
      this._positioned = true
    },
    setPosition(left, top) {
      const bounds = this._bounds
      const nextLeft = Math.min(bounds.maxLeft, Math.max(bounds.minLeft, left))
      const nextTop = Math.min(bounds.maxTop, Math.max(bounds.minTop, top))
      const positionStyle = this.data.floating ? `left:${nextLeft}px;top:${nextTop}px;` : ''
      this.setData({ left: nextLeft, top: nextTop, offsetX: 0, offsetY: 0, positionStyle })
    },
    handleTouchStart(event) {
      if (!this._attached || this.data.sample || !this.data.draggable || !this.data.floating) return
      if (this._touch) { this.handleTouchCancel(); return }
      const touch = findTouch(event.touches)
      this._suppressTap = false
      if (!touch || !event.touches || event.touches.length !== 1) return
      this._touch = {
        identifier: touch.identifier,
        x: touch.clientX,
        y: touch.clientY,
        left: this.data.left,
        top: this.data.top,
        moved: false
      }
    },
    handleTouchMove(event) {
      const start = this._touch
      if (!start || this.data.sample || !this.data.draggable || !this.data.floating) return
      if (event.touches && event.touches.length > 1) { this.handleTouchCancel(); return }
      const touch = findTouch(event.touches, start.identifier)
      if (!touch) return
      const dx = touch.clientX - start.x
      const dy = touch.clientY - start.y
      if (!start.moved && dx * dx + dy * dy < DRAG_THRESHOLD_PX * DRAG_THRESHOLD_PX) return
      start.moved = true
      this._suppressTap = true
      this.setData({ dragging: true, offsetX: dx, offsetY: dy })
      this.setPosition(start.left + dx, start.top + dy)
    },
    handleTouchEnd() {
      if (this._touch && this._touch.moved && this.data.floating) this.setPosition(this._bounds.maxLeft, this.data.top)
      this._touch = null
      this.setData({ offsetX: 0, offsetY: 0, dragging: false })
    },
    handleTouchCancel() {
      this._suppressTap = true
      if (this._touch) this.handleTouchEnd()
    },
    handleToggle() {
      if (this._attached && !this.data.sample && !this._suppressTap) this.triggerEvent(TOGGLE_EVENT)
    },
    handleCoverError() {
      if (this.data.coverSource !== DEFAULT_COVER) this.setData({ coverSource: DEFAULT_COVER })
      else this.setData({ coverFailed: true })
    }
  }
})

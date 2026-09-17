const STACKED = 'STACKED'
const PORTRAIT_CARDS = 'PORTRAIT_CARDS'
const VIDEO_TYPE = 'VIDEO'
const WORK_TAP_EVENT = 'worktap'
const SNAP_DURATION_MS = 220
const DRAG_THRESHOLD_PX = 8
const CARD_DRAG_SPACING_RPX = 472
const CARD_STACK_SPACING_RPX = 109.08
const DEFAULT_TITLE = '视频作品'
const DEFAULT_WORK_TITLE = '未命名视频'

function windowWidth() {
  try { return wx.getWindowInfo().windowWidth || 375 } catch (_) { return 375 }
}

function relativeLoopIndex(index, offset, count) {
  if (!count) return 0
  let relative = (index - offset) % count
  if (relative > count / 2) relative -= count
  if (relative < -count / 2) relative += count
  return relative
}

/** 媒体封面只能使用图片地址，不能把视频资源作为图片重试。 */
function safeCover(work) {
  const cover = work.coverUrl || work.thumbnailUrl || ''
  return typeof cover === 'string' && cover !== work.mediaUrl && !/\.(?:mp4|mov|m4v|webm)(?:[?#]|$)/i.test(cover) ? cover : ''
}

Component({
  properties: {
    config: { type: Object, value: {} },
    works: { type: Array, value: [] },
    componentKey: { type: String, value: '' },
    themeMode: { type: String, value: 'light' }
  },
  data: {
    items: [], cards: [], currentIndex: 0, displayStyle: STACKED, title: DEFAULT_TITLE,
    showTitle: true, showDescription: false, showComponentTitle: true, showSwipeHint: true,
    portraitNextMargin: 68, portraitHeight: 340, snapping: false
  },
  observers: { 'config, works'() { if (this._attached) this.rebuild() } },
  lifetimes: {
    attached() { this._attached = true; this._hidden = false; this._coverErrors = {}; this.rebuild() },
    detached() { this._attached = false; this.cancelMotion() }
  },
  pageLifetimes: {
    hide() { this._hidden = true; this.cancelMotion(); this.renderOffset(this.data.currentIndex) },
    show() { this._hidden = false; if (this._attached) this.measurePortrait() },
    resize() { if (this._attached) this.measurePortrait() }
  },
  methods: {
    cancelMotion() {
      if (this._snapTimer) clearTimeout(this._snapTimer)
      this._snapTimer = null
      this._touch = null
      this._suppressTap = false
      this.setData({ snapping: false })
    },
    rebuild() {
      const active = this.data.items[this.data.currentIndex]
      this.cancelMotion()
      const config = this.data.config || {}
      const library = Array.isArray(this.data.works) ? this.data.works : []
      const available = library.filter(work => work && (!work.mediaType || work.mediaType === VIDEO_TYPE))
      const ids = Array.isArray(config.workIds) ? config.workIds : available.map(work => work.workId || work.id)
      const seen = new Set()
      const items = ids.map(id => available.find(work => Number(work.workId || work.id) === Number(id))).filter(work => {
        const id = work && Number(work.workId || work.id)
        if (!id || seen.has(id)) return false
        seen.add(id)
        return true
      }).map(work => {
        const workId = Number(work.workId || work.id)
        const originalCoverUrl = safeCover(work)
        const coverFailed = this._coverErrors[workId] === originalCoverUrl && !!originalCoverUrl
        return { workId, title: work.title || DEFAULT_WORK_TITLE, description: work.description || '', originalCoverUrl, coverUrl: coverFailed ? '' : originalCoverUrl, coverFailed }
      })
      const index = active ? items.findIndex(item => item.workId === active.workId) : 0
      this.setData({
        items, displayStyle: config.displayStyle === PORTRAIT_CARDS ? PORTRAIT_CARDS : STACKED,
        title: config.title === undefined ? DEFAULT_TITLE : config.title,
        showTitle: config.showTitle !== false, showDescription: config.showDescription === true,
        showComponentTitle: config.showComponentTitle !== false, showSwipeHint: config.showSwipeHint !== false
      })
      this.renderOffset(Math.max(0, index))
      this.measurePortrait()
    },
    measurePortrait() {
      const width = windowWidth()
      const apply = trackWidth => {
        if (!this._attached || !(trackWidth > 0)) return
        this.setData({ portraitNextMargin: trackWidth * 0.2, portraitHeight: (trackWidth * 0.8 - 12 * width / 750) * 1.25 })
      }
      apply(width * (1 - 48 / 750))
      if (this.createSelectorQuery) this.createSelectorQuery().select('.mock-video-portrait').boundingClientRect(rect => { if (rect) apply(rect.width) }).exec()
    },
    renderOffset(value) {
      const count = this.data.items.length
      const requested = Number(value) || 0
      const offset = count >= 3 ? requested : Math.max(0, Math.min(Math.max(0, count - 1), requested))
      this._offset = offset
      const currentIndex = count ? ((Math.round(offset) % count) + count) % count : 0
      const cards = this.data.items.map((work, index) => {
        const relativeIndex = count >= 3 ? relativeLoopIndex(index, offset, count) : index - offset
        const distance = Math.abs(relativeIndex)
        const opacity = distance > 2.3 ? 0 : Math.max(0.25, 1 - Math.max(0, distance - 1.4) * 0.5)
        const translateX = relativeIndex * CARD_STACK_SPACING_RPX
        const scale = Math.max(0.6, 1 - distance * 0.16)
        const brightness = distance < 0.5 ? 1 : 1 - Math.min(distance, 2) * 0.14
        const zIndex = 100 - Math.round(distance * 10)
        const isCenter = distance < 0.5
        return {
          work, index, relativeIndex, translateX, scale, opacity, brightness, zIndex,
          isCenter, active: isCenter, hidden: opacity === 0,
          transformStyle: `transform:translate(-50%,-50%) translateX(${translateX}rpx) scale(${scale});z-index:${zIndex};opacity:${opacity};filter:brightness(${brightness});`
        }
      })
      this.setData({ currentIndex, cards })
    },
    snapTo(index) {
      if (this._snapTimer) clearTimeout(this._snapTimer)
      this.setData({ snapping: true })
      this.renderOffset(index)
      this._snapTimer = setTimeout(() => {
        this._snapTimer = null
        if (this._attached && !this._hidden) this.setData({ snapping: false })
      }, SNAP_DURATION_MS)
    },
    handleTouchStart(event) {
      if (!this._attached || this._hidden) return
      const point = event.touches && event.touches[0]
      if (!point) return
      if (this._snapTimer) clearTimeout(this._snapTimer)
      this._snapTimer = null
      this._suppressTap = false
      this.setData({ snapping: false })
      this._touch = { x: point.clientX, y: point.clientY, offset: this._offset || 0, moved: false, vertical: false }
    },
    handleTouchMove(event) {
      const start = this._touch
      const point = event.touches && event.touches[0]
      if (!start || !point || start.vertical) return
      const dx = point.clientX - start.x
      const dy = point.clientY - start.y
      if (!start.moved && Math.abs(dy) > Math.max(DRAG_THRESHOLD_PX, Math.abs(dx))) { start.vertical = true; this._suppressTap = true; return }
      if (Math.abs(dx) < DRAG_THRESHOLD_PX && !start.moved) return
      start.moved = true
      this._suppressTap = true
      this.renderOffset(start.offset - dx / (CARD_DRAG_SPACING_RPX * windowWidth() / 750))
    },
    handleTouchEnd() {
      const start = this._touch
      this._touch = null
      if (start && start.moved) this.snapTo(Math.round(this._offset))
    },
    handleTouchCancel() { this._suppressTap = true; this.handleTouchEnd() },
    playIndex(index) {
      if (!this._attached || this._hidden || this._suppressTap || this.data.snapping) return
      const item = this.data.items[index]
      if (!item) return
      const state = this.data.cards.find(card => card.index === index)
      if (state && !state.isCenter) { this.snapTo(this._offset + state.relativeIndex); return }
      this.triggerEvent(WORK_TAP_EVENT, { componentKey: this.data.componentKey, workId: item.workId })
    },
    handleCardTap(event) { this.playIndex(Number(event.currentTarget.dataset.index)) },
    handlePortraitTap(event) { this.playIndex(Number(event.currentTarget.dataset.index)) },
    handlePortraitChange(event) {
      this.cancelMotion()
      this.renderOffset(Number(event.detail.current) || 0)
    },
    handleCoverError(event) {
      const id = Number(event.currentTarget.dataset.workId)
      const work = this.data.items.find(item => item.workId === id)
      if (!work || !work.originalCoverUrl) return
      this._coverErrors[id] = work.originalCoverUrl
      this.rebuild()
    },
    handleRetryCover(event) {
      delete this._coverErrors[Number(event.currentTarget.dataset.workId)]
      this.rebuild()
    }
  }
})

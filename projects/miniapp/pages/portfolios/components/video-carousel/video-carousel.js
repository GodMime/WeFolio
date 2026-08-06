const {
  buildCardStates,
  cardSpacingPx,
  dragOffset,
  resolveWindowWidthPx,
  snapOffset
} = require('../../utils/video-carousel.js')

const ANIMATION_FACTOR = 0.16
const ANIMATION_EPSILON = 0.003
const ANIMATION_FRAME_MS = 16

function worksOf(component) {
  return Array.isArray(component.data.works) ? component.data.works : []
}

function clampNonLoopingOffset(offset, count) {
  if (count >= 3) return offset
  return Math.max(0, Math.min(Math.max(0, count - 1), offset))
}

function cancelAnimation(component) {
  if (component._videoCarouselAnimation) {
    clearTimeout(component._videoCarouselAnimation)
    component._videoCarouselAnimation = null
  }
}

function renderOffset(component, requestedOffset) {
  const works = worksOf(component)
  const offset = clampNonLoopingOffset(Number(requestedOffset) || 0, works.length)
  component._videoCarouselOffset = offset
  component.setData({
    offset,
    cardStates: buildCardStates(works, offset)
  })
}

function animateTo(component, requestedTarget) {
  cancelAnimation(component)
  const target = clampNonLoopingOffset(requestedTarget, worksOf(component).length)
  const step = () => {
    const current = Number(component._videoCarouselOffset) || 0
    const next = current + (target - current) * ANIMATION_FACTOR
    if (Math.abs(target - next) < ANIMATION_EPSILON) {
      renderOffset(component, target)
      component._videoCarouselAnimation = null
      return
    }
    renderOffset(component, next)
    component._videoCarouselAnimation = setTimeout(step, ANIMATION_FRAME_MS)
  }
  step()
}

function touchClientX(event) {
  const touches = event && event.touches && event.touches.length
    ? event.touches
    : event && event.changedTouches
  const point = touches && touches[0]
  return point && Number.isFinite(Number(point.clientX)) ? Number(point.clientX) : null
}

Component({
  properties: {
    componentKey: { type: String, value: '' },
    title: { type: String, value: '' },
    works: {
      type: Array,
      value: [],
      observer() {
        cancelAnimation(this)
        renderOffset(this, 0)
      }
    },
    showTitle: { type: Boolean, value: true },
    showSwipeHint: { type: Boolean, value: true },
    themeMode: { type: String, value: 'light' }
  },

  data: {
    offset: 0,
    cardStates: []
  },

  methods: {
    handleTouchStart(event) {
      const clientX = touchClientX(event)
      if (clientX === null) return
      cancelAnimation(this)
      this._videoCarouselTouch = {
        startX: clientX,
        startOffset: Number(this._videoCarouselOffset) || 0,
        maxDistance: 0
      }
    },

    handleTouchMove(event) {
      if (!this._videoCarouselTouch) return
      const clientX = touchClientX(event)
      if (clientX === null) return
      const deltaX = clientX - this._videoCarouselTouch.startX
      this._videoCarouselTouch.maxDistance = Math.max(
        this._videoCarouselTouch.maxDistance,
        Math.abs(deltaX)
      )
      renderOffset(this, dragOffset(
        this._videoCarouselTouch.startOffset,
        deltaX,
        cardSpacingPx(resolveWindowWidthPx())
      ))
    },

    handleTouchEnd() {
      const touch = this._videoCarouselTouch
      this._videoCarouselTouch = null
      if (!touch) return
      animateTo(this, snapOffset(this._videoCarouselOffset))
    },

    handleCardTap(event) {
      const workIndex = Number(event.currentTarget && event.currentTarget.dataset.index)
      const state = buildCardStates(worksOf(this), this._videoCarouselOffset)
        .find((item) => item.workIndex === workIndex)
      if (!state) return
      if (!state.isCenter) {
        animateTo(this, this._videoCarouselOffset + state.relativeIndex)
        return
      }
      renderOffset(this, snapOffset(this._videoCarouselOffset))
      this.triggerEvent('play', {
        componentKey: this.data.componentKey,
        work: state.work
      })
    },

    handleTouchCancel() {
      this._videoCarouselTouch = null
      animateTo(this, snapOffset(this._videoCarouselOffset))
    }
  },

  lifetimes: {
    attached() {
      renderOffset(this, 0)
    },
    detached() {
      cancelAnimation(this)
      this._videoCarouselTouch = null
    }
  }
})

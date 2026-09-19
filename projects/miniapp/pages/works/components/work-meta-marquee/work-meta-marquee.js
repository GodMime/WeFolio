const OVERFLOW_TOLERANCE_PX = 1
const SCROLL_SPEED_PX_PER_SECOND = 24
const MIN_DURATION_SECONDS = 6
// 关键帧首尾各停留 15%，其余时间匀速展示完整信息。
const SCROLL_ACTIVE_RATIO = 0.7
const VIEWPORT_SELECTOR = '.meta-viewport'
const TEXT_SELECTOR = '.meta-text'

Component({
  properties: {
    text: { type: String, value: '' },
    // 标题与灰色信息共用滚动逻辑，分别保留原有字号和行间距。
    titleStyle: { type: Boolean, value: false },
    // 批量选择框会改变信息可用宽度，需要重新测量。
    compact: { type: Boolean, value: false }
  },

  data: {
    animationEnabled: false,
    distance: 0,
    duration: 0,
    visible: true
  },

  observers: {
    'text, compact, titleStyle'() {
      if (this.marqueeReady) this.refreshMarquee()
    }
  },

  lifetimes: {
    ready() {
      this.marqueeReady = true
      this.refreshMarquee()
    },
    detached() {
      this.marqueeReady = false
      this.marqueeMeasurement = (this.marqueeMeasurement || 0) + 1
    }
  },

  pageLifetimes: {
    show() {
      this.setData({ visible: true })
      if (this.marqueeReady) this.refreshMarquee()
    },
    hide() {
      this.setData({ visible: false })
      this.marqueeMeasurement = (this.marqueeMeasurement || 0) + 1
    },
    resize() {
      if (this.marqueeReady) this.refreshMarquee()
    }
  },

  methods: {
    refreshMarquee() {
      const measurement = (this.marqueeMeasurement || 0) + 1
      this.marqueeMeasurement = measurement
      // 先复位旧动画，再在新文字与容器完成布局后读取自然宽度。
      this.setData({ animationEnabled: false, distance: 0, duration: 0 }, () => {
        if (!this.marqueeReady || measurement !== this.marqueeMeasurement) return
        const query = this.createSelectorQuery()
        query.select(VIEWPORT_SELECTOR).boundingClientRect()
        query.select(TEXT_SELECTOR).boundingClientRect()
        query.exec((rects = []) => {
          if (!this.marqueeReady || measurement !== this.marqueeMeasurement) return
          const viewportWidth = Number(rects[0] && rects[0].width)
          const textWidth = Number(rects[1] && rects[1].width)
          if (!Number.isFinite(viewportWidth) || viewportWidth <= 0 || !Number.isFinite(textWidth)) return
          const overflow = textWidth - viewportWidth
          if (overflow <= OVERFLOW_TOLERANCE_PX) return
          const distance = Math.ceil(overflow)
          const duration = Math.max(MIN_DURATION_SECONDS, distance / SCROLL_SPEED_PX_PER_SECOND / SCROLL_ACTIVE_RATIO)
          this.setData({ animationEnabled: true, distance, duration })
        })
      })
    }
  }
})

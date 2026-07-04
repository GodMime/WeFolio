const DEFAULT_INTERVAL_MS = 3000
const DEFAULT_DURATION_MS = 420

function normalizePositiveNumber(value, fallback) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? Math.round(numberValue) : fallback
}

function normalizeWorks(works) {
  return Array.isArray(works) ? works : []
}

function normalizeCurrentIndex(current, works) {
  const maxIndex = Math.max(normalizeWorks(works).length - 1, 0)
  const numberValue = Number(current)
  if (!Number.isFinite(numberValue)) {
    return 0
  }
  return Math.max(0, Math.min(Math.round(numberValue), maxIndex))
}

function buildProgressSegments(works, currentIndex) {
  const sourceWorks = normalizeWorks(works)
  const safeIndex = normalizeCurrentIndex(currentIndex, sourceWorks)
  return sourceWorks.map((work, index) => ({
    key: work && work.workId ? `work-${work.workId}` : `slide-${index}`,
    state: index < safeIndex ? 'done' : index === safeIndex ? 'current' : 'pending'
  }))
}

Component({
  properties: {
    works: {
      type: Array,
      value: [],
      observer: 'syncCarouselState'
    },
    interval: {
      type: Number,
      value: DEFAULT_INTERVAL_MS,
      observer: 'syncTimingState'
    },
    duration: {
      type: Number,
      value: DEFAULT_DURATION_MS,
      observer: 'syncTimingState'
    }
  },

  data: {
    currentIndex: 0,
    canAutoplay: false,
    safeInterval: DEFAULT_INTERVAL_MS,
    safeDuration: DEFAULT_DURATION_MS,
    progressStyle: `animation-duration: ${DEFAULT_INTERVAL_MS}ms;`,
    progressSegments: []
  },

  lifetimes: {
    attached() {
      this.syncTimingState()
      this.syncCarouselState(this.data.works)
    }
  },

  methods: {
    syncTimingState() {
      const safeInterval = normalizePositiveNumber(this.data.interval, DEFAULT_INTERVAL_MS)
      this.setData({
        safeInterval,
        safeDuration: normalizePositiveNumber(this.data.duration, DEFAULT_DURATION_MS),
        progressStyle: `animation-duration: ${safeInterval}ms;`
      })
    },

    syncCarouselState(works) {
      const sourceWorks = normalizeWorks(works)
      const currentIndex = normalizeCurrentIndex(this.data.currentIndex, sourceWorks)
      this.setData({
        currentIndex,
        canAutoplay: sourceWorks.length > 1,
        progressSegments: buildProgressSegments(sourceWorks, currentIndex)
      })
    },

    handleSwiperChange(event) {
      const currentIndex = normalizeCurrentIndex(event && event.detail ? event.detail.current : 0, this.data.works)
      this.setData({
        currentIndex,
        progressSegments: buildProgressSegments(this.data.works, currentIndex)
      })
    }
  }
})

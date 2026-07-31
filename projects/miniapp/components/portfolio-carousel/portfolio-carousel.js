const DEFAULT_INTERVAL_MS = 3000
const DEFAULT_DURATION_MS = 420
const CAROUSEL_FRAME_WIDTH_RPX = 750
const DEFAULT_RATIO_WIDTH = 4
const DEFAULT_RATIO_HEIGHT = 3
const DEFAULT_FRAME_HEIGHT_RPX = Math.round((CAROUSEL_FRAME_WIDTH_RPX * DEFAULT_RATIO_HEIGHT) / DEFAULT_RATIO_WIDTH)
const DEFAULT_FRAME_STYLE = `height: ${DEFAULT_FRAME_HEIGHT_RPX}rpx;`

function gcd(left, right) {
  let a = Math.abs(left)
  let b = Math.abs(right)
  while (b > 0) {
    const remainder = a % b
    a = b
    b = remainder
  }
  return a
}

function normalizePositiveNumber(value, fallback) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? Math.round(numberValue) : fallback
}

function normalizePositiveDimension(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : 0
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

function formatRatioNumber(value) {
  return String(Number(value.toFixed(3)))
}

function buildFrameStyle(width, height) {
  return `height: ${Math.round((CAROUSEL_FRAME_WIDTH_RPX * height) / width)}rpx;`
}

function normalizeRatioPair(width, height) {
  const normalizedWidth = normalizePositiveDimension(width)
  const normalizedHeight = normalizePositiveDimension(height)
  if (!normalizedWidth || !normalizedHeight) {
    return null
  }
  if (Number.isInteger(normalizedWidth) && Number.isInteger(normalizedHeight)) {
    const divisor = gcd(normalizedWidth, normalizedHeight)
    const ratioWidth = normalizedWidth / divisor
    const ratioHeight = normalizedHeight / divisor
    return {
      key: `${ratioWidth}:${ratioHeight}`,
      style: buildFrameStyle(ratioWidth, ratioHeight)
    }
  }
  return {
    key: formatRatioNumber(normalizedWidth / normalizedHeight),
    style: buildFrameStyle(normalizedWidth, normalizedHeight)
  }
}

function parseRatioText(value) {
  const ratioText = String(value || '').trim()
  if (!ratioText || ratioText === '--') {
    return null
  }
  const pairMatch = ratioText.match(/^(\d+(?:\.\d+)?)\s*(?::|\/|比)\s*(\d+(?:\.\d+)?)$/)
  if (pairMatch) {
    return normalizeRatioPair(Number(pairMatch[1]), Number(pairMatch[2]))
  }
  const decimalRatio = Number(ratioText)
  return Number.isFinite(decimalRatio) && decimalRatio > 0
    ? normalizeRatioPair(decimalRatio, 1)
    : null
}

function resolveWorkRatio(work = {}) {
  const ratioFields = [work.aspectRatio, work.aspectRatioText, work.ratio]
  for (let index = 0; index < ratioFields.length; index += 1) {
    const ratioFromText = parseRatioText(ratioFields[index])
    if (ratioFromText) {
      return ratioFromText
    }
  }
  return normalizeRatioPair(
    work.width || work.imageWidth || work.mediaWidth,
    work.height || work.length || work.imageHeight || work.mediaHeight
  )
}

function buildCarouselFrameStyle(works) {
  const ratioCounts = new Map()
  normalizeWorks(works).forEach((work) => {
    const ratio = resolveWorkRatio(work)
    if (!ratio) {
      return
    }
    const existing = ratioCounts.get(ratio.key)
    if (existing) {
      existing.count += 1
      return
    }
    ratioCounts.set(ratio.key, {
      count: 1,
      style: ratio.style
    })
  })
  let bestRatio = null
  ratioCounts.forEach((ratio) => {
    if (!bestRatio || ratio.count > bestRatio.count) {
      bestRatio = ratio
    }
  })
  return bestRatio && bestRatio.count > 1 ? bestRatio.style : DEFAULT_FRAME_STYLE
}

Component({
  properties: {
    themeMode: {
      type: String,
      value: 'light'
    },
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
    carouselFrameStyle: DEFAULT_FRAME_STYLE,
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
        carouselFrameStyle: buildCarouselFrameStyle(sourceWorks),
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

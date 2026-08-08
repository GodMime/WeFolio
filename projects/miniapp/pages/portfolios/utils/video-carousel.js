const CARD_WIDTH_RPX = 576
const CARD_SPACING_RPX = 472
const CARD_STACK_SPACING_RPX = 109.08
const STAGE_HEIGHT_RPX = 464
const CLICK_THRESHOLD_PX = 8
const DEFAULT_WINDOW_WIDTH_PX = 375

function positiveNumber(value, fallback) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : fallback
}

function resolveWindowWidthPx(wxApi = typeof wx === 'undefined' ? null : wx) {
  if (wxApi && typeof wxApi.getWindowInfo === 'function') {
    try {
      const width = positiveNumber(wxApi.getWindowInfo().windowWidth, 0)
      if (width) return width
    } catch (error) {}
  }
  if (wxApi && typeof wxApi.getSystemInfoSync === 'function') {
    try {
      const width = positiveNumber(wxApi.getSystemInfoSync().windowWidth, 0)
      if (width) return width
    } catch (error) {}
  }
  return DEFAULT_WINDOW_WIDTH_PX
}

function cardSpacingPx(windowWidthPx) {
  return CARD_SPACING_RPX * positiveNumber(windowWidthPx, DEFAULT_WINDOW_WIDTH_PX) / 750
}

function dragOffset(startOffset, deltaX, spacingPx) {
  const start = Number.isFinite(Number(startOffset)) ? Number(startOffset) : 0
  const delta = Number.isFinite(Number(deltaX)) ? Number(deltaX) : 0
  return start - delta / positiveNumber(spacingPx, cardSpacingPx(DEFAULT_WINDOW_WIDTH_PX))
}

function snapOffset(offset) {
  return Math.round(Number.isFinite(Number(offset)) ? Number(offset) : 0)
}

function relativeLoopIndex(index, offset, count) {
  const length = Math.max(0, Math.floor(Number(count) || 0))
  if (!length) return 0
  let relative = (Number(index) - Number(offset)) % length
  if (relative > length / 2) relative -= length
  if (relative < -length / 2) relative += length
  return relative
}

function buildCardStates(works = [], offset = 0) {
  const source = Array.isArray(works) ? works : []
  const currentOffset = Number.isFinite(Number(offset)) ? Number(offset) : 0
  const looping = source.length >= 3
  return source.map((work, index) => {
    const relativeIndex = looping
      ? relativeLoopIndex(index, currentOffset, source.length)
      : index - currentOffset
    const distance = Math.abs(relativeIndex)
    const opacity = distance > 2.3
      ? 0
      : Math.max(0.25, 1 - Math.max(0, distance - 1.4) * 0.5)
    return {
      work,
      workIndex: index,
      wrappedWorkIndex: index,
      workKey: work && work.workId ? `work-${work.workId}` : `video-${index}`,
      relativeIndex,
      translateX: relativeIndex * CARD_STACK_SPACING_RPX,
      scale: Math.max(0.6, 1 - distance * 0.16),
      opacity,
      brightness: distance < 0.5 ? 1 : 1 - Math.min(distance, 2) * 0.14,
      zIndex: 100 - Math.round(distance * 10),
      hidden: opacity === 0,
      isCenter: distance < 0.5
    }
  })
}

function isClickGesture(distancePx) {
  return Math.abs(Number(distancePx) || 0) < CLICK_THRESHOLD_PX
}

function formatDuration(milliseconds) {
  const numericValue = Number(milliseconds)
  const totalSeconds = Math.max(0, Math.floor(
    (Number.isFinite(numericValue) ? numericValue : 0) / 1000
  ))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

module.exports = {
  CARD_WIDTH_RPX,
  CARD_SPACING_RPX,
  STAGE_HEIGHT_RPX,
  CLICK_THRESHOLD_PX,
  resolveWindowWidthPx,
  cardSpacingPx,
  dragOffset,
  snapOffset,
  relativeLoopIndex,
  buildCardStates,
  isClickGesture,
  formatDuration
}

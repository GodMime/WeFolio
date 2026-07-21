const OVERFLOW_TOLERANCE_PX = 1
const MIN_DURATION_SECONDS = 6
const SCROLL_SPEED_PX_PER_SECOND = 24
const END_PAUSE_SECONDS = 2

function disabledMarquee() {
  return {
    marqueeEnabled: false,
    marqueeDistance: 0,
    marqueeDuration: 0
  }
}

function buildLedgerMarquee(containerWidth, textWidth) {
  const viewport = Number(containerWidth)
  const content = Number(textWidth)
  if (!Number.isFinite(viewport) || viewport <= 0 || !Number.isFinite(content) || content <= 0) {
    return disabledMarquee()
  }
  const overflow = content - viewport
  if (overflow <= OVERFLOW_TOLERANCE_PX) {
    return disabledMarquee()
  }
  const marqueeDistance = Math.ceil(overflow)
  const calculatedDuration = marqueeDistance / SCROLL_SPEED_PX_PER_SECOND + END_PAUSE_SECONDS
  const marqueeDuration = Math.max(
    MIN_DURATION_SECONDS,
    Math.round(calculatedDuration * 10) / 10
  )
  return {
    marqueeEnabled: true,
    marqueeDistance,
    marqueeDuration
  }
}

module.exports = {
  buildLedgerMarquee
}

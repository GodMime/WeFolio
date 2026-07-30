function normalizeFrameCount(value) {
  const frameCount = Math.floor(Number(value))
  return Number.isFinite(frameCount) && frameCount >= 1 ? frameCount : 1
}

function clampAnimationFrameNumber(value, frameCount) {
  const maxFrame = normalizeFrameCount(frameCount)
  const frameNumber = Math.round(Number(value))
  if (!Number.isFinite(frameNumber)) {
    return 1
  }
  return Math.max(1, Math.min(maxFrame, frameNumber))
}

function buildAnimationFramePreviewUrl(mediaUrl, frameNumber) {
  const source = String(mediaUrl || '').trim()
  if (!source) {
    return ''
  }
  const separator = source.includes('?') ? '&' : '?'
  return `${source}${separator}imageMogr2/frame/${frameNumber}/format/jpg`
}

function createAnimationCoverIdempotencyKey(workId, options = {}) {
  const timestamp = Number.isFinite(Number(options.timestamp))
    ? Number(options.timestamp)
    : Date.now()
  const nonce = String(options.nonce || Math.random().toString(36).slice(2, 10))
  return `animation-cover-${workId}-${timestamp}-${nonce}`
}

function createAnimationCoverEditState(work = {}, options = {}) {
  const frameCount = normalizeFrameCount(work.frameCount)
  const selectedFrame = clampAnimationFrameNumber(
    work.coverFrameNumber || 1, frameCount
  )
  return {
    mediaUrl: String(work.mediaUrl || '').trim(),
    frameCount,
    selectedFrame,
    previewUrl: buildAnimationFramePreviewUrl(work.mediaUrl, selectedFrame),
    idempotencyKey: createAnimationCoverIdempotencyKey(work.id, options),
    confirmed: false
  }
}

function updateAnimationCoverEditFrame(state = {}, value) {
  const selectedFrame = clampAnimationFrameNumber(value, state.frameCount)
  return Object.assign({}, state, {
    selectedFrame,
    previewUrl: buildAnimationFramePreviewUrl(state.mediaUrl || '', selectedFrame),
    confirmed: false
  })
}

function updateAnimationCoverSelection(state = {}, value) {
  return Object.assign({}, state, {
    selectedFrame: clampAnimationFrameNumber(value, state.frameCount),
    confirmed: false
  })
}

module.exports = {
  buildAnimationFramePreviewUrl,
  clampAnimationFrameNumber,
  createAnimationCoverEditState,
  createAnimationCoverIdempotencyKey,
  updateAnimationCoverSelection,
  updateAnimationCoverEditFrame
}

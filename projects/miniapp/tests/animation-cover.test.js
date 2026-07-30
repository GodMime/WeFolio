const assert = require('node:assert/strict')
const test = require('node:test')

const {
  buildAnimationFramePreviewUrl,
  clampAnimationFrameNumber,
  createAnimationCoverEditState,
  updateAnimationCoverSelection,
  updateAnimationCoverEditFrame
} = require('../pages/works/utils/animation-cover')

test('clamps animation frame numbers to the available range', () => {
  assert.equal(clampAnimationFrameNumber(0, 20), 1)
  assert.equal(clampAnimationFrameNumber(8, 20), 8)
  assert.equal(clampAnimationFrameNumber(30, 20), 20)
})

test('builds frame preview URL while preserving an existing query', () => {
  assert.equal(
    buildAnimationFramePreviewUrl('https://cdn.example/a.gif', 5),
    'https://cdn.example/a.gif?imageMogr2/frame/5/format/jpg'
  )
  assert.equal(
    buildAnimationFramePreviewUrl('https://cdn.example/a.webp?token=x', 23),
    'https://cdn.example/a.webp?token=x&imageMogr2/frame/23/format/jpg'
  )
})

test('creates a new idempotency key per edit session and keeps it while sliding', () => {
  const first = createAnimationCoverEditState({
    id: 9,
    mediaUrl: 'https://cdn.example/a.gif',
    frameCount: 20,
    coverFrameNumber: 5
  }, { timestamp: 100, nonce: 'a' })
  const second = createAnimationCoverEditState({
    id: 9,
    mediaUrl: 'https://cdn.example/a.gif',
    frameCount: 20,
    coverFrameNumber: 5
  }, { timestamp: 101, nonce: 'b' })
  const changed = updateAnimationCoverEditFrame(first, 8)

  assert.equal(first.selectedFrame, 5)
  assert.notEqual(first.idempotencyKey, second.idempotencyKey)
  assert.equal(changed.idempotencyKey, first.idempotencyKey)
  assert.equal(changed.selectedFrame, 8)
  assert.match(changed.previewUrl, /frame\/8\/format\/jpg$/)
})

test('keeps the current remote preview while the frame slider is moving', () => {
  const first = createAnimationCoverEditState({
    id: 9,
    mediaUrl: 'https://cdn.example/a.gif',
    frameCount: 20,
    coverFrameNumber: 5
  }, { timestamp: 100, nonce: 'a' })

  const changing = updateAnimationCoverSelection(first, 8)

  assert.equal(changing.selectedFrame, 8)
  assert.equal(changing.previewUrl, first.previewUrl)
  assert.equal(changing.confirmed, false)
})

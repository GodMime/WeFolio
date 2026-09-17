const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const test = require('node:test')
const { createMockAudioController, normalizeMockBackgroundAudio, resolveMockBackgroundAudio } = require('../pages/mock/utils/mock-portfolio-audio')

const AUDIO_URL = 'https://cdn2.we-folio.dingchenyong.top/system/IF_YOU-BIGBANG.mp3'
const RESOURCE = { workId: 108, mediaUrl: AUDIO_URL, enabled: true }
const WORKS = [{ id: 108, mediaType: 'AUDIO', mediaUrl: AUDIO_URL, title: 'IF YOU', coverUrl: 'cover.png' }, { id: 109, mediaType: 'ANIMATION', mediaUrl: 'image.gif' }]

function createHarness(overrides = {}) {
  const contexts = []
  const events = []
  const errors = []
  const wxApi = {
    createInnerAudioContext() {
      const callbacks = {}
      const context = { callbacks, playCalls: 0, pauseCalls: 0, destroyCalls: 0,
        play() { events.push('play'); this.playCalls += 1 },
        pause() { this.pauseCalls += 1 },
        destroy() { this.destroyCalls += 1 },
        ...overrides }
      for (const event of ['Play', 'Pause', 'Stop', 'Ended', 'Error']) context[`on${event}`] = callback => { callbacks[event] = callback }
      contexts.push(context)
      return context
    }
  }
  const player = createMockAudioController({ wxApi, beforePlay: () => events.push('before'), onPlaying: value => events.push(value), onError: error => errors.push(error) })
  player.setResource(RESOURCE)
  return { player, contexts, events, errors }
}

test('audio playback is confirmed only by platform event, with mutual exclusion before play', () => {
  const { player, contexts, events } = createHarness()
  player.play()
  assert.equal(contexts.length, 1)
  assert.equal(contexts[0].src, AUDIO_URL)
  assert.equal(player.getState().playing, false)
  assert.deepEqual(events, ['before', 'play'])
  player.play()
  assert.equal(contexts[0].playCalls, 1)
  contexts[0].callbacks.Play()
  assert.equal(player.getState().playing, true)
  player.pause()
  assert.equal(player.getState().playing, false)
  contexts[0].callbacks.Play()
  assert.equal(player.getState().playing, false)
  assert.ok(contexts[0].pauseCalls >= 2)
})

test('pending play can be cancelled by toggle and an ended track can be manually restarted', () => {
  const { player, contexts } = createHarness()
  player.toggle()
  player.toggle()
  contexts[0].callbacks.Play()
  assert.equal(player.getState().playing, false)
  player.toggle()
  contexts[0].callbacks.Play()
  contexts[0].callbacks.Ended()
  assert.equal(player.getState().playing, false)
  player.play()
  assert.equal(contexts[0].playCalls, 3)
})

test('resource replacement retires previous context and ignores all of its late callbacks', () => {
  const { player, contexts, errors } = createHarness()
  player.play()
  const first = contexts[0]
  player.setResource({ ...RESOURCE, workId: 208, mediaUrl: 'second.mp3' })
  assert.equal(first.destroyCalls, 1)
  player.play()
  contexts[1].callbacks.Play()
  first.callbacks.Play()
  first.callbacks.Pause()
  first.callbacks.Error({ errMsg: 'late' })
  assert.equal(player.getState().playing, true)
  assert.equal(errors.length, 0)
})

test('hide cancels pending playback, show never resumes, destroy is idempotent', () => {
  const { player, contexts } = createHarness()
  player.play()
  player.hide()
  contexts[0].callbacks.Play()
  assert.equal(player.getState().playing, false)
  player.play()
  assert.equal(contexts[0].playCalls, 1)
  player.show()
  assert.equal(contexts[0].playCalls, 1)
  player.play()
  player.destroy()
  player.destroy()
  contexts[0].callbacks.Play()
  player.show()
  player.play()
  assert.equal(contexts[0].destroyCalls, 1)
  assert.equal(contexts[0].playCalls, 2)
  assert.equal(player.getState().playing, false)
})

test('platform errors clear playback without automatic retry and allow a new manual attempt', () => {
  const { player, contexts, errors } = createHarness()
  player.play()
  contexts[0].callbacks.Error({ errCode: 10001, errMsg: 'decode failed' })
  assert.equal(player.getState().playing, false)
  assert.equal(errors.length, 1)
  assert.equal(contexts[0].playCalls, 1)
  player.play()
  contexts[contexts.length - 1].callbacks.Play()
  assert.equal(player.getState().playing, true)
})

test('synchronous play failure reports paused state and can be retried', () => {
  let fail = true
  const { player, contexts, errors } = createHarness({ play() { if (fail) throw new Error('blocked') } })
  assert.equal(player.play(), false)
  assert.equal(errors.length, 1)
  assert.equal(player.getState().playing, false)
  fail = false
  player.play()
  contexts[contexts.length - 1].callbacks.Play()
  assert.equal(player.getState().playing, true)
})

test('context creation failures remain retryable', () => {
  const errors = []
  const player = createMockAudioController({ wxApi: { createInnerAudioContext() { throw new Error('unavailable') } }, onError: error => errors.push(error) })
  player.setResource(RESOURCE)
  assert.equal(player.play(), false)
  assert.equal(player.play(), false)
  assert.equal(errors.length, 2)
})

test('synchronous media load errors cannot restart a retired context', () => {
  let playCalls = 0
  const errors = []
  const player = createMockAudioController({
    wxApi: { createInnerAudioContext() {
      let onError
      return {
        onPlay() {}, onPause() {}, onStop() {}, onEnded() {},
        onError(callback) { onError = callback },
        set src(value) { onError({ errMsg: 'invalid media' }) },
        play() { playCalls += 1 }, pause() {}, destroy() {}
      }
    } },
    onError: error => errors.push(error)
  })
  player.setResource(RESOURCE)
  assert.equal(player.play(), false)
  assert.equal(playCalls, 0)
  assert.equal(player.getState().pending, false)
  assert.equal(errors.length, 1)
})

test('disabling and removing resource prevent play without creating extra contexts', () => {
  const { player, contexts } = createHarness()
  player.play()
  contexts[0].callbacks.Play()
  player.setResource({ ...RESOURCE, enabled: false })
  assert.equal(player.getState().playing, false)
  assert.equal(player.play(), false)
  player.setResource(null)
  assert.equal(player.play(), false)
  assert.equal(contexts.length, 1)
  assert.equal(contexts[0].destroyCalls, 1)
})

test('background config retains disabled selection, clears removed or invalid reference and drops runtime fields', () => {
  assert.deepEqual(normalizeMockBackgroundAudio(undefined, WORKS), { enabled: false, workId: 108, displayStyle: 'DISC' })
  assert.deepEqual(normalizeMockBackgroundAudio({ enabled: false, workId: 108, displayStyle: 'SLEEVE', mediaUrl: AUDIO_URL, playing: true }, WORKS), { enabled: false, workId: 108, displayStyle: 'SLEEVE' })
  for (const workId of [null, 109, 999]) {
    assert.deepEqual(normalizeMockBackgroundAudio({ enabled: true, workId, displayStyle: 'invalid' }, WORKS), { enabled: false, workId: null, displayStyle: 'DISC' })
  }
  assert.equal(normalizeMockBackgroundAudio({ enabled: true, workId: 108, displayStyle: 'MINI_PLAYER' }, WORKS).displayStyle, 'MINI_PLAYER')
  assert.equal(resolveMockBackgroundAudio({ enabled: false, workId: 108 }, WORKS), null)
  assert.equal(resolveMockBackgroundAudio({ enabled: true, workId: 109 }, WORKS), null)
  assert.deepEqual(resolveMockBackgroundAudio({ enabled: true, workId: 108 }, WORKS), { ...RESOURCE, title: 'IF YOU', coverUrl: 'cover.png', displayStyle: 'DISC', loop: true })
})

function loadControl(properties = {}) {
  let definition
  const file = path.join(__dirname, '../components/mock/background-audio-control/background-audio-control.js')
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Component: value => { definition = value },
    wx: { getWindowInfo: () => ({ windowWidth: 375, windowHeight: 667, safeArea: { bottom: 647 } }) }
  })
  const emitted = []
  const instance = { data: { ...definition.data }, setData(value) { Object.assign(this.data, value) }, triggerEvent(name) { emitted.push(name) } }
  for (const [key, value] of Object.entries(definition.properties)) instance.data[key] = value.value
  Object.assign(instance.data, properties)
  for (const [key, method] of Object.entries(definition.methods)) instance[key] = method.bind(instance)
  definition.lifetimes.attached.call(instance)
  return { instance, definition, emitted }
}

const touch = (x, y) => ({ touches: [{ clientX: x, clientY: y }] })

test('floating control clamps dragging and snaps to the right without firing a play click', () => {
  const { instance, emitted } = loadControl()
  const right = instance.data.left
  instance.handleTouchStart(touch(330, 300))
  instance.handleTouchMove(touch(-1000, -1000))
  assert.ok(instance.data.left >= 0)
  assert.ok(instance.data.top >= 0)
  instance.handleTouchEnd()
  assert.equal(instance.data.left, right)
  instance.handleToggle()
  assert.deepEqual(emitted, [])
  instance.handleTouchStart(touch(330, 300))
  instance.handleTouchEnd()
  instance.handleToggle()
  assert.deepEqual(emitted, ['toggle'])
})

test('all three styles recalculate their footprint and remain above bottom inset', () => {
  for (const displayStyle of ['DISC', 'SLEEVE', 'MINI_PLAYER']) {
    const { instance } = loadControl({ displayStyle, topInset: 80, bottomInset: 100 })
    instance.handleTouchStart(touch(300, 300))
    instance.handleTouchMove(touch(1000, 1000))
    instance.handleTouchEnd()
    assert.ok(instance.data.left + instance.data.controlWidth <= 375)
    assert.ok(instance.data.top + instance.data.controlHeight <= 567)
    assert.ok(instance.data.top >= 80)
  }
})

test('inline preview stays in layout and a cancelled gesture does not toggle', () => {
  const { instance, emitted, definition } = loadControl({ floating: false })
  instance.handleTouchStart(touch(10, 10))
  instance.handleTouchMove(touch(100, 100))
  assert.equal(instance.data.positionStyle, '')
  instance.handleTouchCancel()
  instance.handleToggle()
  assert.deepEqual(emitted, [])
  definition.lifetimes.detached.call(instance)
  instance.handleToggle()
  assert.deepEqual(emitted, [])
})

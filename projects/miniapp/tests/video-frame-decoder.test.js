const assert = require('node:assert/strict')
const test = require('node:test')

const {
  decodeVideoFrameAtTime,
  normalizeVideoFrameSeekTime
} = require('../utils/video-frame-decoder')

function createImmediateDecoder(overrides = {}) {
  const handlers = {}
  const decoder = {
    on(eventName, callback) {
      handlers[eventName] = callback
    },
    off(eventName, callback) {
      if (handlers[eventName] === callback) {
        delete handlers[eventName]
      }
    },
    start(options) {
      if (overrides.onStart) {
        overrides.onStart(options)
      }
      if (handlers.start) {
        handlers.start({ width: 1920, height: 1080 })
      }
    },
    seek(position) {
      if (overrides.onSeek) {
        overrides.onSeek(position)
      }
      if (handlers.seek) {
        handlers.seek()
      }
    },
    getFrameData() {
      return overrides.getFrameData()
    },
    stop() {
      if (overrides.onStop) {
        overrides.onStop()
      }
    },
    remove() {
      if (overrides.onRemove) {
        overrides.onRemove()
      }
    }
  }
  return decoder
}

test('video frame decoder avoids seeking exactly at video end', () => {
  assert.equal(normalizeVideoFrameSeekTime(13000, 13000), 12900)
  assert.equal(normalizeVideoFrameSeekTime(3000, 13000), 3000)
  assert.equal(normalizeVideoFrameSeekTime(-100, 13000), 0)
})

test('video frame decoder retries transient player start failures while reading frame', async () => {
  let readCount = 0
  const startOptions = []
  const decoder = createImmediateDecoder({
    onStart(options) {
      startOptions.push(options)
    },
    getFrameData() {
      readCount += 1
      if (readCount < 3) {
        throw new Error('playerStart failed')
      }
      return {
        width: 1,
        height: 1,
        data: new Uint8ClampedArray([1, 2, 3, 4])
      }
    }
  })

  const result = await decodeVideoFrameAtTime({
    wxApi: {
      createVideoDecoder() {
        return decoder
      }
    },
    source: 'wxfile://tmp/clip.mp4',
    timeMs: 1000,
    durationMs: 5000,
    readDelayMs: 0
  })

  assert.equal(result.seekTimeMs, 1000)
  assert.equal(readCount, 3)
  assert.deepEqual(startOptions[0], {
    source: 'wxfile://tmp/clip.mp4',
    abortAudio: true,
    mode: 0
  })
})

test('video frame decoder start failure message includes debug version marker', async () => {
  const decoder = {
    on() {},
    off() {},
    start() {
      throw new Error('playerStart failed')
    },
    stop() {},
    remove() {}
  }

  await assert.rejects(async () => decodeVideoFrameAtTime({
    wxApi: {
      createVideoDecoder() {
        return decoder
      }
    },
    source: 'wxfile://tmp/android.mp4',
    timeMs: 5000,
    durationMs: 13000,
    eventTimeoutMs: 1
  }), /VF-20260630-4/)
})

test('video frame decoder retries start with conservative options when default options fail', async () => {
  const startOptions = []
  const decoder = createImmediateDecoder({
    onStart(options) {
      startOptions.push(options)
      if (startOptions.length === 1) {
        throw new Error('playerStart failed')
      }
    },
    getFrameData() {
      return {
        width: 1,
        height: 1,
        data: new Uint8ClampedArray([1, 2, 3, 4])
      }
    }
  })

  const result = await decodeVideoFrameAtTime({
    wxApi: {
      createVideoDecoder() {
        return decoder
      }
    },
    source: 'wxfile://tmp/android.mp4',
    timeMs: 1000,
    durationMs: 5000,
    readDelayMs: 0
  })

  assert.equal(result.seekTimeMs, 1000)
  assert.deepEqual(startOptions, [
    {
      source: 'wxfile://tmp/android.mp4',
      abortAudio: true,
      mode: 0
    },
    {
      source: 'wxfile://tmp/android.mp4'
    }
  ])
})

test('video frame decoder reports raw start failure for each attempted option set', async () => {
  const errors = [
    'playerStart failed: option abortAudio invalid',
    'playerStart failed: source open failed'
  ]
  let decoderIndex = 0

  await assert.rejects(async () => decodeVideoFrameAtTime({
    wxApi: {
      createVideoDecoder() {
        const message = errors[decoderIndex]
        decoderIndex += 1
        return {
          on() {},
          off() {},
          start() {
            throw new Error(message)
          },
          stop() {},
          remove() {}
        }
      }
    },
    source: 'wxfile://tmp/android.mp4',
    timeMs: 1000,
    durationMs: 5000,
    eventTimeoutMs: 1
  }), (error) => {
    assert.match(error.message, /VF-20260630-4/)
    assert.match(error.message, /default=playerStart failed: option abortAudio invalid/)
    assert.match(error.message, /plain=playerStart failed: source open failed/)
    return true
  })
})

test('video frame decoder waits for delayed Android start event before seeking', async () => {
  let started = false
  const handlers = {}
  const calls = []
  const decoder = {
    on(eventName, callback) {
      handlers[eventName] = callback
    },
    off(eventName, callback) {
      if (handlers[eventName] === callback) {
        delete handlers[eventName]
      }
    },
    start() {
      calls.push({ type: 'start' })
      setTimeout(() => {
        started = true
        if (handlers.start) {
          handlers.start({ width: 1080, height: 1920 })
        }
      }, 350)
    },
    seek(position) {
      calls.push({ type: 'seek', position, started })
      if (!started) {
        throw new Error('playerSeek failed')
      }
      if (handlers.seek) {
        handlers.seek()
      }
    },
    getFrameData() {
      calls.push({ type: 'frame' })
      return {
        width: 1,
        height: 1,
        data: new Uint8ClampedArray([1, 2, 3, 4])
      }
    },
    stop() {
      calls.push({ type: 'stop' })
    },
    remove() {
      calls.push({ type: 'remove' })
    }
  }

  const result = await decodeVideoFrameAtTime({
    wxApi: {
      createVideoDecoder() {
        return decoder
      }
    },
    source: 'wxfile://tmp/android.mp4',
    timeMs: 5000,
    durationMs: 13000,
    readDelayMs: 0
  })

  assert.equal(result.seekTimeMs, 5000)
  assert.deepEqual(calls.filter((call) => call.type === 'seek'), [
    { type: 'seek', position: 5000, started: true }
  ])
})

test('video frame decoder falls back to nearby seek time when selected frame is empty', async () => {
  let currentSeekTime = 0
  const seekTimes = []
  const decoder = createImmediateDecoder({
    onSeek(position) {
      currentSeekTime = position
      seekTimes.push(position)
    },
    getFrameData() {
      if (currentSeekTime === 3000) {
        return null
      }
      return {
        width: 1,
        height: 1,
        data: new Uint8ClampedArray([1, 2, 3, 4])
      }
    }
  })

  const result = await decodeVideoFrameAtTime({
    wxApi: {
      createVideoDecoder() {
        return decoder
      }
    },
    source: 'wxfile://tmp/clip.mp4',
    timeMs: 3000,
    durationMs: 5000,
    readAttempts: 1,
    readDelayMs: 0
  })

  assert.equal(result.seekTimeMs, 2500)
  assert.deepEqual(seekTimes, [3000, 2500])
})

test('video frame decoder accepts promise based start and seek completion without events', async () => {
  const decoder = {
    on() {},
    off() {},
    start() {
      return Promise.resolve()
    },
    seek() {
      return Promise.resolve()
    },
    getFrameData() {
      return {
        width: 1,
        height: 1,
        data: new Uint8ClampedArray([1, 2, 3, 4])
      }
    },
    stop() {},
    remove() {}
  }

  const result = await decodeVideoFrameAtTime({
    wxApi: {
      createVideoDecoder() {
        return decoder
      }
    },
    source: 'wxfile://tmp/clip.mp4',
    timeMs: 1000,
    durationMs: 5000,
    eventTimeoutMs: 1
  })

  assert.equal(result.seekTimeMs, 1000)
  assert.equal(result.frame.width, 1)
})

test('video frame decoder proceeds when device omits start and seek events', async () => {
  const calls = []
  const decoder = {
    on() {},
    off() {},
    start(options) {
      calls.push({ type: 'start', options })
    },
    seek(position) {
      calls.push({ type: 'seek', position })
    },
    getFrameData() {
      calls.push({ type: 'frame' })
      return {
        width: 1,
        height: 1,
        data: new Uint8ClampedArray([1, 2, 3, 4])
      }
    },
    stop() {
      calls.push({ type: 'stop' })
    },
    remove() {
      calls.push({ type: 'remove' })
    }
  }

  const result = await decodeVideoFrameAtTime({
    wxApi: {
      createVideoDecoder() {
        return decoder
      }
    },
    source: 'wxfile://tmp/android.mp4',
    timeMs: 5000,
    durationMs: 13000,
    eventTimeoutMs: 1
  })

  assert.equal(result.seekTimeMs, 5000)
  assert.deepEqual(calls.map((call) => call.type), ['start', 'seek', 'frame', 'stop', 'remove'])
})

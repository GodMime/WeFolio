const assert = require('node:assert/strict')
const test = require('node:test')

const { createClipboardPromptController } = require('../pages/portfolios/utils/portfolio-hyperlink')

function createClock() {
  let now = 0
  let sequence = 0
  const timers = new Map()
  return {
    now: () => now,
    setTimeout(handler, delay) {
      sequence += 1
      timers.set(sequence, { handler, dueAt: now + delay })
      return sequence
    },
    clearTimeout(id) {
      timers.delete(id)
    },
    advance(milliseconds) {
      now += milliseconds
      let ready = true
      while (ready) {
        ready = false
        const next = Array.from(timers.entries())
          .filter(([, timer]) => timer.dueAt <= now)
          .sort((left, right) => left[1].dueAt - right[1].dueAt)[0]
        if (next) {
          timers.delete(next[0])
          next[1].handler()
          ready = true
        }
      }
    }
  }
}

test('clipboard prompt pauses while the miniapp is hidden and resumes with remaining time', () => {
  const clock = createClock()
  const states = []
  const controller = createClipboardPromptController({
    now: clock.now,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    revealDelayMs: 1700,
    visibleDurationMs: 2500,
    onChange(state) {
      states.push(state)
    }
  })

  controller.copied('请打开抖音粘贴')
  clock.advance(600)
  controller.pause()
  clock.advance(5000)
  assert.equal(states.some((state) => state.visible), false)

  controller.resume()
  clock.advance(1099)
  assert.equal(states.some((state) => state.visible), false)
  clock.advance(1)
  assert.deepEqual(states.at(-1), { visible: true, text: '请打开抖音粘贴' })

  clock.advance(900)
  controller.pause()
  clock.advance(5000)
  assert.equal(states.at(-1).visible, true)
  controller.resume()
  clock.advance(1599)
  assert.equal(states.at(-1).visible, true)
  clock.advance(1)
  assert.deepEqual(states.at(-1), { visible: false, text: '' })
})

test('a newer clipboard action replaces all timers and prompt text from the previous action', () => {
  const clock = createClock()
  const states = []
  const controller = createClipboardPromptController({
    now: clock.now,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    revealDelayMs: 100,
    visibleDurationMs: 200,
    onChange(state) {
      states.push(state)
    }
  })

  controller.copied('第一次')
  clock.advance(50)
  controller.copied('第二次')
  clock.advance(100)
  assert.deepEqual(states.at(-1), { visible: true, text: '第二次' })
  clock.advance(200)
  assert.deepEqual(states.at(-1), { visible: false, text: '' })
})

test('clipboard prompt defaults to 1700ms delay and 2500ms visible duration', () => {
  const clock = createClock()
  const states = []
  const controller = createClipboardPromptController({
    now: clock.now,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    onChange(state) {
      states.push(state)
    }
  })

  controller.copied('请打开小红书粘贴')
  clock.advance(1699)
  assert.equal(states.at(-1).visible, false)
  clock.advance(1)
  assert.deepEqual(states.at(-1), { visible: true, text: '请打开小红书粘贴' })
  clock.advance(2499)
  assert.equal(states.at(-1).visible, true)
  clock.advance(1)
  assert.deepEqual(states.at(-1), { visible: false, text: '' })
})

test('clipboard prompt dispose hides the current prompt and permits a later copy', () => {
  const clock = createClock()
  const states = []
  const controller = createClipboardPromptController({
    now: clock.now,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    revealDelayMs: 100,
    visibleDurationMs: 200,
    onChange(state) {
      states.push(state)
    }
  })

  controller.copied('第一次')
  clock.advance(100)
  assert.deepEqual(states.at(-1), { visible: true, text: '第一次' })

  controller.dispose()
  assert.deepEqual(states.at(-1), { visible: false, text: '' })
  const disposedStateCount = states.length
  clock.advance(1000)
  assert.equal(states.length, disposedStateCount)

  controller.copied('第二次')
  clock.advance(100)
  assert.deepEqual(states.at(-1), { visible: true, text: '第二次' })
  clock.advance(200)
  assert.deepEqual(states.at(-1), { visible: false, text: '' })
})

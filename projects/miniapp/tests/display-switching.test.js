const assert = require('node:assert/strict')
const test = require('node:test')

const {
  DISPLAY_SWITCH_ANIMATION_MS,
  clearDisplaySwitchingTimer,
  markDisplaySwitching
} = require('../pages/portfolios/utils/display-switching')

function createPage() {
  return {
    displaySwitchingTimer: null,
    data: {
      displaySwitchingComponentKey: ''
    },
    setData(patch, callback) {
      Object.assign(this.data, patch)
      if (callback) {
        callback()
      }
    }
  }
}

test('display switching utility marks active component then clears it after animation', () => {
  const originalSetTimeout = global.setTimeout
  const timers = []
  global.setTimeout = (handler, delay) => {
    timers.push({ handler, delay })
    return `timer-${timers.length}`
  }

  try {
    const page = createPage()

    markDisplaySwitching(page, 'c_grid')

    assert.equal(page.data.displaySwitchingComponentKey, 'c_grid')
    assert.equal(timers.length, 1)
    assert.equal(timers[0].delay, DISPLAY_SWITCH_ANIMATION_MS)

    timers[0].handler()

    assert.equal(page.data.displaySwitchingComponentKey, '')
    assert.equal(page.displaySwitchingTimer, null)
  } finally {
    global.setTimeout = originalSetTimeout
  }
})

test('display switching utility clears an existing timer before marking again', () => {
  const originalSetTimeout = global.setTimeout
  const originalClearTimeout = global.clearTimeout
  const timers = []
  const clearedTimers = []
  global.setTimeout = (handler, delay) => {
    timers.push({ handler, delay })
    return `timer-${timers.length}`
  }
  global.clearTimeout = (timerId) => {
    clearedTimers.push(timerId)
  }

  try {
    const page = createPage()

    markDisplaySwitching(page, 'c_grid')
    markDisplaySwitching(page, 'c_list')

    assert.deepEqual(clearedTimers, ['timer-1'])
    assert.equal(page.data.displaySwitchingComponentKey, 'c_list')
    assert.equal(page.displaySwitchingTimer, 'timer-2')

    clearDisplaySwitchingTimer(page)

    assert.deepEqual(clearedTimers, ['timer-1', 'timer-2'])
    assert.equal(page.displaySwitchingTimer, null)
  } finally {
    global.setTimeout = originalSetTimeout
    global.clearTimeout = originalClearTimeout
  }
})

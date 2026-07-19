const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MODULE_PATH = path.join(__dirname, '../utils/maintainer-wechat-session.js')

test('maintainer wechat session module exists before lifecycle tests', () => {
  assert.equal(fs.existsSync(MODULE_PATH), true)
})

test('onShow checks immediately, starts configured timer, and onHide clears it', async () => {
  const { createMaintainerWechatSessionController } = require(MODULE_PATH)
  let checks = 0
  let timerDelay = 0
  let clearedTimer = null
  const controller = createMaintainerWechatSessionController({
    hasToken: () => true,
    wxApi: {
      checkSession({ success }) {
        checks += 1
        success()
      }
    },
    setIntervalFn(callback, delay) {
      timerDelay = delay
      return { callback }
    },
    clearIntervalFn(timer) {
      clearedTimer = timer
    }
  })

  const immediate = controller.onShow(300)
  await immediate

  assert.equal(checks, 1)
  assert.equal(timerDelay, 300000)
  const timer = controller.getTimerForTest()
  controller.onHide()
  assert.equal(clearedTimer, timer)
})

test('failed checks merge one login and refresh promise', async () => {
  const { createMaintainerWechatSessionController } = require(MODULE_PATH)
  let checks = 0
  let logins = 0
  let refreshes = 0
  let releaseRefresh
  const refreshPromise = new Promise((resolve) => {
    releaseRefresh = resolve
  })
  const controller = createMaintainerWechatSessionController({
    hasToken: () => true,
    wxApi: {
      checkSession({ fail }) {
        checks += 1
        fail({ errMsg: 'session expired' })
      },
      login({ success }) {
        logins += 1
        success({ code: 'new-code' })
      }
    },
    refreshRequest(code) {
      refreshes += 1
      assert.equal(code, 'new-code')
      return refreshPromise
    },
    setIntervalFn() {
      return 1
    },
    clearIntervalFn() {}
  })

  const first = controller.checkNow()
  const second = controller.checkNow()
  assert.equal(first, second)
  assert.equal(checks, 1)
  await Promise.resolve()
  assert.equal(logins, 1)
  assert.equal(refreshes, 1)
  releaseRefresh()
  await first
})

test('controller skips all wechat session work without maintainer token', async () => {
  const { createMaintainerWechatSessionController } = require(MODULE_PATH)
  let checks = 0
  const controller = createMaintainerWechatSessionController({
    hasToken: () => false,
    wxApi: {
      checkSession() {
        checks += 1
      }
    },
    setIntervalFn() {
      throw new Error('无 token 时不应启动定时器')
    },
    clearIntervalFn() {}
  })

  await controller.onShow()
  assert.equal(checks, 0)
})

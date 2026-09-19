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
    sessionRequest: async () => ({ authenticated: true }),
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

test('有效本地微信会话仍会修复服务端失效状态，并合并并发检查', async () => {
  const { createMaintainerWechatSessionController } = require(MODULE_PATH)
  let probes = 0
  let logins = 0
  let refreshes = 0
  let resolveProbe
  const controller = createMaintainerWechatSessionController({
    hasToken: () => true,
    wxApi: {
      checkSession({ success }) { success() },
      login({ success }) { logins++; success({ code: 'recovery-code' }) }
    },
    sessionRequest() {
      probes++
      return new Promise(resolve => { resolveProbe = resolve })
    },
    async refreshRequest(code) {
      assert.equal(code, 'recovery-code')
      refreshes++
    }
  })

  const first = controller.checkNow()
  assert.strictEqual(controller.checkNow(), first)
  await Promise.resolve()
  assert.equal(probes, 1)
  resolveProbe({ authenticated: true, wechatSessionRefreshRequired: true })
  await first
  assert.equal(logins, 1)
  assert.equal(refreshes, 1)
})

test('服务端会话探测失败不会触发重新登录，下一次检查仍能恢复', async () => {
  const { createMaintainerWechatSessionController } = require(MODULE_PATH)
  let probes = 0
  let logins = 0
  const controller = createMaintainerWechatSessionController({
    hasToken: () => true,
    wxApi: {
      checkSession({ success }) { success() },
      login() { logins++ }
    },
    async sessionRequest() {
      if (++probes === 1) throw new Error('temporary network failure')
      return { authenticated: true, wechatSessionRefreshRequired: false }
    }
  })

  await assert.rejects(controller.checkNow(), /temporary network failure/)
  await controller.checkNow()
  assert.equal(probes, 2)
  assert.equal(logins, 0)
})

test('旧服务端、未登录响应及探测中退出登录均不重新建立维护者会话', async () => {
  const { createMaintainerWechatSessionController } = require(MODULE_PATH)
  for (const response of [
    { authenticated: true },
    { authenticated: false, wechatSessionRefreshRequired: true },
    { authenticated: true, wechatSessionRefreshRequired: false }
  ]) {
    const controller = createMaintainerWechatSessionController({
      hasToken: () => true,
      wxApi: {
        checkSession({ success }) { success() },
        login() { assert.fail('无需刷新时不能调用登录') }
      },
      sessionRequest: async () => response
    })
    await controller.checkNow()
  }
  let loggedIn = true
  const controller = createMaintainerWechatSessionController({
    hasToken: () => loggedIn,
    wxApi: {
      checkSession({ success }) { success() },
      login() { assert.fail('已退出登录时不能重建会话') }
    },
    async sessionRequest() {
      loggedIn = false
      return { authenticated: true, wechatSessionRefreshRequired: true }
    }
  })
  await controller.checkNow()
})

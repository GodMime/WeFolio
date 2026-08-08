const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

function flushPromises() {
  return new Promise((resolve) => setImmediate(resolve))
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function applyData(target, patch) {
  Object.keys(patch).forEach((key) => {
    target[key] = patch[key]
  })
}

function loadRechargePage(fakeRequest, wxOverrides = {}) {
  const pagePath = path.join(__dirname, '../pages/recharge/recharge.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const sessionPath = path.join(__dirname, '../utils/session.js')
  const requestCacheKey = require.resolve(requestPath)
  const sessionCacheKey = require.resolve(sessionPath)
  const originalRequest = require.cache[requestCacheKey]
  const originalSession = require.cache[sessionCacheKey]
  delete require.cache[require.resolve(pagePath)]
  require.cache[requestCacheKey] = {
    id: requestPath,
    filename: requestPath,
    loaded: true,
    exports: { request: fakeRequest }
  }
  require.cache[sessionCacheKey] = {
    id: sessionPath,
    filename: sessionPath,
    loaded: true,
    exports: {
      handleMaintainerAuthRequired() {},
      hasLocalToken() { return true },
      refreshMaintainerWechatSession() { return Promise.resolve() }
    }
  }

  let definition
  global.Page = (value) => { definition = value }
  global.wx = Object.assign({
    navigateTo() {},
    redirectTo() {},
    showToast() {}
  }, wxOverrides)
  require(pagePath)
  delete global.Page
  if (originalRequest) require.cache[requestCacheKey] = originalRequest
  else delete require.cache[requestCacheKey]
  if (originalSession) require.cache[sessionCacheKey] = originalSession
  else delete require.cache[sessionCacheKey]

  return Object.assign({}, definition, {
    data: clone(definition.data),
    setData(patch, callback) {
      applyData(this.data, patch)
      if (callback) callback()
    }
  })
}

test('prevents duplicate payment submission while already paying', async () => {
  const requests = []
  const page = loadRechargePage((options) => {
    requests.push(options)
    return Promise.resolve({})
  })
  page.data.paying = true
  page.data.rechargeData.selectedPackageId = 3

  await page.handlePay()

  assert.equal(requests.length, 0)
})

test('creates order, invokes WeChat payment, syncs paid result and refreshes balance', async () => {
  const requests = []
  const toasts = []
  const page = loadRechargePage((options) => {
    requests.push(options)
    if (options.method === 'POST' && options.url === '/api/mine/recharges/orders') {
      return Promise.resolve({
        merchantOrderNo: 'WFR20260717120000123456789012',
        mode: 'short_series_coin',
        signData: '{"env":0}',
        paySig: 'pay-sign',
        signature: 'user-sign'
      })
    }
    if (options.url.endsWith('/sync')) {
      return Promise.resolve({ status: 'PAID', confirmed: true, balance: 806 })
    }
    return Promise.resolve({ balance: 806, packages: [] })
  }, {
    requestVirtualPayment(options) {
      assert.equal(options.signData, '{"env":0}')
      options.success()
    },
    showToast(options) {
      toasts.push(options.title)
    }
  })
  page.data.rechargeData.selectedPackageId = 3

  await page.handlePay()
  await flushPromises()

  assert.deepEqual(requests.map((item) => [item.method || 'GET', item.url]), [
    ['POST', '/api/mine/recharges/orders'],
    ['POST', '/api/mine/recharges/orders/WFR20260717120000123456789012/sync'],
    ['GET', '/api/mine/recharges']
  ])
  assert.deepEqual(requests[0].data, { packageId: 3 })
  assert.equal(page.data.paying, false)
  assert.equal(page.data.syncing, false)
  assert.ok(toasts.includes('充值成功，积分已到账'))
})

test('keeps order pending and does not sync when user cancels payment', async () => {
  const requests = []
  const toasts = []
  const page = loadRechargePage((options) => {
    requests.push(options)
    return Promise.resolve({
      merchantOrderNo: 'WFR20260717120000123456789012',
      mode: 'short_series_coin',
      signData: '{"env":0}',
      paySig: 'pay-sign',
      signature: 'user-sign'
    })
  }, {
    requestVirtualPayment(options) {
      options.fail({ errMsg: 'requestVirtualPayment:fail cancel' })
    },
    showToast(options) {
      toasts.push(options.title)
    }
  })
  page.data.rechargeData.selectedPackageId = 3

  await page.handlePay()

  assert.equal(requests.length, 1)
  assert.deepEqual(toasts, ['已取消支付'])
  assert.equal(page.data.paying, false)
  assert.equal(page.data.syncing, false)
})

test('keeps confirmed balance visible when post-payment package refresh fails', async () => {
  const toasts = []
  const page = loadRechargePage((options) => {
    if (options.url === '/api/mine/recharges/orders') {
      return Promise.resolve({
        merchantOrderNo: 'WFR20260717120000123456789012',
        mode: 'short_series_coin',
        signData: '{"env":0}',
        paySig: 'pay-sign',
        signature: 'user-sign'
      })
    }
    if (options.url.endsWith('/sync')) {
      return Promise.resolve({ status: 'PAID', confirmed: true, balance: 806 })
    }
    return Promise.reject(new Error('套餐刷新暂时失败'))
  }, {
    requestVirtualPayment(options) {
      options.success()
    },
    showToast(options) {
      toasts.push(options.title)
    }
  })
  page.data.rechargeData.selectedPackageId = 3

  await page.handlePay()

  assert.equal(page.data.rechargeData.balance, 806)
  assert.equal(page.data.rechargeData.balanceText, '806')
  assert.equal(page.data.errorMessage, '')
  assert.ok(toasts.includes('充值成功，积分已到账'))
})

test('keeps payment lock while virtual payment session refresh retry is in flight', async () => {
  let paymentInvocation = 0
  let completeRetriedPayment
  const page = loadRechargePage((options) => {
    if (options.url.endsWith('/sync')) {
      return Promise.resolve({ status: 'PENDING', confirmed: false })
    }
    return Promise.resolve({
      merchantOrderNo: `WFR-RETRY-${paymentInvocation}`,
      mode: 'short_series_coin',
      signData: '{}',
      paySig: 'pay-sign',
      signature: 'user-sign'
    })
  }, {
    login(options) {
      options.success({ code: 'fresh-session-code' })
    },
    requestVirtualPayment(options) {
      paymentInvocation += 1
      if (paymentInvocation === 1) {
        options.fail({ errCode: -15007, errMsg: 'session invalid' })
        return
      }
      completeRetriedPayment = options.success
    }
  })
  page.data.rechargeData.selectedPackageId = 3

  const paymentPromise = page.handlePay()
  await flushPromises()
  await flushPromises()

  assert.equal(paymentInvocation, 2)
  assert.equal(page.data.paying, true)
  completeRetriedPayment()
  await paymentPromise
  assert.equal(page.data.paying, false)
  assert.equal(page.data.syncing, false)
})

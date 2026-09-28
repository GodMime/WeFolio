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
    login(options) { options.success({ code: 'current-device-code' }) },
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
  assert.deepEqual(requests[0].data, { packageId: 3, code: 'current-device-code' })
  assert.equal(page.data.paying, false)
  assert.equal(page.data.syncing, false)
  assert.ok(toasts.includes('充值成功，积分已到账'))
})

test('退款终态展示服务端文案或已退款兜底，结束同步且不继续查询', async () => {
  for (const statusText of ['该订单已退款', undefined]) {
    const requests = []
    const toasts = []
    const page = loadRechargePage((options) => {
      requests.push(options)
      return Promise.resolve(options.url.endsWith('/sync')
        ? { status: 'REFUNDED', statusText }
        : { merchantOrderNo: 'WFR-refunded' })
    }, {
      requestVirtualPayment: (options) => options.success(),
      showToast: (options) => toasts.push(options.title)
    })
    page.data.rechargeData.selectedPackageId = 3

    await page.handlePay()
    await flushPromises()

    assert.deepEqual(requests.map((item) => item.url), [
      '/api/mine/recharges/orders', '/api/mine/recharges/orders/WFR-refunded/sync'
    ])
    assert.deepEqual(toasts, [statusText || '已退款'])
    assert.equal(page.data.paying, false)
    assert.equal(page.data.syncing, false)
  }
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

for (const failure of [{ errCode: -15005 }, { err_code: '-15007' }]) {
  test(`支付签名或会话失效只重试一次且每单使用新 code ${JSON.stringify(failure)}`, async () => {
    const requests = []
    const toasts = []
    let loginCount = 0
    let paymentCount = 0
    const page = loadRechargePage((options) => {
      requests.push(options)
      return Promise.resolve({ merchantOrderNo: `order-${requests.length}` })
    }, {
      login(options) { options.success({ code: `device-code-${++loginCount}` }) },
      requestVirtualPayment(options) {
        paymentCount += 1
        options.fail(Object.assign({ errMsg: 'requestVirtualPayment:fail SIGNATURE_INVALID' }, failure))
      },
      showToast(options) { toasts.push(options.title) }
    })
    page.data.rechargeData.selectedPackageId = 3

    await page.handlePay()

    assert.equal(loginCount, 2)
    assert.equal(paymentCount, 2)
    assert.deepEqual(requests.map(item => item.data), [
      { packageId: 3, code: 'device-code-1' },
      { packageId: 3, code: 'device-code-2' }
    ])
    assert.equal(toasts.length, 1)
    assert.equal(page.data.paying, false)
  })
}

test('获取支付 code 期间锁住按钮，登录失败不建单也不调支付', async () => {
  const requests = []
  let loginCallbacks
  let paymentCount = 0
  const page = loadRechargePage(options => { requests.push(options) }, {
    login(options) { loginCallbacks = options },
    requestVirtualPayment() { paymentCount += 1 }
  })
  page.data.rechargeData.selectedPackageId = 3
  const paying = page.handlePay()
  assert.equal(page.data.paying, true)
  await page.handlePay()
  loginCallbacks.fail({ errMsg: 'login:fail' })
  await paying
  assert.equal(requests.length, 0)
  assert.equal(paymentCount, 0)
  assert.equal(page.data.paying, false)
})

test('应用签名错误不按用户会话错误重试', async () => {
  let loginCount = 0
  let paymentCount = 0
  const page = loadRechargePage(() => Promise.resolve({ merchantOrderNo: 'order-app-signature' }), {
    login(options) { loginCount += 1; options.success({ code: 'device-code' }) },
    requestVirtualPayment(options) { paymentCount += 1; options.fail({ errCode: -15006 }) }
  })
  page.data.rechargeData.selectedPackageId = 3
  await page.handlePay()
  assert.equal(loginCount, 1)
  assert.equal(paymentCount, 1)
})

test('重试获取新 code 期间持续锁定，失败后恢复按钮且不产生第二笔订单', async () => {
  const requests = []
  const toasts = []
  let loginCount = 0
  let retryLogin
  const page = loadRechargePage(options => {
    requests.push(options)
    return Promise.resolve({ merchantOrderNo: 'failed-order' })
  }, {
    login(options) {
      loginCount += 1
      if (loginCount === 1) options.success({ code: 'first-code' })
      else retryLogin = options
    },
    requestVirtualPayment(options) { options.fail({ errCode: -15005 }) },
    showToast(options) { toasts.push(options.title) }
  })
  page.data.rechargeData.selectedPackageId = 3
  const paying = page.handlePay()
  await flushPromises()
  assert.equal(page.data.paying, true)
  await page.handlePay()
  assert.equal(loginCount, 2)
  retryLogin.fail({ errMsg: 'login:fail' })
  await paying
  assert.equal(requests.length, 1)
  assert.equal(toasts.length, 1)
  assert.equal(page.data.paying, false)
})

test('签名失败后新单支付成功，仅同步新订单且不重复发起支付', async () => {
  const requests = []
  let loginCount = 0
  let paymentCount = 0
  const page = loadRechargePage(options => {
    requests.push(options)
    return Promise.resolve(options.url.endsWith('/sync')
      ? { status: 'PENDING', confirmed: false }
      : { merchantOrderNo: `order-${loginCount}` })
  }, {
    login(options) { options.success({ code: `code-${++loginCount}` }) },
    requestVirtualPayment(options) {
      paymentCount += 1
      if (paymentCount === 1) options.fail({ errCode: -15005 })
      else options.success()
    }
  })
  page.data.rechargeData.selectedPackageId = 3
  await page.handlePay()
  assert.deepEqual(requests.map(item => item.url), [
    '/api/mine/recharges/orders', '/api/mine/recharges/orders', '/api/mine/recharges/orders/order-2/sync'
  ])
  assert.equal(paymentCount, 2)
  assert.equal(page.data.paying, false)
  assert.equal(page.data.syncing, false)
})

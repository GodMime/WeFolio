const assert = require('node:assert/strict')
const test = require('node:test')

const {
  TOKEN_STORAGE_KEY,
  setToken,
  clearToken,
  handleMaintainerAuthRequired,
  precheckMaintainerWechatLogin,
  maintainerWechatLogin,
  logoutMaintainer
} = require('../utils/session')

test('维护者登录、令牌清除与401不影响同一访客的上下文和未确认停留', async () => {
  const { visitActivityLifecycle, VISIT_ACTIVITY_STORAGE_KEY } = require('../utils/visit-activity-lifecycle')
  for (const packageName of ['portfolios', 'team-portfolios']) {
    const { createVisitActivityContext } = require(`../pages/${packageName}/utils/visit-activity`)
    const storage = {}
    let now = 0
    const wxApi = { getStorageSync: key => storage[key], setStorageSync: (key, value) => { storage[key] = value },
      removeStorageSync: key => { delete storage[key] }, showToast() {}, redirectTo() {} }
    const portfolioType = packageName === 'portfolios' ? 'PERSONAL' : 'TEAM'
    const requestVisit = portfolioType === 'PERSONAL'
      ? require('../pages/portfolios/utils/visitor-session').requestWithVisitorSessionRefresh
      : (requestOptions, options) => require('../pages/team-portfolios/utils/team-visitor-session')
        .requestWithTeamVisitorSessionRefresh({ ...options, requestOptions })
    const context = createVisitActivityContext({ shareCode: 'another-owner', portfolioType, wxApi, now: () => now,
      setTimer: () => 1, clearTimer() {}, sendActivity: async () => { throw new Error('offline') } })
    context.acceptSession({ visitorKey: 'same-visitor', trackingSessionId: 7, trackingActiveDurationMs: 0 })
    context.setDisplayable(true); context.show({})
    try {
      now = 5000; await context.flush()
      const pending = JSON.parse(JSON.stringify(storage[VISIT_ACTIVITY_STORAGE_KEY]))
      assert.equal(pending[0].activeDurationMs, 5000)
      for (const action of [() => setToken('maintainer-token', wxApi), () => clearToken(wxApi), () => handleMaintainerAuthRequired('未登录', wxApi)]) {
        action()
        assert.equal(context.isInvalid(), false)
        assert.deepEqual(storage[VISIT_ACTIVITY_STORAGE_KEY], pending)
        assert.equal(visitActivityLifecycle.findContext(portfolioType, 'another-owner'), context)
        const url = portfolioType === 'PERSONAL' ? '/api/visitor/portfolios/another-owner/events' : '/api/visitor/team-portfolios/another-owner/events'
        assert.deepEqual(await requestVisit({ url, authMode: 'visitor' }, {
          shareCode: 'another-owner', browserContext: context, requestFn: async () => ({ accepted: true })
        }), { accepted: true })
      }
      now = 7000
      assert.equal(context.snapshot().activeDurationMs, 7000)
      context.beginRecovery()
      context.acceptSession({ visitorKey: 'same-visitor', trackingSessionId: 7, trackingActiveDurationMs: 0 })
      assert.equal(context.isInvalid(), false)
      assert.equal(context.snapshot().activeDurationMs, 7000)
      // 只有服务端重新确认的访客身份变化才失效旧会话并移除其补报。
      context.acceptSession({ visitorKey: 'different-visitor', trackingSessionId: 7, trackingActiveDurationMs: 0 })
      assert.equal(context.isInvalid(), true)
      assert.deepEqual(storage[VISIT_ACTIVITY_STORAGE_KEY], [])
    } finally { context.invalidate() }
  }
})

test('maintainer wechat login precheck uses dedicated auth endpoint', async () => {
  let capturedOptions = null
  global.wx = {
    request(options) {
      capturedOptions = options
      options.success({
        statusCode: 200,
        data: {
          success: true,
          data: { phoneAuthorizationRequired: true }
        }
      })
    },
    getStorageSync() {
      return ''
    }
  }

  try {
    const data = await precheckMaintainerWechatLogin('precheck-code')

    assert.equal(
      capturedOptions.url,
      'https://api.we-folio.dingchenyong.top/api/auth/maintainer/wechat-login/precheck'
    )
    assert.equal(capturedOptions.method, 'POST')
    assert.deepEqual(capturedOptions.data, { code: 'precheck-code' })
    assert.deepEqual(data, { phoneAuthorizationRequired: true })
  } finally {
    delete global.wx
  }
})

test('maintainer wechat login uses dedicated auth endpoint', async () => {
  let capturedOptions = null
  global.wx = {
    request(options) {
      capturedOptions = options
      options.success({
        statusCode: 200,
        data: {
          success: true,
          data: { token: 'wf-dev-user-7' }
        }
      })
    },
    getStorageSync() {
      return ''
    }
  }

  try {
    const data = await maintainerWechatLogin({ code: 'wx-code' })

    assert.equal(capturedOptions.url, 'https://api.we-folio.dingchenyong.top/api/auth/maintainer/wechat-login')
    assert.equal(capturedOptions.method, 'POST')
    assert.deepEqual(capturedOptions.data, { code: 'wx-code' })
    assert.deepEqual(data, { token: 'wf-dev-user-7' })
  } finally {
    delete global.wx
  }
})

test('maintainer auth required handler clears maintainer token, shows toast and redirects to login', () => {
  const storage = {
    [TOKEN_STORAGE_KEY]: 'wf-dev-user-7'
  }
  const calls = {
    toast: null,
    redirect: null,
    removedKey: null
  }
  const wxApi = {
    removeStorageSync(key) {
      calls.removedKey = key
      delete storage[key]
    },
    showToast(options) {
      calls.toast = options
    },
    redirectTo(options) {
      calls.redirect = options
    }
  }

  handleMaintainerAuthRequired('未登录', wxApi)

  assert.equal(calls.removedKey, TOKEN_STORAGE_KEY)
  assert.equal(storage[TOKEN_STORAGE_KEY], undefined)
  assert.deepEqual(calls.toast, {
    title: '未登录',
    icon: 'none'
  })
  assert.deepEqual(calls.redirect, {
    url: '/pages/login/login'
  })
})

test('maintainer logout calls active token revocation endpoint', async () => {
  let capturedOptions = null
  global.wx = {
    request(options) {
      capturedOptions = options
      options.success({ statusCode: 200, data: { success: true } })
    },
    getStorageSync() {
      return 'maintainer-token'
    }
  }

  try {
    await logoutMaintainer()

    assert.equal(capturedOptions.url, 'https://api.we-folio.dingchenyong.top/api/auth/logout')
    assert.equal(capturedOptions.method, 'POST')
    assert.equal(capturedOptions.header.Authorization, 'Bearer maintainer-token')
  } finally {
    delete global.wx
  }
})

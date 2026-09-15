const assert = require('node:assert/strict')
const test = require('node:test')

const {
  DEFAULT_BASE_URL,
  VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY,
  VISITOR_TOKEN_STORAGE_KEY,
  createRequestClient
} = require('../utils/request')

test('default backend base url uses https production domain', () => {
  assert.equal(DEFAULT_BASE_URL, 'https://api.we-folio.dingchenyong.top')
})

test('request client injects bearer token and unwraps successful response data', async () => {
  let capturedOptions
  const wxApi = {
    request(options) {
      capturedOptions = options
      options.success({
        statusCode: 200,
        data: {
          success: true,
          data: { ok: true }
        }
      })
    }
  }
  const client = createRequestClient({
    baseUrl: 'http://api.test',
    wxApi,
    getToken: () => 'wf-dev-user-7'
  })

  const data = await client.request({ url: '/api/mine/dashboard' })

  assert.equal(capturedOptions.url, 'http://api.test/api/mine/dashboard')
  assert.equal(capturedOptions.header.Authorization, 'Bearer wf-dev-user-7')
  assert.equal(capturedOptions.header['X-WeFolio-Capabilities'], undefined)
  assert.deepEqual(data, { ok: true })
})

test('request client supports none and visitor auth modes', async () => {
  const capturedOptions = []
  const wxApi = {
    request(options) {
      capturedOptions.push(options)
      options.success({
        statusCode: 200,
        data: {
          success: true,
          data: { ok: true }
        }
      })
    }
  }
  const client = createRequestClient({
    baseUrl: 'http://api.test',
    wxApi,
    getToken: () => 'wf-maintainer-token',
    getVisitorToken: () => 'wf-visitor-v1.token'
  })

  await client.request({ url: '/api/visitor/portfolios/PF001/open', authMode: 'none' })
  await client.request({ url: '/api/visitor/portfolios/PF001/events', authMode: 'visitor' })
  await client.request({ url: '/api/mine/dashboard' })

  assert.equal(Object.hasOwn(capturedOptions[0].header, 'Authorization'), false)
  assert.equal(capturedOptions[1].header.Authorization, 'Bearer wf-visitor-v1.token')
  assert.equal(capturedOptions[2].header.Authorization, 'Bearer wf-maintainer-token')
})

test('visitor token storage key is exported for visitor login response persistence', () => {
  assert.equal(VISITOR_TOKEN_STORAGE_KEY, 'wefolio_visitor_token')
})

test('request client clears expired visitor token and omits authorization header', async () => {
  let capturedOptions
  const removedKeys = []
  const wxApi = {
    getStorageSync(key) {
      if (key === VISITOR_TOKEN_STORAGE_KEY) {
        return 'wf-visitor-v1.expired'
      }
      if (key === VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY) {
        return Date.now() - 1000
      }
      return ''
    },
    removeStorageSync(key) {
      removedKeys.push(key)
    },
    request(options) {
      capturedOptions = options
      options.success({
        statusCode: 200,
        data: {
          success: true,
          data: { ok: true }
        }
      })
    }
  }
  const client = createRequestClient({
    baseUrl: 'http://api.test',
    wxApi
  })

  await client.request({ url: '/api/visitor/portfolios/PF001/events', authMode: 'visitor' })

  assert.equal(Object.hasOwn(capturedOptions.header, 'Authorization'), false)
  assert.deepEqual(removedKeys, [
    VISITOR_TOKEN_STORAGE_KEY,
    VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY
  ])
})

test('request client clears visitor token and reports visitor auth mode on 401', async () => {
  const removedKeys = []
  const wxApi = {
    request(options) {
      options.success({
        statusCode: 401,
        data: {
          success: false,
          message: '访客未登录'
        }
      })
    }
  }
  const client = createRequestClient({
    baseUrl: 'http://api.test',
    wxApi,
    getVisitorToken: () => 'wf-visitor-v1.stale',
    clearVisitorToken() {
      removedKeys.push(VISITOR_TOKEN_STORAGE_KEY, VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY)
    }
  })

  await assert.rejects(
    () => client.request({ url: '/api/visitor/portfolios/PF001/events', authMode: 'visitor' }),
    (error) => {
      assert.equal(error.authRequired, true)
      assert.equal(error.authMode, 'visitor')
      assert.equal(error.message, '访客未登录')
      return true
    }
  )
  assert.deepEqual(removedKeys, [
    VISITOR_TOKEN_STORAGE_KEY,
    VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY
  ])
})

for (const scenario of [
  { name: '旧令牌请求迟到', initialToken: 'expired-token', currentToken: 'new-token' },
  { name: '无令牌请求迟到', initialToken: '', currentToken: 'new-token' },
  { name: '显式使用其他令牌', initialToken: 'current-token', currentToken: 'current-token', authorization: 'Bearer other-token' }
]) {
  test(`访客401只清理本次使用的令牌：${scenario.name}`, async () => {
    const storage = { [VISITOR_TOKEN_STORAGE_KEY]: scenario.initialToken }
    let pending
    const client = createRequestClient({
      wxApi: {
        getStorageSync: key => storage[key],
        removeStorageSync: key => { delete storage[key] },
        request: options => { pending = options }
      }
    })
    const promise = client.request({
      url: '/api/visitor/portfolios/PF001/events',
      authMode: 'visitor',
      ...(scenario.authorization ? { header: { Authorization: scenario.authorization } } : {})
    })
    // 模拟请求在途期间另一请求完成登录刷新，令牌与有效期必须一起保留。
    storage[VISITOR_TOKEN_STORAGE_KEY] = scenario.currentToken
    const expiresAt = Date.now() + 60000
    storage[VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY] = expiresAt
    pending.success({ statusCode: 401, data: { message: '访客未登录' } })

    await assert.rejects(promise, error => error.authRequired && error.authMode === 'visitor')
    assert.equal(storage[VISITOR_TOKEN_STORAGE_KEY], scenario.currentToken)
    assert.equal(storage[VISITOR_TOKEN_EXPIRES_AT_STORAGE_KEY], expiresAt)
  })
}

test('request client omits empty GET query parameters', async () => {
  let capturedOptions
  const wxApi = {
    request(options) {
      capturedOptions = options
      options.success({
        statusCode: 200,
        data: {
          success: true,
          data: { ok: true }
        }
      })
    }
  }
  const client = createRequestClient({
    baseUrl: 'http://api.test',
    wxApi,
    getToken: () => ''
  })

  await client.request({
    url: '/api/mine/works',
    data: {
      keyword: '',
      tagId: undefined,
      page: 1,
      pageSize: 20
    }
  })

  assert.deepEqual(capturedOptions.data, {
    page: 1,
    pageSize: 20
  })
})

test('request client marks 401 responses as auth required', async () => {
  const wxApi = {
    request(options) {
      options.success({
        statusCode: 401,
        data: {
          success: false,
          message: '未登录'
        }
      })
    }
  }
  const client = createRequestClient({
    baseUrl: 'http://api.test',
    wxApi,
    getToken: () => ''
  })

  await assert.rejects(
    () => client.request({ url: '/api/auth/session', requireAuth: false }),
    (error) => {
      assert.equal(error.authRequired, true)
      assert.equal(error.message, '未登录')
      return true
    }
  )
})

test('request client attaches structured backend failure data to the error', async () => {
  const wxApi = {
    request(options) {
      options.success({
        statusCode: 400,
        data: {
          success: false,
          message: '作品集之间不能循环跳转',
          data: {
            errorCode: 'PORTFOLIO_HYPERLINK_CYCLE',
            componentKey: 'c_hyperlink_2',
            targetPortfolioId: 789
          }
        }
      })
    }
  }
  const client = createRequestClient({ baseUrl: 'http://api.test', wxApi, getToken: () => '' })

  await assert.rejects(
    () => client.request({ url: '/api/mine/portfolios/88/draft', method: 'PUT' }),
    (error) => {
      assert.equal(error.message, '作品集之间不能循环跳转')
      assert.deepEqual(error.data, {
        errorCode: 'PORTFOLIO_HYPERLINK_CYCLE',
        componentKey: 'c_hyperlink_2',
        targetPortfolioId: 789
      })
      assert.equal(error.componentKey, 'c_hyperlink_2')
      return true
    }
  )
})

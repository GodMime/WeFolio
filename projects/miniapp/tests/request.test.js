const assert = require('node:assert/strict')
const test = require('node:test')

const { DEFAULT_BASE_URL, createRequestClient } = require('../utils/request')

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
  assert.deepEqual(data, { ok: true })
})

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

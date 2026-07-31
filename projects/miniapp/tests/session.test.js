const assert = require('node:assert/strict')
const test = require('node:test')

const {
  TOKEN_STORAGE_KEY,
  handleMaintainerAuthRequired,
  precheckMaintainerWechatLogin,
  maintainerWechatLogin
} = require('../utils/session')

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

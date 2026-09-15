const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function restoreGlobal(name, originalValue) {
  if (originalValue === undefined) {
    delete global[name]
    return
  }
  global[name] = originalValue
}

function loadLoginPage(options = {}) {
  const pagePath = path.join(__dirname, '../pages/login/login.js')
  const sessionPath = path.join(__dirname, '../utils/session.js')
  const pageCacheKey = require.resolve(pagePath)
  const sessionCacheKey = require.resolve(sessionPath)
  const originalSessionCache = require.cache[sessionCacheKey]
  const originalPage = global.Page
  const originalWx = global.wx
  const submittedPayloads = []
  const requestCalls = []
  const toastCalls = []
  const redirectCalls = []
  const dataPatches = []
  const storageReads = []
  const loginCodes = ['wx-submit-code']
  let pageDefinition

  require.cache[sessionCacheKey] = {
    id: sessionPath,
    filename: sessionPath,
    loaded: true,
    exports: {
      setToken() {},
      async precheckMaintainerWechatLogin() {
        return { phoneAuthorizationRequired: false }
      },
      async maintainerWechatLogin(payload) {
        submittedPayloads.push(payload)
        if (options.loginError) {
          throw options.loginError
        }
        return { token: 'maintainer-token' }
      }
    }
  }
  global.Page = (definition) => {
    pageDefinition = definition
  }
  global.wx = {
    login({ success }) {
      success({ code: loginCodes.shift() })
    },
    pluginLogin({ success }) {
      success({ code: 'plugin-code' })
    },
    request(requestOptions) {
      requestCalls.push(requestOptions)
    },
    getStorageSync(key) {
      storageReads.push(key)
      return 'stored-token'
    },
    redirectTo(redirectOptions) {
      redirectCalls.push(redirectOptions)
    },
    showToast(toastOptions) {
      toastCalls.push(toastOptions)
    }
  }
  delete require.cache[pageCacheKey]
  require(pagePath)

  const page = Object.assign({}, pageDefinition, {
    data: clone(pageDefinition.data),
    setData(patch) {
      dataPatches.push(patch)
      Object.assign(this.data, patch)
    }
  })
  return {
    page,
    submittedPayloads,
    requestCalls,
    toastCalls,
    redirectCalls,
    dataPatches,
    storageReads,
    async respondConfig(data, statusCode = 200) {
      requestCalls[0].success({ statusCode, data: { success: true, message: 'ok', data } })
      await Promise.resolve()
    },
    cleanup() {
      restoreGlobal('Page', originalPage)
      restoreGlobal('wx', originalWx)
      delete require.cache[pageCacheKey]
      if (originalSessionCache) {
        require.cache[sessionCacheKey] = originalSessionCache
      } else {
        delete require.cache[sessionCacheKey]
      }
    }
  }
}

test('referral share keeps maintainer tab when server defaults to experience', async () => {
  const harness = loadLoginPage()
  try {
    let precheckCalls = 0
    harness.page.runWechatLoginPrecheck = () => {
      precheckCalls += 1
    }

    harness.page.onLoad({
      referralCode: encodeURIComponent('  WF23456789ABCDEFG  ')
    })

    assert.equal(harness.page.data.activeTab, 'maintainer')
    assert.equal(harness.page.data.referralCode, 'WF23456789ABCDEF')
    assert.equal(precheckCalls, 1)
    await harness.respondConfig({ defaultTab: 'experience' })
    assert.equal(harness.page.data.activeTab, 'maintainer')
  } finally {
    harness.cleanup()
  }
})

test('ordinary login entry starts on experience and queries config without a token', () => {
  const harness = loadLoginPage()
  try {
    let precheckCalls = 0
    harness.page.runWechatLoginPrecheck = () => {
      precheckCalls += 1
    }

    harness.page.onLoad({})

    assert.equal(harness.page.data.activeTab, 'experience')
    assert.equal(harness.page.data.referralCode, '')
    assert.equal(precheckCalls, 1)
    assert.equal(harness.requestCalls.length, 1)
    assert.equal(harness.requestCalls[0].url, 'https://api.we-folio.dingchenyong.top/api/auth/login-page-config')
    assert.equal(harness.requestCalls[0].method, 'GET')
    assert.equal(harness.requestCalls[0].header.Authorization, undefined)
    assert.deepEqual(harness.storageReads, [])
  } finally {
    harness.cleanup()
  }
})

test('malformed referral share uses ordinary configurable experience default', async () => {
  const harness = loadLoginPage()
  try {
    let precheckCalls = 0
    harness.page.runWechatLoginPrecheck = () => {
      precheckCalls += 1
    }

    harness.page.onLoad({ referralCode: '%E0%A4%A' })

    assert.equal(harness.page.data.activeTab, 'experience')
    assert.equal(harness.page.data.referralCode, '')
    assert.equal(precheckCalls, 1)
    await harness.respondConfig({ defaultTab: 'maintainer' })
    assert.equal(harness.page.data.activeTab, 'maintainer')
  } finally {
    harness.cleanup()
  }
})

for (const defaultTab of ['experience', 'maintainer']) {
  test(`ordinary login applies server default ${defaultTab}`, async () => {
    const harness = loadLoginPage()
    try {
      harness.page.runWechatLoginPrecheck = () => {}
      harness.page.onLoad()

      await harness.respondConfig({ defaultTab })

      assert.equal(harness.page.data.activeTab, defaultTab)
      assert.deepEqual(harness.toastCalls, [])
    } finally {
      harness.cleanup()
    }
  })
}

for (const [name, response, statusCode] of [
  ['missing endpoint', { defaultTab: 'maintainer' }, 404],
  ['server failure', { defaultTab: 'maintainer' }, 500],
  ['missing field', {}, 200],
  ['empty response', null, 200],
  ['unknown tab', { defaultTab: 'login' }, 200],
  ['non-string tab', { defaultTab: true }, 200]
]) {
  test(`login config falls back silently to experience for ${name}`, async () => {
    const harness = loadLoginPage()
    try {
      harness.page.runWechatLoginPrecheck = () => {}
      harness.page.onLoad()

      await harness.respondConfig(response, statusCode)

      assert.equal(harness.page.data.activeTab, 'experience')
      assert.deepEqual(harness.toastCalls, [])
      assert.deepEqual(harness.redirectCalls, [])
    } finally {
      harness.cleanup()
    }
  })
}

test('login config network failure leaves both login tabs usable without a toast', async () => {
  const harness = loadLoginPage()
  try {
    harness.page.runWechatLoginPrecheck = () => {}
    harness.page.onLoad()

    harness.requestCalls[0].fail({ errMsg: 'request:fail' })
    await Promise.resolve()

    assert.equal(harness.page.data.activeTab, 'experience')
    assert.deepEqual(harness.toastCalls, [])
    harness.page.handleTabTap({ currentTarget: { dataset: { tab: 'maintainer' } } })
    assert.equal(harness.page.data.activeTab, 'maintainer')
    harness.page.handleTabTap({ currentTarget: { dataset: { tab: 'experience' } } })
    harness.page.handleExperienceTap()
    assert.deepEqual(harness.redirectCalls, [{ url: '/pages/mock/index/index' }])
  } finally {
    harness.cleanup()
  }
})

for (const [selectedTab, defaultTab] of [
  ['experience', 'maintainer'],
  ['maintainer', 'experience']
]) {
  test(`late config does not replace user-selected ${selectedTab} tab`, async () => {
    const harness = loadLoginPage()
    try {
      harness.page.runWechatLoginPrecheck = () => {}
      harness.page.onLoad()
      harness.page.handleTabTap({ currentTarget: { dataset: { tab: selectedTab } } })

      await harness.respondConfig({ defaultTab })

      assert.equal(harness.page.data.activeTab, selectedTab)
    } finally {
      harness.cleanup()
    }
  })
}

for (const lifecycle of ['onHide', 'onUnload', 'handleExperienceTap']) {
  test(`late login config does not update page after ${lifecycle}`, async () => {
    const harness = loadLoginPage()
    try {
      harness.page.runWechatLoginPrecheck = () => {}
      harness.page.onLoad()
      harness.page[lifecycle]()
      const patchCount = harness.dataPatches.length

      await harness.respondConfig({ defaultTab: 'maintainer' })

      assert.equal(harness.page.data.activeTab, 'experience')
      assert.equal(harness.dataPatches.length, patchCount)
    } finally {
      harness.cleanup()
    }
  })
}

test('late login config cannot switch tabs after a failed login operation', async () => {
  const harness = loadLoginPage({ loginError: new Error('登录失败') })
  try {
    harness.page.runWechatLoginPrecheck = () => {}
    harness.page.onLoad()
    harness.page.data.activeTab = 'maintainer'

    await harness.page.authorizeByWechat()
    assert.equal(harness.page.data.loading, false)
    await harness.respondConfig({ defaultTab: 'experience' })

    assert.equal(harness.page.data.activeTab, 'maintainer')
  } finally {
    harness.cleanup()
  }
})

test('retrying login precheck protects the current tab from delayed config', async () => {
  const harness = loadLoginPage()
  try {
    harness.page.runWechatLoginPrecheck = () => {}
    harness.page.onLoad()
    harness.page.data.activeTab = 'maintainer'
    harness.page.handleMaintainerAuthTap()

    await harness.respondConfig({ defaultTab: 'experience' })

    assert.equal(harness.page.data.activeTab, 'maintainer')
  } finally {
    harness.cleanup()
  }
})

test('new-user phone registration submits trimmed referral code', async () => {
  const harness = loadLoginPage()
  try {
    harness.page.data.referralCode = '  WFREF0001  '

    await harness.page.authorizeByWechat({ phoneCode: 'phone-code' })

    assert.deepEqual(harness.submittedPayloads, [{
      code: 'wx-submit-code',
      phoneCode: 'phone-code',
      pluginLoginCode: 'plugin-code',
      nickname: '',
      avatarUrl: '',
      referralCode: 'WFREF0001'
    }])
  } finally {
    harness.cleanup()
  }
})

test('existing-user login omits referral code even when the visible field has a value', async () => {
  const harness = loadLoginPage()
  try {
    harness.page.data.referralCode = 'WFREF0001'

    await harness.page.authorizeByWechat()

    assert.deepEqual(harness.submittedPayloads, [{
      code: 'wx-submit-code'
    }])
  } finally {
    harness.cleanup()
  }
})

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
  const navigateCalls = []
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
    login(loginOptions) {
      if (options.wxLogin) options.wxLogin(loginOptions)
      else loginOptions.success({ code: loginCodes.shift() })
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
    navigateTo(navigateOptions) {
      navigateCalls.push(navigateOptions)
      if (options.navigateTo) options.navigateTo(navigateOptions)
      else if (navigateOptions.success) navigateOptions.success({})
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
    navigateCalls,
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

for (const [name, state] of [
  ['未登录且尚未识别', {}],
  ['账号预检中', { prechecking: true }],
  ['账号预检失败', { precheckErrorMessage: '账号识别失败，请重试' }],
  ['等待手机号授权', { precheckReady: true, phoneAuthorizationRequired: true }]
]) {
  test(`AI 构建演示在${name}时可直接打开，保留当前 tab 和推荐码`, () => {
    const harness = loadLoginPage()
    try {
      harness.page.setData({ ...state, activeTab: 'maintainer', referralCode: ' WFREF0001 ' })
      harness.page.runWechatLoginPrecheck = () => assert.fail('观看演示不能触发身份预检')
      harness.page.authorizeByWechat = () => assert.fail('观看演示不能触发登录或手机号授权')
      const before = clone(harness.page.data)

      harness.page.handleAiDemoTap()

      assert.deepEqual(harness.navigateCalls.map(options => options.url), ['/pages/portfolios/tutorial/advanced-portfolio-tutorial'])
      assert.deepEqual(harness.page.data, before)
      assert.deepEqual(harness.submittedPayloads, [])
      assert.deepEqual(harness.requestCalls, [])
      assert.deepEqual(harness.storageReads, [])
      assert.deepEqual(harness.redirectCalls, [])
      assert.deepEqual(harness.toastCalls, [])
    } finally {
      harness.cleanup()
    }
  })
}

test('AI 构建演示跳转后，晚到的登录配置不能切换当前 tab', async () => {
  const harness = loadLoginPage()
  try {
    harness.page.runWechatLoginPrecheck = () => {}
    harness.page.onLoad()
    const before = clone(harness.page.data)

    harness.page.handleAiDemoTap()
    await harness.respondConfig({ defaultTab: 'maintainer' })

    assert.deepEqual(harness.page.data, before)
    assert.equal(harness.navigateCalls.length, 1)
  } finally {
    harness.cleanup()
  }
})

test('实际登录或注册提交期间暂缓演示导航，避免与登录成功跳转竞争', () => {
  const harness = loadLoginPage()
  try {
    harness.page.setData({ loading: true, activeTab: 'maintainer', referralCode: 'WFREF0001' })
    const before = clone(harness.page.data)
    harness.page.handleAiDemoTap()
    assert.deepEqual(harness.navigateCalls, [])
    assert.deepEqual(harness.page.data, before)
    assert.deepEqual(harness.toastCalls, [])
  } finally {
    harness.cleanup()
  }
})

test('AI 构建演示跳转失败提示重试，再次点击仍可打开且不重置输入', () => {
  let attempts = 0
  const harness = loadLoginPage({ navigateTo(options) {
    attempts += 1
    if (attempts === 1) options.fail({ errMsg: 'navigateTo:fail page limit' })
    else if (options.success) options.success({})
  } })
  try {
    harness.page.setData({ activeTab: 'maintainer', referralCode: 'WFREF0001', prechecking: true })
    const before = clone(harness.page.data)
    harness.page.handleAiDemoTap()
    assert.deepEqual(harness.toastCalls, [{ title: '演示打开失败，请重试', icon: 'none' }])
    assert.deepEqual(harness.page.data, before)

    harness.page.handleAiDemoTap()
    assert.deepEqual(harness.navigateCalls.map(options => options.url), [
      '/pages/portfolios/tutorial/advanced-portfolio-tutorial',
      '/pages/portfolios/tutorial/advanced-portfolio-tutorial'
    ])
    assert.deepEqual(harness.page.data, before)
    assert.equal(harness.toastCalls.length, 1)
    assert.deepEqual(harness.submittedPayloads, [])
  } finally {
    harness.cleanup()
  }
})

test('离开登录页后晚到的预检失败只更新登录状态，返回后重试仍正常提示', async () => {
  const loginAttempts = []
  const harness = loadLoginPage({ wxLogin: options => loginAttempts.push(options) })
  try {
    harness.page.onShow()
    const firstPrecheck = harness.page.runWechatLoginPrecheck()
    assert.equal(harness.page.data.prechecking, true)
    harness.page.onHide()
    loginAttempts[0].fail({ errMsg: 'login:fail network error' })
    await firstPrecheck

    assert.equal(harness.page.data.prechecking, false)
    assert.equal(harness.page.data.precheckReady, false)
    assert.equal(harness.page.data.phoneAuthorizationRequired, false)
    assert.equal(harness.page.data.precheckErrorMessage, '账号识别失败，请重试')
    assert.equal(harness.page.data.maintainerButtonText, '重新识别')
    assert.deepEqual(harness.toastCalls, [], '登录页预检失败不得打断正在观看的教程')

    harness.page.onShow()
    const retryPrecheck = harness.page.runWechatLoginPrecheck()
    loginAttempts[1].fail({ errMsg: 'login:fail network error' })
    await retryPrecheck
    assert.deepEqual(harness.toastCalls, [{ title: '账号识别失败，请重试', icon: 'none' }])
  } finally {
    harness.cleanup()
  }
})

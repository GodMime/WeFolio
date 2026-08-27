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

function loadLoginPage() {
  const pagePath = path.join(__dirname, '../pages/login/login.js')
  const sessionPath = path.join(__dirname, '../utils/session.js')
  const pageCacheKey = require.resolve(pagePath)
  const sessionCacheKey = require.resolve(sessionPath)
  const originalSessionCache = require.cache[sessionCacheKey]
  const originalPage = global.Page
  const originalWx = global.wx
  const submittedPayloads = []
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
    redirectTo() {},
    showToast() {}
  }
  delete require.cache[pageCacheKey]
  require(pagePath)

  const page = Object.assign({}, pageDefinition, {
    data: clone(pageDefinition.data),
    setData(patch) {
      Object.assign(this.data, patch)
    }
  })
  return {
    page,
    submittedPayloads,
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

test('referral share opens maintainer tab with normalized referral code', () => {
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
  } finally {
    harness.cleanup()
  }
})

test('ordinary login entry keeps experience tab without referral code', () => {
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
  } finally {
    harness.cleanup()
  }
})

test('malformed referral share keeps ordinary login state', () => {
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

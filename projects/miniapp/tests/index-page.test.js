const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const SHARE_TITLE = '映期Folio｜把作品和档期，装进一张可分享名片'
const SHARE_IMAGE_URL = 'https://cdn2.we-folio.dingchenyong.top/system/folio-logo.png'

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

function loadIndexPage() {
  const pagePath = path.join(__dirname, '../pages/index/index.js')
  const pageCacheKey = require.resolve(pagePath)
  const originalPage = global.Page
  let pageDefinition

  global.Page = (definition) => {
    pageDefinition = definition
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
    cleanup() {
      restoreGlobal('Page', originalPage)
      delete require.cache[pageCacheKey]
    }
  }
}

test('mine page shares login entry with encoded maintainer referral code', () => {
  const harness = loadIndexPage()
  try {
    harness.page.data.dashboard.profile.uniqueCode = '  WF A/B  '

    assert.deepEqual(harness.page.onShareAppMessage(), {
      title: SHARE_TITLE,
      path: '/pages/login/login?referralCode=WF%20A%2FB',
      imageUrl: SHARE_IMAGE_URL
    })
  } finally {
    harness.cleanup()
  }
})

test('mine page shares generic login entry when referral code is unavailable', () => {
  const harness = loadIndexPage()
  try {
    for (const uniqueCode of ['', '  ', '-', null, 123]) {
      harness.page.data.dashboard.profile.uniqueCode = uniqueCode
      assert.deepEqual(harness.page.onShareAppMessage(), {
        title: SHARE_TITLE,
        path: '/pages/login/login',
        imageUrl: SHARE_IMAGE_URL
      })
    }
  } finally {
    harness.cleanup()
  }
})

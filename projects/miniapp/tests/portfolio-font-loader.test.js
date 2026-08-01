const assert = require('node:assert/strict')
const test = require('node:test')

const {
  createPortfolioFontLoader,
  isPortfolioFontAvailable
} = require('../utils/portfolio-font-loader')

test('lets WeChat select the active renderer scope when registering fonts', async () => {
  const calls = []
  const loader = createPortfolioFontLoader({
    loadBuiltInFontFace(options) {
      calls.push(options)
      options.success()
    }
  })

  const first = loader.load()
  const second = loader.load()
  assert.equal(first, second)
  const capability = await first

  assert.deepEqual(calls.map(({ family, source, global }) => ({
    family,
    source,
    global
  })), [
    {
      family: 'WeFolioWechatSansSS',
      source: 'WeChatSansSS',
      global: true
    }
  ])
  assert.equal(
    calls.some((options) => Object.hasOwn(options, 'scopes')),
    false
  )
  assert.equal(capability.apiAvailable, true)
  assert.deepEqual(capability.loadedFamilies, { WECHAT_SANS_SS: true })
  assert.equal(
    Object.hasOwn(capability.loadedFamilies, 'WECHAT_SANS_STD'),
    false
  )
})

test('degrades safely when the built-in font api is unavailable', async () => {
  const loader = createPortfolioFontLoader({})

  const capability = await loader.load()

  assert.deepEqual(capability, {
    apiAvailable: false,
    loadedFamilies: {
      WECHAT_SANS_SS: false
    }
  })
  assert.equal(isPortfolioFontAvailable('SYSTEM', capability), true)
  assert.equal(isPortfolioFontAvailable('WECHAT_SANS_STD', capability), false)
})

test('marks the WeChat font unavailable when registration fails', async () => {
  const originalWarn = console.warn
  console.warn = () => {}
  try {
    const loader = createPortfolioFontLoader({
      loadBuiltInFontFace(options) {
        options.fail(new Error('unsupported font'))
      }
    })

    const capability = await loader.load()

    assert.equal(capability.apiAvailable, true)
    assert.equal(
      isPortfolioFontAvailable('WECHAT_SANS_SS', capability),
      false
    )
  } finally {
    console.warn = originalWarn
  }
})

test('returns detached capability snapshots', async () => {
  const loader = createPortfolioFontLoader({
    loadBuiltInFontFace(options) {
      options.success()
    }
  })
  await loader.load()

  const first = loader.getCapability()
  first.loadedFamilies.WECHAT_SANS_SS = false

  assert.equal(
    loader.getCapability().loadedFamilies.WECHAT_SANS_SS,
    true
  )
})

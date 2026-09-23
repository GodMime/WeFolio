const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function deferred() {
  let resolve
  let reject
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

function flushPromises() {
  return new Promise((resolve) => {
    setImmediate(resolve)
  })
}

function applyData(target, patch) {
  Object.keys(patch).forEach((key) => {
    target[key] = patch[key]
  })
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = content.match(new RegExp(`^\\s*${escapedSelector}\\s*\\{([^}]*)\\}`, 'm'))
  return match ? match[1] : ''
}

function readExisting(relativePath) {
  const absolutePath = path.join(__dirname, '..', relativePath)
  assert.equal(fs.existsSync(absolutePath), true, `${relativePath} should exist`)
  return fs.readFileSync(absolutePath, 'utf8')
}

function loadPreviewPage(fakeRequest, wxOverrides = {}) {
  const pagePath = path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const requestCacheKey = require.resolve(requestPath)
  const originalRequestCache = require.cache[requestCacheKey]
  delete require.cache[require.resolve(pagePath)]
  require.cache[requestCacheKey] = {
    id: requestPath,
    filename: requestPath,
    loaded: true,
    exports: {
      request: fakeRequest
    }
  }

  let pageDefinition
  global.Page = (definition) => {
    pageDefinition = definition
  }
  global.wx = Object.assign({
    navigateBack() {},
    previewImage() {},
    showToast() {}
  }, wxOverrides)
  require(pagePath)
  delete global.Page
  delete global.wx
  if (originalRequestCache) {
    require.cache[requestCacheKey] = originalRequestCache
  } else {
    delete require.cache[requestCacheKey]
  }

  return Object.assign({}, pageDefinition, {
    data: clone(pageDefinition.data),
    setData(patch, callback) {
      applyData(this.data, patch)
      if (callback) {
        callback()
      }
    }
  })
}

for (const renderer of ['webview', 'skyline']) {
  test(`草稿预览在 ${renderer} 中注册已保存字体并传递到结构化正文`, async () => {
    const version = 'gf-809e4d8b8d7e-r1'
    const calls = []
    const requests = []
    const page = loadPreviewPage(async options => {
      requests.push(options.url)
      return { renderData: { preview: true, fonts: {
        versions: { ALLURA: { fontVersion: version } },
        assets: [{ assetId: 'saved-allura', fontId: 'ALLURA', fontVersion: version,
          fontWeight: 400, status: 'READY', url: 'https://fonts.example/saved.woff' }]
      }, components: [{ componentKey: 'body', componentType: 'STRUCTURED_TEXT_SECTION',
        structuredTextSection: { blocks: [{ blockKey: 'paragraph', type: 'PARAGRAPH',
          content: 'PROFILE', fontId: 'ALLURA', fontWeight: 'NORMAL' }] } }] } }
    })
    const previousWx = global.wx
    global.wx = { getAppBaseInfo: () => ({ SDKVersion: '3.17.3' }), loadFontFace(options) {
      calls.push(options)
      // 当前开发工具显式 scopes 会卡住注册；3.7.9 起默认覆盖两种渲染器。
      if (options.scopes) options.fail({ errMsg: 'explicit scopes registration failed' })
      else options.success()
    } }
    try {
      await page.onLoad({ portfolioId: '88' })
      await flushPromises()
      assert.deepEqual(requests, ['/api/mine/portfolios/88/preview'])
      assert.equal(calls.length, 1)
      const { buildStructuredTextPresentation } = require('../pages/portfolios/utils/portfolio-text-sections')
      const config = page.data.portfolio.activeComponents[0].structuredTextSection
      const rendered = buildStructuredTextPresentation(config, 'light', page.data.fontContext)
      assert.ok(rendered.blocks[0].style.includes(`font-family: "${calls[0].family}"`))
      assert.deepEqual(page.data.fontContext.failed, [])
    } finally {
      page.onUnload()
      global.wx = previousWx
    }
  })
}

test('preview video carousel opens a dedicated video page without events and handles missing or failed video', async () => {
  const toasts = [], requests = [], previews = []
  const page = loadPreviewPage((options) => { requests.push(options); return Promise.resolve({}) })
  const previousWx = global.wx
  global.wx = {
    showToast(options) { toasts.push(options) },
    navigateTo(options) {
      const navigation = { route: options.url }; previews.push(navigation)
      options.success({ eventChannel: { emit(event, payload) { Object.assign(navigation, { event, payload }) } } })
    },
    createVideoContext() { assert.fail('来源页不得创建页内播放器') }
  }
  try {
    const work = { workId: 11, mediaType: 'VIDEO', mediaUrl: 'video-11', coverUrl: 'cover-11', title: '视频十一' }
    assert.equal(await page.handleVideoCarouselPlay({ detail: { componentKey: 'vc-1', work } }), true)
    assert.equal(page.data.videoPreviewVisible, true)
    assert.deepEqual(previews, [{ route: '/pages/portfolios/video-player/video-player', event: 'portfolioVideoPlayer', payload: { url: 'video-11', poster: 'cover-11', title: '视频十一', browserContext: null, contextId: '' } }])
    assert.deepEqual(requests, [])
    page.onShow()
    assert.equal(page.data.videoPreviewVisible, false)
    assert.equal(await page.handleVideoCarouselPlay({ detail: { componentKey: 'vc-1', work: {
      workId: 12, mediaType: 'VIDEO', mediaUrl: '', previewUrl: 'cover-12', coverUrl: 'cover-12'
    } } }), false)
    assert.equal(toasts.at(-1).title, '视频地址缺失')
    assert.equal(previews.length, 1)
    global.wx.navigateTo = (options) => options.fail({ errMsg: 'navigateTo:fail' })
    assert.equal(await page.handleVideoCarouselPlay({ detail: { componentKey: 'vc-1', work } }), false)
    assert.equal(toasts.at(-1).title, '视频打开失败，请重试')
    assert.equal(page.data.videoPreviewVisible, false)
  } finally { global.wx = previousWx }
})

test('preview hyperlink always opens the target published preview and keeps native back navigation', async () => {
  const navigations = []
  const toasts = []
  const page = loadPreviewPage(() => Promise.resolve({}))
  const previousWx = global.wx
  global.wx = {
    navigateTo(options) {
      navigations.push(options)
    },
    showToast(options) {
      toasts.push(options)
    }
  }
  try {
    await page.handleHyperlinkTap({
      detail: {
        actionType: 'INTERNAL_PORTFOLIO',
        targetPortfolioId: 99,
        targetAvailable: true,
        targetShareCode: 'PF-TARGET'
      }
    })
    await page.handleHyperlinkTap({
      detail: {
        actionType: 'INTERNAL_PORTFOLIO',
        targetPortfolioId: 100,
        targetAvailable: false
      }
    })
    await page.handleHyperlinkTap({
      detail: {
        actionType: 'EXTERNAL_LINK',
        externalContent: ''
      }
    })

    assert.deepEqual(navigations, [{
      url: '/pages/portfolios/standard-preview/portfolio-standard-preview?portfolioId=99&scope=published'
    }])
    assert.deepEqual(toasts, [
      { title: '内容暂不可见', icon: 'none' },
      { title: '内容暂不可见', icon: 'none' }
    ])
  } finally {
    if (previousWx === undefined) delete global.wx
    else global.wx = previousWx
  }
})

test('preview hyperlink reports clipboard write failure', async () => {
  const clipboardValues = []
  const toasts = []
  const page = loadPreviewPage(() => Promise.resolve({}))
  const previousWx = global.wx
  global.wx = {
    setClipboardData(options) {
      clipboardValues.push(options.data)
      options.fail(new Error('clipboard denied'))
    },
    showToast(options) {
      toasts.push(options)
    }
  }
  try {
    const copied = await page.handleHyperlinkTap({
      detail: {
        actionType: 'EXTERNAL_LINK',
        externalContent: '复制内容',
        promptText: '复制成功'
      }
    })

    assert.equal(copied, false)
    assert.deepEqual(clipboardValues, ['复制内容'])
    assert.deepEqual(toasts, [{ title: '复制失败，请重试', icon: 'none' }])
    assert.equal(page.data.clipboardPromptVisible, false)
  } finally {
    if (previousWx === undefined) delete global.wx
    else global.wx = previousWx
  }
})

test('portfolio preview and visitor pages render miniapp brand footer', () => {
  const logoUrl = '/assets/system/folio-logo-stack-bold-small-50kb.png'
  const brandName = '映期Folio'
  const logoPath = path.join(__dirname, `..${logoUrl}`)
  const previewWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )
  const visitorWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.wxml'),
    'utf8'
  )
  const previewWxss = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.wxss'),
    'utf8'
  )
  const visitorWxss = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.wxss'),
    'utf8'
  )

  ;[previewWxml, visitorWxml].forEach((wxml) => {
    assert.match(wxml, /class="folio-brand-footer"/)
    assert.match(wxml, new RegExp(`src="${logoUrl.replace(/\./g, '\\.')}"`))
    assert.match(wxml, new RegExp(`class="folio-brand-name">${brandName}</view>`))
  })
  ;[previewWxss, visitorWxss].forEach((wxss) => {
    assert.match(readRule(wxss, '.folio-brand-footer'), /padding:\s*56rpx 0 calc\(160rpx \+ env\(safe-area-inset-bottom\)\);/)
  })
  assert.equal(fs.existsSync(logoPath), true)
})

test('personal portfolio scroll regions stay within the viewport below custom navigation', () => {
  const pageStyles = [
    {
      wxss: readExisting('pages/portfolios/standard-preview/portfolio-standard-preview.wxss'),
      rootSelector: '.portfolio-preview-page',
      scrollSelector: '.preview-scroll'
    },
    {
      wxss: readExisting('pages/portfolios/visitor-portfolio/visitor-portfolio.wxss'),
      rootSelector: '.visitor-portfolio-page',
      scrollSelector: '.visitor-scroll'
    },
    {
      wxss: readExisting('pages/mock/styles/portfolio-standard-preview.wxss'),
      rootSelector: '.portfolio-preview-page',
      scrollSelector: '.preview-scroll'
    }
  ]

  pageStyles.forEach(({ wxss, rootSelector, scrollSelector }) => {
    const rootRule = readRule(wxss, rootSelector)
    const scrollRule = readRule(wxss, scrollSelector)

    assert.match(rootRule, /height:\s*100vh;/)
    assert.match(rootRule, /display:\s*flex;/)
    assert.match(rootRule, /flex-direction:\s*column;/)
    assert.match(rootRule, /overflow:\s*hidden;/)
    assert.match(scrollRule, /flex:\s*1;/)
    assert.match(scrollRule, /min-height:\s*0;/)
    assert.doesNotMatch(scrollRule, /height:\s*100vh;/)
  })
})

test('dark preview and visitor footers use the transparent white logo without filters', () => {
  const darkLogoUrl = '/assets/system/folio-logo-stack-bold-dark-50kb.png'
  const darkLogoPath = path.join(__dirname, `..${darkLogoUrl}`)
  const pagePaths = [
    'pages/portfolios/standard-preview/portfolio-standard-preview',
    'pages/portfolios/visitor-portfolio/visitor-portfolio',
    'pages/mock/portfolio-standard-preview/portfolio-standard-preview'
  ]

  pagePaths.forEach((pagePath) => {
    const wxml = readExisting(`${pagePath}.wxml`)
    const wxss = readExisting(`${pagePath}.wxss`)
    assert.match(
      wxml,
      new RegExp(`wx:if="\\{\\{portfolio\\.themeMode === 'dark'\\}\\}"[\\s\\S]*src="${darkLogoUrl.replace(/\./g, '\\.')}"`),
      pagePath
    )
    assert.equal(
      readRule(
        wxss,
        '.portfolio-theme-dark .folio-brand-logo'
      ),
      '',
      pagePath
    )
  })

  assert.equal(fs.existsSync(darkLogoPath), true)
  const darkLogo = fs.readFileSync(darkLogoPath)
  assert.equal(darkLogo.readUInt8(25), 6)
  assert.ok(darkLogo.byteLength < 50 * 1024)
})

test('preview page keeps recoverable loading and error states when request fails', async (t) => {
  const requests = []
  const fakeRequest = (options) => {
    const pending = deferred()
    requests.push(Object.assign({ pending }, options))
    return pending.promise
  }
  const page = loadPreviewPage(fakeRequest)
  global.wx = {}
  t.after(() => { delete global.wx })

  page.onLoad({ portfolioId: '88' })

  assert.equal(page.data.loading, true)
  assert.equal(page.data.errorMessage, '')
  assert.equal(requests[0].url, '/api/mine/portfolios/88/preview')

  requests[0].pending.reject(new Error('网络异常'))
  await flushPromises()

  assert.equal(page.data.loading, false)
  assert.equal(page.data.errorMessage, '网络异常')

  const retryPromise = page.handleRetryPreview()
  assert.equal(page.data.loading, true)
  assert.equal(page.data.errorMessage, '')
  assert.equal(requests[1].url, '/api/mine/portfolios/88/preview')

  requests[1].pending.resolve({
    renderData: {
      title: '预览成功',
      preview: true,
      components: []
    }
  })
  await retryPromise

  assert.equal(page.data.loading, false)
  assert.equal(page.data.errorMessage, '')
  assert.equal(page.data.portfolio.title, '预览成功')
})

test('preview page requests published preview endpoint for published scope', async (t) => {
  const requests = []
  const page = loadPreviewPage((options) => {
    requests.push(options)
    return Promise.resolve({
      portfolioId: 88,
      publishedRevision: 4,
      renderData: {
        preview: true,
        title: '已发布作品集',
        components: []
      }
    })
  })

  global.wx = {}
  t.after(() => { delete global.wx })
  page.onLoad({ portfolioId: '88', scope: 'published' })
  await flushPromises()

  assert.equal(requests[0].url, '/api/mine/portfolios/88/published-preview')
  assert.equal(page.data.portfolio.title, '已发布作品集')
  assert.equal(page.data.portfolio.preview, true)
})

test('preview page loads a team-referenced member portfolio through the nested published endpoint', async (t) => {
  const requests = []
  const page = loadPreviewPage((options) => {
    requests.push(options)
    return Promise.resolve({
      portfolioId: 88,
      publishedRevision: 4,
      renderData: {
        preview: true,
        title: '成员发布作品集',
        components: []
      }
    })
  })

  global.wx = {}
  t.after(() => { delete global.wx })
  page.onLoad({
    portfolioId: '88',
    teamPortfolioId: '13',
    teamScope: 'draft'
  })
  await flushPromises()

  assert.equal(requests[0].url, '/api/mine/team-portfolios/13/member-portfolios/88/published-preview')
  assert.deepEqual(requests[0].data, { scope: 'draft' })
  assert.equal(page.data.teamPortfolioId, 13)
  assert.equal(page.data.teamPreviewScope, 'draft')
  assert.equal(page.data.previewScope, 'published')
  assert.equal(page.data.portfolio.title, '成员发布作品集')

  const wxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )
  assert.match(wxml, /team-portfolio-id="\{\{teamPortfolioId\}\}"/)
  assert.match(wxml, /team-preview-scope="\{\{teamPreviewScope\}\}"/)
})

test('preview display group switch tolerates unnormalized component arrays', () => {
  const page = loadPreviewPage(() => Promise.resolve({}))
  page.data.portfolio = {
    components: [
      {
        componentKey: 'c_grid',
        componentType: 'WORK_GRID',
        activeGroupKey: 'g_existing',
        activeGroup: {
          groupKey: 'g_existing',
          name: '已有标签',
          works: []
        }
      }
    ]
  }

  assert.doesNotThrow(() => {
    page.handleDisplayTagTap({
      currentTarget: {
        dataset: {
          componentKey: 'c_grid',
          groupKey: 'g_missing'
        }
      }
    })
  })
  assert.equal(page.data.portfolio.components[0].activeGroupKey, 'g_existing')
})

test('preview display group switch marks work content as switching briefly', () => {
  const originalSetTimeout = global.setTimeout
  const originalClearTimeout = global.clearTimeout
  const timers = []
  const clearedTimers = []
  global.setTimeout = (handler, delay) => {
    timers.push({ handler, delay })
    return `timer-${timers.length}`
  }
  global.clearTimeout = (timerId) => {
    clearedTimers.push(timerId)
  }

  try {
    const page = loadPreviewPage(() => Promise.resolve({}))
    page.data.portfolio = {
      components: [
        {
          componentKey: 'c_grid',
          componentType: 'WORK_GRID',
          activeGroupKey: 'all',
          activeGroup: {
            groupKey: 'all',
            name: '全部',
            works: []
          },
          groups: [
            { groupKey: 'all', name: '全部', works: [] },
            { groupKey: 'tag_1', name: '中式婚礼', works: [] }
          ],
          displayTags: [
            { groupKey: 'all', name: '全部', active: true },
            { groupKey: 'tag_1', name: '中式婚礼', active: false }
          ]
        }
      ]
    }

    page.handleDisplayTagTap({
      currentTarget: {
        dataset: {
          componentKey: 'c_grid',
          groupKey: 'tag_1'
        }
      }
    })

    assert.equal(page.data.portfolio.components[0].activeGroupKey, 'tag_1')
    assert.equal(page.data.displaySwitchingComponentKey, 'c_grid')
    assert.equal(timers.length, 1)
    assert.equal(timers[0].delay, 180)
    assert.deepEqual(clearedTimers, [])

    timers[0].handler()

    assert.equal(page.data.displaySwitchingComponentKey, '')
  } finally {
    global.setTimeout = originalSetTimeout
    global.clearTimeout = originalClearTimeout
  }
})

test('preview page opens image and video work media without visitor event request', async () => {
  const requests = []
  const previews = [], videos = []
  const wxMock = {
    navigateTo(options) {
      const navigation = { route: options.url }; videos.push(navigation)
      options.success({ eventChannel: { emit(event, payload) { Object.assign(navigation, { event, payload }) } } })
    },
    previewImage(options) {
      previews.push(options)
    }
  }
  const page = loadPreviewPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  }, wxMock)
  global.wx = Object.assign({
    showToast() {}
  }, wxMock)

  try {
    await page.handleWorkTap({
      currentTarget: {
        dataset: {
          workId: '11',
          mediaType: 'IMAGE',
          mediaUrl: 'https://cdn.example.com/original.jpg',
          coverUrl: 'https://cdn.example.com/thumb.jpg',
          title: '迎宾图'
        }
      }
    })

    assert.deepEqual(previews[0], {
      current: 'https://cdn.example.com/original.jpg',
      urls: ['https://cdn.example.com/original.jpg']
    })

    await page.handleWorkTap({
      currentTarget: {
        dataset: {
          workId: '12',
          mediaType: 'VIDEO',
          mediaUrl: 'https://cdn.example.com/movie.mp4',
          coverUrl: 'https://cdn.example.com/movie.jpg',
          title: '婚礼快剪'
        }
      }
    })
  } finally {
    delete global.wx
  }

  assert.equal(requests.length, 0)
  assert.equal(page.data.videoPreviewVisible, true)
  assert.deepEqual(videos, [{ route: '/pages/portfolios/video-player/video-player', event: 'portfolioVideoPlayer', payload: { url: 'https://cdn.example.com/movie.mp4', poster: 'https://cdn.example.com/movie.jpg', title: '婚礼快剪', browserContext: null, contextId: '' } }])
})

test('preview single work images open originals and videos play inline one at a time', () => {
  const previews = []
  const paused = []
  const wxMock = {
    previewImage(options) {
      previews.push(options)
    },
    showToast() {}
  }
  const page = loadPreviewPage(() => Promise.resolve({}), wxMock)
  page.selectAllComponents = () => [
    {
      data: { componentKey: 'c_video_a' },
      pauseVideo() {
        paused.push('c_video_a')
      }
    },
    {
      data: { componentKey: 'c_video_b' },
      pauseVideo() {
        paused.push('c_video_b')
      }
    }
  ]
  global.wx = wxMock

  try {
    page.handleSingleWorkTap({
      currentTarget: {
        dataset: {
          componentKey: 'c_image',
          workId: '11',
          mediaType: 'IMAGE',
          mediaUrl: 'https://cdn.example.com/original.jpg',
          coverUrl: 'https://cdn.example.com/thumb.jpg',
          title: '迎宾图'
        }
      }
    })
    page.handleSingleWorkTap({
      currentTarget: {
        dataset: {
          componentKey: 'c_video_a',
          workId: '12',
          mediaType: 'VIDEO',
          mediaUrl: 'https://cdn.example.com/a.mp4',
          coverUrl: 'https://cdn.example.com/a.jpg',
          title: '视频 A'
        }
      }
    })
    page.handleSingleWorkTap({
      currentTarget: {
        dataset: {
          componentKey: 'c_video_b',
          workId: '13',
          mediaType: 'VIDEO',
          mediaUrl: 'https://cdn.example.com/b.mp4',
          coverUrl: 'https://cdn.example.com/b.jpg',
          title: '视频 B'
        }
      }
    })
  } finally {
    delete global.wx
  }

  assert.deepEqual(previews[0], {
    current: 'https://cdn.example.com/original.jpg',
    urls: ['https://cdn.example.com/original.jpg']
  })
  assert.equal(page.data.activeSingleWorkVideoKey, 'c_video_b')
  assert.deepEqual(paused, ['c_video_a'])
  assert.equal(page.data.videoPreviewVisible, false)
})

test('preview page pauses only the active single work child component', () => {
  const page = loadPreviewPage(() => Promise.resolve({}))
  const calls = []
  page.data.activeSingleWorkVideoKey = 'single:active'
  page.selectAllComponents = (selector) => {
    assert.equal(selector, '.portfolio-single-work-instance')
    return [
      {
        data: { componentKey: 'single:other' },
        pauseVideo() {
          calls.push('other')
        }
      },
      {
        data: { componentKey: 'single:active' },
        pauseVideo() {
          calls.push('active')
        }
      }
    ]
  }

  page.stopActiveSingleWorkVideo()

  assert.deepEqual(calls, ['active'])
  assert.equal(page.data.activeSingleWorkVideoKey, '')
})

test('preview single work does not use cover as missing original or video media', () => {
  const previews = []
  const toasts = []
  const wxMock = {
    previewImage(options) {
      previews.push(options)
    },
    showToast(options) {
      toasts.push(options)
    }
  }
  const page = loadPreviewPage(() => Promise.resolve({}), wxMock)
  global.wx = wxMock

  try {
    assert.equal(page.handleSingleWorkTap({
      currentTarget: { dataset: {
        componentKey: 'c_video',
        mediaType: 'VIDEO',
        mediaUrl: '',
        coverUrl: 'video-cover.jpg'
      } }
    }), false)
    assert.equal(page.handleSingleWorkTap({
      currentTarget: { dataset: {
        componentKey: 'c_image',
        mediaType: 'IMAGE',
        mediaUrl: '',
        coverUrl: 'image-thumb.jpg'
      } }
    }), false)
  } finally {
    delete global.wx
  }

  assert.deepEqual(toasts.map((item) => item.title), ['视频地址缺失', '图片地址缺失'])
  assert.equal(previews.length, 0)
  assert.equal(page.data.activeSingleWorkVideoKey, '')
})

test('single work markup keeps width-fix images and inline autoplay alongside dedicated list video playback', () => {
  const pageWxml = readExisting('pages/portfolios/standard-preview/portfolio-standard-preview.wxml')
  const componentWxml = readExisting('pages/portfolios/components/single-work/single-work.wxml')

  assert.match(pageWxml, /item\.componentType === 'SINGLE_WORK'/)
  assert.match(pageWxml, /<portfolio-single-work[\s\S]*active-video-key="\{\{activeSingleWorkVideoKey\}\}"/)
  assert.match(componentWxml, /class="single-work-image"[\s\S]*mode="widthFix"/)
  assert.match(componentWxml, /<video[\s\S]*activeVideoKey === componentKey[\s\S]*autoplay="\{\{true\}\}"/)
  assert.match(componentWxml, /class="single-work-play-badge"/)
  assert.match(componentWxml, /wx:if="\{\{showTitle && work\.title\}\}" class="single-work-title"/)
  assert.doesNotMatch(pageWxml, /work-video-mask|work-video-player/)
})

test('preview markup exposes loading skeleton and retryable error state', () => {
  const wxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )

  assert.match(wxml, /wx:if="\{\{loading\}\}"/)
  assert.match(wxml, /wx:elif="\{\{errorMessage\}\}"/)
  assert.match(wxml, /bindtap="handleRetryPreview"/)
})

test('preview page passes schedule query context to shared component', () => {
  const wxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )
  const json = JSON.parse(fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.json'),
    'utf8'
  ))

  assert.equal(json.usingComponents['portfolio-schedule-query'], '/pages/portfolios/components/portfolio-schedule-query/portfolio-schedule-query')
  assert.match(wxml, /<portfolio-schedule-query[\s\S]*portfolio-id="\{\{portfolioId\}\}"[\s\S]*preview="\{\{true\}\}"[\s\S]*preview-scope="\{\{previewScope\}\}"/)
  assert.match(wxml, /<portfolio-schedule-query[\s\S]*component-key="\{\{item\.componentKey\}\}"[\s\S]*schedule-query="\{\{item\.scheduleQuery\}\}"/)
  assert.doesNotMatch(wxml, /<button class="secondary-action">档期查询<\/button>/)
})

test('portfolio work sections render fixed title, all tags, play badge, and dedicated video playback', () => {
  const previewWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )
  const visitorWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.wxml'),
    'utf8'
  )
  const gridWxml = readExisting('pages/portfolios/components/work-grid/work-grid.wxml')
  const listWxml = readExisting('pages/portfolios/components/work-list/work-list.wxml')
  const gridWxss = readExisting('pages/portfolios/components/work-grid/work-grid.wxss')
  const listWxss = readExisting('pages/portfolios/components/work-list/work-list.wxss')
  const mockSharedWxss = readExisting('styles/portfolio-render-shared.wxss')

  ;[previewWxml, visitorWxml].forEach((wxml) => {
    assert.match(wxml, /<portfolio-work-grid[\s\S]*switching="\{\{displaySwitchingComponentKey === item\.componentKey\}\}"/)
    assert.match(wxml, /<portfolio-work-list[\s\S]*switching="\{\{displaySwitchingComponentKey === item\.componentKey\}\}"/)
    assert.doesNotMatch(wxml, /work-video-mask|work-video-player|portfolioWorkVideo/)
  })
  ;[gridWxml, listWxml].forEach((wxml) => {
    assert.match(wxml, /class="work-section-title">作品列表<\/view>/)
    assert.doesNotMatch(wxml, /class="work-section-title">作品 &gt;<\/view>/)
    assert.match(wxml, /wx:for="\{\{displayTags\}\}"[\s\S]*>\{\{tag\.name\}\}<\/view>/)
    assert.match(wxml, /class="work-cover-wrap"[\s\S]*bindtap="handleWorkTap"/)
    assert.match(wxml, /data-media-url="\{\{work\.previewUrl\}\}"/)
    assert.match(wxml, /wx:if="\{\{work\.isVideo\}\}"[\s\S]*class="work-play-badge"/)
  })
  ;[gridWxss, listWxss].forEach((wxss) => {
    assert.match(wxss, /\.work-section-title\s*\{[\s\S]*color:\s*var\(--portfolio-text-primary\);[\s\S]*font-size:\s*34rpx;/)
    assert.match(wxss, /\.work-play-badge\s*\{[\s\S]*position:\s*absolute;[\s\S]*right:\s*16rpx;[\s\S]*bottom:\s*16rpx;/)
    assert.doesNotMatch(wxss, /\.work-play-badge\s*\{[\s\S]*top:\s*50%;[\s\S]*left:\s*50%;/)
  })
  ;[mockSharedWxss].forEach((wxss) => {
    assert.match(wxss, /\.display-tag\s*\{[\s\S]*color:\s*#868e96;[\s\S]*font-size:\s*28rpx;/)
    assert.match(wxss, /\.display-tag\.active\s*\{[\s\S]*color:\s*#212529;/)
  })
})

test('single work inline videos fill their frame across production and mock previews', () => {
  const productionWxml = readExisting('pages/portfolios/components/single-work/single-work.wxml')
  const mockPreviewWxml = readExisting('pages/mock/portfolio-standard-preview/portfolio-standard-preview.wxml')
  const mockRendererWxml = readExisting('components/mock/portfolio-renderer/portfolio-renderer.wxml')
  const inlineVideoPattern = /<video[\s\S]*?class="single-work-video"[\s\S]*?object-fit="cover"[\s\S]*?<\/video>/

  assert.match(productionWxml, inlineVideoPattern)
  assert.match(mockPreviewWxml, /<mock-portfolio-renderer[\s\S]*active-single-work-video-key="\{\{activeSingleWorkVideoKey\}\}"/)
  assert.match(mockRendererWxml, inlineVideoPattern)
})

test('preview and visitor delegate video controls to the dedicated video page without page overlays', () => {
  const previewWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )
  const visitorWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.wxml'),
    'utf8'
  )

  for (const wxml of [visitorWxml, previewWxml]) {
    assert.doesNotMatch(wxml, /work-video-mask|work-video-player|video-page-hidden/)
    assert.doesNotMatch(wxml, /<root-portal\b[^>]*videoPreview/)
  }
})

test('preview stylesheet no longer hides content for page video fullscreen', () => {
  const previewWxss = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.wxss'),
    'utf8'
  )
  assert.doesNotMatch(previewWxss, /\.work-video-|\.video-page-hidden/)
})

test('profile component can render selected wechat qr in actual pages', () => {
  const previewWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )
  const visitorWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.wxml'),
    'utf8'
  )
  const profileWxml = readExisting('pages/portfolios/components/profile/profile.wxml')

  assert.match(previewWxml, /<portfolio-profile[\s\S]*profile="\{\{item\.profile\}\}"[\s\S]*bindpreviewqr="handlePreviewQr"/)
  assert.match(visitorWxml, /<portfolio-profile[\s\S]*profile="\{\{item\.profile\}\}"[\s\S]*bindpreviewqr="handlePreviewQr"/)
  assert.match(profileWxml, /wx:if="\{\{profile\.wechatQrUrl\}\}"[\s\S]*src="\{\{profile\.wechatQrUrl\}\}"[\s\S]*bindtap="handlePreviewQr"/)
})

test('profile component renders personal tags as chromatic outlined pills', () => {
  const profileWxml = readExisting('pages/portfolios/components/profile/profile.wxml')
  const profileWxss = readExisting('pages/portfolios/components/profile/profile.wxss')
  const tagRule = readRule(profileWxss, '.profile-tag')
  const dotRule = readRule(profileWxss, '.profile-tag-dot')

  assert.match(
    profileWxml,
    /class="profile-tag"[\s\S]*color: \{\{tag\.color \|\| '#0f766e'\}\};[\s\S]*border-color: \{\{tag\.color \|\| '#0f766e'\}\};/
  )
  assert.match(
    profileWxml,
    /class="profile-tag-dot"[\s\S]*background: \{\{tag\.color \|\| '#0f766e'\}\};/
  )
  assert.match(tagRule, /background:\s*#ffffff/)
  assert.match(dotRule, /width:\s*12rpx/)
  assert.match(dotRule, /height:\s*12rpx/)
  assert.match(dotRule, /border-radius:\s*50%/)
})

test('contact form components support modal entry and inline form in actual pages', () => {
  const previewWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )
  const visitorWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.wxml'),
    'utf8'
  )
  const previewJson = JSON.parse(fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.json'),
    'utf8'
  ))
  const visitorJson = JSON.parse(fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.json'),
    'utf8'
  ))
  const componentWxml = readExisting('pages/portfolios/components/portfolio-contact-form/portfolio-contact-form.wxml')

  assert.equal(previewJson.usingComponents['portfolio-contact-form'], '/pages/portfolios/components/portfolio-contact-form/portfolio-contact-form')
  assert.equal(visitorJson.usingComponents['portfolio-contact-form'], '/pages/portfolios/components/portfolio-contact-form/portfolio-contact-form')
  ;[previewWxml, visitorWxml].forEach((wxml) => {
    assert.match(wxml, /<portfolio-contact-form[\s\S]*contact-component="\{\{item\}\}"[\s\S]*bindcontactinput="handleContactInput"[\s\S]*bindopenmodal="handleOpenContactFormModal"/)
    assert.match(wxml, /<portfolio-contact-form[\s\S]*view-mode="modal"[\s\S]*modal-visible="\{\{contactFormModalVisible\}\}"[\s\S]*contact-component="\{\{activeContactFormComponent\}\}"/)
    assert.doesNotMatch(wxml, /class="contact-form-mask/)
  })
  assert.match(componentWxml, /contactComponent\.contactForm\.displayMode === inlineMode[\s\S]*class="form-section"/)
  assert.match(componentWxml, /class="form-entry-section"[\s\S]*bindtap="handleOpenModal"/)
  assert.match(componentWxml, /class="contact-form-mask portfolio-theme-\{\{themeMode\}\} \{\{modalVisible \? 'visible' : ''\}\}"/)
  assert.match(componentWxml, /class="contact-form-panel"[\s\S]*contactComponent\.contactForm\.title/)
})

test('preview opens a modal contact form from the active secondary menu', () => {
  const page = loadPreviewPage(() => Promise.resolve({}))
  const component = {
    componentKey: 'c_secondary_contact',
    componentType: 'CONTACT_FORM',
    contactForm: {
      displayMode: 'MODAL_FORM',
      fields: ['contactName']
    }
  }
  page.data.portfolio = {
    components: [],
    activeComponents: [component],
    bottomNav: {
      enabled: true,
      items: [
        { key: 'nav_home', title: '主页' },
        { key: 'nav_contact', title: '联系', components: [component] }
      ]
    }
  }

  page.handleOpenContactFormModal({
    detail: { componentKey: 'c_secondary_contact' }
  })

  assert.equal(page.data.contactFormModalVisible, true)
  assert.equal(page.data.activeContactFormComponent.componentKey, 'c_secondary_contact')
})

test('actual portfolio pages do not render share intro as page content', () => {
  const previewWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-preview/portfolio-standard-preview.wxml'),
    'utf8'
  )
  const visitorWxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/visitor-portfolio/visitor-portfolio.wxml'),
    'utf8'
  )

  assert.doesNotMatch(previewWxml, /portfolio\.share\.intro/)
  assert.doesNotMatch(visitorWxml, /portfolio\.share\.intro/)
  assert.doesNotMatch(previewWxml, /class="share-intro"/)
  assert.doesNotMatch(visitorWxml, /class="share-intro"/)
})

test('portfolio user-authored text preserves line breaks in actual pages', () => {
  const profileWxml = readExisting('pages/portfolios/components/profile/profile.wxml')
  const profileWxss = readExisting('pages/portfolios/components/profile/profile.wxss')
  const textWxss = readExisting('pages/portfolios/components/text-section/text-section.wxss')
  const listWxss = readExisting('pages/portfolios/components/work-list/work-list.wxss')
  const appWxss = fs.readFileSync(
    path.join(__dirname, '../app.wxss'),
    'utf8'
  )
  assert.match(profileWxml, /<text wx:if="\{\{profile\.bio\}\}" class="profile-bio" space="nbsp">\{\{profile\.bio\}\}<\/text>/)
  assert.match(profileWxss, /\.profile-bio\s*\{[\s\S]*display:\s*block;/)
  ;[
    profileWxss,
    readRule(textWxss, '.text-content'),
    readRule(listWxss, '.work-desc')
  ].forEach((rule) => {
    assert.match(rule, /white-space:\s*pre-wrap;/)
    assert.match(rule, /overflow-wrap:\s*break-word;/)
    assert.match(rule, /word-break:\s*break-word;/)
  })
  assert.match(appWxss, /\.user-authored-text,[\s\S]*\.message-content,[\s\S]*\.team-summary,[\s\S]*\.member-summary,[\s\S]*\.candidate-summary,[\s\S]*\.visit-summary\s*\{[^}]*white-space:\s*pre-wrap;/)
})

test('personal profile borders use scoped card spacing and the existing color picker', () => {
  const profileWxml = readExisting('pages/portfolios/components/profile/profile.wxml')
  const profileWxss = readExisting('pages/portfolios/components/profile/profile.wxss')
  const editorWxml = readExisting('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  assert.match(profileWxml, /profileBorderVisible \? 'profile-bordered' : ''/)
  assert.match(profileWxml, /style="\{\{profileBorderStyle\}\}"/)
  const cardRule = readRule(profileWxss, '.profile-bordered')
  assert.doesNotMatch(cardRule, /margin:/)
  assert.match(profileWxml, /^<view class="profile-spacing" style="\{\{profileSpacingStyle\}\}">/)
  assert.match(readRule(profileWxss, '.profile-spacing'), /box-sizing:\s*border-box;/)
  assert.match(cardRule, /padding:\s*32rpx;/)
  assert.match(cardRule, /border-radius:\s*24rpx;/)
  assert.match(cardRule, /border-style:\s*solid;/)
  assert.match(readRule(profileWxss, '.profile-bordered .profile-identity'), /word-break:\s*break-word;/)
  assert.match(editorWxml, /<text-color-editor label="边框颜色" inline="\{\{true\}\}"[^>]*active="\{\{profileSheetVisible && profileBorderConfig\.profileBorder\}\}"[^>]*bindchange="handleProfileBorderColorChange"/)
  assert.match(editorWxml, /catchtap="handleProfileBorderAutoColor"/)
  assert.match(editorWxml, /profile-border-settings[\s\S]*外侧留白[\s\S]*data-field="\{\{item.field\}\}"[^>]*disabled="\{\{!profileSheetVisible \|\| !profileBorderConfig.profileBorder\}\}"[^>]*bindchange="handleProfileMarginChange"/)
})

test('personal profile horizontal layout keeps the header flexible and details below it', () => {
  const profileWxml = readExisting('pages/portfolios/components/profile/profile.wxml')
  const profileWxss = readExisting('pages/portfolios/components/profile/profile.wxss')
  const editorWxml = readExisting('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  assert.match(profileWxml, /profile\.profileLayout === 'HORIZONTAL' \? 'profile-horizontal'/)
  assert.match(profileWxml, /class="profile-heading"[\s\S]*class="profile-avatar"[\s\S]*class="profile-identity"[\s\S]*<\/view>\s*<\/view>\s*<text wx:if="\{\{profile\.bio\}\}"/)
  assert.match(readRule(profileWxss, '.profile-horizontal .profile-heading'), /display:\s*flex;/)
  assert.match(readRule(profileWxss, '.profile-horizontal .profile-identity'), /min-width:\s*0;/)
  assert.match(readRule(profileWxss, '.profile-horizontal .profile-identity'), /word-break:\s*break-word;/)
  assert.match(readRule(profileWxss, '.profile-horizontal .profile-tags'), /justify-content:\s*flex-start;/)
  assert.match(editorWxml, /wx:for="\{\{profileLayoutOptions\}\}"[^>]*catchtap="handleProfileLayoutChange"/)
})

test('portfolio profile avatar is centered in actual pages', () => {
  const profileWxss = readExisting('pages/portfolios/components/profile/profile.wxss')
  const profileAvatarRule = readRule(profileWxss, '.profile-avatar')

  assert.match(profileAvatarRule, /display:\s*block;/)
  assert.match(profileAvatarRule, /margin:\s*0 auto;/)
})

test('portfolio bottom navigation derives distinct light and dark selected states', () => {
  const wxss = readExisting('components/portfolio-bottom-nav/portfolio-bottom-nav.wxss')

  assert.match(wxss, /\.portfolio-bottom-nav-inner\s*\{[\s\S]*background:\s*#ffffff;/)
  assert.match(wxss, /\.portfolio-bottom-nav-item\.active\s*\{[\s\S]*color:\s*#ffffff;[\s\S]*background:\s*#212529;/)
  assert.match(wxss, /\.portfolio-bottom-nav\.portfolio-theme-dark \.portfolio-bottom-nav-inner\s*\{[\s\S]*background:\s*#1e1e1e;/)
  assert.match(wxss, /\.portfolio-bottom-nav\.portfolio-theme-dark \.portfolio-bottom-nav-item\.active\s*\{[\s\S]*color:\s*#151515;[\s\S]*background:\s*#ffffff;/)
})

test('preview bottom navigation exits old content before entering the target menu', () => {
  const originalSetTimeout = global.setTimeout
  const originalClearTimeout = global.clearTimeout
  const timers = []
  global.setTimeout = (handler, delay) => {
    const timer = { handler, delay, id: `timer-${timers.length + 1}` }
    timers.push(timer)
    return timer.id
  }
  global.clearTimeout = () => {}

  try {
    const page = loadPreviewPage(() => Promise.resolve({}))
    const homeComponents = [{ componentKey: 'c_home', componentType: 'PROFILE' }]
    const worksComponents = [{ componentKey: 'c_works', componentType: 'WORK_GRID' }]
    page.data.portfolio = {
      components: homeComponents,
      activeComponents: homeComponents,
      activeMenuKey: 'home',
      bottomNav: {
        enabled: true,
        items: [
          { key: 'home', title: '主页' },
          { key: 'works', title: '作品', components: worksComponents }
        ]
      }
    }

    const started = page.handleBottomNavChange({
      detail: { menuKey: 'works' }
    })

    assert.equal(started, true)
    assert.equal(page.data.portfolio.activeMenuKey, 'home')
    assert.deepEqual(page.data.portfolio.activeComponents, homeComponents)
    assert.equal(page.data.portfolioMenuSwitching, true)
    assert.equal(page.data.portfolioMenuTransitionClass, 'portfolio-menu-exit-forward')
    assert.equal(timers[0].delay, 120)

    timers[0].handler()

    assert.equal(page.data.portfolio.activeMenuKey, 'works')
    assert.deepEqual(page.data.portfolio.activeComponents, worksComponents)
    assert.equal(page.data.portfolioMenuTransitionClass, 'portfolio-menu-enter-forward')
    assert.equal(page.data.portfolioScrollTop, 0)
    assert.equal(timers[1].delay, 220)

    timers[1].handler()

    assert.equal(page.data.portfolioMenuSwitching, false)
    assert.equal(page.data.portfolioMenuTransitionClass, '')
  } finally {
    global.setTimeout = originalSetTimeout
    global.clearTimeout = originalClearTimeout
  }
})

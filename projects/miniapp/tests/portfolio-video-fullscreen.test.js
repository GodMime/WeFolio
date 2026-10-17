const assert = require('node:assert/strict')
const fs = require('node:fs')
const { createRequire } = require('node:module')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '..')
const PAGES = [
  'pages/portfolios/standard-preview/portfolio-standard-preview',
  'pages/portfolios/visitor-portfolio/visitor-portfolio',
  'pages/team-portfolios/standard-preview/team-portfolio-standard-preview',
  'pages/team-portfolios/visitor-portfolio/team-visitor-portfolio'
]
const WORK = { workId: 11, mediaType: 'VIDEO', mediaUrl: 'video-11.mp4', coverUrl: 'cover-11.jpg', title: '测试视频' }

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

// 页面在独立微信环境中执行，加载真实依赖，但不触发请求、背景音乐或统计上报。
function loadPage(pagePath, { wx: wxOverrides = {} } = {}) {
  const filename = path.join(ROOT, `${pagePath}.js`)
  const calls = []
  const patches = []
  let definition
  const wxApi = {
    navigateTo(options) { calls.push({ action: 'navigateTo', options }); finishNavigation(options) },
    createVideoContext(id, scope) {
      calls.push({ action: 'createVideoContext', id, scope })
      return {
        pause() {
          calls.push({ action: 'pause' })
        },
        exitFullScreen() { calls.push({ action: 'exitFullScreen' }) },
        stop() { calls.push({ action: 'stop' }) }
      }
    },
    showToast(options) { calls.push({ action: 'toast', options }) },
    ...wxOverrides
  }
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    require: createRequire(filename),
    Page(value) { definition = value },
    wx: wxApi,
    setTimeout,
    clearTimeout
  }, { filename })
  const page = Object.assign({}, definition, {
    data: clone(definition.data),
    setData(patch, callback) {
      patches.push(clone(patch))
      Object.assign(this.data, patch)
      if (callback) callback()
    },
    pauseBackgroundAudio() { calls.push({ action: 'pauseBackgroundAudio' }) },
    showBackgroundAudio() { calls.push({ action: 'showBackgroundAudio' }) },
    hideBackgroundAudio() {},
    destroyBackgroundAudio() {},
    stopActiveSingleWorkVideo() { calls.push({ action: 'stopActiveSingleWorkVideo' }) },
    stopSingleWorkVideos() { calls.push({ action: 'stopSingleWorkVideos' }) },
    sendEvent(payload) { calls.push({ action: 'sendEvent', payload }); return Promise.resolve() },
    recordWorkEvent(work) { calls.push({ action: 'recordWorkEvent', work }); return Promise.resolve() },
    recordVideoCarouselEvent(componentKey, work) {
      calls.push({ action: 'recordVideoCarouselEvent', componentKey, work })
      return Promise.resolve()
    }
  })
  return { page, calls, patches }
}

function videoRoute(pagePath) {
  const folder = pagePath.includes('team-portfolios') ? 'team-portfolios' : 'portfolios'
  return `/pages/${folder}/video-player/video-player`
}

function finishNavigation(options) {
  const messages = []
  options.success({ eventChannel: { emit(event, payload) { messages.push({ event, payload }) } } })
  return messages
}

function assertNoInlinePreview(page, calls, patches) {
  assert.equal(Boolean(page.data.videoPreviewFullscreen), false)
  assert.equal(page.data.videoPreviewUrl || '', '')
  assert.equal(page.data.videoPreview || null, null)
  assert.equal(patches.some((patch) => patch.videoPreviewFullscreen || patch.videoPreviewUrl || patch.videoPreview), false,
    '微信独立播放页期间不能挂载页面内的视频弹层')
  assert.equal(calls.some((call) => ['createVideoContext', 'pause', 'exitFullScreen', 'stop'].includes(call.action)), false,
    '来源页面生命周期不能通过 VideoContext 操控独立播放页')
}

function assertCarouselEventCount(pagePath, calls) {
  const events = calls.filter((call) => ['sendEvent', 'recordVideoCarouselEvent', 'recordWorkEvent'].includes(call.action))
  if (!pagePath.includes('visitor-portfolio')) {
    assert.deepEqual(events, [], '维护者预览不应产生访客统计')
    return
  }
  assert.equal(events.length, 1, '一次轮播点击只记录一次播放事件')
  const event = events[0]
  if (pagePath.includes('team-portfolios')) {
    assert.equal(event.action, 'sendEvent')
    assert.equal(event.payload.eventType, 'VIDEO_PLAYED')
    assert.equal(event.payload.componentKey, 'carousel-1')
    assert.equal(event.payload.workId, WORK.workId)
  } else {
    assert.equal(event.action, 'recordVideoCarouselEvent')
    assert.equal(event.componentKey, 'carousel-1')
    assert.equal(event.work.workId, WORK.workId)
  }
}

for (const pagePath of PAGES) {
  for (const platform of ['ios', 'android', 'devtools']) {
    test(`${pagePath}: ${platform} 轮播使用独立播放页，页面隐藏与返回不干扰播放或重复上报`, async () => {
      let navigationOptions
      const { page, calls, patches } = loadPage(pagePath, { wx: {
        getDeviceInfo() { return { platform } },
        navigateTo(options) { calls.push({ action: 'navigateTo' }); navigationOptions = options }
      } })
      const browserContext = pagePath.includes('visitor-portfolio') ? {
        idempotencyKey: 'visit-context',
        isInvalid() { return false }, isDisposed() { return false },
        show() {}, hide() {}, dispose() {}
      } : undefined
      page.browserContext = browserContext
      const result = page.handleVideoCarouselPlay({ detail: { componentKey: 'carousel-1', work: WORK } })
      assert.equal(typeof result.then, 'function', '独立播放页应等待微信 API 的打开结果')
      await Promise.resolve()
      assert.ok(navigationOptions, '所有平台都必须进入微信独立视频页')
      assert.equal(navigationOptions.url, videoRoute(pagePath))
      assert.equal(navigationOptions.url.includes(WORK.mediaUrl), false, '导航地址不能携带媒体 URL')
      assert.equal(navigationOptions.url.includes('?'), false)
      assert.equal(calls.filter((call) => call.action === 'navigateTo').length, 1)
      const navigationIndex = calls.findIndex((call) => call.action === 'navigateTo')
      const stopAction = pagePath.includes('team-portfolios') ? 'stopSingleWorkVideos' : 'stopActiveSingleWorkVideo'
      for (const action of ['pauseBackgroundAudio', stopAction]) {
        const index = calls.findIndex((call) => call.action === action)
        assert.ok(index >= 0 && index < navigationIndex, '打开独立播放页前应暂停背景音和单作品视频')
      }
      assert.equal(page.data.videoPreviewVisible, true, '独立播放页期间应暂停文字组件的背景视频')
      assertNoInlinePreview(page, calls, patches)
      assertCarouselEventCount(pagePath, calls)

      const messages = finishNavigation(navigationOptions)
      assert.equal(messages.length, 1)
      assert.equal(messages[0].event, 'portfolioVideoPlayer')
      assert.deepEqual(messages[0].payload, {
        url: WORK.mediaUrl, poster: WORK.coverUrl, title: WORK.title,
        browserContext: browserContext || null, contextId: browserContext ? browserContext.idempotencyKey : ''
      })
      assert.equal(messages[0].payload.browserContext, browserContext || null, '访客上下文必须原样传递，预览不附加访客上下文')
      assert.equal(await result, true)
      assert.equal(page.data.videoPreviewVisible, true, 'success 只表示预览打开，不应提前恢复背景视频')
      page.onHide()
      assert.equal(page.data.videoPreviewVisible, true)
      page.onShow()
      assert.equal(page.data.videoPreviewVisible, false, '返回作品集后解除文字组件的背景视频暂停')
      page.onUnload()
      assertNoInlinePreview(page, calls, patches)
      assertCarouselEventCount(pagePath, calls)
      assert.equal(calls.filter((call) => call.action === 'navigateTo').length, 1)
    })

    test(`${pagePath}: ${platform} 独立播放页失败返回 false 并解除背景暂停，不回退到故障弹层`, async () => {
      let navigationOptions
      const { page, calls, patches } = loadPage(pagePath, { wx: {
        getDeviceInfo() { return { platform } },
        navigateTo(options) { navigationOptions = options }
      } })
      const result = page.handleVideoCarouselPlay({ detail: { componentKey: 'carousel-1', work: WORK } })
      await Promise.resolve()
      assert.ok(navigationOptions)
      assert.equal(page.data.videoPreviewVisible, true)
      navigationOptions.fail({ errMsg: 'navigateTo:fail' })
      assert.equal(await result, false)
      assert.equal(page.data.videoPreviewVisible, false)
      const toasts = calls.filter((call) => call.action === 'toast')
      assert.equal(toasts.length, 1)
      assert.match(toasts[0].options.title, /视频|播放/)
      assertNoInlinePreview(page, calls, patches)
      assertCarouselEventCount(pagePath, calls)
    })

    if (!pagePath.includes('team-portfolios')) {
      for (const succeeded of [true, false]) {
        test(`${pagePath}: ${platform} 作品列表点击等待独立播放页${succeeded ? '成功' : '失败'}，保留地址兜底和统计`, async () => {
          let navigationOptions
          const { page, calls, patches } = loadPage(pagePath, { wx: {
            getDeviceInfo() { return { platform } },
            navigateTo(options) { navigationOptions = options }
          } })
          const result = page.handleWorkTap({ detail: { ...WORK, mediaUrl: '', previewUrl: 'list-video.mp4', coverUrl: '' } })
          await Promise.resolve()
          assert.ok(navigationOptions, '列表 worktap 事件必须经过真实页面入口进入独立播放页')
          assert.equal(navigationOptions.url, videoRoute(pagePath))
          if (succeeded) {
            const messages = finishNavigation(navigationOptions)
            assert.deepEqual(messages, [{ event: 'portfolioVideoPlayer', payload: {
              url: 'list-video.mp4', poster: '', title: WORK.title, browserContext: null, contextId: ''
            } }])
          } else navigationOptions.fail({ errMsg: 'navigateTo:fail' })
          assert.equal(await result, succeeded)
          assert.equal(page.data.videoPreviewVisible, succeeded)
          assertNoInlinePreview(page, calls, patches)
          assert.equal(calls.filter((call) => call.action === 'toast').length, succeeded ? 0 : 1)
          const events = calls.filter((call) => call.action === 'recordWorkEvent')
          assert.equal(events.length, pagePath.includes('visitor-portfolio') ? 1 : 0)
          if (events.length) {
            assert.equal(events[0].work.workId, WORK.workId)
            assert.equal(events[0].work.mediaType, 'VIDEO')
          }
        })
      }
    }
  }

  test(`${pagePath}: 不依赖平台信息，导航不可用时提示失败并解除背景暂停`, async () => {
    const { page, calls, patches } = loadPage(pagePath, { wx: { navigateTo: undefined } })
    assert.equal(await page.handleVideoCarouselPlay({ detail: { componentKey: 'carousel-1', work: WORK } }), false)
    assert.equal(page.data.videoPreviewVisible, false)
    const toasts = calls.filter((call) => call.action === 'toast')
    assert.equal(toasts.length, 1)
    assert.match(toasts[0].options.title, /视频|播放/)
    assertNoInlinePreview(page, calls, patches)
    assertCarouselEventCount(pagePath, calls)
  })

  test(`${pagePath}: 重复点击不叠加播放页，返回后可以再次打开`, async () => {
    const navigations = []
    const { page, calls, patches } = loadPage(pagePath, { wx: {
      navigateTo(options) { navigations.push(options) }
    } })
    const first = page.openVideoPreview(WORK)
    assert.equal(await page.openVideoPreview(WORK), false)
    assert.equal(navigations.length, 1)
    finishNavigation(navigations[0])
    assert.equal(await first, true)
    assert.equal(await page.openVideoPreview(WORK), false)
    page.onHide()
    assert.equal(page.data.videoPreviewVisible, true)
    page.onShow()
    const reopened = page.openVideoPreview(WORK)
    assert.equal(navigations.length, 2)
    finishNavigation(navigations[1])
    assert.equal(await reopened, true)
    assertNoInlinePreview(page, calls, patches)
  })

  test(`${pagePath}: 卸载后到达的预览失败不再回写页面，过期点击不能打开新预览`, async () => {
    let navigationOptions
    const { page, calls, patches } = loadPage(pagePath, { wx: {
      navigateTo(options) { calls.push({ action: 'navigateTo' }); navigationOptions = options }
    } })
    const result = page.openVideoPreview(WORK)
    page.onUnload()
    patches.length = 0
    navigationOptions.fail({ errMsg: 'navigateTo:fail' })
    assert.equal(await result, false)
    assert.deepEqual(patches, [])
    assert.equal(await page.openVideoPreview(WORK), false)
    assert.equal(calls.filter((call) => call.action === 'navigateTo').length, 1)
    assertNoInlinePreview(page, calls, patches)
  })

  test(`${pagePath}: 缺少视频地址时不打开独立播放页或上报事件`, async () => {
    const { page, calls, patches } = loadPage(pagePath)
    assert.equal(await page.handleVideoCarouselPlay({ detail: { componentKey: 'carousel-1', work: { ...WORK, mediaUrl: '' } } }), false)
    assert.equal(page.data.videoPreviewVisible, false)
    assert.equal(calls.filter((call) => call.action === 'toast').length, 1)
    assert.equal(calls.some((call) => ['navigateTo', 'recordVideoCarouselEvent', 'recordWorkEvent', 'sendEvent'].includes(call.action)), false)
    assertNoInlinePreview(page, calls, patches)
  })

  test(`${pagePath}: 单作品入口继续激活独立组件的视频，不改用轮播的独立播放页`, async () => {
    const { page, calls, patches } = loadPage(pagePath)
    if (pagePath.includes('team-portfolios')) {
      page.handleSingleWorkActivate({ detail: { componentKey: 'single-1', work: WORK } })
    } else {
      assert.equal(await page.handleSingleWorkTap({ detail: { ...WORK, componentKey: 'single-1' } }), true)
    }
    assert.equal(page.data.activeSingleWorkVideoKey, 'single-1')
    assert.equal(page.data.videoPreviewVisible, false)
    assert.equal(calls.some((call) => call.action === 'navigateTo'), false)
    assert.ok(calls.some((call) => call.action === 'pauseBackgroundAudio'))
    assertNoInlinePreview(page, calls, patches)
    const events = calls.filter((call) => ['recordWorkEvent', 'sendEvent'].includes(call.action))
    assert.equal(events.length, pagePath.includes('visitor-portfolio') ? 1 : 0)
  })

  test(`${pagePath}: 页面移除旧视频弹层及全屏隐藏样式，保留单作品和背景暂停绑定`, () => {
    const wxml = fs.readFileSync(path.join(ROOT, `${pagePath}.wxml`), 'utf8')
    const css = fs.readFileSync(path.join(ROOT, `${pagePath}.wxss`), 'utf8')
    const script = fs.readFileSync(path.join(ROOT, `${pagePath}.js`), 'utf8')
    assert.doesNotMatch(wxml, /work-video-(?:mask|player|panel|viewport|close|backdrop)|video-page-hidden|videoPreviewFullscreen|videoPreviewUrl|handleVideoPreviewFullscreenChange|bindfullscreenchange/)
    assert.doesNotMatch(css, /work-video-(?:mask|player|panel|viewport|close|backdrop)|video-page-hidden/)
    assert.doesNotMatch(script, /videoPreviewFullscreen|videoPreviewUrl|videoPreviewClosePending|handleVideoPreviewFullscreenChange|createVideoContext|previewMedia|openNativeVideoPreview/)
    assert.match(wxml, /background-video-paused="\{\{videoPreviewVisible \|\| fontOpening \|\| loading\}\}"/)
    if (pagePath.includes('team-portfolios')) {
      assert.match(wxml, /<team-single-work\b[^>]*bindactivate="handleSingleWorkActivate"/)
    } else {
      assert.match(wxml, /<portfolio-single-work\b[^>]*bindsingleworktap="handleSingleWorkTap"/)
    }
  })
}

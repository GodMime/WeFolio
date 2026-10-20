const assert = require('node:assert/strict')
const fs = require('node:fs')
const { createRequire } = require('node:module')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

const PAGE_PATH = path.join(__dirname, '../pages/works/works.js')
const PLAYER_ROUTE = '/pages/works/video-player/video-player'
const WORK = { id: 11, mediaType: 'VIDEO', mediaUrl: 'https://cdn.example.com/video.mp4', coverUrl: 'https://cdn.example.com/cover.jpg', title: '测试视频' }

// 回放录屏中的真实缩略图与播放按钮入口，防止视频再次挂载到带底栏的 Skyline 页面。
function loadPage(wxOverrides = {}) {
  const calls = []
  const patches = []
  let page
  vm.runInNewContext(fs.readFileSync(PAGE_PATH, 'utf8'), {
    require: createRequire(PAGE_PATH),
    Page(value) { page = value },
    wx: {
      navigateTo(options) { calls.push({ action: 'navigateTo', options }) },
      showToast(options) { calls.push({ action: 'toast', options }) },
      createVideoContext() { assert.fail('素材库不能操控独立播放页的视频实例') },
      ...wxOverrides
    },
    setTimeout,
    clearTimeout
  }, { filename: PAGE_PATH })
  page.data = structuredClone(page.data)
  page.data.list.works = [WORK]
  page.setData = function (patch) { patches.push(patch); Object.assign(this.data, patch) }
  page.pauseWorkAudio = () => calls.push({ action: 'pauseAudio' })
  page.bootstrap = () => calls.push({ action: 'bootstrap' })
  return { page, calls, patches }
}

function finishNavigation(call) {
  const events = []
  call.options.success({ eventChannel: { emit(event, payload) { events.push({ event, payload }) } } })
  return events
}

for (const handler of ['handlePlayVideoTap', 'handleWorkPreviewTap']) {
  test(`${handler}: 视频播放与全屏不能留在带底栏的 Skyline 素材库页面`, async () => {
    const { page, calls, patches } = loadPage()
    const result = page[handler]({ currentTarget: { dataset: { id: WORK.id } } })
    assert.equal(Boolean(page.data.videoPreview && page.data.videoPreview.src), false,
      '不能在素材库弹层内挂载视频，否则 iPhone 横屏时底栏仍与原生播放器共存')
    const navigation = calls.find(call => call.action === 'navigateTo')
    assert.ok(navigation, '作品播放必须进入独立播放页')
    assert.equal(navigation.options.url, PLAYER_ROUTE)
    assert.ok(calls.findIndex(call => call.action === 'pauseAudio') < calls.indexOf(navigation))
    assert.deepEqual(finishNavigation(navigation), [{ event: 'portfolioVideoPlayer', payload: {
      url: WORK.mediaUrl, poster: WORK.coverUrl, title: WORK.title, browserContext: null, contextId: ''
    } }])
    assert.equal(await result, true)
    page.onHide()
    page.onShow()
    assert.equal(patches.some(patch => patch.videoPreview && patch.videoPreview.src), false)
  })

  for (const mode of ['batchMode', 'sortMode']) {
    test(`${handler}: ${mode} 下不能打开播放页`, async () => {
      const { page, calls } = loadPage()
      page.data[mode] = true
      await page[handler]({ currentTarget: { dataset: { id: WORK.id } } })
      assert.equal(calls.length, 0)
    })
  }
}

test('连续点击只打开一个视频页，返回素材库后可以再次播放', async () => {
  const { page, calls } = loadPage()
  const first = page.openVideoPreview(WORK)
  assert.equal(await page.openVideoPreview(WORK), false)
  const navigations = () => calls.filter(call => call.action === 'navigateTo')
  assert.equal(navigations().length, 1)
  finishNavigation(navigations()[0])
  assert.equal(await first, true)
  page.onHide()
  assert.equal(await page.openVideoPreview(WORK), false)
  page.onShow()
  const second = page.openVideoPreview(WORK)
  assert.equal(navigations().length, 2)
  finishNavigation(navigations()[1])
  assert.equal(await second, true)
})

test('播放返回保留已加载分页和列表位置，其他页面进入仍正常刷新', async () => {
  const { page, calls } = loadPage()
  page.onShow()
  assert.equal(calls.filter(call => call.action === 'bootstrap').length, 1)
  const list = { ...page.data.list, works: [WORK, { ...WORK, id: 12 }], page: 3, hasMore: true }
  page.data.list = list
  page.data.loading = false
  const opened = page.openVideoPreview(WORK)
  finishNavigation(calls.find(call => call.action === 'navigateTo'))
  await opened
  page.onHide()
  page.onShow()
  assert.equal(calls.filter(call => call.action === 'bootstrap').length, 1,
    '播放返回不能重新加载第一页，否则会卸载滚动列表并丢失位置')
  assert.equal(page.data.list, list)
  assert.equal(page.data.loading, false)
  page.onHide()
  page.onShow()
  assert.equal(calls.filter(call => call.action === 'bootstrap').length, 2,
    '非播放页返回仍须刷新作品数据')
})

test('导航失败仅提示错误并允许重试，不回退到素材库内播放', async () => {
  const { page, calls, patches } = loadPage()
  const first = page.openVideoPreview(WORK)
  calls.find(call => call.action === 'navigateTo').options.fail({ errMsg: 'navigateTo:fail page limit' })
  assert.equal(await first, false)
  assert.equal(calls.filter(call => call.action === 'toast').length, 1)
  assert.equal(patches.some(patch => patch.videoPreview || patch.videoPreviewVisible), false)
  const second = page.openVideoPreview(WORK)
  const navigations = calls.filter(call => call.action === 'navigateTo')
  assert.equal(navigations.length, 2)
  finishNavigation(navigations[1])
  assert.equal(await second, true)
})

test('旧导航延迟失败不能解除新播放会话的重复点击保护', async () => {
  const { page, calls } = loadPage()
  const old = page.openVideoPreview(WORK)
  const oldNavigation = calls.find(call => call.action === 'navigateTo')
  page.onHide()
  page.onShow()
  const current = page.openVideoPreview(WORK)
  oldNavigation.options.fail({ errMsg: 'navigateTo:fail cancel' })
  assert.equal(await old, false)
  assert.equal(await page.openVideoPreview(WORK), false)
  const navigations = calls.filter(call => call.action === 'navigateTo')
  assert.equal(navigations.length, 2)
  finishNavigation(navigations[1])
  assert.equal(await current, true)
})

test('素材库卸载后，延迟回调不更新界面且不能再次播放', async () => {
  const { page, calls, patches } = loadPage()
  const pending = page.openVideoPreview(WORK)
  const navigation = calls.find(call => call.action === 'navigateTo')
  page.onUnload()
  patches.length = 0
  navigation.options.fail({ errMsg: 'navigateTo:fail cancel' })
  assert.equal(await pending, false)
  assert.equal(await page.openVideoPreview(WORK), false)
  assert.equal(patches.length, 0)
  assert.equal(calls.filter(call => call.action === 'navigateTo').length, 1)
})

test('视频地址缺失时保持原提示，不打开播放页', async () => {
  const { page, calls } = loadPage()
  assert.equal(await page.openVideoPreview({ ...WORK, mediaUrl: '' }), false)
  assert.equal(calls.some(call => call.action === 'navigateTo'), false)
  assert.equal(calls.find(call => call.action === 'toast').options.title, '视频地址缺失')
})

test('实际路由落到作品分包的独立 webview 页，源页面不再挂载视频预览弹层', () => {
  const root = path.join(__dirname, '..')
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
  const worksPackage = app.subPackages.find(item => item.root === 'pages/works')
  assert.ok(worksPackage.pages.includes('video-player/video-player'))
  const config = JSON.parse(fs.readFileSync(path.join(root, `${PLAYER_ROUTE}.json`), 'utf8'))
  assert.equal(config.renderer, 'webview')
  assert.equal(config.navigationStyle, 'default')
  const source = fs.readFileSync(path.join(root, 'pages/works/works.wxml'), 'utf8')
  assert.doesNotMatch(source, /workPreviewVideo|video-preview-/)
  const player = fs.readFileSync(path.join(root, `${PLAYER_ROUTE}.wxml`), 'utf8')
  assert.doesNotMatch(player, /tabbar|navigation-bar|catchtap|catchtouch/)
  assert.match(player, /bindfullscreenchange="handleFullscreenChange"/)
})

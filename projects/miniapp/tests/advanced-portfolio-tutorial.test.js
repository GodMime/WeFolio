const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '..')
const TUTORIAL_ROUTE = '/pages/portfolios/tutorial/advanced-portfolio-tutorial'
const TUTORIAL_URL = 'https://cdn2.we-folio.dingchenyong.top/system/wefolio-ai-portfolio-v11.mp4'
const TUTORIAL_TITLE = '高级作品集使用教程'
const CODE_URL = 'https://cdn2.we-folio.dingchenyong.top/system/yingqi-folio-teach-vedio.png'

function pageFor({ wxApi = {} } = {}) {
  const { createAdvancedPortfolioTutorialPage } = require('../pages/portfolios/utils/advanced-portfolio-tutorial')
  const definition = createAdvancedPortfolioTutorialPage({ wxApi })
  return {
    ...definition,
    data: structuredClone(definition.data),
    setData(value, callback) { Object.assign(this.data, value); if (callback) callback() },
    getOpenerEventChannel() { assert.fail('独立教程不得依赖来源页面事件通道') }
  }
}

test('扫码和卡片直接进入固定公开教程，外部参数不能覆盖视频或携带身份', () => {
  const shares = []
  const page = pageFor({ wxApi: {
    showShareMenu: options => shares.push(options),
    getStorageSync: () => assert.fail('观看教程无需读取维护者身份'),
    login: () => assert.fail('观看教程无需微信登录')
  } })
  page.onLoad({ scene: 'advanced-portfolio', url: 'https://example.com/private.mp4', title: '外部标题', token: 'ignored' })

  assert.equal(page.data.url, TUTORIAL_URL)
  assert.equal(page.data.title, TUTORIAL_TITLE)
  assert.equal(shares.length, 1)
  assert.deepEqual(shares[0].menus, ['shareAppMessage', 'shareTimeline'])
  assert.deepEqual(page.onShareAppMessage(), { title: TUTORIAL_TITLE, path: TUTORIAL_ROUTE })
  assert.deepEqual(page.onShareTimeline(), { title: TUTORIAL_TITLE, query: '' })
})

test('教程二维码使用固定公开图片，打开时暂停视频且重复点击不重置加载状态', () => {
  const calls = []
  const page = pageFor({ wxApi: {
    createVideoContext: () => ({ pause: () => calls.push('pause') }),
    request: () => assert.fail('固定教程码无需请求后端'),
    previewImage: () => assert.fail('透明二维码必须保留在白底弹层内')
  } })
  page.onLoad({ codeUrl: 'https://example.com/other.png' })
  assert.equal(page.data.codeUrl, CODE_URL)
  assert.equal(page.data.codeVisible, false)
  assert.equal(page.data.codeLoading, false)
  assert.equal(page.data.codeError, false)
  assert.equal(page.data.codeImageMounted, true)
  const originalSetData = page.setData
  page.setData = function(value) { calls.push('setData'); originalSetData.call(this, value) }
  page.handleShowCode()
  assert.deepEqual(calls, ['pause', 'setData'])
  page.handleVideoPlay()
  assert.deepEqual(calls, ['pause', 'setData', 'pause'], '弹层打开后延迟播放事件也必须暂停')
  assert.equal(page.data.codeVisible, true)
  assert.equal(page.data.codeLoading, true)
  assert.equal(page.data.codeError, false)
  assert.equal(page.data.codeImageMounted, true)
  page.handleCodeLoad()
  assert.equal(page.data.codeLoading, false)
  const completed = structuredClone(page.data)
  page.handleShowCode()
  assert.deepEqual(page.data, completed, '弹层可见时重复点击不得重新进入加载')
})

test('图片加载失败显示重试，重试必须重新挂载图片再等待加载完成', () => {
  const page = pageFor()
  page.onLoad()
  page.handleShowCode()
  page.handleCodeError()
  assert.equal(page.data.codeLoading, false)
  assert.equal(page.data.codeError, true)
  let rendered
  page.setData = (value, callback) => {
    Object.assign(page.data, value)
    if (callback) rendered = callback
  }
  page.handleRetryCode()
  assert.equal(page.data.codeImageMounted, false)
  assert.equal(typeof rendered, 'function')
  rendered()
  assert.equal(page.data.codeImageMounted, true)
  assert.equal(page.data.codeLoading, true)
  assert.equal(page.data.codeError, false)
  assert.equal(page.data.codeUrl, CODE_URL)
  page.handleCodeLoad()
  assert.equal(page.data.codeLoading, false)
})

test('关闭二维码后忽略延迟图片事件，再次打开恢复初始加载状态', () => {
  const page = pageFor()
  page.onLoad()
  page.handleShowCode()
  page.handleCodeError()
  page.handleCloseCode()
  assert.equal(page.data.codeVisible, false)
  assert.equal(page.data.codeLoading, false)
  const closed = structuredClone(page.data)
  page.handleCodeLoad()
  page.handleCodeError()
  assert.deepEqual(page.data, closed)
  page.handleShowCode()
  assert.equal(page.data.codeVisible, true)
  assert.equal(page.data.codeLoading, true)
  assert.equal(page.data.codeError, false)
  assert.equal(page.data.codeImageMounted, true)
})

test('隐藏页面关闭二维码并暂停声音，返回不会自动打开弹层或恢复播放', () => {
  const calls = []
  const page = pageFor({ wxApi: {
    createVideoContext: () => ({ pause: () => calls.push('pause'), play: () => calls.push('play') })
  } })
  page.onLoad()
  page.handleShowCode()
  page.onHide()
  assert.equal(page.data.codeVisible, false)
  assert.equal(page.data.codeLoading, false)
  const hidden = structuredClone(page.data)
  page.handleCodeLoad()
  page.handleCodeError()
  page.handleShowCode()
  assert.deepEqual(page.data, hidden)
  page.onShow()
  assert.equal(page.data.codeVisible, false)
  assert.equal(calls.includes('play'), false)
  calls.length = 0
  page.onShareAppMessage()
  page.onShareTimeline()
  assert.deepEqual(calls, ['pause', 'pause'])
})

test('二维码重试渲染回调和图片事件在卸载后不能更新页面', () => {
  const page = pageFor()
  page.onLoad()
  page.handleShowCode()
  page.handleCodeError()
  let rendered
  page.setData = (value, callback) => {
    Object.assign(page.data, value)
    if (callback) rendered = callback
  }
  page.handleRetryCode()
  assert.equal(typeof rendered, 'function')
  page.onUnload()
  page.setData = () => assert.fail('卸载后不得更新二维码弹层')
  rendered()
  page.handleCodeLoad()
  page.handleCodeError()
  page.handleRetryCode()
  page.handleShowCode()
})

test('离开教程暂停或停止视频，播放错误可恢复重试', () => {
  const videoCalls = []
  const page = pageFor({ wxApi: { createVideoContext: () => ({
    pause: () => videoCalls.push('pause'),
    stop: () => videoCalls.push('stop'),
    play: () => videoCalls.push('play'),
    exitFullScreen: () => videoCalls.push('exitFullScreen')
  }) } })
  page.onLoad()
  page.handleVideoError()
  assert.equal(page.data.videoError, true)
  page.handleRetryVideo()
  assert.equal(page.data.videoError, false)
  page.onHide()
  assert.ok(videoCalls.includes('pause'))
  page.onUnload()
  assert.ok(videoCalls.includes('stop'))
})

test('视频重试先卸载再挂载原生组件，换用新视频上下文，隐藏后拦截延迟播放', () => {
  const calls = []
  let contexts = 0
  const page = pageFor({ wxApi: { createVideoContext: () => {
    const id = ++contexts
    return { pause: () => calls.push(`pause:${id}`) }
  } } })
  page.onLoad()
  page.onHide()
  page.onShow()
  assert.equal(contexts, 1)
  page.handleVideoError()
  const patches = []
  let rendered
  page.setData = (value, callback) => {
    patches.push(value)
    Object.assign(page.data, value)
    if (callback) rendered = callback
  }
  page.handleRetryVideo()
  assert.equal(page.data.videoMounted, false)
  assert.equal(page.data.videoError, true, '旧组件移除前继续保留错误状态')
  assert.equal(typeof rendered, 'function')
  rendered()
  assert.equal(page.data.videoMounted, true)
  assert.equal(page.data.videoError, false)
  assert.equal(patches.length, 2)
  page.onHide()
  assert.equal(contexts, 2, '重试后的播放控制不能使用旧原生组件的上下文')
  page.handleVideoPlay()
  assert.deepEqual(calls.slice(-2), ['pause:2', 'pause:2'])
})

test('视频重试渲染回调在页面卸载后不会重新挂载播放器', () => {
  const page = pageFor()
  page.onLoad()
  let rendered
  page.setData = (value, callback) => {
    Object.assign(page.data, value)
    if (callback) rendered = callback
  }
  page.handleRetryVideo()
  page.onUnload()
  page.setData = () => assert.fail('重试回调不得重新挂载已离开的教程')
  rendered()
})

test('教程页面注册竖屏原生视频与分享入口，不引入身份或访客统计', () => {
  const pageBase = path.join(ROOT, TUTORIAL_ROUTE.slice(1))
  const config = JSON.parse(fs.readFileSync(`${pageBase}.json`, 'utf8'))
  const wxml = fs.readFileSync(`${pageBase}.wxml`, 'utf8')
  const wxss = fs.readFileSync(`${pageBase}.wxss`, 'utf8')
  const source = fs.readFileSync(`${pageBase}.js`, 'utf8')
    + fs.readFileSync(path.join(ROOT, 'pages/portfolios/utils/advanced-portfolio-tutorial.js'), 'utf8')
  assert.equal(config.renderer, 'webview')
  assert.equal(config.pageOrientation, 'portrait')
  assert.match(wxml, /<video\b/)
  assert.match(wxml, /object-fit="contain"/)
  assert.match(wxml, /open-type="share"/)
  assert.match(wxml, /bindtap="handleShowCode"/)
  assert.match(wxml, /bindtap="handleRetryVideo"/)
  const codeImage = wxml.match(/<image\b[^>]*src="\{\{codeUrl\}\}"[^>]*>/)?.[0]
  assert.ok(codeImage, '弹层展示固定二维码图片')
  assert.match(codeImage, /show-menu-by-longpress="\{\{true\}\}"/)
  assert.match(codeImage, /bindload="handleCodeLoad"/)
  assert.match(codeImage, /binderror="handleCodeError"/)
  assert.match(wxml, /bindtap="handleCloseCode"/)
  assert.match(wxml, /bindtap="handleRetryCode"/)
  const imageClasses = codeImage.match(/class="([^"]+)"/)?.[1].split(/\s+/) || []
  assert.ok(imageClasses.some(name => {
    const rule = wxss.match(new RegExp(`\\.${name}\\s*\\{([^}]*)\\}`))?.[1] || ''
    return /background(?:-color)?:\s*(?:#fff(?:fff)?|white)\s*;/i.test(rule)
  }), '透明黑色二维码图片本身必须有白色背景')
  assert.doesNotMatch(source, /require\([^)]*(?:request|session|visit-activity|portfolio-video-player-page)/)
  assert.doesNotMatch(source, /getOpenerEventChannel|hasLocalToken|handleMaintainerAuthRequired|wxApi\.(?:request|previewImage)|requestFn|\/api\//)

  // 作品播放器仍不允许将私有作品播放地址独立转发。
  for (const folder of ['portfolios', 'team-portfolios', 'works']) {
    const { createVideoPlayerPage } = require(`../pages/${folder}/utils/portfolio-video-player-page`)
    const hidden = []
    const definition = createVideoPlayerPage({ wxApi: { hideShareMenu: value => hidden.push(value) } })
    const player = { ...definition, data: structuredClone(definition.data), setData(value) { Object.assign(this.data, value) } }
    player.onLoad()
    assert.deepEqual(hidden, [{ menus: ['shareAppMessage', 'shareTimeline'] }])
    assert.equal(player.data.available, false)
  }
})

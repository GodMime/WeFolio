import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import vm from 'node:vm'
import { ANDROID_ROOT, GENERATED_ROOT, MINIAPP_ROOT } from '../scripts/paths.mjs'
import { check } from '../scripts/check.mjs'

const pages = await check()

/** 用隔离上下文加载小程序 CommonJS，不访问真实微信或后端。 */
async function moduleAt(relative, dependencies = {}, globals = {}) {
  const module = { exports: {} }
  const source = await fs.readFile(path.join(ANDROID_ROOT, relative), 'utf8')
  vm.runInNewContext(source, {
    module, exports: module.exports,
    require(name) {
      if (Object.hasOwn(dependencies, name)) return dependencies[name]
      throw new Error(`测试未声明依赖：${name}`)
    },
    ...globals
  }, { filename: relative })
  return module.exports
}

test('覆盖全部原页面，唯一排除项是用户明确关闭的支付页面', async () => {
  const original = JSON.parse(await fs.readFile(path.join(MINIAPP_ROOT, 'app.json'), 'utf8'))
  const expected = [...original.pages, ...original.subPackages.flatMap(pkg => pkg.pages.map(route => `${pkg.root}/${route}`))]
    .filter(route => route !== 'pages/recharge/recharge')
  assert.deepEqual(pages, expected)
  await assert.rejects(fs.access(path.join(GENERATED_ROOT, 'pages/recharge/recharge.js')))
})

test('复杂作品集编辑布局和业务工具直接复用原文件内容', async () => {
  for (const relative of [
    'pages/portfolios/standard-edit/portfolio-standard-edit.wxml',
    'pages/portfolios/utils/portfolios.js',
    'pages/team-portfolios/utils/team-portfolios.js',
    'pages/schedule/schedule.wxml',
    'pages/works/works.wxml'
  ]) {
    assert.equal(await fs.readFile(path.join(GENERATED_ROOT, relative), 'utf8'),
      await fs.readFile(path.join(MINIAPP_ROOT, relative), 'utf8'), relative)
  }
})

test('充值和小程序会话接口被拦截，积分与充值记录只读查询保持可用', async () => {
  const { assertRequestAllowed } = await moduleAt('overrides/utils/android-policy.js')
  for (const url of ['/api/mine/recharges/orders', 'https://api.example.test/api/mine/recharges/orders/x/sync']) {
    assert.throws(() => assertRequestAllowed(url, 'POST'), /不支持充值/)
    assert.doesNotThrow(() => assertRequestAllowed(url, 'GET'))
  }
  assert.throws(() => assertRequestAllowed('/api/auth/maintainer/wechat-session/refresh', 'POST'), /移动应用微信授权/)
  assert.doesNotThrow(() => assertRequestAllowed('/api/auth/mobile/wechat-login', 'POST'))
  assert.doesNotThrow(() => assertRequestAllowed('/api/mine/points/transactions', 'GET'))
  const history = await fs.readFile(path.join(GENERATED_ROOT, 'pages/recharge-records/recharge-records.js'), 'utf8')
  assert.doesNotMatch(history, /await this\.syncPendingOrders/)
})

test('无 AppID 时不发起微信或登录请求，允许进入本地体验', async () => {
  let definition
  let destination
  await moduleAt('overrides/pages/login/login.js', {
    '../../utils/request': { request() { throw new Error('不应发出请求') } },
    '../../utils/session': { hasLocalToken: () => false },
    '../../utils/android-config': { mobileAppId: '' }
  }, { Page(value) { definition = value }, wx: { navigateTo({ url }) { destination = url } } })
  const page = { ...definition, data: { ...definition.data }, setData(data) { Object.assign(this.data, data) } }
  await page.onLoad()
  assert.equal(page.data.loginReady, false)
  assert.match(page.data.message, /尚未开通/)
  page.handleExperienceTap()
  assert.equal(destination, '/pages/mock/index/index')
})

test('安卓 OAuth 凭证仅提交移动应用登录接口，成功后保存原维护者令牌', async () => {
  let definition, savedToken, route
  const requests = []
  await moduleAt('overrides/pages/login/login.js', {
    '../../utils/request': { async request(options) { requests.push(options); return { token: 'maintainer-session' } } },
    '../../utils/session': { setToken(token) { savedToken = token } },
    '../../utils/android-config': { mobileAppId: 'wx-test' }
  }, {
    Page(value) { definition = value },
    wx: { miniapp: { login({ success }) { success({ code: 'official-mobile-code' }) } }, reLaunch({ url }) { route = url } }
  })
  const page = { ...definition, data: { ...definition.data, agreed: true, loginReady: true }, setData(data) { Object.assign(this.data, data) } }
  await page.handleLogin()
  assert.equal(requests[0].url, '/api/auth/mobile/wechat-login')
  assert.equal(requests[0].data.code, 'official-mobile-code')
  assert.equal(savedToken, 'maintainer-session')
  assert.equal(route, '/pages/index/index')
  assert.equal(page.data.loading, false)
})

test('匿名浏览保留旧路由，登录访客只走新增安卓路由', async () => {
  const stored = new Map()
  const wxApi = { getStorageSync: key => stored.get(key), setStorageSync: (key, value) => stored.set(key, value) }
  let token = ''
  const adapter = await moduleAt('overrides/utils/android-visitor.js', {
    './session': { getToken: () => token },
    './request': { request() { assert.fail('身份选择不应请求后端') } }
  })
  const anonymous = await adapter.createVisitorIdentity(wxApi)
  assert.match(anonymous.anonymousSessionId, /^timeline-[a-z0-9-]{32,119}$/)
  assert.equal((await adapter.createVisitorIdentity(wxApi)).anonymousSessionId, anonymous.anonymousSessionId)
  token = 'authenticated'
  const identity = await adapter.createVisitorIdentity(wxApi)
  assert.equal(Object.keys(identity).length, 0)
  for (const type of ['portfolios', 'team-portfolios', 'advanced-team-portfolios']) {
    const url = `/api/visitor/${type}/share/open`
    let received
    const request = options => { received = options; return { token: 'visitor-token' } }
    assert.equal(adapter.openVisitorRequest(request, { url, authMode: 'none', data: identity }).token, 'visitor-token')
    assert.equal(received.url, `/api/mobile/visitor/${type}/share/open`)
    assert.equal(received.authMode, 'maintainer')
    adapter.openVisitorRequest(request, { url, authMode: 'none', data: anonymous })
    assert.equal(received.url, url)
    assert.equal(received.authMode, 'none')
  }
  assert.throws(() => adapter.openVisitorRequest(() => {}, { url: '/api/mine/profile' }), /接口无效/)
})

test('两种作品集访客均使用安卓身份适配，原小程序源码保持原实现', async () => {
  for (const relative of ['pages/portfolios/utils/visitor-session.js', 'pages/team-portfolios/utils/team-visitor-session.js']) {
    assert.match(await fs.readFile(path.join(GENERATED_ROOT, relative), 'utf8'), /createVisitorIdentity/)
    assert.doesNotMatch(await fs.readFile(path.join(MINIAPP_ROOT, relative), 'utf8'), /createVisitorIdentity/)
  }
})

test('分享封面先下载为本地文件并保持路径和朋友圈渠道', async () => {
  const share = await moduleAt('overrides/utils/android-share.js', {
    './android-config': { mobileAppId: 'wx-test', miniProgramOriginalId: 'gh_test', shareFallbackUrl: 'https://example.test' }
  })
  let sent
  const wxApi = {
    downloadFile({ success }) { success({ statusCode: 200, tempFilePath: 'wxfile://cover' }) },
    miniapp: { shareMiniProgramMessage(options) { sent = options; options.success({}) } }
  }
  await share.sharePage({ route: 'pages/portfolios/visitor-portfolio/visitor-portfolio',
    onShareTimeline() { return { query: 'shareCode=abc', imageUrl: 'https://example.test/cover', title: '作品集' } }
  }, 1, wxApi)
  assert.equal(sent.imagePath, 'wxfile://cover')
  assert.equal(sent.path, 'pages/portfolios/visitor-portfolio/visitor-portfolio?shareCode=abc')
  assert.equal(sent.scene, 1)
  assert.equal(sent.userName, 'gh_test')
})

test('缺少分享配置时不触发业务分享计数', async () => {
  const share = await moduleAt('overrides/utils/android-share.js', { './android-config': {} })
  await assert.rejects(share.sharePage({ onShareAppMessage() { assert.fail('不应记录分享') } }, 0, {}), /尚未开通/)
})

test('系统音频选择器超过批量上限时拒绝，不静默丢弃文件', async () => {
  const picker = await moduleAt('overrides/pages/works/utils/android-file-picker.js')
  let failed
  picker.chooseAudioFiles({ miniapp: { chooseFile({ success }) { success({ tempFiles: [{}, {}] }) } } }, {
    count: 1, success() { assert.fail('不应返回超量文件') }, fail(error) { failed = error }
  })
  assert.match(failed.errMsg, /最多可选择 1 个/)
})

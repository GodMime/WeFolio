const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '..')
const TUTORIAL_URL = 'https://cdn2.we-folio.dingchenyong.top/system/wefolio-ai-portfolio-v11.mp4'
const TUTORIAL_TITLE = '高级作品集使用教程'
const FAILURE_TOAST = { title: '视频打开失败，请重试', icon: 'none' }

function pageFor(folder, step, requests) {
  const { createAdvancedAiPage, normalizeSource } = require(`../pages/${folder}/utils/advanced-ai-page`)
  const team = folder === 'team-portfolios'
  const definition = createAdvancedAiPage(team, { requestFn: async options => { requests.push(options); return {} } })
  const page = { ...definition, data: structuredClone(definition.data), setData(value) { Object.assign(this.data, value) } }
  const source = normalizeSource({ sourceId: '101', kind: 'WORK', title: '婚礼作品', mediaType: 'VIDEO' }, team)
  page.sourceNumbers = {}
  page.sourceFacts = { 'WORK:101': source }
  page.setData({ loading: false, ownerId: '201', sourceOptions: [source], capabilities: { enabled: true } })
  page.handleSourceTap({ currentTarget: { dataset: { index: 0 } } })
  page.handleNext()
  page.handlePromptInput({ detail: { value: '请先展示 1 号作品，风格简洁。' } })
  if (step === 'sources') page.handleBackToSources()
  page.rememberSourceNumbers()
  return page
}

function withWx(t, wxApi) {
  const previous = global.wx
  global.wx = wxApi
  t.after(() => { if (previous === undefined) delete global.wx; else global.wx = previous })
}

function readPage(folder, extension) {
  return fs.readFileSync(path.join(ROOT, 'pages', folder, 'advanced-ai', `advanced-ai.${extension}`), 'utf8')
}

function readRule(wxss, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const rule = wxss.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
  assert.ok(rule, `缺少样式 ${selector}`)
  return rule[1]
}

for (const folder of ['portfolios', 'team-portfolios']) {
  for (const step of ['sources', 'prompt']) {
    test(`${folder} ${step} 教程打开同分包竖屏视频，返回后保留选择和输入且不触发任务`, async t => {
      const requests = []
      const events = []
      const navigations = []
      withWx(t, {
        navigateTo(options) {
          navigations.push(options.url)
          options.success({ eventChannel: { emit: (...args) => events.push(args) } })
        },
        showToast: () => assert.fail('正常打开教程不应提示错误')
      })
      const page = pageFor(folder, step, requests)
      const before = structuredClone(page.data)
      const sourceNumbers = { ...page.sourceNumbers }

      await page.handleOpenTutorial()
      page.onHide()
      page.onShow()

      assert.deepEqual(navigations, [`/pages/${folder}/video-player/video-player`])
      assert.deepEqual(events, [['portfolioVideoPlayer', {
        url: TUTORIAL_URL, title: TUTORIAL_TITLE, poster: '', browserContext: null, contextId: '', portrait: true
      }]])
      assert.deepEqual(page.data, before, '观看教程不能重新初始化表单或清空已选内容')
      assert.deepEqual(page.sourceNumbers, sourceNumbers, '来源编号必须沿用当前创建会话')
      assert.deepEqual(requests, [], '观看教程及返回不能生成、取消或重新加载任务')
    })

    test(`${folder} ${step} 教程跳转失败提示重试并保留用户数据`, async t => {
      const requests = []
      const toasts = []
      withWx(t, {
        navigateTo: options => options.fail({ errMsg: 'navigateTo:fail page limit' }),
        showToast: options => toasts.push(options)
      })
      const page = pageFor(folder, step, requests)
      const before = structuredClone(page.data)

      await page.handleOpenTutorial()

      assert.deepEqual(toasts, [FAILURE_TOAST])
      assert.deepEqual(page.data, before)
      assert.deepEqual(requests, [])
    })
  }

  test(`${folder} 修改作品集或管理内容不能通过教程处理器跳转`, async t => {
    withWx(t, {
      navigateTo: () => assert.fail('非新建流程不应打开新建教程'),
      showToast: () => assert.fail('隐藏入口不应额外提示错误')
    })
    for (const state of [{ mode: 'MODIFY_PAGE' }, { mode: 'MODIFY_PAGE', managingSources: true }, { managingSources: true }]) {
      const requests = []
      const page = pageFor(folder, 'sources', requests)
      page.setData(state)
      const before = structuredClone(page.data)
      await page.handleOpenTutorial()
      assert.deepEqual(page.data, before)
      assert.deepEqual(requests, [])
    }
  })

  test(`${folder} 两步教程入口位于独立标题下的说明行，仅新建可见并保留足够点击区域`, () => {
    const wxml = readPage(folder, 'wxml')
    const wxss = readPage(folder, 'wxss')
    const rows = Array.from(wxml.matchAll(/<view\b[^>]*class="selection-subtitle-row"[^>]*>[\s\S]*?<\/button>\s*<\/view>/g))
    assert.equal(rows.length, 2, '选择内容与描述需求各有一个教程入口')
    for (const match of rows) {
      const row = match[0]
      assert.match(row, /class="help-text"/)
      assert.doesNotMatch(row, /selection-title/, '主标题独占一行，教程只与辅助说明同行')
      assert.match(wxml.slice(0, match.index), /<view class="selection-title">[^<]*<\/view>\s*$/, '说明行应位于完整标题之后')
      assert.match(row, /wx:if="\{\{mode === 'CREATE' && !managingSources\}\}"/, '入口或所在说明行仅在新建时可见')
      const button = row.match(/<button\b[^>]*class="tutorial-entry"[^>]*>[\s\S]*?<\/button>/)?.[0]
      assert.ok(button, '说明行应包含小播放图标与教程按钮')
      assert.match(button, /bindtap="handleOpenTutorial"/)
      assert.match(button, /aria-label="[^"]+"/)
      assert.match(button, /src="\/assets\/system\/video-tutorial\.svg"/)
      assert.match(button, />教程<\/text>/)
    }
    assert.match(readRule(wxss, '.selection-subtitle-row'), /display:\s*flex;/)
    const entryRule = readRule(wxss, '.advanced-ai-page .tutorial-entry')
    assert.ok(Number(entryRule.match(/min-height:\s*([\d.]+)rpx;/)?.[1]) >= 56, '紧凑布局下点击区域仍须完整覆盖教程胶囊')
    assert.match(readRule(wxss, '.tutorial-icon'), /width:\s*32rpx;/)
    assert.match(readRule(wxss, '.tutorial-icon'), /height:\s*32rpx;/)
    assert.ok(fs.existsSync(path.join(ROOT, 'assets/system/video-tutorial.svg')))
    const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
    const subpackage = app.subPackages.find(item => item.root === `pages/${folder}`)
    assert.ok(subpackage.pages.includes('video-player/video-player'), '教程播放必须留在当前已注册分包')
  })
}

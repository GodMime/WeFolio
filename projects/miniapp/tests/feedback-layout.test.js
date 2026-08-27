const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

const MINIAPP_ROOT = path.join(__dirname, '..')

function readProjectFile(relativePath) {
  return fs.readFileSync(path.join(MINIAPP_ROOT, relativePath), 'utf8')
}

function readJson(relativePath) {
  return JSON.parse(readProjectFile(relativePath))
}

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = content.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
}

function createFeedbackHistoryMeasurementPage() {
  let pageDefinition
  const feedbackModule = {
    MAX_ATTACHMENT_COUNT: 3,
    addChosenFeedbackMedia() {},
    createFeedbackDraft() { return { idempotencyKey: 'append', description: '', attachments: [] } },
    fetchFeedbackDetail() {},
    fetchFeedbackList() {},
    mergeFeedbackPage() {},
    normalizeFeedbackDetail(raw = {}) { return Object.assign({ rounds: [] }, raw) },
    normalizeFeedbackPage() { return { pageNo: 1, pageSize: 20, total: 0, hasMore: false, items: [] } },
    submitFeedbackDraft() {},
    validateFeedbackDescription() { return { valid: false } }
  }
  const sandbox = {
    Page(definition) { pageDefinition = definition },
    clearTimeout,
    setTimeout,
    wx: {},
    require(request) {
      if (request === '../../utils/feedback') return feedbackModule
      if (request === '../../utils/session') {
        return { handleMaintainerAuthRequired() {}, hasLocalToken() { return true } }
      }
      throw new Error(`unexpected require: ${request}`)
    }
  }
  vm.runInNewContext(readProjectFile('pages/feedback-history/feedback-history.js'), sandbox)
  const page = Object.assign({}, pageDefinition, {
    data: structuredClone(pageDefinition.data),
    setData(values, callback) {
      Object.assign(this.data, values)
      if (typeof callback === 'function') callback()
    }
  })
  page.data.detailVisible = true
  page.data.detailLoading = false
  page.data.detail = { id: 1, canAppendRound: false, rounds: [] }
  return page
}

test('feedback pages are registered as complete main package pages', () => {
  const appJson = readJson('app.json')
  const pageBases = ['pages/feedback/feedback', 'pages/feedback-history/feedback-history']

  pageBases.forEach((pageBase) => {
    assert.ok(appJson.pages.includes(pageBase))
    ;['.js', '.json', '.wxml', '.wxss'].forEach((extension) => {
      assert.equal(fs.existsSync(path.join(MINIAPP_ROOT, `${pageBase}${extension}`)), true)
    })
    assert.equal(
      readJson(`${pageBase}.json`).usingComponents['navigation-bar'],
      '/components/navigation-bar/navigation-bar'
    )
  })
})

test('new feedback page keeps loading error content states and only uploads on submit', () => {
  const pageJs = readProjectFile('pages/feedback/feedback.js')
  const pageWxml = readProjectFile('pages/feedback/feedback.wxml')
  const pageWxss = readProjectFile('pages/feedback/feedback.wxss')
  const panelRule = readRule(pageWxss, '.feedback-panel')
  const inputRule = readRule(pageWxss, '.description-input')
  const submitRule = readRule(pageWxss, '.submit-button')
  const footerRule = readRule(pageWxss, '.feedback-footer')
  const historyActionRule = readRule(pageWxss, '.history-action')

  assert.match(pageJs, /require\('\.\.\/\.\.\/utils\/feedback'\)/)
  assert.match(pageJs, /fetchFeedbackCreationState/)
  assert.match(pageJs, /createFeedbackDraft/)
  assert.match(pageJs, /addChosenFeedbackMedia/)
  assert.match(pageJs, /submitFeedbackDraft/)
  assert.match(pageJs, /hasLocalToken/)
  assert.match(pageJs, /handleMaintainerAuthRequired/)
  assert.match(pageJs, /wx\.chooseMedia\(/)
  assert.match(pageJs, /new Promise\(\(resolve, reject\) => wx\.chooseMedia\(\{[\s\S]*success:\s*resolve,[\s\S]*fail:\s*reject/)
  assert.match(pageJs, /handleDescriptionInput/)
  assert.match(pageJs, /function isFeedbackSubmitDisabled\(/)
  assert.match(pageJs, /submitDisabled:\s*isFeedbackSubmitDisabled\(/)
  assert.match(pageJs, /onShow\(\)[\s\S]*loadCreationState\(\{ silent: true \}\)/)
  assert.match(pageJs, /Array\.from\([^)]+\)\.length/)
  assert.match(pageJs, /handleRemoveAttachment/)
  assert.match(pageJs, /draft\.attachments\.filter/)
  assert.match(pageJs, /handleSubmit/)
  assert.match(pageJs, /await submitFeedbackDraft\(/)
  assert.match(pageJs, /uploadProgressPercent/)
  assert.match(pageJs, /draft:\s*createFeedbackDraft\(\)/)
  assert.match(pageJs, /const FEEDBACK_HISTORY_PAGE_URL = '\/pages\/feedback-history\/feedback-history'/)
  assert.match(pageJs, /handleHistoryTap\(\)[\s\S]*url:\s*FEEDBACK_HISTORY_PAGE_URL/)
  assert.doesNotMatch(pageJs, /wx\.uploadFile\(/)

  assert.match(pageWxml, /navigation-bar title="问题反馈" back="\{\{true\}\}"/)
  assert.doesNotMatch(pageWxml, /slot="right"/)
  assert.match(pageWxml, /<\/scroll-view>\s*<view class="feedback-footer">[\s\S]*class="history-action"[\s\S]*bindtap="handleHistoryTap"[\s\S]*>\s*历史问题\s*<\/button>/)
  assert.match(pageWxml, /wx:if="\{\{loading\}\}"/)
  assert.match(pageWxml, /wx:elif="\{\{errorMessage\}\}"/)
  assert.match(pageWxml, /class="panel feedback-panel"/)
  assert.match(pageWxml, /maxlength="-1"/)
  assert.match(pageWxml, /\{\{descriptionLength\}\}\s*\/\s*200/)
  assert.match(pageWxml, /wx:for="\{\{attachmentSlots\}\}"/)
  assert.match(pageWxml, /bindtap="handleChooseMedia"/)
  assert.match(pageWxml, /catchtap="handleRemoveAttachment"/)
  assert.match(pageWxml, /disabled="\{\{submitDisabled\}\}"/)
  assert.match(pageWxml, /wx:if="\{\{submitting && draft\.attachments\.length\}\}"[\s\S]*\{\{uploadProgressPercent\}\}%/)
  assert.match(pageWxml, /\{\{creationState\.hintText\}\}/)

  assert.match(panelRule, /border-radius:\s*48rpx/)
  assert.match(panelRule, /background:\s*#ffffff/)
  assert.match(inputRule, /background:\s*#f1f3f5/)
  assert.match(submitRule, /border-radius:\s*999rpx/)
  assert.match(submitRule, /background:\s*#212529/)
  assert.match(footerRule, /flex:\s*none/)
  assert.match(footerRule, /env\(safe-area-inset-bottom\)/)
  assert.match(historyActionRule, /color:\s*#868e96/)
  assert.doesNotMatch(pageWxss, /gradient|orb|bokeh/i)
})

test('new feedback page creates a fresh idempotency key for every page instance', () => {
  let pageDefinition
  let draftSequence = 0
  const feedbackModule = {
    MAX_ATTACHMENT_COUNT: 3,
    addChosenFeedbackMedia() {},
    createFeedbackDraft() {
      draftSequence += 1
      return {
        idempotencyKey: `feedback-draft-${draftSequence}`,
        description: '',
        attachments: []
      }
    },
    fetchFeedbackCreationState() {},
    submitFeedbackDraft() {},
    validateFeedbackDescription() {
      return { valid: false }
    }
  }
  const sessionModule = {
    handleMaintainerAuthRequired() {},
    hasLocalToken() { return true }
  }
  const sandbox = {
    Page(definition) { pageDefinition = definition },
    wx: {},
    require(request) {
      if (request === '../../utils/feedback') return feedbackModule
      if (request === '../../utils/session') return sessionModule
      throw new Error(`unexpected require: ${request}`)
    }
  }
  vm.runInNewContext(readProjectFile('pages/feedback/feedback.js'), sandbox)

  function createPageInstance() {
    const page = Object.assign({}, pageDefinition, {
      data: structuredClone(pageDefinition.data),
      setData(values) { Object.assign(this.data, values) }
    })
    page.bootstrap = () => {}
    page.onLoad()
    return page
  }

  const firstPage = createPageInstance()
  const secondPage = createPageInstance()

  assert.notEqual(firstPage.data.draft.idempotencyKey, secondPage.data.draft.idempotencyKey)
})

test('feedback history page paginates and keeps detail and native video layers mounted', () => {
  const pageJs = readProjectFile('pages/feedback-history/feedback-history.js')
  const pageWxml = readProjectFile('pages/feedback-history/feedback-history.wxml')
  const pageWxss = readProjectFile('pages/feedback-history/feedback-history.wxss')
  const overlayRule = readRule(pageWxss, '.detail-overlay')
  const visibleOverlayRule = readRule(pageWxss, '.detail-overlay.visible')
  const sheetRule = readRule(pageWxss, '.detail-sheet')
  const detailScrollRule = readRule(pageWxss, '.detail-scroll')
  const videoLayerRule = readRule(pageWxss, '.video-preview-layer')
  const historyEmptyRule = readRule(pageWxss, '.history-empty')
  const historyEmptyActionRule = readRule(pageWxss, '.history-empty-action')
  const feedbackStatusRule = readRule(pageWxss, '.feedback-status')
  const feedbackStatusDotRule = readRule(pageWxss, '.feedback-status::before')

  assert.match(pageJs, /require\('\.\.\/\.\.\/utils\/feedback'\)/)
  assert.match(pageJs, /fetchFeedbackList/)
  assert.match(pageJs, /mergeFeedbackPage/)
  assert.match(pageJs, /fetchFeedbackDetail/)
  assert.match(pageJs, /feedbackDetailRequestId/)
  assert.match(pageJs, /catch \(error\) \{[\s\S]*requestId !== this\.feedbackDetailRequestId[\s\S]*return/)
  assert.match(pageJs, /submitFeedbackDraft/)
  assert.match(pageJs, /handleLoadMore/)
  assert.match(pageJs, /handleRefresh/)
  assert.match(pageJs, /handleFeedbackTap/)
  assert.match(pageJs, /wx\.previewImage\(/)
  assert.match(pageJs, /handleOpenVideo/)
  assert.match(pageJs, /handleAppendSubmit/)
  assert.match(pageJs, /function isAppendSubmitDisabled\(/)
  assert.match(pageJs, /appendSubmitDisabled:\s*isAppendSubmitDisabled\(/)
  assert.match(pageJs, /handleAppendUploadProgress/)
  assert.match(pageJs, /onProgress:\s*this\.handleAppendUploadProgress\.bind\(this\)/)
  assert.match(pageJs, /new Promise\(\(resolve, reject\) => wx\.chooseMedia\(\{[\s\S]*success:\s*resolve,[\s\S]*fail:\s*reject/)
  assert.match(pageJs, /await submitFeedbackDraft\([\s\S]*feedbackId:/)
  assert.match(pageJs, /handleMaintainerAuthRequired/)

  assert.match(pageWxml, /bindscrolltolower="handleLoadMore"/)
  assert.match(pageWxml, /refresher-enabled="\{\{true\}\}"/)
  assert.match(pageWxml, /bindrefresherrefresh="handleRefresh"/)
  assert.match(pageWxml, /wx:for="\{\{feedbackPage\.items\}\}"/)
  assert.match(pageWxml, /\{\{item\.feedbackNo\}\}/)
  assert.match(pageWxml, /\{\{item\.descriptionSummary\}\}/)
  assert.match(pageWxml, /\{\{item\.updatedAtText\}\}/)
  assert.match(pageWxml, /class="feedback-status \{\{item\.statusTone\}\}"/)
  assert.match(pageWxml, /class="history-empty-icon"[\s\S]*src="\/assets\/system\/wefolio-feedback-icon\.png"/)
  assert.match(pageWxml, /class="history-empty-action"[\s\S]*bindtap="handleSubmitFeedbackTap"[\s\S]*>去提交问题<\/button>/)
  assert.match(pageWxml, /class="detail-overlay \{\{detailVisible \? 'visible' : ''\}\}"/)
  assert.doesNotMatch(pageWxml, /wx:if="\{\{detailVisible\}\}"/)
  assert.match(
    pageWxml,
    /class="detail-scroll"[\s\S]*style="height: \{\{detailScrollHeightPx\}\}px;"[\s\S]*scroll-y[\s\S]*type="list"/
  )
  assert.match(pageWxml, /class="detail-scroll-content"/)
  assert.match(pageWxml, /class="feedback-status \{\{detail\.statusTone\}\}"/)
  assert.match(pageWxml, /wx:for="\{\{detail\.timeline\}\}"/)
  assert.match(pageWxml, /bindtap="handlePreviewImage"/)
  assert.match(pageWxml, /bindtap="handleOpenVideo"/)
  assert.match(pageWxml, /wx:if="\{\{detail\.canAppendRound\}\}"[\s\S]*bindtap="handleAppendToggle"/)
  assert.match(pageWxml, /\{\{appendDescriptionLength\}\}\s*\/\s*200/)
  assert.match(pageWxml, /wx:if="\{\{appendSubmitting && appendDraft\.attachments\.length\}\}"[\s\S]*\{\{appendUploadProgressPercent\}\}%/)
  assert.match(pageWxml, /class="append-submit"[\s\S]*disabled="\{\{appendSubmitDisabled\}\}"/)
  assert.match(pageWxml, /wx:for="\{\{appendAttachmentSlots\}\}"/)
  assert.match(pageWxml, /class="video-preview-layer \{\{videoPreviewVisible \? 'visible' : ''\}\}"/)
  assert.doesNotMatch(pageWxml, /wx:if="\{\{videoPreviewVisible\}\}"/)
  assert.match(pageWxml, /<video[\s\S]*id="feedbackPreviewVideo"[\s\S]*src="\{\{videoPreviewUrl\}\}"/)

  assert.match(overlayRule, /position:\s*fixed/)
  assert.match(overlayRule, /pointer-events:\s*none/)
  assert.match(visibleOverlayRule, /pointer-events:\s*auto/)
  ;['top', 'right', 'bottom', 'left'].forEach((side) => {
    const sideDeclaration = new RegExp(`(?:^|;)\\s*${side}\\s*:\\s*0(?:rpx|px)?\\s*(?:;|$)`)
    assert.match(overlayRule, sideDeclaration)
    assert.match(videoLayerRule, sideDeclaration)
  })
  assert.doesNotMatch(overlayRule, /inset\s*:/)
  assert.doesNotMatch(videoLayerRule, /inset\s*:/)
  assert.match(sheetRule, /transform:\s*translateY\(32rpx\)/)
  assert.match(sheetRule, /height:\s*auto/)
  assert.match(sheetRule, /max-height:\s*88vh/)
  assert.match(sheetRule, /overflow:\s*hidden/)
  assert.doesNotMatch(sheetRule, /(?:^|;)\s*height:\s*88vh\s*;/)
  assert.doesNotMatch(sheetRule, /max-height:\s*1320rpx/)
  assert.match(detailScrollRule, /flex:\s*none/)
  assert.match(detailScrollRule, /min-height:\s*0/)
  assert.match(videoLayerRule, /position:\s*fixed/)
  assert.match(historyEmptyRule, /display:\s*flex/)
  assert.match(historyEmptyRule, /align-items:\s*center/)
  assert.match(historyEmptyRule, /justify-content:\s*center/)
  assert.match(historyEmptyActionRule, /border-radius:\s*999rpx/)
  assert.match(historyEmptyActionRule, /background:\s*#212529/)
  assert.match(feedbackStatusRule, /display:\s*inline-flex/)
  assert.match(feedbackStatusRule, /align-items:\s*center/)
  assert.match(feedbackStatusRule, /gap:\s*8rpx/)
  assert.match(feedbackStatusRule, /padding:\s*0/)
  assert.doesNotMatch(feedbackStatusRule, /background|border-radius/)
  assert.match(feedbackStatusDotRule, /width:\s*12rpx/)
  assert.match(feedbackStatusDotRule, /height:\s*12rpx/)
  assert.match(feedbackStatusDotRule, /background:\s*currentColor/)
  assert.match(pageWxss, /\.feedback-status\.blue[\s\S]*#2d5f9a/)
  assert.match(pageWxss, /\.feedback-status\.amber[\s\S]*#8a4b09/)
  assert.match(pageWxss, /\.feedback-status\.teal[\s\S]*#0f766e/)
  assert.doesNotMatch(pageWxss, /\.status-pill/)
  assert.doesNotMatch(pageWxss, /gradient|orb|bokeh/i)
})

test('feedback detail sheet uses content height and caps the scroll area at 88vh', () => {
  const page = createFeedbackHistoryMeasurementPage()
  let rects = []
  let queriedSelectors = []
  page.createSelectorQuery = () => ({
    select(selector) {
      queriedSelectors.push(selector)
      return this
    },
    boundingClientRect() { return this },
    exec(callback) { callback(rects) }
  })

  rects = [{ height: 1000 }, { height: 181 }, { height: 1 }, { height: 320 }]
  page.measureDetailScrollHeight()

  assert.equal(page.data.detailScrollHeightPx, 320)
  assert.deepEqual(queriedSelectors, [
    '.detail-overlay',
    '.detail-sheet',
    '.detail-scroll',
    '.detail-scroll-content'
  ])

  queriedSelectors = []
  rects = [{ height: 1000 }, { height: 500 }, { height: 320 }, { height: 900 }]
  page.measureDetailScrollHeight()

  assert.equal(page.data.detailScrollHeightPx, 700)
  assert.equal(queriedSelectors.length, 4)
})

test('feedback detail sheet ignores a measurement completed after closing', () => {
  const page = createFeedbackHistoryMeasurementPage()
  let queryCallback
  page.createSelectorQuery = () => ({
    select() { return this },
    boundingClientRect() { return this },
    exec(callback) { queryCallback = callback }
  })

  page.measureDetailScrollHeight()
  page.handleCloseDetail()
  queryCallback([{ height: 1000 }, { height: 181 }, { height: 1 }, { height: 320 }])

  assert.equal(page.data.detailScrollHeightPx, 1)
})

test('feedback history empty-state action returns and falls back to feedback page', () => {
  let pageDefinition
  let navigateBackOptions
  const redirects = []
  const feedbackModule = {
    MAX_ATTACHMENT_COUNT: 3,
    addChosenFeedbackMedia() {},
    createFeedbackDraft() { return { idempotencyKey: 'append', description: '', attachments: [] } },
    fetchFeedbackDetail() {},
    fetchFeedbackList() {},
    mergeFeedbackPage() {},
    normalizeFeedbackDetail(raw = {}) { return raw },
    normalizeFeedbackPage() { return { pageNo: 1, pageSize: 20, total: 0, hasMore: false, items: [] } },
    submitFeedbackDraft() {},
    validateFeedbackDescription() { return { valid: false } }
  }
  const sandbox = {
    Page(definition) { pageDefinition = definition },
    wx: {
      navigateBack(options) { navigateBackOptions = options },
      redirectTo(options) { redirects.push(options) }
    },
    require(request) {
      if (request === '../../utils/feedback') return feedbackModule
      if (request === '../../utils/session') {
        return { handleMaintainerAuthRequired() {}, hasLocalToken() { return true } }
      }
      throw new Error(`unexpected require: ${request}`)
    }
  }

  vm.runInNewContext(readProjectFile('pages/feedback-history/feedback-history.js'), sandbox)
  pageDefinition.handleSubmitFeedbackTap()

  assert.equal(navigateBackOptions.delta, 1)
  assert.equal(redirects.length, 0)
  navigateBackOptions.fail()
  assert.equal(redirects.length, 1)
  assert.equal(redirects[0].url, '/pages/feedback/feedback')
})

test('background first-page refresh blocks load-more until the fresh page is applied', async () => {
  let pageDefinition
  let resolveFirstPage
  const requests = []
  const firstPageResponse = new Promise((resolve) => { resolveFirstPage = resolve })
  const normalizePage = (raw = {}) => Object.assign({
    pageNo: 1,
    pageSize: 20,
    total: 0,
    hasMore: false,
    items: []
  }, raw)
  const feedbackModule = {
    MAX_ATTACHMENT_COUNT: 3,
    addChosenFeedbackMedia() {},
    createFeedbackDraft() { return { idempotencyKey: 'append', description: '', attachments: [] } },
    fetchFeedbackDetail() {},
    fetchFeedbackList(requestFn, options) {
      requests.push(options)
      if (options.pageNo === 1) return firstPageResponse
      return Promise.resolve(normalizePage({ pageNo: 2, items: [{ id: 2, state: 'next' }] }))
    },
    mergeFeedbackPage(current, incoming) {
      const nextPage = normalizePage(incoming)
      if (nextPage.pageNo === 1) return nextPage
      return Object.assign({}, nextPage, { items: current.items.concat(nextPage.items) })
    },
    normalizeFeedbackDetail(raw = {}) { return Object.assign({ rounds: [] }, raw) },
    normalizeFeedbackPage: normalizePage,
    submitFeedbackDraft() {},
    validateFeedbackDescription() { return { valid: false } }
  }
  const sandbox = {
    Page(definition) { pageDefinition = definition },
    wx: {},
    require(request) {
      if (request === '../../utils/feedback') return feedbackModule
      if (request === '../../utils/session') {
        return { handleMaintainerAuthRequired() {}, hasLocalToken() { return true } }
      }
      throw new Error(`unexpected require: ${request}`)
    }
  }
  vm.runInNewContext(readProjectFile('pages/feedback-history/feedback-history.js'), sandbox)
  const page = Object.assign({}, pageDefinition, {
    data: structuredClone(pageDefinition.data),
    setData(values) { Object.assign(this.data, values) }
  })
  page.data.loading = false
  page.data.feedbackPage = normalizePage({
    pageNo: 1,
    hasMore: true,
    items: [{ id: 1, state: 'stale' }]
  })

  const refresh = page.loadFeedbacks({ background: true })
  page.handleLoadMore()

  assert.deepEqual(requests.map((request) => request.pageNo), [1])
  resolveFirstPage(normalizePage({
    pageNo: 1,
    hasMore: true,
    items: [{ id: 1, state: 'fresh' }]
  }))
  await refresh
  assert.equal(page.data.feedbackPage.items[0].state, 'fresh')

  page.handleLoadMore()
  await new Promise((resolve) => setImmediate(resolve))
  assert.deepEqual(requests.map((request) => request.pageNo), [1, 2])
  assert.deepEqual(page.data.feedbackPage.items.map((item) => item.state), ['fresh', 'next'])
})

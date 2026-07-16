const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../pages/team-portfolios')
const REQUEST_PATH = path.resolve(ROOT, '../../utils/request.js')
const TEAM_LIST_UTILITY_PATH = path.resolve(ROOT, 'utils/team-portfolio-list.js')
const TEAM_UTILITY_PATH = path.resolve(ROOT, 'utils/team-portfolios.js')

function clone(value) { return JSON.parse(JSON.stringify(value)) }
function flush() { return new Promise((resolve) => setImmediate(resolve)) }

function loadPage(relativePath, requestFn, wxOverrides = {}) {
  const pagePath = path.join(ROOT, relativePath)
  const requestKey = require.resolve(REQUEST_PATH)
  const teamListUtilityKey = require.resolve(TEAM_LIST_UTILITY_PATH)
  const teamUtilityKey = require.resolve(TEAM_UTILITY_PATH)
  const oldRequest = require.cache[requestKey]
  const oldTeamListUtility = require.cache[teamListUtilityKey]
  const oldTeamUtility = require.cache[teamUtilityKey]
  const oldPage = global.Page
  const oldWx = global.wx
  let definition
  require.cache[requestKey] = { id: REQUEST_PATH, filename: REQUEST_PATH, loaded: true, exports: { request: requestFn } }
  delete require.cache[teamListUtilityKey]
  delete require.cache[teamUtilityKey]
  global.Page = (value) => { definition = value }
  global.wx = Object.assign({ navigateTo() {}, navigateBack() {}, redirectTo() {}, showToast() {}, showModal({ success }) { success({ confirm: true }) }, stopPullDownRefresh() {}, previewImage() {}, login({ success }) { success({ code: 'code' }) }, setStorageSync() {} }, wxOverrides)
  delete require.cache[require.resolve(pagePath)]
  try { require(pagePath) } finally {
    global.Page = oldPage
    if (oldRequest) require.cache[requestKey] = oldRequest
    else delete require.cache[requestKey]
    if (oldTeamListUtility) require.cache[teamListUtilityKey] = oldTeamListUtility
    else delete require.cache[teamListUtilityKey]
    if (oldTeamUtility) require.cache[teamUtilityKey] = oldTeamUtility
    else delete require.cache[teamUtilityKey]
  }
  return Object.assign({}, definition, {
    data: clone(definition.data),
    setData(patch) { Object.assign(this.data, patch) },
    selectComponent() { return null },
    cleanup() { global.wx = oldWx }
  })
}

test('editor QR picker cancellation is silent and preserves the existing configuration', async () => {
  const toasts = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}), {
    chooseMedia({ fail }) { fail({ errMsg: 'chooseMedia:fail cancel' }) },
    showToast(value) { toasts.push(value) }
  })
  page.setData({
    portfolioId: 7,
    canMaintain: true,
    config: { share: {}, components: [{ componentKey: 'qr-1', componentType: 'QR_CONTACT', config: { title: '联系我', description: '请扫码', qrUrl: 'https://old.example/qr.png' } }] }
  })
  try {
    await page.handleQrChoose({ currentTarget: { dataset: { key: 'qr-1' } } })
    assert.equal(toasts.length, 0)
    assert.equal(page.data.config.components[0].config.qrUrl, 'https://old.example/qr.png')
  } finally { page.cleanup() }
})

test('editor WXML presents all component options in Chinese and binds member source states', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'standard-edit/team-portfolio-standard-edit.wxml'), 'utf8')
  const source = fs.readFileSync(path.join(ROOT, 'standard-edit/team-portfolio-standard-edit.js'), 'utf8')
  for (const label of ['团队资料', '轮播图', '分割线', '双列作品集', '单列作品集', '文字说明', '档期查询', '预留联系信息', '二维码联系']) assert.match(source, new RegExp(label))
  assert.match(wxml, /class="component-title">\{\{item\.displayName\}\}/)
  for (const handler of ['handleCarouselLoadMembers', 'handleGridLoadMembers', 'handleListLoadMembers']) assert.match(wxml, new RegExp(`bindloadmembers="${handler}"`))
  for (const handler of ['handleCarouselRetrySource', 'handleGridRetrySource', 'handleListRetrySource']) assert.match(wxml, new RegExp(`bindtap="${handler}"`))
  assert.match(wxml, /class="source-state"/)
  assert.match(wxml, /class="source-state source-error"/)
  assert.doesNotMatch(wxml, /<team-(?:carousel|member-portfolio-grid|member-portfolio-list)[^>]*\s(?:loading|error-message)=/)
})

test('editor renders the personal-style 1:1 QR crop overlay above the component editor', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'standard-edit/team-portfolio-standard-edit.wxml'), 'utf8')
  const css = fs.readFileSync(path.join(ROOT, 'standard-edit/team-portfolio-standard-edit.wxss'), 'utf8')
  assert.match(wxml, /class="qr-contact-crop-mask \{\{qrContactCropVisible \? 'visible' : ''\}\}"/)
  assert.match(wxml, />1:1</)
  for (const handler of [
    'handleQrContactCropTouchStart',
    'handleQrContactCropTouchMove',
    'handleQrContactCropTouchEnd',
    'handleQrContactCropTouchCancel',
    'handleCloseQrContactCrop',
    'handleConfirmQrContactCrop'
  ]) assert.match(wxml, new RegExp(handler))
  assert.match(wxml, /id="teamQrContactCropCanvas"/)
  assert.match(wxml, /type="2d"/)
  assert.match(css, /\.qr-contact-crop-mask\s*\{[^}]*position:\s*fixed[^}]*z-index:\s*45/)
  assert.match(css, /\.qr-contact-crop-stage\s*\{[^}]*overflow:\s*hidden/)
  assert.match(css, /\.qr-contact-crop-canvas\s*\{[^}]*left:\s*-9999px/)
})

test('team pages protect long text and editor styles its source and component controls', () => {
  const editorCss = fs.readFileSync(path.join(ROOT, 'standard-edit/team-portfolio-standard-edit.wxss'), 'utf8')
  for (const selector of ['.source-state', '.component-list', '.component-row', '.cover-preview']) assert.match(editorCss, new RegExp(`\\${selector}\\s*\\{`))
  assert.match(editorCss, /\.cover-preview\s*\{[^}]*width:\s*360rpx[^}]*height:\s*288rpx/)
  for (const relativePath of ['team-select/team-select.wxss', 'component-library/team-portfolio-component-library.wxss', 'standard-preview/team-portfolio-standard-preview.wxss', 'contact-leads/team-contact-leads.wxss', 'visitor-portfolio/team-visitor-portfolio.wxss']) {
    const css = fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
    assert.match(css, /min-width:\s*0/)
    assert.match(css, /overflow-wrap:\s*anywhere|overflow:\s*hidden|white-space:\s*nowrap/)
  }
})

test('visitor profile retries ticket with refreshed token and sends the first profile PUT with it', async () => {
  const requests = []
  let ticketAttempts = 0
  let openAttempts = 0
  const page = loadPage('visitor-portfolio/team-visitor-portfolio.js', async (options) => {
    requests.push(options)
    if (options.url.endsWith('/visitor-avatar/upload-ticket')) {
      ticketAttempts += 1
      if (ticketAttempts === 1) { const error = new Error('expired'); error.authRequired = true; throw error }
      return { uploadUrl: 'https://upload.example/avatar', publicUrl: 'https://cdn.example/avatar.png' }
    }
    if (options.url.endsWith('/open')) { openAttempts += 1; return { visitorProfileToken: 'T1', components: [] } }
    if (options.url.endsWith('/visitor-profile')) return {}
    throw new Error(`unexpected request: ${options.url}`)
  }, {
    getFileSystemManager() { return { statSync() { return { size: 100 } } } },
    uploadFile({ success }) { success({ statusCode: 204 }) }
  })
  page.setData({ shareCode: 'share-1', sourceType: 'WECHAT_SHARE_CARD', visitorProfileToken: 'T0', visitorProfileForm: { nickname: '访客', avatarPath: '/tmp/avatar.png' } })
  try {
    await page.handleProfileSave()
    const tickets = requests.filter((item) => item.url.endsWith('/visitor-avatar/upload-ticket'))
    const profile = requests.find((item) => item.url.endsWith('/visitor-profile'))
    assert.equal(openAttempts, 1)
    assert.equal(tickets.length, 2)
    assert.equal(tickets[0].data.visitorProfileToken, 'T0')
    assert.equal(tickets[1].data.visitorProfileToken, 'T1')
    assert.equal(profile.data.visitorProfileToken, 'T1')
  } finally { page.cleanup() }
})

test('visitor contact keeps an uncertain key, changes it after input or a 400, and retains failed form data', async () => {
  const requests = []
  const completions = []
  let attempt = 0
  const page = loadPage('visitor-portfolio/team-visitor-portfolio.js', async (options) => {
    requests.push(options)
    attempt += 1
    if (attempt === 1) throw new Error('network')
    if (attempt === 2) return { leadId: 1 }
    if (attempt === 3) { const error = new Error('invalid'); error.statusCode = 400; throw error }
    return { leadId: 2 }
  })
  page.selectComponent = () => ({ completeSubmit(value) { completions.push(value) } })
  const form = { contactName: '客户', phone: '13800000000', wechat: 'client' }
  page.setData({ shareCode: 'share-1', visitRecordId: 2, sourceType: 'WECHAT_SHARE_CARD', contactForms: { 'contact-1': form }, contactSubmitting: {}, pendingContactKeys: {} })
  try {
    const submit = () => page.handleContactSubmit({ currentTarget: { dataset: { key: 'contact-1' } }, detail: { form } })
    await submit()
    const firstKey = requests[0].data.idempotencyKey
    assert.equal(page.data.contactForms['contact-1'].contactName, '客户')
    assert.deepEqual(completions[0], { detail: { success: false, message: '提交失败，请检查信息' } })
    await submit()
    assert.equal(requests[1].data.idempotencyKey, firstKey)
    page.handleContactInput({ currentTarget: { dataset: { key: 'contact-1' } }, detail: { form: Object.assign({}, form, { contactName: '新客户' }) } })
    await submit()
    const inputKey = requests[2].data.idempotencyKey
    assert.notEqual(inputKey, firstKey)
    await submit()
    assert.notEqual(requests[3].data.idempotencyKey, inputKey)
  } finally { page.cleanup() }
})

test('editor source requests coalesce by fingerprint, expose ordinary failures, and toast feature-disabled requests', async () => {
  const requests = []
  let resolveGrid
  const gridPending = new Promise((resolve) => { resolveGrid = resolve })
  const redirects = []
  const toasts = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(options)
    if (options.url.endsWith('/member-portfolio-grid/members')) return gridPending
    if (options.url.endsWith('/member-portfolio-list/members')) return [{ memberUserId: 9 }]
    if (options.url.endsWith('/carousel/members')) throw new Error('network')
    if (options.url.endsWith('/member-portfolio-list/members/9/portfolios')) throw new Error('团队作品集功能暂未开放')
    return []
  }, { redirectTo(value) { redirects.push(value) }, showToast(value) { toasts.push(value) } })
  page.setData({ portfolioId: 7, componentSources: {} })
  try {
    const gridEvent = { currentTarget: { dataset: { key: 'grid-1' } } }
    const first = page.handleGridLoadMembers(gridEvent)
    const second = page.handleGridLoadMembers(gridEvent)
    assert.equal(requests.length, 1)
    resolveGrid([{ memberUserId: 8 }])
    await Promise.all([first, second])
    await page.handleListLoadMembers({ currentTarget: { dataset: { key: 'list-1' } } })
    assert.match(requests[1].url, /member-portfolio-list\/members$/)
    await page.handleCarouselLoadMembers({ currentTarget: { dataset: { key: 'carousel-1' } } })
    assert.equal(page.data.componentSources['carousel-1'].loadingFingerprint, '')
    assert.equal(page.data.componentSources['carousel-1'].errorMessage, '来源加载失败，请重试')
    await page.handleListMemberChange({ currentTarget: { dataset: { key: 'list-1' } }, detail: { memberUserId: 9 } })
    assert.deepEqual(toasts, [{ title: '团队作品集功能暂未开放', icon: 'none' }])
    assert.deepEqual(redirects, [])
    assert.equal(page.data.componentSources['list-1'].loadingFingerprint, '')
  } finally { page.cleanup() }
})

function editablePage() {
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}))
  page.setData({ portfolioId: 7, teamId: 3, canMaintain: true, config: { share: { coverUrl: 'https://old.example/cover.png' }, components: [{ componentKey: 'text-1', componentType: 'TEXT_SECTION', sortOrder: 0, config: { content: '旧内容' } }, { componentKey: 'qr-1', componentType: 'QR_CONTACT', sortOrder: 1, config: { qrUrlSource: 'CUSTOM', qrUrl: 'https://old.example/qr.png' } }] }, componentValidation: { 'text-1': true, 'qr-1': true }, hasInvalidComponents: false })
  return page
}

function seedPending(page) { page.setData({ pendingDraftKey: 'draft-key', pendingPublishKey: 'publish-key', pendingPublishRevision: 5 }) }
function assertPendingCleared(page) { assert.equal(page.data.pendingDraftKey, ''); assert.equal(page.data.pendingPublishKey, ''); assert.equal(page.data.pendingPublishRevision, 0) }

test('editor adding a component clears draft and publish retry state', () => {
  const page = editablePage()
  try { seedPending(page); page.addComponent('DIVIDER'); assertPendingCleared(page) } finally { page.cleanup() }
})

test('editor deleting a component clears draft and publish retry state', () => {
  const page = editablePage()
  try { seedPending(page); page.handleComponentDelete({ currentTarget: { dataset: { key: 'text-1' } } }); assertPendingCleared(page) } finally { page.cleanup() }
})

test('editor moving a component clears draft and publish retry state', () => {
  const page = editablePage()
  try { seedPending(page); page.handleMove({ currentTarget: { dataset: { key: 'text-1', direction: 1 } } }); assertPendingCleared(page) } finally { page.cleanup() }
})

test('editor share input clears draft and publish retry state', () => {
  const page = editablePage()
  try { seedPending(page); page.handleShareInput({ currentTarget: { dataset: { field: 'title' } }, detail: { value: '新标题' } }); assertPendingCleared(page) } finally { page.cleanup() }
})

test('editor component save clears draft and publish retry state', () => {
  const page = editablePage()
  try { seedPending(page); page.handleComponentSave({ currentTarget: { dataset: { key: 'text-1' } }, detail: { config: { content: '新内容' } } }); assertPendingCleared(page) } finally { page.cleanup() }
})

test('editor cover selection updates the local preview and clears draft and publish retry state', async () => {
  const page = editablePage()
  page.cleanup()
  const uploadPage = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}), { chooseMedia({ success }) { success({ tempFiles: [{ tempFilePath: 'https://cdn.example/new-cover.png' }] }) } })
  uploadPage.setData(page.data)
  try { seedPending(uploadPage); await uploadPage.handleCoverChoose(); assert.equal(uploadPage.data.config.share.coverUrl, 'https://cdn.example/new-cover.png'); assertPendingCleared(uploadPage) } finally { uploadPage.cleanup() }
})

test('editor QR selection opens a square crop without uploading or changing the child draft', async () => {
  const requests = []
  const applied = []
  const page = editablePage()
  page.cleanup()
  const uploadPage = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(options)
    return { uploadUrl: 'https://cos.example/upload', publicUrl: 'https://cdn.example/new-qr.png', formData: {} }
  }, {
    chooseMedia({ success }) { success({ tempFiles: [{ tempFilePath: 'wxfile://tmp/new-qr.png', width: 1200, height: 800 }] }) },
    getSystemInfoSync() { return { windowWidth: 375 } },
    getFileSystemManager() { return { statSync() { return { size: 128 } } } },
    uploadFile({ success }) { success({ statusCode: 204 }) }
  })
  uploadPage.setData(page.data)
  uploadPage.selectComponent = () => ({ applySelectedImage(value) { applied.push(value) } })
  try {
    seedPending(uploadPage)
    await uploadPage.handleQrChoose({ currentTarget: { dataset: { key: 'qr-1' } } })
    const config = uploadPage.data.config.components.find((item) => item.componentKey === 'qr-1').config
    assert.deepEqual(config, { qrUrlSource: 'CUSTOM', qrUrl: 'https://old.example/qr.png' })
    assert.equal(uploadPage.data.qrContactCropVisible, true)
    assert.equal(uploadPage.data.qrContactCropState.imagePath, 'wxfile://tmp/new-qr.png')
    assert.equal(uploadPage.data.qrContactCropState.cropBoxWidth, 280)
    assert.deepEqual(applied, [])
    assert.deepEqual(requests, [])
    assert.equal(uploadPage.data.pendingDraftKey, 'draft-key')
    assert.equal(uploadPage.data.pendingPublishKey, 'publish-key')
    assert.equal(uploadPage.data.pendingPublishRevision, 5)
    uploadPage.handleCloseQrContactCrop()
    assert.equal(uploadPage.data.qrContactCropVisible, false)
    assert.equal(uploadPage.data.qrContactCropState, null)
    assert.deepEqual(applied, [])
  } finally { uploadPage.cleanup() }
})

test('editor QR crop drags the image and exports a 720px JPG before updating the child draft', async () => {
  const applied = []
  let canvasOptions
  const canvas = {
    createImage() {
      return {
        set src(value) {
          this.path = value
          this.onload()
        }
      }
    },
    getContext() {
      return { clearRect() {}, drawImage() {} }
    }
  }
  let page
  page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}), {
    chooseMedia({ success }) { success({ tempFiles: [{ tempFilePath: 'wxfile://tmp/new-qr.png', width: 1200, height: 800 }] }) },
    getSystemInfoSync() { return { windowWidth: 375 } },
    createSelectorQuery() {
      return {
        in(target) { assert.equal(target, page); return this },
        select(selector) { assert.equal(selector, '#teamQrContactCropCanvas'); return this },
        fields(options) { assert.deepEqual(options, { node: true, size: true }); return this },
        exec(callback) { callback([{ node: canvas }]) }
      }
    },
    canvasToTempFilePath(options) {
      canvasOptions = options
      options.success({ tempFilePath: 'wxfile://tmp/team-qr-cropped.jpg' })
    }
  })
  page.setData({ portfolioId: 7, canMaintain: true })
  page.selectComponent = () => ({ applySelectedImage(value) { applied.push(value) } })
  try {
    await page.handleQrChoose()
    const initialOffsetX = page.data.qrContactCropState.offsetX
    page.handleQrContactCropTouchStart({ touches: [{ clientX: 100, clientY: 100 }] })
    page.handleQrContactCropTouchMove({ touches: [{ clientX: 50, clientY: 100 }] })
    assert.ok(page.data.qrContactCropState.offsetX < initialOffsetX)
    page.handleQrContactCropTouchEnd()
    assert.equal(page.data.qrContactCropTouchStart, null)

    await page.handleConfirmQrContactCrop()

    assert.equal(canvasOptions.destWidth, 720)
    assert.equal(canvasOptions.destHeight, 720)
    assert.equal(canvasOptions.fileType, 'jpg')
    assert.equal(canvasOptions.quality, 0.92)
    assert.deepEqual(applied, [{ detail: { qrUrl: 'wxfile://tmp/team-qr-cropped.jpg' } }])
    assert.equal(page.data.qrContactCropVisible, false)
    assert.equal(page.data.qrContactCropState, null)
  } finally { page.cleanup() }
})

test('editor QR crop export failure keeps the crop state and original child draft', async () => {
  const applied = []
  const toasts = []
  const canvas = {
    createImage() {
      return {
        set src(value) {
          this.path = value
          this.onload()
        }
      }
    },
    getContext() {
      return { clearRect() {}, drawImage() {} }
    }
  }
  let page
  page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}), {
    chooseMedia({ success }) { success({ tempFiles: [{ tempFilePath: 'wxfile://tmp/new-qr.png', width: 1200, height: 800 }] }) },
    getSystemInfoSync() { return { windowWidth: 375 } },
    createSelectorQuery() {
      return {
        in(target) { assert.equal(target, page); return this },
        select() { return this },
        fields() { return this },
        exec(callback) { callback([{ node: canvas }]) }
      }
    },
    canvasToTempFilePath({ fail }) { fail({ errMsg: 'canvasToTempFilePath:fail export' }) },
    showToast(value) { toasts.push(value) }
  })
  page.setData({ portfolioId: 7, canMaintain: true })
  page.selectComponent = () => ({ applySelectedImage(value) { applied.push(value) } })
  try {
    await page.handleQrChoose()
    const cropState = clone(page.data.qrContactCropState)

    await page.handleConfirmQrContactCrop()

    assert.equal(page.data.qrContactCropVisible, true)
    assert.deepEqual(page.data.qrContactCropState, cropState)
    assert.equal(page.data.qrContactCropSaving, false)
    assert.equal(page.data.qrContactCropErrorText, '二维码裁剪失败，请重试')
    assert.deepEqual(applied, [])
    assert.deepEqual(toasts, [{ title: '二维码裁剪失败，请重试', icon: 'none' }])
  } finally { page.cleanup() }
})

test('editor cover selection keeps the prior cover on a non-cancel failure and handles empty media safely', async () => {
  const toasts = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}), { chooseMedia({ fail }) { fail({ errMsg: 'chooseMedia:fail network' }) }, showToast(value) { toasts.push(value) } })
  page.setData({ portfolioId: 7, canMaintain: true, config: { share: { coverUrl: 'https://old.example/cover.png' }, components: [] } })
  try { await page.handleCoverChoose(); assert.equal(page.data.config.share.coverUrl, 'https://old.example/cover.png'); assert.match(toasts[0].title, /图片选择失败/); global.wx.chooseMedia = ({ success }) => success({ tempFiles: [] }); await page.handleCoverChoose(); assert.equal(page.data.config.share.coverUrl, 'https://old.example/cover.png') } finally { page.cleanup() }
})

test('editor cover picker cancellation is silent', async () => {
  const toasts = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}), { chooseMedia({ fail }) { fail({ errMsg: 'chooseMedia:fail cancel' }) }, showToast(value) { toasts.push(value) } })
  page.setData({ portfolioId: 7, canMaintain: true, config: { share: { coverUrl: 'https://old.example/cover.png' }, components: [] } })
  try { await page.handleCoverChoose(); assert.equal(toasts.length, 0); assert.equal(page.data.config.share.coverUrl, 'https://old.example/cover.png') } finally { page.cleanup() }
})

test('editor publish retries only publish with one draft revision and clears rejected pending state', async () => {
  const requests = []
  let publishAttempt = 0
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(options)
    if (options.url.endsWith('/draft')) return { draftRevision: 8 }
    if (options.url.endsWith('/publish')) {
      publishAttempt += 1
      if (publishAttempt === 1) throw new Error('network')
      return { publicationStatus: 'PUBLISHED', publishedRevision: 8 }
    }
    return {}
  })
  page.setData({ portfolioId: 7, teamId: 3, canMaintain: true, draftRevision: 7, config: { share: { title: '测试团队作品集' }, components: [] }, componentValidation: {}, hasInvalidComponents: false })
  try {
    await page.handlePublishTap(); await page.handlePublishTap()
    const drafts = requests.filter((item) => item.url.endsWith('/draft'))
    const publishes = requests.filter((item) => item.url.endsWith('/publish'))
    assert.equal(drafts.length, 1)
    assert.equal(publishes.length, 2)
    assert.equal(publishes[0].data.idempotencyKey, publishes[1].data.idempotencyKey)
    assert.equal(publishes[0].data.draftRevision, publishes[1].data.draftRevision)
  } finally { page.cleanup() }

  const rejected = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => { const error = new Error('invalid'); error.statusCode = 400; throw error })
  rejected.setData({ portfolioId: 7, canMaintain: true, pendingPublishKey: 'publish-key', pendingPublishRevision: 8 })
  try { await rejected.handlePublishTap(); assert.equal(rejected.data.pendingPublishKey, ''); assert.equal(rejected.data.pendingPublishRevision, 0) } finally { rejected.cleanup() }
})

test('contact leads ignore a forged route role, accept only trusted canMaintain, and tolerate malformed names', async () => {
  const page = loadPage('contact-leads/team-contact-leads.js', async (options) => {
    if (options.url === '/api/mine/team-portfolios') return [{ portfolioId: 1, teamId: 7, canMaintain: false, currentRole: 'MEMBER' }]
    return { page: 1, hasMore: false, items: [] }
  })
  try {
    page.onLoad({ teamId: '7', currentRole: 'OWNER', teamName: '%' })
    assert.equal(page.data.canUpdateFollow, false)
    await flush()
    assert.equal(page.data.canUpdateFollow, false)
    assert.equal(page.data.teamName, '%')
  } finally { page.cleanup() }

  const trusted = loadPage('contact-leads/team-contact-leads.js', async (options) => options.url === '/api/mine/team-portfolios' ? [{ portfolioId: 1, teamId: 7, canMaintain: true, currentRole: 'MANAGER' }] : { page: 1, hasMore: false, items: [] })
  try { trusted.onLoad({ teamId: '7', currentRole: 'MEMBER' }); await flush(); assert.equal(trusted.data.canUpdateFollow, true) } finally { trusted.cleanup() }
})

test('contact leads shows a toast for feature-disabled permission failures', async () => {
  const redirects = []
  const toasts = []
  const page = loadPage('contact-leads/team-contact-leads.js', async () => { throw new Error('团队作品集功能暂未开放') }, { redirectTo(value) { redirects.push(value) }, showToast(value) { toasts.push(value) } })
  try { page.setData({ teamId: 7 }); await page.loadPermissions(); assert.deepEqual(toasts, [{ title: '团队作品集功能暂未开放', icon: 'none' }]); assert.deepEqual(redirects, []) } finally { page.cleanup() }
})

test('secondary team pages toast recognized unavailable failures', async () => {
  const cases = [
    ['team-select/team-select.js', (page) => page.bootstrap()],
    ['component-library/team-portfolio-component-library.js', (page) => page.bootstrap()],
    ['standard-preview/team-portfolio-standard-preview.js', (page) => { page.setData({ portfolioId: 7, scope: 'draft' }); return page.bootstrap() }]
  ]
  for (const [relativePath, run] of cases) {
    const redirects = []
    const toasts = []
    const page = loadPage(relativePath, async () => { throw new Error('团队作品集功能暂未开放') }, { redirectTo(value) { redirects.push(value) }, showToast(value) { toasts.push(value) } })
    try { await run(page); assert.deepEqual(toasts, [{ title: '团队作品集功能暂未开放', icon: 'none' }]); assert.deepEqual(redirects, []); assert.equal(page.data.loading, false) } finally { page.cleanup() }
  }
})

test('event-channel success callbacks accept direct opens that omit eventChannel, emit, or on', async () => {
  const navigations = []
  const editor = editablePage()
  editor.cleanup()
  const safeEditor = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}), { navigateTo(value) { navigations.push(value); value.success({}) } })
  safeEditor.setData({ portfolioId: 7, canMaintain: true, config: { share: {}, components: [] } })
  try { safeEditor.handleOpenLibrary(); assert.equal(safeEditor.data.openingLibrary, false) } finally { safeEditor.cleanup() }

  const library = loadPage('component-library/team-portfolio-component-library.js', async () => ({}), { navigateBack() { navigations.push('back') } })
  try { library.handleSelect({ currentTarget: { dataset: { type: 'TEXT_SECTION', disabled: false } } }); assert.equal(navigations.at(-1), 'back') } finally { library.cleanup() }
})

test('team WXML handlers exist and templates do not call methods', () => {
  const pageFiles = ['portfolios', 'team-select/team-select', 'standard-edit/team-portfolio-standard-edit', 'component-library/team-portfolio-component-library', 'standard-preview/team-portfolio-standard-preview', 'contact-leads/team-contact-leads', 'visitor-portfolio/team-visitor-portfolio']
  for (const base of pageFiles) {
    const wxml = fs.readFileSync(path.join(ROOT, `${base}.wxml`), 'utf8')
    const source = fs.readFileSync(path.join(ROOT, `${base}.js`), 'utf8')
    assert.doesNotMatch(wxml, /\{\{[^}]*[A-Za-z_$][\w$]*\s*\(/)
    for (const match of wxml.matchAll(/\b(?:bind|catch)[a-z]+="([A-Za-z][A-Za-z0-9_]*)"/g)) assert.match(source, new RegExp(`\\b${match[1]}\\s*\\(`))
  }
})

function authRequiredError() { const error = new Error('登录已过期'); error.authRequired = true; return error }

test('maintainer main loads and representative save operations redirect to login on authRequired', async () => {
  const cases = [
    ['team-select/team-select.js', (page) => page.bootstrap()],
    ['component-library/team-portfolio-component-library.js', (page) => page.bootstrap()],
    ['standard-edit/team-portfolio-standard-edit.js', (page) => { page.setData({ portfolioId: 7 }); return page.bootstrap() }],
    ['standard-preview/team-portfolio-standard-preview.js', (page) => { page.setData({ portfolioId: 7 }); return page.bootstrap() }],
    ['contact-leads/team-contact-leads.js', (page) => { page.setData({ teamId: 7 }); return page.loadPage(1) }]
  ]
  for (const [relativePath, run] of cases) {
    const removed = []
    const redirects = []
    const page = loadPage(relativePath, async () => { throw authRequiredError() }, { removeStorageSync(value) { removed.push(value) }, redirectTo(value) { redirects.push(value) } })
    try { await run(page); assert.equal(removed.length, 1, relativePath); assert.equal(redirects[0].url, '/pages/login/login', relativePath) } finally { page.cleanup() }
  }

  const saveRemoved = []
  const saveRedirects = []
  const editor = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => { throw authRequiredError() }, { removeStorageSync(value) { saveRemoved.push(value) }, redirectTo(value) { saveRedirects.push(value) } })
  editor.setData({ portfolioId: 7, canMaintain: true, draftRevision: 1, config: { share: { title: '测试团队作品集' }, components: [] }, componentValidation: {} })
  try { await editor.saveDraft(); assert.equal(saveRemoved.length, 1); assert.equal(saveRedirects[0].url, '/pages/login/login') } finally { editor.cleanup() }

  const followRemoved = []
  const followRedirects = []
  const leads = loadPage('contact-leads/team-contact-leads.js', async () => { throw authRequiredError() }, { removeStorageSync(value) { followRemoved.push(value) }, redirectTo(value) { followRedirects.push(value) } })
  leads.setData({ teamId: 7, canUpdateFollow: true, followEditor: { leadId: 1, followStatus: 'CONTACTED', followNote: '已联系' }, items: [{ leadId: 1 }] })
  try { await leads.handleFollowSave(); assert.equal(followRemoved.length, 1); assert.equal(followRedirects[0].url, '/pages/login/login') } finally { leads.cleanup() }
})

test('contact leads WXML renders team and portfolio sources without masking', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'contact-leads/team-contact-leads.wxml'), 'utf8')
  assert.match(wxml, /团队来源：\{\{item\.teamName \|\| teamName\}\}/)
  assert.match(wxml, /作品集来源：\{\{item\.portfolioTitle\}\} \{\{item\.sourceText\}\}/)
  assert.doesNotMatch(wxml, /\*{3}|脱敏|mask/i)
})

test('operation-level unavailable errors toast without redirecting for save, publish, preview query, follow update, visitor query, and visitor contact', async () => {
  const unavailable = async () => { throw new Error('团队作品集功能暂未开放') }
  const redirects = []
  const toasts = []
  const wxOverrides = { redirectTo(value) { redirects.push(value) }, showToast(value) { toasts.push(value) } }

  const editor = loadPage('standard-edit/team-portfolio-standard-edit.js', unavailable, wxOverrides)
  editor.setData({ portfolioId: 8, canMaintain: true, draftRevision: 1, config: { share: { title: '测试团队作品集' }, components: [] }, componentValidation: {} })
  try {
    await editor.saveDraft()
    assert.deepEqual(toasts.shift(), { title: '团队作品集功能暂未开放', icon: 'none' })
    editor.setData({ pendingPublishKey: 'publish-key', pendingPublishRevision: 1 })
    await editor.handlePublishTap()
    assert.deepEqual(toasts.shift(), { title: '团队作品集功能暂未开放', icon: 'none' })
  } finally { editor.cleanup() }

  const preview = loadPage('standard-preview/team-portfolio-standard-preview.js', unavailable, wxOverrides)
  preview.setData({ portfolioId: 8, scope: 'draft' })
  const previewRejections = []
  preview.selectComponent = () => ({ rejectQuery(value) { previewRejections.push(value) } })
  try { await preview.handleScheduleQuery({ detail: { componentKey: 'schedule-1', queriedDate: '2026-08-01', idempotencyKey: 'query-1' } }); assert.deepEqual(toasts.shift(), { title: '团队作品集功能暂未开放', icon: 'none' }); assert.equal(previewRejections.length, 1) } finally { preview.cleanup() }

  const leads = loadPage('contact-leads/team-contact-leads.js', unavailable, wxOverrides)
  leads.setData({ teamId: 7, canUpdateFollow: true, followEditor: { leadId: 1, followStatus: 'CONTACTED', followNote: '' }, items: [{ leadId: 1 }] })
  try { await leads.handleFollowSave(); assert.deepEqual(toasts.shift(), { title: '团队作品集功能暂未开放', icon: 'none' }) } finally { leads.cleanup() }

  const visitor = loadPage('visitor-portfolio/team-visitor-portfolio.js', unavailable, wxOverrides)
  visitor.setData({ shareCode: 'share-8', sourceType: 'WECHAT_SHARE_CARD', visitRecordId: 2, contactSubmitting: {}, pendingContactKeys: {}, contactForms: {} })
  const visitorRejections = []
  visitor.selectComponent = () => ({ rejectQuery(value) { visitorRejections.push(value) } })
  try {
    await visitor.handleScheduleQuery({ detail: { componentKey: 'schedule-1', queriedDate: '2026-08-01', idempotencyKey: 'query-1' } })
    assert.deepEqual(toasts.shift(), { title: '团队作品集功能暂未开放', icon: 'none' })
    assert.equal(visitorRejections.length, 1)
    await visitor.handleContactSubmit({ currentTarget: { dataset: { key: 'contact-1' } }, detail: { form: { contactName: '客户', phone: '1' } } })
    assert.deepEqual(toasts.shift(), { title: '团队作品集功能暂未开放', icon: 'none' })
  } finally { visitor.cleanup() }
  assert.deepEqual(redirects, [])
})

test('member-first retries preserve the failed carousel work or grid/list portfolio endpoint', async () => {
  const variants = [
    ['Carousel', 'carousel-1', '/components/carousel/members/9/works'],
    ['Grid', 'grid-1', '/components/member-portfolio-grid/members/9/portfolios'],
    ['List', 'list-1', '/components/member-portfolio-list/members/9/portfolios']
  ]
  for (const [name, key, suffix] of variants) {
    const requests = []
    let attempts = 0
    const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
      requests.push(options)
      attempts += 1
      if (attempts === 1) throw new Error('network')
      return [{ id: 1 }]
    })
    page.setData({ portfolioId: 7, componentSources: {} })
    try {
      await page[`handle${name}MemberChange`]({ currentTarget: { dataset: { key } }, detail: { memberUserId: 9 } })
      assert.equal(page.data.componentSources[key].failedStage, 'member-items')
      assert.equal(page.data.componentSources[key].memberUserId, 9)
      await page[`handle${name}RetrySource`]({ currentTarget: { dataset: { key } } })
      assert.equal(requests.length, 2)
      assert.match(requests[1].url, new RegExp(`${suffix}$`))
    } finally { page.cleanup() }
  }
  const wxml = fs.readFileSync(path.join(ROOT, 'standard-edit/team-portfolio-standard-edit.wxml'), 'utf8')
  for (const handler of ['handleCarouselRetrySource', 'handleGridRetrySource', 'handleListRetrySource']) assert.match(wxml, new RegExp(`bindtap="${handler}"`))
})

test('member switches clear stale second-stage sources and ignore late responses for every member-first component', async () => {
  const variants = [
    ['Carousel', 'carousel-1', 'works'],
    ['Grid', 'grid-1', 'portfolios'],
    ['List', 'list-1', 'portfolios']
  ]
  for (const [name, key, sourceField] of variants) {
    const requests = []
    const pending = []
    const page = loadPage('standard-edit/team-portfolio-standard-edit.js', (options) => {
      requests.push(options)
      if (requests.length < 3) return new Promise((resolve, reject) => pending.push({ resolve, reject }))
      return Promise.resolve([{ id: 10 }])
    })
    page.setData({ portfolioId: 7, componentSources: { [key]: { [sourceField]: [{ id: 8 }] } } })
    try {
      const first = page[`handle${name}MemberChange`]({ currentTarget: { dataset: { key } }, detail: { memberUserId: 9 } })
      assert.deepEqual(page.data.componentSources[key][sourceField], [], `${name} clears the previous member content immediately`)
      const second = page[`handle${name}MemberChange`]({ currentTarget: { dataset: { key } }, detail: { memberUserId: 10 } })
      assert.deepEqual(page.data.componentSources[key][sourceField], [], `${name} keeps the source disabled while the new member loads`)
      pending[0].resolve([{ id: 9 }])
      await first
      assert.deepEqual(page.data.componentSources[key][sourceField], [], `${name} ignores the old member response`)
      pending[1].reject(new Error('network'))
      await second
      assert.deepEqual(page.data.componentSources[key][sourceField], [], `${name} keeps an empty source after the current member fails`)
      assert.equal(page.data.componentSources[key].memberUserId, 10)
      await page[`handle${name}RetrySource`]({ currentTarget: { dataset: { key } } })
      assert.match(requests[2].url, /\/members\/10\//)
      assert.deepEqual(page.data.componentSources[key][sourceField], [{ id: 10 }])
    } finally { page.cleanup() }
  }
})

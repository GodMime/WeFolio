const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MINIAPP_ROOT = path.resolve(__dirname, '..')
const TEAM_ROOT = path.join(MINIAPP_ROOT, 'pages/team-portfolios')
const REQUEST_PATH = path.join(MINIAPP_ROOT, 'utils/request.js')
const TEAM_UTILITY_PATH = path.join(TEAM_ROOT, 'utils/team-portfolios.js')

function read(relativePath) {
  return fs.readFileSync(path.join(TEAM_ROOT, relativePath), 'utf8')
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function loadPreviewPage(toasts) {
  const pagePath = path.join(TEAM_ROOT, 'standard-preview/team-portfolio-standard-preview.js')
  const requestKey = require.resolve(REQUEST_PATH)
  const utilityKey = require.resolve(TEAM_UTILITY_PATH)
  const oldRequest = require.cache[requestKey]
  const oldUtility = require.cache[utilityKey]
  const oldPage = global.Page
  const oldWx = global.wx
  let definition

  require.cache[requestKey] = {
    id: REQUEST_PATH,
    filename: REQUEST_PATH,
    loaded: true,
    exports: { request: async () => { throw new Error('预览联系表单不应请求接口') } }
  }
  delete require.cache[utilityKey]
  global.Page = (value) => { definition = value }
  global.wx = {
    showToast(options) { toasts.push(options) },
    navigateTo() {},
    previewImage() {}
  }
  delete require.cache[require.resolve(pagePath)]
  try {
    require(pagePath)
  } finally {
    global.Page = oldPage
    if (oldRequest) require.cache[requestKey] = oldRequest
    else delete require.cache[requestKey]
    if (oldUtility) require.cache[utilityKey] = oldUtility
    else delete require.cache[utilityKey]
  }

  return Object.assign({}, definition, {
    data: clone(definition.data),
    setData(patch) { Object.assign(this.data, patch) },
    cleanup() { global.wx = oldWx }
  })
}

test('team schedule query uses the personal portfolio entry and bottom sheet interaction', () => {
  const wxml = read('components/schedule-query/schedule-query.wxml')
  const wxss = read('components/schedule-query/schedule-query.wxss')

  assert.match(wxml, /class="schedule-query-modal-mask \{\{modalVisible \? 'visible' : ''\}\}" catchtap="closeModal"/)
  assert.match(wxml, /class="schedule-query-modal-panel" catchtap="noop"/)
  assert.match(wxml, /class="schedule-query-open-button" catchtap="openModal">查询团队档期<\/button>/)
  assert.match(wxss, /\.schedule-query-open-button\s*\{[^}]*width:\s*320rpx;[^}]*height:\s*72rpx;[^}]*background:\s*#17202a;/s)
  assert.match(wxss, /\.schedule-query-modal-panel\s*\{[^}]*max-height:\s*82vh;/s)
})

test('team contact form uses the personal portfolio entry and bottom sheet interaction', () => {
  const wxml = read('components/contact-form/contact-form.wxml')
  const wxss = read('components/contact-form/contact-form.wxss')

  assert.match(wxml, /class="contact-form-mask \{\{modalVisible \? 'visible' : ''\}\}" catchtap="closeModal"/)
  assert.match(wxml, /class="contact-form-panel" catchtap="noop"/)
  assert.match(wxml, /class="primary-button contact-form-entry-button" catchtap="openModal"/)
  assert.match(wxss, /\.contact-form-entry-button\s*\{[^}]*width:\s*320rpx;/s)
  assert.match(wxss, /\.primary-button\s*\{[^}]*height:\s*72rpx;[^}]*background:\s*#17202a;/s)
  assert.match(wxss, /\.contact-form-panel\s*\{[^}]*max-height:\s*82vh;/s)
})

test('team preview controls each contact form modal and only warns when submitting', () => {
  const wxml = read('standard-preview/team-portfolio-standard-preview.wxml')
  assert.match(wxml, /form="\{\{contactForms\[item\.componentKey\]\}\}"/)
  assert.match(wxml, /modal-visible="\{\{contactModalVisible\[item\.componentKey\]\}\}"/)
  assert.match(wxml, /bindcontactinput="handleContactInput"/)
  assert.match(wxml, /bindopenmodal="handleContactOpen"/)
  assert.match(wxml, /bindclosemodal="handleContactClose"/)
  assert.match(wxml, /bindsubmit="handleContactSubmit"/)

  const toasts = []
  const page = loadPreviewPage(toasts)
  const event = { currentTarget: { dataset: { key: 'contact-a' } }, detail: {} }
  try {
    page.handleContactOpen(event)
    assert.deepEqual(page.data.contactModalVisible, { 'contact-a': true })

    page.handleContactInput(Object.assign({}, event, { detail: { form: { contactName: '小映', phone: '13800000000' } } }))
    assert.deepEqual(page.data.contactForms, { 'contact-a': { contactName: '小映', phone: '13800000000' } })

    page.handleContactClose(event)
    assert.deepEqual(page.data.contactModalVisible, { 'contact-a': false })

    page.handleContactSubmit(event)
    assert.deepEqual(toasts, [{ title: '预览模式不提交', icon: 'none' }])
  } finally {
    page.cleanup()
  }
})

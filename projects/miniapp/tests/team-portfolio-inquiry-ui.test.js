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

  assert.match(wxml, /class="schedule-query-modal-mask theme-\{\{themeMode\}\} \{\{modalVisible \? 'visible' : ''\}\}" catchtap="closeModal"/)
  assert.match(wxml, /class="schedule-query-modal-panel" catchtap="noop"/)
  assert.match(wxml, /class="schedule-query-open-button" catchtap="openModal">查询团队档期<\/button>/)
  assert.match(wxss, /\.schedule-query-open-button\s*\{[^}]*width:\s*320rpx;[^}]*height:\s*72rpx;[^}]*background:\s*#212529;/s)
  assert.match(wxss, /\.schedule-query-modal-panel\s*\{[^}]*max-height:\s*82vh;[^}]*border-radius:\s*56rpx 56rpx 0 0;/s)
})

test('team schedule query renders the personal-style month calendar grid', () => {
  const wxml = read('components/schedule-query/schedule-query.wxml')
  const wxss = read('components/schedule-query/schedule-query.wxss')

  assert.match(wxml, /class="schedule-query-month-bar"/)
  assert.match(wxml, /catchtap="handlePrevMonth"/)
  assert.match(wxml, /catchtap="handleNextMonth"/)
  assert.match(wxml, /fields="month"[^>]*bindchange="handleMonthPickerChange"/)
  assert.match(wxml, /class="schedule-query-weekdays"/)
  assert.match(wxml, /wx:for="\{\{calendarDays\}\}"/)
  assert.match(wxml, /wx:key="key"/)
  assert.match(wxml, /catchtap="handleDayTap"/)
  assert.match(wxml, /class="\{\{item\.dayClass\}\} \{\{selectedDate === item\.date \? 'selected' : ''\}\}"/)
  assert.match(wxml, /class="schedule-query-day-stack"[\s\S]*class="schedule-query-day-number"/)
  assert.match(wxml, /disabled="\{\{!selectedDate \|\| loading\}\}"/)
  assert.doesNotMatch(wxml, /<picker mode="date" value="\{\{selectedDate\}\}"/)

  assert.match(wxss, /\.schedule-query-weekdays\s*\{/)
  assert.match(wxss, /\.schedule-query-days\s*\{/)
  assert.match(wxss, /\.schedule-calendar-day\s*\{[^}]*width:\s*calc\(14\.285714% - 8rpx\);[^}]*min-height:\s*88rpx;[^}]*padding:\s*8rpx 4rpx;[^}]*border:\s*0;[^}]*background:\s*transparent;/s)
  assert.match(wxss, /\.schedule-calendar-day\.muted\s*\{/)
  assert.match(wxss, /\.schedule-calendar-day\.disabled\s*\{/)
  assert.match(wxss, /\.schedule-calendar-day\.selected\s*\{[^}]*background:\s*transparent;/s)
  assert.match(wxss, /\.schedule-calendar-day\.selected \.schedule-query-day-stack\s*\{[^}]*width:\s*72rpx;[^}]*height:\s*72rpx;[^}]*border-radius:\s*50%;[^}]*background:\s*#212529;/s)
  assert.match(wxss, /\.schedule-query-day-number\s*\{[^}]*font-size:\s*30rpx;[^}]*font-weight:\s*600;/s)
})

test('team contact form uses the personal portfolio entry and bottom sheet interaction', () => {
  const wxml = read('components/contact-form/contact-form.wxml')
  const wxss = read('components/contact-form/contact-form.wxss')

  assert.match(wxml, /class="contact-form-mask theme-\{\{themeMode\}\} \{\{modalVisible \? 'visible' : ''\}\}" catchtap="closeModal"/)
  assert.match(wxml, /class="contact-form-panel" catchtap="noop"/)
  assert.match(wxml, /class="primary-button contact-form-entry-button" catchtap="openModal"/)
  assert.match(wxss, /\.contact-form-entry-button\s*\{[^}]*width:\s*320rpx;/s)
  assert.match(wxss, /\.primary-button\s*\{[^}]*height:\s*72rpx;[^}]*background:\s*#212529;/s)
  assert.match(wxss, /\.contact-form-panel\s*\{[^}]*max-height:\s*82vh;[^}]*border-radius:\s*56rpx 56rpx 0 0;/s)
  assert.doesNotMatch(wxml, /maxlength=/)
  assert.doesNotMatch(wxml, /field-count|\/20|\/11|\/200/)
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

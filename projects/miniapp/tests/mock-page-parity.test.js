const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.join(__dirname, '..')

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matches = Array.from(content.matchAll(
    new RegExp(`(?:^|\\n)\\s*${escapedSelector}\\s*\\{([^}]*)\\}`, 'g')
  ))
  const match = matches[matches.length - 1]
  return match ? match[1] : ''
}

function normalizeRule(rule) {
  return String(rule || '').replace(/\s+/g, ' ').trim()
}

function loadMockExperience() {
  const modulePath = path.join(ROOT, 'pages/mock/utils/mock-experience.js')
  delete require.cache[require.resolve(modulePath)]
  return require(modulePath)
}

test('mock works keeps production media preview geometry and local filtering', () => {
  const wxml = read('pages/mock/works/works.wxml')
  const wxss = read('pages/mock/works/works.wxss')
  const pageJs = read('pages/mock/works/works.js')
  const mockSource = read('pages/mock/utils/mock-experience.js')
  const imageMaskRule = readRule(wxss, '.image-preview-mask')
  const imagePanelRule = readRule(wxss, '.image-preview-panel')
  const imageMediaRule = readRule(wxss, '.image-preview-media')
  const mock = loadMockExperience()

  assert.match(wxml, /class="search-input"[\s\S]*bindinput="handleSearchInput"/)
  assert.doesNotMatch(wxml, /class="search-input"[^>]*disabled/)
  assert.doesNotMatch(wxml, /media-filter|handleMediaTypeTap/)
  assert.doesNotMatch(pageJs, /selectedMediaType|handleMediaTypeTap/)
  assert.doesNotMatch(mockSource, /MOCK_WORK_MEDIA_FILTERS|normalizedMediaType|matchesMediaType/)
  assert.match(wxml, /class="image-preview-heading"/)
  assert.match(wxml, /class="image-preview-title"/)
  assert.match(wxml, /class="image-preview-close"/)
  assert.match(wxml, /class="image-preview-media"/)
  assert.match(imageMaskRule, /align-items:\s*center/)
  assert.match(imageMaskRule, /justify-content:\s*center/)
  assert.match(imageMaskRule, /background:\s*rgba\(10,\s*14,\s*20,\s*0\.88\)/)
  assert.doesNotMatch(imagePanelRule, /border-radius:\s*56rpx 56rpx 0 0/)
  assert.match(imageMediaRule, /height:\s*72vh/)
  assert.equal(Object.prototype.hasOwnProperty.call(mock.MOCK_WORK_LIBRARY, 'mediaFilters'), false)
  assert.equal(Object.prototype.hasOwnProperty.call(mock.MOCK_WORK_LIBRARY, 'summary'), false)
  assert.equal(typeof mock.filterMockWorks, 'function')
  assert.equal(mock.filterMockWorks(mock.MOCK_WORK_LIBRARY.works, '视频', 0).length, 1)
  assert.equal(mock.filterMockWorks(mock.MOCK_WORK_LIBRARY.works, '', 201).length, 7)
  assert.equal(mock.filterMockWorks(mock.MOCK_WORK_LIBRARY.works, '', 999).length, 0)
})

test('mock works tag row and plus buttons mirror the current formal page', () => {
  const formalWxml = read('pages/works/works.wxml')
  const mockWxml = read('pages/mock/works/works.wxml')
  const formalWxss = read('pages/works/works.wxss')
  const mockWxss = read('pages/mock/styles/works.wxss')

  assert.match(formalWxml, /wx:for="\{\{list\.filterTags\}\}"/)
  assert.match(mockWxml, /wx:for="\{\{list\.filterTags\}\}"/)
  assert.match(mockWxml, /\{\{item\.labelText\}\}/)
  assert.match(mockWxml, /class="tag-color-dot"/)
  assert.match(mockWxml, /style="\{\{\(\(!selectedTagId && !item\.id\) \|\| selectedTagId === item\.id\) \? item\.activeStyle : item\.filterStyle\}\}"/)

  for (const selector of ['.tag-pill.active', '.tag-color-dot', '.tag-add-button']) {
    assert.equal(
      normalizeRule(readRule(mockWxss, selector)),
      normalizeRule(readRule(formalWxss, selector)),
      `${selector} should stay visually identical to the formal works page`
    )
  }
})

test('mock schedule renders local dated schedules and calendar color marks', () => {
  const wxml = read('pages/mock/schedule/schedule.wxml')
  const mock = loadMockExperience()
  assert.ok(Array.isArray(mock.MOCK_SCHEDULE_DATA.schedules))
  assert.ok(mock.MOCK_SCHEDULE_DATA.schedules.length > 0)
  const example = mock.MOCK_SCHEDULE_DATA.schedules[0]
  const month = mock.buildMockCalendarMonth(example.date.slice(0, 7), example.date)
  const markedDay = month.days.find((day) => day.date === example.date)

  assert.ok(example)
  assert.ok(markedDay)
  assert.ok(markedDay.statusColors.length > 0)
  assert.match(wxml, /wx:for="\{\{selectedSchedules\}\}"/)
  assert.match(wxml, /class="schedule-line"/)
  assert.match(wxml, /class="day-colors"/)
})

test('mock schedule keeps the formal borderless calendar cell geometry', () => {
  const formalWxss = read('pages/schedule/schedule.wxss')
  const mockWxss = read('pages/mock/styles/schedule.wxss')
  const selectors = [
    '.calendar',
    '.weekday',
    '.calendar-day',
    '.calendar-day.muted',
    '.calendar-day.selected',
    '.date-stack',
    '.calendar-day.selected .date-stack',
    '.day-number',
    '.day-meta',
    '.calendar-day.muted .day-meta',
    '.calendar-day.selected .day-meta',
    '.day-colors',
    '.day-color'
  ]

  for (const selector of selectors) {
    assert.equal(
      normalizeRule(readRule(mockWxss, selector)),
      normalizeRule(readRule(formalWxss, selector)),
      `${selector} should stay visually identical to the formal schedule page`
    )
  }

  const calendarDayRule = readRule(mockWxss, '.calendar-day')
  assert.match(calendarDayRule, /min-height:\s*88rpx/)
  assert.match(calendarDayRule, /border:\s*0/)
})

test('mock slot definitions use the same single toggle action as the formal page', () => {
  const wxml = read('pages/mock/schedule/schedule.wxml')

  assert.doesNotMatch(wxml, /class="mini-action"[^>]*>编辑<\/button>/)
  assert.match(wxml, /class="mini-action warn slot-toggle-action"[^>]*>停用<\/button>/)
})

test('mock portfolio list keeps the production personal and team switch track', () => {
  const wxml = read('pages/mock/portfolios/portfolios.wxml')
  const js = read('pages/mock/portfolios/portfolios.js')
  const mock = loadMockExperience()

  assert.match(wxml, /class="segment-indicator \{\{ownerType === 'TEAM' \? 'team-active' : ''\}\}"/)
  assert.match(wxml, /class="portfolio-switch-track \{\{ownerType === 'TEAM' \? 'team-active' : ''\}\}"/)
  assert.match(wxml, /class="portfolio-content personal-portfolio-content"/)
  assert.match(wxml, /class="portfolio-content team-portfolio-content"/)
  assert.match(wxml, /class="team-portfolio-affiliation"/)
  assert.match(js, /handleOwnerTypeTap/)
  assert.ok(mock.MOCK_TEAM_PORTFOLIO_LIST)
  assert.ok(Array.isArray(mock.MOCK_TEAM_PORTFOLIO_LIST.portfolios))
  assert.ok(mock.MOCK_TEAM_PORTFOLIO_LIST.portfolios.length > 0)
})

test('mock portfolio editor filters local works and exposes typography controls', () => {
  const wxml = read('pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml')
  const js = read('pages/mock/portfolio-standard-edit/portfolio-standard-edit.js')

  assert.match(wxml, /class="work-filter-input"/)
  assert.match(wxml, /bindinput="handleWorkFilterInput"/)
  assert.match(wxml, /class="work-filter-tag/)
  assert.match(js, /workFilterKeyword:/)
  assert.match(js, /selectedWorkFilterTagId:/)
  assert.match(js, /handleWorkFilterInput/)
  assert.match(wxml, /fontFamily/)
  assert.match(wxml, /fontSize/)
  assert.match(wxml, /textAlign/)
})

test('mock portfolio preview keeps production stable states and local modal entry points', () => {
  const wxml = read('pages/mock/portfolio-standard-preview/portfolio-standard-preview.wxml')
  const js = read('pages/mock/portfolio-standard-preview/portfolio-standard-preview.js')

  assert.match(wxml, /wx:if="\{\{loading\}\}"/)
  assert.match(wxml, /class="preview-skeleton"/)
  assert.match(wxml, /wx:elif="\{\{errorMessage\}\}"/)
  assert.match(wxml, /class="preview-error"/)
  assert.match(wxml, /class="portfolio-menu-transition-content \{\{portfolioMenuTransitionClass\}\}"/)
  assert.match(wxml, /class="mock-schedule-query-modal/)
  assert.match(wxml, /class="mock-contact-form-modal/)
  assert.match(js, /scheduleQueryModalVisible:/)
  assert.match(js, /contactFormModalVisible:/)
})

test('mock mine entries expose the same visible action semantics as formal entries', () => {
  const wxml = read('pages/mock/index/index.wxml')
  const js = read('pages/mock/index/index.js')

  assert.match(wxml, /class="entry-row"[\s\S]*data-type="\{\{item\.type\}\}"[\s\S]*bindtap="handleEntryTap"/)
  assert.match(wxml, /aria-role="button"/)
  assert.match(wxml, /aria-label="\{\{item\.title\}\}"/)
  assert.match(wxml, /class="light-button"[^>]*bindtap="handleLockedAction"/)
  assert.match(js, /handleEntryTap/)
  assert.match(js, /showMockLoginRequiredToast/)
})

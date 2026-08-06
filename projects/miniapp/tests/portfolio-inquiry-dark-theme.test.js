const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MINIAPP_ROOT = path.resolve(__dirname, '..')

function read(relativePath) {
  return fs.readFileSync(path.join(MINIAPP_ROOT, relativePath), 'utf8')
}

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = content.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
}

test('personal inline schedule query replaces light calendar surfaces in dark theme', () => {
  const wxss = read('components/portfolio-schedule-query/portfolio-schedule-query.wxss')
  const calendarRule = readRule(
    wxss,
    '.schedule-query.portfolio-theme-dark .schedule-query-inline-shell .schedule-query-calendar'
  )
  const dayRule = readRule(
    wxss,
    '.schedule-query.portfolio-theme-dark .schedule-query-inline-shell .schedule-calendar-day'
  )
  const selectedDayRule = readRule(
    wxss,
    '.schedule-query.portfolio-theme-dark .schedule-query-inline-shell .schedule-calendar-day.selected'
  )
  const selectedDayStackRule = readRule(
    wxss,
    '.schedule-query.portfolio-theme-dark .schedule-query-inline-shell .schedule-calendar-day.selected .schedule-query-day-stack'
  )
  const submitRule = readRule(
    wxss,
    '.schedule-query.portfolio-theme-dark .schedule-query-inline-shell .schedule-query-submit'
  )
  const disabledSubmitRule = readRule(
    wxss,
    '.schedule-query.portfolio-theme-dark .schedule-query-inline-shell .schedule-query-submit[disabled]'
  )
  const errorStateRule = readRule(
    wxss,
    '.schedule-query.portfolio-theme-dark .schedule-query-inline-shell .schedule-query-state.error'
  )

  assert.match(calendarRule, /color:\s*var\(--portfolio-text-primary\);/)
  assert.match(calendarRule, /border-color:\s*var\(--portfolio-border\);/)
  assert.match(calendarRule, /background:\s*var\(--portfolio-surface\);/)
  assert.match(dayRule, /color:\s*var\(--portfolio-text-primary\);/)
  assert.match(dayRule, /border-color:\s*transparent;/)
  assert.match(dayRule, /background:\s*transparent;/)
  assert.match(selectedDayRule, /background:\s*transparent;/)
  assert.match(selectedDayStackRule, /color:\s*#212529;/)
  assert.match(selectedDayStackRule, /background:\s*var\(--portfolio-text-primary\);/)
  assert.match(selectedDayStackRule, /box-shadow:\s*0\s+0\s+0\s+9rpx\s+var\(--portfolio-text-primary\);/)
  assert.match(submitRule, /color:\s*#212529;/)
  assert.match(submitRule, /background:\s*var\(--portfolio-text-primary\);/)
  assert.match(disabledSubmitRule, /color:\s*var\(--portfolio-text-muted\);/)
  assert.match(disabledSubmitRule, /background:\s*var\(--portfolio-surface-muted\);/)
  assert.match(errorStateRule, /color:\s*#dc9999;/)
})

test('personal dark contact form keeps the optional schedule prompt secondary', () => {
  const wxss = read('components/portfolio-contact-form/portfolio-contact-form.wxss')
  const placeholderRule = readRule(
    wxss,
    '.portfolio-contact-form.portfolio-theme-dark .date-field.placeholder'
  )

  assert.match(placeholderRule, /color:\s*var\(--portfolio-text-secondary\);/)
})

test('team dark inquiry entry buttons use the same muted surface as personal portfolio', () => {
  const themeWxss = read('pages/team-portfolios/styles/team-portfolio-theme.wxss')
  const contactEntryRule = readRule(themeWxss, '.theme-dark .contact-form-entry-button')
  const scheduleEntryRule = readRule(themeWxss, '.theme-dark .schedule-query-open-button')

  for (const rule of [contactEntryRule, scheduleEntryRule]) {
    assert.match(rule, /color:\s*var\(--team-portfolio-text-primary\);/)
    assert.match(rule, /background:\s*var\(--team-portfolio-surface-muted\);/)
  }
})

test('team dark inline inquiry action buttons use muted surfaces', () => {
  const themeWxss = read('pages/team-portfolios/styles/team-portfolio-theme.wxss')
  const contactSubmitRule = readRule(
    themeWxss,
    '.theme-dark .form-section .primary-button'
  )
  const scheduleSubmitRule = readRule(
    themeWxss,
    '.theme-dark .schedule-query-inline-shell .schedule-query-submit'
  )
  const disabledScheduleSubmitRule = readRule(
    themeWxss,
    '.theme-dark .schedule-query-inline-shell .schedule-query-submit[disabled]'
  )

  for (const rule of [contactSubmitRule, scheduleSubmitRule]) {
    assert.match(rule, /color:\s*var\(--team-portfolio-text-primary\);/)
    assert.match(rule, /background:\s*var\(--team-portfolio-surface-muted\);/)
  }
  assert.match(disabledScheduleSubmitRule, /color:\s*var\(--team-portfolio-text-secondary\);/)
  assert.match(disabledScheduleSubmitRule, /background:\s*var\(--team-portfolio-surface-muted\);/)
})

test('team inquiry components keep their dark theme wiring and secondary schedule prompt', () => {
  const scheduleWxml = read('pages/team-portfolios/components/schedule-query/schedule-query.wxml')
  const contactWxml = read('pages/team-portfolios/components/contact-form/contact-form.wxml')
  const themeWxss = read('pages/team-portfolios/styles/team-portfolio-theme.wxss')
  const placeholderRule = readRule(themeWxss, '.theme-dark .date-field.placeholder')
  const selectedDayRule = readRule(themeWxss, '.theme-dark .schedule-calendar-day.selected')
  const selectedDayStackRule = readRule(
    themeWxss,
    '.theme-dark .schedule-calendar-day.selected .schedule-query-day-stack'
  )
  const modalSelectedDayStackRule = readRule(
    themeWxss,
    '.theme-dark .schedule-query-modal-panel .schedule-calendar-day.selected .schedule-query-day-stack'
  )

  assert.match(scheduleWxml, /class="schedule-query theme-\{\{themeMode\}\}/)
  assert.match(contactWxml, /class="contact-form theme-\{\{themeMode\}\}/)
  assert.match(
    themeWxss,
    /\.theme-dark \.schedule-query-inline-shell \.schedule-query-calendar,[\s\S]*background:\s*#222222;/
  )
  assert.match(selectedDayRule, /background:\s*transparent;/)
  assert.match(selectedDayStackRule, /color:\s*#212529;/)
  assert.match(selectedDayStackRule, /background:\s*#ffffff;/)
  assert.match(selectedDayStackRule, /box-shadow:\s*0\s+0\s+0\s+20rpx\s+#ffffff;/)
  assert.match(modalSelectedDayStackRule, /color:\s*#ffffff;/)
  assert.match(modalSelectedDayStackRule, /background:\s*#212529;/)
  assert.match(modalSelectedDayStackRule, /box-shadow:\s*0\s+0\s+0\s+20rpx\s+#212529;/)
  assert.match(placeholderRule, /color:\s*var\(--team-portfolio-text-secondary\);/)
})

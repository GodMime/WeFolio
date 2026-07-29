const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MINIAPP_ROOT = path.resolve(__dirname, '..')

function read(relativePath) {
  return fs.readFileSync(path.join(MINIAPP_ROOT, relativePath), 'utf8')
}

function readZIndex(wxss, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const rule = wxss.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  const zIndex = rule && rule[1].match(/z-index:\s*(\d+);/)
  return zIndex ? Number(zIndex[1]) : 0
}

function readRule(wxss, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const rule = wxss.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return rule ? rule[1] : ''
}

function assertRootModal({
  wxml,
  wxss,
  modalSelector,
  visibleExpression,
  themeClass
}) {
  const modalStart = wxml.indexOf(`<view class="${modalSelector}`)
  const portalStart = wxml.lastIndexOf('<root-portal', modalStart)
  const portalEnd = wxml.indexOf('</root-portal>', modalStart)

  assert.ok(modalStart >= 0, `${modalSelector} 必须存在`)
  assert.ok(portalStart >= 0 && portalStart < modalStart, `${modalSelector} 必须位于 root-portal 内`)
  assert.ok(portalEnd > modalStart, `root-portal 必须完整包裹 ${modalSelector}`)
  assert.match(
    wxml.slice(portalStart, wxml.indexOf('>', portalStart) + 1),
    new RegExp(`wx:if="\\{\\{${visibleExpression}\\}\\}"`)
  )
  assert.match(
    wxml.slice(modalStart, wxml.indexOf('>', modalStart) + 1),
    new RegExp(themeClass)
  )

  const bottomNavWxss = read('components/portfolio-bottom-nav/portfolio-bottom-nav.wxss')
  const modalRule = readRule(wxss, `.${modalSelector}`)
  assert.match(modalRule, /position:\s*fixed;/)
  assert.match(modalRule, /bottom:\s*0;/)
  assert.match(modalRule, /align-items:\s*flex-end;/)
  assert.ok(
    readZIndex(wxss, `.${modalSelector}`) >
      readZIndex(bottomNavWxss, '.portfolio-bottom-nav'),
    `${modalSelector} 必须遮住底部导航`
  )
}

test('team schedule and contact modals mount at page root and reach the viewport bottom', () => {
  assertRootModal({
    wxml: read('pages/team-portfolios/components/schedule-query/schedule-query.wxml'),
    wxss: read('pages/team-portfolios/components/schedule-query/schedule-query.wxss'),
    modalSelector: 'schedule-query-modal-mask',
    visibleExpression: 'modalVisible',
    themeClass: 'theme-\\{\\{themeMode\\}\\}'
  })
  assertRootModal({
    wxml: read('pages/team-portfolios/components/contact-form/contact-form.wxml'),
    wxss: read('pages/team-portfolios/components/contact-form/contact-form.wxss'),
    modalSelector: 'contact-form-mask',
    visibleExpression: 'modalVisible',
    themeClass: 'theme-\\{\\{themeMode\\}\\}'
  })
})

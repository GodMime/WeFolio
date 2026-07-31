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

test('personal schedule modal mounts at root above the bottom navigation', () => {
  const scheduleWxml = read('components/portfolio-schedule-query/portfolio-schedule-query.wxml')
  const scheduleWxss = read('components/portfolio-schedule-query/portfolio-schedule-query.wxss')
  const bottomNavWxss = read('components/portfolio-bottom-nav/portfolio-bottom-nav.wxss')
  const modalStart = scheduleWxml.indexOf('<view class="schedule-query-modal-mask')
  const portalStart = scheduleWxml.lastIndexOf('<root-portal', modalStart)
  const portalEnd = scheduleWxml.indexOf('</root-portal>', modalStart)

  assert.ok(modalStart >= 0, '档期弹层遮罩必须存在')
  assert.ok(portalStart >= 0 && portalStart < modalStart, '档期弹层必须位于 root-portal 内')
  assert.ok(portalEnd > modalStart, 'root-portal 必须完整包裹档期弹层')
  assert.match(
    scheduleWxml.slice(portalStart, scheduleWxml.indexOf('>', portalStart) + 1),
    /wx:if="\{\{visible\}\}"/
  )
  assert.ok(
    readZIndex(scheduleWxss, '.schedule-query-modal-mask') >
      readZIndex(bottomNavWxss, '.portfolio-bottom-nav'),
    '根层档期弹层的 z-index 必须高于底部导航'
  )
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function read(relativePath) {
  return fs.readFileSync(path.join(__dirname, '..', relativePath), 'utf8')
}

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = content.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
}

test('navigation bar title stays on a single centered line on narrow devices', () => {
  const navWxml = read('components/navigation-bar/navigation-bar.wxml')
  const navWxss = read('components/navigation-bar/navigation-bar.wxss')
  const centerRule = readRule(navWxss, '.weui-navigation-bar__center')
  const titleRule = readRule(navWxss, '.weui-navigation-bar__title')

  assert.match(navWxml, /<text class="weui-navigation-bar__title">\{\{title\}\}<\/text>/)
  assert.match(centerRule, /min-width:\s*0;/)
  assert.match(centerRule, /overflow:\s*hidden;/)
  assert.match(titleRule, /display:\s*block;/)
  assert.match(titleRule, /max-width:\s*100%;/)
  assert.match(titleRule, /overflow:\s*hidden;/)
  assert.match(titleRule, /text-overflow:\s*ellipsis;/)
  assert.match(titleRule, /white-space:\s*nowrap;/)
})

test('navigation bar reserves top space through the WeChat capsule area', () => {
  const { buildNavigationBarLayout } = require('../utils/navigation-bar-layout')

  const layout = buildNavigationBarLayout({
    deviceInfo: { platform: 'android' },
    menuButtonRect: {
      left: 300,
      top: 52,
      height: 32
    },
    windowInfo: {
      windowWidth: 390,
      statusBarHeight: 44,
      safeArea: {
        top: 0
      }
    }
  })

  assert.equal(layout.ios, false)
  assert.equal(layout.innerPaddingRight, 'padding-right: 90px')
  assert.equal(layout.leftWidth, 'width: 90px')
  assert.equal(layout.safeAreaTop, 'height: 92px; padding-top: 44px')
})

test('navigation bar back button aligns to the calculated content row', () => {
  const navWxss = read('components/navigation-bar/navigation-bar.wxss')
  const leftRule = readRule(navWxss, '.weui-navigation-bar__left')

  assert.match(leftRule, /display:\s*flex/)
  assert.match(leftRule, /align-items:\s*center;/)
  assert.doesNotMatch(leftRule, /align-items:\s*flex-start;/)
})

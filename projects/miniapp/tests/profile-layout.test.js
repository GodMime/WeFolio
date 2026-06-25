const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const appJson = fs.readFileSync(path.join(__dirname, '../app.json'), 'utf8')
const indexJs = fs.readFileSync(path.join(__dirname, '../pages/index/index.js'), 'utf8')
const indexWxml = fs.readFileSync(path.join(__dirname, '../pages/index/index.wxml'), 'utf8')
const profileJsPath = path.join(__dirname, '../pages/profile/profile.js')
const profileWxmlPath = path.join(__dirname, '../pages/profile/profile.wxml')
const profileWxssPath = path.join(__dirname, '../pages/profile/profile.wxss')

function readProfileRule(selector) {
  const profileWxss = fs.readFileSync(profileWxssPath, 'utf8')
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = profileWxss.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
}

test('mine profile card navigates to basic profile page', () => {
  assert.match(appJson, /"pages\/profile\/profile"/)
  assert.match(indexJs, /handleProfileTap/)
  assert.match(indexJs, /url:\s*'\/pages\/profile\/profile'/)
  assert.match(indexWxml, /class="panel profile-panel"[\s\S]*bindtap="handleProfileTap"/)
  assert.match(indexWxml, /class="code-pill"[\s\S]*catchtap="handleCopyCode"/)
})

test('basic profile page files and controls match design draft', () => {
  assert.equal(fs.existsSync(profileJsPath), true)
  assert.equal(fs.existsSync(profileWxmlPath), true)
  assert.equal(fs.existsSync(profileWxssPath), true)

  const profileWxml = fs.readFileSync(profileWxmlPath, 'utf8')
  assert.match(profileWxml, /navigation-bar title="基础信息"/)
  assert.match(profileWxml, /open-type="chooseAvatar"/)
  assert.match(profileWxml, />姓名 \/ 艺名</)
  assert.match(profileWxml, />职业身份</)
  assert.match(profileWxml, />服务城市</)
  assert.match(profileWxml, />个人简介</)
  assert.match(profileWxml, />个人标签</)
  assert.match(profileWxml, /bindtap="handleSave"[\s\S]*>保存修改</)
  assert.match(profileWxml, /bindtap="handleLogout"[\s\S]*>退出登录</)
  assert.match(profileWxml, /bindtap="handleCancelAccount"[\s\S]*>注销账号</)
  assert.match(profileWxml, /wx:if="{{tagDialogVisible}}"/)
  assert.match(profileWxml, />标签颜色</)
  assert.match(profileWxml, /wx:for="{{tagColorOptions}}"/)
  assert.match(profileWxml, /bindtap="handleSelectTagColor"/)
})

test('basic profile page uses backend profile and avatar endpoints', () => {
  const profileJs = fs.readFileSync(profileJsPath, 'utf8')

  assert.match(profileJs, /url:\s*'\/api\/mine\/profile'/)
  assert.match(profileJs, /method:\s*'PUT'/)
  assert.match(profileJs, /\/api\/auth\/avatar/)
  assert.match(profileJs, /url:\s*'\/api\/auth\/account\/cancel'/)
  assert.match(profileJs, /wx\.showModal/)
  assert.match(profileJs, /TOKEN_STORAGE_KEY/)
})

test('basic profile fields show character limit counters', () => {
  const profileWxml = fs.readFileSync(profileWxmlPath, 'utf8')
  const headingRule = readProfileRule('.field-heading')
  const limitRule = readProfileRule('.field-limit')

  assert.match(
    profileWxml,
    /class="field-heading"[\s\S]*姓名 \/ 艺名[\s\S]*\{\{fieldCounters\.nickname\}\}/
  )
  assert.match(
    profileWxml,
    /class="field-heading"[\s\S]*职业身份[\s\S]*\{\{fieldCounters\.profession\}\}/
  )
  assert.match(
    profileWxml,
    /class="field-heading"[\s\S]*服务城市[\s\S]*\{\{fieldCounters\.city\}\}/
  )
  assert.match(
    profileWxml,
    /class="field-heading"[\s\S]*个人简介[\s\S]*\{\{fieldCounters\.intro\}\}/
  )
  assert.match(headingRule, /display:\s*flex/)
  assert.match(headingRule, /justify-content:\s*space-between/)
  assert.match(limitRule, /font-size:\s*22rpx/)
  assert.match(limitRule, /color:\s*#8b96a3/)
})

test('profile tag remove control renders as a compact icon trigger', () => {
  const profileWxml = fs.readFileSync(profileWxmlPath, 'utf8')
  const tagRemoveRule = readProfileRule('.tag-remove')

  assert.doesNotMatch(profileWxml, /<button class="tag-remove"/)
  assert.match(
    profileWxml,
    /<view class="tag-remove"[^>]*data-index="\{\{index\}\}"[^>]*catchtap="handleRemoveTag"[^>]*aria-role="button"[^>]*aria-label="删除标签\{\{item\.content\}\}"/
  )
  assert.match(tagRemoveRule, /width:\s*36rpx/)
  assert.match(tagRemoveRule, /height:\s*36rpx/)
  assert.match(tagRemoveRule, /min-width:\s*36rpx/)
  assert.match(tagRemoveRule, /flex:\s*none/)
  assert.match(tagRemoveRule, /overflow:\s*hidden/)
})

test('basic profile action buttons use wider tap targets', () => {
  const profileWxml = fs.readFileSync(profileWxmlPath, 'utf8')
  const wideButtonRule = readProfileRule('.wide-button')
  const contentWideButtonRule = readProfileRule('.profile-content > .wide-button')
  const dangerWideButtonRule = readProfileRule('.danger-panel > .wide-button')

  assert.match(profileWxml, /class="tag-add-trigger wide-button"/)
  assert.match(profileWxml, /class="primary-button full wide-button"[\s\S]*>保存修改</)
  assert.match(profileWxml, /class="secondary-button full wide-button"[\s\S]*>退出登录</)
  assert.match(profileWxml, /class="danger-button full wide-button"[\s\S]*>注销账号</)
  assert.match(wideButtonRule, /margin-left:\s*auto/)
  assert.match(wideButtonRule, /margin-right:\s*auto/)
  assert.match(contentWideButtonRule, /width:\s*calc\(100% - 48rpx\)/)
  assert.match(contentWideButtonRule, /min-width:\s*calc\(100% - 48rpx\)/)
  assert.match(contentWideButtonRule, /max-width:\s*calc\(100% - 48rpx\)/)
  assert.match(dangerWideButtonRule, /width:\s*100%/)
  assert.match(dangerWideButtonRule, /min-width:\s*100%/)
  assert.match(dangerWideButtonRule, /max-width:\s*100%/)
})

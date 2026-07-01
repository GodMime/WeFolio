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
const avatarJsPath = path.join(__dirname, '../utils/avatar.js')
const uploadFileJsPath = path.join(__dirname, '../utils/upload-file.js')

function readProfileRule(selector) {
  const profileWxss = fs.readFileSync(profileWxssPath, 'utf8')
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = profileWxss.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
}

function assertProfileTagDialogAnimation() {
  const tagDialogRule = readProfileRule('.tag-dialog')
  const visibleTagDialogRule = readProfileRule('.tag-dialog.visible')
  const tagDialogPanelRule = readProfileRule('.tag-dialog-panel')
  const visiblePanelRule = readProfileRule('.tag-dialog.visible .tag-dialog-panel')

  assert.match(tagDialogRule, /opacity:\s*0/)
  assert.match(tagDialogRule, /pointer-events:\s*none/)
  assert.match(tagDialogRule, /transition:[^;]*opacity\s+\d+ms/)
  assert.match(visibleTagDialogRule, /opacity:\s*1/)
  assert.match(visibleTagDialogRule, /pointer-events:\s*auto/)
  assert.match(tagDialogPanelRule, /transform:/)
  assert.match(tagDialogPanelRule, /transition:[^;]*transform\s+\d+ms/)
  assert.match(visiblePanelRule, /transform:/)
  assert.doesNotMatch(tagDialogPanelRule, /translateY\(32rpx\)/)
  assert.doesNotMatch(tagDialogPanelRule, /transition:\s*transform 180ms ease/)
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
  assert.match(profileWxml, /class="tag-dialog \{\{tagDialogVisible \? 'visible' : ''\}\}"/)
  assert.doesNotMatch(profileWxml, /wx:if="{{tagDialogVisible}}"/)
  assert.match(profileWxml, />标签颜色</)
  assert.match(profileWxml, /wx:for="{{tagColorOptions}}"/)
  assert.match(profileWxml, /bindtap="handleSelectTagColor"/)
})

test('basic profile page uses backend profile and shared avatar endpoints', () => {
  const profileJs = fs.readFileSync(profileJsPath, 'utf8')
  const avatarJs = fs.readFileSync(avatarJsPath, 'utf8')
  const uploadFileJs = fs.readFileSync(uploadFileJsPath, 'utf8')

  assert.match(profileJs, /url:\s*'\/api\/mine\/profile'/)
  assert.match(profileJs, /method:\s*'PUT'/)
  assert.match(profileJs, /uploadAvatar/)
  assert.match(avatarJs, /\/api\/auth\/avatar/)
  assert.match(avatarJs, /uploadPreparedFile/)
  assert.match(uploadFileJs, /TOKEN_STORAGE_KEY/)
  assert.match(uploadFileJs, /Authorization = `Bearer \$\{token\}`/)
  assert.match(profileJs, /url:\s*'\/api\/auth\/account\/cancel'/)
  assert.match(profileJs, /wx\.showModal/)
})

test('basic profile avatar picker only previews and save uploads the selected image', () => {
  const profileJs = fs.readFileSync(profileJsPath, 'utf8')

  assert.match(profileJs, /handleChooseAvatar\(event\)/)
  assert.match(profileJs, /'form\.avatarUrl': avatarUrl/)
  assert.doesNotMatch(profileJs, /avatarUploading/)
  assert.doesNotMatch(profileJs, /const uploadedAvatarUrl = await uploadAvatar\(avatarUrl\)/)
  assert.match(profileJs, /avatarUrl = await uploadAvatar\(payload\.avatarUrl\)/)
  assert.match(profileJs, /data:\s*Object\.assign\(\{\}, payload, \{[\s\S]*avatarUrl/)
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

test('profile tag dialog uses Skyline compatible bottom sheet positioning', () => {
  const profileWxss = fs.readFileSync(profileWxssPath, 'utf8')
  const tagDialogRule = readProfileRule('.tag-dialog')
  const tagDialogPanelRule = readProfileRule('.tag-dialog-panel')
  const colorGridRule = readProfileRule('.color-grid')
  const colorChoiceRule = readProfileRule('.color-choice')

  assert.match(tagDialogRule, /position:\s*fixed/)
  assert.match(tagDialogRule, /left:\s*0/)
  assert.match(tagDialogRule, /right:\s*0/)
  assert.match(tagDialogRule, /top:\s*0/)
  assert.match(tagDialogRule, /bottom:\s*0/)
  assert.doesNotMatch(tagDialogRule, /display:\s*flex/)
  assert.doesNotMatch(tagDialogRule, /align-items:\s*flex-end/)
  assert.match(tagDialogPanelRule, /position:\s*absolute/)
  assert.match(tagDialogPanelRule, /left:\s*0/)
  assert.match(tagDialogPanelRule, /right:\s*0/)
  assert.match(tagDialogPanelRule, /bottom:\s*0/)
  assertProfileTagDialogAnimation()
  assert.match(colorGridRule, /display:\s*flex/)
  assert.match(colorGridRule, /flex-wrap:\s*wrap/)
  assert.doesNotMatch(colorGridRule, /display:\s*grid/)
  assert.doesNotMatch(profileWxss, /grid-template-columns:\s*repeat\(3,\s*minmax\(0,\s*1fr\)\)/)
  assert.doesNotMatch(colorChoiceRule, /\/\s*3/)
  assert.match(colorChoiceRule, /width:\s*31\.3%/)
  assert.match(colorChoiceRule, /flex:\s*none/)
})

test('single full-width profile buttons follow their container width rule', () => {
  const profileWxml = fs.readFileSync(profileWxmlPath, 'utf8')
  const wideButtonRule = readProfileRule('.wide-button')
  const contentWideButtonRule = readProfileRule('.profile-content > .wide-button')
  const dangerWideButtonRule = readProfileRule('.danger-panel > .wide-button')

  assert.match(profileWxml, /class="tag-add-trigger wide-button"/)
  assert.match(profileWxml, /class="primary-button full wide-button"[\s\S]*>保存修改</)
  assert.match(profileWxml, /class="secondary-button full wide-button"[\s\S]*>退出登录</)
  assert.match(profileWxml, /class="danger-button full wide-button"[\s\S]*>注销账号</)
  assert.match(wideButtonRule, /width:\s*100%/)
  assert.match(wideButtonRule, /min-width:\s*100%/)
  assert.match(wideButtonRule, /max-width:\s*100%/)
  assert.match(wideButtonRule, /margin-left:\s*auto/)
  assert.match(wideButtonRule, /margin-right:\s*auto/)
  assert.match(contentWideButtonRule, /width:\s*100%/)
  assert.match(contentWideButtonRule, /min-width:\s*100%/)
  assert.match(contentWideButtonRule, /max-width:\s*100%/)
  assert.doesNotMatch(contentWideButtonRule, /calc\(100% - 48rpx\)/)
  assert.match(dangerWideButtonRule, /width:\s*100%/)
  assert.match(dangerWideButtonRule, /min-width:\s*100%/)
  assert.match(dangerWideButtonRule, /max-width:\s*100%/)
})

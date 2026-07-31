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
const profileAssetsJsPath = path.join(__dirname, '../utils/profile-assets.js')

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
  assert.match(profileWxml, />微信二维码</)
  assert.match(profileWxml, />小于300KB · 每月可修改3次</)
  assert.doesNotMatch(profileWxml, />小于300KB · 每月3次</)
  assert.match(profileWxml, /bindtap="handleChooseWechatQr"/)
  assert.match(profileWxml, /class="secondary-button profile-action-button avatar-button"[\s\S]*>更换头像</)
  assert.match(profileWxml, /class="secondary-button profile-action-button qr-button"[\s\S]*>上传二维码</)
  assert.match(profileWxml, /class="wechat-qr-crop-dialog \{\{wechatQrCropVisible \? 'visible' : ''\}\}"/)
  assert.match(profileWxml, />裁剪微信二维码</)
  assert.match(profileWxml, />1:1</)
  assert.match(profileWxml, /catchtouchstart="handleWechatQrCropTouchStart"/)
  assert.match(profileWxml, /catchtap="handleConfirmWechatQrCrop"/)
  assert.match(profileWxml, /id="wechatQrCropCanvas"/)
  assert.doesNotMatch(profileWxml, /bindtap="handleClearWechatQr"/)
  assert.doesNotMatch(profileWxml, />清空二维码</)
  assert.doesNotMatch(profileWxml, /class="panel profile-qr-panel"/)
  assert.match(profileWxml, />个人标签</)
  assert.match(profileWxml, /bindtap="handleSave"[\s\S]*>保存修改</)
  assert.match(profileWxml, /bindtap="handleLogout"[\s\S]*>退出登录</)
  assert.match(profileWxml, /bindtap="handleCancelAccount"[\s\S]*>注销账号</)
  assert.match(profileWxml, /class="tag-dialog \{\{tagDialogVisible \? 'visible' : ''\}\}"/)
  assert.doesNotMatch(profileWxml, /wx:if="{{tagDialogVisible}}"/)
  assert.match(profileWxml, />标签颜色</)
  assert.match(profileWxml, /wx:for="{{tagColorOptions}}"/)
  assert.match(profileWxml, /bindtap="handleSelectTagColor"/)

  const contentRule = readProfileRule('.profile-content')
  const panelRule = readProfileRule('.panel')
  const inputRule = readProfileRule('.input')
  const textareaRule = readProfileRule('.input.textarea')
  const primaryButtonRule = readProfileRule('.primary-button')

  assert.match(contentRule, /padding:\s*20rpx 32rpx/)
  assert.match(panelRule, /border:\s*1rpx solid #e9ecef/)
  assert.match(panelRule, /border-radius:\s*48rpx/)
  assert.match(inputRule, /min-height:\s*84rpx/)
  assert.match(textareaRule, /min-height:\s*144rpx/)
  assert.match(primaryButtonRule, /border-radius:\s*999rpx/)
  assert.match(primaryButtonRule, /background:\s*#212529/)
})

test('wechat QR editor sits directly below intro inside profile fields panel', () => {
  const profileWxml = fs.readFileSync(profileWxmlPath, 'utf8')
  const fieldsPanelStart = profileWxml.indexOf('class="panel profile-fields-panel"')
  const tagPanelStart = profileWxml.indexOf('class="panel tag-editor-panel"')
  const introStart = profileWxml.indexOf('>个人简介<')
  const qrStart = profileWxml.indexOf('>微信二维码<')

  assert.notEqual(fieldsPanelStart, -1)
  assert.notEqual(tagPanelStart, -1)
  assert.notEqual(introStart, -1)
  assert.notEqual(qrStart, -1)
  assert.ok(fieldsPanelStart < introStart)
  assert.ok(introStart < qrStart)
  assert.ok(qrStart < tagPanelStart)
  assert.match(
    profileWxml,
    /class="panel profile-fields-panel"[\s\S]*>个人简介<[\s\S]*<textarea[^>]*\/>\s*<\/view>\s*<view class="form-row qr-form-row">[\s\S]*>微信二维码</
  )
})

test('profile asset buttons share one compact secondary style', () => {
  const profileWxss = fs.readFileSync(profileWxssPath, 'utf8')
  const actionButtonRule = readProfileRule('.profile-action-button')
  const qrButtonRule = readProfileRule('.qr-button')
  const qrActionsRule = readProfileRule('.qr-actions')

  assert.match(actionButtonRule, /flex:\s*none/)
  assert.match(actionButtonRule, /min-width:\s*140rpx/)
  assert.match(actionButtonRule, /min-height:\s*72rpx/)
  assert.match(actionButtonRule, /font-size:\s*24rpx/)
  assert.match(qrActionsRule, /display:\s*flex/)
  assert.match(qrActionsRule, /align-items:\s*center/)
  assert.doesNotMatch(qrButtonRule, /width:\s*100%/)
  assert.doesNotMatch(qrButtonRule, /min-height:\s*70rpx/)
  assert.doesNotMatch(profileWxss, /\.qr-button\s*\{[^}]*font-size:\s*24rpx/)
})

test('basic profile page uses backend profile and shared avatar endpoints', () => {
  const profileJs = fs.readFileSync(profileJsPath, 'utf8')
  const profileAssetsJs = fs.readFileSync(profileAssetsJsPath, 'utf8')

  assert.match(profileJs, /url:\s*'\/api\/mine\/profile'/)
  assert.match(profileJs, /method:\s*'PUT'/)
  assert.match(profileJs, /uploadProfileAvatar/)
  assert.match(profileJs, /uploadWechatQr/)
  assert.doesNotMatch(profileJs, /uploadAvatar/)
  assert.match(profileAssetsJs, /\/api\/mine\/profile\/assets\/upload-ticket/)
  assert.match(profileAssetsJs, /WECHAT_QR/)
  assert.match(profileAssetsJs, /AVATAR/)
  assert.match(profileJs, /url:\s*'\/api\/auth\/account\/cancel'/)
  assert.match(profileJs, /wx\.showModal/)
})

test('basic profile avatar picker only previews and save uploads the selected image', () => {
  const profileJs = fs.readFileSync(profileJsPath, 'utf8')

  assert.match(profileJs, /handleChooseAvatar\(event\)/)
  assert.match(profileJs, /'form\.avatarUrl': avatarUrl/)
  assert.doesNotMatch(profileJs, /avatarUploading/)
  assert.doesNotMatch(profileJs, /const uploadedAvatarUrl = await uploadProfileAvatar\(avatarUrl\)/)
  assert.match(profileJs, /avatarUrl = await uploadProfileAvatar\(payload\.avatarUrl\)/)
  assert.match(profileJs, /wechatQrUrl = await uploadWechatQr\(payload\.wechatQrUrl\)/)
  assert.match(profileJs, /data:\s*Object\.assign\(\{\}, payload, \{[\s\S]*avatarUrl[\s\S]*wechatQrUrl/)
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
  assert.match(limitRule, /color:\s*#adb5bd/)
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

test('profile tags use white outlined pills with their own color dots', () => {
  const profileWxml = fs.readFileSync(profileWxmlPath, 'utf8')
  const profileWxss = fs.readFileSync(profileWxssPath, 'utf8')
  const tagDotRule = readProfileRule('.tag-dot')
  const tagRemoveRule = readProfileRule('.tag-remove')

  assert.match(
    profileWxml,
    /class="tag-pill"[\s\S]*style="\{\{item\.style\}\}"[\s\S]*class="tag-dot"[\s\S]*style="\{\{item\.dotStyle\}\}"/
  )
  assert.match(profileWxss, /\.tag-pill,\s*\.tag-add-trigger\s*\{[\s\S]*background:\s*#ffffff;/)
  assert.match(tagDotRule, /width:\s*12rpx/)
  assert.match(tagDotRule, /height:\s*12rpx/)
  assert.match(tagDotRule, /border-radius:\s*50%/)
  assert.match(tagRemoveRule, /border:\s*1rpx solid #e9ecef/)
  assert.match(tagRemoveRule, /background:\s*#ffffff/)
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

test('profile tag color choices match the neutral design with color-only swatches', () => {
  const profileWxml = fs.readFileSync(profileWxmlPath, 'utf8')
  const colorChoiceRule = readProfileRule('.color-choice')
  const activeColorChoiceRule = readProfileRule('.color-choice.active')
  const colorSwatchRule = readProfileRule('.color-swatch')
  const colorNameRule = readProfileRule('.color-name')

  assert.doesNotMatch(
    profileWxml,
    /class="color-choice[^"]*"[\s\S]*style="\{\{item\.choiceStyle\}\}"/
  )
  assert.match(colorChoiceRule, /color:\s*#495057/)
  assert.match(colorChoiceRule, /font-weight:\s*500/)
  assert.match(colorChoiceRule, /border:\s*1rpx solid #e9ecef/)
  assert.match(colorChoiceRule, /background:\s*#ffffff/)
  assert.match(activeColorChoiceRule, /color:\s*#212529/)
  assert.match(activeColorChoiceRule, /font-weight:\s*600/)
  assert.match(activeColorChoiceRule, /border-color:\s*#212529/)
  assert.doesNotMatch(activeColorChoiceRule, /box-shadow/)
  assert.match(colorSwatchRule, /width:\s*12rpx/)
  assert.match(colorSwatchRule, /height:\s*12rpx/)
  assert.match(colorNameRule, /font-weight:\s*inherit/)
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

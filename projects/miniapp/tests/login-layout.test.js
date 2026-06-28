const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const loginWxss = fs.readFileSync(
  path.join(__dirname, '../pages/login/login.wxss'),
  'utf8'
)
const loginWxml = fs.readFileSync(
  path.join(__dirname, '../pages/login/login.wxml'),
  'utf8'
)
const loginJs = fs.readFileSync(
  path.join(__dirname, '../pages/login/login.js'),
  'utf8'
)
const indexWxss = fs.readFileSync(
  path.join(__dirname, '../pages/index/index.wxss'),
  'utf8'
)

const STATIC_ASSET_ORIGIN = 'https://cos.we-folio.dingchenyong.top'
const BACKGROUND_IMAGE_URL = `${STATIC_ASSET_ORIGIN}/system/backgroud.jpeg`
const LOGO_IMAGE_URL = `${STATIC_ASSET_ORIGIN}/system/logo.png`
const LEGACY_STATIC_ASSET_URLS = [
  `${STATIC_ASSET_ORIGIN}/backgroud.jpeg`,
  `${STATIC_ASSET_ORIGIN}/logo.png`
]

function readRule(selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = loginWxss.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
}

test('login segmented tabs use flex columns for skyline compatibility', () => {
  const tabsRule = readRule('.login-tabs')
  const tabRule = readRule('.login-tab')

  assert.match(tabsRule, /display:\s*flex/)
  assert.doesNotMatch(tabsRule, /display:\s*grid/)
  assert.match(tabRule, /flex:\s*1/)
})

test('login welcome icon uses product logo image', () => {
  const avatarRule = readRule('.welcome-avatar')

  assert.ok(loginWxml.includes(`src="${LOGO_IMAGE_URL}"`))
  assert.match(loginWxml, /class="welcome-avatar"/)
  assert.match(loginWxml, /mode="aspectFill"/)
  assert.match(avatarRule, /overflow:\s*hidden/)
})

test('login hero uses configured background image', () => {
  assert.ok(loginWxml.includes(`src="${BACKGROUND_IMAGE_URL}"`))
  assert.match(loginWxml, /class="hero-image"/)
})

test('miniapp pages use system static asset urls', () => {
  const staticAssetConsumers = [loginWxml, indexWxss]

  assert.ok(staticAssetConsumers.some((content) => content.includes(BACKGROUND_IMAGE_URL)))
  assert.ok(staticAssetConsumers.some((content) => content.includes(LOGO_IMAGE_URL)))

  for (const content of staticAssetConsumers) {
    for (const legacyUrl of LEGACY_STATIC_ASSET_URLS) {
      assert.ok(!content.includes(legacyUrl), `legacy asset url remains: ${legacyUrl}`)
    }
  }
})

test('login layout keeps content at the bottom while hero resizes', () => {
  const pageRule = readRule('.login-page')
  const scrollRule = readRule('.login-scroll')
  const layoutRule = readRule('.login-layout')
  const heroRule = readRule('.login-hero')
  const contentRule = readRule('.login-content')

  assert.match(loginWxml, /class="login-layout"/)
  assert.ok(loginWxml.indexOf('class="login-hero"') < loginWxml.indexOf('class="login-content"'))
  assert.match(pageRule, /display:\s*flex/)
  assert.match(pageRule, /flex-direction:\s*column/)
  assert.match(scrollRule, /flex:\s*1/)
  assert.match(scrollRule, /min-height:\s*0/)
  assert.doesNotMatch(scrollRule, /calc\(100vh\s*-\s*88rpx\)/)
  assert.match(layoutRule, /display:\s*flex/)
  assert.match(layoutRule, /(^|\n)\s*height:\s*100%/)
  assert.match(layoutRule, /flex-direction:\s*column/)
  assert.match(heroRule, /flex:\s*1\s+1\s+0/)
  assert.match(contentRule, /flex:\s*none/)
})

test('login tab switch animates hero height and image crop', () => {
  const heroRule = readRule('.login-hero')
  const heroImageRule = readRule('.hero-image')

  assert.match(
    loginWxml,
    /class="login-hero \{\{activeTab === 'register' \? 'register-mode' : 'wechat-mode'\}\}"/
  )
  assert.match(heroRule, /transition:[^;]*min-height/)
  assert.match(heroImageRule, /transition:[^;]*transform/)
  assert.match(loginWxss, /\.login-hero\.register-mode\s+\.hero-image\s*\{[\s\S]*transform:\s*scale/)
  assert.match(loginWxss, /\.login-hero\.wechat-mode\s+\.hero-image\s*\{[\s\S]*transform:\s*scale/)
})

test('login tab content stays mounted for animated height transitions', () => {
  const authBodyRule = readRule('.auth-body')
  const formRule = readRule('.login-form')
  const activeFormRule = readRule('.login-form.active')
  const inactiveFormRule = readRule('.login-form.inactive')

  assert.match(
    loginWxml,
    /class="auth-body \{\{activeTab === 'register' \? 'register-mode' : 'wechat-mode'\}\}"/
  )
  assert.match(
    loginWxml,
    /class="login-form register-form \{\{activeTab === 'register' \? 'active' : 'inactive'\}\}"/
  )
  assert.match(
    loginWxml,
    /class="login-form wechat-form \{\{activeTab === 'wechat' \? 'active' : 'inactive'\}\}"/
  )
  assert.doesNotMatch(loginWxml, /wx:else class="login-form"/)
  assert.match(authBodyRule, /overflow:\s*hidden/)
  assert.match(authBodyRule, /transition:\s*max-height/)
  assert.match(formRule, /transition:[^;]*max-height/)
  assert.match(formRule, /transition:[^;]*opacity/)
  assert.match(formRule, /transition:[^;]*transform/)
  assert.match(activeFormRule, /opacity:\s*1/)
  assert.match(activeFormRule, /pointer-events:\s*auto/)
  assert.match(inactiveFormRule, /max-height:\s*0/)
  assert.match(inactiveFormRule, /opacity:\s*0/)
  assert.match(inactiveFormRule, /pointer-events:\s*none/)
})

test('login tabs match design underline style', () => {
  const tabsRule = readRule('.login-tabs')
  const activeTabRule = readRule('.login-tab.active')
  const indicatorRule = readRule('.tab-indicator')
  const registerIndicatorRule = readRule('.tab-indicator.register')
  const wechatIndicatorRule = readRule('.tab-indicator.wechat')

  assert.match(
    loginWxml,
    /class="tab-indicator \{\{activeTab === 'register' \? 'register' : 'wechat'\}\}"/
  )
  assert.match(tabsRule, /border-bottom:\s*1rpx\s+solid\s+#d7dee5/)
  assert.match(tabsRule, /background:\s*transparent/)
  assert.match(tabsRule, /padding:\s*0/)
  assert.match(tabsRule, /position:\s*relative/)
  assert.match(activeTabRule, /background:\s*transparent/)
  assert.match(activeTabRule, /box-shadow:\s*none/)
  assert.match(indicatorRule, /position:\s*absolute/)
  assert.match(indicatorRule, /height:\s*4rpx/)
  assert.match(indicatorRule, /background:\s*#315f9d/)
  assert.match(indicatorRule, /transition:\s*left/)
  assert.match(registerIndicatorRule, /left:\s*25%/)
  assert.match(wechatIndicatorRule, /left:\s*75%/)
})

test('login buttons match first-login design copy and shape', () => {
  const buttonRule = readRule('.primary-button')

  assert.match(loginWxml, />\s*\{\{loading \? '注册中' : '注册'\}\}\s*</)
  assert.doesNotMatch(loginWxml, /微信授权获取手机号并注册/)
  assert.doesNotMatch(loginWxml, /微信授权注册/)
  assert.match(loginWxml, /微信授权登录/)
  assert.match(buttonRule, /height:\s*84rpx/)
  assert.match(buttonRule, /border-radius:\s*16rpx/)
  assert.match(buttonRule, /background:\s*linear-gradient\(180deg,\s*#263445,\s*#111827\)/)
})

test('register form uses WeChat avatar nickname and phone components', () => {
  assert.match(loginWxml, /open-type="chooseAvatar"/)
  assert.match(loginWxml, /bindchooseavatar="handleChooseAvatar"/)
  assert.match(loginWxml, /type="nickname"/)
  assert.match(loginWxml, /bindinput="handleNicknameInput"/)
  assert.match(loginWxml, /open-type="getPhoneNumber"/)
  assert.match(loginWxml, /bindgetphonenumber="handleRegisterPhone"/)
})

test('avatar picker does not render as a left logo beside nickname', () => {
  assert.doesNotMatch(loginWxml, /class="profile-fields"/)
  assert.doesNotMatch(loginWxml, /defaultAvatarUrl/)
  assert.doesNotMatch(loginJs, /defaultAvatarUrl/)
  assert.match(
    loginWxml,
    /class="profile-auth-row"[\s\S]*class="avatar-picker[\s\S]*class="nickname-auth-field/
  )
  assert.match(loginWxml, /class="avatar-placeholder">头像/)
})

test('avatar picker renders as a fixed circular avatar crop', () => {
  const avatarRule = readRule('.avatar-picker')
  const pickedAvatarRule = readRule('.picked-avatar')

  assert.match(avatarRule, /width:\s*68rpx/)
  assert.match(avatarRule, /height:\s*68rpx/)
  assert.match(avatarRule, /min-width:\s*68rpx/)
  assert.match(avatarRule, /border-radius:\s*999rpx/)
  assert.match(avatarRule, /overflow:\s*hidden/)
  assert.match(loginWxml, /class="picked-avatar"[\s\S]*mode="aspectFill"/)
  assert.match(pickedAvatarRule, /border-radius:\s*50%/)
})

test('register form marks missing avatar and nickname authorization in red', () => {
  assert.match(loginWxml, /avatarError/)
  assert.match(loginWxml, /nicknameError/)
  assert.match(loginWxml, /profileErrorText/)
  assert.match(loginWxml, /class="field-error"/)
  assert.match(loginWxss, /\.avatar-picker\.invalid\s*\{/)
  assert.match(loginWxss, /\.nickname-auth-field\.invalid\s*\{/)
  assert.match(loginWxss, /\.field-error\s*\{/)
  assert.match(loginWxss, /#d92d20/)
})

test('nickname is presented as WeChat authorization instead of manual input', () => {
  const nativeNicknameRule = readRule('.nickname-native-input')

  assert.doesNotMatch(loginWxml, /class="nickname-input"/)
  assert.doesNotMatch(loginWxml, /请输入微信昵称/)
  assert.match(loginWxml, /点击授权微信昵称/)
  assert.match(loginWxml, /class="nickname-native-input"/)
  assert.match(loginWxml, /type="nickname"/)
  assert.match(nativeNicknameRule, /opacity:\s*0/)
  assert.match(nativeNicknameRule, /position:\s*absolute/)
})

test('register flow sends phone code and optional plugin openpid code to backend', () => {
  assert.doesNotMatch(loginJs, /getUserProfile/)
  assert.match(loginJs, /wx\.pluginLogin/)
  assert.doesNotMatch(loginJs, /当前微信版本不支持 openpid 授权/)
  assert.match(loginJs, /resolve\(''\)/)
  assert.match(loginJs, /phoneCode/)
  assert.match(loginJs, /pluginLoginCode/)
  assert.match(loginJs, /nickname/)
  assert.match(loginJs, /avatarUrl/)
})

test('register flow uploads avatar only after login token is stored', () => {
  assert.match(loginJs, /avatarUrl:\s*''/)
  assert.match(loginJs, /const preparedAvatarFilePath = options\.phoneCode/)
  assert.match(loginJs, /await prepareAvatarFilePath\(this\.data\.avatarUrl\)/)
  assert.match(
    loginJs,
    /setToken\(response\.token\)[\s\S]*const avatarUrl = await uploadAvatar\(preparedAvatarFilePath,[\s\S]*skipPrepare:\s*true[\s\S]*url:\s*'\/api\/mine\/profile'/
  )
})

test('wechat authorization tab only shows the design CTA', () => {
  assert.doesNotMatch(loginWxml, /wechat-note/)
  assert.doesNotMatch(loginWxml, /使用微信身份快速进入已有账号/)
})

test('mine page does not use login background image', () => {
  assert.ok(!indexWxss.includes(BACKGROUND_IMAGE_URL))
})

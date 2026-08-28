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
const indexJs = fs.readFileSync(
  path.join(__dirname, '../pages/index/index.js'),
  'utf8'
)
const worksWxml = fs.readFileSync(
  path.join(__dirname, '../pages/works/works.wxml'),
  'utf8'
)
const visitsWxml = fs.readFileSync(
  path.join(__dirname, '../pages/visits/visits.wxml'),
  'utf8'
)

const STATIC_ASSET_ROOT = '/assets/system'
const REMOTE_STATIC_ASSET_ROOT = 'https://cdn2.we-folio.dingchenyong.top/system'
const STATIC_ASSET_DIR = path.join(__dirname, '../assets/system')
const MAX_LOCAL_STATIC_ASSET_BYTES = 200 * 1024
const MAX_LOCAL_STATIC_ASSET_TOTAL_BYTES = 200 * 1024
const LOCAL_STATIC_MEDIA_EXTENSIONS = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.mp3', '.wav', '.aac', '.m4a'
])
const COS_SYSTEM_STATIC_ASSET_PATTERN = /https:\/\/cos\.we-folio\.dingchenyong\.top\/system/
const BACKGROUND_IMAGE_URL = `${REMOTE_STATIC_ASSET_ROOT}/backgroud.jpeg`
const LOGO_IMAGE_URL = `${REMOTE_STATIC_ASSET_ROOT}/folio-logo.png`
const MESSAGE_ICON_URL = `${STATIC_ASSET_ROOT}/wefolio-message-icon.png`
const VISITOR_RECORD_ICON_URL = `${STATIC_ASSET_ROOT}/wefolio-visitor-record-icon.png`
const TEAM_ICON_URL = `${STATIC_ASSET_ROOT}/wefolio-team-icon.png`
const WORK_LOGO_URL = `${STATIC_ASSET_ROOT}/work-logo-100kb.jpg`
const COPY_ICON_URL = `${STATIC_ASSET_ROOT}/copy-line.svg`
const REMOTE_SYSTEM_STATIC_ASSETS = [
  { fileName: 'backgroud.jpeg', url: BACKGROUND_IMAGE_URL },
  { fileName: 'folio-logo.png', url: LOGO_IMAGE_URL }
]
const LOCAL_SYSTEM_STATIC_ASSETS = [
  { fileName: 'wefolio-message-icon.png', url: MESSAGE_ICON_URL },
  { fileName: 'wefolio-visitor-record-icon.png', url: VISITOR_RECORD_ICON_URL },
  { fileName: 'wefolio-team-icon.png', url: TEAM_ICON_URL },
  { fileName: 'work-logo-100kb.jpg', url: WORK_LOGO_URL },
  { fileName: 'copy-line.svg', url: COPY_ICON_URL }
]
const SYSTEM_STATIC_ASSETS = [
  ...REMOTE_SYSTEM_STATIC_ASSETS,
  ...LOCAL_SYSTEM_STATIC_ASSETS
]
const NON_SYSTEM_STATIC_ASSET_URLS = [
  '/assets/backgroud.jpeg',
  '/assets/logo.png'
]

function readRule(selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = loginWxss.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
}

function listLocalStaticMediaFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name)
    if (entry.isDirectory()) return listLocalStaticMediaFiles(entryPath)
    return LOCAL_STATIC_MEDIA_EXTENSIONS.has(path.extname(entry.name).toLowerCase()) ? [entryPath] : []
  })
}

test('login segmented tabs use flex columns for skyline compatibility', () => {
  const tabsRule = readRule('.login-tabs')
  const tabRule = readRule('.login-tab')

  assert.match(tabsRule, /display:\s*flex/)
  assert.doesNotMatch(tabsRule, /display:\s*grid/)
  assert.match(tabRule, /flex:\s*1/)
  assert.ok(
    loginWxml.indexOf('data-tab="experience"') < loginWxml.indexOf('data-tab="maintainer"'),
    '体验 tab 应排在登录注册 tab 左侧'
  )
  assert.doesNotMatch(loginWxml, /data-tab="register"|data-tab="wechat"/)
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

test('login hero title uses available width before wrapping', () => {
  const heroTitleRule = readRule('.hero-title')

  assert.match(heroTitleRule, /max-width:\s*100%/)
  assert.match(heroTitleRule, /white-space:\s*normal/)
  assert.doesNotMatch(heroTitleRule, /max-width:\s*520rpx/)
})

test('miniapp pages use system static asset urls', () => {
  const staticAssetConsumers = [loginWxml, indexJs, worksWxml, visitsWxml, indexWxss]
  const combinedStaticAssetConsumers = staticAssetConsumers.join('\n')

  for (const systemAsset of SYSTEM_STATIC_ASSETS) {
    assert.ok(combinedStaticAssetConsumers.includes(systemAsset.url), `missing system asset url: ${systemAsset.url}`)
  }

  for (const systemAsset of LOCAL_SYSTEM_STATIC_ASSETS) {
    const assetPath = path.join(STATIC_ASSET_DIR, systemAsset.fileName)
    assert.ok(
      fs.existsSync(assetPath),
      `missing local system asset file: ${systemAsset.fileName}`
    )
    const assetSize = fs.statSync(assetPath).size
    assert.ok(
      assetSize <= MAX_LOCAL_STATIC_ASSET_BYTES,
      `local system asset ${systemAsset.fileName} is ${assetSize} bytes, max ${MAX_LOCAL_STATIC_ASSET_BYTES} bytes`
    )
  }

  for (const systemAsset of REMOTE_SYSTEM_STATIC_ASSETS) {
    assert.ok(
      !fs.existsSync(path.join(STATIC_ASSET_DIR, systemAsset.fileName)),
      `oversized system asset should stay remote: ${systemAsset.fileName}`
    )
  }

  for (const content of staticAssetConsumers) {
    assert.doesNotMatch(content, COS_SYSTEM_STATIC_ASSET_PATTERN)
    for (const nonSystemUrl of NON_SYSTEM_STATIC_ASSET_URLS) {
      assert.ok(!content.includes(nonSystemUrl), `non-system asset url remains: ${nonSystemUrl}`)
    }
  }
})

test('local system static assets stay within code quality package budget', () => {
  const totalBytes = listLocalStaticMediaFiles(STATIC_ASSET_DIR)
    .reduce((total, assetPath) => total + fs.statSync(assetPath).size, 0)

  assert.ok(
    totalBytes <= MAX_LOCAL_STATIC_ASSET_TOTAL_BYTES,
    `local system static assets use ${totalBytes} bytes, max ${MAX_LOCAL_STATIC_ASSET_TOTAL_BYTES} bytes`
  )
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
  assert.match(contentRule, /padding:\s*20rpx 32rpx/)
})

test('login tab switch animates hero height and image crop', () => {
  const heroRule = readRule('.login-hero')
  const heroImageRule = readRule('.hero-image')

  assert.match(
    loginWxml,
    /class="login-hero \{\{activeTab === 'experience' \? 'experience-mode' : 'maintainer-mode'\}\}"/
  )
  assert.match(heroRule, /transition:[^;]*min-height/)
  assert.match(heroImageRule, /transition:[^;]*transform/)
  assert.match(loginWxss, /\.login-hero\.experience-mode\s+\.hero-image\s*\{[\s\S]*transform:\s*scale/)
  assert.match(loginWxss, /\.login-hero\.maintainer-mode\s+\.hero-image\s*\{[\s\S]*transform:\s*scale/)
})

test('login tab content stays mounted for animated height transitions', () => {
  const authBodyRule = readRule('.auth-body')
  const formRule = readRule('.login-form')
  const activeFormRule = readRule('.login-form.active')
  const inactiveFormRule = readRule('.login-form.inactive')

  assert.match(
    loginWxml,
    /class="auth-body \{\{activeTab === 'experience' \? 'experience-mode' : 'maintainer-mode'\}\}"/
  )
  assert.match(
    loginWxml,
    /class="login-form experience-form \{\{activeTab === 'experience' \? 'active' : 'inactive'\}\}"/
  )
  assert.match(
    loginWxml,
    /class="login-form maintainer-form \{\{activeTab === 'maintainer' \? 'active' : 'inactive'\}\}"/
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
  const experienceIndicatorRule = readRule('.tab-indicator.experience')
  const maintainerIndicatorRule = readRule('.tab-indicator.maintainer')

  assert.match(
    loginWxml,
    /class="tab-indicator \{\{activeTab\}\}"/
  )
  assert.match(tabsRule, /border-bottom:\s*1rpx\s+solid\s+#e9ecef/)
  assert.match(tabsRule, /background:\s*transparent/)
  assert.match(tabsRule, /padding:\s*0/)
  assert.match(tabsRule, /position:\s*relative/)
  assert.match(activeTabRule, /background:\s*transparent/)
  assert.match(activeTabRule, /box-shadow:\s*none/)
  assert.match(indicatorRule, /position:\s*absolute/)
  assert.match(indicatorRule, /width:\s*48rpx/)
  assert.match(indicatorRule, /height:\s*5rpx/)
  assert.match(indicatorRule, /background:\s*#212529/)
  assert.match(indicatorRule, /transition:\s*left/)
  assert.match(experienceIndicatorRule, /left:\s*25%/)
  assert.match(maintainerIndicatorRule, /left:\s*75%/)
})

test('login buttons match first-login design copy and shape', () => {
  const buttonRule = readRule('.primary-button')

  assert.match(loginWxml, />\s*先体验\s*</)
  assert.match(loginWxml, />\s*\{\{maintainerButtonText\}\}\s*</)
  assert.match(loginJs, /'识别账号中'/)
  assert.match(loginJs, /'重新识别'/)
  assert.match(loginJs, /'手机号快捷注册'/)
  assert.match(loginJs, /'登录中'/)
  assert.match(buttonRule, /width:\s*100%/)
  assert.match(buttonRule, /min-width:\s*100%/)
  assert.match(buttonRule, /max-width:\s*100%/)
  assert.match(buttonRule, /margin-left:\s*0/)
  assert.match(buttonRule, /margin-right:\s*0/)
  assert.match(buttonRule, /box-sizing:\s*border-box/)
  assert.match(buttonRule, /height:\s*92rpx/)
  assert.match(buttonRule, /border-radius:\s*999rpx/)
  assert.match(buttonRule, /background:\s*#212529/)
})

test('experience login tab redirects to independent mock pages', () => {
  assert.match(loginJs, /activeTab:\s*'maintainer'/)
  assert.match(loginJs, /handleExperienceTap\(\)/)
  assert.match(loginJs, /wx\.redirectTo\(\{[\s\S]*url:\s*'\/pages\/mock\/index\/index'/)
  assert.match(loginWxml, /data-tab="experience"[\s\S]*体验/)
  assert.match(loginWxml, /class="login-form experience-form[\s\S]*bindtap="handleExperienceTap"[\s\S]*先体验/)
})

test('experience login tab explains trial before registration', () => {
  const authExperienceRule = readRule('.auth-body.experience-mode')
  const experienceFormRule = readRule('.experience-form.active')
  const experienceHintRule = readRule('.experience-hint')

  assert.match(
    loginWxml,
    /class="login-form experience-form[\s\S]*先体验[\s\S]*class="experience-hint"[\s\S]*支持先体验基础功能再授权注册/
  )
  assert.match(authExperienceRule, /max-height:\s*142rpx/)
  assert.match(experienceFormRule, /max-height:\s*142rpx/)
  assert.match(experienceHintRule, /text-align:\s*center/)
  assert.match(experienceHintRule, /color:\s*#868e96/)
})

test('maintainer area uses one button and only enables phone capability for new users', () => {
  assert.equal(
    (loginWxml.match(/class="primary-button maintainer-auth-button"/g) || []).length,
    1
  )
  assert.match(loginWxml, /open-type="\{\{phoneAuthorizationRequired \? 'getPhoneNumber' : ''\}\}"/)
  assert.match(loginWxml, /bindtap="handleMaintainerAuthTap"/)
  assert.match(loginWxml, /bindgetphonenumber="handleRegisterPhone"/)
  assert.match(loginJs, /if \(this\.data\.phoneAuthorizationRequired\) \{\s*return\s*\}/)
})

test('maintainer registration no longer requires avatar or nickname', () => {
  assert.doesNotMatch(loginWxml, /chooseAvatar|type="nickname"|avatarError|nicknameError/)
  assert.doesNotMatch(loginJs, /prepareAvatarFilePath|uploadAvatar|handleChooseAvatar|handleNicknameInput/)
  assert.doesNotMatch(loginJs, /\/api\/mine\/profile/)
  assert.match(loginJs, /nickname:\s*''/)
  assert.match(loginJs, /avatarUrl:\s*''/)
})

test('login page prechecks on load and uses a fresh wx login code for submission', () => {
  assert.match(loginJs, /precheckMaintainerWechatLogin/)
  assert.match(loginJs, /onLoad\(options = \{\}\)\s*\{[\s\S]*?this\.runWechatLoginPrecheck\(\)\s*\n\s*\},/)
  assert.match(loginJs, /async runWechatLoginPrecheck\(\)[\s\S]*const code = await wxLogin\(\)[\s\S]*precheckMaintainerWechatLogin\(code\)/)
  assert.match(loginJs, /async authorizeByWechat\(options = \{\}\)[\s\S]*const code = await wxLogin\(\)/)
})

test('precheck failure keeps phone authorization disabled and supports retry', () => {
  assert.match(loginJs, /precheckReady:\s*false/)
  assert.match(loginJs, /phoneAuthorizationRequired:\s*false/)
  assert.match(loginJs, /precheckErrorMessage:\s*'账号识别失败，请重试'/)
  assert.match(loginJs, /if \(!this\.data\.precheckReady\) \{\s*this\.runWechatLoginPrecheck\(\)\s*return\s*\}/)
  assert.match(loginWxml, /disabled="\{\{prechecking \|\| loading\}\}"/)
})

test('referral code is shown only for an unbound WeChat identity after precheck', () => {
  assert.match(
    loginWxml,
    /wx:if="\{\{precheckReady && phoneAuthorizationRequired\}\}"[^>]*class="form-row"[\s\S]*bindinput="handleReferralInput"/
  )
  assert.match(
    loginWxml,
    /class="field-heading"[\s\S]*推荐码[\s\S]*\{\{referralCode\.length\}\}\/16[\s\S]*maxlength="16"[\s\S]*class="referral-hint">推荐码仅新用户注册时有效/
  )
})

test('referral code is submitted only from the phone registration branch', () => {
  assert.match(loginWxml, /class="field-heading"[\s\S]*推荐码[\s\S]*\{\{referralCode\.length\}\}\/16[\s\S]*maxlength="16"/)
  assert.match(loginJs, /if \(options\.phoneCode\) \{[\s\S]*referralCode:\s*this\.data\.referralCode\.trim\(\)/)
})

test('maintainer form height accommodates referral input hint and auth feedback', () => {
  const authMaintainerRule = readRule('.auth-body.maintainer-mode')
  const maintainerFormRule = readRule('.maintainer-form.active')
  const referralHintRule = readRule('.referral-hint')

  assert.match(authMaintainerRule, /max-height:\s*340rpx/)
  assert.match(maintainerFormRule, /max-height:\s*340rpx/)
  assert.match(referralHintRule, /font-size:\s*21rpx/)
  assert.match(referralHintRule, /color:\s*#868e96/)
})

test('login cards and referral field use the neutral TripGlide contract', () => {
  const panelRule = readRule('.panel')
  const inputRule = readRule('.input-shell')

  assert.match(panelRule, /border:\s*1rpx solid #e9ecef/)
  assert.match(panelRule, /border-radius:\s*48rpx/)
  assert.match(inputRule, /min-height:\s*84rpx/)
})

test('new-user registration sends phone and optional plugin codes while old-user login omits them', () => {
  assert.doesNotMatch(loginJs, /getUserProfile/)
  assert.match(loginJs, /wx\.pluginLogin/)
  assert.match(loginJs, /resolve\(''\)/)
  assert.match(loginJs, /const payload = \{\s*code\s*\}/)
  assert.match(loginJs, /if \(options\.phoneCode\) \{[\s\S]*phoneCode:\s*options\.phoneCode[\s\S]*pluginLoginCode/)
  assert.match(loginJs, /maintainerWechatLogin\(payload\)/)
})

test('ordinary login failure refreshes precheck branch', () => {
  assert.match(loginJs, /catch \(error\) \{[\s\S]*if \(!options\.phoneCode\) \{[\s\S]*this\.runWechatLoginPrecheck\(\)/)
})

test('mine page does not use login background image', () => {
  assert.ok(!indexWxss.includes(BACKGROUND_IMAGE_URL))
})

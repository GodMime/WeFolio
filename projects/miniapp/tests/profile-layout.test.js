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

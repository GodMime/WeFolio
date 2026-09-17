const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const mock = require('../pages/mock/utils/mock-experience')
const root = path.join(__dirname, '..')

test('个人资料边框关闭时不增加外侧留白，开启默认左右32并保留显式零值', () => {
  const draft = mock.buildStandardPortfolio()
  const profile = draft.config.components.find(item => item.componentType === 'PROFILE')
  function render(patch) {
    profile.config = {...profile.config, ...patch}
    return mock.buildMockPortfolioRenderData(draft.config).components.find(item => item.componentType === 'PROFILE').profile
  }
  assert.equal(render({profileBorder:false,profileHorizontalMarginRpx:80}).spacingStyle, '')
  delete profile.config.profileHorizontalMarginRpx
  assert.equal(render({profileBorder:true}).spacingStyle, 'padding: 0rpx 32rpx;')
  assert.equal(render({profileHorizontalMarginRpx:0,profileVerticalMarginRpx:12}).spacingStyle, 'padding: 12rpx 0rpx;')
})

test('个人资料横排只影响头像和身份区，二维码保持正式固定尺寸和空值显隐', () => {
  const markup = fs.readFileSync(path.join(root, 'components/mock/portfolio-renderer/portfolio-renderer.wxml'), 'utf8')
  assert.match(markup, /class="profile-heading"/)
  assert.match(markup, /class="profile-identity"/)
  const qr = markup.split("component.componentType === 'QR_CONTACT'")[1].split('</block>')[0]
  assert.doesNotMatch(qr, /wx:else|qrSize|showLabel/)
  const css = fs.readFileSync(path.join(root, 'components/mock/portfolio-renderer/portfolio-renderer.wxss'), 'utf8')
  assert.match(css, /\.profile-horizontal \.profile-heading\s*\{[^}]*display:\s*flex/)
})

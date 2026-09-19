const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { normalizePortfolioConfig, buildDraftPayload } = require('../pages/portfolios/utils/portfolios')
const { normalizeTeamPortfolioConfig } = require('../pages/team-portfolios/utils/team-portfolios')

test('背景音频配置工具跨包副本保持一致', () => {
  assert.equal(fs.readFileSync(path.join(__dirname, '../pages/portfolios/utils/portfolio-background-audio.js'), 'utf8'),
    fs.readFileSync(path.join(__dirname, '../pages/team-portfolios/utils/portfolio-background-audio.js'), 'utf8'))
})

for (const [name, normalize] of [
  ['个人', normalizePortfolioConfig],
  ['团队', normalizeTeamPortfolioConfig]
]) {
  test(`${name}历史作品集背景音频默认关闭，配置只保留三个字段`, () => {
    assert.deepEqual(normalize({}).backgroundAudio, { enabled: false, workId: null, displayStyle: 'DISC' })
    const input = { enabled: false, workId: 19, displayStyle: 'SLEEVE', mediaUrl: 'https://unused/audio.mp3' }
    assert.deepEqual(normalize({ backgroundAudio: input }).backgroundAudio,
      { enabled: false, workId: 19, displayStyle: 'SLEEVE' })
    assert.equal(input.mediaUrl, 'https://unused/audio.mp3')
  })

  test(`${name}开关与移除保持简单配置语义，重复归一化不丢选择`, () => {
    let config = normalize({ backgroundAudio: { enabled: true, workId: 19, displayStyle: 'MINI_PLAYER' } })
    config.backgroundAudio.enabled = false
    config = normalize(config)
    assert.equal(config.backgroundAudio.workId, 19)
    assert.equal(config.backgroundAudio.displayStyle, 'MINI_PLAYER')
    config.backgroundAudio.workId = null
    assert.deepEqual(normalize(config).backgroundAudio, { enabled: false, workId: null, displayStyle: 'MINI_PLAYER' })
  })
}

test('个人草稿保存沿用原请求并携带背景配置', () => {
  const config = normalizePortfolioConfig({ backgroundAudio: { enabled: true, workId: 19, displayStyle: 'DISC' } })
  assert.deepEqual(buildDraftPayload(config, 3, 'save-key').config.backgroundAudio, config.backgroundAudio)
})

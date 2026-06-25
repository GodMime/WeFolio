const assert = require('node:assert/strict')
const test = require('node:test')

const {
  buildProfilePayload,
  normalizeProfile,
  validateProfileForm
} = require('../utils/profile')

test('normalizes basic profile response for page rendering', () => {
  const profile = normalizeProfile({
    userId: 7,
    uniqueCode: 'WF8392',
    nickname: '林安',
    profession: '婚礼司仪',
    avatarUrl: 'https://example.com/avatar.jpg',
    city: '上海、杭州、苏州',
    intro: '10 年婚礼主持经验',
    tags: ['高端婚礼', '双语主持']
  })

  assert.equal(profile.userId, 7)
  assert.equal(profile.uniqueCode, 'WF8392')
  assert.equal(profile.displayName, '林安 · 婚礼司仪')
  assert.equal(profile.tagCountText, '2 / 10')
  assert.deepEqual(profile.tags, ['高端婚礼', '双语主持'])
})

test('builds trimmed profile save payload', () => {
  const payload = buildProfilePayload({
    nickname: ' 林安 ',
    avatarUrl: ' https://example.com/avatar.jpg ',
    profession: ' 婚礼司仪 ',
    city: ' 上海、杭州、苏州 ',
    intro: ' 10 年婚礼主持经验 ',
    tags: [' 高端婚礼 ', '双语主持']
  })

  assert.deepEqual(payload, {
    nickname: '林安',
    avatarUrl: 'https://example.com/avatar.jpg',
    profession: '婚礼司仪',
    city: '上海、杭州、苏州',
    intro: '10 年婚礼主持经验',
    tags: ['高端婚礼', '双语主持']
  })
})

test('validates profile tag limits before save', () => {
  assert.deepEqual(
    validateProfileForm({ tags: ['高端婚礼', ' 高端婚礼 '] }),
    { valid: false, message: '标签不能重复' }
  )
  assert.deepEqual(
    validateProfileForm({ tags: ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十', '十一'] }),
    { valid: false, message: '标签最多保留 10 个' }
  )
})

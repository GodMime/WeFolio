const assert = require('node:assert/strict')
const test = require('node:test')

const {
  TAG_COLOR_OPTIONS,
  buildProfileFieldCounters,
  buildProfilePayload,
  normalizeProfile,
  validateProfileForm
} = require('../utils/profile')

test('provides nine profile tag color options', () => {
  assert.equal(TAG_COLOR_OPTIONS.length, 9)
  assert.equal(new Set(TAG_COLOR_OPTIONS.map((item) => item.color)).size, 9)
})

test('normalizes basic profile response for page rendering', () => {
  const profile = normalizeProfile({
    userId: 7,
    uniqueCode: 'WF8392',
    nickname: '林安',
    profession: '婚礼司仪',
    avatarUrl: 'https://example.com/avatar.jpg',
    city: '上海、杭州、苏州',
    intro: '10 年婚礼主持经验',
    tags: [
      { content: '高端婚礼', color: '#0f766e' },
      { content: '双语主持', color: '#2d5f9a' }
    ]
  })

  assert.equal(profile.userId, 7)
  assert.equal(profile.uniqueCode, 'WF8392')
  assert.equal(profile.displayName, '林安 · 婚礼司仪')
  assert.equal(profile.tagCountText, '2 / 10')
  assert.deepEqual(profile.tags.map((item) => ({ content: item.content, color: item.color })), [
    { content: '高端婚礼', color: '#0f766e' },
    { content: '双语主持', color: '#2d5f9a' }
  ])
  assert.equal(profile.tags[0].style, 'color: #0f766e; background: #ffffff; border-color: #0f766e;')
  assert.equal(profile.tags[0].dotStyle, 'background: #0f766e;')
  assert.equal(profile.tags[0].removeStyle, 'color: #adb5bd; background: #ffffff; border-color: #e9ecef;')
})

test('normalizes legacy string profile tags with default color', () => {
  const profile = normalizeProfile({
    tags: [' 高端婚礼 ', '双语主持']
  })

  assert.deepEqual(profile.tags.map((item) => ({ content: item.content, color: item.color })), [
    { content: '高端婚礼', color: '#0f766e' },
    { content: '双语主持', color: '#0f766e' }
  ])
})

test('builds trimmed profile save payload', () => {
  const payload = buildProfilePayload({
    nickname: ' 林安 ',
    avatarUrl: ' https://example.com/avatar.jpg ',
    profession: ' 婚礼司仪 ',
    city: ' 上海、杭州、苏州 ',
    intro: ' 10 年婚礼主持经验 ',
    tags: [
      { content: ' 高端婚礼 ', color: '#0F766E' },
      { content: '双语主持', color: '#2d5f9a' }
    ]
  })

  assert.deepEqual(payload, {
    nickname: '林安',
    avatarUrl: 'https://example.com/avatar.jpg',
    wechatQrUrl: '',
    profession: '婚礼司仪',
    city: '上海、杭州、苏州',
    intro: '10 年婚礼主持经验',
    tags: [
      { content: '高端婚礼', color: '#0f766e' },
      { content: '双语主持', color: '#2d5f9a' }
    ]
  })
})

test('builds profile field character limit counters', () => {
  const counters = buildProfileFieldCounters({
    nickname: '丁Sir',
    profession: '全栈',
    city: '杭州、湖州',
    intro: 'OPC'
  })

  assert.deepEqual(counters, {
    nickname: '4 / 50',
    profession: '2 / 50',
    city: '5 / 50',
    intro: '3 / 500'
  })
})

test('validates profile tag limits before save', () => {
  assert.deepEqual(
    validateProfileForm({
      tags: [
        { content: '高端婚礼', color: '#0f766e' },
        { content: ' 高端婚礼 ', color: '#2d5f9a' }
      ]
    }),
    { valid: false, message: '标签不能重复' }
  )
  assert.deepEqual(
    validateProfileForm({
      tags: ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十', '十一']
    }),
    { valid: false, message: '标签最多保留 10 个' }
  )
  assert.deepEqual(
    validateProfileForm({ tags: [{ content: '高端婚礼', color: '#123456' }] }),
    { valid: false, message: '请选择有效的标签颜色' }
  )
})

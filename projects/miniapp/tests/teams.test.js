const assert = require('node:assert/strict')
const test = require('node:test')

const {
  buildTeamFieldCounters,
  buildTeamPayload,
  normalizeTeamDetail,
  normalizeTeamList,
  validateTeamForm
} = require('../utils/teams')

test('normalizes team list response for cards and summary', () => {
  const data = normalizeTeamList({
    summary: {
      joinedCount: 2,
      ownerCount: 1,
      manageableCount: 2
    },
    teams: [
      {
        teamId: 100,
        uniqueCode: 'TM2048',
        name: '星曜司仪团',
        avatarUrl: 'https://cos.example.com/tm2048.png',
        intro: '高端婚礼主持团队',
        role: 'OWNER',
        roleText: '拥有者',
        memberCount: 12,
        memberCountText: '12 位成员',
        canMaintain: true,
        updatedText: '最近更新 06-18'
      },
      {
        teamId: 101,
        uniqueCode: 'TM7316',
        name: '星河主持团队',
        role: 'MANAGER',
        memberCount: 8,
        canMaintain: true
      }
    ]
  })

  assert.equal(data.summary.summaryText, '已加入 2 个团队，其中 1 个为拥有者。')
  assert.equal(data.summary.manageableText, '2 个可维护')
  assert.equal(data.teams.length, 2)
  assert.equal(data.teams[0].subtitle, 'TM2048 · 12 位成员 · 最近更新 06-18')
  assert.equal(data.teams[0].actionText, '维护')
  assert.deepEqual(data.teams[0].pills, [
    { text: '拥有者', tone: 'blue' },
    { text: '可维护', tone: 'teal' }
  ])
  assert.equal(data.teams[1].roleText, '管理者')
  assert.equal(data.teams[1].memberCountText, '8 位成员')
})

test('normalizes team detail response for maintenance page', () => {
  const detail = normalizeTeamDetail({
    team: {
      teamId: 100,
      uniqueCode: 'TM2048',
      name: '星曜司仪团',
      avatarUrl: 'https://cos.example.com/tm2048.png',
      intro: '高端婚礼主持团队',
      roleText: '拥有者',
      memberCount: 2,
      memberCountText: '2 位成员',
      canMaintain: true
    },
    members: [
      {
        memberId: 21,
        userId: 7,
        uniqueCode: 'WF8392',
        displayName: '林安 · 婚礼司仪',
        avatarUrl: 'https://cos.example.com/u7.png',
        profession: '婚礼司仪',
        roleText: '拥有者',
        joinStatusText: '已加入',
        statusTone: 'teal'
      },
      {
        memberId: 22,
        userId: 8,
        uniqueCode: 'MU1186',
        displayName: '乔伊 · 化妆师',
        roleText: '管理者',
        joinStatusText: '待确认',
        statusTone: 'amber',
        userStatus: 'DISABLED',
        userStatusText: '已停用',
        userStatusTone: 'muted'
      }
    ]
  })

  assert.equal(detail.team.codeText, '团队唯一码 TM2048')
  assert.equal(detail.team.displayUniqueCode, 'TM2048')
  assert.equal(detail.team.memberText, '2 位成员')
  assert.equal(detail.team.canMaintain, true)
  assert.equal(detail.members[0].summaryText, '婚礼司仪 · WF8392 · 拥有者')
  assert.equal(detail.members[1].summaryText, '成员 · MU1186 · 管理者')
  assert.equal(detail.members[1].statusTone, 'amber')
  assert.equal(detail.members[1].userStatusVisible, true)
  assert.equal(detail.members[1].userStatusText, '已停用')
  assert.equal(detail.members[1].userStatusTone, 'muted')
})

test('team detail exposes ten-character display code for maintenance header', () => {
  const detail = normalizeTeamDetail({
    team: {
      uniqueCode: 'TM7H9C4V2X99'
    }
  })

  assert.equal(detail.team.uniqueCode, 'TM7H9C4V2X99')
  assert.equal(detail.team.displayUniqueCode, 'TM7H9C4V2X')
  assert.equal(detail.team.codeText, '团队唯一码 TM7H9C4V2X99')
})

test('validates and builds team form payloads', () => {
  assert.deepEqual(validateTeamForm({ name: '' }), {
    valid: false,
    message: '团队名称不能为空'
  })
  assert.deepEqual(validateTeamForm({ name: '星曜司仪团', intro: '介'.repeat(1001) }), {
    valid: false,
    message: '团队简介不能超过 1000 个字'
  })

  const payload = buildTeamPayload({
    name: ' 星曜司仪团 ',
    intro: ' 高端婚礼主持团队 ',
    avatarUrl: ' https://cos.example.com/tm2048.png '
  })

  assert.deepEqual(payload, {
    name: '星曜司仪团',
    intro: '高端婚礼主持团队',
    avatarUrl: 'https://cos.example.com/tm2048.png'
  })
})

test('builds team field counters', () => {
  const counters = buildTeamFieldCounters({
    name: '星曜司仪团',
    intro: 'OPC'
  })

  assert.deepEqual(counters, {
    name: '5 / 100',
    intro: '3 / 1000'
  })
})

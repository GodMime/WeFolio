const assert = require('node:assert/strict')
const test = require('node:test')

const {
  buildMemberCandidateQuery,
  buildMemberChangePayload,
  buildMemberInvitePayload,
  buildTeamFieldCounters,
  buildTeamPayload,
  normalizeTeamMemberChangeDetail,
  normalizeTeamInvitation,
  normalizeTeamMemberCandidate,
  normalizeTeamDetail,
  normalizeTeamList,
  validateMemberChangeForm,
  validateMemberInviteForm,
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
      canMaintain: true,
      canManageMembers: true
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
        statusTone: 'teal',
        allowPortfolio: true,
        allowProfile: true,
        allowWorks: false,
        pendingChange: true,
        pendingChangeId: 41,
        pendingChangeText: '信息变更待同意'
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
  assert.equal(detail.team.canManageMembers, true)
  assert.equal(detail.members[0].summaryText, '婚礼司仪 · WF8392 · 拥有者')
  assert.equal(detail.members[0].allowPortfolio, true)
  assert.equal(detail.members[0].allowProfile, true)
  assert.equal(detail.members[0].allowWorks, false)
  assert.equal(detail.members[0].pendingChange, true)
  assert.equal(detail.members[0].pendingChangeId, 41)
  assert.equal(detail.members[0].pendingChangeText, '信息变更待同意')
  assert.equal(detail.members[1].summaryText, '成员 · MU1186 · 管理者')
  assert.equal(detail.members[1].statusTone, 'amber')
  assert.equal(detail.members[1].userStatusVisible, true)
  assert.equal(detail.members[1].userStatusText, '已停用')
  assert.equal(detail.members[1].userStatusTone, 'muted')
})

test('validates and builds member change payload with body parameters', () => {
  const member = {
    memberId: 31,
    role: 'MEMBER',
    profession: '摄影师',
    allowPortfolio: true,
    allowProfile: false,
    allowWorks: true
  }
  const form = {
    teamId: 100,
    memberId: 31,
    role: 'MANAGER',
    profession: ' 导演 ',
    allowPortfolio: false,
    allowProfile: true,
    allowWorks: true
  }

  assert.deepEqual(validateMemberChangeForm(form, member), {
    valid: true,
    message: ''
  })
  assert.deepEqual(buildMemberChangePayload(form), {
    teamId: 100,
    memberId: 31,
    role: 'MANAGER',
    profession: '导演',
    allowPortfolio: false,
    allowProfile: true,
    allowWorks: true
  })
  assert.deepEqual(validateMemberChangeForm(Object.assign({}, form, { role: 'OWNER' }), member), {
    valid: false,
    message: '请选择团队角色'
  })
  assert.deepEqual(validateMemberChangeForm(Object.assign({}, form, { profession: '团'.repeat(51) }), member), {
    valid: false,
    message: '团队身份不能超过 50 个字'
  })
  assert.deepEqual(validateMemberChangeForm(Object.assign({}, form, {
    role: 'MEMBER',
    profession: '摄影师',
    allowPortfolio: true,
    allowProfile: false,
    allowWorks: true
  }), member), {
    valid: false,
    message: '成员信息没有变化'
  })
})

test('normalizes member change detail for read-only accept reject page', () => {
  const detail = normalizeTeamMemberChangeDetail({
    changeRequestId: 41,
    teamId: 100,
    teamName: '星曜司仪团',
    teamAvatarUrl: 'https://cos.example.com/tm.png',
    requesterName: '林安 · 主持人',
    targetName: '乔伊 · 摄影师',
    status: 'PENDING_CONFIRMATION',
    statusText: '待同意',
    canRespond: true,
    roleBeforeText: '普通成员',
    roleAfterText: '管理者',
    professionBefore: '摄影师',
    professionAfter: '导演',
    permissionBeforeText: '作品集、作品素材',
    permissionAfterText: '主页资料、作品素材',
    requestedAtText: '06-28 16:00'
  })

  assert.equal(detail.changeRequestId, 41)
  assert.equal(detail.titleText, '星曜司仪团')
  assert.equal(detail.summaryText, '林安 · 主持人发起成员信息变更')
  assert.equal(detail.statusTone, 'amber')
  assert.equal(detail.canRespond, true)
  assert.deepEqual(detail.changeRows, [
    { label: '团队角色', before: '普通成员', after: '管理者' },
    { label: '团队身份', before: '摄影师', after: '导演' },
    { label: '引用权限', before: '作品集、作品素材', after: '主页资料、作品素材' }
  ])
  assert.equal(normalizeTeamMemberChangeDetail({ status: 'ACCEPTED' }).statusText, '已同意')
  assert.equal(normalizeTeamMemberChangeDetail({ status: 'REJECTED' }).statusText, '已拒绝')
  assert.equal(normalizeTeamMemberChangeDetail({ status: 'INVALIDATED' }).statusText, '已失效')
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

test('normalizes member candidate and builds candidate query', () => {
  const candidate = normalizeTeamMemberCandidate({
    userId: 8,
    uniqueCode: 'WF1186',
    nickname: '乔伊',
    displayName: '乔伊 · 化妆师',
    avatarUrl: 'https://cos.example.com/u8.png',
    profession: '化妆师',
    city: '上海',
    canInvite: true,
    reason: '可添加'
  })

  assert.equal(candidate.userId, 8)
  assert.equal(candidate.uniqueCode, 'WF1186')
  assert.equal(candidate.displayName, '乔伊 · 化妆师')
  assert.equal(candidate.summaryText, '上海 · 化妆师')
  assert.equal(candidate.canInvite, true)
  assert.equal(candidate.reason, '可添加')
  assert.deepEqual(buildMemberCandidateQuery(' WF1186 '), {
    uniqueCode: 'WF1186'
  })
})

test('builds member invite payload with prototype default permissions', () => {
  assert.deepEqual(validateMemberInviteForm({
    uniqueCode: 'WF1186',
    role: 'MEMBER',
    profession: '团'.repeat(51)
  }, { canInvite: true }), {
    valid: false,
    message: '团队身份不能超过 50 个字'
  })

  const payload = buildMemberInvitePayload({
    uniqueCode: ' WF1186 ',
    role: 'MEMBER',
    profession: ' 化妆师 ',
    allowPortfolio: undefined,
    allowProfile: undefined,
    allowWorks: undefined
  })

  assert.deepEqual(payload, {
    uniqueCode: 'WF1186',
    role: 'MEMBER',
    profession: '化妆师',
    allowPortfolio: true,
    allowProfile: true,
    allowWorks: false
  })
})

test('normalizes team invitation for accept and reject page', () => {
  const invitation = normalizeTeamInvitation({
    memberId: 31,
    teamId: 100,
    teamName: '星曜司仪团',
    inviterName: '林安 · 婚礼司仪',
    roleText: '普通成员',
    profession: '化妆师',
    allowPortfolio: true,
    allowProfile: true,
    allowWorks: false,
    joinStatus: 'PENDING_CONFIRMATION',
    joinStatusText: '待确认',
    statusTone: 'amber',
    canRespond: true
  })

  assert.equal(invitation.memberId, 31)
  assert.equal(invitation.titleText, '星曜司仪团')
  assert.equal(invitation.summaryText, '林安 · 婚礼司仪邀请你以普通成员加入')
  assert.equal(invitation.permissionText, '个人作品集、头像资料')
  assert.equal(invitation.canRespond, true)
})

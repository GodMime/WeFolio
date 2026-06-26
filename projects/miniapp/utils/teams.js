const TEAM_NAME_MAX_LENGTH = 100
const TEAM_INTRO_MAX_LENGTH = 1000
const TEAM_UNIQUE_CODE_DISPLAY_LENGTH = 10

const ROLE_TEXT = {
  OWNER: '拥有者',
  MANAGER: '管理者',
  MEMBER: '普通成员'
}

// 后端历史数据或测试桩可能传字符串数字，这里统一转成安全数字。
function toNumber(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

// 表单提交前统一去除首尾空格，保持小程序和后端校验口径一致。
function trimText(value) {
  return String(value || '').trim()
}

// 角色文案优先使用后端给出的 display 文案，缺失时再由小程序兜底。
function roleText(role, fallback) {
  return fallback || ROLE_TEXT[role] || '普通成员'
}

// 成员数文案后端可直接下发；本地兜底用于测试数据和旧接口兼容。
function memberCountText(value, fallback) {
  if (fallback) {
    return fallback
  }
  return `${toNumber(value)} 位成员`
}

// 维护页顶部空间有限，团队唯一码固定展示前 10 位，复制仍使用完整唯一码。
function displayTeamUniqueCode(value) {
  const uniqueCode = String(value || '')
  if (!uniqueCode) {
    return '-'
  }
  return uniqueCode.slice(0, TEAM_UNIQUE_CODE_DISPLAY_LENGTH)
}

// 列表摘要统一在工具层生成，页面只关心展示，不重复拼业务文案。
function buildSummary(raw = {}) {
  const joinedCount = toNumber(raw.joinedCount)
  const ownerCount = toNumber(raw.ownerCount)
  const manageableCount = toNumber(raw.manageableCount)
  return {
    joinedCount,
    ownerCount,
    manageableCount,
    summaryText: raw.summaryText || `已加入 ${joinedCount} 个团队，其中 ${ownerCount} 个为拥有者。`,
    manageableText: `${manageableCount} 个可维护`
  }
}

// 团队列表项归一化为卡片可直接消费的字段，减少 WXML 中的判断和拼接。
function normalizeTeamItem(raw = {}) {
  const memberCount = toNumber(raw.memberCount)
  const computedMemberCountText = memberCountText(memberCount, raw.memberCountText)
  const computedRoleText = roleText(raw.role, raw.roleText)
  const canMaintain = Boolean(raw.canMaintain)
  const uniqueCode = raw.uniqueCode || '-'
  const updatedText = raw.updatedText || '最近更新 -'
  return {
    teamId: raw.teamId || raw.id || null,
    uniqueCode,
    name: raw.name || '未命名团队',
    avatarUrl: raw.avatarUrl || '',
    intro: raw.intro || '',
    city: raw.city || '',
    role: raw.role || 'MEMBER',
    roleText: computedRoleText,
    memberCount,
    memberCountText: computedMemberCountText,
    canMaintain,
    actionText: raw.maintainText || (canMaintain ? '维护' : '查看'),
    updatedText,
    subtitle: `${uniqueCode} · ${computedMemberCountText} · ${updatedText}`,
    pills: [
      { text: computedRoleText, tone: canMaintain ? 'blue' : 'muted' },
      { text: canMaintain ? '可维护' : '仅查看', tone: canMaintain ? 'teal' : 'muted' }
    ]
  }
}

// “我的团队”页面入口数据，兼容后端空响应并补齐摘要。
function normalizeTeamList(raw = {}) {
  const teams = Array.isArray(raw.teams) ? raw.teams.map(normalizeTeamItem) : []
  return {
    summary: buildSummary(raw.summary || {
      joinedCount: teams.length,
      ownerCount: teams.filter((item) => item.role === 'OWNER').length,
      manageableCount: teams.filter((item) => item.canMaintain).length
    }),
    teams
  }
}

// 团队详情页顶部资料区的数据结构，额外补齐唯一码展示文案。
function normalizeTeamInfo(raw = {}) {
  const memberCount = toNumber(raw.memberCount)
  const computedMemberText = memberCountText(memberCount, raw.memberCountText)
  const uniqueCode = raw.uniqueCode || '-'
  return {
    teamId: raw.teamId || raw.id || null,
    uniqueCode,
    displayUniqueCode: displayTeamUniqueCode(uniqueCode),
    name: raw.name || '未命名团队',
    avatarUrl: raw.avatarUrl || '',
    intro: raw.intro || '',
    city: raw.city || '',
    role: raw.role || 'MEMBER',
    roleText: roleText(raw.role, raw.roleText),
    memberCount,
    memberText: computedMemberText,
    memberCountText: computedMemberText,
    canMaintain: Boolean(raw.canMaintain),
    codeText: `团队唯一码 ${uniqueCode}`
  }
}

// 成员列表项归一化，完整承接后端团队维护详情的状态字段。
// 后端会同时返回两套状态：
// 1. joinStatus/joinStatusText/statusTone：团队成员关系状态，始终展示为成员行右侧主标签。
//    statusTone 是后端给小程序的样式 token，直接对应 .role-pill.teal/.amber/.muted。
// 2. userStatus/userStatusText/userStatusTone：成员账号状态，仅当账号非 ACTIVE 时额外展示。
//    例如后端返回 DISABLED/已停用/muted，前端就显示第二个“已停用”标签。
// 如果后端暂未返回账号状态，前端保持兼容：不展示额外账号状态标签。
function normalizeMember(raw = {}) {
  const profession = raw.profession || '成员'
  const uniqueCode = raw.uniqueCode || '-'
  const computedRoleText = roleText(raw.role, raw.roleText)
  const userStatus = raw.userStatus || ''
  const userStatusText = raw.userStatusText || (userStatus === 'DISABLED' ? '已停用' : '')
  return {
    memberId: raw.memberId || raw.id || null,
    userId: raw.userId || null,
    uniqueCode,
    nickname: raw.nickname || '',
    initial: computedRoleText.slice(0, 1),
    displayName: raw.displayName || raw.nickname || '微信用户',
    avatarUrl: raw.avatarUrl || '',
    profession,
    role: raw.role || 'MEMBER',
    roleText: computedRoleText,
    joinStatus: raw.joinStatus || '',
    joinStatusText: raw.joinStatusText || '待确认',
    // 团队加入状态标签的色调由后端决定，前端只做兜底，避免重复维护业务状态到颜色的映射。
    statusTone: raw.statusTone || 'muted',
    userStatus,
    userStatusText,
    userStatusTone: raw.userStatusTone || 'muted',
    // ACTIVE 是正常账号状态，不需要额外标签；DISABLED/资料缺失/未知状态才额外展示。
    userStatusVisible: Boolean(userStatus && userStatus !== 'ACTIVE'),
    summaryText: `${profession} · ${uniqueCode} · ${computedRoleText}`
  }
}

// 团队维护页详情数据，拆成 team 和 members 两个区域方便页面独立渲染。
function normalizeTeamDetail(raw = {}) {
  return {
    team: normalizeTeamInfo(raw.team || {}),
    members: Array.isArray(raw.members) ? raw.members.map(normalizeMember) : []
  }
}

// 创建页和维护页共用一套表单校验，确保两个入口的限制一致。
function validateTeamForm(form = {}) {
  const name = trimText(form.name)
  const intro = trimText(form.intro)
  if (!name) {
    return { valid: false, message: '团队名称不能为空' }
  }
  if (name.length > TEAM_NAME_MAX_LENGTH) {
    return { valid: false, message: `团队名称不能超过 ${TEAM_NAME_MAX_LENGTH} 个字` }
  }
  if (intro.length > TEAM_INTRO_MAX_LENGTH) {
    return { valid: false, message: `团队简介不能超过 ${TEAM_INTRO_MAX_LENGTH} 个字` }
  }
  return { valid: true, message: '' }
}

// 提交给后端前只保留接口需要的字段，避免页面态字段混入请求体。
function buildTeamPayload(form = {}) {
  return {
    name: trimText(form.name),
    intro: trimText(form.intro),
    avatarUrl: trimText(form.avatarUrl)
  }
}

// 字数统计与校验上限共用常量，避免 WXML 文案和 JS 校验不一致。
function buildTeamFieldCounters(form = {}) {
  return {
    name: `${trimText(form.name).length} / ${TEAM_NAME_MAX_LENGTH}`,
    intro: `${trimText(form.intro).length} / ${TEAM_INTRO_MAX_LENGTH}`
  }
}

module.exports = {
  TEAM_INTRO_MAX_LENGTH,
  TEAM_NAME_MAX_LENGTH,
  buildTeamFieldCounters,
  buildTeamPayload,
  normalizeTeamDetail,
  normalizeTeamList,
  validateTeamForm
}

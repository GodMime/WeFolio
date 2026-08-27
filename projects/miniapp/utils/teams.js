const TEAM_NAME_MAX_LENGTH = 100
const TEAM_INTRO_MAX_LENGTH = 1000
const TEAM_UNIQUE_CODE_DISPLAY_LENGTH = 10
const MEMBER_PROFESSION_MAX_LENGTH = 50

const ROLE_TEXT = {
  OWNER: '拥有者',
  MANAGER: '管理者',
  MEMBER: '普通成员'
}

const ROLE_SUMMARY = {
  MANAGER: '作品集管理、预览、分享',
  MEMBER: '仅预览、分享'
}

const MEMBER_CHANGE_STATUS_TEXT = {
  PENDING_CONFIRMATION: '待同意',
  ACCEPTED: '已同意',
  REJECTED: '已拒绝',
  INVALIDATED: '已失效'
}

const DEFAULT_MEMBER_INVITE_FORM = {
  uniqueCode: '',
  role: 'MEMBER',
  profession: '',
  allowPortfolio: true,
  allowProfile: true,
  allowWorks: false
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

// 团队创建键在失败重试期间保持不变，格式与后端 64 字符可打印 ASCII 约束一致。
function createTeamIdempotencyKey(options = {}) {
  const nowMs = options.nowMs === undefined ? Date.now() : Number(options.nowMs)
  const randomFn = typeof options.randomFn === 'function' ? options.randomFn : Math.random
  const randomValue = Math.floor(Math.max(0, Math.min(0.999999999, Number(randomFn()) || 0)) * 2176782336)
  return `team-create-${Math.max(0, nowMs).toString(36)}-${randomValue.toString(36)}`.slice(0, 64)
}

function toBoolean(value, fallback = false) {
  if (value === undefined || value === null) {
    return fallback
  }
  return Boolean(value)
}

function statusTone(status, fallback = 'muted') {
  if (status === 'PENDING_CONFIRMATION') {
    return 'amber'
  }
  if (status === 'ACCEPTED' || status === 'JOINED') {
    return 'teal'
  }
  if (status === 'REJECTED' || status === 'INVALIDATED' || status === 'REMOVED') {
    return 'muted'
  }
  return fallback
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
    canManageMembers: Boolean(raw.canManageMembers),
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
    allowPortfolio: toBoolean(raw.allowPortfolio),
    allowProfile: toBoolean(raw.allowProfile),
    allowWorks: toBoolean(raw.allowWorks),
    pendingChange: Boolean(raw.pendingChange),
    pendingChangeId: raw.pendingChangeId || null,
    pendingChangeText: raw.pendingChangeText || (raw.pendingChange ? '信息变更待同意' : ''),
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

// 候选人查询只需要个人唯一码，页面输入可包含空格，提交前统一修剪。
function buildMemberCandidateQuery(uniqueCode) {
  return {
    uniqueCode: trimText(uniqueCode)
  }
}

// 添加成员页候选人卡片归一化，既承接后端 canInvite，也兜底生成摘要。
function normalizeTeamMemberCandidate(raw = {}) {
  const profession = raw.profession || '成员'
  const city = raw.city || ''
  const nickname = raw.nickname || '微信用户'
  const displayName = raw.displayName || (profession ? `${nickname} · ${profession}` : nickname)
  return {
    userId: raw.userId || null,
    uniqueCode: raw.uniqueCode || '',
    nickname,
    initial: displayName.slice(0, 1),
    displayName,
    avatarUrl: raw.avatarUrl || '',
    profession,
    city,
    userStatus: raw.userStatus || '',
    memberId: raw.memberId || null,
    existingJoinStatus: raw.existingJoinStatus || '',
    existingJoinStatusText: raw.existingJoinStatusText || '',
    canInvite: Boolean(raw.canInvite),
    reason: raw.reason || (raw.canInvite ? '可添加' : '不可添加'),
    statusTone: raw.canInvite ? 'teal' : 'muted',
    summaryText: `${city || '未填写城市'} · ${profession}`
  }
}

function normalizeMemberInviteForm(form = {}) {
  return Object.assign({}, DEFAULT_MEMBER_INVITE_FORM, form, {
    uniqueCode: trimText(form.uniqueCode),
    profession: trimText(form.profession)
  })
}

function validateMemberInviteForm(form = {}, candidate = {}) {
  const normalized = normalizeMemberInviteForm(form)
  if (!normalized.uniqueCode) {
    return { valid: false, message: '请输入个人唯一码' }
  }
  if (!candidate || !candidate.canInvite) {
    return { valid: false, message: candidate && candidate.reason ? candidate.reason : '请先匹配可添加成员' }
  }
  if (!ROLE_TEXT[normalized.role] || normalized.role === 'OWNER') {
    return { valid: false, message: '请选择团队角色' }
  }
  if (normalized.profession.length > MEMBER_PROFESSION_MAX_LENGTH) {
    return { valid: false, message: `团队身份不能超过 ${MEMBER_PROFESSION_MAX_LENGTH} 个字` }
  }
  return { valid: true, message: '' }
}

// 添加成员提交载荷：权限默认与原型一致，个人作品集和头像资料开启，个人作品素材关闭。
function buildMemberInvitePayload(form = {}) {
  const normalized = normalizeMemberInviteForm(form)
  return {
    uniqueCode: normalized.uniqueCode,
    role: normalized.role === 'MANAGER' ? 'MANAGER' : 'MEMBER',
    profession: normalized.profession,
    allowPortfolio: toBoolean(normalized.allowPortfolio, true),
    allowProfile: toBoolean(normalized.allowProfile, true),
    allowWorks: toBoolean(normalized.allowWorks, false)
  }
}

function normalizeMemberChangeForm(form = {}) {
  return {
    teamId: toNumber(form.teamId),
    memberId: toNumber(form.memberId),
    role: form.role === 'MANAGER' ? 'MANAGER' : 'MEMBER',
    profession: trimText(form.profession),
    allowPortfolio: toBoolean(form.allowPortfolio),
    allowProfile: toBoolean(form.allowProfile),
    allowWorks: toBoolean(form.allowWorks)
  }
}

function buildMemberChangePayload(form = {}) {
  return normalizeMemberChangeForm(form)
}

function validateMemberChangeForm(form = {}, member = {}) {
  const rawRole = form.role || ''
  if (rawRole !== 'MANAGER' && rawRole !== 'MEMBER') {
    return { valid: false, message: '请选择团队角色' }
  }
  const normalized = normalizeMemberChangeForm(form)
  if (!normalized.teamId || !normalized.memberId) {
    return { valid: false, message: '成员信息不完整' }
  }
  if (normalized.profession.length > MEMBER_PROFESSION_MAX_LENGTH) {
    return { valid: false, message: `团队身份不能超过 ${MEMBER_PROFESSION_MAX_LENGTH} 个字` }
  }
  const unchanged = normalized.role === (member.role || 'MEMBER')
    && normalized.profession === trimText(member.profession)
    && normalized.allowPortfolio === toBoolean(member.allowPortfolio)
    && normalized.allowProfile === toBoolean(member.allowProfile)
    && normalized.allowWorks === toBoolean(member.allowWorks)
  if (unchanged) {
    return { valid: false, message: '成员信息没有变化' }
  }
  return { valid: true, message: '' }
}

function normalizeTeamMemberChangeDetail(raw = {}) {
  const status = raw.status || 'PENDING_CONFIRMATION'
  const roleBeforeText = raw.roleBeforeText || roleText(raw.roleBefore)
  const roleAfterText = raw.roleAfterText || roleText(raw.roleAfter)
  const permissionBeforeText = raw.permissionBeforeText || '未开放'
  const permissionAfterText = raw.permissionAfterText || '未开放'
  const detail = {
    changeRequestId: raw.changeRequestId || raw.id || null,
    teamId: raw.teamId || null,
    teamName: raw.teamName || '团队信息变更',
    teamAvatarUrl: raw.teamAvatarUrl || '',
    requesterName: raw.requesterName || '团队拥有者',
    targetName: raw.targetName || '成员',
    status,
    statusText: raw.statusText || MEMBER_CHANGE_STATUS_TEXT[status] || status,
    statusTone: raw.statusTone || statusTone(status, 'amber'),
    canRespond: Boolean(raw.canRespond),
    roleBeforeText,
    roleAfterText,
    professionBefore: raw.professionBefore || '未填写',
    professionAfter: raw.professionAfter || '未填写',
    permissionBeforeText,
    permissionAfterText,
    requestedAtText: raw.requestedAtText || '',
    respondedAtText: raw.respondedAtText || ''
  }
  detail.titleText = detail.teamName
  detail.summaryText = `${detail.requesterName}发起成员信息变更`
  detail.changeRows = [
    { label: '团队角色', before: detail.roleBeforeText, after: detail.roleAfterText },
    { label: '团队身份', before: detail.professionBefore, after: detail.professionAfter },
    { label: '引用权限', before: detail.permissionBeforeText, after: detail.permissionAfterText }
  ]
  return detail
}

function permissionText(invitation = {}) {
  const permissions = []
  if (invitation.allowPortfolio) {
    permissions.push('个人作品集')
  }
  if (invitation.allowProfile) {
    permissions.push('头像资料')
  }
  if (invitation.allowWorks) {
    permissions.push('个人作品素材')
  }
  return permissions.length ? permissions.join('、') : '未开放引用权限'
}

// 邀请处理页归一化，页面只消费文案和按钮状态。
function normalizeTeamInvitation(raw = {}) {
  const role = raw.role || 'MEMBER'
  const roleDisplay = roleText(role, raw.roleText)
  const allowPortfolio = toBoolean(raw.allowPortfolio)
  const allowProfile = toBoolean(raw.allowProfile)
  const allowWorks = toBoolean(raw.allowWorks)
  const invitation = {
    memberId: raw.memberId || raw.id || null,
    teamId: raw.teamId || null,
    teamUniqueCode: raw.teamUniqueCode || '',
    teamName: raw.teamName || '团队邀请',
    teamAvatarUrl: raw.teamAvatarUrl || '',
    inviterUserId: raw.inviterUserId || null,
    inviterName: raw.inviterName || '团队拥有者',
    role,
    roleText: roleDisplay,
    roleSummary: ROLE_SUMMARY[role] || ROLE_SUMMARY.MEMBER,
    profession: raw.profession || '成员',
    allowPortfolio,
    allowProfile,
    allowWorks,
    joinStatus: raw.joinStatus || 'PENDING_CONFIRMATION',
    joinStatusText: raw.joinStatusText || '待确认',
    statusTone: raw.statusTone || 'amber',
    canRespond: Boolean(raw.canRespond)
  }
  invitation.titleText = invitation.teamName
  invitation.summaryText = `${invitation.inviterName}邀请你以${roleDisplay}加入`
  invitation.permissionText = permissionText(invitation)
  return invitation
}

module.exports = {
  DEFAULT_MEMBER_INVITE_FORM,
  buildMemberCandidateQuery,
  buildMemberChangePayload,
  buildMemberInvitePayload,
  TEAM_INTRO_MAX_LENGTH,
  TEAM_NAME_MAX_LENGTH,
  buildTeamFieldCounters,
  buildTeamPayload,
  createTeamIdempotencyKey,
  normalizeTeamMemberChangeDetail,
  normalizeTeamInvitation,
  normalizeTeamMemberCandidate,
  normalizeTeamDetail,
  normalizeTeamList,
  validateMemberChangeForm,
  validateMemberInviteForm,
  validateTeamForm
}

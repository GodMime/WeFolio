const MESSAGE_READ_STATUS = {
  UNREAD: 'UNREAD',
  READ: 'READ'
}

const MESSAGE_CATEGORY = {
  POINT: 'POINT',
  TEAM: 'TEAM',
  SYSTEM: 'SYSTEM'
}

const MESSAGE_TYPE = {
  TEAM_INVITATION: 'TEAM_INVITATION'
}

const MESSAGE_ACTION_TYPE = {
  NONE: 'NONE',
  TEAM_INVITATION: 'TEAM_INVITATION',
  POINT_RECHARGE: 'POINT_RECHARGE',
  PAGE_NAVIGATION: 'PAGE_NAVIGATION'
}

const FILTER_QUERY = {
  unread: { status: MESSAGE_READ_STATUS.UNREAD },
  team: { category: MESSAGE_CATEGORY.TEAM },
  point: { category: MESSAGE_CATEGORY.POINT }
}

const CATEGORY_TEXT = {
  POINT: '积分',
  TEAM: '团队',
  SYSTEM: '系统'
}

const CATEGORY_TONE = {
  POINT: 'point',
  TEAM: 'team',
  SYSTEM: 'system'
}

function toNumber(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function normalizeId(value) {
  const id = Number(value)
  return Number.isFinite(id) && id > 0 ? id : null
}

function normalizeUnreadCount(raw = {}) {
  const unreadCount = toNumber(raw.unreadCount)
  const teamUnreadCount = toNumber(raw.teamUnreadCount)
  const pointUnreadCount = toNumber(raw.pointUnreadCount)
  return {
    unreadCount,
    teamUnreadCount,
    pointUnreadCount,
    hasUnread: unreadCount > 0,
    badgeText: unreadCount > 99 ? '99+' : (unreadCount > 0 ? String(unreadCount) : '')
  }
}

function actionText(actionType) {
  if (actionType === MESSAGE_ACTION_TYPE.TEAM_INVITATION) {
    return '去处理'
  }
  if (actionType === MESSAGE_ACTION_TYPE.POINT_RECHARGE || actionType === MESSAGE_ACTION_TYPE.PAGE_NAVIGATION) {
    return '去查看'
  }
  return '查看'
}

function normalizeMessageItem(raw = {}) {
  const id = normalizeId(raw.messageId || raw.id)
  const category = raw.category || MESSAGE_CATEGORY.SYSTEM
  const readStatus = raw.readStatus || MESSAGE_READ_STATUS.UNREAD
  const actionType = raw.actionType || MESSAGE_ACTION_TYPE.NONE
  const unread = readStatus === MESSAGE_READ_STATUS.UNREAD
  return {
    id,
    messageId: id,
    messageType: raw.messageType || '',
    category,
    categoryText: raw.categoryText || CATEGORY_TEXT[category] || '系统',
    tone: CATEGORY_TONE[category] || 'system',
    readStatus,
    unread,
    unreadDotClass: unread ? 'unread-dot amber' : 'unread-dot muted',
    title: raw.title || '系统消息',
    content: raw.content || '',
    actionType,
    actionUrl: raw.actionUrl || '',
    actionVisible: actionType !== MESSAGE_ACTION_TYPE.NONE && Boolean(raw.actionUrl),
    actionText: raw.actionText || actionText(actionType),
    createdAtText: raw.createdAtText || '',
    selected: false
  }
}

function normalizeMessageList(raw = {}) {
  const messages = Array.isArray(raw.messages) ? raw.messages.map(normalizeMessageItem) : []
  const unreadCount = toNumber(raw.summary && raw.summary.unreadCount)
  const invitationCount = messages.filter((item) => (
    item.unread && item.messageType === MESSAGE_TYPE.TEAM_INVITATION
  )).length
  return {
    summary: {
      unreadCount,
      unreadText: unreadCount > 0 ? `${unreadCount} 条未读` : '暂无未读',
      invitationText: invitationCount > 0 ? `含 ${invitationCount} 条邀请` : '暂无待处理邀请'
    },
    messages,
    hasMore: Boolean(raw.hasMore),
    nextCursor: raw.nextCursor || null
  }
}

function buildMessageQuery(options = {}) {
  const filter = options.filter || 'all'
  const query = Object.assign({}, FILTER_QUERY[filter] || {})
  const cursor = normalizeId(options.cursor)
  if (cursor) {
    query.cursor = cursor
  }
  if (Number.isFinite(Number(options.size)) && Number(options.size) > 0) {
    query.size = Number(options.size)
  }
  return query
}

function buildMarkReadPayload(messageIds = []) {
  const normalized = []
  messageIds.forEach((messageId) => {
    const id = normalizeId(messageId)
    if (id && !normalized.includes(id)) {
      normalized.push(id)
    }
  })
  return {
    messageIds: normalized
  }
}

function buildReadAllPayload(filter) {
  const query = FILTER_QUERY[filter] || {}
  return query.category ? { category: query.category } : {}
}

module.exports = {
  buildMarkReadPayload,
  buildMessageQuery,
  buildReadAllPayload,
  normalizeMessageList,
  normalizeUnreadCount
}

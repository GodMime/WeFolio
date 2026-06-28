const { normalizeId } = require('./id')

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
  TEAM_MEMBER_CHANGE: 'TEAM_MEMBER_CHANGE',
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
  if (actionType === MESSAGE_ACTION_TYPE.TEAM_INVITATION || actionType === MESSAGE_ACTION_TYPE.TEAM_MEMBER_CHANGE) {
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
    selected: Boolean(raw.selected)
  }
}

function buildMessageSummary(unreadCount, messages = []) {
  const normalizedUnreadCount = toNumber(unreadCount)
  const invitationCount = messages.filter((item) => (
    item.unread && item.messageType === MESSAGE_TYPE.TEAM_INVITATION
  )).length
  return {
    unreadCount: normalizedUnreadCount,
    unreadText: normalizedUnreadCount > 0 ? `${normalizedUnreadCount} 条未读` : '暂无未读',
    invitationText: invitationCount > 0 ? `含 ${invitationCount} 条邀请` : '暂无待处理邀请'
  }
}

function normalizeMessageList(raw = {}) {
  const messages = Array.isArray(raw.messages) ? raw.messages.map(normalizeMessageItem) : []
  const unreadCount = toNumber(raw.summary && raw.summary.unreadCount)
  return {
    summary: buildMessageSummary(unreadCount, messages),
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

function appendMessageList(current = {}, next = {}) {
  const currentData = normalizeMessageList(current)
  const nextData = normalizeMessageList(next)
  const seen = {}
  const messages = []
  currentData.messages.concat(nextData.messages).forEach((item) => {
    if (item.id) {
      if (seen[item.id]) {
        return
      }
      seen[item.id] = true
    }
    messages.push(item)
  })
  return Object.assign({}, nextData, { messages })
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

function readCountFromResponse(response = {}, fallback) {
  if (Object.prototype.hasOwnProperty.call(response, 'unreadCount')) {
    return toNumber(response.unreadCount)
  }
  return toNumber(fallback)
}

function markMessageItemRead(item) {
  return normalizeMessageItem(Object.assign({}, item, {
    readStatus: MESSAGE_READ_STATUS.READ,
    selected: false
  }))
}

function shouldMarkAllItemRead(item, filter) {
  if (!item.unread) {
    return false
  }
  if (filter === 'team') {
    return item.category === MESSAGE_CATEGORY.TEAM
  }
  if (filter === 'point') {
    return item.category === MESSAGE_CATEGORY.POINT
  }
  return true
}

function applyMessagesRead(messageData = {}, messageIds = [], unreadCountResponse = {}, options = {}) {
  const current = normalizeMessageList(messageData)
  const idSet = new Set(buildMarkReadPayload(messageIds).messageIds)
  const removeFromUnreadFilter = options.filter === 'unread'
  const messages = current.messages
    .map((item) => (idSet.has(item.id) ? markMessageItemRead(item) : normalizeMessageItem(item)))
    .filter((item) => !(removeFromUnreadFilter && idSet.has(item.id)))
  return Object.assign({}, current, {
    summary: buildMessageSummary(readCountFromResponse(unreadCountResponse, current.summary.unreadCount), messages),
    messages
  })
}

function applyMessagesReadAll(messageData = {}, unreadCountResponse = {}, options = {}) {
  const current = normalizeMessageList(messageData)
  const filter = options.filter || 'all'
  const removeFromUnreadFilter = filter === 'unread'
  const messages = []
  current.messages.forEach((item) => {
    if (!shouldMarkAllItemRead(item, filter)) {
      messages.push(normalizeMessageItem(item))
      return
    }
    if (!removeFromUnreadFilter) {
      messages.push(markMessageItemRead(item))
    }
  })
  return Object.assign({}, current, {
    summary: buildMessageSummary(readCountFromResponse(unreadCountResponse, current.summary.unreadCount), messages),
    messages
  })
}

module.exports = {
  appendMessageList,
  applyMessagesRead,
  applyMessagesReadAll,
  buildMarkReadPayload,
  buildMessageQuery,
  buildReadAllPayload,
  normalizeMessageList,
  normalizeUnreadCount
}

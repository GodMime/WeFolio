const assert = require('node:assert/strict')
const test = require('node:test')

const {
  buildMarkReadPayload,
  buildMessageQuery,
  buildReadAllPayload,
  normalizeMessageList,
  normalizeUnreadCount
} = require('../utils/messages')

test('normalizes message list response for page rendering', () => {
  const result = normalizeMessageList({
    summary: {
      unreadCount: 3
    },
    hasMore: true,
    nextCursor: 88,
    messages: [
      {
        messageId: 101,
        messageType: 'TEAM_INVITATION',
        category: 'TEAM',
        readStatus: 'UNREAD',
        title: '团队邀请',
        content: '星曜司仪团邀请你加入团队',
        actionType: 'TEAM_INVITATION',
        actionUrl: '/pages/team-invitations/team-invitations?id=88',
        createdAtText: '06-25 10:30'
      },
      {
        messageId: 100,
        messageType: 'POINT_LOW_BALANCE',
        category: 'POINT',
        readStatus: 'READ',
        title: '积分不足',
        content: '当前积分余额偏低',
        actionType: 'POINT_RECHARGE',
        actionUrl: '/pages/points/points',
        createdAtText: '06-24 09:05'
      }
    ]
  })

  assert.equal(result.summary.unreadCount, 3)
  assert.equal(result.summary.unreadText, '3 条未读')
  assert.equal(result.summary.invitationText, '含 1 条邀请')
  assert.equal(result.hasMore, true)
  assert.equal(result.nextCursor, 88)
  assert.equal(result.messages.length, 2)
  assert.equal(result.messages[0].id, 101)
  assert.equal(result.messages[0].categoryText, '团队')
  assert.equal(result.messages[0].tone, 'team')
  assert.equal(result.messages[0].unread, true)
  assert.equal(result.messages[0].unreadDotClass, 'unread-dot amber')
  assert.equal(result.messages[0].actionVisible, true)
  assert.equal(result.messages[0].actionText, '去处理')
  assert.equal(result.messages[0].selected, false)
  assert.equal(result.messages[1].categoryText, '积分')
  assert.equal(result.messages[1].tone, 'point')
  assert.equal(result.messages[1].unreadDotClass, 'unread-dot muted')
  assert.equal(result.messages[1].actionText, '去查看')
})

test('builds message query parameters from page filters', () => {
  assert.deepEqual(buildMessageQuery({ filter: 'all', cursor: 88, size: 20 }), {
    cursor: 88,
    size: 20
  })
  assert.deepEqual(buildMessageQuery({ filter: 'unread' }), {
    status: 'UNREAD'
  })
  assert.deepEqual(buildMessageQuery({ filter: 'team' }), {
    category: 'TEAM'
  })
  assert.deepEqual(buildMessageQuery({ filter: 'point' }), {
    category: 'POINT'
  })
})

test('builds mark read and read all payloads', () => {
  assert.deepEqual(buildMarkReadPayload([101, 102, 101, -1, null]), {
    messageIds: [101, 102]
  })
  assert.deepEqual(buildReadAllPayload('team'), {
    category: 'TEAM'
  })
  assert.deepEqual(buildReadAllPayload('point'), {
    category: 'POINT'
  })
  assert.deepEqual(buildReadAllPayload('all'), {})
  assert.deepEqual(buildReadAllPayload('unread'), {})
})

test('normalizes unread count for mine entry badge', () => {
  assert.deepEqual(normalizeUnreadCount({
    unreadCount: 7,
    teamUnreadCount: 2,
    pointUnreadCount: 3
  }), {
    unreadCount: 7,
    teamUnreadCount: 2,
    pointUnreadCount: 3,
    hasUnread: true,
    badgeText: '7'
  })
  assert.equal(normalizeUnreadCount({ unreadCount: 120 }).badgeText, '99+')
  assert.equal(normalizeUnreadCount({}).hasUnread, false)
})

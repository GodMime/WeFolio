const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const { normalizeMessageList } = require('../utils/messages')

function deferred() {
  let resolve
  let reject
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

function flushPromises() {
  return new Promise((resolve) => {
    setImmediate(resolve)
  })
}

function applyData(target, patch) {
  Object.keys(patch).forEach((key) => {
    if (!key.includes('.')) {
      target[key] = patch[key]
      return
    }
    const parts = key.split('.')
    let current = target
    parts.slice(0, -1).forEach((part) => {
      if (!current[part]) {
        current[part] = {}
      }
      current = current[part]
    })
    current[parts[parts.length - 1]] = patch[key]
  })
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function loadMessagesPage(fakeRequest) {
  const pagePath = path.join(__dirname, '../pages/messages/messages.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const sessionPath = path.join(__dirname, '../utils/session.js')
  delete require.cache[require.resolve(pagePath)]
  require.cache[require.resolve(requestPath)] = {
    id: requestPath,
    filename: requestPath,
    loaded: true,
    exports: {
      request: fakeRequest
    }
  }
  require.cache[require.resolve(sessionPath)] = {
    id: sessionPath,
    filename: sessionPath,
    loaded: true,
    exports: {
      handleAuthRequired() {},
      hasLocalToken() {
        return true
      }
    }
  }

  let pageDefinition
  global.Page = (definition) => {
    pageDefinition = definition
  }
  global.wx = {
    redirectTo() {},
    showToast() {}
  }
  require(pagePath)
  delete global.Page

  return Object.assign({}, pageDefinition, {
    data: clone(pageDefinition.data),
    setData(patch, callback) {
      applyData(this.data, patch)
      if (callback) {
        callback()
      }
    }
  })
}

test('ignores stale load-more response after switching message filter', async () => {
  const requests = []
  const fakeRequest = (options) => {
    const pending = deferred()
    requests.push(Object.assign({ pending }, options))
    return pending.promise
  }
  const page = loadMessagesPage(fakeRequest)
  page.data.loading = false
  page.data.activeFilter = 'all'
  page.data.messageData = normalizeMessageList({
    hasMore: true,
    nextCursor: 100,
    messages: [
      { messageId: 101, title: '全部第 1 页', readStatus: 'READ' }
    ]
  })

  const loadMorePromise = page.loadMessages({ append: true })
  assert.deepEqual(requests[0].data, { cursor: 100, size: 20 })

  page.handleFilterTap({
    currentTarget: {
      dataset: {
        filter: 'unread'
      }
    }
  })
  assert.deepEqual(requests[1].data, { status: 'UNREAD', size: 20 })

  requests[1].pending.resolve({
    hasMore: false,
    nextCursor: null,
    summary: { unreadCount: 1 },
    messages: [
      { messageId: 201, title: '未读第 1 页', readStatus: 'UNREAD' }
    ]
  })
  await flushPromises()

  requests[0].pending.resolve({
    hasMore: false,
    nextCursor: null,
    summary: { unreadCount: 2 },
    messages: [
      { messageId: 99, title: '全部第 2 页', readStatus: 'READ' }
    ]
  })
  await loadMorePromise
  await flushPromises()

  assert.equal(page.data.activeFilter, 'unread')
  assert.deepEqual(page.data.messageData.messages.map((item) => item.id), [201])
  assert.equal(page.data.loading, false)
  assert.equal(page.data.loadingMore, false)
})

test('refreshes message list after returning from action page', async () => {
  const requests = []
  const fakeRequest = (options) => {
    const pending = deferred()
    requests.push(Object.assign({ pending }, options))
    return pending.promise
  }
  const page = loadMessagesPage(fakeRequest)
  page.data.loading = false
  page.data.activeFilter = 'team'
  page.data.messageData = normalizeMessageList({
    hasMore: false,
    nextCursor: null,
    summary: { unreadCount: 1 },
    messages: [
      {
        messageId: 31,
        title: '团队邀请',
        category: 'TEAM',
        readStatus: 'UNREAD',
        actionUrl: '/pages/team-invitations/team-invitations?memberId=31'
      }
    ]
  })
  let navigatedUrl = ''
  global.wx.navigateTo = ({ url, success }) => {
    navigatedUrl = url
    success()
  }

  page.handleActionTap({
    currentTarget: {
      dataset: {
        actionUrl: '/pages/team-invitations/team-invitations?memberId=31'
      }
    }
  })
  page.onShow()

  assert.equal(navigatedUrl, '/pages/team-invitations/team-invitations?memberId=31')
  assert.deepEqual(requests[0].data, { category: 'TEAM', size: 20 })

  requests[0].pending.resolve({
    hasMore: false,
    nextCursor: null,
    summary: { unreadCount: 0 },
    messages: [
      {
        messageId: 31,
        title: '团队邀请',
        category: 'TEAM',
        readStatus: 'READ',
        actionUrl: '/pages/team-invitations/team-invitations?memberId=31'
      }
    ]
  })
  await flushPromises()

  assert.equal(page.data.messageData.summary.unreadCount, 0)
  assert.equal(page.data.messageData.messages[0].unread, false)
})

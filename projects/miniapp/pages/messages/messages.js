const { request } = require('../../utils/request')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  appendMessageList,
  applyMessagesRead,
  applyMessagesReadAll,
  buildMarkReadPayload,
  buildMessageQuery,
  buildReadAllPayload,
  normalizeMessageList
} = require('../../utils/messages')

const MESSAGE_LIST_URL = '/api/mine/messages'
const MESSAGE_MARK_READ_URL = '/api/mine/messages/read'
const MESSAGE_MARK_ALL_READ_URL = '/api/mine/messages/read-all'
const LOGIN_PAGE_URL = '/pages/login/login'
const PAGE_SIZE = 20

function emptyMessageData() {
  return normalizeMessageList({})
}

Page({
  data: {
    loading: true,
    loadingMore: false,
    saving: false,
    errorMessage: '',
    activeFilter: 'all',
    selectedIds: [],
    messageData: emptyMessageData(),
    filters: [
      { key: 'all', label: '全部' },
      { key: 'unread', label: '未读' },
      { key: 'team', label: '团队' },
      { key: 'point', label: '积分' }
    ]
  },

  onLoad() {
    this.bootstrap()
  },

  onShow() {
    if (!this.shouldRefreshOnShow) {
      return
    }
    this.shouldRefreshOnShow = false
    this.loadMessages()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadMessages()
  },

  async loadMessages(options = {}) {
    const append = Boolean(options.append)
    const requestContext = this.createMessageRequestContext(append)
    this.setData(append ? {
      loadingMore: true
    } : {
      loading: true,
      loadingMore: false,
      errorMessage: ''
    })

    try {
      const response = await request({
        url: MESSAGE_LIST_URL,
        data: buildMessageQuery({
          filter: requestContext.filter,
          cursor: requestContext.cursor,
          size: PAGE_SIZE
        })
      })
      if (!this.isCurrentMessageRequest(requestContext)) {
        return
      }
      const normalized = normalizeMessageList(response)
      this.setData({
        messageData: append ? appendMessageList(this.data.messageData, normalized) : normalized,
        selectedIds: append ? this.data.selectedIds : [],
        loading: false,
        loadingMore: false
      })
    } catch (error) {
      if (!this.isCurrentMessageRequest(requestContext)) {
        return
      }
      if (error && error.authRequired) {
        this.setData({
          loading: false,
          loadingMore: false
        })
        handleMaintainerAuthRequired(error.message)
        return
      }
      if (append) {
        this.setData({
          loadingMore: false
        })
        wx.showToast({
          title: error && error.message ? error.message : '更多消息加载失败',
          icon: 'none'
        })
        return
      }
      this.setData({
        loading: false,
        loadingMore: false,
        errorMessage: error && error.message ? error.message : '消息加载失败'
      })
    }
  },

  createMessageRequestContext(append) {
    const messageData = this.data.messageData || {}
    const requestId = (this.messageListRequestId || 0) + 1
    this.messageListRequestId = requestId
    return {
      requestId,
      append,
      filter: this.data.activeFilter,
      cursor: append ? messageData.nextCursor : null
    }
  },

  isCurrentMessageRequest(requestContext) {
    return Boolean(requestContext)
      && this.messageListRequestId === requestContext.requestId
      && this.data.activeFilter === requestContext.filter
  },

  redirectToLogin() {
    wx.redirectTo({
      url: LOGIN_PAGE_URL
    })
  },

  handleRetry() {
    this.bootstrap()
  },

  handleLoadMore() {
    const messageData = this.data.messageData || {}
    if (
      this.data.loading ||
      this.data.loadingMore ||
      this.data.saving ||
      !messageData.hasMore ||
      !messageData.nextCursor
    ) {
      return
    }
    this.loadMessages({ append: true })
  },

  handleFilterTap(event) {
    const filter = event.currentTarget.dataset.filter
    if (!filter || filter === this.data.activeFilter) {
      return
    }
    this.setData({
      activeFilter: filter,
      selectedIds: []
    }, () => {
      this.loadMessages()
    })
  },

  handleToggleSelect(event) {
    const messageId = Number(event.currentTarget.dataset.messageId)
    if (!messageId) {
      return
    }
    const target = this.data.messageData.messages.find((item) => item.id === messageId)
    if (!target || !target.unread) {
      return
    }
    const selectedIds = this.data.selectedIds.includes(messageId)
      ? this.data.selectedIds.filter((item) => item !== messageId)
      : this.data.selectedIds.concat(messageId)
    this.setData({
      selectedIds,
      'messageData.messages': this.data.messageData.messages.map((item) => Object.assign({}, item, {
        selected: selectedIds.includes(item.id)
      }))
    })
  },

  async handleMarkOneRead(event) {
    const messageId = Number(event.currentTarget.dataset.messageId)
    if (!messageId) {
      return
    }
    await this.markMessagesRead([messageId], '已标记已读')
  },

  async handleMarkSelectedRead() {
    if (!this.data.selectedIds.length) {
      wx.showToast({
        title: '请选择未读消息',
        icon: 'none'
      })
      return
    }
    await this.markMessagesRead(this.data.selectedIds, '已批量标记')
  },

  async markMessagesRead(messageIds, successTitle) {
    if (this.data.saving) {
      return
    }
    this.setData({
      saving: true
    })
    try {
      const unreadCountResponse = await request({
        url: MESSAGE_MARK_READ_URL,
        method: 'PUT',
        data: buildMarkReadPayload(messageIds)
      })
      wx.showToast({
        title: successTitle,
        icon: 'success'
      })
      // 本地更新已读状态，保留滚动位置和分页游标
      this.setData({
        saving: false,
        selectedIds: [],
        messageData: applyMessagesRead(this.data.messageData, messageIds, unreadCountResponse, {
          filter: this.data.activeFilter
        })
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        saving: false
      })
      wx.showToast({
        title: error && error.message ? error.message : '标记失败',
        icon: 'none'
      })
    }
  },

  async handleMarkAllRead() {
    if (this.data.saving) {
      return
    }
    this.setData({
      saving: true
    })
    try {
      const unreadCountResponse = await request({
        url: MESSAGE_MARK_ALL_READ_URL,
        method: 'PUT',
        data: buildReadAllPayload(this.data.activeFilter)
      })
      wx.showToast({
        title: '已全部标记',
        icon: 'success'
      })
      // 本地更新全部消息为已读，保留滚动位置和分页游标
      this.setData({
        saving: false,
        selectedIds: [],
        messageData: applyMessagesReadAll(this.data.messageData, unreadCountResponse, {
          filter: this.data.activeFilter
        })
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        saving: false
      })
      wx.showToast({
        title: error && error.message ? error.message : '标记失败',
        icon: 'none'
      })
    }
  },

  handleActionTap(event) {
    const actionUrl = event.currentTarget.dataset.actionUrl
    if (!actionUrl) {
      return
    }
    wx.navigateTo({
      url: actionUrl,
      success: () => {
        this.shouldRefreshOnShow = true
      }
    })
  }
})

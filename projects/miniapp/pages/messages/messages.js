const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const {
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

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadMessages()
  },

  async loadMessages() {
    this.setData({
      loading: true,
      errorMessage: ''
    })

    try {
      const response = await request({
        url: MESSAGE_LIST_URL,
        data: buildMessageQuery({
          filter: this.data.activeFilter,
          size: PAGE_SIZE
        })
      })
      this.setData({
        messageData: normalizeMessageList(response),
        selectedIds: [],
        loading: false
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '消息加载失败'
      })
    }
  },

  redirectToLogin() {
    wx.redirectTo({
      url: LOGIN_PAGE_URL
    })
  },

  handleRetry() {
    this.bootstrap()
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
      await request({
        url: MESSAGE_MARK_READ_URL,
        method: 'PUT',
        data: buildMarkReadPayload(messageIds)
      })
      wx.showToast({
        title: successTitle,
        icon: 'success'
      })
      this.setData({
        saving: false
      })
      this.loadMessages()
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
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
      await request({
        url: MESSAGE_MARK_ALL_READ_URL,
        method: 'PUT',
        data: buildReadAllPayload(this.data.activeFilter)
      })
      wx.showToast({
        title: '已全部标记',
        icon: 'success'
      })
      this.setData({
        saving: false
      })
      this.loadMessages()
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
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
      url: actionUrl
    })
  }
})

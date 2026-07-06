const { request } = require('../../utils/request')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const { normalizeDashboard } = require('../../utils/dashboard')
const { normalizeUnreadCount } = require('../../utils/messages')

const MESSAGE_ENTRY_TYPE = 'messages'
const MESSAGE_ICON_URL = '/assets/system/wefolio-message-icon.png'
const MESSAGE_UNREAD_COUNT_URL = '/api/mine/messages/unread-count'
const MESSAGES_PAGE_URL = '/pages/messages/messages'
const POINTS_PAGE_URL = '/pages/points/points'
const SCHEDULE_PAGE_URL = '/pages/schedule/schedule'
const WORKS_PAGE_URL = '/pages/works/works'
const PORTFOLIOS_PAGE_URL = '/pages/portfolios/portfolios'

function buildEntries(messageUnread = normalizeUnreadCount({})) {
  return [
    {
      type: 'visits',
      title: '访问记录',
      desc: '访客来源、访问次数、跟进状态',
      iconUrl: '/assets/system/wefolio-visitor-record-icon.png'
    },
    {
      type: 'teams',
      title: '我的团队',
      desc: '按角色显示可用功能',
      iconUrl: '/assets/system/wefolio-team-icon.png'
    },
    {
      type: MESSAGE_ENTRY_TYPE,
      title: '我的消息',
      desc: '系统提醒、团队邀请',
      iconUrl: MESSAGE_ICON_URL,
      badgeText: messageUnread.badgeText
    }
  ]
}

Page({
  data: {
    loading: true,
    errorMessage: '',
    dashboard: normalizeDashboard({}),
    entries: buildEntries(),
    tabs: [
      { key: 'schedule', label: '档期', icon: 'schedule' },
      { key: 'work', label: '作品', icon: 'work' },
      { key: 'portfolio', label: '作品集', icon: 'portfolio' },
      { key: 'mine', label: '我的', icon: 'mine', active: true }
    ]
  },

  onShow() {
    this.bootstrap()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadDashboard()
  },

  async loadDashboard() {
    this.setData({
      loading: true,
      errorMessage: ''
    })

    try {
      const dashboard = await request({
        url: '/api/mine/dashboard'
      })
      this.setData({
        dashboard: normalizeDashboard(dashboard),
        loading: false
      })
      this.loadMessageUnreadCount()
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '首页加载失败'
      })
    }
  },

  async loadMessageUnreadCount() {
    try {
      const response = await request({
        url: MESSAGE_UNREAD_COUNT_URL
      })
      this.setData({
        entries: buildEntries(normalizeUnreadCount(response))
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
      }
    }
  },

  redirectToLogin() {
    wx.redirectTo({
      url: '/pages/login/login'
    })
  },

  handleRetry() {
    this.bootstrap()
  },

  handleCopyCode() {
    const uniqueCode = this.data.dashboard.profile.uniqueCode
    if (!uniqueCode || uniqueCode === '-') {
      wx.showToast({
        title: '暂无唯一码',
        icon: 'none'
      })
      return
    }
    wx.setClipboardData({
      data: uniqueCode,
      success() {
        wx.showToast({
          title: '已复制唯一码',
          icon: 'success'
        })
      }
    })
  },

  handleProfileTap() {
    wx.navigateTo({
      url: '/pages/profile/profile'
    })
  },

  handlePointsTap() {
    wx.navigateTo({
      url: POINTS_PAGE_URL
    })
  },

  handleEntryTap(event) {
    const type = event.currentTarget.dataset.type
    if (type === 'visits') {
      wx.navigateTo({
        url: '/pages/visits/visits'
      })
      return
    }
    if (type === 'teams') {
      wx.navigateTo({
        url: '/pages/teams/teams'
      })
      return
    }
    if (type === MESSAGE_ENTRY_TYPE) {
      wx.navigateTo({
        url: MESSAGES_PAGE_URL
      })
      return
    }
    wx.showToast({
      title: '一期后续页面接入中',
      icon: 'none'
    })
  },

  handleRechargeTap() {
    wx.showToast({
      title: '体验版暂时只支持后台加积分',
      icon: 'none'
    })
  },

  handleTabTap(event) {
    const label = event.currentTarget.dataset.label
    if (label === '档期') {
      wx.redirectTo({
        url: SCHEDULE_PAGE_URL
      })
      return
    }
    if (label === '我的') {
      return
    }
    if (label === '作品') {
      wx.redirectTo({
        url: WORKS_PAGE_URL
      })
      return
    }
    if (label === '作品集') {
      wx.redirectTo({
        url: PORTFOLIOS_PAGE_URL
      })
      return
    }
    wx.showToast({
      title: `${label}页面接入中`,
      icon: 'none'
    })
  }
})

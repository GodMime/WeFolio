const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const { normalizeDashboard } = require('../../utils/dashboard')

Page({
  data: {
    loading: true,
    errorMessage: '',
    dashboard: normalizeDashboard({}),
    entries: [
      {
        type: 'visits',
        title: '访问记录',
        desc: '访客来源、访问次数、跟进状态',
        iconUrl: 'https://cos.we-folio.dingchenyong.top/system/wefolio-visitor-record-icon.png'
      },
      {
        type: 'teams',
        title: '我的团队',
        desc: '按角色显示可用功能',
        iconUrl: 'https://cos.we-folio.dingchenyong.top/system/wefolio-team-icon.png'
      }
    ],
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
      await request({
        url: '/api/auth/session',
        requireAuth: false
      })
      const dashboard = await request({
        url: '/api/mine/dashboard'
      })
      this.setData({
        dashboard: normalizeDashboard(dashboard),
        loading: false
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '首页加载失败'
      })
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

  handleEntryTap(event) {
    const type = event.currentTarget.dataset.type
    if (type === 'visits') {
      wx.navigateTo({
        url: '/pages/visits/visits'
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
    if (label === '我的') {
      return
    }
    wx.showToast({
      title: `${label}页面接入中`,
      icon: 'none'
    })
  }
})

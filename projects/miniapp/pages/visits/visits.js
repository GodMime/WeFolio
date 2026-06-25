const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const { normalizeVisitRecords } = require('../../utils/visits')

Page({
  data: {
    loading: true,
    errorMessage: '',
    visitData: normalizeVisitRecords({})
  },

  onLoad() {
    this.bootstrap()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadVisits()
  },

  async loadVisits() {
    this.setData({
      loading: true,
      errorMessage: ''
    })

    try {
      const response = await request({
        url: '/api/mine/visits'
      })
      this.setData({
        visitData: normalizeVisitRecords(response),
        loading: false
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '访问记录加载失败'
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
  }
})

const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const { normalizeTeamMemberChangeDetail } = require('../../utils/teams')

const LOGIN_PAGE_URL = '/pages/login/login'
const TOAST_NAVIGATE_BACK_DELAY_MS = 1200

function emptyChangeDetail() {
  return normalizeTeamMemberChangeDetail({})
}

Page({
  data: {
    changeRequestId: null,
    loading: true,
    saving: false,
    errorMessage: '',
    changeDetail: emptyChangeDetail()
  },

  onLoad(options = {}) {
    const changeRequestId = Number(options.changeRequestId || options.id)
    this.setData({
      changeRequestId: Number.isFinite(changeRequestId) && changeRequestId > 0 ? changeRequestId : null
    })
    this.bootstrap()
  },

  onUnload() {
    if (this._navigateBackTimer) {
      clearTimeout(this._navigateBackTimer)
      this._navigateBackTimer = null
    }
  },

  bootstrap() {
    if (!hasLocalToken()) {
      wx.redirectTo({
        url: LOGIN_PAGE_URL
      })
      return
    }
    if (!this.data.changeRequestId) {
      this.setData({
        loading: false,
        errorMessage: '缺少信息变更 ID'
      })
      return
    }
    this.loadChangeDetail()
  },

  async loadChangeDetail() {
    this.setData({
      loading: true,
      errorMessage: ''
    })
    try {
      const response = await request({
        url: '/api/mine/team-member-change-requests/detail',
        method: 'POST',
        data: { changeRequestId: this.data.changeRequestId }
      })
      this.setData({
        changeDetail: normalizeTeamMemberChangeDetail(response),
        loading: false
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '信息变更加载失败'
      })
    }
  },

  handleRetry() {
    this.bootstrap()
  },

  async handleAccept() {
    await this.respondChange({
      url: '/api/mine/team-member-change-requests/accept',
      method: 'POST',
      data: { changeRequestId: this.data.changeRequestId }
    }, '已同意')
  },

  async handleReject() {
    await this.respondChange({
      url: '/api/mine/team-member-change-requests/reject',
      method: 'POST',
      data: { changeRequestId: this.data.changeRequestId }
    }, '已拒绝')
  },

  async respondChange(requestOptions, successTitle) {
    if (this.data.saving || !this.data.changeDetail.canRespond) {
      return
    }
    this.setData({
      saving: true
    })
    try {
      const response = await request(requestOptions)
      this.setData({
        changeDetail: normalizeTeamMemberChangeDetail(response),
        saving: false
      })
      wx.showToast({
        title: successTitle,
        icon: 'success'
      })
      this.navigateBackAfterToast()
    } catch (error) {
      if (error && error.authRequired) {
        this.setData({
          saving: false
        })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        saving: false
      })
      wx.showToast({
        title: error && error.message ? error.message : '处理失败',
        icon: 'none'
      })
    }
  },

  navigateBackAfterToast() {
    if (this._navigateBackTimer) {
      clearTimeout(this._navigateBackTimer)
    }
    this._navigateBackTimer = setTimeout(() => {
      wx.navigateBack()
      this._navigateBackTimer = null
    }, TOAST_NAVIGATE_BACK_DELAY_MS)
  }
})

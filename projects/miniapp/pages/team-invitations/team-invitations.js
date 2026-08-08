const { request } = require('../../utils/request')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const { normalizeTeamInvitation } = require('../../utils/teams')

const LOGIN_PAGE_URL = '/pages/login/login'
const TOAST_NAVIGATE_BACK_DELAY_MS = 1200

function emptyInvitation() {
  return normalizeTeamInvitation({})
}

Page({
  data: {
    memberId: null,
    loading: true,
    saving: false,
    errorMessage: '',
    invitation: emptyInvitation()
  },

  onLoad(options = {}) {
    const memberId = Number(options.memberId || options.id)
    this.setData({
      memberId: Number.isFinite(memberId) && memberId > 0 ? memberId : null
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
    if (!this.data.memberId) {
      this.setData({
        loading: false,
        errorMessage: '缺少团队邀请 ID'
      })
      return
    }
    this.loadInvitation()
  },

  async loadInvitation() {
    this.setData({
      loading: true,
      errorMessage: ''
    })
    try {
      const response = await request({
        url: `/api/mine/team-invitations/${this.data.memberId}`
      })
      this.setData({
        invitation: normalizeTeamInvitation(response),
        loading: false
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '团队邀请加载失败'
      })
    }
  },

  handleRetry() {
    this.bootstrap()
  },

  async handleAccept() {
    await this.respondInvitation({
      url: `/api/mine/team-invitations/${this.data.memberId}/accept`,
      method: 'POST'
    }, '已加入团队')
  },

  async handleReject() {
    await this.respondInvitation({
      url: `/api/mine/team-invitations/${this.data.memberId}/reject`,
      method: 'POST'
    }, '已拒绝邀请')
  },

  async respondInvitation(requestOptions, successTitle) {
    if (this.data.saving || !this.data.invitation.canRespond) {
      return
    }
    this.setData({
      saving: true
    })
    try {
      const response = await request(requestOptions)
      this.setData({
        invitation: normalizeTeamInvitation(response),
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
        handleMaintainerAuthRequired(error.message)
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
    this._navigateBackTimer = setTimeout(() => {
      this._navigateBackTimer = null
      wx.navigateBack()
    }, TOAST_NAVIGATE_BACK_DELAY_MS)
  }
})

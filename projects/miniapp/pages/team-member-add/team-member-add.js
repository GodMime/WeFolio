const { request } = require('../../utils/request')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const {
  DEFAULT_MEMBER_INVITE_FORM,
  buildMemberCandidateQuery,
  buildMemberInvitePayload,
  normalizeTeamMemberCandidate,
  validateMemberInviteForm
} = require('../../utils/teams')

const LOGIN_PAGE_URL = '/pages/login/login'
const TOAST_NAVIGATE_BACK_DELAY_MS = 1200

Page({
  data: {
    teamId: null,
    loading: false,
    saving: false,
    errorMessage: '',
    uniqueCode: '',
    candidate: null,
    candidateVisible: false,
    form: {
      uniqueCode: DEFAULT_MEMBER_INVITE_FORM.uniqueCode,
      role: DEFAULT_MEMBER_INVITE_FORM.role,
      profession: DEFAULT_MEMBER_INVITE_FORM.profession,
      allowPortfolio: true,
      allowProfile: true,
      allowWorks: false
    },
    roleOptions: [
      { key: 'MANAGER', label: '管理者', desc: '作品集管理、预览、分享' },
      { key: 'MEMBER', label: '普通成员', desc: '仅预览、分享' }
    ]
  },

  onLoad(options = {}) {
    const teamId = Number(options.teamId)
    this.setData({
      teamId: Number.isFinite(teamId) && teamId > 0 ? teamId : null
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
    if (!this.data.teamId) {
      this.setData({
        errorMessage: '缺少团队 ID'
      })
    }
  },

  handleUniqueCodeInput(event) {
    const uniqueCode = event.detail.value || ''
    this.invalidateCandidateSearch()
    this.setData({
      uniqueCode,
      'form.uniqueCode': uniqueCode,
      candidateVisible: false,
      candidate: null,
      loading: false
    })
  },

  async handleSearchCandidate() {
    if (this.data.loading || !this.data.teamId) {
      return
    }
    const uniqueCode = (this.data.uniqueCode || '').trim()
    if (!uniqueCode) {
      wx.showToast({
        title: '请输入个人唯一码',
        icon: 'none'
      })
      return
    }
    const requestContext = this.createCandidateRequestContext(uniqueCode)
    this.setData({
      loading: true,
      errorMessage: ''
    })
    try {
      const response = await request({
        url: `/api/mine/teams/${this.data.teamId}/member-candidate`,
        data: buildMemberCandidateQuery(uniqueCode)
      })
      if (!this.isCurrentCandidateRequest(requestContext)) {
        return
      }
      const candidate = normalizeTeamMemberCandidate(response)
      this.setData({
        candidate,
        candidateVisible: true,
        loading: false,
        'form.uniqueCode': candidate.uniqueCode || uniqueCode,
        'form.profession': candidate.profession || ''
      })
      if (!candidate.canInvite) {
        wx.showToast({
          title: candidate.reason,
          icon: 'none'
        })
      }
    } catch (error) {
      if (!this.isCurrentCandidateRequest(requestContext)) {
        return
      }
      if (error && error.authRequired) {
        this.setData({
          loading: false
        })
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        candidateVisible: false,
        candidate: null,
        errorMessage: error && error.message ? error.message : '成员查询失败'
      })
      wx.showToast({
        title: error && error.message ? error.message : '成员查询失败',
        icon: 'none'
      })
    }
  },

  createCandidateRequestContext(uniqueCode) {
    const requestId = (this.candidateSearchRequestId || 0) + 1
    this.candidateSearchRequestId = requestId
    return {
      requestId,
      teamId: this.data.teamId,
      uniqueCode
    }
  },

  invalidateCandidateSearch() {
    this.candidateSearchRequestId = (this.candidateSearchRequestId || 0) + 1
  },

  isCurrentCandidateRequest(requestContext) {
    const currentUniqueCode = (this.data.uniqueCode || '').trim()
    return Boolean(requestContext)
      && this.candidateSearchRequestId === requestContext.requestId
      && this.data.teamId === requestContext.teamId
      && currentUniqueCode === requestContext.uniqueCode
  },

  handleRoleTap(event) {
    const role = event.currentTarget.dataset.role || 'MEMBER'
    this.setData({
      'form.role': role
    })
  },

  handleProfessionInput(event) {
    this.setData({
      'form.profession': event.detail.value || ''
    })
  },

  handlePermissionTap(event) {
    const field = event.currentTarget.dataset.field
    if (!field) {
      return
    }
    this.setData({
      [`form.${field}`]: !this.data.form[field]
    })
  },

  async handleInviteMember() {
    if (this.data.saving || !this.data.teamId) {
      return
    }
    const validation = validateMemberInviteForm(this.data.form, this.data.candidate)
    if (!validation.valid) {
      wx.showToast({
        title: validation.message,
        icon: 'none'
      })
      return
    }
    this.setData({
      saving: true
    })
    try {
      await request({
        url: `/api/mine/teams/${this.data.teamId}/members`,
        method: 'POST',
        data: buildMemberInvitePayload(this.data.form)
      })
      this.refreshPreviousPage()
      wx.showToast({
        title: '邀请已发送',
        icon: 'success'
      })
      this.setData({
        saving: false
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
        title: error && error.message ? error.message : '邀请发送失败',
        icon: 'none'
      })
    }
  },

  handleCancel() {
    wx.navigateBack()
  },

  navigateBackAfterToast() {
    this._navigateBackTimer = setTimeout(() => {
      this._navigateBackTimer = null
      wx.navigateBack()
    }, TOAST_NAVIGATE_BACK_DELAY_MS)
  },

  refreshPreviousPage() {
    const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
    const previousPage = pages.length >= 2 ? pages[pages.length - 2] : null
    if (previousPage && typeof previousPage.loadDetail === 'function') {
      previousPage.loadDetail()
    }
  }
})

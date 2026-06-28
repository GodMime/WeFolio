const { request } = require('../../utils/request')
const { handleAuthRequired, hasLocalToken } = require('../../utils/session')
const { uploadTeamAvatar } = require('../../utils/team-avatar')
const {
  DEFAULT_MEMBER_INVITE_FORM,
  buildMemberCandidateQuery,
  buildMemberInvitePayload,
  buildTeamFieldCounters,
  buildTeamPayload,
  normalizeTeamMemberCandidate,
  normalizeTeamDetail,
  validateMemberInviteForm,
  validateTeamForm
} = require('../../utils/teams')

// 维护页表单的空值结构，与创建页保持一致。
function emptyForm() {
  return {
    name: '',
    intro: '',
    avatarUrl: ''
  }
}

// 远程头像可以直接保存；本地临时头像需要先上传团队图标接口。
function isRemoteUrl(url) {
  return /^https?:\/\//.test(url || '') && !/^https?:\/\/tmp\//.test(url || '')
}

// 后端详情归一化后再转成表单，避免页面直接修改 detail 原对象。
function formFromDetail(detail) {
  return {
    name: detail.team.name,
    intro: detail.team.intro,
    avatarUrl: detail.team.avatarUrl
  }
}

// 添加成员弹窗每次打开都使用干净表单，避免沿用上一次候选人和权限选择。
function emptyMemberInviteForm() {
  return Object.assign({}, DEFAULT_MEMBER_INVITE_FORM)
}

Page({
  data: {
    teamId: null,
    loading: true,
    saving: false,
    errorMessage: '',
    detail: normalizeTeamDetail({}),
    form: emptyForm(),
    fieldCounters: buildTeamFieldCounters(emptyForm()),
    keyword: '',
    statusFilter: 'ALL',
    statusFilters: [
      { key: 'ALL', label: '全部' },
      { key: 'PENDING_CONFIRMATION', label: '待确认' },
      { key: 'JOINED', label: '已加入' }
    ],
    visibleMembers: [],
    memberAddVisible: false,
    memberSearching: false,
    memberAddErrorMessage: '',
    memberUniqueCode: '',
    candidate: null,
    candidateVisible: false,
    memberInviteForm: emptyMemberInviteForm(),
    memberRoleOptions: [
      { key: 'MANAGER', label: '管理者', desc: '作品集管理、预览、分享' },
      { key: 'MEMBER', label: '普通成员', desc: '仅预览、分享' }
    ]
  },

  onLoad(options = {}) {
    // teamId 来自“我的团队”列表卡片，非法值直接进入错误态。
    const teamId = Number(options.teamId)
    this.setData({
      teamId: Number.isFinite(teamId) && teamId > 0 ? teamId : null
    })
    this.bootstrap()
  },

  bootstrap() {
    // 维护页既需要登录态，也必须有明确 teamId，任一条件缺失都不请求后端。
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    if (!this.data.teamId) {
      this.setData({
        loading: false,
        errorMessage: '缺少团队 ID'
      })
      return
    }
    this.loadDetail()
  },

  async loadDetail() {
    // 团队详情包含资料和成员列表，加载后立即同步表单并刷新成员筛选结果。
    this.setData({
      loading: true,
      errorMessage: ''
    })

    try {
      const response = await request({
        url: `/api/mine/teams/${this.data.teamId}`
      })
      const detail = normalizeTeamDetail(response)
      const form = formFromDetail(detail)
      this.setData({
        detail,
        form,
        fieldCounters: buildTeamFieldCounters(form),
        loading: false
      }, () => {
        this.filterMembers()
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '团队详情加载失败'
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

  handleCopyTeamCode(event) {
    const uniqueCode = event.currentTarget.dataset.code || this.data.detail.team.uniqueCode
    if (!uniqueCode || uniqueCode === '-') {
      wx.showToast({
        title: '暂无团队唯一码',
        icon: 'none'
      })
      return
    }
    wx.setClipboardData({
      data: uniqueCode,
      success() {
        wx.showToast({
          title: '已复制团队唯一码',
          icon: 'success'
        })
      }
    })
  },

  handleInput(event) {
    // 资料表单共用输入处理器，保证字数统计和实际提交内容来自同一份 form。
    const field = event.currentTarget.dataset.field
    if (!field) {
      return
    }
    const value = event.detail.value || ''
    const form = Object.assign({}, this.data.form, {
      [field]: value
    })
    this.setData({
      [`form.${field}`]: value,
      fieldCounters: buildTeamFieldCounters(form)
    })
  },

  handleChooseAvatar(event) {
    // 先展示本地头像预览，保存时再根据路径类型决定是否上传。
    const avatarUrl = event.detail && event.detail.avatarUrl ? event.detail.avatarUrl : ''
    if (!avatarUrl) {
      return
    }
    this.setData({
      'form.avatarUrl': avatarUrl
    })
  },

  handleSearchInput(event) {
    // 搜索条件变化后立即在本地筛选成员，避免每次输入都访问后端。
    this.setData({
      keyword: event.detail.value || ''
    }, () => {
      this.filterMembers()
    })
  },

  handleStatusFilterTap(event) {
    // 成员状态筛选只影响当前页面展示，不改变服务端数据。
    const key = event.currentTarget.dataset.key || 'ALL'
    this.setData({
      statusFilter: key
    }, () => {
      this.filterMembers()
    })
  },

  filterMembers() {
    // 姓名、职业和个人唯一码拼成搜索文本，满足维护页快速定位成员的场景。
    const keyword = (this.data.keyword || '').trim().toLowerCase()
    const statusFilter = this.data.statusFilter
    const members = this.data.detail.members.filter((member) => {
      const matchesStatus = statusFilter === 'ALL' || member.joinStatus === statusFilter
      const haystack = `${member.displayName} ${member.profession} ${member.uniqueCode}`.toLowerCase()
      const matchesKeyword = !keyword || haystack.includes(keyword)
      return matchesStatus && matchesKeyword
    })
    this.setData({
      visibleMembers: members
    })
  },

  async handleSaveTeam() {
    // 无维护权限的成员即使触发事件也直接返回，后端还会再做一次权限校验。
    if (this.data.saving || !this.data.detail.team.canMaintain) {
      return
    }

    const validation = validateTeamForm(this.data.form)
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
      const payload = buildTeamPayload(this.data.form)
      let avatarUrl = payload.avatarUrl
      // 本地临时头像先上传到团队 others 目录，再把公开 URL 写回团队资料。
      if (avatarUrl && !isRemoteUrl(avatarUrl)) {
        avatarUrl = await uploadTeamAvatar(this.data.teamId, avatarUrl)
      }
      const response = await request({
        url: `/api/mine/teams/${this.data.teamId}`,
        method: 'PUT',
        data: Object.assign({}, payload, {
          avatarUrl
        })
      })
      const detail = normalizeTeamDetail(response)
      const form = formFromDetail(detail)
      this.setData({
        detail,
        form,
        fieldCounters: buildTeamFieldCounters(form),
        saving: false
      }, () => {
        this.filterMembers()
      })
      wx.showToast({
        title: '已保存',
        icon: 'success'
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        saving: false
      })
      wx.showToast({
        title: error && error.message ? error.message : '团队保存失败',
        icon: 'none'
      })
    }
  },

  handleAddMember() {
    if (!this.data.detail.team.canManageMembers) {
      wx.showToast({
        title: '仅拥有者可添加成员',
        icon: 'none'
      })
      return
    }
    this.invalidateCandidateSearch()
    this.setData({
      memberAddVisible: true,
      memberSearching: false,
      memberAddErrorMessage: '',
      memberUniqueCode: '',
      candidate: null,
      candidateVisible: false,
      memberInviteForm: emptyMemberInviteForm()
    })
  },

  handleMemberAddCancel() {
    this.invalidateCandidateSearch()
    this.setData({
      memberAddVisible: false,
      memberSearching: false,
      memberAddErrorMessage: '',
      memberUniqueCode: '',
      candidate: null,
      candidateVisible: false,
      memberInviteForm: emptyMemberInviteForm(),
      saving: false
    })
  },

  noop() {},

  handleMemberUniqueCodeInput(event) {
    const uniqueCode = event.detail.value || ''
    this.invalidateCandidateSearch()
    this.setData({
      memberUniqueCode: uniqueCode,
      'memberInviteForm.uniqueCode': uniqueCode,
      candidateVisible: false,
      candidate: null,
      memberSearching: false,
      memberAddErrorMessage: ''
    })
  },

  async handleSearchCandidate() {
    if (this.data.memberSearching || !this.data.teamId) {
      return
    }
    const uniqueCode = (this.data.memberUniqueCode || '').trim()
    if (!uniqueCode) {
      wx.showToast({
        title: '请输入个人唯一码',
        icon: 'none'
      })
      return
    }
    const requestContext = this.createCandidateRequestContext(uniqueCode)
    this.setData({
      memberSearching: true,
      memberAddErrorMessage: ''
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
        memberSearching: false,
        'memberInviteForm.uniqueCode': candidate.uniqueCode || uniqueCode,
        'memberInviteForm.profession': candidate.profession || ''
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
          memberSearching: false
        })
        handleAuthRequired(error.message)
        return
      }
      this.setData({
        memberSearching: false,
        candidateVisible: false,
        candidate: null,
        memberAddErrorMessage: error && error.message ? error.message : '成员查询失败'
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
    const currentUniqueCode = (this.data.memberUniqueCode || '').trim()
    return Boolean(requestContext)
      && this.candidateSearchRequestId === requestContext.requestId
      && this.data.teamId === requestContext.teamId
      && currentUniqueCode === requestContext.uniqueCode
  },

  handleMemberRoleTap(event) {
    const role = event.currentTarget.dataset.role || 'MEMBER'
    this.setData({
      'memberInviteForm.role': role
    })
  },

  handleMemberProfessionInput(event) {
    this.setData({
      'memberInviteForm.profession': event.detail.value || ''
    })
  },

  handleMemberPermissionTap(event) {
    const field = event.currentTarget.dataset.field
    if (!field) {
      return
    }
    this.setData({
      [`memberInviteForm.${field}`]: !this.data.memberInviteForm[field]
    })
  },

  async handleInviteMember() {
    if (this.data.saving || !this.data.teamId) {
      return
    }
    const validation = validateMemberInviteForm(this.data.memberInviteForm, this.data.candidate)
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
        data: buildMemberInvitePayload(this.data.memberInviteForm)
      })
      wx.showToast({
        title: '邀请已发送',
        icon: 'success'
      })
      this.invalidateCandidateSearch()
      this.setData({
        memberAddVisible: false,
        memberSearching: false,
        memberAddErrorMessage: '',
        memberUniqueCode: '',
        candidate: null,
        candidateVisible: false,
        memberInviteForm: emptyMemberInviteForm(),
        saving: false
      })
      await this.loadDetail()
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
        title: error && error.message ? error.message : '邀请发送失败',
        icon: 'none'
      })
    }
  }
})

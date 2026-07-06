const { request } = require('../../utils/request')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const { uploadTeamAvatar } = require('../../utils/team-avatar')
const {
  DEFAULT_MEMBER_INVITE_FORM,
  buildMemberCandidateQuery,
  buildMemberChangePayload,
  buildMemberInvitePayload,
  buildTeamFieldCounters,
  buildTeamPayload,
  normalizeTeamMemberCandidate,
  normalizeTeamDetail,
  validateMemberChangeForm,
  validateMemberInviteForm,
  validateTeamForm
} = require('../../utils/teams')

const TRANSFER_OWNER_REQUEST = { url: '/api/mine/teams/transfer-owner' }
const REMOVE_MEMBER_REQUEST = { url: '/api/mine/teams/remove-member' }

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

function emptyMemberChangeForm() {
  return {
    teamId: null,
    memberId: null,
    role: 'MEMBER',
    profession: '',
    allowPortfolio: false,
    allowProfile: false,
    allowWorks: false
  }
}

function changeFormFromMember(teamId, member) {
  return {
    teamId,
    memberId: member.memberId,
    role: member.role === 'MANAGER' ? 'MANAGER' : 'MEMBER',
    profession: member.profession || '',
    allowPortfolio: Boolean(member.allowPortfolio),
    allowProfile: Boolean(member.allowProfile),
    allowWorks: Boolean(member.allowWorks)
  }
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
    openedMemberActionId: null,
    memberAddVisible: false,
    memberSearching: false,
    memberAddErrorMessage: '',
    memberUniqueCode: '',
    candidate: null,
    candidateVisible: false,
    memberInviteForm: emptyMemberInviteForm(),
    selectedMember: null,
    memberChangeVisible: false,
    memberChangeForm: emptyMemberChangeForm(),
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
        handleMaintainerAuthRequired(error.message)
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
        handleMaintainerAuthRequired(error.message)
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

  getMemberById(memberId) {
    return this.data.detail.members.find((member) => Number(member.memberId) === Number(memberId)) || null
  },

  handleMemberRowTap(event) {
    if (this.justOpenedMemberAction) {
      this.justOpenedMemberAction = false
      return
    }
    if (this.data.openedMemberActionId) {
      this.setData({
        openedMemberActionId: null
      })
      return
    }
    if (!this.data.detail.team.canManageMembers) {
      return
    }
    const member = this.getMemberById(event.currentTarget.dataset.memberId)
    if (!member || member.role === 'OWNER' || member.joinStatus !== 'JOINED') {
      return
    }
    if (member.pendingChange) {
      wx.showToast({
        title: member.pendingChangeText || '信息变更待同意',
        icon: 'none'
      })
      return
    }
    this.setData({
      selectedMember: member,
      memberChangeVisible: true,
      memberChangeForm: changeFormFromMember(this.data.teamId, member)
    })
  },

  handleMemberTouchStart(event) {
    this.memberTouchStartX = event.touches && event.touches[0] ? event.touches[0].clientX : 0
    this.memberTouchMemberId = event.currentTarget.dataset.memberId
  },

  handleMemberTouchEnd(event) {
    const endX = event.changedTouches && event.changedTouches[0] ? event.changedTouches[0].clientX : this.memberTouchStartX
    const deltaX = endX - this.memberTouchStartX
    const member = this.getMemberById(this.memberTouchMemberId)
    if (!member || !this.data.detail.team.canManageMembers || member.role === 'OWNER' || member.joinStatus !== 'JOINED') {
      return
    }
    if (deltaX < -36) {
      this.justOpenedMemberAction = true
      this.setData({
        openedMemberActionId: member.memberId
      })
    } else if (deltaX > 36) {
      this.setData({
        openedMemberActionId: null
      })
    }
  },

  handleMemberChangeCancel() {
    this.setData({
      selectedMember: null,
      memberChangeVisible: false,
      memberChangeForm: emptyMemberChangeForm(),
      saving: false
    })
  },

  handleMemberChangeRoleTap(event) {
    const role = event.currentTarget.dataset.role || 'MEMBER'
    this.setData({
      'memberChangeForm.role': role
    })
  },

  handleMemberChangeProfessionInput(event) {
    this.setData({
      'memberChangeForm.profession': event.detail.value || ''
    })
  },

  handleMemberChangePermissionTap(event) {
    const field = event.currentTarget.dataset.field
    if (!field) {
      return
    }
    this.setData({
      [`memberChangeForm.${field}`]: !this.data.memberChangeForm[field]
    })
  },

  async handleSaveMemberChange() {
    if (this.data.saving || !this.data.selectedMember) {
      return
    }
    const validation = validateMemberChangeForm(this.data.memberChangeForm, this.data.selectedMember)
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
        url: '/api/mine/team-member-change-requests',
        method: 'POST',
        data: buildMemberChangePayload(this.data.memberChangeForm)
      })
      wx.showToast({
        title: '已发送确认',
        icon: 'success'
      })
      this.setData({
        selectedMember: null,
        memberChangeVisible: false,
        memberChangeForm: emptyMemberChangeForm(),
        openedMemberActionId: null,
        saving: false
      })
      await this.loadDetail()
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
        title: error && error.message ? error.message : '发送失败',
        icon: 'none'
      })
    }
  },

  handleTransferOwner(event) {
    const member = this.getMemberById(event.currentTarget.dataset.memberId)
    if (!member || this.data.saving) {
      return
    }
    wx.showModal({
      title: '转让团队',
      content: `确认将团队转让给${member.displayName}？转让后你将变为管理员。`,
      confirmText: '确认转让',
      success: (result) => {
        if (!result.confirm) {
          return
        }
        this.handleConfirmedMemberAction(TRANSFER_OWNER_REQUEST.url, member.memberId, '已转让')
      }
    })
  },

  handleRemoveMember(event) {
    const member = this.getMemberById(event.currentTarget.dataset.memberId)
    if (!member || this.data.saving) {
      return
    }
    wx.showModal({
      title: '移除成员',
      content: `确认将${member.displayName}移出团队？`,
      confirmText: '确认移除',
      success: (result) => {
        if (!result.confirm) {
          return
        }
        this.handleConfirmedMemberAction(REMOVE_MEMBER_REQUEST.url, member.memberId, '已移除')
      }
    })
  },

  handleConfirmedMemberAction(url, memberId, successTitle) {
    this.submitMemberAction(url, memberId, successTitle).catch((error) => {
      this.setData({
        saving: false
      })
      wx.showToast({
        title: error && error.message ? error.message : '处理失败',
        icon: 'none'
      })
    })
  },

  async submitMemberAction(url, memberId, successTitle) {
    this.setData({
      saving: true
    })
    try {
      await request({
        url,
        method: 'POST',
        data: {
          teamId: this.data.teamId,
          memberId
        }
      })
      wx.showToast({
        title: successTitle,
        icon: 'success'
      })
      this.setData({
        saving: false
      })
      await this.loadDetail()
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
        title: error && error.message ? error.message : '操作失败',
        icon: 'none'
      })
    }
  },

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
        handleMaintainerAuthRequired(error.message)
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
  }
})

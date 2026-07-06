const { request } = require('../../utils/request')
const { handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const { uploadTeamAvatar } = require('../../utils/team-avatar')
const {
  buildTeamFieldCounters,
  buildTeamPayload,
  normalizeTeamList,
  validateTeamForm
} = require('../../utils/teams')

// 创建表单的初始值，收起表单时也使用它恢复空白状态。
function emptyForm() {
  return {
    name: '',
    intro: '',
    avatarUrl: ''
  }
}

// 微信 chooseAvatar 返回的是本地临时路径；只有远程 URL 才能直接写入团队资料。
function isRemoteUrl(url) {
  return /^https?:\/\//.test(url || '') && !/^https?:\/\/tmp\//.test(url || '')
}

Page({
  data: {
    loading: true,
    saving: false,
    errorMessage: '',
    teamData: normalizeTeamList({}),
    createFormVisible: false,
    form: emptyForm(),
    fieldCounters: buildTeamFieldCounters(emptyForm())
  },

  onShow() {
    // 从“团队维护”页返回时刷新列表，保证刚保存的团队名称和图标能立即同步。
    this.bootstrap()
  },

  bootstrap() {
    // 团队接口需要登录态；本地没有 token 时直接回登录页，避免请求后再闪错误态。
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadTeams()
  },

  async loadTeams() {
    // 页面列表态统一由 normalizeTeamList 兜底，后端字段缺失时也能保持页面可渲染。
    this.setData({
      loading: true,
      errorMessage: ''
    })

    try {
      const response = await request({
        url: '/api/mine/teams'
      })
      this.setData({
        teamData: normalizeTeamList(response),
        loading: false
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '团队列表加载失败'
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

  handleCreateToggle() {
    const nextVisible = !this.data.createFormVisible
    // 收起创建区时清空表单，避免下次展开残留上一次未提交的团队信息。
    this.setData({
      createFormVisible: nextVisible,
      form: nextVisible ? this.data.form : emptyForm(),
      fieldCounters: buildTeamFieldCounters(nextVisible ? this.data.form : emptyForm())
    })
  },

  handleInput(event) {
    // 所有输入框共用一个处理器，通过 data-field 指向具体字段并同步字数统计。
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
    // chooseAvatar 只把临时头像路径写入表单；真正上传发生在保存创建之后。
    const avatarUrl = event.detail && event.detail.avatarUrl ? event.detail.avatarUrl : ''
    if (!avatarUrl) {
      return
    }
    this.setData({
      'form.avatarUrl': avatarUrl
    })
  },

  async handleCreateTeam() {
    // saving 保护防止用户连续点击导致重复创建团队。
    if (this.data.saving) {
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
      // 创建接口先生成团队 ID 和唯一码；本地头像无法随 JSON 提交，先传空头像。
      const created = await request({
        url: '/api/mine/teams',
        method: 'POST',
        data: Object.assign({}, payload, {
          avatarUrl: isRemoteUrl(payload.avatarUrl) ? payload.avatarUrl : ''
        })
      })
      // 拿到 teamId 后再上传本地头像，并用返回的公开 URL 回写团队资料。
      if (payload.avatarUrl && !isRemoteUrl(payload.avatarUrl)) {
        const avatarUrl = await uploadTeamAvatar(created.team.teamId, payload.avatarUrl)
        await request({
          url: `/api/mine/teams/${created.team.teamId}`,
          method: 'PUT',
          data: {
            avatarUrl
          }
        })
      }
      wx.showToast({
        title: '团队已创建',
        icon: 'success'
      })
      this.setData({
        saving: false,
        createFormVisible: false,
        form: emptyForm(),
        fieldCounters: buildTeamFieldCounters(emptyForm())
      })
      this.loadTeams()
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        saving: false
      })
      wx.showToast({
        title: error && error.message ? error.message : '团队创建失败',
        icon: 'none'
      })
    }
  },

  handleTeamTap(event) {
    // 列表卡片统一进入团队维护页；权限由维护页详情和后端再次校验。
    const teamId = event.currentTarget.dataset.teamId
    if (!teamId) {
      return
    }
    wx.navigateTo({
      url: `/pages/team-maintenance/team-maintenance?teamId=${teamId}`
    })
  }
})

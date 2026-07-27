const { request } = require('../../utils/request')
const { clearToken, handleMaintainerAuthRequired, hasLocalToken } = require('../../utils/session')
const { noop } = require('../../utils/noop')
const {
  WECHAT_QR_CROP_FILE_TYPE,
  WECHAT_QR_CROP_OUTPUT_WIDTH,
  WECHAT_QR_CROP_QUALITY,
  buildWechatQrCropFrame,
  buildWechatQrCropState,
  cropWechatQrToTempFilePath,
  getWechatQrImageInfo,
  moveWechatQrCropState,
  uploadProfileAvatar,
  uploadWechatQr
} = require('../../utils/profile-assets')
const {
  DEFAULT_TAG_COLOR,
  TAG_COLOR_OPTIONS,
  buildProfileFieldCounters,
  buildProfilePayload,
  createProfileTag,
  normalizeProfile,
  validateProfileForm
} = require('../../utils/profile')

const DESIGN_VIEWPORT_RPX = 750
const WECHAT_QR_CROP_CANVAS_ID = 'wechatQrCropCanvas'
const WECHAT_QR_CROP_MAX_WIDTH_RPX = 560
const WECHAT_QR_CROP_HORIZONTAL_GUTTER_RPX = 116
const WECHAT_QR_PICK_FAILED_MESSAGE = '二维码选择失败'
const WECHAT_QR_CROP_FAILED_MESSAGE = '二维码裁剪失败'

function emptyProfile() {
  return normalizeProfile({})
}

function emptyForm() {
  return {
    nickname: '',
    avatarUrl: '',
    profession: '',
    city: '',
    intro: '',
    wechatQrUrl: '',
    tags: []
  }
}

function formFromProfile(profile) {
  return {
    nickname: profile.nickname,
    avatarUrl: profile.avatarUrl,
    profession: profile.profession,
    city: profile.city,
    intro: profile.intro,
    wechatQrUrl: profile.wechatQrUrl,
    tags: profile.tags.slice()
  }
}

function trimText(value) {
  return (value || '').trim()
}

function getTouchClientX(event = {}) {
  const touch = (event.touches && event.touches[0]) || (event.changedTouches && event.changedTouches[0])
  const clientX = touch && Number(touch.clientX)
  return Number.isFinite(clientX) ? clientX : null
}

function getTouchClientY(event = {}) {
  const touch = (event.touches && event.touches[0]) || (event.changedTouches && event.changedTouches[0])
  const clientY = touch && Number(touch.clientY)
  return Number.isFinite(clientY) ? clientY : null
}

function resolveChosenImageFile(response = {}) {
  const files = Array.isArray(response.tempFiles) ? response.tempFiles : []
  const firstFile = files[0] || {}
  const filePath = firstFile.tempFilePath || firstFile.path || ''
  return filePath ? Object.assign({}, firstFile, { path: filePath }) : null
}

function getWechatQrCropBoxWidth() {
  const fallbackWindowWidth = 375
  const windowInfo = typeof wx !== 'undefined' && wx.getWindowInfo
    ? wx.getWindowInfo()
    : (typeof wx !== 'undefined' && wx.getSystemInfoSync ? wx.getSystemInfoSync() : {})
  const windowWidth = Number(windowInfo.windowWidth) || fallbackWindowWidth
  const rpxScale = windowWidth / DESIGN_VIEWPORT_RPX
  return Math.floor(Math.min(
    WECHAT_QR_CROP_MAX_WIDTH_RPX * rpxScale,
    windowWidth - WECHAT_QR_CROP_HORIZONTAL_GUTTER_RPX * rpxScale
  ))
}

function resetWechatQrCropState() {
  return {
    wechatQrCropVisible: false,
    wechatQrCropSaving: false,
    wechatQrCropErrorText: '',
    wechatQrCropState: null,
    wechatQrCropTouchStart: null
  }
}

Page({
  data: {
    loading: true,
    saving: false,
    errorMessage: '',
    profile: emptyProfile(),
    form: emptyForm(),
    fieldCounters: buildProfileFieldCounters(emptyForm()),
    cancelling: false,
    tagDialogVisible: false,
    tagColorOptions: TAG_COLOR_OPTIONS,
    selectedTagColor: DEFAULT_TAG_COLOR,
    newTag: '',
    tagErrorText: '',
    wechatQrCropVisible: false,
    wechatQrCropSaving: false,
    wechatQrCropErrorText: '',
    wechatQrCropState: null,
    wechatQrCropTouchStart: null,
    wechatQrCropCanvasWidth: WECHAT_QR_CROP_OUTPUT_WIDTH,
    wechatQrCropCanvasHeight: WECHAT_QR_CROP_OUTPUT_WIDTH
  },

  onLoad() {
    this.bootstrap()
  },

  bootstrap() {
    if (!hasLocalToken()) {
      this.redirectToLogin()
      return
    }
    this.loadProfile()
  },

  async loadProfile() {
    this.setData({
      loading: true,
      errorMessage: ''
    })

    try {
      const response = await request({
        url: '/api/mine/profile'
      })
      const profile = normalizeProfile(response)
      const form = formFromProfile(profile)
      this.setData({
        profile,
        form,
        fieldCounters: buildProfileFieldCounters(form),
        loading: false
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        loading: false,
        errorMessage: error && error.message ? error.message : '基础信息加载失败'
      })
    }
  },

  redirectToLogin() {
    wx.redirectTo({
      url: '/pages/login/login'
    })
  },

  noop,

  handleRetry() {
    this.bootstrap()
  },

  handleInput(event) {
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
      fieldCounters: buildProfileFieldCounters(form)
    })
  },

  handleChooseAvatar(event) {
    const avatarUrl = event.detail && event.detail.avatarUrl ? event.detail.avatarUrl : ''
    if (!avatarUrl) {
      return
    }
    this.setData({
      'form.avatarUrl': avatarUrl
    })
  },

  handleChooseWechatQr() {
    if (this.data.saving) {
      return
    }
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['album'],
      success: (response) => {
        const imageFile = resolveChosenImageFile(response)
        if (imageFile) {
          this.prepareSelectedWechatQr(imageFile)
        }
      },
      fail: (error) => {
        if (error && /cancel/i.test(error.errMsg || '')) {
          return
        }
        wx.showToast({
          title: error && error.errMsg ? error.errMsg : WECHAT_QR_PICK_FAILED_MESSAGE,
          icon: 'none'
        })
      }
    })
  },

  prepareSelectedWechatQr(imageFile) {
    return getWechatQrImageInfo(imageFile)
      .then((imageInfo) => {
        const cropState = buildWechatQrCropState(imageInfo, {
          cropBoxWidth: getWechatQrCropBoxWidth()
        })
        this.setData({
          wechatQrCropVisible: true,
          wechatQrCropSaving: false,
          wechatQrCropErrorText: '',
          wechatQrCropState: cropState,
          wechatQrCropTouchStart: null
        })
      })
      .catch((error) => {
        wx.showToast({
          title: error && error.message ? error.message : WECHAT_QR_PICK_FAILED_MESSAGE,
          icon: 'none'
        })
      })
  },

  setWechatQrUrl(wechatQrUrl) {
    const form = Object.assign({}, this.data.form, {
      wechatQrUrl
    })
    this.setData(Object.assign({
      'form.wechatQrUrl': wechatQrUrl,
      fieldCounters: buildProfileFieldCounters(form)
    }, resetWechatQrCropState()))
  },

  handleCloseWechatQrCrop() {
    if (this.data.wechatQrCropSaving) {
      return
    }
    this.setData(resetWechatQrCropState())
  },

  handleWechatQrCropTouchStart(event) {
    const clientX = getTouchClientX(event)
    const clientY = getTouchClientY(event)
    const cropState = this.data.wechatQrCropState || {}
    this.setData({
      wechatQrCropTouchStart: {
        x: clientX === null ? 0 : clientX,
        y: clientY === null ? 0 : clientY,
        offsetX: Number(cropState.offsetX) || 0,
        offsetY: Number(cropState.offsetY) || 0
      }
    })
  },

  handleWechatQrCropTouchMove(event) {
    const start = this.data.wechatQrCropTouchStart
    const cropState = this.data.wechatQrCropState
    if (!start || !cropState) {
      return
    }
    const clientX = getTouchClientX(event)
    const clientY = getTouchClientY(event)
    const baseState = Object.assign({}, cropState, {
      offsetX: start.offsetX,
      offsetY: start.offsetY
    })
    this.setData({
      wechatQrCropState: moveWechatQrCropState(baseState, {
        deltaX: (clientX === null ? start.x : clientX) - start.x,
        deltaY: (clientY === null ? start.y : clientY) - start.y
      })
    })
  },

  handleWechatQrCropTouchEnd() {
    this.setData({ wechatQrCropTouchStart: null })
  },

  handleWechatQrCropTouchCancel() {
    this.setData({ wechatQrCropTouchStart: null })
  },

  handleConfirmWechatQrCrop() {
    if (this.data.wechatQrCropSaving || !this.data.wechatQrCropState) {
      return Promise.resolve()
    }
    const cropState = this.data.wechatQrCropState
    const cropFrame = buildWechatQrCropFrame(cropState, {
      outputWidth: WECHAT_QR_CROP_OUTPUT_WIDTH
    })
    this.setData({
      wechatQrCropSaving: true,
      wechatQrCropErrorText: ''
    })
    return cropWechatQrToTempFilePath({
      page: this,
      wxApi: wx,
      canvasId: WECHAT_QR_CROP_CANVAS_ID,
      imagePath: cropState.imagePath,
      cropFrame,
      fileType: WECHAT_QR_CROP_FILE_TYPE,
      quality: WECHAT_QR_CROP_QUALITY
    }).then((croppedPath) => {
      this.setWechatQrUrl(croppedPath)
    }).catch((error) => {
      const message = error && error.message ? error.message : WECHAT_QR_CROP_FAILED_MESSAGE
      this.setData({
        wechatQrCropSaving: false,
        wechatQrCropErrorText: message
      })
      wx.showToast({
        title: message,
        icon: 'none'
      })
    })
  },

  handleOpenTagDialog() {
    if (this.data.form.tags.length >= 10) {
      wx.showToast({
        title: '标签最多保留 10 个',
        icon: 'none'
      })
      return
    }
    this.setData({
      tagDialogVisible: true,
      selectedTagColor: DEFAULT_TAG_COLOR,
      newTag: '',
      tagErrorText: ''
    })
  },

  handleCloseTagDialog() {
    this.setData({
      tagDialogVisible: false,
      selectedTagColor: DEFAULT_TAG_COLOR,
      newTag: '',
      tagErrorText: ''
    })
  },

  handleNewTagInput(event) {
    this.setData({
      newTag: event.detail.value || '',
      tagErrorText: ''
    })
  },

  handleSelectTagColor(event) {
    const color = event.currentTarget.dataset.color || DEFAULT_TAG_COLOR
    this.setData({
      selectedTagColor: color,
      tagErrorText: ''
    })
  },

  handleAddTag() {
    const value = trimText(this.data.newTag)
    const nextTags = this.data.form.tags.concat(createProfileTag(value, this.data.selectedTagColor))
    const validation = validateProfileForm(Object.assign({}, this.data.form, {
      tags: nextTags
    }))
    if (!validation.valid) {
      this.setData({
        tagErrorText: validation.message
      })
      return
    }

    this.setData({
      'form.tags': nextTags,
      'profile.tagCountText': `${nextTags.length} / 10`,
      tagDialogVisible: false,
      selectedTagColor: DEFAULT_TAG_COLOR,
      newTag: '',
      tagErrorText: ''
    })
  },

  handleRemoveTag(event) {
    const index = Number(event.currentTarget.dataset.index)
    if (!Number.isInteger(index) || index < 0) {
      return
    }
    const nextTags = this.data.form.tags.filter((_, currentIndex) => currentIndex !== index)
    this.setData({
      'form.tags': nextTags,
      'profile.tagCountText': `${nextTags.length} / 10`
    })
  },

  async handleSave() {
    if (this.data.saving) {
      return
    }

    const validation = validateProfileForm(this.data.form)
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
      const payload = buildProfilePayload(this.data.form)
      let avatarUrl = payload.avatarUrl
      try {
        avatarUrl = await uploadProfileAvatar(payload.avatarUrl)
        if (!avatarUrl) {
          avatarUrl = this.data.profile.avatarUrl
        }
      } catch (uploadError) {
        // 上传失败时保留已有头像，不覆盖为空
        avatarUrl = this.data.profile.avatarUrl
        wx.showToast({
          title: '头像上传失败，已保留原有头像',
          icon: 'none'
        })
      }
      let wechatQrUrl = payload.wechatQrUrl
      try {
        wechatQrUrl = await uploadWechatQr(payload.wechatQrUrl)
      } catch (uploadError) {
        wechatQrUrl = this.data.profile.wechatQrUrl
        wx.showToast({
          title: '微信二维码上传失败，已保留原有二维码',
          icon: 'none'
        })
      }
      const response = await request({
        url: '/api/mine/profile',
        method: 'PUT',
        data: Object.assign({}, payload, {
          avatarUrl,
          wechatQrUrl
        })
      })
      const profile = normalizeProfile(response)
      const form = formFromProfile(profile)
      this.setData({
        profile,
        form,
        fieldCounters: buildProfileFieldCounters(form),
        saving: false
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
        title: error && error.message ? error.message : '保存失败',
        icon: 'none'
      })
    }
  },

  handleLogout() {
    clearToken()
    wx.redirectTo({
      url: '/pages/login/login'
    })
  },

  handleCancelAccount() {
    if (this.data.cancelling) {
      return
    }
    wx.showModal({
      title: '确认注销账号',
      content: '注销后账号将被停用，当前登录会失效。',
      confirmText: '确认注销',
      confirmColor: '#d83a3a',
      success: (result) => {
        if (result && result.confirm) {
          this.cancelAccount()
        }
      }
    })
  },

  async cancelAccount() {
    this.setData({
      cancelling: true
    })

    try {
      await request({
        url: '/api/auth/account/cancel',
        method: 'POST'
      })
      clearToken()
      wx.showToast({
        title: '账号已注销',
        icon: 'none'
      })
      wx.redirectTo({
        url: '/pages/login/login'
      })
    } catch (error) {
      if (error && error.authRequired) {
        handleMaintainerAuthRequired(error.message)
        return
      }
      this.setData({
        cancelling: false
      })
      wx.showToast({
        title: error && error.message ? error.message : '注销失败',
        icon: 'none'
      })
    }
  }
})

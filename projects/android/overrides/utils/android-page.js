const { sharePage } = require('./android-share')

/** 给原页面增加系统头像选择，上传和保存仍复用原有业务处理。 */
function registerAndroidPage(definition) {
  const page = Object.assign({}, definition, {
    androidShare(scene = 0) {
      return sharePage(this, scene).catch(error => wx.showToast({ title: error.message, icon: 'none' }))
    },
    androidChooseAvatar(event) {
      const handler = event.currentTarget.dataset.androidAvatarHandler
      if (typeof this[handler] !== 'function') return
      wx.chooseMedia({
        count: 1,
        mediaType: ['image'],
        sourceType: ['album', 'camera'],
        success: result => {
          const image = result.tempFiles && result.tempFiles[0]
          if (image && image.tempFilePath) {
            this[handler]({ ...event, detail: { avatarUrl: image.tempFilePath } })
          }
        },
        fail: error => {
          if (!/cancel/i.test(error.errMsg || '')) wx.showToast({ title: '选择头像失败，请重试', icon: 'none' })
        }
      })
    }
  })
  return Page(page)
}

module.exports = { registerAndroidPage }

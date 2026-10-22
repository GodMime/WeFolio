/** 系统文件选择替代微信会话附件选择，返回结构沿用现有音频上传校验。 */
function chooseAudioFiles(wxApi, options) {
  if (!wxApi.miniapp || typeof wxApi.miniapp.chooseFile !== 'function') {
    options.fail({ errMsg: '系统文件选择器不可用，请在安卓 App 中重试' })
    return
  }
  wxApi.miniapp.chooseFile({
    allowsMultipleSelection: options.count > 1,
    success(result) {
      const files = result.tempFiles || []
      if (files.length > options.count) {
        options.fail({ errMsg: `本次最多可选择 ${options.count} 个文件` })
        return
      }
      options.success({ tempFiles: files })
    },
    fail: options.fail
  })
}

module.exports = { chooseAudioFiles }

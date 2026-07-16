const UPDATE_MODAL = Object.freeze({
  title: '版本更新',
  content: '新版本已经准备好。点击后将立即重启，当前未保存的内容会丢失。',
  confirmText: '立即重启',
  showCancel: false
})

const UPDATE_FAILED_MODAL = Object.freeze({
  title: '更新失败',
  content: '新版本下载失败，请检查网络并稍后重新打开小程序。',
  confirmText: '知道了',
  showCancel: false
})

const UNSUPPORTED_MODAL = Object.freeze({
  title: '微信版本过低',
  content: '当前微信版本不支持自动更新，请升级微信后重新打开小程序。',
  confirmText: '知道了',
  showCancel: false
})

function warn(message, error) {
  if (typeof console !== 'undefined' && typeof console.warn === 'function') {
    console.warn(message, error)
  }
}

function createUpdateController(providedWxApi) {
  const wxApi = providedWxApi || (typeof wx !== 'undefined' ? wx : null)
  let initialized = false
  let updateReady = false
  let prompting = false
  let applyRequested = false
  let updateManager = null
  let initialShowObserved = false

  function showInformation(options) {
    try {
      if (wxApi && typeof wxApi.showModal === 'function') {
        wxApi.showModal(Object.assign({}, options))
      }
    } catch (error) {
      warn('显示小程序更新提示失败', error)
    }
  }

  function init() {
    if (initialized) return Boolean(updateManager)
    initialized = true

    if (!wxApi || typeof wxApi.getUpdateManager !== 'function') {
      showInformation(UNSUPPORTED_MODAL)
      return false
    }

    try {
      updateManager = wxApi.getUpdateManager()
      updateManager.onCheckForUpdate(() => {})
      updateManager.onUpdateReady(() => {
        updateReady = true
      })
      updateManager.onUpdateFailed(() => {
        updateReady = false
        showInformation(UPDATE_FAILED_MODAL)
      })
      return true
    } catch (error) {
      updateManager = null
      warn('初始化小程序更新管理器失败', error)
      return false
    }
  }

  function promptIfReady() {
    if (!initialShowObserved) {
      initialShowObserved = true
      return
    }
    if (!updateReady || prompting || applyRequested || !updateManager) return

    prompting = true
    try {
      wxApi.showModal(Object.assign({}, UPDATE_MODAL, {
        success(result) {
          if (!result || !result.confirm) {
            prompting = false
            return
          }
          if (!updateReady) {
            prompting = false
            return
          }
          if (applyRequested) {
            prompting = false
            return
          }

          applyRequested = true
          try {
            updateManager.applyUpdate()
          } catch (error) {
            applyRequested = false
            prompting = false
            warn('应用小程序更新失败', error)
          }
        },
        fail(error) {
          prompting = false
          warn('显示小程序强制更新提示失败', error)
        }
      }))
    } catch (error) {
      prompting = false
      warn('显示小程序强制更新提示失败', error)
    }
  }

  return {
    init,
    promptIfReady
  }
}

module.exports = {
  createUpdateController
}

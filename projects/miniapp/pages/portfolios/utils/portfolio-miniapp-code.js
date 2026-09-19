const { request } = require('../../../utils/request')

const API_PREFIXES = { USER: '/api/mine/portfolios', TEAM: '/api/mine/team-portfolios' }
const PAGE_URL = '/pages/portfolios/share-code/portfolio-share-code'
const REVALIDATE_AFTER_MS = 5 * 60 * 1000
const ALBUM_SCOPE = 'scope.writePhotosAlbum'
const DEFAULT_AVATARS = { USER: '/assets/system/wefolio-default-avatar-512.jpg', TEAM: '/assets/system/wefolio-team-icon.png' }
const DOWNLOAD_ERROR = '图片下载失败，请重试'
const LOCAL_FILE_ERROR = '临时图片已失效，请重试'
const RESOURCE_MISSING_STATUSES = [404, 410]
const AVATAR_TOGGLE_STATUSES = ['ready', 'download-error', 'render-error']
const SAVE_FAILURE_LOG = 'portfolio-miniapp-code-save-failed'
const UNDECLARED_ALBUM_PRIVACY_ERROR = 'saveImageToPhotosAlbum:fail api scope is not declared in the privacy agreement'
const ALBUM_UNAVAILABLE_MESSAGE = '保存功能暂不可用，请联系管理员'
const REDACTED_LOG_VALUE = '[redacted]'
const MAX_DIAGNOSTIC_MESSAGE_LENGTH = 512

// 原生错误可能拼接文件路径、资源地址或身份字段，只记录脱敏后的短消息，不记录原错误对象。
function safeDiagnosticMessage(value) {
  return String(value || 'unknown error')
    .replace(/(["'])(?:(?:https?|wxfile|file|data|blob):|\/|[A-Za-z]:\\)[\s\S]*?\1/gi, REDACTED_LOG_VALUE)
    .replace(/(?:https?|wxfile|file|data|blob):[^\s"'<>]+/gi, REDACTED_LOG_VALUE)
    .replace(/((?:access[_-]?token|token|secret|authorization|openid|unionid|phone|mobile|userId|ownerId|portfolioId|uniqueCode|nickname|displayName|avatarUrl|filePath)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;]+)/gi, '$1' + REDACTED_LOG_VALUE)
    .replace(/Bearer\s+[A-Za-z0-9._~+\/-]+=*/gi, REDACTED_LOG_VALUE)
    .replace(/(?:[A-Za-z]:[\\/]|\/)[^\s"'<>]*/g, REDACTED_LOG_VALUE)
    .replace(/\b1[3-9]\d{9}\b/g, REDACTED_LOG_VALUE)
    .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, REDACTED_LOG_VALUE)
    .replace(/\s+/g, ' ')
    .slice(0, MAX_DIAGNOSTIC_MESSAGE_LENGTH)
}

function normalizeTarget(options = {}) {
  const portfolioId = Number(options.portfolioId)
  return API_PREFIXES[options.ownerType] && Number.isSafeInteger(portfolioId) && portfolioId > 0
    ? { ownerType: options.ownerType, portfolioId }
    : null
}

function buildPortfolioMiniappCodePath(ownerType, portfolioId) {
  const target = normalizeTarget({ ownerType, portfolioId })
  return target ? `${PAGE_URL}?ownerType=${target.ownerType}&portfolioId=${target.portfolioId}` : ''
}

// 每个页面持有独立生命周期，避免返回设置、预览或旧请求覆盖当前作品集。
function createPortfolioMiniappCodeController(options = {}) {
  const wxApi = options.wxApi || wx
  const requestFn = options.requestFn || request
  const now = options.now || Date.now
  const renderFn = options.renderFn
  const consoleApi = options.consoleApi || console
  let revision = 0
  let disposed = false
  let target = null
  let validatedAt = 0
  let resources = null
  let downloads = new Map()
  let state = { status: 'idle', saving: false, useAvatar: false, canToggleAvatar: false, codeUrl: '', tempFilePath: '', displayFilePath: '', errorMessage: '' }
  const active = ticket => !disposed && ticket === revision
  const change = patch => {
    state = Object.assign({}, state, patch)
    state.canToggleAvatar = !!resources && !state.saving && AVATAR_TOGGLE_STATUSES.includes(state.status)
    if (!disposed && options.onChange) options.onChange(Object.assign({}, state))
  }
  const invoke = (method, args = {}) => new Promise((resolve, reject) => {
    wxApi[method](Object.assign({}, args, { success: resolve, fail: reject }))
  })
  // 诊断通道不能改变保存结果；取消属于用户操作，不作为异常上传。
  const logSaveFailure = (stage, error) => {
    const rawMessage = String(error && (error.errMsg || error.message) || '')
    if (/cancel/i.test(rawMessage)) return
    const code = error && error.errCode
    const errno = error && error.errno
    const details = {
      stage,
      errMsg: safeDiagnosticMessage(rawMessage),
      errCode: typeof code === 'number' && Number.isFinite(code) ? code
        : typeof code === 'string' && /^-?\d{1,16}$/.test(code) ? code : null,
      errno: typeof errno === 'number' && Number.isFinite(errno) ? errno : null
    }
    try { consoleApi.error(SAVE_FAILURE_LOG, details) } catch (logError) { /* 控制台不可用时仍尝试实时日志。 */ }
    try {
      const logger = wxApi.getRealtimeLogManager && wxApi.getRealtimeLogManager()
      if (logger && typeof logger.error === 'function') logger.error(SAVE_FAILURE_LOG, details)
    } catch (logError) { /* 旧基础库或日志管理器失败不影响保存失败的原有处理。 */ }
  }
  const invokeSaveStage = async (stage, args, ticket) => {
    try { return await invoke(stage, args) }
    catch (error) {
      if (active(ticket)) logSaveFailure(stage, error)
      throw error
    }
  }
  const toast = title => wxApi.showToast({ title, icon: 'none' })
  const apiPath = () => `${API_PREFIXES[target.ownerType]}/${target.portfolioId}`
  const failGeneration = error => {
    change({ status: 'error', codeUrl: '', tempFilePath: '', displayFilePath: '', errorMessage: error.message || '小程序码生成失败，请稍后重试' })
    if (error.authRequired && options.onAuthRequired) options.onAuthRequired(error.message)
  }

  async function generate(ticket) {
    try {
      const result = await requestFn({ url: `${apiPath()}/miniapp-code`, method: 'POST' })
      if (!active(ticket)) return false
      if (!result || !result.codeUrl || !result.contentVersion || result.width !== 1080 || result.height !== 1440 || result.codeSize !== 800 || result.avatarSize !== 120) throw new Error('小程序码生成失败，请稍后重试')
      const unchanged = resources && result.contentVersion === resources.contentVersion
      resources = Object.assign({}, result, { ownerType: target.ownerType })
      validatedAt = now()
      change({ codeUrl: result.codeUrl, tempFilePath: unchanged ? state.tempFilePath : '', errorMessage: '' })
      return true
    } catch (error) {
      if (active(ticket)) { resources = null; failGeneration(error) }
      return false
    }
  }

  // 微信可随时回收临时文件；版本相同并不表示本地成图仍然存在。
  function fileExists(path) {
    return new Promise(resolve => {
      try { wxApi.getFileSystemManager().access({ path, success: () => resolve(true), fail: () => resolve(false) }) }
      catch (error) { resolve(false) }
    })
  }

  async function downloadResource(url, ticket) {
    if (downloads.has(url)) {
      const path = downloads.get(url)
      const exists = await fileExists(path)
      if (!active(ticket)) return ''
      if (exists) return path
      downloads.delete(url)
    }
    const file = await invoke('downloadFile', { url })
    if (!active(ticket)) return ''
    if (file.statusCode !== 200 || !file.tempFilePath) throw Object.assign(new Error(DOWNLOAD_ERROR), { statusCode: file.statusCode })
    downloads.set(url, file.tempFilePath)
    return file.tempFilePath
  }

  async function compose(ticket, refreshRemaining = 1) {
    if (!active(ticket)) return false
    if (state.tempFilePath) {
      change({ status: 'checking' })
      const exists = await fileExists(state.tempFilePath)
      if (!active(ticket)) return false
      if (exists) { change({ status: 'ready' }); return true }
      change({ tempFilePath: '' })
    }
    change({ status: 'downloading', errorMessage: '' })
    let stage = 'download-error'
    try {
      const codePath = await downloadResource(resources.codeUrl, ticket)
      if (!active(ticket)) return false
      // 默认完整保留微信原码，只有主动打开开关才在本机读取头像。
      const useAvatar = state.useAvatar
      const avatarPath = useAvatar
        ? (resources.avatarUrl ? await downloadResource(resources.avatarUrl, ticket) : DEFAULT_AVATARS[target.ownerType])
        : ''
      if (!active(ticket)) return false
      stage = 'render-error'
      change({ status: 'rendering' })
      const tempFilePath = await renderFn({ resources, codePath, avatarPath, useAvatar, isActive: () => active(ticket) })
      if (!active(ticket)) return false
      if (!tempFilePath) throw new Error('图片绘制失败，请重试')
      change({ status: 'ready', tempFilePath, displayFilePath: tempFilePath })
      return true
    } catch (error) {
      if (active(ticket)) {
        // 只有明确的资源缺失才重新鉴权取地址；每次用户操作最多刷新一次，避免循环请求。
        if (stage === 'download-error' && RESOURCE_MISSING_STATUSES.includes(error.statusCode) && refreshRemaining > 0) {
          change({ status: 'generating' })
          if (!await generate(ticket)) return false
          return compose(ticket, refreshRemaining - 1)
        }
        // 临时素材可能被微信回收，重试仅重新下载损坏的资源，不重新请求微信码。
        if (error.imagePath) for (const [url, path] of downloads) { if (path === error.imagePath) downloads.delete(url) }
        change({ status: stage, errorMessage: stage === 'download-error' ? DOWNLOAD_ERROR : (error.message || '图片绘制失败，请重试') })
      }
      return false
    }
  }

  async function load(ticket, regenerate) {
    if (regenerate) {
      change({ status: 'generating', errorMessage: '' })
      if (!await generate(ticket)) return
    }
    if (active(ticket)) await compose(ticket)
  }

  async function permissionSettings(ticket) {
    const choice = await invoke('showModal', {
      title: '相册权限', content: '需要相册权限才能保存小程序码', cancelText: '暂不', confirmText: '去设置'
    })
    if (active(ticket) && choice.confirm) await invokeSaveStage('openSetting', {}, ticket)
    // 设置返回后等待用户再次主动点击，不自动继续保存或循环弹窗。
  }

  return {
    start(input) {
      if (disposed) return Promise.resolve()
      revision += 1
      target = normalizeTarget(input)
      resources = null
      downloads = new Map()
      change({ status: 'idle', saving: false, useAvatar: false, codeUrl: '', tempFilePath: '', displayFilePath: '', errorMessage: '' })
      if (!target) {
        change({ status: 'error', errorMessage: '作品集参数无效' })
        return Promise.resolve()
      }
      return load(revision, true)
    },
    setUseAvatar(value) {
      const useAvatar = value === true
      if (disposed || !state.canToggleAvatar || useAvatar === state.useAvatar) return Promise.resolve()
      const ticket = ++revision
      // 旧图只留在卡片中展示；清空可保存文件，避免生成期间保存与开关不一致的图片。
      change({ useAvatar, status: 'downloading', tempFilePath: '', errorMessage: '' })
      return compose(ticket)
    },
    retry() {
      if (disposed || !target || state.saving || !['error', 'download-error', 'render-error'].includes(state.status)) return Promise.resolve()
      return load(revision, !resources)
    },
    async preview() {
      if (disposed || state.status !== 'ready' || state.saving || !state.tempFilePath) return
      const ticket = revision
      if (!await compose(ticket) || !active(ticket)) return
      wxApi.previewImage({
        current: state.tempFilePath, urls: [state.tempFilePath],
        fail(error) {
          if (!active(ticket)) return
          if (/no such file|file.*not exist/i.test(String(error.errMsg || ''))) change({ status: 'render-error', tempFilePath: '', errorMessage: LOCAL_FILE_ERROR })
          else toast('预览失败，请重试')
        }
      })
    },
    async save() {
      if (disposed || state.status !== 'ready' || state.saving) return
      const ticket = revision
      const recordUrl = `${apiPath()}/share-records`
      change({ saving: true })
      try {
        if (now() - validatedAt >= REVALIDATE_AFTER_MS) {
          if (!await generate(ticket)) return
        }
        if (!await compose(ticket) || !active(ticket)) return
        const settings = await invokeSaveStage('getSetting', {}, ticket)
        if (!active(ticket)) return
        if ((settings.authSetting || {})[ALBUM_SCOPE] === false) {
          await permissionSettings(ticket)
          return
        }
        // 首次授权由用户点击后的原生保存动作触发，不在进入页面时提前请求。
        await invokeSaveStage('saveImageToPhotosAlbum', { filePath: state.tempFilePath }, ticket)
        // 原生保存成功后，即使页面已卸载也记录这次动作；捕获的地址不会随目标变化。
        // Promise 只结算一次，记录失败不影响保存结果，页面提示仍受生命周期约束。
        Promise.resolve().then(() => requestFn({ url: recordUrl, method: 'POST', data: { shareChannel: 'QR_CODE', shareScene: 'PORTFOLIO_QR_SAVE' } })).catch(() => {})
        if (active(ticket)) toast('已保存到相册')
      } catch (error) {
        if (!active(ticket)) return
        const message = String(error.errMsg || error.message || '')
        // 平台隐私声明缺失只能由管理方处理，用户进入权限设置无法解决。
        if (message.trim() === UNDECLARED_ALBUM_PRIVACY_ERROR) {
          toast(ALBUM_UNAVAILABLE_MESSAGE)
        } else if (/auth deny|auth denied|authorize.*fail/i.test(message)) {
          try { await permissionSettings(ticket) } catch (settingError) { if (active(ticket)) toast('相册权限设置未完成，请重试') }
        } else if (/no such file|file.*not exist/i.test(message)) {
          change({ status: 'render-error', tempFilePath: '', errorMessage: LOCAL_FILE_ERROR })
        } else {
          toast(/cancel/i.test(message) ? '已取消保存' : '保存失败，请重试')
        }
      } finally {
        if (active(ticket)) change({ saving: false })
      }
    },
    dispose() {
      disposed = true
      revision += 1
      target = null
      resources = null
      downloads.clear()
      state = { status: 'idle', saving: false, useAvatar: false, canToggleAvatar: false, codeUrl: '', tempFilePath: '', displayFilePath: '', errorMessage: '' }
    }
  }
}

module.exports = { buildPortfolioMiniappCodePath, createPortfolioMiniappCodeController }

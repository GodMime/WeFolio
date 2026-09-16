const COMPRESSION_CANCELLED = 'MEDIA_COMPRESSION_CANCELLED'
const COMPRESSION_TIMEOUT = 'MEDIA_COMPRESSION_TIMEOUT'
const COMPRESSION_BUSY = 'MEDIA_COMPRESSION_BUSY'
const METADATA_TIMEOUT_MS = 10 * 1000
const IMAGE_ATTEMPT_TIMEOUT_MS = 30 * 1000
const IMAGE_TOTAL_TIMEOUT_MS = 120 * 1000
const VIDEO_ATTEMPT_TIMEOUT_MS = 10 * 60 * 1000
const VIDEO_TOTAL_TIMEOUT_MS = 20 * 60 * 1000
const ENCODING_LEASE_MULTIPLIER = 2

const COMPRESSION_CANCELLED_MESSAGE = '已取消处理'
const COMPRESSION_TIMEOUT_MESSAGE = '媒体处理超时'
const COMPRESSION_BUSY_MESSAGE = '上一次媒体处理尚未结束，请稍后重试'
const GENERATED_FILE_MISSING_MESSAGE = '媒体处理未返回生成文件'
const NATIVE_METHOD_MISSING_MESSAGE = '当前微信版本不支持该媒体处理能力'
const INVALID_NOMINAL_TIMEOUT_MESSAGE = '编码调用必须提供完整的名义超时时间'
const LEASE_EXPIRED_LOG_MESSAGE = '媒体编码调用超过租约期限，已释放运行时锁'
const NATIVE_SETTLED_CALLBACK_ERROR_MESSAGE = '媒体处理原生收尾回调执行失败'
const MEDIA_FAILURE_MESSAGE = '媒体处理失败'
const MEDIA_CHOOSE_FAILURE_MESSAGE = '选择作品失败'
const NATIVE_FAILURE_MESSAGES = {
  chooseMedia: MEDIA_CHOOSE_FAILURE_MESSAGE,
  getVideoInfo: '视频信息读取失败',
  compressVideo: '视频压缩失败',
  getImageInfo: '图片信息读取失败',
  compressImage: '图片压缩失败',
  canvasToTempFilePath: '图片导出失败'
}

const encodingOwners = new WeakMap()

function createRuntimeError(code, message) {
  return Object.assign(new Error(message), { code })
}

function createCancelledError() {
  return createRuntimeError(COMPRESSION_CANCELLED, COMPRESSION_CANCELLED_MESSAGE)
}

function createTimeoutError() {
  return createRuntimeError(COMPRESSION_TIMEOUT, COMPRESSION_TIMEOUT_MESSAGE)
}

function createBusyError() {
  return createRuntimeError(COMPRESSION_BUSY, COMPRESSION_BUSY_MESSAGE)
}

function logRuntimeEvent(message) {
  if (typeof console !== 'undefined' && typeof console.warn === 'function') {
    console.warn(message)
  }
}

function isPositiveFiniteNumber(value) {
  return Number.isFinite(Number(value)) && Number(value) > 0
}

function normalizeGeneratedPath(response = {}) {
  const path = response.tempFilePath || response.filePath
  return typeof path === 'string' ? path.trim() : ''
}

function nativeErrorMessage(error) {
  if (typeof error === 'string') return error.trim()
  if (!error) return ''
  return String(error.errMsg || error.message || '').trim()
}

function normalizeNativeError(error, method) {
  // 微信 fail 常返回普通对象，必须补齐 message，否则页面只剩通用失败提示。
  const normalized = Object.assign(new Error(nativeErrorMessage(error) || MEDIA_FAILURE_MESSAGE),
    error && typeof error === 'object' ? error : {}, { cause: error })
  if (typeof method === 'string') normalized.nativeMethod = method
  return normalized
}

function buildMediaErrorMessage(error, fallback = MEDIA_CHOOSE_FAILURE_MESSAGE) {
  const rawMessage = nativeErrorMessage(error).replace(/\s+/g, ' ')
  const nativeFailure = rawMessage.match(/^(\w+):fail\b\s*(.*)$/i)
  const method = (error && error.nativeMethod) || (nativeFailure && nativeFailure[1])
  const stage = Object.prototype.hasOwnProperty.call(NATIVE_FAILURE_MESSAGES, method)
    ? NATIVE_FAILURE_MESSAGES[method] : ''
  let reason = stage && nativeFailure ? nativeFailure[2] : rawMessage
  // 原生报错可能带文件地址，展示和日志只保留原因，避免泄露本地路径或URL参数。
  reason = reason.replace(/(['"])(?:(?:wxfile|https?|file):\/\/|\/|[A-Za-z]:[\\/]).*?\1/g, '[文件路径]')
    .replace(/(?:wxfile|https?|file):\/\/[^\s,'")]+|[A-Za-z]:[\\/][^\s,'")]+/g, '[文件路径]')
    .replace(/(^|[\s=:(,])\/[^\s,'")]+/g, '$1[文件路径]')
  const message = stage ? (reason ? `${stage}：${reason}` : stage) : (reason || fallback)
  const errno = error && (error.errno !== undefined ? error.errno : error.errCode)
  return errno !== undefined && errno !== null && /^-?\d+$/.test(String(errno))
    ? `${message}（错误码：${errno}）` : message
}

function safeUnlink(wxApi, path) {
  try {
    wxApi.getFileSystemManager().unlinkSync(path)
  } catch (error) {
    // 临时文件清理失败不能覆盖原业务结果，也不能阻断其他文件清理。
  }
}

function releaseEncodingOwner(wxApi, ownerToken) {
  const currentOwner = encodingOwners.get(wxApi)
  if (!currentOwner || currentOwner.ownerToken !== ownerToken) {
    return false
  }
  if (currentOwner.leaseTimer) {
    clearTimeout(currentOwner.leaseTimer)
  }
  encodingOwners.delete(wxApi)
  return true
}

function releaseExpiredEncodingOwner(wxApi, expectedOwner) {
  const currentOwner = encodingOwners.get(wxApi)
  if (!currentOwner || currentOwner.ownerToken !== expectedOwner.ownerToken) {
    return false
  }
  const remainingMs = currentOwner.leaseExpiresAt - Date.now()
  if (remainingMs > 0) {
    currentOwner.leaseTimer = setTimeout(() => {
      releaseExpiredEncodingOwner(wxApi, currentOwner)
    }, remainingMs)
    return false
  }
  if (currentOwner.leaseTimer) {
    clearTimeout(currentOwner.leaseTimer)
  }
  encodingOwners.delete(wxApi)
  logRuntimeEvent(LEASE_EXPIRED_LOG_MESSAGE)
  return true
}

function acquireEncodingOwner(wxApi, ownerToken, nominalTimeoutMs) {
  const currentOwner = encodingOwners.get(wxApi)
  if (currentOwner) {
    if (Date.now() < currentOwner.leaseExpiresAt) {
      throw createBusyError()
    }
    releaseExpiredEncodingOwner(wxApi, currentOwner)
  }

  const nativeStartedAt = Date.now()
  const leaseExpiresAt = nativeStartedAt
    + ENCODING_LEASE_MULTIPLIER * Number(nominalTimeoutMs)
  const owner = {
    ownerToken,
    nativeStartedAt,
    leaseExpiresAt,
    leaseTimer: null
  }
  encodingOwners.set(wxApi, owner)
  owner.leaseTimer = setTimeout(() => {
    releaseExpiredEncodingOwner(wxApi, owner)
  }, leaseExpiresAt - nativeStartedAt)
}

function resolveNativeMethod(wxApi, method) {
  if (typeof method === 'function') {
    return method
  }
  if (typeof method === 'string' && typeof wxApi[method] === 'function') {
    return wxApi[method].bind(wxApi)
  }
  throw new Error(NATIVE_METHOD_MISSING_MESSAGE)
}

function createCompressionSession({ wxApi, protectedPaths = [] }) {
  if (!wxApi || (typeof wxApi !== 'object' && typeof wxApi !== 'function')) {
    throw new TypeError('wxApi必须是有效的微信运行时对象')
  }

  const protectedPathSet = new Set(protectedPaths)
  const ownedPaths = new Set()
  const cancelListeners = new Set()
  let active = true

  function discard(path) {
    if (!ownedPaths.has(path)) {
      return false
    }
    ownedPaths.delete(path)
    safeUnlink(wxApi, path)
    return true
  }

  function disposeOwnedPaths() {
    Array.from(ownedPaths).forEach(discard)
  }

  function assertActive() {
    if (!active) {
      throw createCancelledError()
    }
  }

  function call(method, args = {}, options = {}) {
    let invoke
    const timeoutMs = Number(options.timeoutMs)
    const nominalTimeoutMs = Number(options.nominalTimeoutMs)
    const encoding = options.encoding === true
    const createsFile = options.createsFile === true
    const onNativeSettled = options.onNativeSettled
    const ownerToken = {}

    try {
      assertActive()
      invoke = resolveNativeMethod(wxApi, method)
      if (!isPositiveFiniteNumber(timeoutMs)) {
        throw createTimeoutError()
      }
      if (encoding && !isPositiveFiniteNumber(nominalTimeoutMs)) {
        throw new TypeError(INVALID_NOMINAL_TIMEOUT_MESSAGE)
      }
      if (encoding) {
        acquireEncodingOwner(wxApi, ownerToken, nominalTimeoutMs)
      }
    } catch (error) {
      return Promise.reject(error)
    }

    return new Promise((resolve, reject) => {
      let businessSettled = false
      let nativeSettled = false
      let waitTimer = null

      function settleBusiness(callback, value) {
        if (businessSettled) {
          return false
        }
        businessSettled = true
        if (waitTimer) {
          clearTimeout(waitTimer)
          waitTimer = null
        }
        cancelListeners.delete(handleCancel)
        callback(value)
        return true
      }

      function notifyNativeSettled() {
        if (typeof onNativeSettled !== 'function') {
          return
        }
        try {
          onNativeSettled()
        } catch (error) {
          logRuntimeEvent(NATIVE_SETTLED_CALLBACK_ERROR_MESSAGE)
        }
      }

      function finishNative() {
        if (nativeSettled) {
          return false
        }
        nativeSettled = true
        if (encoding) {
          releaseEncodingOwner(wxApi, ownerToken)
        }
        notifyNativeSettled()
        return true
      }

      function handleCancel() {
        settleBusiness(reject, createCancelledError())
      }

      function handleSuccess(response = {}) {
        if (!finishNative()) {
          return
        }

        let generatedPath = ''
        if (createsFile) {
          generatedPath = normalizeGeneratedPath(response)
          if (!generatedPath) {
            settleBusiness(reject, new Error(GENERATED_FILE_MISSING_MESSAGE))
            return
          }
          if (!protectedPathSet.has(generatedPath)) {
            ownedPaths.add(generatedPath)
          }
        }

        if (!active || businessSettled) {
          if (generatedPath) {
            discard(generatedPath)
          }
          return
        }
        settleBusiness(resolve, response)
      }

      function handleFail(error) {
        if (!finishNative()) {
          return
        }
        settleBusiness(reject, normalizeNativeError(error, method))
      }

      cancelListeners.add(handleCancel)
      waitTimer = setTimeout(() => {
        settleBusiness(reject, createTimeoutError())
      }, timeoutMs)

      try {
        invoke(Object.assign({}, args, {
          success: handleSuccess,
          fail: handleFail
        }))
      } catch (error) {
        handleFail(error)
      }
    })
  }

  function cancel() {
    if (!active) {
      return
    }
    active = false
    Array.from(cancelListeners).forEach(listener => listener())
    disposeOwnedPaths()
  }

  function transfer(paths = []) {
    const transferred = []
    for (const path of paths) {
      if (!ownedPaths.has(path)) {
        continue
      }
      ownedPaths.delete(path)
      transferred.push(path)
    }
    return transferred
  }

  function dispose() {
    if (active) {
      cancel()
      return
    }
    disposeOwnedPaths()
  }

  return {
    call,
    assertActive,
    cancel,
    discard,
    transfer,
    dispose
  }
}

function releasePreparedWorkFiles({ wxApi, ownedPathsByClientId, clientIds } = {}) {
  if (!wxApi || !ownedPathsByClientId || typeof ownedPathsByClientId !== 'object') {
    return
  }
  const selectedClientIds = clientIds === undefined
    ? Object.keys(ownedPathsByClientId)
    : Array.from(new Set(clientIds))
  for (const clientId of selectedClientIds) {
    const paths = Array.isArray(ownedPathsByClientId[clientId])
      ? ownedPathsByClientId[clientId]
      : []
    delete ownedPathsByClientId[clientId]
    Array.from(new Set(paths)).forEach(path => {
      if (typeof path === 'string' && path) {
        safeUnlink(wxApi, path)
      }
    })
  }
}

module.exports = {
  COMPRESSION_BUSY,
  COMPRESSION_BUSY_MESSAGE,
  COMPRESSION_CANCELLED,
  COMPRESSION_CANCELLED_MESSAGE,
  COMPRESSION_TIMEOUT,
  COMPRESSION_TIMEOUT_MESSAGE,
  ENCODING_LEASE_MULTIPLIER,
  IMAGE_ATTEMPT_TIMEOUT_MS,
  IMAGE_TOTAL_TIMEOUT_MS,
  METADATA_TIMEOUT_MS,
  VIDEO_ATTEMPT_TIMEOUT_MS,
  VIDEO_TOTAL_TIMEOUT_MS,
  buildMediaErrorMessage,
  createCompressionSession,
  releasePreparedWorkFiles
}

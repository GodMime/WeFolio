const { requestWithVisitorSessionRefresh } = require('../../../utils/visitor-session')
const { isRemoteUrl } = require('../../../utils/upload-file')

const VISITOR_AVATAR_MAX_SIZE_BYTES = 200 * 1024
const VISITOR_AVATAR_UPLOAD_TIMEOUT = 10 * 60 * 1000
const DEFAULT_IMAGE_MIME_TYPE = 'image/jpeg'
const PNG_MIME_TYPE = 'image/png'
const WEBP_MIME_TYPE = 'image/webp'
const AVATAR_COMPRESS_ATTEMPTS = [
  { quality: 85, compressedSize: 720 },
  { quality: 78, compressedSize: 640 },
  { quality: 70, compressedSize: 560 },
  { quality: 62, compressedSize: 480 },
  { quality: 54, compressedSize: 420 }
]

function getRuntimeWx(wxApi) {
  if (wxApi) {
    return wxApi
  }
  if (typeof wx !== 'undefined') {
    return wx
  }
  throw new Error('wx 运行环境不可用')
}

function trimText(value) {
  return String(value || '').trim()
}

function getLocalFileSize(filePath, wxApi) {
  try {
    const runtimeWx = getRuntimeWx(wxApi)
    const fileSystemManager = runtimeWx.getFileSystemManager()
    const stats = fileSystemManager.statSync(filePath)
    return stats && typeof stats.size === 'number' ? stats.size : 0
  } catch (error) {
    return 0
  }
}

function mimeTypeFromPath(filePath) {
  const normalized = trimText(filePath)
  if (/\.png($|\?)/i.test(normalized)) {
    return PNG_MIME_TYPE
  }
  if (/\.webp($|\?)/i.test(normalized)) {
    return WEBP_MIME_TYPE
  }
  return DEFAULT_IMAGE_MIME_TYPE
}

function compressImageFile(filePath, attempt, options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  if (!runtimeWx.compressImage) {
    return Promise.reject(new Error('当前微信版本不支持图片压缩'))
  }
  return new Promise((resolve, reject) => {
    runtimeWx.compressImage({
      src: filePath,
      quality: attempt.quality,
      compressedWidth: attempt.compressedSize,
      success(response) {
        if (response && response.tempFilePath) {
          resolve(response.tempFilePath)
          return
        }
        reject(new Error('访客头像压缩失败'))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '访客头像压缩失败'))
      }
    })
  })
}

async function prepareVisitorAvatarFile(filePath, options = {}) {
  const normalizedPath = trimText(filePath)
  if (!normalizedPath || isRemoteUrl(normalizedPath)) {
    return {
      filePath: normalizedPath,
      fileSize: 0,
      mimeType: ''
    }
  }

  const originalSize = getLocalFileSize(normalizedPath, options.wxApi)
  if (originalSize > 0 && originalSize <= VISITOR_AVATAR_MAX_SIZE_BYTES) {
    return {
      filePath: normalizedPath,
      fileSize: originalSize,
      mimeType: mimeTypeFromPath(normalizedPath)
    }
  }

  for (const attempt of AVATAR_COMPRESS_ATTEMPTS) {
    const compressedPath = await compressImageFile(normalizedPath, attempt, options)
    const compressedSize = getLocalFileSize(compressedPath, options.wxApi)
    if (compressedSize > 0 && compressedSize <= VISITOR_AVATAR_MAX_SIZE_BYTES) {
      return {
        filePath: compressedPath,
        fileSize: compressedSize,
        mimeType: mimeTypeFromPath(compressedPath)
      }
    }
  }
  throw new Error('访客头像不能超过 200KB')
}

function uploadVisitorAvatarToCos(filePath, ticket, options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  return new Promise((resolve, reject) => {
    runtimeWx.uploadFile({
      url: ticket.uploadUrl,
      filePath,
      name: 'file',
      formData: ticket.formData || {},
      timeout: options.timeout || VISITOR_AVATAR_UPLOAD_TIMEOUT,
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve()
          return
        }
        reject(new Error(`访客头像上传失败(${response.statusCode})`))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '访客头像上传失败'))
      }
    })
  })
}

async function uploadVisitorAvatarProfile(payload = {}, options = {}) {
  const shareCode = trimText(payload.shareCode)
  const visitorProfileToken = trimText(payload.visitorProfileToken)
  const nickname = trimText(payload.nickname)
  const avatarFilePath = trimText(payload.avatarFilePath || payload.avatarUrl)
  if (!shareCode) {
    throw new Error('作品集编码不能为空')
  }
  if (!visitorProfileToken) {
    throw new Error('访客资料授权已过期')
  }
  if (!nickname) {
    throw new Error('请填写昵称')
  }
  if (!avatarFilePath) {
    throw new Error('请选择头像')
  }

  let avatarUrl = avatarFilePath
  if (!isRemoteUrl(avatarFilePath)) {
    const prepared = await prepareVisitorAvatarFile(avatarFilePath, options)
    const ticket = await requestWithVisitorSessionRefresh({
      url: `/api/visitor/portfolios/${shareCode}/visitor-avatar/upload-ticket`,
      method: 'POST',
      authMode: 'visitor',
      data: {
        visitorProfileToken,
        mimeType: prepared.mimeType,
        fileSize: prepared.fileSize
      }
    }, {
      shareCode
    })
    await uploadVisitorAvatarToCos(prepared.filePath, ticket, options)
    if (!ticket.publicUrl) {
      throw new Error('访客头像上传成功但未获取到文件地址')
    }
    avatarUrl = ticket.publicUrl
  }

  await requestWithVisitorSessionRefresh({
    url: `/api/visitor/portfolios/${shareCode}/visitor-profile`,
    method: 'PUT',
    authMode: 'visitor',
    data: {
      visitorProfileToken,
      nickname,
      avatarUrl
    }
  }, {
    shareCode
  })
  return avatarUrl
}

module.exports = {
  VISITOR_AVATAR_MAX_SIZE_BYTES,
  prepareVisitorAvatarFile,
  uploadVisitorAvatarProfile,
  uploadVisitorAvatarToCos
}

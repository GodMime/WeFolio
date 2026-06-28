const {
  isRemoteUrl,
  prepareLocalUploadFilePath,
  uploadPreparedFile
} = require('./upload-file')

const AVATAR_MAX_SIZE_BYTES = 200 * 1024
const AVATAR_COMPRESS_QUALITY = 95
const AVATAR_COMPRESSED_SIZE = 512
const AVATAR_UPLOAD_URL = '/api/auth/avatar'
const AVATAR_UPLOAD_MESSAGES = {
  unsupportedCompress: '当前微信版本不支持头像压缩',
  compressFailed: '头像压缩失败',
  tooLarge: '头像文件不能超过 200KB',
  parseFailed: '头像上传响应解析失败',
  uploadFailed: '头像上传失败',
  missingUrl: '头像上传成功但未获取到文件地址'
}

async function prepareAvatarFilePath(filePath, options = {}) {
  return prepareLocalUploadFilePath(filePath, Object.assign({}, options, {
    maxSizeBytes: AVATAR_MAX_SIZE_BYTES,
    compressQuality: AVATAR_COMPRESS_QUALITY,
    compressedSize: AVATAR_COMPRESSED_SIZE,
    messages: AVATAR_UPLOAD_MESSAGES
  }))
}

async function uploadAvatar(filePath, options = {}) {
  if (!filePath || isRemoteUrl(filePath)) {
    return filePath || ''
  }

  const preparedFilePath = options.skipPrepare
    ? filePath
    : await prepareAvatarFilePath(filePath, options)
  return uploadPreparedFile(preparedFilePath, AVATAR_UPLOAD_URL, options, AVATAR_UPLOAD_MESSAGES)
}

module.exports = {
  AVATAR_MAX_SIZE_BYTES,
  prepareAvatarFilePath,
  uploadAvatar
}

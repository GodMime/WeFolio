const {
  isRemoteUrl,
  prepareLocalUploadFilePath,
  uploadPreparedFile
} = require('./upload-file')

const TEAM_AVATAR_MAX_SIZE_BYTES = 200 * 1024
const TEAM_AVATAR_COMPRESS_QUALITY = 95
const TEAM_AVATAR_COMPRESSED_SIZE = 512
const TEAM_AVATAR_UPLOAD_MESSAGES = {
  unsupportedCompress: '当前微信版本不支持团队图标压缩',
  compressFailed: '团队图标压缩失败',
  tooLarge: '团队图标不能超过 200KB',
  parseFailed: '团队图标上传响应解析失败',
  uploadFailed: '团队图标上传失败',
  missingUrl: '团队图标上传成功但未获取到文件地址'
}

async function prepareTeamAvatarFilePath(filePath, options = {}) {
  return prepareLocalUploadFilePath(filePath, Object.assign({}, options, {
    maxSizeBytes: TEAM_AVATAR_MAX_SIZE_BYTES,
    compressQuality: TEAM_AVATAR_COMPRESS_QUALITY,
    compressedSize: TEAM_AVATAR_COMPRESSED_SIZE,
    messages: TEAM_AVATAR_UPLOAD_MESSAGES
  }))
}

async function uploadTeamAvatar(teamId, filePath, options = {}) {
  if (!filePath || isRemoteUrl(filePath)) {
    return filePath || ''
  }
  if (!teamId) {
    throw new Error('团队 ID 不能为空')
  }

  const preparedFilePath = await prepareTeamAvatarFilePath(filePath, options)
  return uploadPreparedFile(
    preparedFilePath,
    `/api/mine/teams/${teamId}/avatar`,
    options,
    TEAM_AVATAR_UPLOAD_MESSAGES
  )
}

module.exports = {
  TEAM_AVATAR_MAX_SIZE_BYTES,
  prepareTeamAvatarFilePath,
  uploadTeamAvatar
}

const TAG_MAX_COUNT = 10
const TAG_MAX_LENGTH = 10

function trimText(value) {
  return (value || '').trim()
}

function normalizeTags(tags) {
  if (!Array.isArray(tags)) {
    return []
  }
  const seen = {}
  return tags.reduce((result, tag) => {
    const value = trimText(tag)
    if (!value || seen[value]) {
      return result
    }
    seen[value] = true
    result.push(value)
    return result
  }, [])
}

function buildDisplayName(profile) {
  const displayName = trimText(profile.displayName)
  if (displayName) {
    return displayName
  }
  const nickname = trimText(profile.nickname)
  const profession = trimText(profile.profession)
  if (!nickname && !profession) {
    return '微信用户'
  }
  if (!profession) {
    return nickname
  }
  if (!nickname) {
    return profession
  }
  return `${nickname} · ${profession}`
}

function normalizeProfile(raw = {}) {
  const tags = normalizeTags(raw.tags)
  const profile = {
    userId: raw.userId || null,
    uniqueCode: raw.uniqueCode || '-',
    displayName: buildDisplayName(raw),
    nickname: raw.nickname || '',
    avatarUrl: raw.avatarUrl || '',
    profession: raw.profession || '',
    city: raw.city || '',
    intro: raw.intro || '',
    tags
  }
  profile.tagCountText = `${tags.length} / ${TAG_MAX_COUNT}`
  return profile
}

function trimTags(tags) {
  return Array.isArray(tags) ? tags.map(trimText) : []
}

function buildProfilePayload(form = {}) {
  return {
    nickname: trimText(form.nickname),
    avatarUrl: trimText(form.avatarUrl),
    profession: trimText(form.profession),
    city: trimText(form.city),
    intro: trimText(form.intro),
    tags: trimTags(form.tags).filter(Boolean)
  }
}

function validateLength(value, maxLength, fieldName) {
  if (trimText(value).length > maxLength) {
    return `${fieldName}不能超过 ${maxLength} 个字`
  }
  return ''
}

function validateProfileForm(form = {}) {
  const fieldError = validateLength(form.nickname, 50, '姓名 / 艺名') ||
    validateLength(form.avatarUrl, 512, '头像地址') ||
    validateLength(form.profession, 50, '职业身份') ||
    validateLength(form.city, 50, '服务城市') ||
    validateLength(form.intro, 500, '个人简介')

  if (fieldError) {
    return {
      valid: false,
      message: fieldError
    }
  }

  const tags = trimTags(form.tags)
  if (tags.length > TAG_MAX_COUNT) {
    return {
      valid: false,
      message: '标签最多保留 10 个'
    }
  }

  const seen = {}
  for (const tag of tags) {
    if (!tag) {
      return {
        valid: false,
        message: '标签不能为空'
      }
    }
    if (tag.length > TAG_MAX_LENGTH) {
      return {
        valid: false,
        message: '单个标签不能超过 10 个字'
      }
    }
    if (seen[tag]) {
      return {
        valid: false,
        message: '标签不能重复'
      }
    }
    seen[tag] = true
  }

  return {
    valid: true,
    message: ''
  }
}

module.exports = {
  TAG_MAX_COUNT,
  TAG_MAX_LENGTH,
  buildProfilePayload,
  normalizeProfile,
  validateProfileForm
}

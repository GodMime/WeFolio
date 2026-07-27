const TAG_MAX_COUNT = 10
const TAG_MAX_LENGTH = 10
const PROFILE_FIELD_LIMITS = {
  nickname: 50,
  profession: 50,
  city: 50,
  intro: 500
}
const DEFAULT_TAG_COLOR = '#0f766e'
const TAG_COLOR_OPTIONS = [
  {
    name: '青绿',
    color: '#0f766e',
    background: '#dcf7f1',
    border: '#a7eadc',
    removeBackground: 'rgba(15, 118, 110, 0.12)'
  },
  {
    name: '湖蓝',
    color: '#2d5f9a',
    background: '#e5effb',
    border: '#bfd7f4',
    removeBackground: 'rgba(45, 95, 154, 0.12)'
  },
  {
    name: '琥珀',
    color: '#8a4b09',
    background: '#fff0d7',
    border: '#f5d29b',
    removeBackground: 'rgba(138, 75, 9, 0.12)'
  },
  {
    name: '玫红',
    color: '#a9354f',
    background: '#fde7ed',
    border: '#f5bfcc',
    removeBackground: 'rgba(169, 53, 79, 0.12)'
  },
  {
    name: '紫藤',
    color: '#6d5bd0',
    background: '#eeeafd',
    border: '#d2c9fa',
    removeBackground: 'rgba(109, 91, 208, 0.12)'
  },
  {
    name: '森绿',
    color: '#3f6f45',
    background: '#e6f3e8',
    border: '#bfdcc4',
    removeBackground: 'rgba(63, 111, 69, 0.12)'
  },
  {
    name: '墨蓝',
    color: '#36516e',
    background: '#e7edf4',
    border: '#c6d3e2',
    removeBackground: 'rgba(54, 81, 110, 0.12)'
  },
  {
    name: '砖红',
    color: '#9a4a35',
    background: '#f8e8e2',
    border: '#e9c2b5',
    removeBackground: 'rgba(154, 74, 53, 0.12)'
  },
  {
    name: '石墨',
    color: '#4b5563',
    background: '#eef2f6',
    border: '#d5dce5',
    removeBackground: 'rgba(75, 85, 99, 0.12)'
  }
].map((item) => Object.assign({}, item, {
  swatchStyle: `background: ${item.color};`,
  choiceStyle: `color: ${item.color}; background: ${item.background}; border-color: ${item.border};`
}))

const TAG_COLOR_MAP = TAG_COLOR_OPTIONS.reduce((result, item) => {
  result[item.color] = item
  return result
}, {})

function trimText(value) {
  return (value || '').trim()
}

function countText(value) {
  return Array.from(String(value || '')).length
}

function buildProfileFieldCounters(form = {}) {
  return Object.keys(PROFILE_FIELD_LIMITS).reduce((result, field) => {
    result[field] = `${countText(form[field])} / ${PROFILE_FIELD_LIMITS[field]}`
    return result
  }, {})
}

function getTagContent(tag) {
  if (tag && typeof tag === 'object') {
    return trimText(tag.content || tag.text || tag.name)
  }
  return trimText(tag)
}

function getTagColor(tag) {
  if (tag && typeof tag === 'object') {
    return trimText(tag.color).toLowerCase() || DEFAULT_TAG_COLOR
  }
  return DEFAULT_TAG_COLOR
}

function isValidTagColor(color) {
  return Boolean(TAG_COLOR_MAP[trimText(color).toLowerCase()])
}

function getTagColorOption(color) {
  return TAG_COLOR_MAP[trimText(color).toLowerCase()] || TAG_COLOR_MAP[DEFAULT_TAG_COLOR]
}

function createProfileTag(content, color) {
  const option = getTagColorOption(color)
  return {
    content: trimText(content),
    color: option.color,
    colorName: option.name,
    style: `color: ${option.color}; background: #ffffff; border-color: ${option.color};`,
    dotStyle: `background: ${option.color};`,
    removeStyle: 'color: #adb5bd; background: #ffffff; border-color: #e9ecef;'
  }
}

function normalizeTags(tags) {
  if (!Array.isArray(tags)) {
    return []
  }
  const seen = {}
  return tags.reduce((result, tag) => {
    const value = getTagContent(tag)
    if (!value || seen[value]) {
      return result
    }
    seen[value] = true
    result.push(createProfileTag(value, getTagColor(tag)))
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
    wechatQrUrl: raw.wechatQrUrl || '',
    profession: raw.profession || '',
    city: raw.city || '',
    intro: raw.intro || '',
    tags
  }
  profile.tagCountText = `${tags.length} / ${TAG_MAX_COUNT}`
  return profile
}

function trimTags(tags) {
  if (!Array.isArray(tags)) {
    return []
  }
  return tags.map((tag) => ({
    content: getTagContent(tag),
    color: getTagColor(tag)
  }))
}

function buildProfilePayload(form = {}) {
  return {
    nickname: trimText(form.nickname),
    avatarUrl: trimText(form.avatarUrl),
    wechatQrUrl: trimText(form.wechatQrUrl),
    profession: trimText(form.profession),
    city: trimText(form.city),
    intro: trimText(form.intro),
    tags: trimTags(form.tags)
      .filter((tag) => tag.content)
      .map((tag) => ({
        content: tag.content,
        color: getTagColorOption(tag.color).color
      }))
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
    validateLength(form.wechatQrUrl, 512, '微信二维码地址') ||
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
    if (!tag.content) {
      return {
        valid: false,
        message: '标签不能为空'
      }
    }
    if (tag.content.length > TAG_MAX_LENGTH) {
      return {
        valid: false,
        message: '单个标签不能超过 10 个字'
      }
    }
    if (!isValidTagColor(tag.color)) {
      return {
        valid: false,
        message: '请选择有效的标签颜色'
      }
    }
    if (seen[tag.content]) {
      return {
        valid: false,
        message: '标签不能重复'
      }
    }
    seen[tag.content] = true
  }

  return {
    valid: true,
    message: ''
  }
}

module.exports = {
  DEFAULT_TAG_COLOR,
  PROFILE_FIELD_LIMITS,
  TAG_MAX_COUNT,
  TAG_COLOR_OPTIONS,
  TAG_MAX_LENGTH,
  buildProfileFieldCounters,
  buildProfilePayload,
  createProfileTag,
  normalizeProfile,
  validateProfileForm
}

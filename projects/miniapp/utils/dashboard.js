const DEFAULT_PROFILE_SUBTITLE = '完善资料后展示服务区域和标签'

function toNumber(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function toDisplayText(value) {
  return String(toNumber(value))
}

function normalizeTags(tags) {
  if (!Array.isArray(tags)) {
    return []
  }
  return tags
    .map((item) => String(item || '').trim())
    .filter(Boolean)
}

function buildSubtitle(profile, tags) {
  const parts = [
    profile.city,
    ...tags.slice(0, 2)
  ]
    .map((item) => String(item || '').trim())
    .filter(Boolean)

  return parts.length > 0 ? parts.join(' / ') : DEFAULT_PROFILE_SUBTITLE
}

function normalizeDashboard(raw = {}) {
  const profile = raw.profile || {}
  const point = raw.point || {}
  const metrics = raw.metrics || {}
  const tags = normalizeTags(profile.tags)

  return {
    profile: {
      userId: profile.userId || null,
      uniqueCode: profile.uniqueCode || '-',
      displayName: profile.displayName || profile.nickname || '微信用户',
      avatarUrl: profile.avatarUrl || '',
      profession: profile.profession || '',
      city: profile.city || '',
      tags,
      subtitle: buildSubtitle(profile, tags)
    },
    point: {
      balance: toNumber(point.balance),
      balanceText: toDisplayText(point.balance),
      todayConsumed: toNumber(point.todayConsumed),
      todayConsumedText: `今日已消耗 ${toDisplayText(point.todayConsumed)} 积分`,
      lowBalance: Boolean(point.lowBalance),
      warningText: point.lowBalance ? '低于 50 提醒' : ''
    },
    metrics: [
      { label: '作品素材', value: toDisplayText(metrics.workCount) },
      { label: '已发布作品集', value: toDisplayText(metrics.publishedPortfolioCount) },
      { label: '近 7 日访问', value: toDisplayText(metrics.recentVisitCount) }
    ]
  }
}

module.exports = {
  normalizeDashboard
}

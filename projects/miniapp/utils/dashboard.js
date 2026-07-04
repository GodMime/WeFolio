const DEFAULT_PROFILE_SUBTITLE = '完善资料后展示服务区域和标签'
const METRIC_LABEL_WORK = '作品素材'
const METRIC_LABEL_PORTFOLIO = '作品集'
const METRIC_LABEL_RECENT_VISIT = '近 7 日访问'

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
    .map((item) => {
      if (item && typeof item === 'object') {
        return String(item.content || item.text || item.name || '').trim()
      }
      return String(item || '').trim()
    })
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
      { label: METRIC_LABEL_WORK, value: toDisplayText(metrics.workCount) },
      { label: METRIC_LABEL_PORTFOLIO, value: toDisplayText(metrics.publishedPortfolioCount) },
      { label: METRIC_LABEL_RECENT_VISIT, value: toDisplayText(metrics.recentVisitCount) }
    ]
  }
}

module.exports = {
  normalizeDashboard
}

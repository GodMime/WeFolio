const BACKGROUND_AUDIO_STYLES = ['DISC', 'SLEEVE', 'MINI_PLAYER']

// 配置只存三个字段，不保存媒体地址；跨包副本由一致性测试约束。
function normalizeBackgroundAudio(raw = {}) {
  const source = raw && typeof raw === 'object' ? raw : {}
  const workId = Number(source.workId)
  return {
    enabled: source.enabled === true,
    workId: Number.isFinite(workId) && workId > 0 ? workId : null,
    displayStyle: BACKGROUND_AUDIO_STYLES.includes(source.displayStyle) ? source.displayStyle : 'DISC'
  }
}

module.exports = { BACKGROUND_AUDIO_STYLES, normalizeBackgroundAudio }

const VIDEO_AI_LOADING_MS = 1000
const VIDEO_AI_TABS = ['summary', 'audio', 'frames']

function text(value) {
  return typeof value === 'string' ? value : ''
}

function textList(value) {
  return Array.isArray(value) ? value.filter((item) => text(item).trim()) : []
}

function frameTime(timestampMs) {
  const seconds = Math.max(0, Math.floor(Number(timestampMs) / 1000) || 0)
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

// AI 结果只构造展示数据，与作品编辑表单和保存参数隔离。
function normalizeVideoAiContent(raw = {}) {
  const summarySource = raw.summary || {}
  const summary = {
    summary: text(summarySource.summary),
    observations: textList(summarySource.observations),
    limitations: textList(summarySource.limitations)
  }
  const hasSummary = Boolean(summary.summary.trim() || summary.observations.length)
  const audioText = typeof raw.audioText === 'string' ? raw.audioText : null
  const hasAudioText = Boolean(audioText && audioText.trim())
  const frames = (Array.isArray(raw.frames) ? raw.frames : [])
    .filter((frame) => frame && text(frame.url).trim())
    .map((frame, index) => ({
      index,
      timestampMs: frame.timestampMs,
      timeText: frameTime(frame.timestampMs),
      width: frame.width,
      height: frame.height,
      url: frame.url,
      loadFailed: false
    }))
  return {
    hasContent: raw.hasContent === true && (hasSummary || hasAudioText || frames.length > 0),
    hasSummary,
    summary,
    summaryCopyText: [
      summary.summary,
      summary.observations.length ? `观察要点：\n${summary.observations.join('\n')}` : '',
      summary.limitations.length ? `识别局限：\n${summary.limitations.join('\n')}` : ''
    ].filter(Boolean).join('\n\n'),
    audioText,
    hasAudioText,
    audioEmptyText: audioText === null ? '暂未生成音频文字' : '未识别到可转写文字',
    frames
  }
}

module.exports = { VIDEO_AI_LOADING_MS, VIDEO_AI_TABS, normalizeVideoAiContent }

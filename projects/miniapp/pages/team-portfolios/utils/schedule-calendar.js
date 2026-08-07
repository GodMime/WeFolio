const {
  formatLunarDayMeta,
  toLunarDate
} = require('../../../utils/lunar.js')

/**
 * 生成团队作品集月历日期的农历或传统节日文案。
 *
 * @param {string} date 日期，格式为 YYYY-MM-DD
 * @returns {string} 农历日期或传统节日文案
 */
function buildScheduleDayMetaText(date) {
  return formatLunarDayMeta(toLunarDate(date))
}

module.exports = {
  buildScheduleDayMetaText
}

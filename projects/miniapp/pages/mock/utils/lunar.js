const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const LUNAR_BASE_YEAR = 1900
const LUNAR_BASE_DATE_UTC = Date.UTC(1900, 0, 31)
const DAY_MILLISECONDS = 24 * 60 * 60 * 1000
const LUNAR_LEAP_MONTH_MASK = 0x0f
const LUNAR_BIG_LEAP_MONTH_MASK = 0x10000
const LUNAR_MONTH_DAY_MASK_BASE = 0x10000
const LUNAR_YEAR_MONTH_MASK_START = 0x8000
const LUNAR_YEAR_MONTH_MASK_END = 0x08
const LUNAR_MONTH_COUNT = 12
const LUNAR_NORMAL_YEAR_DAYS = 348
const LUNAR_INFO = [
  0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
  0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
  0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
  0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
  0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
  0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5d0, 0x14573, 0x052d0, 0x0a9a8, 0x0e950, 0x06aa0,
  0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
  0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b5a0, 0x195a6,
  0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
  0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0,
  0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
  0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
  0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
  0x05aa0, 0x076a3, 0x096d0, 0x04bd7, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
  0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0
]
const LUNAR_MONTH_TEXT = ['正月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '冬月', '腊月']
const LUNAR_DAY_TEXT = [
  '初一', '初二', '初三', '初四', '初五', '初六', '初七', '初八', '初九', '初十',
  '十一', '十二', '十三', '十四', '十五', '十六', '十七', '十八', '十九', '二十',
  '廿一', '廿二', '廿三', '廿四', '廿五', '廿六', '廿七', '廿八', '廿九', '三十'
]
const LUNAR_FESTIVAL_TEXT = {
  '1-1': '春节',
  '1-15': '元宵节',
  '5-5': '端午节',
  '7-7': '七夕',
  '8-15': '中秋节',
  '9-9': '重阳节',
  '12-8': '腊八'
}
const LUNAR_DATE_CACHE = new Map()

function trimText(value) {
  return String(value || '').trim()
}

function lunarYearInfo(year) {
  return LUNAR_INFO[year - LUNAR_BASE_YEAR] || 0
}

function leapMonth(year) {
  return lunarYearInfo(year) & LUNAR_LEAP_MONTH_MASK
}

function leapMonthDays(year) {
  if (!leapMonth(year)) {
    return 0
  }
  return (lunarYearInfo(year) & LUNAR_BIG_LEAP_MONTH_MASK) ? 30 : 29
}

function lunarMonthDays(year, month) {
  return (lunarYearInfo(year) & (LUNAR_MONTH_DAY_MASK_BASE >> month)) ? 30 : 29
}

function lunarYearDays(year) {
  let days = LUNAR_NORMAL_YEAR_DAYS
  for (let mask = LUNAR_YEAR_MONTH_MASK_START; mask > LUNAR_YEAR_MONTH_MASK_END; mask >>= 1) {
    if (lunarYearInfo(year) & mask) {
      days += 1
    }
  }
  return days + leapMonthDays(year)
}

function parseDateParts(dateText) {
  const value = trimText(dateText)
  const match = value.match(DATE_PATTERN)
  if (!match) {
    return null
  }
  return {
    year: Number(value.slice(0, 4)),
    month: Number(value.slice(5, 7)),
    day: Number(value.slice(8, 10))
  }
}

function calculateLunarDate(dateText) {
  const dateParts = parseDateParts(dateText)
  if (!dateParts) {
    return null
  }
  const minYear = LUNAR_BASE_YEAR
  const maxYear = LUNAR_BASE_YEAR + LUNAR_INFO.length - 1
  if (dateParts.year < minYear || dateParts.year > maxYear) {
    return null
  }

  let offset = Math.floor((Date.UTC(dateParts.year, dateParts.month - 1, dateParts.day) - LUNAR_BASE_DATE_UTC) / DAY_MILLISECONDS)
  let year = LUNAR_BASE_YEAR
  let yearDays = 0
  while (year <= maxYear && offset > 0) {
    yearDays = lunarYearDays(year)
    offset -= yearDays
    year += 1
  }
  if (offset < 0) {
    offset += yearDays
    year -= 1
  }

  const leap = leapMonth(year)
  let isLeap = false
  let month = 1
  let monthDays = 0
  while (month <= LUNAR_MONTH_COUNT && offset > 0) {
    if (leap > 0 && month === leap + 1 && !isLeap) {
      month -= 1
      isLeap = true
      monthDays = leapMonthDays(year)
    } else {
      monthDays = lunarMonthDays(year, month)
    }
    if (isLeap && month === leap + 1) {
      isLeap = false
    }
    offset -= monthDays
    month += 1
  }
  if (offset === 0 && leap > 0 && month === leap + 1) {
    if (isLeap) {
      isLeap = false
    } else {
      isLeap = true
      month -= 1
    }
  }
  if (offset < 0) {
    offset += monthDays
    month -= 1
  }
  return Object.freeze({
    year,
    month,
    day: offset + 1,
    isLeap
  })
}

function toLunarDate(dateText) {
  const cacheKey = trimText(dateText)
  if (LUNAR_DATE_CACHE.has(cacheKey)) {
    return LUNAR_DATE_CACHE.get(cacheKey)
  }
  const lunarDate = calculateLunarDate(cacheKey)
  LUNAR_DATE_CACHE.set(cacheKey, lunarDate)
  return lunarDate
}

function formatLunarDayMeta(lunarDate) {
  if (!lunarDate || lunarDate.month < 1 || lunarDate.month > LUNAR_MONTH_TEXT.length) {
    return ''
  }
  const festivalText = LUNAR_FESTIVAL_TEXT[`${lunarDate.month}-${lunarDate.day}`]
  if (festivalText && !lunarDate.isLeap) {
    return festivalText
  }
  if (lunarDate.day === 1) {
    return `${lunarDate.isLeap ? '闰' : ''}${LUNAR_MONTH_TEXT[lunarDate.month - 1]}`
  }
  return LUNAR_DAY_TEXT[lunarDate.day - 1] || ''
}

function formatLunarFullText(lunarDate) {
  if (!lunarDate || lunarDate.month < 1 || lunarDate.month > LUNAR_MONTH_TEXT.length) {
    return ''
  }
  const monthText = `${lunarDate.isLeap ? '闰' : ''}${LUNAR_MONTH_TEXT[lunarDate.month - 1]}`
  const dayText = LUNAR_DAY_TEXT[lunarDate.day - 1] || ''
  return dayText ? `${monthText}${dayText}` : monthText
}

module.exports = {
  formatLunarDayMeta,
  formatLunarFullText,
  toLunarDate
}

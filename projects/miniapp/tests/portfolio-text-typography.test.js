const assert = require('node:assert/strict')
const test = require('node:test')

const {
  LEGACY_PERSONAL_FONT_SIZE_RPX,
  PORTFOLIO_TEXT_FONT_OPTIONS,
  PORTFOLIO_TEXT_FONT_SIZE_OPTIONS,
  buildPortfolioTextFontSizeOptions,
  buildPortfolioTextTypography,
  normalizePortfolioTextFontFamily,
  normalizePortfolioTextFontSizeRpx
} = require('../utils/portfolio-text-typography')

test('keeps supported fonts and exact numeric sizes only', () => {
  assert.deepEqual(
    buildPortfolioTextTypography(
      { fontFamily: 'WECHAT_SANS_SS', fontSizeRpx: 30 },
      LEGACY_PERSONAL_FONT_SIZE_RPX
    ),
    {
      fontFamily: 'WECHAT_SANS_SS',
      fontSizeRpx: 30,
      fontClass: 'font-wechat-sans-ss',
      fontSizeStyle: 'font-size: 30rpx;'
    }
  )
  for (const invalid of [28.5, '28', 19, 49, null]) {
    assert.equal(
      normalizePortfolioTextFontSizeRpx(
        invalid,
        LEGACY_PERSONAL_FONT_SIZE_RPX
      ),
      LEGACY_PERSONAL_FONT_SIZE_RPX
    )
  }
  assert.equal(normalizePortfolioTextFontFamily('WECHAT_SANS_STD'), 'SYSTEM')
  assert.equal(normalizePortfolioTextFontFamily('UNKNOWN'), 'SYSTEM')
})

test('exposes two fixed font samples and four numeric quick sizes', () => {
  assert.deepEqual(
    PORTFOLIO_TEXT_FONT_OPTIONS.map(({ value, label, sample }) => ({
      value,
      label,
      sample
    })),
    [
      {
        value: 'SYSTEM',
        label: '系统默认',
        sample: '映期 Folio 字体预览 123'
      },
      {
        value: 'WECHAT_SANS_SS',
        label: '微信字体',
        sample: '映期 Folio 字体预览 123'
      }
    ]
  )
  assert.deepEqual(
    PORTFOLIO_TEXT_FONT_SIZE_OPTIONS.map((item) => item.value),
    [26, 28, 32, 36]
  )
})

test('adds one non-destructive custom size option', () => {
  assert.deepEqual(
    buildPortfolioTextFontSizeOptions(30).at(-1),
    { value: 30, label: '自定义 30rpx', custom: true }
  )
  assert.deepEqual(
    buildPortfolioTextFontSizeOptions(28),
    PORTFOLIO_TEXT_FONT_SIZE_OPTIONS
  )
})

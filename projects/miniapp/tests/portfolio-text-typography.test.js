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
const {
  isValidPortfolioTextLineHeight, parsePortfolioTextLineHeightInput,
  stepPortfolioTextLineHeight, buildPortfolioTextLineHeightStyle,
  buildPortfolioTextLineHeightEditor
} = require('../utils/portfolio-text-typography')

test('行距仅接受 0.5–3.0 的一位小数倍数，缺省保持原排版', () => {
  for (let tenths = 5; tenths <= 30; tenths++) {
    const value = tenths / 10
    assert.equal(isValidPortfolioTextLineHeight(value), true)
    assert.equal(buildPortfolioTextLineHeightStyle(value), `line-height: ${value};`)
    assert.equal(parsePortfolioTextLineHeightInput(String(value)), value)
  }
  for (const value of [undefined, null, '', '1.5', 0.4, 3.1, 1.75, NaN, Infinity]) {
    assert.equal(isValidPortfolioTextLineHeight(value), false)
    assert.equal(buildPortfolioTextLineHeightStyle(value), '')
  }
  assert.equal(buildPortfolioTextLineHeightEditor(undefined, 1.75).value, '')
  assert.equal(buildPortfolioTextLineHeightEditor(undefined, 1.75).error, '')
  assert.equal(buildPortfolioTextTypography({}).lineHeightStyle, undefined)
  assert.equal(buildPortfolioTextTypography({ lineHeight: 2.1 }).lineHeightStyle, 'line-height: 2.1;')
  for (const raw of ['', 'abc', '1.75', '0.4', '3.1']) {
    assert.equal(parsePortfolioTextLineHeightInput(raw), raw)
    assert.ok(buildPortfolioTextLineHeightEditor(raw, 1.75).error)
  }
})

test('行距按 0.1 增减且不积累浮点误差，首次调节才离开旧默认值', () => {
  assert.equal(stepPortfolioTextLineHeight(undefined, 0.1, 1.75), 1.8)
  assert.equal(stepPortfolioTextLineHeight(undefined, -0.1, 1.75), 1.7)
  assert.equal(stepPortfolioTextLineHeight(undefined, 0.1, 1.65), 1.7)
  assert.equal(stepPortfolioTextLineHeight(undefined, -0.1, 1.65), 1.6)
  let value = 0.5
  for (let i = 6; i <= 30; i++) {
    value = stepPortfolioTextLineHeight(value, 0.1, 1.75)
    assert.equal(value, i / 10)
  }
  assert.equal(stepPortfolioTextLineHeight(value, 0.1, 1.75), 3)
  assert.equal(stepPortfolioTextLineHeight(0.5, -0.1, 1.75), 0.5)
  assert.equal(stepPortfolioTextLineHeight('错误', 0.1, 1.75), '错误')
  assert.equal(stepPortfolioTextLineHeight(1.5, 0.2, 1.75), 1.5)
})

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
  for (const size of [10, 11, 19, 20, 48, 49, 95, 96]) {
    assert.equal(normalizePortfolioTextFontSizeRpx(size), size)
    assert.equal(buildPortfolioTextTypography({ fontSizeRpx: size }).fontSizeStyle, `font-size: ${size}rpx;`)
  }
  for (const invalid of [28.5, '28', 9, 97, null]) {
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
  for (const size of [10, 11, 49, 96]) {
    assert.deepEqual(buildPortfolioTextFontSizeOptions(size).at(-1),
      { value: size, label: `自定义 ${size}rpx`, custom: true })
  }
})

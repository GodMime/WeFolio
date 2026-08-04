const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MINIAPP_ROOT = path.resolve(__dirname, '..')
const FOUNDATION_PATH = path.join(MINIAPP_ROOT, 'styles/portfolio-editor-foundation.wxss')

const EXPECTED_TOKENS = {
  '--pe-color-page': '#f5f6f7',
  '--pe-color-surface': '#ffffff',
  '--pe-color-text-primary': '#212529',
  '--pe-color-text-secondary': '#868e96',
  '--pe-color-text-muted': '#adb5bd',
  '--pe-color-border': '#e9ecef',
  '--pe-color-mask': 'rgba(17,24,39,0.42)',
  '--pe-color-danger': '#b55656',
  '--pe-color-danger-surface': '#fff1f1',
  '--pe-color-danger-shadow': 'rgba(181,86,86,0.12)',
  '--pe-color-primary-disabled': '#ced4da',
  '--pe-color-primary-disabled-text': '#495057',
  '--pe-color-secondary-disabled': '#f1f3f5',
  '--pe-color-secondary-disabled-text': '#6c757d',
  '--pe-color-secondary-surface': '#f1f3f5',
  '--pe-radius-card': '48rpx',
  '--pe-radius-sheet': '56rpx'
}

const REQUIRED_SINGLE_CLASS_SELECTORS = [
  '.pe-page-content',
  '.pe-page-card',
  '.pe-page-card-heading',
  '.pe-page-card-title',
  '.pe-page-card-description',
  '.pe-component-row',
  '.pe-component-index',
  '.pe-component-title',
  '.pe-component-description',
  '.pe-component-delete',
  '.pe-page-field',
  '.pe-page-actions',
  '.pe-page-action-secondary',
  '.pe-page-action-primary',
  '.pe-sheet-mask',
  '.pe-sheet-mask-visible',
  '.pe-sheet-panel',
  '.pe-sheet-panel-visible',
  '.pe-sheet-grabber',
  '.pe-sheet-heading',
  '.pe-sheet-title',
  '.pe-sheet-meta',
  '.pe-sheet-scroll',
  '.pe-sheet-actions',
  '.pe-sheet-action-cancel',
  '.pe-sheet-action-confirm',
  '.pe-action-loading',
  '.pe-sheet-choice',
  '.pe-sheet-choice-selected',
  '.pe-sheet-choice-selected-filled',
  '.pe-sheet-field',
  '.pe-sheet-field-textarea',
  '.pe-sheet-size-compact',
  '.pe-sheet-size-standard',
  '.pe-sheet-size-long'
]

const ALLOWED_DISABLED_SELECTORS = [
  '.pe-page-action-primary[disabled]',
  '.pe-page-action-secondary[disabled]',
  '.pe-sheet-action-confirm[disabled]',
  '.pe-sheet-action-cancel[disabled]'
]

const EDITOR_STYLE_CLOSURE = [
  'styles/portfolio-editor-foundation.wxss',
  'styles/portfolio-text-section-editor.wxss',
  'pages/portfolios/standard-edit/portfolio-standard-edit.wxss',
  'pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxss',
  'pages/mock/styles/portfolio-standard-edit.wxss',
  'pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxss'
]

const EXPECTED_SHEET_TIERS = {
  'pages/portfolios/standard-edit/portfolio-standard-edit.wxml': {
    backgroundColorSheetVisible: 'compact',
    componentSheetVisible: 'compact',
    hyperlinkSheetVisible: 'long',
    componentWorkSheetVisible: 'long',
    profileSheetVisible: 'long',
    profileTagDialogVisible: 'compact',
    displayGroupSheetVisible: 'long',
    qrContactSheetVisible: 'standard',
    scheduleQuerySheetVisible: 'compact',
    contactFormSheetVisible: 'standard',
    textSectionSheetVisible: 'long',
    dividerSheetVisible: 'compact',
    shareCoverCropVisible: 'standard'
  },
  'pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxml': {
    backgroundColorSheetVisible: 'compact',
    componentSheetVisible: 'compact',
    componentMoveSheetVisible: 'compact',
    textSectionSheetVisible: 'long',
    contactFormSheetVisible: 'standard',
    scheduleQuerySheetVisible: 'compact',
    dividerSheetVisible: 'compact',
    componentEditorVisible: 'long',
    shareCoverCropVisible: 'standard',
    qrContactCropVisible: 'standard'
  },
  'pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml': {
    backgroundColorSheetVisible: 'compact',
    componentSheetVisible: 'compact',
    componentEditSheetVisible: 'long'
  }
}

const LEGACY_ACCENT_PATTERN = /#(?:2d5f9a|edf5fb|536b82|c28b37|fff9ed|8fb7d5)|rgba\(\s*45\s*,\s*95\s*,\s*154\s*,/i
const CONTRACT_COLOR_PATTERN = /#(?:f5f6f7|ffffff|212529|868e96|adb5bd|e9ecef|b55656|ced4da|495057|f1f3f5|6c757d|dee2e6)|rgba\(\s*17\s*,\s*24\s*,\s*39\s*,\s*0\.42\s*\)/i
const MIGRATION_DEBT_COLOR_PATTERN = /#(?:5f6b7a|0f766e|d83a3a|a9354f|edf1f5|dbe5ef|cfd9e4|685019|fbf3e7|fbefef|fff1f1|fff5f5|c08a3e|ead8bc|fbf6ed|d5dee8|f1f4f7)/i
const MIGRATION_DEBT_RGBA_PATTERN = /rgba\(\s*181\s*,\s*86\s*,\s*86\s*,\s*0\.12\s*\)/i

const OBSOLETE_SKELETON_SELECTORS = {
  'pages/portfolios/standard-edit/portfolio-standard-edit.wxss': [
    '.panel', '.panel-heading', '.section-title', '.section-desc', '.component-row', '.component-order',
    '.component-title', '.component-remove-button', '.input', '.textarea', '.component-picker-confirm',
    '.component-work-actions', '.component-work-cancel', '.component-work-confirm', '.profile-tag-dialog-actions',
    '.share-cover-crop-actions'
  ],
  'pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxss': [
    '.panel', '.panel-heading', '.section-title', '.section-desc', '.component-row', '.component-order',
    '.component-title', '.component-remove-button', '.input', '.textarea', '.component-picker-confirm',
    '.contact-form-sheet-actions', '.schedule-query-sheet-actions', '.divider-sheet-actions',
    '.text-section-actions', '.component-editor-actions', '.sheet-actions', '.component-move-sheet-panel'
  ],
  'pages/mock/styles/portfolio-standard-edit.wxss': [
    '.panel', '.panel-heading', '.section-title', '.component-row', '.component-order', '.component-title',
    '.component-remove-button', '.input', '.textarea', '.component-picker-mask', '.component-picker-panel',
    '.component-work-picker-mask', '.component-work-picker-panel', '.component-picker-actions',
    '.component-picker-cancel', '.component-work-actions', '.component-work-cancel', '.component-work-confirm',
    '.bottom-actions', '.action-button', '.text-section-sheet-panel'
  ],
  'pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxss': ['.mock-background-color-confirm']
}

const OBSOLETE_CHOICE_STATE_SELECTORS = {
  'styles/portfolio-text-section-editor.wxss': [
    '.text-section-font-option.active',
    '.text-section-font-option.active .schedule-query-mode-radio',
    '.text-section-font-option.active .text-section-alignment-radio',
    '.text-section-font-option.disabled:not(.active)',
    '.text-section-size-option.active'
  ],
  'pages/portfolios/standard-edit/portfolio-standard-edit.wxss': [
    '.component-work-filter-pill.active',
    '.component-work-option.selected',
    '.component-work-option.selected .component-work-check',
    '.qr-source-tab.active',
    '.profile-tag-color-choice.active',
    '.divider-color-option.active',
    '.schedule-query-mode-option.active',
    '.schedule-query-mode-option.active .schedule-query-mode-radio',
    '.display-group-pill.selected',
    '.display-group-pill.active',
    '.display-group-order.selected',
    '.display-group-work-item.selected',
    '.display-group-work-order.selected'
  ],
  'pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxss': [
    '.contact-form-display-mode-option.active',
    '.schedule-query-display-mode-option.active',
    '.divider-color-option.active',
    '.contact-form-display-mode-option.active .contact-form-display-mode-radio',
    '.schedule-query-display-mode-option.active .schedule-query-display-mode-radio',
    '.text-section-alignment-option.active',
    '.text-section-alignment-option.active .text-section-alignment-radio'
  ],
  'pages/mock/styles/portfolio-standard-edit.wxss': [
    '.component-work-filter-pill.active',
    '.component-work-option.selected',
    '.component-work-option.selected .component-work-check',
    '.qr-source-tab.active',
    '.schedule-query-mode-option.active',
    '.schedule-query-mode-option.active .schedule-query-mode-radio',
    '.divider-color-option.active'
  ]
}

function normalizeCssValue(value) {
  return String(value || '').toLowerCase().replace(/\s+/g, '')
}

function parseRules(source) {
  const rules = []
  const pattern = /([^{}]+)\{([^{}]*)\}/g
  let match
  while ((match = pattern.exec(source))) {
    const selectors = match[1]
      .split(',')
      .map((selector) => selector.trim())
      .filter(Boolean)
    rules.push({ selectors, body: match[2] })
  }
  return rules
}

function ruleBody(rules, selector) {
  const rule = rules.find((candidate) => candidate.selectors.includes(selector))
  assert.ok(rule, `缺少共享选择器 ${selector}`)
  return rule.body
}

function readMiniapp(relativePath) {
  return fs.readFileSync(path.join(MINIAPP_ROOT, relativePath), 'utf8')
}

function importPaths(source) {
  return [...source.matchAll(/^@import\s+["']([^"']+)["'];\s*$/gm)].map((match) => match[1])
}

function classTokenCount(source, token) {
  const escaped = token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return [...source.matchAll(new RegExp(`(?:^|[\\s"'])${escaped}(?=$|[\\s"'])`, 'g'))].length
}

function assertSheetTierMap(wxml, mapping, relativePath) {
  const classAttributes = [...wxml.matchAll(/class="([^"]+)"/g)].map((match) => match[1])
  for (const [state, tier] of Object.entries(mapping)) {
    const masks = classAttributes.filter((value) => value.includes('pe-sheet-mask') && value.includes(`${state} ? 'pe-sheet-mask-visible'`))
    const panels = classAttributes.filter((value) => value.includes('pe-sheet-panel') && value.includes(`${state} ? 'pe-sheet-panel-visible'`))
    assert.equal(masks.length, 1, `${relativePath} 的 ${state} 必须绑定唯一共享遮罩`)
    assert.equal(panels.length, 1, `${relativePath} 的 ${state} 必须绑定唯一共享面板`)
    assert.ok(panels[0].split(/\s+/).includes(`pe-sheet-size-${tier}`), `${relativePath} 的 ${state} 必须使用 ${tier} 档`)
  }
}

function assertNativeLoadingContract(wxml, relativePath, expectedCount) {
  const buttons = [...wxml.matchAll(/<button\b[^>]*\bloading="\{\{([^}]+)\}\}"[^>]*>/g)]
  assert.equal(buttons.length, expectedCount, `${relativePath} 的原生 loading 按钮数量漂移`)
  for (const [tag, rawState] of buttons) {
    const state = rawState.trim()
    assert.ok(tag.includes(`${state} ? 'pe-action-loading' : ''`), `${relativePath} 的 ${state} loading 缺少 pe-action-loading`)
    const disabled = tag.match(/\bdisabled="\{\{([^}]+)\}\}"/)
    assert.ok(disabled, `${relativePath} 的 ${state} loading 必须同时保留 disabled`)
    assert.ok(disabled[1].includes(state), `${relativePath} 的 disabled 必须覆盖 ${state}`)
  }
}

function assertNoLegacyChoiceStateClasses(wxml, relativePath) {
  const choiceClasses = [...wxml.matchAll(/class="([^"]*\bpe-sheet-choice\b[^"]*)"/g)].map((match) => match[1])
  for (const value of choiceClasses) {
    const staticTokens = value.replace(/\{\{.*?\}\}/g, '').split(/\s+/).filter(Boolean)
    assert.ok(!staticTokens.includes('active') && !staticTokens.includes('selected'), `${relativePath} 共享选择项不得保留静态 active/selected`)
    for (const [, dynamicValue] of value.matchAll(/'([^']*)'/g)) {
      const dynamicTokens = dynamicValue.split(/\s+/).filter(Boolean)
      assert.ok(!dynamicTokens.includes('active') && !dynamicTokens.includes('selected'), `${relativePath} 共享选择项不得输出动态 active/selected`)
    }
  }
}

function assertLongSheetScrollContracts(wxml, mapping, relativePath) {
  const maskMarkers = Object.keys(EXPECTED_SHEET_TIERS[relativePath])
    .map((state) => ({ state, index: wxml.indexOf(`${state} ? 'pe-sheet-mask-visible'`) }))
    .filter((item) => item.index >= 0)
    .sort((left, right) => left.index - right.index)
  for (const [state, scrollClass] of Object.entries(mapping)) {
    const start = wxml.indexOf(`${state} ? 'pe-sheet-mask-visible'`)
    const next = maskMarkers.find((item) => item.index > start)
    const sheetMarkup = wxml.slice(start, next ? next.index : wxml.length)
    assert.match(sheetMarkup, /<scroll-view\b/, `${relativePath} 的 ${state} 长表单必须有内部 scroll-view`)
    assert.match(sheetMarkup, new RegExp(`class="[^"]*\\b${scrollClass}\\b`), `${relativePath} 的 ${state} 缺少 ${scrollClass} 滚动区`)
  }
}

function assertDisabledButton(wxml, classToken, stateFragment, relativePath) {
  const buttons = [...wxml.matchAll(/<button\b[^>]*>/g)].map((match) => match[0])
  const button = buttons.find((tag) => tag.includes(classToken) && tag.includes(stateFragment))
  assert.ok(button, `${relativePath} 缺少 ${classToken} 的 ${stateFragment} disabled 按钮`)
  assert.match(button, /\bdisabled="\{\{[^}]+\}\}"/)
  assert.doesNotMatch(button, /\bloading=/)
}

function stripPeVariableCalls(source) {
  let result = ''
  let cursor = 0
  while (cursor < source.length) {
    const start = source.indexOf('var(', cursor)
    if (start === -1) {
      result += source.slice(cursor)
      break
    }
    result += source.slice(cursor, start)
    let depth = 0
    let end = start
    for (; end < source.length; end += 1) {
      if (source[end] === '(') depth += 1
      if (source[end] === ')') {
        depth -= 1
        if (depth === 0) {
          end += 1
          break
        }
      }
    }
    const call = source.slice(start, end)
    result += call.includes('--pe-') ? '' : call
    cursor = end
  }
  return result
}

function wxssFilesUnder(relativeRoot) {
  const root = path.join(MINIAPP_ROOT, relativeRoot)
  return fs.readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const relativePath = path.join(relativeRoot, entry.name)
    if (entry.isDirectory()) return wxssFilesUnder(relativePath)
    return entry.name.endsWith('.wxss') ? [relativePath] : []
  })
}

test('portfolio editor foundation defines the neutral token and selector contract', () => {
  const source = fs.readFileSync(FOUNDATION_PATH, 'utf8')
  const rules = parseRules(source)
  const rootBody = ruleBody(rules, '.portfolio-edit-page')

  for (const [name, expected] of Object.entries(EXPECTED_TOKENS)) {
    const match = rootBody.match(new RegExp(`${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*:\\s*([^;]+);`, 'i'))
    assert.ok(match, `缺少共享变量 ${name}`)
    assert.equal(normalizeCssValue(match[1]), normalizeCssValue(expected), `${name} 取值漂移`)
  }

  for (const selector of REQUIRED_SINGLE_CLASS_SELECTORS) {
    assert.match(source, new RegExp(`(?:^|\\n)${selector.replace('.', '\\.')}\\s*\\{`, 'm'), `${selector} 必须使用扁平单类规则`)
  }

  assert.doesNotMatch(source, /!important/)
  assert.match(ruleBody(rules, '.pe-page-card'), /border-radius:\s*var\(--pe-radius-card,\s*48rpx\)/)
  assert.match(ruleBody(rules, '.pe-page-field'), /background:\s*var\(--pe-color-page,\s*#F5F6F7\)/)
  assert.match(ruleBody(rules, '.pe-page-action-secondary'), /background:\s*var\(--pe-color-secondary-surface,\s*#F1F3F5\)/)
  assert.match(ruleBody(rules, '.pe-sheet-action-cancel'), /background:\s*var\(--pe-color-secondary-surface,\s*#F1F3F5\)/)
  assert.match(ruleBody(rules, '.pe-sheet-mask'), /background:\s*var\(--pe-color-mask,\s*rgba\(17,\s*24,\s*39,\s*0\.42\)\)/)
  assert.match(ruleBody(rules, '.pe-sheet-panel'), /border-radius:\s*var\(--pe-radius-sheet,\s*56rpx\)\s+var\(--pe-radius-sheet,\s*56rpx\)\s+0\s+0/)
  assert.match(ruleBody(rules, '.pe-sheet-panel'), /padding:\s*24rpx\s+32rpx\s+calc\(32rpx\s*\+\s*env\(safe-area-inset-bottom\)\)/)
  assert.match(ruleBody(rules, '.pe-sheet-size-compact'), /height:\s*auto;/)
  assert.match(ruleBody(rules, '.pe-sheet-size-compact'), /max-height:\s*72vh;/)
  assert.match(ruleBody(rules, '.pe-sheet-size-standard'), /height:\s*auto;/)
  assert.match(ruleBody(rules, '.pe-sheet-size-standard'), /max-height:\s*76vh;/)
  assert.match(ruleBody(rules, '.pe-sheet-size-long'), /height:\s*86vh;/)
  assert.match(ruleBody(rules, '.pe-sheet-size-long'), /max-height:\s*calc\(100vh\s*-\s*176rpx\s*-\s*env\(safe-area-inset-top\)\)/)

  const peSelectors = rules.flatMap((rule) => rule.selectors).filter((selector) => selector.startsWith('.pe-'))
  const compoundSelectors = peSelectors.filter((selector) => !/^\.pe-[a-z0-9-]+$/.test(selector))
  assert.deepEqual([...compoundSelectors].sort(), [...ALLOWED_DISABLED_SELECTORS].sort())

  for (const selector of ALLOWED_DISABLED_SELECTORS) {
    const body = ruleBody(rules, selector)
    assert.match(body, /opacity:\s*1;/)
    assert.doesNotMatch(body, /(?:height|width|padding|margin|display|position)\s*:/)
  }

  const variableCalls = [...source.matchAll(/var\(\s*(--pe-[a-z0-9-]+)\s*,\s*(rgba?\([^)]*\)|[^)]+)\)/gi)]
  assert.ok(variableCalls.length > 0, '共享规则必须通过带 fallback 的 var() 消费 token')
  for (const [, name, fallback] of variableCalls) {
    assert.ok(EXPECTED_TOKENS[name] || new RegExp(`${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*:`).test(rootBody), `${name} 必须在根 token 表定义`)
    const definition = rootBody.match(new RegExp(`${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*:\\s*([^;]+);`, 'i'))
    assert.ok(definition, `${name} 缺少定义`)
    assert.equal(normalizeCssValue(fallback), normalizeCssValue(definition[1]), `${name} fallback 与定义不一致`)
  }
})

test('portfolio editor sheet mask uses Skyline-compatible viewport anchors', () => {
  const source = fs.readFileSync(FOUNDATION_PATH, 'utf8')
  const rules = parseRules(source)
  const maskBody = ruleBody(rules, '.pe-sheet-mask')

  assert.match(maskBody, /left:\s*0;/)
  assert.match(maskBody, /right:\s*0;/)
  assert.match(maskBody, /top:\s*0;/)
  assert.match(maskBody, /bottom:\s*0;/)
  assert.doesNotMatch(maskBody, /inset\s*:/)
})

test('personal portfolio editor consumes the shared page and thirteen-sheet contract', () => {
  const wxml = readMiniapp('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const wxss = readMiniapp('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')

  assert.deepEqual(importPaths(wxss), [
    '../../../styles/portfolio-text-typography.wxss',
    '../../../styles/portfolio-text-section-editor.wxss',
    '../../../styles/portfolio-editor-foundation.wxss'
  ])
  assert.match(wxss, /^(?:@import[^\n]+\n)+\n[^@]/)

  assert.equal(classTokenCount(wxml, 'pe-page-card'), 3)
  assert.equal(classTokenCount(wxml, 'pe-page-card-heading'), 3)
  assert.equal(classTokenCount(wxml, 'pe-component-row'), 1)
  assert.equal(classTokenCount(wxml, 'pe-component-index'), 1)
  assert.equal(classTokenCount(wxml, 'pe-page-actions'), 1)
  assert.ok(classTokenCount(wxml, 'pe-page-action-primary') >= 1)
  assert.ok(classTokenCount(wxml, 'pe-page-action-secondary') >= 1)
  assert.equal(classTokenCount(wxml, 'pe-page-content'), 1)
  assert.equal(classTokenCount(wxml, 'pe-component-delete'), 1)

  assert.equal(classTokenCount(wxml, 'pe-sheet-mask'), 13)
  assert.equal(classTokenCount(wxml, 'pe-sheet-mask-visible'), 13)
  assert.equal(classTokenCount(wxml, 'pe-sheet-panel'), 13)
  assert.equal(classTokenCount(wxml, 'pe-sheet-panel-visible'), 13)
  assert.equal(classTokenCount(wxml, 'pe-sheet-size-compact'), 5)
  assert.equal(classTokenCount(wxml, 'pe-sheet-size-standard'), 3)
  assert.equal(classTokenCount(wxml, 'pe-sheet-size-long'), 5)
  assertSheetTierMap(wxml, EXPECTED_SHEET_TIERS['pages/portfolios/standard-edit/portfolio-standard-edit.wxml'], '个人作品集编辑器')
  assert.doesNotMatch(wxml, /\?\s*'visible'\s*:/)
  assert.doesNotMatch(wxml, /class="input pe-page-field"/)
  assertNoLegacyChoiceStateClasses(wxml, '个人作品集编辑器')
  for (const [switchTag] of wxml.matchAll(/<switch\b[^>]*>/g)) assert.match(switchTag, /\bcolor="#212529"/)
  assertLongSheetScrollContracts(wxml, {
    hyperlinkSheetVisible: 'hyperlink-form-scroll',
    componentWorkSheetVisible: 'component-work-scroll',
    profileSheetVisible: 'profile-sheet-scroll',
    displayGroupSheetVisible: 'display-group-scroll',
    textSectionSheetVisible: 'text-section-form'
  }, 'pages/portfolios/standard-edit/portfolio-standard-edit.wxml')

  assert.ok(classTokenCount(wxml, 'pe-sheet-heading') >= 13)
  assert.ok(classTokenCount(wxml, 'pe-sheet-title') >= 13)
  assert.ok(classTokenCount(wxml, 'pe-sheet-actions') >= 13)
  assert.ok(classTokenCount(wxml, 'pe-sheet-action-cancel') >= 11)
  assert.ok(classTokenCount(wxml, 'pe-sheet-action-confirm') >= 11)
  assert.ok(classTokenCount(wxml, 'pe-sheet-field') >= 8)
  assert.ok(classTokenCount(wxml, 'pe-sheet-choice') >= 8)
  assert.match(wxml, /class="component-work-filter-pill pe-sheet-choice \{\{[^\n]+pe-sheet-choice-selected/)
  assert.doesNotMatch(wxml, /component-work-filter-pill[^>]+style="\{\{[^\n]+activeStyle/)

  assertNativeLoadingContract(wxml, '个人作品集编辑器', 2)
  assert.doesNotMatch(wxss, /(?:^|\n)\s*\.pe-[^{]+\{/)
})

test('hyperlink editor follows the travel-design sheet composition', () => {
  const wxml = readMiniapp('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const wxss = readMiniapp('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const rules = parseRules(wxss)
  const start = wxml.indexOf("hyperlinkSheetVisible ? 'pe-sheet-mask-visible'")
  const end = wxml.indexOf("componentWorkSheetVisible ? 'pe-sheet-mask-visible'", start)
  const sheet = wxml.slice(start, end)

  assert.match(sheet, /hyperlinkForm\.actionType\s*\?\s*''\s*:\s*'hyperlink-sheet-panel-initial'/)
  assert.match(sheet, /class="pe-sheet-meta hyperlink-sheet-meta">二选一配置<\/view>/)
  assert.match(sheet, />展示图片（图片 \/ 动图作品）<\/view>/)
  assert.match(sheet, /class="hyperlink-work-current/)
  assert.match(sheet, />当前已选<\/view>/)
  assert.match(sheet, /wx:for="\{\{componentWorkFilterTags\}\}"/)
  assert.match(sheet, /class="hyperlink-work-scroll"[\s\S]*scroll-x/)
  assert.match(sheet, /bindscrolltolower="handleComponentWorkScrollToLower"/)
  assert.match(sheet, /catchtap="handleSelectHyperlinkWork"/)
  assert.match(sheet, />点击行为（二选一）<\/view>/)
  assert.match(sheet, /class="hyperlink-choice-title">\{\{item\.label\}\}<\/view>/)
  assert.match(sheet, /class="hyperlink-choice-description">\{\{item\.description\}\}<\/view>/)
  assert.match(sheet, /class="hyperlink-sheet-note"/)
  assert.match(sheet, /hyperlinkForm\.actionType\s*\?\s*''\s*:\s*'hyperlink-action-confirm-initial'/)
  assert.doesNotMatch(sheet, />图标位置<\/view>/)
  assert.match(sheet, /选中「内部作品集跳转」后才展示跳转作品集配置/)
  assert.match(sheet, /点击图片后跳转到目标作品集/)
  assert.match(sheet, /点击图片后原样复制整段内容/)
  assert.match(sheet, /class="hyperlink-sheet-actions pe-sheet-actions"/)

  assert.match(ruleBody(rules, '.hyperlink-sheet-panel-initial'), /height:\s*auto;/)
  assert.match(ruleBody(rules, '.hyperlink-sheet-panel-initial'), /max-height:\s*72vh;/)
  assert.match(ruleBody(rules, '.hyperlink-form-content'), /padding:\s*0\s+0\s+8rpx;/)
  assert.match(ruleBody(rules, '.hyperlink-work-list'), /display:\s*inline-flex;/)
  assert.match(ruleBody(rules, '.hyperlink-work-option'), /width:\s*260rpx;/)
  assert.match(ruleBody(rules, '.hyperlink-work-option'), /flex:\s*0\s+0\s+260rpx;/)
  assert.match(ruleBody(rules, '.hyperlink-choice'), /border-radius:\s*40rpx;/)
  assert.match(ruleBody(rules, '.hyperlink-choice[aria-checked="true"] .schedule-query-mode-radio'), /background:\s*var\(--pe-color-text-primary,\s*#212529\)/)
  assert.match(ruleBody(rules, '.hyperlink-sheet-actions'), /justify-content:\s*flex-end;/)
  assert.match(ruleBody(rules, '.hyperlink-action-confirm-initial'), /opacity:\s*0\.4;/)
  assert.match(ruleBody(rules, '.hyperlink-action-confirm'), /width:\s*148rpx;/)
  assert.match(ruleBody(rules, '.hyperlink-action-confirm'), /height:\s*68rpx;/)
})

test('team portfolio editor consumes the shared page and ten-sheet contract', () => {
  const wxml = readMiniapp('pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxml')
  const wxss = readMiniapp('pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxss')

  assert.deepEqual(importPaths(wxss), [
    '../../../styles/portfolio-text-typography.wxss',
    '../../../styles/portfolio-text-section-editor.wxss',
    '../../../styles/portfolio-editor-foundation.wxss'
  ])
  assert.match(wxss, /^(?:@import[^\n]+\n)+\n[^@]/)

  assert.equal(classTokenCount(wxml, 'pe-page-card'), 3)
  assert.equal(classTokenCount(wxml, 'pe-page-card-heading'), 3)
  assert.equal(classTokenCount(wxml, 'pe-component-row'), 1)
  assert.equal(classTokenCount(wxml, 'pe-component-index'), 1)
  assert.equal(classTokenCount(wxml, 'pe-page-actions'), 1)
  assert.ok(classTokenCount(wxml, 'pe-page-action-primary') >= 1)
  assert.ok(classTokenCount(wxml, 'pe-page-action-secondary') >= 1)
  assert.equal(classTokenCount(wxml, 'pe-page-content'), 1)
  assert.equal(classTokenCount(wxml, 'pe-component-delete'), 1)

  assert.equal(classTokenCount(wxml, 'pe-sheet-mask'), 10)
  assert.equal(classTokenCount(wxml, 'pe-sheet-mask-visible'), 10)
  assert.equal(classTokenCount(wxml, 'pe-sheet-panel'), 10)
  assert.equal(classTokenCount(wxml, 'pe-sheet-panel-visible'), 10)
  assert.equal(classTokenCount(wxml, 'pe-sheet-size-compact'), 5)
  assert.equal(classTokenCount(wxml, 'pe-sheet-size-standard'), 3)
  assert.equal(classTokenCount(wxml, 'pe-sheet-size-long'), 2)
  assertSheetTierMap(wxml, EXPECTED_SHEET_TIERS['pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxml'], '团队作品集编辑器')
  assert.doesNotMatch(wxml, /\?\s*'visible'\s*:/)
  assert.doesNotMatch(wxml, /class="input pe-page-field"/)
  assertNoLegacyChoiceStateClasses(wxml, '团队作品集编辑器')
  assertLongSheetScrollContracts(wxml, {
    textSectionSheetVisible: 'text-section-form',
    componentEditorVisible: 'component-editor-scroll'
  }, 'pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxml')
  assertDisabledButton(wxml, 'pe-sheet-action-cancel', 'componentMovePending', '团队作品集编辑器')
  assertDisabledButton(wxml, 'pe-sheet-action-cancel', 'qrContactCropSaving', '团队作品集编辑器')
  assertDisabledButton(wxml, 'pe-page-action-secondary', '!portfolioId', '团队作品集编辑器')
  assert.ok(wxml.indexOf("componentEditorVisible ? 'pe-sheet-mask-visible'") < wxml.indexOf("qrContactCropVisible ? 'pe-sheet-mask-visible'"), '二维码裁剪遮罩必须位于组件编辑遮罩之后')

  assert.ok(classTokenCount(wxml, 'pe-sheet-heading') >= 10)
  assert.ok(classTokenCount(wxml, 'pe-sheet-title') >= 10)
  assert.ok(classTokenCount(wxml, 'pe-sheet-actions') >= 10)
  assert.ok(classTokenCount(wxml, 'pe-sheet-action-cancel') >= 10)
  assert.ok(classTokenCount(wxml, 'pe-sheet-action-confirm') >= 8)
  assert.ok(classTokenCount(wxml, 'pe-sheet-field') >= 2)
  assert.ok(classTokenCount(wxml, 'pe-sheet-choice') >= 6)

  assertNativeLoadingContract(wxml, '团队作品集编辑器', 4)
  assert.doesNotMatch(wxss, /(?:^|\n)\s*\.pe-[^{]+\{/)
})

test('shared text typography editor uses the neutral portfolio editor tokens', () => {
  const wxss = readMiniapp('styles/portfolio-text-section-editor.wxss')
  const rules = parseRules(wxss)

  assert.doesNotMatch(wxss, /#2d5f9a|#edf5fb|#536b82/i)
  assert.match(ruleBody(rules, '.text-section-font-option'), /background:\s*var\(--pe-color-page,\s*#F5F6F7\)/i)
  assert.match(ruleBody(rules, '.text-section-font-option[aria-checked="true"] .text-section-alignment-radio'), /background:\s*var\(--pe-color-text-primary,\s*#212529\)/i)
  assert.match(ruleBody(rules, '.text-section-font-sample'), /color:\s*var\(--pe-color-text-strong,\s*#495057\)/i)
  assert.match(ruleBody(rules, '.text-section-font-hint'), /color:\s*var\(--pe-color-text-muted,\s*#ADB5BD\)/i)
  assert.match(ruleBody(rules, '.text-section-font-hint.current'), /color:\s*var\(--pe-color-text-secondary,\s*#868E96\)/i)
  assert.doesNotMatch(wxss, /\.text-section-(?:font|size)-option\.active/)
})

test('text section editor headings omit duplicate meta while keeping input counters and limits', () => {
  const personalWxml = readMiniapp('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const teamWxml = readMiniapp('pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxml')
  const mockWxml = readMiniapp('pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml')

  for (const [name, wxml, endMarker] of [
    ['个人作品集', personalWxml, '<view class="divider-sheet-mask'],
    ['团队作品集', teamWxml, '<view class="contact-form-sheet-mask']
  ]) {
    const section = wxml.slice(wxml.indexOf('<view class="text-section-sheet-mask'), wxml.indexOf(endMarker))
    const heading = section.slice(section.indexOf('<view class="pe-sheet-heading">'), section.indexOf('<scroll-view'))
    assert.doesNotMatch(heading, /pe-sheet-meta/, `${name}文字说明标题区不得重复显示字数`)
    assert.match(section, /class="field-limit">\{\{textSectionFieldCounters\.content\}\}<\/view>/)
    assert.match(section, /maxlength="\{\{textSectionMaxLength\}\}"/)
  }

  const mockEditor = mockWxml.slice(
    mockWxml.indexOf('<view class="mock-component-edit-mask'),
    mockWxml.indexOf('</view>\n\n  <view class="bottom-actions')
  )
  const mockHeading = mockEditor.slice(mockEditor.indexOf('<view class="component-picker-heading'), mockEditor.indexOf('<scroll-view'))
  const mockTextSection = mockEditor.slice(mockEditor.indexOf("selectedComponentType === 'TEXT_SECTION'"))
  assert.match(mockHeading, /wx:if="\{\{selectedComponentType !== 'TEXT_SECTION'\}\}"[^>]*class="component-picker-count pe-sheet-meta"/)
  assert.match(mockTextSection, /maxlength="200"/)
})

test('mock portfolio editor consumes three shared sheets without crossing its isolation boundary', () => {
  const wxml = readMiniapp('pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml')
  const wxss = readMiniapp('pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxss')
  const js = readMiniapp('pages/mock/portfolio-standard-edit/portfolio-standard-edit.js')

  assert.deepEqual(importPaths(wxss), [
    '../styles/portfolio-standard-edit.wxss',
    '../common.wxss',
    '../../../styles/portfolio-editor-foundation.wxss',
    '../../../styles/portfolio-text-section-editor.wxss'
  ])
  assert.match(wxss, /^(?:@import[^\n]+\n)+\n[^@]/)

  assert.equal(classTokenCount(wxml, 'pe-page-card'), 3)
  assert.equal(classTokenCount(wxml, 'pe-page-card-heading'), 3)
  assert.equal(classTokenCount(wxml, 'pe-component-row'), 1)
  assert.equal(classTokenCount(wxml, 'pe-component-index'), 1)
  assert.equal(classTokenCount(wxml, 'pe-page-actions'), 1)
  assert.ok(classTokenCount(wxml, 'pe-page-action-primary') >= 1)
  assert.ok(classTokenCount(wxml, 'pe-page-action-secondary') >= 1)
  assert.equal(classTokenCount(wxml, 'pe-page-content'), 1)
  assert.equal(classTokenCount(wxml, 'pe-component-delete'), 1)

  assert.equal(classTokenCount(wxml, 'pe-sheet-mask'), 3)
  assert.equal(classTokenCount(wxml, 'pe-sheet-mask-visible'), 3)
  assert.equal(classTokenCount(wxml, 'pe-sheet-panel'), 3)
  assert.equal(classTokenCount(wxml, 'pe-sheet-panel-visible'), 3)
  assert.equal(classTokenCount(wxml, 'pe-sheet-size-compact'), 2)
  assert.equal(classTokenCount(wxml, 'pe-sheet-size-standard'), 0)
  assert.equal(classTokenCount(wxml, 'pe-sheet-size-long'), 1)
  assertSheetTierMap(wxml, EXPECTED_SHEET_TIERS['pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml'], 'Mock 作品集编辑器')
  assert.doesNotMatch(wxml, /\?\s*'visible'\s*:/)
  assert.doesNotMatch(wxml, /class="input pe-page-field"/)
  assertNoLegacyChoiceStateClasses(wxml, 'Mock 作品集编辑器')
  assertLongSheetScrollContracts(wxml, {
    componentEditSheetVisible: 'mock-component-edit-scroll'
  }, 'pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml')
  assert.ok(classTokenCount(wxml, 'pe-sheet-actions') >= 3)
  assert.ok(classTokenCount(wxml, 'pe-sheet-action-cancel') >= 3)
  assert.ok(classTokenCount(wxml, 'pe-sheet-action-confirm') >= 2)
  assert.ok(classTokenCount(wxml, 'pe-sheet-field') >= 6)
  assert.ok(classTokenCount(wxml, 'pe-sheet-choice') >= 6)
  assertNativeLoadingContract(wxml, 'Mock 作品集编辑器', 0)

  assert.doesNotMatch(wxss, /(?:^|\n)\s*\.pe-[^{]+\{/)
  assert.doesNotMatch(`${wxml}\n${wxss}\n${js}`, /utils\/(?:request|session|avatar|team-avatar|visitor-session)|wx\.(?:request|uploadFile)|\/api\//)
})

test('portfolio editor style closure has one pe source and no direct contract or legacy accent colors', () => {
  const foundationRules = parseRules(readMiniapp('styles/portfolio-editor-foundation.wxss'))
  const rootBody = ruleBody(foundationRules, '.portfolio-edit-page')
  const tokenDefinitions = Object.fromEntries(
    [...rootBody.matchAll(/(--pe-[a-z0-9-]+)\s*:\s*([^;]+);/gi)].map((match) => [match[1], match[2]])
  )
  const peDefinitionFiles = []
  for (const relativePath of wxssFilesUnder('.')) {
    const source = readMiniapp(relativePath)
    if (parseRules(source).some((rule) => rule.selectors.some((selector) => /\.pe-[a-z0-9-]+/i.test(selector)))) {
      peDefinitionFiles.push(relativePath)
    }
  }
  assert.deepEqual(peDefinitionFiles, ['styles/portfolio-editor-foundation.wxss'])

  for (const relativePath of EDITOR_STYLE_CLOSURE) {
    const source = readMiniapp(relativePath)
    assert.doesNotMatch(source, LEGACY_ACCENT_PATTERN, `${relativePath} 仍包含旧蓝色或金色编辑态`)
    assert.doesNotMatch(source, /\.visible\b/, `${relativePath} 不得继续定义无前缀 visible 状态`)

    source.split('\n').forEach((line, index) => {
      if (relativePath === 'styles/portfolio-editor-foundation.wxss' && /^\s*--pe-[a-z0-9-]+\s*:/.test(line)) return
      const withoutVariables = stripPeVariableCalls(line)
      assert.doesNotMatch(withoutVariables, CONTRACT_COLOR_PATTERN, `${relativePath}:${index + 1} 契约色必须通过 --pe-* 变量消费`)
      assert.doesNotMatch(withoutVariables, MIGRATION_DEBT_COLOR_PATTERN, `${relativePath}:${index + 1} 遗留色必须迁移到 --pe-* 变量`)
      assert.doesNotMatch(withoutVariables, MIGRATION_DEBT_RGBA_PATTERN, `${relativePath}:${index + 1} 危险色阴影必须迁移到 --pe-* 变量`)
    })

    const variableCalls = [...source.matchAll(/var\(\s*(--pe-[a-z0-9-]+)\s*,\s*(rgba?\([^)]*\)|[^)]+)\)/gi)]
    for (const [, name, fallback] of variableCalls) {
      assert.ok(tokenDefinitions[name], `${relativePath} 使用了未定义变量 ${name}`)
      assert.equal(normalizeCssValue(fallback), normalizeCssValue(tokenDefinitions[name]), `${relativePath} 的 ${name} fallback 与定义不一致`)
    }
    if (relativePath !== 'styles/portfolio-editor-foundation.wxss') {
      assert.doesNotMatch(source, /(?:height|max-height)\s*:\s*calc\([^;]*safe-area-inset-bottom/i, `${relativePath} 不得在高度和底部 padding 重复扣安全区`)
    }
  }

  for (const [relativePath, obsoleteSelectors] of Object.entries(OBSOLETE_SKELETON_SELECTORS)) {
    const selectors = parseRules(readMiniapp(relativePath)).flatMap((rule) => rule.selectors)
    for (const selector of obsoleteSelectors) {
      assert.ok(!selectors.includes(selector), `${relativePath} 仍声明旧骨架规则 ${selector}`)
    }
  }

  for (const [relativePath, obsoleteSelectors] of Object.entries(OBSOLETE_CHOICE_STATE_SELECTORS)) {
    const selectors = parseRules(readMiniapp(relativePath)).flatMap((rule) => rule.selectors)
    for (const selector of obsoleteSelectors) {
      assert.ok(!selectors.includes(selector), `${relativePath} 仍声明旧选择状态规则 ${selector}`)
    }
  }

  const pageMarkup = [
    readMiniapp('pages/portfolios/standard-edit/portfolio-standard-edit.wxml'),
    readMiniapp('pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxml'),
    readMiniapp('pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml')
  ].join('\n')
  assert.doesNotMatch(pageMarkup, LEGACY_ACCENT_PATTERN, '编辑器 WXML 不得继续使用旧蓝色或金色控件色')
  assert.equal(classTokenCount(pageMarkup, 'pe-sheet-panel'), 26)
  assert.equal(classTokenCount(pageMarkup, 'pe-sheet-size-compact'), 12)
  assert.equal(classTokenCount(pageMarkup, 'pe-sheet-size-standard'), 6)
  assert.equal(classTokenCount(pageMarkup, 'pe-sheet-size-long'), 8)
})

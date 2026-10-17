const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

// 九处真实字体循环分别锁定数据源和用户选择，不以整页中其它正确绑定代替。
const cases = []
for (const pkg of ['portfolios', 'team-portfolios']) {
  cases.push({ file: `pages/${pkg}/standard-edit/${pkg === 'portfolios' ? 'portfolio' : 'team-portfolio'}-standard-edit.wxml`, source: 'textSectionFontOptions', state: 'textSectionForm', tap: 'handleTextSectionFontTap', event: 'catchtap' })
  cases.push({ file: `pages/${pkg}/components/structured-text-editor/structured-text-editor.wxml`, source: 'fonts', state: 'blockDraft.block', tap: 'handleBlockOption', expanded: true })
  cases.push({ file: `pages/${pkg}/components/text-grid-editor/text-grid-editor.wxml`, source: 'fonts', state: 'run', tap: 'handleField', expanded: true })
}
cases.push({ file: 'pages/mock/portfolio-standard-edit/portfolio-standard-edit.wxml', source: 'textSectionFontOptions', state: 'textSectionForm', tap: 'handleTextSectionFontTap', event: 'catchtap' })
cases.push({ file: 'components/mock/structured-text-editor/structured-text-editor.wxml', source: 'fontOptions', state: 'selectedBlock', tap: 'handleFontTap' })
cases.push({ file: 'components/mock/text-grid-editor/text-grid-editor.wxml', source: 'fontOptions', state: 'focusedRun', tap: 'handleFontTap' })
function verify(markup, spec) {
  const tags = markup.match(/<view\b(?:[^">]|"[^"]*")*>/g) || []
  const choices = tags.map(tag => Object.fromEntries([...tag.matchAll(/([\w:-]+)="([^"]*)"/g)].map(match => [match[1], match[2]])))
    .filter(attrs => attrs['wx:for'] && attrs['wx:for'].includes(spec.source) && attrs['aria-role'] === 'radio')
  assert.equal(choices.length, 1, '必须定位到唯一真实字体循环元素')
  const attrs = choices[0]
  assert.equal(attrs['wx:for'], `{{${spec.source}}}`)
  assert.equal(attrs['data-value'], '{{item.value}}')
  assert.equal(attrs[spec.event || 'bindtap'], spec.tap)
  assert.equal(attrs['aria-checked'], `{{(${spec.state}.fontId || ${spec.state}.fontFamily) === item.value}}`)
  assert.ok(attrs.class.includes(`(${spec.state}.fontId || ${spec.state}.fontFamily) === item.value`))
  assert.ok(attrs['aria-label'].includes('{{item.label}}'))
  assert.equal(attrs['aria-disabled'], '{{!item.available}}')
  if (spec.expanded) assert.equal(attrs['wx:if'], `{{!item.remote || moreFontsExpanded || ${spec.state}.fontId === item.value}}`)
}
for (const spec of cases) {
  test(`${spec.file}: 字体循环使用真实数组、选中态和点击值`, () => verify(fs.readFileSync(path.join(__dirname, '..', spec.file), 'utf8'), spec))
  test(`${spec.file}: 单花括号变异必须被拦截`, () => {
    const markup = fs.readFileSync(path.join(__dirname, '..', spec.file), 'utf8')
    assert.throws(() => verify(markup.replace(`wx:for="{{${spec.source}}}"`, `wx:for="{${spec.source}}"`), spec), assert.AssertionError)
  })
}

test('正式与 Mock 六款样张文案一致，图片不再沿用 400 字重 WOFF 的对象名', () => {
  const mock = require('../pages/mock/utils/mock-portfolio-font-catalog')
  const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, '../../java/wefolio-java-runtime/src/main/resources/fonts/manifest.json'), 'utf8'))
  for (const entry of mock) {
    const formal = manifest.fonts.find(font => font.fontId === entry.fontId)
    assert.equal(entry.sample, formal.sample, entry.fontId)
    assert.equal(entry.sampleUrl, formal.previewImageUrl)
    assert.match(entry.sampleUrl, /\/[a-f0-9]{64}\.png$/)
    assert.notEqual(path.basename(entry.sampleUrl, '.png'), path.basename(entry.assets['400'].url, '.woff'))
  }
})

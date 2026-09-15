const test = require('node:test')
const assert = require('node:assert/strict')
const personal = require('../utils/portfolios')
const fs = require('node:fs')
const path = require('node:path')

test('主包和团队文字颜色纯规则保持一致，且团队组件不跨包依赖', () => {
  const shared = fs.readFileSync(path.join(__dirname, '../utils/portfolio-text-color.js'), 'utf8').split('\n').slice(1).join('\n')
  const team = fs.readFileSync(path.join(__dirname, '../pages/team-portfolios/utils/portfolio-text-color.js'), 'utf8').split('\n').slice(1).join('\n')
  assert.equal(team, shared)
})

function renderer(directory) {
  let definition
  global.Component = value => { definition = value }
  const file = require.resolve(`../pages/${directory}/components/text-section/text-section`)
  delete require.cache[file]
  try { require(file) } finally { delete global.Component }
  return Object.assign({}, definition.methods, {
    definition, data: structuredClone(definition.data), properties: {},
    setData(patch) { Object.assign(this.data, patch) }
  })
}

test('普通文字旧配置默认跟随主题，显式颜色保存后仍存在', () => {
  assert.equal(personal.normalizeTextSectionConfig({ content: '正文' }).color, 'AUTO')
  let config = personal.addComponent({}, 'TEXT_SECTION')
  const key = config.components.at(-1).componentKey
  config = personal.updateComponentTextSectionConfig(config, key, { content: '正文', color: '#abcdef' })
  assert.equal(config.components.at(-1).config.color, '#ABCDEF')
  config = personal.updateComponentTextSectionConfig(config, key, { content: '更新正文', alignment: 'RIGHT' })
  assert.equal(config.components.at(-1).config.color, '#ABCDEF')
})

for (const directory of ['portfolios', 'team-portfolios']) {
  test(`${directory} 普通文字显式颜色覆盖背景文字色，AUTO保留主题配色，非法颜色不拼入样式`, () => {
    const view = renderer(directory)
    for (const themeMode of ['light', 'dark']) {
      for (const backgroundEnabled of [false, true]) {
        for (const [color, style] of [[undefined, ''], ['AUTO', ''], ['#FFFFFF', 'color:#FFFFFF;'], ['#212529', 'color:#212529;'], ['#ab12cd', 'color:#AB12CD;'], ['red;position:fixed', '']]) {
          const config = { content: '正文', backgroundEnabled, color }
          view.properties = { themeMode, config, textSection: config }
          view.updatePresentation()
          assert.equal(view.data.textColorStyle, style)
        }
      }
    }
  })
}

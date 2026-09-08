const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')

const MODULE_PATHS = [
  '../pages/portfolios/utils/portfolio-text-sections',
  '../pages/team-portfolios/utils/portfolio-text-sections'
]

function freeze(value) {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(freeze)
    Object.freeze(value)
  }
  return value
}

for (const modulePath of MODULE_PATHS) {
  test(`${modulePath}: 编辑类型切换与安全展示`, async (t) => {
    const rules = require(modulePath)

    await t.test('不提供 Object.hasOwn 的小程序运行时仍可规范化与校验', () => {
      const sandbox = { module: { exports: {} } }
      vm.runInNewContext('Object.hasOwn = undefined; Object.fromEntries = undefined;\n' + fs.readFileSync(require.resolve(modulePath), 'utf8'), sandbox)
      const compatible = sandbox.module.exports
      assert.equal(compatible.validateStructuredTextConfig({ blocks: [{ type: 'TITLE', blockKey: 'x', content: '有效' }] }), '')
      assert.equal(compatible.createStructuredBlock('TITLE', 'x').fontSizeRpx, 44)
    })

    await t.test('文字类型共用内容且所有独立样式在切换后保留', () => {
      const block = freeze({ ...rules.createStructuredBlock('TITLE', 'key'), content: 'A\n\n B ',
        fontSizeRpx: 72, fontWeight: 'NORMAL', color: '#abcdef', alignment: 'RIGHT', marginBottomRpx: 124 })
      const initial = freeze(rules.createBlockEditDraft(block))
      const { draft, error } = rules.switchBlockEditType(initial, 'HINT')
      assert.equal(error, '')
      assert.equal(draft.block.content, 'A\n\n B ')
      for (const field of ['blockKey', 'fontSizeRpx', 'fontWeight', 'color', 'alignment', 'marginTopRpx', 'marginBottomRpx']) {
        assert.equal(draft.block[field], block[field])
      }
      assert.equal(initial.block.type, 'TITLE')
      assert.notStrictEqual(draft.block, initial.block)
    })

    await t.test('首次文字转列表按非空行转换，切回恢复两种结构各自内容', () => {
      let draft = rules.createBlockEditDraft({ ...rules.createStructuredBlock('PARAGRAPH', 'p'), content: '甲\n\n 乙 ' })
      draft = rules.switchBlockEditType(freeze(draft), 'LIST').draft
      assert.deepEqual(draft.block.items, ['甲', ' 乙 '])
      draft.block.items.push('丙')
      draft = rules.switchBlockEditType(freeze(draft), 'TITLE').draft
      assert.equal(draft.block.content, '甲\n\n 乙 ')
      draft.block.content = '修改后的文字'
      draft = rules.switchBlockEditType(freeze(draft), 'LIST').draft
      assert.deepEqual(draft.block.items, ['甲', ' 乙 ', '丙'])
      draft = rules.switchBlockEditType(freeze(draft), 'PARAGRAPH').draft
      assert.equal(draft.block.content, '修改后的文字')
      const fromList = rules.switchBlockEditType(rules.createBlockEditDraft({
        ...rules.createStructuredBlock('LIST', 'l'), items: ['甲', '乙']
      }), 'PARAGRAPH')
      assert.equal(fromList.draft.block.content, '甲\n乙')
    })

    await t.test('超过十行转换被拒绝且不截断，非法列表不能通过类型切换绕过限制', () => {
      const initial = freeze(rules.createBlockEditDraft({ ...rules.createStructuredBlock('PARAGRAPH', 'p'),
        content: Array.from({ length: 11 }, (_, i) => String(i)).join('\n') }))
      const result = rules.switchBlockEditType(initial, 'LIST')
      assert.match(result.error, /10/)
      assert.deepEqual(result.draft, initial)
      const invalidList = freeze(rules.createBlockEditDraft({ ...rules.createStructuredBlock('LIST', 'l'), items: Array(11).fill('x') }))
      assert.notEqual(rules.switchBlockEditType(invalidList, 'PARAGRAPH').error, '')
      assert.notEqual(rules.switchBlockEditType(initial, 'UNKNOWN').error, '')
      const tooLong = rules.createBlockEditDraft({ ...rules.createStructuredBlock('TITLE', 't'), content: '😀'.repeat(2001) })
      assert.notEqual(rules.switchBlockEditType(tooLong, 'SPACER').error, '')
    })

    await t.test('留白只暂存原文，切回恢复；已保存留白转文字按新类型初始化', () => {
      let draft = rules.createBlockEditDraft({ ...rules.createStructuredBlock('TITLE', 't'), content: '原文', fontSizeRpx: 80 })
      draft = rules.switchBlockEditType(draft, 'SPACER').draft
      assert.equal(Object.hasOwn(draft.block, 'content'), false)
      assert.equal(Object.hasOwn(draft.block, 'fontSizeRpx'), false)
      assert.equal(draft.block.heightRpx, 32)
      draft.block.heightRpx = 64
      const saved = rules.finalizeStructuredTextConfig({ blocks: [draft.block] })
      assert.equal(Object.hasOwn(saved.blocks[0], 'content'), false)
      draft = rules.switchBlockEditType(freeze(draft), 'TITLE').draft
      assert.equal(draft.block.content, '原文')
      assert.equal(draft.block.fontSizeRpx, 80)
      assert.equal(rules.switchBlockEditType(draft, 'SPACER').draft.block.heightRpx, 64)
      const reopened = rules.switchBlockEditType(rules.createBlockEditDraft(saved.blocks[0]), 'PARAGRAPH').draft
      assert.equal(reopened.block.content, '')
      assert.equal(reopened.block.fontSizeRpx, 28)
      assert.notEqual(rules.validateStructuredTextConfig({ blocks: [reopened.block] }), '')
    })

    await t.test('AUTO 随背景与主题派生颜色，显式颜色不变且保存不带展示字段', () => {
      for (const [backgroundEnabled, themeMode, autoColor] of [
        [false, 'LIGHT', '#212529'], [false, 'DARK', '#F8F9FA'],
        [true, 'LIGHT', '#F8F9FA'], [true, 'DARK', '#F8F9FA']
      ]) {
        const config = freeze({ backgroundEnabled, blocks: ['AUTO', '#FFFFFF', '#212529', '#aBcDeF'].map((color, i) => ({
          ...rules.createStructuredBlock('TITLE', String(i)), content: '文本', color
        })) })
        const display = rules.buildStructuredTextPresentation(config, themeMode)
        assert.deepEqual(display.blocks.map((block) => block.displayColor), [autoColor, '#FFFFFF', '#212529', '#aBcDeF'])
        assert.equal(display.blocks[0].color, 'AUTO')
        assert.match(display.blocks[0].style, /font-size:44rpx/)
        assert.match(display.blocks[0].style, /font-weight:700/)
        assert.match(display.blocks[0].style, /margin-top:24rpx/)
        assert.match(display.blocks[0].style, new RegExp(`color:${autoColor}`))
        const saved = rules.finalizeStructuredTextConfig({ ...config, blocks: display.blocks })
        assert.equal(saved.blocks[0].color, 'AUTO')
        assert.equal(Object.hasOwn(saved.blocks[0], 'displayColor'), false)
      }
    })

    await t.test('留白展示无文字行高，非法样式不拼接为 CSS 且原值保留用于校验', () => {
      const input = freeze({ blocks: [
        { ...rules.createStructuredBlock('SPACER', 's'), heightRpx: 32, marginTopRpx: 4, marginBottomRpx: 8 },
        { ...rules.createStructuredBlock('PARAGRAPH', 'p'), content: '原文', color: 'red;position:fixed',
          alignment: 'left;display:none', fontSizeRpx: '44;display:none', marginTopRpx: '0;display:none' }
      ] })
      const { blocks } = rules.buildStructuredTextPresentation(input, 'LIGHT')
      assert.match(blocks[0].style, /height:32rpx/)
      assert.doesNotMatch(blocks[0].style, /font|line-height|color/)
      assert.equal(blocks[1].color, 'red;position:fixed')
      assert.doesNotMatch(blocks[1].style, /position|display:none/)
      assert.equal(blocks[1].displayColor, '#212529')
    })

    await t.test('背景按原比例计算最小高度，动图用原 URL，三种处理独立映射', () => {
      for (const [backgroundTreatment, treatmentClass] of [['ORIGINAL', 'text-background--original'],
        ['DARK_MASK', 'text-background--dark-mask'], ['GRADIENT', 'text-background--gradient']]) {
        assert.deepEqual(rules.buildTextBackgroundPresentation(freeze({ backgroundEnabled: true, backgroundWorkId: 7,
          backgroundTreatment, backgroundWork: { workId: 7, mediaType: 'ANIMATION', url: 'https://example.test/original.gif',
            width: 1000, height: 1600, coverUrl: 'https://example.test/static.jpg' } })), {
          enabled: true, imageUrl: 'https://example.test/original.gif', minHeightRpx: 1200, treatmentClass, invalid: false
        })
      }
    })

    await t.test('无效、关闭、无权 URL 和缺尺寸只产生安全展示数据', () => {
      const base = { backgroundEnabled: true, backgroundWorkId: 7, backgroundUrl: 'https://example.test/untrusted.jpg' }
      for (const patch of [{}, { backgroundInvalid: true, backgroundWork: { workId: 7, mediaType: 'IMAGE', url: 'bad' } },
        { backgroundWork: null }, { backgroundWork: { workId: 7, mediaType: 'IMAGE' } },
        { backgroundWork: { workId: 7, mediaType: 'VIDEO', url: 'video.mp4' } },
        { backgroundWork: { workId: 99, mediaType: 'IMAGE', url: 'https://example.test/old.jpg' } }]) {
        assert.deepEqual(rules.buildTextBackgroundPresentation({ ...base, ...patch }), {
          enabled: true, imageUrl: '', minHeightRpx: 0, treatmentClass: 'text-background--gradient', invalid: true
        })
      }
      assert.deepEqual(rules.buildTextBackgroundPresentation({ ...base, backgroundEnabled: false }), {
        enabled: false, imageUrl: '', minHeightRpx: 0, treatmentClass: '', invalid: false
      })
      const noDimensions = rules.buildTextBackgroundPresentation({ ...base,
        backgroundWork: { workId: 7, mediaType: 'IMAGE', url: 'https://example.test/image.jpg', width: 0, height: 100 } })
      assert.equal(noDimensions.minHeightRpx, 0)
      assert.equal(noDimensions.invalid, false)
      const display = rules.buildStructuredTextPresentation({ ...base, backgroundInvalid: true,
        blocks: [{ ...rules.createStructuredBlock('TITLE', 't'), content: '仍可见' }] }, 'LIGHT')
      assert.equal(display.blocks[0].displayColor, '#F8F9FA')
      assert.equal(display.blocks[0].content, '仍可见')
    })

    await t.test('未知类型及原型同名枚举不会导致规范化或展示崩溃', () => {
      for (const type of ['constructor', '__proto__']) {
        assert.deepEqual(rules.createStructuredBlock(type, 'x'), { blockKey: 'x', type })
        assert.notEqual(rules.validateStructuredTextConfig({ blocks: [{ blockKey: 'x', type }] }), '')
      }
      const block = rules.buildStructuredTextPresentation({ blocks: [{
        ...rules.createStructuredBlock('TITLE', 'x'), fontFamily: '__proto__', content: '安全'
      }] }, 'LIGHT').blocks[0]
      assert.equal(block.fontClass, 'font-system')
      assert.equal(rules.buildTextBackgroundPresentation({ backgroundEnabled: true, backgroundTreatment: '__proto__' }).treatmentClass,
        'text-background--gradient')
    })
  })

  test(`${modulePath}: 默认、规范化、持久化和内容限制`, async (t) => {
    const rules = require(modulePath)
    const valid = (patch = {}) => ({ blocks: [{ blockKey: 'a', type: 'TITLE', content: '你好', ...patch }] })

    await t.test('六种类型应用独立默认值且不生成不适用字段', () => {
      for (const [type, size, weight, top, bottom] of [
        ['EYEBROW', 24, 'NORMAL', 0, 16], ['TITLE', 44, 'BOLD', 24, 16],
        ['PARAGRAPH', 28, 'NORMAL', 0, 16], ['LIST', 26, 'NORMAL', 0, 16],
        ['HINT', 24, 'NORMAL', 32, 0]
      ]) {
        const block = rules.createStructuredBlock(type, 'stable-key')
        assert.equal(block.blockKey, 'stable-key')
        assert.equal(block.fontSizeRpx, size)
        assert.equal(block.fontWeight, weight)
        assert.equal(block.marginTopRpx, top)
        assert.equal(block.marginBottomRpx, bottom)
        assert.equal(block.fontFamily, 'SYSTEM')
        assert.equal(block.color, 'AUTO')
        assert.equal(block.alignment, 'LEFT')
        assert.equal(Object.hasOwn(block, 'heightRpx'), false)
        if (type === 'LIST') assert.deepEqual(block.items, [''])
        else assert.equal(block.content, '')
      }
      assert.deepEqual(rules.createStructuredBlock('SPACER', 'space'), {
        blockKey: 'space', type: 'SPACER', marginTopRpx: 0, marginBottomRpx: 0, heightRpx: 32
      })
    })

    await t.test('规范化创建深副本且只回填缺失，不覆盖非法值或已有样式', () => {
      const input = freeze({ blocks: [{ blockKey: 'x', type: 'LIST', items: ['A'], fontSizeRpx: 70,
        color: '#AaBbCc', alignment: 'RIGHT', marginTopRpx: 128, fontWeight: 'NORMAL' }] })
      const output = rules.normalizeStructuredTextConfig(input)
      assert.equal(output.blocks[0].fontSizeRpx, 70)
      assert.equal(output.blocks[0].color, '#AaBbCc')
      assert.equal(output.blocks[0].alignment, 'RIGHT')
      assert.equal(output.blocks[0].marginTopRpx, 128)
      assert.equal(output.blocks[0].fontFamily, 'SYSTEM')
      output.blocks[0].items.push('B')
      assert.deepEqual(input.blocks[0].items, ['A'])
      for (const [field, value] of [['fontSizeRpx', null], ['color', 'bad'], ['marginTopRpx', 27]]) {
        assert.equal(rules.normalizeStructuredTextConfig(valid({ [field]: value })).blocks[0][field], value)
      }
      assert.equal(rules.normalizeStructuredTextConfig({ blocks: null }).blocks, null)
    })

    await t.test('数字输入限制范围后按四舍五入取 4 的倍数', () => {
      for (const [input, want] of [['', 0], [null, 0], [-7, 0], [27, 28], ['26', 28], [140, 128], ['bad', 0]]) {
        assert.equal(rules.normalizeSpacingInput(input), want)
      }
    })

    await t.test('Unicode 码点统计计入空格换行，仅累计当前类型的文字', () => {
      assert.equal(rules.countStructuredText([
        { type: 'PARAGRAPH', content: 'A\n😀' }, { type: 'LIST', items: ['a ', '😀'] },
        { type: 'SPACER', content: '不计入', items: ['不计入'] }
      ]), 6)
      assert.equal(rules.validateStructuredTextConfig(valid({ content: '😀'.repeat(2000) })), '')
      assert.match(rules.validateStructuredTextConfig(valid({ content: '😀'.repeat(2001) })), /2000|2,000/)
    })

    await t.test('校验各独立字段与边界，不接受隐式数字转换', () => {
      for (const patch of [
        { fontSizeRpx: 19 }, { fontSizeRpx: 97 }, { fontSizeRpx: 20.5 }, { fontSizeRpx: '44' },
        { fontFamily: 'CUSTOM' }, { fontWeight: 'HEAVY' }, { alignment: 'JUSTIFY' },
        { color: '#fff' }, { color: '#12345678' }, { color: null },
        { marginTopRpx: -4 }, { marginBottomRpx: 132 }, { marginTopRpx: 27 },
        { content: ' \n ' }, { content: 23 }, { type: 'UNKNOWN' }, { blockKey: '' }
      ]) assert.notEqual(rules.validateStructuredTextConfig(valid(patch)), '', JSON.stringify(patch))
      for (const patch of [{ fontSizeRpx: 20 }, { fontSizeRpx: 96 }, { color: '#aAbBcC' }, { marginTopRpx: 128 }]) {
        assert.equal(rules.validateStructuredTextConfig(valid(patch)), '')
      }
    })

    await t.test('区块数、唯一标识、列表数与非空内容共同约束保存', () => {
      const blocks = Array.from({ length: 20 }, (_, i) => ({ blockKey: String(i), type: 'PARAGRAPH', content: 'x' }))
      assert.equal(rules.validateStructuredTextConfig({ blocks }), '')
      assert.match(rules.validateStructuredTextConfig({ blocks: [...blocks, { ...blocks[0], blockKey: '20' }] }), /20/)
      assert.notEqual(rules.validateStructuredTextConfig({ blocks: [blocks[0], blocks[0]] }), '')
      assert.notEqual(rules.validateStructuredTextConfig({ blocks: [] }), '')
      assert.notEqual(rules.validateStructuredTextConfig({ blocks: [{ type: 'SPACER', blockKey: 's' }] }), '')
      assert.equal(rules.validateStructuredTextConfig({ blocks: [{ type: 'SPACER', blockKey: 's' }] }, { requireContent: false }), '')
      for (const items of [[], [' '], ['ok', ''], Array(11).fill('x'), [123], null]) {
        assert.notEqual(rules.validateStructuredTextConfig(valid({ type: 'LIST', items })), '')
      }
      assert.equal(rules.validateStructuredTextConfig(valid({ type: 'LIST', items: Array(10).fill('x') })), '')
      assert.notEqual(rules.validateStructuredTextConfig({ blocks: [{ type: 'SPACER', blockKey: 's', heightRpx: 3 }] }, { requireContent: false }), '')
    })

    await t.test('背景缺失默认关闭，普通与结构化字段隔离，团队保留成员身份', () => {
      assert.deepEqual(rules.normalizeTextBackground({}), {
        backgroundEnabled: false, backgroundTreatment: 'GRADIENT', verticalAlignment: 'CENTER'
      })
      const input = freeze({ backgroundEnabled: true, backgroundWorkId: 7, backgroundMemberUserId: 9,
        backgroundTreatment: 'DARK_MASK', verticalAlignment: 'BOTTOM', content: '不属于背景' })
      assert.deepEqual(rules.normalizeTextBackground(input, { team: true, structured: true }), {
        backgroundEnabled: true, backgroundWorkId: 7, backgroundMemberUserId: 9, backgroundTreatment: 'DARK_MASK'
      })
      assert.equal(Object.hasOwn(rules.normalizeTextBackground(input), 'backgroundMemberUserId'), false)
      assert.equal(rules.normalizeTextBackground({ backgroundEnabled: 'false' }).backgroundEnabled, 'false')
    })

    await t.test('背景开启必选资源、团队必选成员、拒绝已知无效或视频资源', () => {
      assert.equal(rules.validateTextBackground({}), '')
      assert.notEqual(rules.validateTextBackground({ backgroundEnabled: true }), '')
      const background = { backgroundEnabled: true, backgroundWorkId: 7 }
      assert.equal(rules.validateTextBackground(background), '')
      assert.notEqual(rules.validateTextBackground(background, { team: true }), '')
      assert.equal(rules.validateTextBackground({ ...background, backgroundMemberUserId: 9 }, { team: true }), '')
      for (const patch of [{ backgroundWorkId: -1 }, { backgroundWorkId: {} }, { backgroundEnabled: 'true' },
        { backgroundTreatment: 'CUSTOM' }, { verticalAlignment: 'LEFT' }, { backgroundInvalid: true },
        { backgroundWork: { workId: 7, mediaType: 'VIDEO', url: 'video.mp4' } },
        { backgroundWork: { workId: 7, mediaType: 'IMAGE' } },
        { backgroundWork: { workId: 99, mediaType: 'IMAGE', url: 'https://example.test/old.jpg' } }]) {
        assert.notEqual(rules.validateTextBackground({ ...background, ...patch }), '')
      }
      for (const mediaType of ['IMAGE', 'ANIMATION']) {
        assert.equal(rules.validateTextBackground({ ...background, backgroundWork: { workId: 7, mediaType, url: 'image.gif' } }), '')
      }
    })

    await t.test('最终配置白名单剔除展示资源、隐藏内容、留白字体与关闭背景身份', () => {
      const input = freeze({ backgroundEnabled: false, backgroundWorkId: 7, backgroundMemberUserId: 9,
        backgroundTreatment: 'ORIGINAL', verticalAlignment: 'BOTTOM', backgroundWork: { url: 'secret' },
        blocks: [{ blockKey: 's', type: 'SPACER', heightRpx: 32, content: '丢弃', items: ['丢弃'],
          color: '#FFFFFF', fontSizeRpx: 77, displayColor: '#FFFFFF', style: 'bad' },
        { blockKey: 'p', type: 'PARAGRAPH', content: '保留\n原文 ', items: ['丢弃'], heightRpx: 64, displayColor: 'bad' }] })
      const output = rules.finalizeStructuredTextConfig(input, { team: true })
      assert.equal(Object.hasOwn(output, 'backgroundWork'), false)
      assert.equal(Object.hasOwn(output, 'backgroundWorkId'), false)
      assert.equal(Object.hasOwn(output, 'backgroundMemberUserId'), false)
      assert.equal(Object.hasOwn(output, 'verticalAlignment'), false)
      assert.equal(output.backgroundTreatment, 'ORIGINAL')
      assert.deepEqual(output.blocks[0], { blockKey: 's', type: 'SPACER', heightRpx: 32, marginTopRpx: 0, marginBottomRpx: 0 })
      assert.equal(output.blocks[1].content, '保留\n原文 ')
      for (const field of ['items', 'heightRpx', 'displayColor', 'style']) assert.equal(Object.hasOwn(output.blocks[1], field), false)
      assert.deepEqual(rules.finalizeTextBackground(input, { team: true }), {
        backgroundEnabled: false, backgroundTreatment: 'ORIGINAL', verticalAlignment: 'BOTTOM'
      })
    })
  })
}

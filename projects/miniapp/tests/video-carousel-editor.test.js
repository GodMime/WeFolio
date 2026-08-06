const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MINIAPP_ROOT = path.resolve(__dirname, '..')

test('personal video carousel row exposes its edit affordance and accessibility', () => {
  const modulePath = path.join(
    MINIAPP_ROOT,
    'pages/portfolios/standard-edit/component-rows.wxs'
  )
  delete require.cache[require.resolve(modulePath)]
  const componentRows = require(modulePath)

  assert.equal(componentRows.isEditable('VIDEO_CAROUSEL'), true)
  assert.equal(componentRows.resolveAriaRole('VIDEO_CAROUSEL'), 'button')
  assert.equal(
    componentRows.resolveAriaLabel('VIDEO_CAROUSEL', '视频轮播'),
    '编辑视频轮播'
  )
})

test('personal video editor is a filter sheet rather than a two-step wizard', () => {
  const root = path.join(MINIAPP_ROOT, 'pages/portfolios/standard-edit')
  const js = fs.readFileSync(path.join(root, 'portfolio-standard-edit.js'), 'utf8')
  const wxml = fs.readFileSync(path.join(root, 'portfolio-standard-edit.wxml'), 'utf8')
  const wxss = fs.readFileSync(path.join(root, 'portfolio-standard-edit.wxss'), 'utf8')
  assert.match(js, /\/api\/mine\/portfolios\/components\/video-carousel\/works/)
  assert.match(js, /videoCarouselSelectedWorks/)
  assert.doesNotMatch(js, /mediaType:\s*['"]VIDEO['"]/)
  assert.doesNotMatch(wxml, /第一步|第二步/)
  assert.match(wxml, /videoCarouselTitle/)
  assert.match(wxml, /videoCarouselShowSwipeHint/)
  assert.match(wxml, /disabled="\{\{editingComponentType === 'VIDEO_CAROUSEL' && videoCarouselSelectedWorks.length < 3\}\}"/)
  assert.match(wxss, /video-carousel-editor-settings/)
  assert.match(js, /VIDEO_CAROUSEL:\s*'叠放循环展示视频作品，访客左右滑动浏览、点击播放'/)
  const json = JSON.parse(fs.readFileSync(path.join(root, 'portfolio-standard-edit.json'), 'utf8'))
  assert.equal(Object.hasOwn(json.usingComponents, 'video-carousel'), false)
})

test('team video editor uses members as filters and the dedicated paged source', () => {
  const root = path.join(MINIAPP_ROOT, 'pages/team-portfolios/standard-edit')
  const js = fs.readFileSync(path.join(root, 'team-portfolio-standard-edit.js'), 'utf8')
  const wxml = fs.readFileSync(path.join(root, 'team-portfolio-standard-edit.wxml'), 'utf8')
  const wxss = fs.readFileSync(path.join(root, 'team-portfolio-standard-edit.wxss'), 'utf8')
  assert.match(js, /components\/carousel\/members/)
  assert.match(js, /components\/video-carousel\/members\/\$\{memberUserId\}\/works/)
  assert.match(js, /teamVideoSelectedItems/)
  assert.doesNotMatch(wxml, /第一步|第二步/)
  assert.match(wxml, /teamVideoMembers/)
  assert.match(wxml, /teamVideoShowSwipeHint/)
  assert.match(wxml, /item\.memberDisplayName/)
  assert.match(wxml, /item\.durationText/)
  assert.match(wxml, /disabled="\{\{activeComponentType === 'VIDEO_CAROUSEL' && teamVideoSelectedItems.length < 3\}\}"/)
  assert.match(wxss, /team-video-editor-settings/)
  assert.match(js, /VIDEO_CAROUSEL:\s*'叠放循环展示视频作品，访客左右滑动浏览、点击播放'/)
  const json = JSON.parse(fs.readFileSync(path.join(root, 'team-portfolio-standard-edit.json'), 'utf8'))
  assert.equal(Object.hasOwn(json.usingComponents, 'video-carousel'), false)
})

test('team video selection order is centered inside its circular badge', () => {
  const wxss = fs.readFileSync(
    path.join(MINIAPP_ROOT, 'pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxss'),
    'utf8'
  )
  const rule = wxss.match(/\.team-video-work-order\s*\{([^}]*)\}/)

  assert.ok(rule)
  assert.match(rule[1], /display:\s*flex;/)
  assert.match(rule[1], /align-items:\s*center;/)
  assert.match(rule[1], /justify-content:\s*center;/)
  assert.match(rule[1], /line-height:\s*1;/)
})

test('personal video candidate list renders formatted duration', () => {
  const wxml = fs.readFileSync(
    path.join(MINIAPP_ROOT, 'pages/portfolios/standard-edit/portfolio-standard-edit.wxml'),
    'utf8'
  )
  assert.match(wxml, /item\.durationText/)
})

test('team member-first editors share one accessible member selector contract', () => {
  const sharedStylePath = path.join(
    MINIAPP_ROOT,
    'pages/team-portfolios/styles/team-member-selector.wxss'
  )
  assert.equal(fs.existsSync(sharedStylePath), true)

  const sharedStyle = fs.readFileSync(sharedStylePath, 'utf8')
  const choiceRule = sharedStyle.match(/\.team-member-selector-choice\s*\{([^}]*)\}/)
  const nameRule = sharedStyle.match(/\.team-member-selector-name\s*\{([^}]*)\}/)
  assert.ok(choiceRule)
  assert.match(choiceRule[1], /height:\s*56rpx;/)
  assert.match(choiceRule[1], /padding:\s*0 20rpx;/)
  assert.ok(nameRule)
  assert.match(nameRule[1], /text-overflow:\s*ellipsis;/)

  const childEditors = [
    'carousel',
    'single-work',
    'member-portfolio-grid',
    'member-portfolio-list'
  ]
  childEditors.forEach((componentName) => {
    const root = path.join(
      MINIAPP_ROOT,
      'pages/team-portfolios/components',
      componentName
    )
    const wxml = fs.readFileSync(path.join(root, `${componentName}.wxml`), 'utf8')
    const wxss = fs.readFileSync(path.join(root, `${componentName}.wxss`), 'utf8')
    assert.match(wxss, /@import "\.\.\/\.\.\/styles\/team-member-selector\.wxss";/)
    assert.match(wxml, />选择团队成员</)
    assert.match(wxml, /team-member-selector-scroll/)
    assert.match(wxml, /team-member-selector-row/)
    assert.match(wxml, /team-member-selector-choice/)
    assert.match(wxml, /team-member-selector-name/)
    assert.match(wxml, /aria-role="radio"/)
    assert.match(wxml, /aria-checked=/)
    if (componentName === 'carousel') {
      assert.match(wxml, /team-member-selector-count/)
    }
  })

  const pageRoot = path.join(MINIAPP_ROOT, 'pages/team-portfolios/standard-edit')
  const pageWxml = fs.readFileSync(
    path.join(pageRoot, 'team-portfolio-standard-edit.wxml'),
    'utf8'
  )
  const pageWxss = fs.readFileSync(
    path.join(pageRoot, 'team-portfolio-standard-edit.wxss'),
    'utf8'
  )
  assert.match(pageWxss, /@import "\.\.\/styles\/team-member-selector\.wxss";/)
  assert.match(pageWxml, />选择团队成员</)
  assert.match(pageWxml, /team-member-selector-scroll/)
  assert.match(pageWxml, /team-member-selector-row/)
  assert.match(pageWxml, /team-member-selector-choice/)
  assert.match(pageWxml, /team-member-selector-name/)
  assert.match(pageWxml, /team-member-selector-count/)
  assert.match(pageWxml, /aria-role="radio"/)
  assert.match(pageWxml, /aria-checked=/)
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.join(__dirname, '..')
const read = (relativePath) => fs.readFileSync(path.join(ROOT, relativePath), 'utf8')

function listWxss(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name)
    if (entry.isDirectory()) return listWxss(entryPath)
    return entry.name.endsWith('.wxss') ? [entryPath] : []
  })
}

function readRule(content, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matches = Array.from(content.matchAll(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`, 'g')))
  return matches.length ? matches[matches.length - 1][1] : ''
}

test('global shell uses the TripGlide neutral palette without changing the button reset', () => {
  const wxss = read('app.wxss')
  const pageRule = readRule(wxss, 'page')
  const shellRule = readRule(wxss, '.page-shell')
  const buttonRule = readRule(wxss, 'button')
  const buttonAfterRule = readRule(wxss, 'button::after')

  assert.match(pageRule, /color:\s*#212529/)
  assert.match(pageRule, /background:\s*#f5f6f7/)
  assert.match(pageRule, /"Helvetica Neue"/)
  assert.match(pageRule, /"PingFang SC"/)
  assert.match(pageRule, /"Microsoft YaHei"/)
  assert.match(shellRule, /background:\s*#f5f6f7/)
  assert.doesNotMatch(shellRule, /linear-gradient/)

  assert.match(buttonRule, /margin:\s*0/)
  assert.match(buttonRule, /padding:\s*0/)
  assert.match(buttonRule, /line-height:\s*1/)
  assert.match(buttonRule, /border-radius:\s*0/)
  assert.match(buttonRule, /background:\s*transparent/)
  assert.match(buttonAfterRule, /border:\s*0/)
})

test('miniapp WXSS avoids grid under Skyline', () => {
  for (const absolutePath of listWxss(ROOT)) {
    const wxss = fs.readFileSync(absolutePath, 'utf8')
    assert.doesNotMatch(
      wxss,
      /display:\s*grid|grid-template-/,
      path.relative(ROOT, absolutePath)
    )
  }
})

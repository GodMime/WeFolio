const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function readProjectFile(filePath) {
  return fs.readFileSync(path.join(__dirname, '..', filePath), 'utf8')
}

function readJson(filePath) {
  return JSON.parse(readProjectFile(filePath))
}

test('app registers team pages and mine entry navigates to teams page', () => {
  const appJson = readJson('app.json')
  const indexJs = readProjectFile('pages/index/index.js')

  assert.ok(appJson.pages.includes('pages/teams/teams'))
  assert.ok(appJson.pages.includes('pages/team-maintenance/team-maintenance'))
  assert.match(indexJs, /type === 'teams'[\s\S]*wx\.navigateTo\(\{[\s\S]*url:\s*'\/pages\/teams\/teams'/)
})

test('teams page contains list cards and slide-down create form', () => {
  const teamsJs = readProjectFile('pages/teams/teams.js')
  const teamsWxml = readProjectFile('pages/teams/teams.wxml')
  const teamsWxss = readProjectFile('pages/teams/teams.wxss')

  assert.match(teamsJs, /createFormVisible:\s*false/)
  assert.match(teamsJs, /handleCreateToggle\(\)/)
  assert.match(teamsJs, /url:\s*'\/api\/mine\/teams'/)
  assert.match(teamsJs, /uploadTeamAvatar\(created\.team\.teamId/)
  assert.match(teamsWxml, /navigation-bar title="我的团队"/)
  assert.match(teamsWxml, /wx:for="\{\{teamData\.teams\}\}"/)
  assert.match(teamsWxml, /bindtap="handleTeamTap"/)
  assert.match(teamsWxml, /wx:if="\{\{createFormVisible\}\}"/)
  assert.match(teamsWxml, /open-type="chooseAvatar"/)
  assert.match(teamsWxml, /bindtap="handleCreateTeam"/)
  assert.match(teamsWxss, /\.create-form-panel/)
  assert.match(teamsWxss, /\.team-card/)
})

test('team maintenance page renders editable team profile and member controls', () => {
  const pageJs = readProjectFile('pages/team-maintenance/team-maintenance.js')
  const pageWxml = readProjectFile('pages/team-maintenance/team-maintenance.wxml')
  const pageWxss = readProjectFile('pages/team-maintenance/team-maintenance.wxss')

  assert.match(pageJs, /teamId:\s*null/)
  assert.match(pageJs, /url:\s*`\/api\/mine\/teams\/\$\{this\.data\.teamId\}`/)
  assert.match(pageJs, /handleStatusFilterTap/)
  assert.match(pageJs, /handleSaveTeam/)
  assert.match(pageWxml, /navigation-bar title="团队维护"/)
  assert.match(pageWxml, /团队唯一码/)
  assert.match(pageWxml, /data-field="name"/)
  assert.match(pageWxml, /data-field="intro"/)
  assert.match(pageWxml, /open-type="chooseAvatar"/)
  assert.match(pageWxml, /placeholder="输入姓名、职业或个人唯一码"/)
  assert.match(pageWxml, /wx:for="\{\{visibleMembers\}\}"/)
  assert.match(pageWxml, /wx:if="\{\{item\.userStatusVisible\}\}"/)
  assert.match(pageWxml, /bindtap="handleAddMember"/)
  assert.match(pageWxss, /\.team-members-panel/)
  assert.match(pageWxss, /\.member-row/)
})

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

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = content.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
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
  assert.match(teamsWxml, /class="create-section \{\{createFormVisible \? 'expanded' : 'collapsed'\}\}"/)
  assert.match(teamsWxml, /open-type="chooseAvatar"/)
  assert.match(teamsWxml, /bindtap="handleCreateTeam"/)
  assert.match(teamsWxss, /\.create-form-panel/)
  assert.match(teamsWxss, /\.team-card/)
})

test('team create form expands slowly instead of mounting instantly', () => {
  const teamsWxml = readProjectFile('pages/teams/teams.wxml')
  const teamsWxss = readProjectFile('pages/teams/teams.wxss')
  const createSectionRule = readRule(teamsWxss, '.create-section')
  const expandedSectionRule = readRule(teamsWxss, '.create-section.expanded')
  const bodyRule = readRule(teamsWxss, '.create-section-body')

  assert.match(
    teamsWxml,
    /class="create-section \{\{createFormVisible \? 'expanded' : 'collapsed'\}\}"[\s\S]*class="create-section-body"[\s\S]*class="panel create-form-panel"/
  )
  assert.doesNotMatch(teamsWxml, /class="panel create-form-panel" wx:if="\{\{createFormVisible\}\}"/)
  assert.match(createSectionRule, /max-height:\s*0/)
  assert.match(createSectionRule, /overflow:\s*hidden/)
  assert.match(createSectionRule, /transition:[^}]*max-height/)
  assert.match(createSectionRule, /transition:[^}]*opacity/)
  assert.match(createSectionRule, /transition:[^}]*transform/)
  assert.match(createSectionRule, /pointer-events:\s*none/)
  assert.match(expandedSectionRule, /max-height:\s*900rpx/)
  assert.match(expandedSectionRule, /opacity:\s*1/)
  assert.match(expandedSectionRule, /pointer-events:\s*auto/)
  assert.match(bodyRule, /padding-top:\s*22rpx/)
})

test('teams page create button matches the panel content width', () => {
  const teamsWxml = readProjectFile('pages/teams/teams.wxml')
  const teamsWxss = readProjectFile('pages/teams/teams.wxss')
  const pageCreateButtonRule = readRule(teamsWxss, '.teams-content > .primary-button.full')

  assert.match(teamsWxml, /<button class="primary-button full" bindtap="handleCreateToggle"/)
  assert.match(pageCreateButtonRule, /width:\s*100%/)
  assert.match(pageCreateButtonRule, /min-width:\s*100%/)
  assert.match(pageCreateButtonRule, /max-width:\s*100%/)
  assert.match(pageCreateButtonRule, /margin-left:\s*0/)
  assert.match(pageCreateButtonRule, /margin-right:\s*0/)
})

test('team create form edits avatar from icon and aligns actions with fields', () => {
  const teamsWxml = readProjectFile('pages/teams/teams.wxml')
  const teamsWxss = readProjectFile('pages/teams/teams.wxss')
  const avatarFieldRule = readRule(teamsWxss, '.avatar-field')
  const avatarPickerRule = readRule(teamsWxss, '.avatar-picker')
  const avatarNameFieldRule = readRule(teamsWxss, '.avatar-name-field')

  assert.doesNotMatch(teamsWxml, /class="secondary-button avatar-button"/)
  assert.doesNotMatch(teamsWxml, />团队图标</)
  assert.match(
    teamsWxml,
    /<button class="team-logo large avatar-picker"[\s\S]*open-type="chooseAvatar"[\s\S]*bindchooseavatar="handleChooseAvatar"[\s\S]*<\/button>/
  )
  assert.match(
    teamsWxml,
    /class="avatar-field"[\s\S]*class="avatar-name-field form-row"[\s\S]*data-field="name"/
  )
  assert.match(avatarFieldRule, /align-items:\s*flex-start/)
  assert.match(avatarPickerRule, /width:\s*84rpx/)
  assert.match(avatarPickerRule, /height:\s*84rpx/)
  assert.match(avatarPickerRule, /margin-top:\s*43rpx/)
  assert.match(avatarPickerRule, /padding:\s*0/)
  assert.match(avatarNameFieldRule, /flex:\s*1/)
})

test('team create save button sits below the white create panel', () => {
  const teamsWxml = readProjectFile('pages/teams/teams.wxml')
  const teamsWxss = readProjectFile('pages/teams/teams.wxss')
  const pageButtonRule = readRule(teamsWxss, '.teams-content > .primary-button.full')
  const createSaveButtonRule = readRule(teamsWxss, '.create-section-body > .primary-button.full')

  assert.match(
    teamsWxml,
    /class="panel create-form-panel"[\s\S]*class="create-note"[\s\S]*<\/view>\s*<button class="primary-button full create-save-button"[\s\S]*>保存创建<\/button>/
  )
  assert.doesNotMatch(teamsWxss, /\.create-form-panel\s*>\s*\.primary-button\.full/)
  assert.match(pageButtonRule, /width:\s*100%/)
  assert.match(pageButtonRule, /min-width:\s*100%/)
  assert.match(pageButtonRule, /max-width:\s*100%/)
  assert.match(pageButtonRule, /margin-left:\s*0/)
  assert.match(pageButtonRule, /margin-right:\s*0/)
  assert.match(createSaveButtonRule, /width:\s*100%/)
  assert.match(createSaveButtonRule, /min-width:\s*100%/)
  assert.match(createSaveButtonRule, /max-width:\s*100%/)
  assert.match(createSaveButtonRule, /margin-left:\s*0/)
  assert.match(createSaveButtonRule, /margin-right:\s*0/)
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

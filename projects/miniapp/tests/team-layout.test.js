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
  assert.ok(appJson.pages.includes('pages/team-member-add/team-member-add'))
  assert.ok(appJson.pages.includes('pages/team-invitations/team-invitations'))
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
  assert.doesNotMatch(teamsWxml, /item\.actionText/)
  assert.doesNotMatch(teamsWxml, /class="mini-action"/)
  assert.match(teamsWxss, /\.create-form-panel/)
  assert.match(teamsWxss, /\.team-card/)
  assert.doesNotMatch(teamsWxss, /\.mini-action/)
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

test('team maintenance status filters use Skyline compatible flex columns', () => {
  const pageWxml = readProjectFile('pages/team-maintenance/team-maintenance.wxml')
  const pageWxss = readProjectFile('pages/team-maintenance/team-maintenance.wxss')
  const segmentedRule = readRule(pageWxss, '.segmented')
  const segmentRule = readRule(pageWxss, '.segmented .segment')
  const activeSegmentRule = readRule(pageWxss, '.segment.active')

  assert.match(pageWxml, /class="segmented"[\s\S]*class="segment \{\{statusFilter === item\.key \? 'active' : ''\}\}"/)
  assert.match(segmentedRule, /display:\s*flex/)
  assert.match(segmentedRule, /padding:\s*8rpx/)
  assert.match(segmentedRule, /border:\s*1rpx solid #d8e0e8/)
  assert.match(segmentedRule, /background:\s*rgba\(255,\s*255,\s*255,\s*0\.94\)/)
  assert.doesNotMatch(segmentedRule, /display:\s*grid/)
  assert.doesNotMatch(segmentedRule, /grid-template-columns/)
  assert.match(segmentRule, /flex:\s*1\s+1\s+0/)
  assert.match(segmentRule, /min-width:\s*0/)
  assert.match(segmentRule, /margin:\s*0/)
  assert.match(segmentRule, /background:\s*transparent/)
  assert.match(activeSegmentRule, /background:\s*#17202a/)
})

test('team maintenance add member action sits in panel heading as outline pill', () => {
  const pageWxml = readProjectFile('pages/team-maintenance/team-maintenance.wxml')
  const pageWxss = readProjectFile('pages/team-maintenance/team-maintenance.wxss')
  const pageJs = readProjectFile('pages/team-maintenance/team-maintenance.js')
  const panelHeadingRule = readRule(pageWxss, '.panel-heading')
  const actionRule = readRule(pageWxss, '.add-member-button')
  const sheetOverlayRule = readRule(pageWxss, '.member-add-sheet-overlay')
  const visibleOverlayRule = readRule(pageWxss, '.member-add-sheet-overlay.visible')
  const sheetRule = readRule(pageWxss, '.member-add-sheet')
  const permissionRowRule = readRule(pageWxss, '.permission-row')
  const lookupInputRule = readRule(pageWxss, '.lookup-input')
  const lookupButtonRule = readRule(pageWxss, '.lookup-button')

  assert.match(
    pageWxml,
    /class="panel-heading"[\s\S]*class="section-title">团队成员<\/view>\s*<view wx:if="\{\{detail\.team\.canManageMembers\}\}" class="add-member-button" bindtap="handleAddMember" aria-role="button" aria-label="添加团队成员">添加成员<\/view>/
  )
  assert.match(pageJs, /memberAddVisible:\s*false/)
  assert.match(pageJs, /handleAddMember\(\)[\s\S]*memberAddVisible:\s*true/)
  assert.doesNotMatch(pageJs, /wx\.navigateTo\(\{[\s\S]*team-member-add/)
  assert.match(pageWxml, /class="member-add-sheet-overlay \{\{memberAddVisible \? 'visible' : ''\}\}" catchtap="handleMemberAddCancel"/)
  assert.match(pageWxml, /class="member-add-sheet" catchtap="noop"/)
  assert.match(pageWxml, /bindinput="handleMemberUniqueCodeInput"/)
  assert.match(pageWxml, /loading="\{\{memberSearching\}\}" bindtap="handleSearchCandidate"/)
  assert.match(pageWxml, /wx:for="\{\{memberRoleOptions\}\}"/)
  assert.match(pageWxml, /class="permission-row \{\{memberInviteForm\.allowPortfolio \? 'active' : ''\}\}"/)
  assert.match(pageWxml, /bindtap="handleInviteMember"/)
  assert.doesNotMatch(pageWxml, /class="mini-action"/)
  assert.doesNotMatch(pageWxss, /\.mini-action/)
  assert.match(panelHeadingRule, /justify-content:\s*space-between/)
  assert.match(actionRule, /flex:\s*none/)
  assert.match(actionRule, /width:\s*128rpx/)
  assert.match(actionRule, /min-width:\s*0/)
  assert.match(actionRule, /margin-left:\s*auto/)
  assert.match(actionRule, /margin-right:\s*0/)
  assert.match(actionRule, /padding:\s*0 12rpx/)
  assert.match(actionRule, /border:\s*1rpx solid #cbd6e2/)
  assert.match(actionRule, /background:\s*#f7fafc/)
  assert.match(sheetOverlayRule, /position:\s*fixed/)
  assert.match(sheetOverlayRule, /align-items:\s*flex-end/)
  assert.match(sheetOverlayRule, /pointer-events:\s*none/)
  assert.match(visibleOverlayRule, /pointer-events:\s*auto/)
  assert.match(sheetRule, /transform:\s*translateY\(32rpx\)/)
  assert.match(sheetRule, /overflow-y:\s*auto/)
  assert.match(permissionRowRule, /display:\s*flex/)
  assert.match(permissionRowRule, /justify-content:\s*flex-start/)
  assert.match(lookupInputRule, /flex:\s*1\s+1\s+0/)
  assert.match(lookupInputRule, /width:\s*0/)
  assert.match(lookupButtonRule, /flex:\s*0\s+0\s+112rpx/)
  assert.match(lookupButtonRule, /width:\s*112rpx/)
  assert.match(lookupButtonRule, /min-width:\s*112rpx/)
  assert.match(lookupButtonRule, /max-width:\s*112rpx/)
  assert.match(lookupButtonRule, /padding:\s*0/)
})

test('team maintenance profile header uses avatar picker and copyable team code', () => {
  const pageJs = readProjectFile('pages/team-maintenance/team-maintenance.js')
  const pageWxml = readProjectFile('pages/team-maintenance/team-maintenance.wxml')
  const pageWxss = readProjectFile('pages/team-maintenance/team-maintenance.wxss')
  const identityRule = readRule(pageWxss, '.team-identity')
  const avatarPickerRule = readRule(pageWxss, '.avatar-picker')
  const codeButtonRule = readRule(pageWxss, '.role-pill.team-code-button')
  const rolePillRule = readRule(pageWxss, '.role-pill')
  const saveButtonRule = readRule(pageWxss, '.profile-panel > .primary-button.full')

  assert.match(
    pageWxml,
    /<button wx:if="\{\{detail\.team\.canMaintain\}\}" class="team-logo large avatar-picker"[\s\S]*open-type="chooseAvatar"[\s\S]*bindchooseavatar="handleChooseAvatar"[\s\S]*aria-label="编辑团队图标"/
  )
  assert.match(pageWxml, /<view wx:else class="team-logo large"/)
  assert.doesNotMatch(pageWxml, /class="secondary-button avatar-button"/)
  assert.doesNotMatch(pageWxml, />更换</)
  assert.match(
    pageWxml,
    /class="team-heading"[\s\S]*class="pill-row"[\s\S]*class="role-pill blue">\{\{detail\.team\.memberText\}\}<\/view>[\s\S]*class="role-pill blue">\{\{detail\.team\.roleText\}\}<\/view>[\s\S]*class="role-pill teal team-code-button" data-code="\{\{detail\.team\.uniqueCode\}\}" bindtap="handleCopyTeamCode"/
  )
  assert.doesNotMatch(pageWxml, /class="team-code-label"/)
  assert.match(pageWxml, /class="role-pill teal team-code-button"[^>]*>\{\{detail\.team\.displayUniqueCode\}\}<\/view>/)
  assert.match(pageJs, /handleCopyTeamCode\(/)
  assert.match(pageJs, /wx\.setClipboardData\(\{[\s\S]*data:\s*uniqueCode/)
  assert.match(pageJs, /title:\s*'已复制团队唯一码'/)
  assert.match(identityRule, /align-items:\s*flex-start/)
  assert.match(avatarPickerRule, /padding:\s*0/)
  assert.match(codeButtonRule, /width:\s*200rpx/)
  assert.match(codeButtonRule, /min-width:\s*200rpx/)
  assert.match(codeButtonRule, /max-width:\s*200rpx/)
  assert.match(codeButtonRule, /margin-left:\s*auto/)
  assert.match(codeButtonRule, /text-align:\s*center/)
  assert.doesNotMatch(codeButtonRule, /text-overflow:\s*ellipsis/)
  assert.match(rolePillRule, /height:\s*38rpx/)
  assert.match(rolePillRule, /min-height:\s*38rpx/)
  assert.match(rolePillRule, /display:\s*flex/)
  assert.match(rolePillRule, /align-items:\s*center/)
  assert.match(rolePillRule, /justify-content:\s*center/)
  assert.match(rolePillRule, /padding:\s*0 14rpx/)
  assert.match(saveButtonRule, /width:\s*100%/)
  assert.match(saveButtonRule, /margin-left:\s*0/)
  assert.match(saveButtonRule, /margin-right:\s*0/)
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

test('team member add page provides candidate lookup role and permission controls', () => {
  const pageJs = readProjectFile('pages/team-member-add/team-member-add.js')
  const pageWxml = readProjectFile('pages/team-member-add/team-member-add.wxml')
  const pageWxss = readProjectFile('pages/team-member-add/team-member-add.wxss')
  const pageJson = readJson('pages/team-member-add/team-member-add.json')

  assert.equal(pageJson.usingComponents['navigation-bar'], '/components/navigation-bar/navigation-bar')
  assert.match(pageJs, /url:\s*`\/api\/mine\/teams\/\$\{this\.data\.teamId\}\/member-candidate`/)
  assert.match(pageJs, /url:\s*`\/api\/mine\/teams\/\$\{this\.data\.teamId\}\/members`/)
  assert.match(pageJs, /roleOptions:[\s\S]*MANAGER[\s\S]*MEMBER/)
  assert.match(pageJs, /allowPortfolio:\s*true/)
  assert.match(pageJs, /allowProfile:\s*true/)
  assert.match(pageJs, /allowWorks:\s*false/)
  assert.match(pageWxml, /navigation-bar title="添加成员" back="\{\{true\}\}"/)
  assert.match(pageWxml, /个人唯一码/)
  assert.match(pageWxml, /bindtap="handleSearchCandidate"/)
  assert.match(pageWxml, /团队角色/)
  assert.match(pageWxml, /可被团队作品集引用/)
  assert.match(pageWxml, /bindtap="handleInviteMember"/)
  assert.match(pageWxss, /\.member-add-page/)
  assert.match(pageWxss, /\.permission-row/)
})

test('team invitations page supports accept and reject actions', () => {
  const pageJs = readProjectFile('pages/team-invitations/team-invitations.js')
  const pageWxml = readProjectFile('pages/team-invitations/team-invitations.wxml')
  const pageWxss = readProjectFile('pages/team-invitations/team-invitations.wxss')
  const pageJson = readJson('pages/team-invitations/team-invitations.json')

  assert.equal(pageJson.usingComponents['navigation-bar'], '/components/navigation-bar/navigation-bar')
  assert.match(pageJs, /url:\s*`\/api\/mine\/team-invitations\/\$\{this\.data\.memberId\}`/)
  assert.match(pageJs, /url:\s*`\/api\/mine\/team-invitations\/\$\{this\.data\.memberId\}\/accept`/)
  assert.match(pageJs, /url:\s*`\/api\/mine\/team-invitations\/\$\{this\.data\.memberId\}\/reject`/)
  assert.match(pageWxml, /navigation-bar title="团队邀请" back="\{\{true\}\}"/)
  assert.match(pageWxml, /bindtap="handleAccept"/)
  assert.match(pageWxml, /bindtap="handleReject"/)
  assert.match(pageWxss, /\.team-invitations-page/)
  assert.match(pageWxss, /\.invitation-actions/)
})

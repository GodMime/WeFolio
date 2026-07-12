const TEAM_NAME_MAX_LENGTH = 100
const TEAM_INTRO_MAX_LENGTH = 1000

function text(value) {
  return String(value || '').trim()
}

function createDefaultTeamProfileConfig(source = {}) {
  const team = source.team && typeof source.team === 'object' ? source.team : {}
  return { team: Object.assign({}, team) }
}

function normalizeTeamProfile(config = {}) {
  const normalized = createDefaultTeamProfileConfig(config)
  normalized.team = {
    teamId: Number(normalized.team.teamId) > 0 ? Number(normalized.team.teamId) : null,
    avatarUrl: text(normalized.team.avatarUrl),
    teamName: text(normalized.team.teamName),
    intro: text(normalized.team.intro)
  }
  return normalized
}

function validateTeamProfile(team = {}) {
  const normalized = normalizeTeamProfile({ team }).team
  if (!normalized.teamId) return { valid: false, message: '团队资料无效' }
  if (!normalized.teamName) return { valid: false, message: '请填写团队名称' }
  if (normalized.teamName.length > TEAM_NAME_MAX_LENGTH) return { valid: false, message: '团队名称不能超过100字' }
  if (normalized.intro.length > TEAM_INTRO_MAX_LENGTH) return { valid: false, message: '团队简介不能超过1000字' }
  return { valid: true, message: '' }
}

function buildTeamProfileConfig(team = {}) {
  const teamId = Number(team.teamId)
  return { team: { teamId: Number.isFinite(teamId) && teamId > 0 ? teamId : null } }
}

function syncTeamProfileDraft(component) {
  component.setData({ draft: normalizeTeamProfile({ team: component.properties.team }), errorMessage: '' })
}

Component({
  properties: {
    team: {
      type: Object,
      value: {},
      observer() { if (!this.properties.editMode) syncTeamProfileDraft(this) }
    },
    config: { type: Object, value: {} },
    editMode: {
      type: Boolean,
      value: false,
      observer(value, oldValue) { if (value !== oldValue) syncTeamProfileDraft(this) }
    }
  },
  data: {
    draft: createDefaultTeamProfileConfig(),
    errorMessage: ''
  },
  methods: {
    beginEdit() { syncTeamProfileDraft(this) },
    openTeamMaintenance() { this.triggerEvent('maintainteam', { teamId: Number(this.properties.team.teamId) || null }) },
    cancelEdit() {
      syncTeamProfileDraft(this)
      this.triggerEvent('cancel')
    },
    saveEdit() {
      const normalized = normalizeTeamProfile(this.data.draft)
      const validation = validateTeamProfile(normalized.team)
      if (!validation.valid) {
        this.setData({ errorMessage: validation.message })
        return
      }
      this.triggerEvent('save', { config: buildTeamProfileConfig(normalized.team) })
    }
  },
  lifetimes: {
    attached() { syncTeamProfileDraft(this) }
  }
})

module.exports = {
  TEAM_INTRO_MAX_LENGTH,
  TEAM_NAME_MAX_LENGTH,
  buildTeamProfileConfig,
  createDefaultTeamProfileConfig,
  normalizeTeamProfile,
  validateTeamProfile
}

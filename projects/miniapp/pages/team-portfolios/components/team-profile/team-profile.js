const TEAM_NAME_MAX_LENGTH = 100
const TEAM_INTRO_MAX_LENGTH = 1000
const TEAM_PROFILE_VISIBLE_FIELD_DEFAULTS = Object.freeze({
  avatar: true,
  teamName: true,
  intro: true
})
const TEAM_PROFILE_VISIBLE_FIELD_OPTIONS = Object.freeze([
  Object.freeze({ field: 'avatar', label: '团队头像' }),
  Object.freeze({ field: 'teamName', label: '团队名称' }),
  Object.freeze({ field: 'intro', label: '团队简介' })
])

function text(value) {
  return String(value || '').trim()
}

function normalizeTeamProfileVisibleFields(visibleFields = {}) {
  return Object.keys(TEAM_PROFILE_VISIBLE_FIELD_DEFAULTS).reduce((result, field) => {
    result[field] = Object.prototype.hasOwnProperty.call(visibleFields, field)
      ? Boolean(visibleFields[field])
      : TEAM_PROFILE_VISIBLE_FIELD_DEFAULTS[field]
    return result
  }, {})
}

function buildTeamProfileVisibleOptions(visibleFields = {}) {
  const normalized = normalizeTeamProfileVisibleFields(visibleFields)
  return TEAM_PROFILE_VISIBLE_FIELD_OPTIONS.map((item) => Object.assign({}, item, {
    checked: normalized[item.field]
  }))
}

function createDefaultTeamProfileConfig(source = {}) {
  const team = source.team && typeof source.team === 'object' ? source.team : {}
  return {
    team: Object.assign({}, team),
    visibleFields: normalizeTeamProfileVisibleFields(source.visibleFields)
  }
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

function buildTeamProfileConfig(team = {}, visibleFields = {}) {
  return normalizeTeamProfile({ team, visibleFields })
}

function syncTeamProfileDraft(component) {
  const draft = normalizeTeamProfile({
    team: component.properties.team,
    visibleFields: component.properties.visibleFields
  })
  component.setData({
    draft,
    visibleFieldOptions: buildTeamProfileVisibleOptions(draft.visibleFields),
    teamNameCounter: `${draft.team.teamName.length} / ${TEAM_NAME_MAX_LENGTH}`,
    introCounter: `${draft.team.intro.length} / ${TEAM_INTRO_MAX_LENGTH}`,
    errorMessage: ''
  })
}

Component({
  properties: {
    team: {
      type: Object,
      value: {},
      observer() { if (!this.properties.editMode) syncTeamProfileDraft(this) }
    },
    visibleFields: {
      type: Object,
      value: {},
      observer() { syncTeamProfileDraft(this) }
    },
    config: { type: Object, value: {} },
    editMode: {
      type: Boolean,
      value: false,
      observer(value, oldValue) { if (value !== oldValue) syncTeamProfileDraft(this) }
    },
    refreshing: { type: Boolean, value: false }
  },
  data: {
    draft: createDefaultTeamProfileConfig(),
    visibleFieldOptions: buildTeamProfileVisibleOptions(),
    teamNameCounter: `0 / ${TEAM_NAME_MAX_LENGTH}`,
    introCounter: `0 / ${TEAM_INTRO_MAX_LENGTH}`,
    errorMessage: ''
  },
  methods: {
    beginEdit() { syncTeamProfileDraft(this) },
    handleInput(event) {
      const field = event.currentTarget.dataset.field
      if (!['teamName', 'intro'].includes(field)) return
      const value = String(event.detail.value || '')
      const patch = { [`draft.team.${field}`]: value, errorMessage: '' }
      patch[field === 'teamName' ? 'teamNameCounter' : 'introCounter'] = `${value.length} / ${field === 'teamName' ? TEAM_NAME_MAX_LENGTH : TEAM_INTRO_MAX_LENGTH}`
      this.setData(patch)
    },
    handleVisibleFieldChange(event) {
      const field = event.currentTarget.dataset.field
      if (!Object.prototype.hasOwnProperty.call(TEAM_PROFILE_VISIBLE_FIELD_DEFAULTS, field)) return
      const visibleFields = Object.assign({}, this.data.draft.visibleFields, {
        [field]: Boolean(event.detail.value)
      })
      this.setData({
        'draft.visibleFields': visibleFields,
        visibleFieldOptions: buildTeamProfileVisibleOptions(visibleFields)
      })
    },
    chooseAvatar() { this.triggerEvent('chooseavatar') },
    refreshFromTeam() {
      if (!this.properties.refreshing) this.triggerEvent('refresh')
    },
    applyAvatar(event = {}) {
      const avatarUrl = text(event.detail && event.detail.avatarUrl)
      if (avatarUrl) this.setData({ 'draft.team.avatarUrl': avatarUrl, errorMessage: '' })
    },
    applyTeamSnapshot(event = {}) {
      const team = event.detail && event.detail.team ? event.detail.team : event.team
      const draft = normalizeTeamProfile({ team, visibleFields: this.data.draft.visibleFields })
      this.setData({
        draft,
        teamNameCounter: `${draft.team.teamName.length} / ${TEAM_NAME_MAX_LENGTH}`,
        introCounter: `${draft.team.intro.length} / ${TEAM_INTRO_MAX_LENGTH}`,
        errorMessage: ''
      })
    },
    applyRefreshError(event = {}) {
      this.setData({ errorMessage: text(event.detail && event.detail.message) || '团队资料刷新失败，请重试' })
    },
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
      this.triggerEvent('save', {
        config: buildTeamProfileConfig(normalized.team, normalized.visibleFields)
      })
    }
  },
  lifetimes: {
    attached() { syncTeamProfileDraft(this) }
  }
})

module.exports = {
  TEAM_INTRO_MAX_LENGTH,
  TEAM_NAME_MAX_LENGTH,
  TEAM_PROFILE_VISIBLE_FIELD_DEFAULTS,
  buildTeamProfileVisibleOptions,
  buildTeamProfileConfig,
  createDefaultTeamProfileConfig,
  normalizeTeamProfileVisibleFields,
  normalizeTeamProfile,
  validateTeamProfile
}

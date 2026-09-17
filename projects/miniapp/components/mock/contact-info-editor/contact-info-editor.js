const INPUT_FIELDS = ['contactPhone', 'contactWechat']
const NUMBER_FIELDS = ['contactBorderWidthRpx', 'horizontalMarginRpx', 'verticalMarginRpx']

Component({
  properties: { config: { type: Object, value: {} } },
  methods: {
    emitPatch(patch) {
      this.triggerEvent('configchange', { config: Object.assign({}, this.data.config, patch) })
    },
    handleInput(event) {
      const field = event.currentTarget.dataset.field
      if (INPUT_FIELDS.includes(field)) this.emitPatch({ [field]: event.detail.value })
    },
    handleNumber(event) {
      const field = event.currentTarget.dataset.field
      if (this.data.config.contactBorder && NUMBER_FIELDS.includes(field)) this.emitPatch({ [field]: Number(event.detail.value) })
    },
    handleBorder(event) { this.emitPatch({ contactBorder: event.detail.value }) },
    handleBorderColor(event) {
      if (this.data.config.contactBorder) this.emitPatch({ contactBorderColor: event.detail.color })
    },
    handleFillProfile() { this.triggerEvent('fillprofile') }
  }
})

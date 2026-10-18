/** 提示弹层只维护组件状态，避免打开说明影响编辑页的草稿与撤销记录。 */
Component({
  data: {
    visible: false
  },

  pageLifetimes: {
    hide() {
      this.handleClose()
    }
  },

  methods: {
    /** 标题与提示共用点击区，保持紧凑布局下的触摸面积。 */
    handleOpen() {
      this.setData({ visible: true })
    },

    handleClose() {
      this.setData({ visible: false })
    },

    /** 拦截面板点击与遮罩滑动，避免误关闭或滚动底层编辑区。 */
    noop() {}
  }
})

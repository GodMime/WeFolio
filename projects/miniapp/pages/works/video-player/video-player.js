const { createVideoPlayerPage } = require('../utils/portfolio-video-player-page')

const FULLSCREEN_EXIT_FAILED = '请先退出全屏，再返回作品'

Page(createVideoPlayerPage({ wxApi: wx, fullscreenExitFailedMessage: FULLSCREEN_EXIT_FAILED }))

-- 字体资源独立保存于草稿与发布快照，旧作品集无需回填。
ALTER TABLE wf_portfolio
    ADD COLUMN draft_font_assets_json JSON NULL COMMENT '草稿字体子集资源清单',
    ADD COLUMN published_font_assets_json JSON NULL COMMENT '发布字体子集资源清单';

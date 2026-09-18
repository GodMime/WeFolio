package com.jxc.wefolio.service.miniappcode;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioMiniappCodeResponse;
import com.jxc.wefolio.entity.BaseEntity;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.AuthenticationRequiredException;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import com.jxc.wefolio.message.PortfolioMiniappCodeApplicationMessage;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 小程序码应用编排：校验分享权限、提取发布快照，并在返回资源前重新校验。
 * 远端生成不包裹数据库事务，避免长事务以及重复读隔离级别导致重检读取旧值。
 */
@Service
@RequiredArgsConstructor
public class PortfolioMiniappCodeApplicationService {

    /** 发布配置中的分享节点名称。 */
    private static final String SHARE_FIELD = "share";
    /** 发布配置中的公开标题字段。 */
    private static final String TITLE_FIELD = "title";
    /** 兼容历史个人配置的空标题。 */
    private static final String PERSONAL_DEFAULT_TITLE = "未命名作品集";
    /** 兼容历史团队配置的空标题。 */
    private static final String TEAM_DEFAULT_TITLE = "团队作品集";
    /** 职业与城市之间的显示分隔符。 */
    private static final String SUBTITLE_SEPARATOR = " · ";

    /** 个人作品集读取入口。 */
    private final PortfolioEntityMapper portfolioEntityMapper;
    /** 所属个人资料读取入口。 */
    private final UserEntityMapper userEntityMapper;
    /** 复用团队可见性权限，允许已加入的普通成员分享。 */
    private final TeamPortfolioAccessService teamPortfolioAccessService;
    /** 负责原码缓存与头像元数据的基础服务。 */
    private final PortfolioMiniappCodeResourceService resourceService;

    /** 为当前维护者持有的已发布标准个人作品集生成小程序码。 */
    public PortfolioMiniappCodeResponse generatePersonal(Long portfolioId, Long actorUserId) {
        requireIdentity(actorUserId);
        return generate(() -> personalSnapshot(portfolioId, actorUserId), actorUserId);
    }

    /** 为当前已加入成员可分享的已发布标准团队作品集生成小程序码。 */
    public PortfolioMiniappCodeResponse generateTeam(Long portfolioId, Long actorUserId) {
        requireIdentity(actorUserId);
        return generate(() -> teamSnapshot(portfolioId, actorUserId), actorUserId);
    }

    /** 无论原码来自缓存还是本次生成，返回前均重新读取权限和公开快照。 */
    private PortfolioMiniappCodeResponse generate(Supplier<PortfolioMiniappCodeSnapshot> snapshotReader,
                                                 Long actorUserId) {
        PortfolioMiniappCodeSnapshot initial = snapshotReader.get();
        PortfolioMiniappCodeResponse result = resourceService.generate(initial, actorUserId);
        PortfolioMiniappCodeSnapshot current = snapshotReader.get();
        if (!initial.equals(current)) {
            throw new BusinessException(PortfolioMiniappCodeApplicationMessage.SNAPSHOT_CHANGED);
        }
        return result;
    }

    /** 读取个人发布快照，头像严格来自所属用户个人资料。 */
    private PortfolioMiniappCodeSnapshot personalSnapshot(Long portfolioId, Long actorUserId) {
        PortfolioEntity portfolio = portfolioId == null ? null : portfolioEntityMapper.selectById(portfolioId);
        if (portfolio == null || isDeleted(portfolio)
                || !PortfolioOwnerTypeDict.USER.getCode().equals(portfolio.getOwnerType())
                || !Objects.equals(actorUserId, portfolio.getOwnerId())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_NOT_FOUND_MESSAGE);
        }
        if (!PortfolioTemplateTypeDict.STANDARD.getCode().equals(portfolio.getTemplateType())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_MAINTENANCE_UNAVAILABLE_MESSAGE);
        }
        if (!PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1.equals(portfolio.getSchemaVersion())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        requirePublished(portfolio, PortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
        UserEntity owner = userEntityMapper.selectById(portfolio.getOwnerId());
        if (owner == null || isDeleted(owner) || !UserStatusDict.ACTIVE.getCode().equals(owner.getStatus())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        return snapshot(portfolio, owner.getUniqueCode(), clean(owner.getNickname()),
                Stream.of(owner.getProfession(), owner.getCity()).filter(StringUtils::hasText)
                        .map(String::strip).collect(Collectors.joining(SUBTITLE_SEPARATOR)),
                publishedTitle(portfolio, PERSONAL_DEFAULT_TITLE), owner.getAvatarUrl());
    }

    /** 读取团队公开资料，绝不读取分享成员的姓名、城市或头像。 */
    private PortfolioMiniappCodeSnapshot teamSnapshot(Long portfolioId, Long actorUserId) {
        if (portfolioId == null) {
            throw new BusinessException(TeamPortfolioMessage.PORTFOLIO_NOT_FOUND);
        }
        TeamPortfolioAccessService.TeamPortfolioAccess access =
                teamPortfolioAccessService.requireVisiblePortfolio(portfolioId, actorUserId);
        PortfolioEntity portfolio = access.portfolio();
        TeamEntity team = access.team();
        if (!access.canShare() || isDeleted(portfolio) || isDeleted(team) || isDeleted(access.membership())) {
            throw new BusinessException(TeamPortfolioMessage.PORTFOLIO_NOT_FOUND);
        }
        requirePublished(portfolio, TeamPortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
        return snapshot(portfolio, team.getUniqueCode(), clean(team.getName()), clean(team.getCity()),
                publishedTitle(portfolio, TEAM_DEFAULT_TITLE), team.getAvatarUrl());
    }

    /** 固定快照仅携带正式版本，不包含随草稿保存递增的实体乐观锁版本。 */
    private PortfolioMiniappCodeSnapshot snapshot(PortfolioEntity portfolio, String uniqueCode,
                                                 String displayName, String subtitle,
                                                 String title, String avatarUrl) {
        return new PortfolioMiniappCodeSnapshot(portfolio.getOwnerType(), portfolio.getOwnerId(),
                portfolio.getId(), portfolio.getPublishedRevision().toString(), portfolio.getShareCode(),
                uniqueCode, displayName, subtitle, title, avatarUrl);
    }

    /** 检查发布态及版本完整性，禁止对草稿或已失效对象生成图片。 */
    private void requirePublished(PortfolioEntity portfolio, String unavailableMessage) {
        if (!PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                || !PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                || !StringUtils.hasText(portfolio.getPublishedConfigJson())
                || !StringUtils.hasText(portfolio.getShareCode())
                || portfolio.getPublishedRevision() == null || portfolio.getPublishedRevision() <= 0) {
            throw new BusinessException(unavailableMessage);
        }
    }

    /** 解析正式发布标题，不读取草稿、封面或历史分享头像。 */
    private String publishedTitle(PortfolioEntity portfolio, String defaultTitle) {
        try {
            JSONObject config = JSON.parseObject(portfolio.getPublishedConfigJson());
            if (config == null) {
                throw new BusinessException(PortfolioMessage.PORTFOLIO_CONFIG_FORMAT_INVALID_MESSAGE);
            }
            JSONObject share = config.getJSONObject(SHARE_FIELD);
            String title = share == null ? null : share.getString(TITLE_FIELD);
            return StringUtils.hasText(title) ? title.strip() : defaultTitle;
        } catch (JSONException | ClassCastException exception) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_CONFIG_FORMAT_INVALID_MESSAGE);
        }
    }

    /** 防御性检查已删除实体；正常 Mapper 查询已默认过滤逻辑删除。 */
    private boolean isDeleted(BaseEntity entity) {
        return entity == null || (entity.getDeleted() != null && entity.getDeleted() != 0L);
    }

    /** 将缺省公开资料规范化为空文本，避免展示空分隔符。 */
    private String clean(String text) {
        return text == null ? "" : text.strip();
    }

    /** 应用入口不接受缺失或无效维护者身份。 */
    private void requireIdentity(Long actorUserId) {
        if (actorUserId == null || actorUserId <= 0L) {
            throw new AuthenticationRequiredException(PortfolioMiniappCodeApplicationMessage.LOGIN_REQUIRED);
        }
    }
}

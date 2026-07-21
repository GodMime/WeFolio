package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.config.ContentLimitProperties;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 内容数量上限服务测试。 */
@ExtendWith(MockitoExtension.class)
class ContentLimitServiceTest {

    @Mock
    private WorkEntityMapper workEntityMapper;

    @Mock
    private PortfolioEntityMapper portfolioEntityMapper;

    private ContentLimitProperties properties;
    private ContentLimitService service;

    @BeforeAll
    static void initializeTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), WorkEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), PortfolioEntity.class);
    }

    @BeforeEach
    void setUp() {
        properties = new ContentLimitProperties();
        properties.setWorkImageMaxCount(2);
        properties.setWorkVideoMaxCount(2);
        properties.setPersonalPortfolioMaxCount(2);
        properties.setTeamPortfolioMaxCount(2);
        service = new ContentLimitService(properties, workEntityMapper, portfolioEntityMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void imageCapacityShouldAllowExactLimitAndScopeCountQuery() {
        when(workEntityMapper.selectCount(any())).thenReturn(1L);

        assertThatCode(() -> service.ensureWorkCapacity(7L, MediaTypeDict.IMAGE.getCode(), 1L))
                .doesNotThrowAnyException();

        ArgumentCaptor<Wrapper<WorkEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(workEntityMapper).selectCount(captor.capture());
        LambdaQueryWrapper<WorkEntity> wrapper = (LambdaQueryWrapper<WorkEntity>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("user_id", "media_type", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(7L, MediaTypeDict.IMAGE.getCode(), 0L);
    }

    @Test
    void imageCapacityShouldRejectOverflow() {
        when(workEntityMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.ensureWorkCapacity(7L, MediaTypeDict.IMAGE.getCode(), 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片作品数量已达上限（2个），请删除部分图片作品后再上传");
    }

    @Test
    void videoCapacityShouldRejectAtLimit() {
        when(workEntityMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.ensureWorkCapacity(7L, MediaTypeDict.VIDEO.getCode(), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("视频作品数量已达上限（2个），请删除部分视频作品后再上传");
    }

    @Test
    void workCapacityShouldSkipZeroAddition() {
        service.ensureWorkCapacity(7L, MediaTypeDict.IMAGE.getCode(), 0L);

        verify(workEntityMapper, never()).selectCount(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void personalPortfolioCapacityShouldRejectAtLimitAndScopeOwner() {
        when(portfolioEntityMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.ensurePersonalPortfolioCapacity(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("个人作品集数量已达上限（2个），请删除部分个人作品集后再新建");

        ArgumentCaptor<Wrapper<PortfolioEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(portfolioEntityMapper).selectCount(captor.capture());
        LambdaQueryWrapper<PortfolioEntity> wrapper = (LambdaQueryWrapper<PortfolioEntity>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("owner_type", "owner_id", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(PortfolioOwnerTypeDict.USER.getCode(), 7L, 0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void teamPortfolioCapacityShouldRejectAtLimitAndScopeOwner() {
        when(portfolioEntityMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.ensureTeamPortfolioCapacity(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前团队作品集数量已达上限（2个），请删除部分团队作品集后再新建");

        ArgumentCaptor<Wrapper<PortfolioEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(portfolioEntityMapper).selectCount(captor.capture());
        LambdaQueryWrapper<PortfolioEntity> wrapper = (LambdaQueryWrapper<PortfolioEntity>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("owner_type", "owner_id", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(PortfolioOwnerTypeDict.TEAM.getCode(), 11L, 0L);
    }
}

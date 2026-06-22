package com.jxc.wefolio.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * MyBatis Plus 配置
 *
 * <p>注册插件：</p>
 * <ul>
 *   <li>{@link OptimisticLockerInnerInterceptor} — 乐观锁，更新时自动 version+1</li>
 *   <li>{@link PaginationInnerInterceptor} — 分页，自动拦截分页查询</li>
 * </ul>
 *
 * <p>Mapper 扫描路径：com.jxc.wefolio.mapper</p>
 */
@Configuration
@MapperScan("com.jxc.wefolio.mapper")
public class MyBatisPlusConfig {

    /**
     * MyBatis Plus 核心拦截器
     *
     * <p>乐观锁拦截器需在分页拦截器之前注册</p>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁：基于 @Version 字段，UPDATE 时自动 WHERE version=? AND SET version=version+1
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 分页：自动识别分页参数，生成数据库方言对应的分页 SQL
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}

package com.jxc.wefolio.service;

import com.jxc.wefolio.mapper.FeedbackEntityMapper;
import com.jxc.wefolio.mapper.FeedbackUploadTaskEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 反馈生产时间源契约测试。
 */
class FeedbackProductionClockTest {

    /** 反馈业务统一时区。 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 修改 JVM 默认时区时使用的并行测试资源锁。 */
    private static final String DEFAULT_TIME_ZONE_RESOURCE = "java.util.TimeZone.default";

    /** 生产服务必须使用上海时区，不能随 JVM 默认时区变化。 */
    @Test
    @ResourceLock(DEFAULT_TIME_ZONE_RESOURCE)
    void productionServicesShouldUseShanghaiClockRegardlessOfJvmDefaultZone() throws Exception {
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            FeedbackUploadService uploadService = new FeedbackUploadService(
                    mock(FeedbackUploadTaskEntityMapper.class),
                    mock(UserEntityMapper.class),
                    mock(CosService.class),
                    new FeedbackUploadFileValidator());
            FeedbackRoundCodec roundCodec = new FeedbackRoundCodec();
            FeedbackTransactionService transactionService = new FeedbackTransactionService(
                    mock(FeedbackEntityMapper.class),
                    mock(UserEntityMapper.class),
                    uploadService,
                    roundCodec,
                    new FeedbackRoundSnapshotValidator(roundCodec, new FeedbackUploadFileValidator()));

            assertThat(readClock(uploadService).getZone()).isEqualTo(SHANGHAI_ZONE);
            assertThat(readClock(transactionService).getZone()).isEqualTo(SHANGHAI_ZONE);
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    /** 读取服务持有的生产时间源。 */
    private Clock readClock(Object service) throws ReflectiveOperationException {
        Field field = service.getClass().getDeclaredField("clock");
        field.setAccessible(true);
        return (Clock) field.get(service);
    }
}

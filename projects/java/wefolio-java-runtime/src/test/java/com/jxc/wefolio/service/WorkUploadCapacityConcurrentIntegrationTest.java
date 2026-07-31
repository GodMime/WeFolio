package com.jxc.wefolio.service;

import com.jxc.wefolio.config.ContentLimitProperties;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.WorkUploadTaskStatusDict;
import com.jxc.wefolio.dto.MineWorkUploadCompleteRequest;
import com.jxc.wefolio.entity.WorkUploadTaskEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WfTagEntityMapper;
import com.jxc.wefolio.mapper.WorkTagEntityMapper;
import com.jxc.wefolio.mapper.WorkUploadTaskEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 作品上传最后一个容量名额的真实事务并发集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
class WorkUploadCapacityConcurrentIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkUploadTransactionService transactionService;

    @Autowired
    private ContentLimitProperties contentLimitProperties;

    @MockBean
    private WorkUploadTaskEntityMapper workUploadTaskEntityMapper;

    @MockBean
    private WfTagEntityMapper wfTagEntityMapper;

    @MockBean
    private WorkTagEntityMapper workTagEntityMapper;

    @MockBean
    private PointService pointService;

    private final Map<Long, WorkUploadTaskEntity> tasks = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_user");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_work");
        jdbcTemplate.execute("""
                CREATE TABLE wf_user (
                  id BIGINT PRIMARY KEY,
                  status VARCHAR(32) NOT NULL,
                  deleted BIGINT NOT NULL
                )
                """);
        jdbcTemplate.update(
                "INSERT INTO wf_user (id, status, deleted) VALUES (7, 'ACTIVE', 0)");
        jdbcTemplate.execute("""
                CREATE TABLE wf_work (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  media_type VARCHAR(32) NOT NULL,
                  title VARCHAR(30) NOT NULL,
                  original_file_name VARCHAR(255),
                  media_object_key VARCHAR(512) NOT NULL,
                  media_sha256 CHAR(64) NOT NULL,
                  cover_object_key VARCHAR(512),
                  cover_sha256 CHAR(64),
                  mime_type VARCHAR(100),
                  file_size BIGINT,
                  duration_ms INT,
                  frame_count INT,
                  cover_frame_number INT,
                  width INT,
                  height INT,
                  aspect_ratio VARCHAR(32),
                  description VARCHAR(1000),
                  service_date DATE,
                  sort_order INT NOT NULL DEFAULT 0,
                  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  audit_status VARCHAR(32),
                  audit_round INT NOT NULL DEFAULT 0,
                  audit_reason_code VARCHAR(64),
                  audit_reason_codes CLOB,
                  audit_reject_reason VARCHAR(1000),
                  deleted_at TIMESTAMP,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL DEFAULT 0,
                  version INT NOT NULL DEFAULT 0,
                  CONSTRAINT uk_test_work_sha UNIQUE (user_id, media_sha256, deleted)
                )
                """);
        contentLimitProperties.setWorkAnimationMaxCount(1);
        tasks.clear();
        tasks.put(101L, animationTask(101L, "a.gif", "a".repeat(64)));
        tasks.put(102L, animationTask(102L, "b.webp", "b".repeat(64)));
        when(workUploadTaskEntityMapper.selectById(any())).thenAnswer(
                invocation -> tasks.get(invocation.getArgument(0)));
        when(workUploadTaskEntityMapper.updateById(any(WorkUploadTaskEntity.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        contentLimitProperties.setWorkAnimationMaxCount(100);
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_work");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_user");
    }

    @Test
    void concurrentAnimationConfirmationsShouldAllowExactlyOneLastSlot() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstReachedPointConsumption = new CountDownLatch(1);
        CountDownLatch releaseFirstConfirmation = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            String businessId = invocation.getArgument(3);
            if ("101".equals(businessId)) {
                firstReachedPointConsumption.countDown();
                if (!releaseFirstConfirmation.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("首个确认等待释放超时");
                }
            }
            return null;
        }).when(pointService).consume(
                any(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
        try {
            Future<Boolean> first = executor.submit(() -> confirm(101L));
            assertThat(firstReachedPointConsumption.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second = executor.submit(() -> {
                secondStarted.countDown();
                return confirm(102L);
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseFirstConfirmation.countDown();

            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            releaseFirstConfirmation.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wf_work WHERE user_id = 7 AND media_type = 'ANIMATION' AND deleted = 0",
                Long.class)).isEqualTo(1L);
    }

    private boolean confirm(Long taskId) {
        try {
            transactionService.confirmUploadedTask(7L, tasks.get(taskId), completeItem(taskId));
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private WorkUploadTaskEntity animationTask(Long taskId, String fileName, String sha256) {
        WorkUploadTaskEntity task = new WorkUploadTaskEntity();
        task.setId(taskId);
        task.setUserId(7L);
        task.setMediaType(MediaTypeDict.ANIMATION.getCode());
        task.setOriginalFileName(fileName);
        task.setObjectKey("WFA3B1E7A2/work/animation/" + fileName);
        task.setFileSha256(sha256);
        task.setCoverObjectKey("WFA3B1E7A2/work/animation/" + fileName + "-thumb.jpg");
        task.setCoverSha256("c".repeat(64));
        task.setMimeType(fileName.endsWith(".gif") ? "image/gif" : "image/webp");
        task.setFileSize(1024L);
        task.setFrameCount(2);
        task.setWidth(320);
        task.setHeight(240);
        task.setStatus(WorkUploadTaskStatusDict.UPLOADED.getCode());
        task.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return task;
    }

    private MineWorkUploadCompleteRequest.CompleteItem completeItem(Long taskId) {
        MineWorkUploadCompleteRequest.CompleteItem item = new MineWorkUploadCompleteRequest.CompleteItem();
        item.setTaskId(taskId);
        item.setTitle("动图 " + taskId);
        item.setAspectRatio("4:3");
        item.setIdempotencyKey("confirm-" + taskId);
        return item;
    }
}

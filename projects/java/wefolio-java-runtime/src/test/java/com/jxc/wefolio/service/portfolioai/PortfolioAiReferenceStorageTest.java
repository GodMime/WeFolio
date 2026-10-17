package com.jxc.wefolio.service.portfolioai;

import com.jxc.wefolio.exception.BusinessException;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.BucketVersioningConfiguration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 短超时存储适配的删除前提和错误分类，不访问真实服务。 */
class PortfolioAiReferenceStorageTest {
    /** 版本控制开启或挂起都不能执行只生成删除标记的删除。 */
    @Test void refusesDeletionWhenVersioningIsNotOff() {
        for(String status:new String[]{BucketVersioningConfiguration.ENABLED,BucketVersioningConfiguration.SUSPENDED}) {
            COSClient client=mock(COSClient.class);when(client.getBucketVersioningConfiguration("bucket")).thenReturn(new BucketVersioningConfiguration(status));
            assertThatThrownBy(()->new PortfolioAiReferenceStorage("bucket",client).delete("key")).isInstanceOf(BusinessException.class);
            verify(client,never()).deleteObject(anyString(),anyString());
        }
    }
    /** 每次删除前检查桶状态，不能仅在第一次上传时检查一次。 */
    @Test void verifiesVersioningForEveryDeletion() {
        COSClient client=mock(COSClient.class);when(client.getBucketVersioningConfiguration("bucket")).thenReturn(new BucketVersioningConfiguration(BucketVersioningConfiguration.OFF));
        PortfolioAiReferenceStorage storage=new PortfolioAiReferenceStorage("bucket",client);storage.delete("one");storage.delete("two");
        verify(client,times(2)).getBucketVersioningConfiguration("bucket");verify(client).deleteObject("bucket","one");verify(client).deleteObject("bucket","two");
    }
    /** 只有404可解释为不存在，403必须使保护和清理失败关闭。 */
    @Test void distinguishesMissingFromForbidden() {
        COSClient client=mock(COSClient.class);CosServiceException missing=new CosServiceException("missing");missing.setStatusCode(404);
        CosServiceException forbidden=new CosServiceException("forbidden");forbidden.setStatusCode(403);
        when(client.getObjectMetadata("bucket","key")).thenThrow(missing).thenThrow(forbidden);
        PortfolioAiReferenceStorage storage=new PortfolioAiReferenceStorage("bucket",client);
        assertThat(storage.head("key")).isNull();assertThatThrownBy(()->storage.head("key")).isSameAs(forbidden);
    }
}

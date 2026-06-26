package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dict.MessageCategoryDict;
import com.jxc.wefolio.dict.MessageReadStatusDict;
import com.jxc.wefolio.dto.MineMessageListResponse;
import com.jxc.wefolio.dto.MineMessageReadRequest;
import com.jxc.wefolio.dto.MineMessageUnreadCountResponse;
import com.jxc.wefolio.service.MineMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的消息控制器测试 — 确认系统消息接口路径、登录态注解和服务委托。
 */
@ExtendWith(MockitoExtension.class)
class MineMessageControllerTest {

    /** 我的消息服务模拟 */
    @Mock
    private MineMessageService mineMessageService;

    @Test
    void messageEndpointsUseMaintainerAccessAndDelegateToService() throws NoSuchMethodException {
        MineMessageController controller = new MineMessageController(mineMessageService);
        MineMessageListResponse listResponse = new MineMessageListResponse();
        MineMessageUnreadCountResponse countResponse = new MineMessageUnreadCountResponse();
        MineMessageUnreadCountResponse readResponse = new MineMessageUnreadCountResponse();
        MineMessageReadRequest readRequest = new MineMessageReadRequest();
        MineMessageReadRequest readAllRequest = new MineMessageReadRequest();
        when(mineMessageService.listMessages(
                MessageReadStatusDict.UNREAD.getCode(),
                MessageCategoryDict.TEAM.getCode(),
                88L,
                20
        )).thenReturn(listResponse);
        when(mineMessageService.getUnreadCount()).thenReturn(countResponse);
        when(mineMessageService.markRead(readRequest)).thenReturn(readResponse);
        when(mineMessageService.markAllRead(readAllRequest)).thenReturn(readResponse);

        Response<MineMessageListResponse> messages = controller.messages(
                MessageReadStatusDict.UNREAD.getCode(),
                MessageCategoryDict.TEAM.getCode(),
                88L,
                20
        );
        Response<MineMessageUnreadCountResponse> unreadCount = controller.unreadCount();
        Response<MineMessageUnreadCountResponse> markedRead = controller.markRead(readRequest);
        Response<MineMessageUnreadCountResponse> markedAllRead = controller.markAllRead(readAllRequest);

        assertThat(MineMessageController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertGetMapping("messages", "/api/mine/messages");
        assertGetMapping("unreadCount", "/api/mine/messages/unread-count");
        assertPutMapping("markRead", "/api/mine/messages/read");
        assertPutMapping("markAllRead", "/api/mine/messages/read-all");
        assertParameterAnnotations();
        assertThat(messages.getData()).isSameAs(listResponse);
        assertThat(unreadCount.getData()).isSameAs(countResponse);
        assertThat(markedRead.getData()).isSameAs(readResponse);
        assertThat(markedAllRead.getData()).isSameAs(readResponse);
        verify(mineMessageService).listMessages(
                MessageReadStatusDict.UNREAD.getCode(),
                MessageCategoryDict.TEAM.getCode(),
                88L,
                20
        );
        verify(mineMessageService).getUnreadCount();
        verify(mineMessageService).markRead(readRequest);
        verify(mineMessageService).markAllRead(readAllRequest);
    }

    /**
     * 断言 GET 映射路径。
     *
     * @param methodName 方法名
     * @param path 接口路径
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    private void assertGetMapping(String methodName, String path) throws NoSuchMethodException {
        Method method = MineMessageController.class.getMethod(methodName, methodParameterTypes(methodName));
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    /**
     * 断言 PUT 映射路径。
     *
     * @param methodName 方法名
     * @param path 接口路径
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    private void assertPutMapping(String methodName, String path) throws NoSuchMethodException {
        Method method = MineMessageController.class.getMethod(methodName, methodParameterTypes(methodName));
        PutMapping mapping = method.getAnnotation(PutMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    /**
     * 断言查询参数与请求体注解存在。
     *
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    private void assertParameterAnnotations() throws NoSuchMethodException {
        Method listMethod = MineMessageController.class.getMethod(
                "messages", String.class, String.class, Long.class, Integer.class);
        assertThat(listMethod.getParameters()[0].isAnnotationPresent(RequestParam.class)).isTrue();
        assertThat(listMethod.getParameters()[1].isAnnotationPresent(RequestParam.class)).isTrue();
        assertThat(listMethod.getParameters()[2].isAnnotationPresent(RequestParam.class)).isTrue();
        assertThat(listMethod.getParameters()[3].isAnnotationPresent(RequestParam.class)).isTrue();

        Method readMethod = MineMessageController.class.getMethod("markRead", MineMessageReadRequest.class);
        Method readAllMethod = MineMessageController.class.getMethod("markAllRead", MineMessageReadRequest.class);
        assertThat(readMethod.getParameters()[0].isAnnotationPresent(RequestBody.class)).isTrue();
        assertThat(readAllMethod.getParameters()[0].isAnnotationPresent(RequestBody.class)).isTrue();
    }

    /**
     * 返回控制器方法参数类型。
     *
     * @param methodName 方法名
     * @return 参数类型数组
     */
    private Class<?>[] methodParameterTypes(String methodName) {
        if ("messages".equals(methodName)) {
            return new Class<?>[] {String.class, String.class, Long.class, Integer.class};
        }
        if ("markRead".equals(methodName) || "markAllRead".equals(methodName)) {
            return new Class<?>[] {MineMessageReadRequest.class};
        }
        return new Class<?>[0];
    }
}

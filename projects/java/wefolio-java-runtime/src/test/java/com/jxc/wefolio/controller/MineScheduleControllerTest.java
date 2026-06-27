package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.MineScheduleResponse;
import com.jxc.wefolio.dto.ScheduleItemSaveRequest;
import com.jxc.wefolio.dto.ScheduleSlotDefinitionRequest;
import com.jxc.wefolio.dto.ScheduleSlotDefinitionStatusRequest;
import com.jxc.wefolio.service.MineScheduleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 我的档期控制器测试 — 固定维护者档期接口路径、访问控制注解和服务委托。
 */
@ExtendWith(MockitoExtension.class)
class MineScheduleControllerTest {

    /** 我的档期服务模拟 */
    @Mock
    private MineScheduleService mineScheduleService;

    @Test
    void scheduleEndpointsUseMaintainerAccessAndDelegateToService() throws NoSuchMethodException {
        MineScheduleController controller = new MineScheduleController(mineScheduleService);
        MineScheduleResponse overview = new MineScheduleResponse();
        MineScheduleResponse.SlotDefinitionItem definition = new MineScheduleResponse.SlotDefinitionItem();
        MineScheduleResponse.ScheduleItem scheduleItem = new MineScheduleResponse.ScheduleItem();
        ScheduleSlotDefinitionRequest definitionRequest = new ScheduleSlotDefinitionRequest();
        ScheduleSlotDefinitionStatusRequest statusRequest = new ScheduleSlotDefinitionStatusRequest();
        ScheduleItemSaveRequest saveRequest = new ScheduleItemSaveRequest();
        when(mineScheduleService.getScheduleOverview("2026-06", "2026-06-24")).thenReturn(overview);
        when(mineScheduleService.createSlotDefinition(definitionRequest)).thenReturn(definition);
        when(mineScheduleService.updateSlotDefinition(1L, definitionRequest)).thenReturn(definition);
        when(mineScheduleService.updateSlotDefinitionStatus(1L, statusRequest)).thenReturn(definition);
        when(mineScheduleService.saveScheduleItem(saveRequest)).thenReturn(scheduleItem);

        Response<MineScheduleResponse> overviewResponse = controller.schedule("2026-06", "2026-06-24");
        Response<MineScheduleResponse.SlotDefinitionItem> created = controller.createSlotDefinition(definitionRequest);
        Response<MineScheduleResponse.SlotDefinitionItem> updated =
                controller.updateSlotDefinition(1L, definitionRequest);
        Response<MineScheduleResponse.SlotDefinitionItem> statusUpdated =
                controller.updateSlotDefinitionStatus(1L, statusRequest);
        Response<MineScheduleResponse.ScheduleItem> saved = controller.saveScheduleItem(saveRequest);
        Response<Void> deleted = controller.deleteScheduleItem(9L);

        assertThat(MineScheduleController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertGetMapping("schedule", new Class<?>[] {String.class, String.class}, "/api/mine/schedule");
        assertPostMapping("createSlotDefinition",
                new Class<?>[] {ScheduleSlotDefinitionRequest.class},
                "/api/mine/schedule/slot-definitions");
        assertPutMapping("updateSlotDefinition",
                new Class<?>[] {Long.class, ScheduleSlotDefinitionRequest.class},
                "/api/mine/schedule/slot-definitions/{id}");
        assertPutMapping("updateSlotDefinitionStatus",
                new Class<?>[] {Long.class, ScheduleSlotDefinitionStatusRequest.class},
                "/api/mine/schedule/slot-definitions/{id}/status");
        assertPostMapping("saveScheduleItem",
                new Class<?>[] {ScheduleItemSaveRequest.class},
                "/api/mine/schedule/items/save");
        assertPostMapping("deleteScheduleItem", new Class<?>[] {Long.class}, "/api/mine/schedule/items/{id}/delete");
        assertThat(MineScheduleController.class.getMethod("deleteScheduleItem", Long.class)
                .isAnnotationPresent(DeleteMapping.class)).isFalse();
        assertThat(MineScheduleController.class.getMethod("schedule", String.class, String.class)
                .getParameters()[0].isAnnotationPresent(RequestParam.class)).isTrue();
        assertThat(MineScheduleController.class.getMethod("deleteScheduleItem", Long.class)
                .getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
        assertThat(overviewResponse.getData()).isSameAs(overview);
        assertThat(created.getData()).isSameAs(definition);
        assertThat(updated.getData()).isSameAs(definition);
        assertThat(statusUpdated.getData()).isSameAs(definition);
        assertThat(saved.getData()).isSameAs(scheduleItem);
        assertThat(deleted.isSuccess()).isTrue();
        verify(mineScheduleService).getScheduleOverview("2026-06", "2026-06-24");
        verify(mineScheduleService).createSlotDefinition(definitionRequest);
        verify(mineScheduleService).updateSlotDefinition(1L, definitionRequest);
        verify(mineScheduleService).updateSlotDefinitionStatus(1L, statusRequest);
        verify(mineScheduleService).saveScheduleItem(saveRequest);
        verify(mineScheduleService).deleteScheduleItem(9L);
    }

    /**
     * 断言 GET 映射路径。
     *
     * @param methodName 方法名
     * @param parameterTypes 参数类型
     * @param path 路径
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    private void assertGetMapping(String methodName, Class<?>[] parameterTypes, String path)
            throws NoSuchMethodException {
        GetMapping mapping = MineScheduleController.class.getMethod(methodName, parameterTypes)
                .getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    /**
     * 断言 POST 映射路径。
     *
     * @param methodName 方法名
     * @param parameterTypes 参数类型
     * @param path 路径
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    private void assertPostMapping(String methodName, Class<?>[] parameterTypes, String path)
            throws NoSuchMethodException {
        PostMapping mapping = MineScheduleController.class.getMethod(methodName, parameterTypes)
                .getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    /**
     * 断言 PUT 映射路径。
     *
     * @param methodName 方法名
     * @param parameterTypes 参数类型
     * @param path 路径
     * @throws NoSuchMethodException 方法不存在时抛出
     */
    private void assertPutMapping(String methodName, Class<?>[] parameterTypes, String path)
            throws NoSuchMethodException {
        PutMapping mapping = MineScheduleController.class.getMethod(methodName, parameterTypes)
                .getAnnotation(PutMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    @Test
    void controllerDoesNotDeclareDeleteMapping() {
        boolean hasDeleteMapping = Arrays.stream(MineScheduleController.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(DeleteMapping.class));

        assertThat(hasDeleteMapping).isFalse();
    }
}

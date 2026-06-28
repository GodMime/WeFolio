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
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.List;

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
        MineScheduleResponse.SlotDefinitionItem definition = new MineScheduleResponse.SlotDefinitionItem();
        MineScheduleResponse.MonthOverview month = new MineScheduleResponse.MonthOverview();
        MineScheduleResponse.SelectedDateOverview selectedDate = new MineScheduleResponse.SelectedDateOverview();
        MineScheduleResponse.ScheduleItem scheduleItem = new MineScheduleResponse.ScheduleItem();
        ScheduleSlotDefinitionRequest definitionRequest = new ScheduleSlotDefinitionRequest();
        ScheduleSlotDefinitionStatusRequest statusRequest = new ScheduleSlotDefinitionStatusRequest();
        ScheduleItemSaveRequest saveRequest = new ScheduleItemSaveRequest();
        when(mineScheduleService.getSlotDefinitions()).thenReturn(List.of(definition));
        when(mineScheduleService.getMonthOverview("2026-06")).thenReturn(month);
        when(mineScheduleService.getSelectedDateOverview("2026-06-24")).thenReturn(selectedDate);
        when(mineScheduleService.createSlotDefinition(definitionRequest)).thenReturn(definition);
        when(mineScheduleService.updateSlotDefinition(1L, definitionRequest)).thenReturn(definition);
        when(mineScheduleService.updateSlotDefinitionStatus(1L, statusRequest)).thenReturn(definition);
        when(mineScheduleService.saveScheduleItem(saveRequest)).thenReturn(scheduleItem);

        Response<List<MineScheduleResponse.SlotDefinitionItem>> slotDefinitions = controller.slotDefinitions();
        Response<MineScheduleResponse.MonthOverview> monthResponse = controller.monthOverview("2026-06");
        Response<MineScheduleResponse.SelectedDateOverview> dayResponse = controller.dayOverview("2026-06-24");
        Response<MineScheduleResponse.SlotDefinitionItem> created = controller.createSlotDefinition(definitionRequest);
        Response<MineScheduleResponse.SlotDefinitionItem> updated =
                controller.updateSlotDefinition(1L, definitionRequest);
        Response<MineScheduleResponse.SlotDefinitionItem> statusUpdated =
                controller.updateSlotDefinitionStatus(1L, statusRequest);
        Response<Void> slotDefinitionDeleted = controller.deleteSlotDefinition(1L);
        Response<MineScheduleResponse.ScheduleItem> saved = controller.saveScheduleItem(saveRequest);
        Response<Void> deleted = controller.deleteScheduleItem(9L);

        assertThat(MineScheduleController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertGetMapping("slotDefinitions", new Class<?>[] {}, "/api/mine/schedule/slot-definitions");
        assertGetMapping("monthOverview", new Class<?>[] {String.class}, "/api/mine/schedule/month");
        assertGetMapping("dayOverview", new Class<?>[] {String.class}, "/api/mine/schedule/day");
        assertThat(hasGetMappingPath("/api/mine/schedule")).isFalse();
        assertPostMapping("createSlotDefinition",
                new Class<?>[] {ScheduleSlotDefinitionRequest.class},
                "/api/mine/schedule/slot-definitions");
        assertPostMapping("updateSlotDefinition",
                new Class<?>[] {Long.class, ScheduleSlotDefinitionRequest.class},
                "/api/mine/schedule/slot-definitions/save/{id}");
        assertPostMapping("updateSlotDefinitionStatus",
                new Class<?>[] {Long.class, ScheduleSlotDefinitionStatusRequest.class},
                "/api/mine/schedule/slot-definitions/status/{id}");
        assertPostMapping("deleteSlotDefinition",
                new Class<?>[] {Long.class},
                "/api/mine/schedule/slot-definitions/delete/{id}");
        assertPostMapping("saveScheduleItem",
                new Class<?>[] {ScheduleItemSaveRequest.class},
                "/api/mine/schedule/items/save");
        assertPostMapping("deleteScheduleItem", new Class<?>[] {Long.class}, "/api/mine/schedule/items/delete/{id}");
        assertThat(MineScheduleController.class.getMethod("deleteScheduleItem", Long.class)
                .isAnnotationPresent(DeleteMapping.class)).isFalse();
        assertThat(MineScheduleController.class.getMethod("monthOverview", String.class)
                .getParameters()[0].isAnnotationPresent(RequestParam.class)).isTrue();
        assertThat(MineScheduleController.class.getMethod("dayOverview", String.class)
                .getParameters()[0].isAnnotationPresent(RequestParam.class)).isTrue();
        assertThat(MineScheduleController.class.getMethod("deleteScheduleItem", Long.class)
                .getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
        assertThat(slotDefinitions.getData()).containsExactly(definition);
        assertThat(monthResponse.getData()).isSameAs(month);
        assertThat(dayResponse.getData()).isSameAs(selectedDate);
        assertThat(created.getData()).isSameAs(definition);
        assertThat(updated.getData()).isSameAs(definition);
        assertThat(statusUpdated.getData()).isSameAs(definition);
        assertThat(slotDefinitionDeleted.isSuccess()).isTrue();
        assertThat(saved.getData()).isSameAs(scheduleItem);
        assertThat(deleted.isSuccess()).isTrue();
        verify(mineScheduleService).getSlotDefinitions();
        verify(mineScheduleService).getMonthOverview("2026-06");
        verify(mineScheduleService).getSelectedDateOverview("2026-06-24");
        verify(mineScheduleService).createSlotDefinition(definitionRequest);
        verify(mineScheduleService).updateSlotDefinition(1L, definitionRequest);
        verify(mineScheduleService).updateSlotDefinitionStatus(1L, statusRequest);
        verify(mineScheduleService).deleteSlotDefinition(1L);
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

    @Test
    void controllerDoesNotDeclareDeleteMapping() {
        boolean hasDeleteMapping = Arrays.stream(MineScheduleController.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(DeleteMapping.class));

        assertThat(hasDeleteMapping).isFalse();
    }

    /**
     * 判断控制器是否声明指定 GET 路径。
     *
     * @param path 接口路径
     * @return 是否存在
     */
    private boolean hasGetMappingPath(String path) {
        return Arrays.stream(MineScheduleController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(GetMapping.class))
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .anyMatch(path::equals);
    }
}

package com.jxc.wefolio.service.point;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地用户积分互斥锁契约测试 — 验证串行化、异常释放和安全回收。
 */
class LocalUserPointMutexContractTest {

    /** 本地锁实现源码路径。 */
    private static final Path IMPLEMENTATION = Path.of(
            "src/main/java/com/jxc/wefolio/service/point/LocalUserPointMutex.java");

    @Test
    void sameUserActionsShouldNeverOverlapAndLockShouldBeReclaimed() throws Exception {
        assertThat(IMPLEMENTATION).exists();
        Object mutex = newMutex();
        Method execute = executeMethod(mutex);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch allowFirstExit = new CountDownLatch(1);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        try {
            Future<?> first = executor.submit(() -> invoke(execute, mutex, 7L, () -> {
                int current = inside.incrementAndGet();
                maxInside.accumulateAndGet(current, Math::max);
                firstEntered.countDown();
                await(allowFirstExit);
                inside.decrementAndGet();
                return null;
            }));
            firstEntered.await();
            Future<?> second = executor.submit(() -> invoke(execute, mutex, 7L, () -> {
                int current = inside.incrementAndGet();
                maxInside.accumulateAndGet(current, Math::max);
                inside.decrementAndGet();
                return null;
            }));
            allowFirstExit.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(maxInside).hasValue(1);
        assertThat(activeMutexCount(mutex)).isZero();
    }

    @Test
    void exceptionShouldReleaseLockAndAllowNextAction() throws Exception {
        assertThat(IMPLEMENTATION).exists();
        Object mutex = newMutex();
        Method execute = executeMethod(mutex);

        assertThatThrownBy(() -> invoke(execute, mutex, 9L, () -> {
            throw new IllegalStateException("模拟失败");
        })).isInstanceOf(IllegalStateException.class).hasMessage("模拟失败");

        Object result = invoke(execute, mutex, 9L, () -> "恢复成功");
        assertThat(result).isEqualTo("恢复成功");
        assertThat(activeMutexCount(mutex)).isZero();
    }

    @Test
    void transactionShouldKeepUserMutexUntilCompletionCallback() throws Exception {
        Object mutex = newMutex();
        Method execute = executeMethod(mutex);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch secondEntered = new CountDownLatch(1);
        java.util.List<TransactionSynchronization> synchronizations = java.util.List.of();
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(invoke(execute, mutex, 7L, () -> "first")).isEqualTo("first");
            synchronizations = TransactionSynchronizationManager.getSynchronizations();
            Future<?> second = executor.submit(() -> invoke(execute, mutex, 7L, () -> {
                secondEntered.countDown();
                return null;
            }));

            assertThat(secondEntered.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();
            synchronizations.forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            TransactionSynchronizationManager.clearSynchronization();
            assertThat(secondEntered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            second.get(1, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                synchronizations.forEach(synchronization ->
                        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
                TransactionSynchronizationManager.clearSynchronization();
            }
            executor.shutdownNow();
        }

        assertThat(activeMutexCount(mutex)).isZero();
    }

    /** 创建本地锁实例。 */
    private Object newMutex() throws ReflectiveOperationException {
        return Class.forName("com.jxc.wefolio.service.point.LocalUserPointMutex")
                .getDeclaredConstructor()
                .newInstance();
    }

    /** 获取统一执行方法。 */
    private Method executeMethod(Object mutex) throws NoSuchMethodException {
        return mutex.getClass().getMethod("execute", Long.class, Supplier.class);
    }

    /** 反射调用并还原业务异常。 */
    private Object invoke(Method execute, Object mutex, Long userId, Supplier<?> action) {
        try {
            return execute.invoke(mutex, userId, action);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 查询当前持有或等待的锁对象数量。 */
    private int activeMutexCount(Object mutex) throws ReflectiveOperationException {
        Method method = mutex.getClass().getDeclaredMethod("activeMutexCount");
        method.setAccessible(true);
        return (Integer) method.invoke(mutex);
    }

    /** 等待并在中断时恢复线程中断标记。 */
    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待本地锁测试信号时被中断", exception);
        }
    }
}

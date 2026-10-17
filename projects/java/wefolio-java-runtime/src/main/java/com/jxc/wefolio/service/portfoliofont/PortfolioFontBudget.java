package com.jxc.wefolio.service.portfoliofont;

import com.jxc.wefolio.message.PortfolioFontMessage;

import org.apache.http.client.methods.HttpRequestBase;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** 单次同步处理的截止时间与可取消资源；迟到结果不再接入。 */
@Slf4j
final class PortfolioFontBudget implements AutoCloseable {
    /** 单调时钟截止时间。 */
    private final long deadline;
    /** 可替换单调时钟用于确定性预算测试。 */
    private final LongSupplier clock;
    /** 为异常后环境取证预留总预算的十分之一，最多二百五十毫秒。 */
    private final long evidenceReserveMs;
    /** 当前原生进程及字体专用 COS 连接。 */
    private Process process;
    /** POSIX 独立进程组 ID；即使 Python 父进程退出，也能终止孤立的 HarfBuzz 写入者。 */
    private long processGroup;
    /** 系统信号工具，PID 为本次直接启动进程的数值，不接收请求文本。 */
    private static final String KILL_COMMAND = "/bin/kill";
    /** 当前上传或删除使用的专用客户端。 */
    private HttpRequestBase client;
    /** 取消标记。 */
    private boolean closed;

    /** 从请求开始计入全部等待时间。 */
    PortfolioFontBudget(long millis) { this(millis, System::nanoTime); }
    /** 测试时可推进时钟，无需依赖线程休眠误差。 */
    PortfolioFontBudget(long millis, LongSupplier clock) {
        this.clock = clock;
        deadline = clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(millis);
        evidenceReserveMs = Math.max(1, Math.min(250, millis / 10));
    }
    /** 外部进程等待提前结束，剩余时间用于取证而不延长总预算。 */
    synchronized long processRemaining() throws TimeoutException {
        long available = remaining() - evidenceReserveMs;
        if (available <= 0) { throw new TimeoutException(PortfolioFontMessage.BUDGET_EXPIRED); }
        return available;
    }
    /** 终止本次进程但保留取证预算，不允许重新生成。 */
    synchronized void terminateProcess() {
        // 先终止父进程，防止组信号早于 setsid 到达后又产生未捕获的子进程。
        if (process != null) { kill(process); }
        if (processGroup > 1) { signalAsync(processGroup); }
    }
    /** 对独立进程组终止并确认全部写入者退出；无法确认则不恢复任何完成组。 */
    synchronized void stopWriters() throws IOException, InterruptedException, TimeoutException {
        if (processGroup > 1) {
            signal("-KILL");
            while (signal("-0") == 0) {
                remaining(); Thread.sleep(1);
            }
        }
        if (process != null && process.isAlive()) {
            kill(process);
            if (!process.waitFor(remaining(), TimeUnit.MILLISECONDS)) { throw new TimeoutException(PortfolioFontMessage.ENVIRONMENT_EVIDENCE_INCOMPLETE); }
        }
        process = null; processGroup = 0;
    }
    /** 每次信号确认使用原剩余预算，不能在进程退出检查上无限等待。 */
    private int signal(String signal) throws IOException, InterruptedException, TimeoutException {
        Process command = new ProcessBuilder(KILL_COMMAND, signal, "--", "-" + processGroup)
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!command.waitFor(remaining(), TimeUnit.MILLISECONDS)) { throw new TimeoutException(PortfolioFontMessage.ENVIRONMENT_EVIDENCE_INCOMPLETE); }
            return command.exitValue();
        } finally { if (command.isAlive()) { command.destroyForcibly(); } }
    }
    /** 取消线程不再等待；独立的系统信号命令只负责终止已登记组，不生成资源。 */
    private static void signalAsync(long group) {
        try {
            new ProcessBuilder(KILL_COMMAND, "-KILL", "--", "-" + group)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException exception) { log.warn("字体进程组终止信号未发出"); }
    }
    /** 返回剩余毫秒，到期或取消时失败。 */
    synchronized long remaining() throws TimeoutException {
        long millis = TimeUnit.NANOSECONDS.toMillis(deadline - clock.getAsLong());
        if (closed || Thread.currentThread().isInterrupted() || millis <= 0) { throw new TimeoutException(PortfolioFontMessage.BUDGET_EXPIRED); }
        return millis;
    }
    /** 注册进程时避免取消与创建竞争留下无人管理的子进程。 */
    synchronized void attach(Process next) throws TimeoutException {
        try { remaining(); process = next; }
        catch (TimeoutException exception) { kill(next); throw exception; }
    }
    /** 仅 Runner 经 setsid 启动的进程使用进程组，普通进程保留原取消行为。 */
    synchronized void attachGroup(Process next) throws TimeoutException {
        long group = next.pid();
        try { attach(next); processGroup = group; }
        catch (TimeoutException exception) { if (group > 1) { signalAsync(group); } throw exception; }
    }
    /** 注册客户端时避免取消后仍发出新的网络请求。 */
    synchronized void attach(HttpRequestBase next) throws TimeoutException {
        try { remaining(); client = next; }
        catch (TimeoutException exception) { next.abort(); throw exception; }
    }
    /** 终止子进程树并关闭字体专用网络连接。 */
    @Override public synchronized void close() {
        closed = true;
        if (process != null) { kill(process); process = null; }
        if (processGroup > 1) { signalAsync(processGroup); processGroup = 0; }
        if (client != null) { client.abort(); client = null; }
    }
    /** 子进程后代先于父进程终止。 */
    private static void kill(Process target) {
        if (!target.isAlive()) { return; }
        try { target.descendants().forEach(ProcessHandle::destroyForcibly); }
        catch (RuntimeException exception) { log.warn("字体子进程树清理受限，继续终止父进程"); }
        finally { target.destroyForcibly(); }
    }
}

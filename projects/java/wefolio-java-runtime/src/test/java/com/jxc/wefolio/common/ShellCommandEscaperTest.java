package com.jxc.wefolio.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Shell 命令参数转义工具测试。 */
class ShellCommandEscaperTest {

    /** 单引号转义结果必须能被 POSIX shell 解析回完整原值。 */
    @Test
    void singleQuotedValueRoundTripsThroughPosixShell() throws IOException, InterruptedException {
        String original = "X-Admin-Point-Secret: admin'point-secret";
        String command = "set -- " + ShellCommandEscaper.singleQuote(original)
                + "; printf '%s' \"$1\"";

        Process process = new ProcessBuilder("/bin/sh", "-c", command).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as(error).isZero();
        assertThat(output).isEqualTo(original);
    }
}

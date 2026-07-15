package com.jxc.wefolio.job;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品存储结算代码边界测试。
 */
class WorkStorageBillingStructureTest {

    @Test
    void jobImplementationShouldNotUseRuntimeClassesOrObsoleteRuleColumn() throws Exception {
        List<Path> sourceFiles;
        try (var files = Files.walk(Path.of("src/main/java/com/jxc/wefolio/job"))) {
            sourceFiles = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.contains("WorkStorageBilling")
                                || name.equals("AdminPointProperties.java")
                                || name.equals("AdminPointSecretValidator.java")
                                || name.equals("MonthlyWorkStorageBillingJob.java");
                    })
                    .toList();
        }

        assertThat(sourceFiles).isNotEmpty();
        assertThat(sourceFiles)
                .extracting(path -> path.getFileName().toString())
                .contains(
                        "WorkStorageBillingUnavailableException.java",
                        "WorkStorageBillingModels.java"
                );
        for (Path sourceFile : sourceFiles) {
            String source = Files.readString(sourceFile);
            assertThat(source)
                    .as(sourceFile.toString())
                    .doesNotContain("import com.jxc.wefolio.entity")
                    .doesNotContain("import com.jxc.wefolio.mapper")
                    .doesNotContain("import com.jxc.wefolio.dict")
                    .doesNotContain("import com.jxc.wefolio.service")
                    .doesNotContain("lock_version")
                    .doesNotContain("String.intern()")
                    .doesNotContain("MiB")
                    .doesNotContain("TODO");
        }
    }
}

package com.jxc.wefolio.service.teamportfolio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队作品集业务边界测试。
 */
class TeamPortfolioDependencyBoundaryTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java/com/jxc/wefolio");
    private static final Path TEAM_PORTFOLIO = MAIN_JAVA.resolve("service/teamportfolio");
    private static final Path COMPONENT_ROOT = TEAM_PORTFOLIO.resolve("component");
    private static final String WEFOLIO_PACKAGE_PREFIX = "com.jxc.wefolio";
    private static final String COMPONENT_PACKAGE_PREFIX =
            "com.jxc.wefolio.service.teamportfolio.component";
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?"
                    + "([A-Za-z_$][\\w$]*(?:\\.(?:[A-Za-z_$][\\w$]*|\\*))+?)\\s*;");
    private static final Pattern FQCN_PATTERN = Pattern.compile(
            "(?<![\\w$])com\\.jxc\\.wefolio(?:\\.(?:[A-Za-z_$][\\w$]*|\\*))+");
    private static final List<String> TOP_LEVEL_ALLOWED_DEPENDENCIES = List.of(
            "com.jxc.wefolio.constant.TeamPortfolioConstants",
            "com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict",
            "com.jxc.wefolio.dict.PortfolioConfigScopeDict",
            "com.jxc.wefolio.dto.teamportfolio",
            "com.jxc.wefolio.entity.PortfolioReferenceEntity",
            "com.jxc.wefolio.exception.BusinessException",
            "com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper",
            "com.jxc.wefolio.message.TeamPortfolioMessage",
            "com.jxc.wefolio.service.teamportfolio");

    /**
     * 顶层团队分发服务只能依赖团队常量、团队 DTO、团队组件和必要基础设施。
     */
    @Test
    void teamTopLevelServicesShouldUseOnlyAllowedWefolioPackages() throws IOException {
        List<Path> services = List.of(
                TEAM_PORTFOLIO.resolve("TeamPortfolioConfigValidator.java"),
                TEAM_PORTFOLIO.resolve("TeamPortfolioRenderService.java"),
                TEAM_PORTFOLIO.resolve("TeamPortfolioReferenceService.java"));

        for (Path service : services) {
            assertThat(forbiddenTopLevelDependencies(Files.readString(service)))
                    .as("顶层服务依赖越界: %s", service)
                    .isEmpty();
        }
    }

    /**
     * 每个团队组件包不能通过 import 或代码体完整类名引用其他组件实现。
     */
    @Test
    void teamComponentPackagesShouldNotReferenceOtherComponentPackages() throws IOException {
        try (Stream<Path> paths = Files.walk(COMPONENT_ROOT)) {
            for (Path sourceFile : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(sourceFile);
                assertThat(crossComponentDependencies(source))
                        .as("团队组件包交叉依赖: %s", sourceFile)
                        .isEmpty();
            }
        }
    }

    /**
     * 除规范化、渲染兼容映射和统一遍历器外，团队业务代码不得只读取顶层组件。
     */
    @Test
    void teamServicesShouldTraverseAllMenusInsteadOfReadingTopLevelComponentsDirectly() throws IOException {
        Set<String> allowedFileNames = Set.of(
                "TeamPortfolioComponentTraversal.java",
                "TeamPortfolioConfigValidator.java",
                "TeamPortfolioConfigMerger.java",
                "TeamPortfolioRenderService.java");

        try (Stream<Path> paths = Files.walk(TEAM_PORTFOLIO)) {
            for (Path sourceFile : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (allowedFileNames.contains(sourceFile.getFileName().toString())) {
                    continue;
                }
                String source = Files.readString(sourceFile);
                assertThat(source)
                        .as("团队服务禁止直接读取顶层组件: %s", sourceFile)
                        .doesNotContain(".getComponents()")
                        .doesNotContain("getJSONArray(\"components\")");
            }
        }
    }

    /**
     * 扫描器必须识别普通、静态、通配 import 和代码体完整类名绕过。
     */
    @Test
    void topLevelScannerShouldDetectImportAndFullyQualifiedNameBypasses() {
        String source = """
                package example;
                import com.jxc.wefolio.service.PortfolioRenderService;
                import static com.jxc.wefolio.service.PortfolioConfigValidator.normalize;
                import com.jxc.wefolio.dto.*;
                class Example {
                    Class<?> type = com.jxc.wefolio.dto.PortfolioConfigDto.class;
                }
                """;

        Set<String> violations = forbiddenTopLevelDependencies(source);

        assertThat(violations).anyMatch(value -> value.startsWith(
                "com.jxc.wefolio.service.PortfolioRenderService"));
        assertThat(violations).anyMatch(value -> value.startsWith(
                "com.jxc.wefolio.service.PortfolioConfigValidator"));
        assertThat(violations).contains("com.jxc.wefolio.dto.*", "com.jxc.wefolio.dto.PortfolioConfigDto");
    }

    /**
     * 组件扫描器必须识别跨包的普通、静态、通配 import 和代码体完整类名。
     */
    @Test
    void componentScannerShouldDetectCrossPackageBypasses() {
        String source = """
                package com.jxc.wefolio.service.teamportfolio.component.carousel;
                import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentRenderer;
                import static com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentConfig.DEFAULT;
                import com.jxc.wefolio.service.teamportfolio.component.schedulequery.*;
                class Example {
                    Class<?> type = com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentRenderer.class;
                }
                """;

        Set<String> violations = crossComponentDependencies(source);

        assertThat(violations).anyMatch(value -> value.contains("component.divider"));
        assertThat(violations).anyMatch(value -> value.contains("component.textsection"));
        assertThat(violations).anyMatch(value -> value.contains("component.schedulequery"));
        assertThat(violations).anyMatch(value -> value.contains("component.qrcontact"));
    }

    /**
     * 允许的团队依赖、自包引用以及注释和字符串中的示例不能被误报。
     */
    @Test
    void scannersShouldAllowApprovedDependenciesWithoutFalsePositives() {
        String topLevelSource = """
                package com.jxc.wefolio.service.teamportfolio;
                import com.jxc.wefolio.dto.teamportfolio.*;
                import static com.jxc.wefolio.dict.PortfolioConfigScopeDict.DRAFT;
                import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentRenderer;
                class Example {
                    Class<?> type = com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto.class;
                    String ignored = "com.jxc.wefolio.service.PortfolioRenderService";
                    // com.jxc.wefolio.dto.PortfolioConfigDto
                }
                """;
        String componentSource = """
                package com.jxc.wefolio.service.teamportfolio.component.carousel;
                import com.jxc.wefolio.service.teamportfolio.component.carousel.*;
                import static com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentConfig.VALUE;
                import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentContext;
                class Example {
                    Class<?> type = com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentRenderer.class;
                    /* com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentRenderer */
                }
                """;

        assertThat(forbiddenTopLevelDependencies(topLevelSource)).isEmpty();
        assertThat(crossComponentDependencies(componentSource)).isEmpty();
    }

    private static Set<String> forbiddenTopLevelDependencies(String source) {
        Set<String> violations = new LinkedHashSet<>();
        for (String dependency : collectDependencies(source)) {
            if (dependency.startsWith(WEFOLIO_PACKAGE_PREFIX)
                    && TOP_LEVEL_ALLOWED_DEPENDENCIES.stream().noneMatch(
                    allowed -> dependency.equals(allowed) || dependency.startsWith(allowed + "."))) {
                violations.add(dependency);
            }
        }
        return violations;
    }

    private static Set<String> crossComponentDependencies(String source) {
        String currentPackage = packageName(source);
        if (!currentPackage.startsWith(COMPONENT_PACKAGE_PREFIX + ".")) {
            throw new IllegalArgumentException("不是团队组件包源码");
        }
        String currentComponent = firstPackageSegment(
                currentPackage.substring(COMPONENT_PACKAGE_PREFIX.length() + 1));
        Set<String> violations = new LinkedHashSet<>();
        for (String dependency : collectDependencies(source)) {
            if (!dependency.startsWith(COMPONENT_PACKAGE_PREFIX + ".")) {
                continue;
            }
            String targetComponent = firstPackageSegment(
                    dependency.substring(COMPONENT_PACKAGE_PREFIX.length() + 1));
            if (!currentComponent.equals(targetComponent)) {
                violations.add(dependency);
            }
        }
        return violations;
    }

    private static Set<String> collectDependencies(String source) {
        String code = stripCommentsAndLiterals(source);
        Set<String> dependencies = new LinkedHashSet<>();
        Matcher importMatcher = IMPORT_PATTERN.matcher(code);
        while (importMatcher.find()) {
            dependencies.add(normalizeDependency(importMatcher.group(1)));
        }
        Matcher fqcnMatcher = FQCN_PATTERN.matcher(code);
        while (fqcnMatcher.find()) {
            dependencies.add(normalizeDependency(fqcnMatcher.group()));
        }
        return dependencies;
    }

    private static String normalizeDependency(String dependency) {
        String classLiteralSuffix = ".class";
        return dependency.endsWith(classLiteralSuffix)
                ? dependency.substring(0, dependency.length() - classLiteralSuffix.length())
                : dependency;
    }

    private static String packageName(String source) {
        Matcher matcher = PACKAGE_PATTERN.matcher(stripCommentsAndLiterals(source));
        if (!matcher.find()) {
            throw new IllegalArgumentException("源码缺少 package 声明");
        }
        return matcher.group(1);
    }

    private static String firstPackageSegment(String packageSuffix) {
        int separator = packageSuffix.indexOf('.');
        return separator < 0 ? packageSuffix : packageSuffix.substring(0, separator);
    }

    /**
     * 去除注释、字符串、字符和文本块，避免示例文本被当作代码依赖。
     */
    private static String stripCommentsAndLiterals(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                index = replaceLineComment(source, result, index);
            } else if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                index = replaceBlockComment(source, result, index);
            } else if (current == '"' && index + 2 < source.length()
                    && source.charAt(index + 1) == '"' && source.charAt(index + 2) == '"') {
                index = replaceQuoted(source, result, index, '"', true);
            } else if (current == '"' || current == '\'') {
                index = replaceQuoted(source, result, index, current, false);
            } else {
                result.append(current);
                index++;
            }
        }
        return result.toString();
    }

    private static int replaceLineComment(String source, StringBuilder result, int start) {
        int index = start;
        while (index < source.length() && source.charAt(index) != '\n') {
            result.append(' ');
            index++;
        }
        return index;
    }

    private static int replaceBlockComment(String source, StringBuilder result, int start) {
        int index = start;
        while (index < source.length()) {
            if (index + 1 < source.length() && source.charAt(index) == '*' && source.charAt(index + 1) == '/') {
                result.append("  ");
                return index + 2;
            }
            result.append(source.charAt(index) == '\n' ? '\n' : ' ');
            index++;
        }
        return index;
    }

    private static int replaceQuoted(
            String source,
            StringBuilder result,
            int start,
            char quote,
            boolean textBlock
    ) {
        int openingLength = textBlock ? 3 : 1;
        result.append(" ".repeat(openingLength));
        int index = start + openingLength;
        while (index < source.length()) {
            if (textBlock && index + 2 < source.length()
                    && source.charAt(index) == quote
                    && source.charAt(index + 1) == quote
                    && source.charAt(index + 2) == quote) {
                result.append("   ");
                return index + 3;
            }
            char current = source.charAt(index);
            if (!textBlock && current == '\\' && index + 1 < source.length()) {
                result.append("  ");
                index += 2;
            } else if (!textBlock && current == quote) {
                result.append(' ');
                return index + 1;
            } else {
                result.append(current == '\n' ? '\n' : ' ');
                index++;
            }
        }
        return index;
    }
}

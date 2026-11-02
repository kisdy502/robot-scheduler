package com.zioneer.robotscheduler.boot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.ColorConverter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 控制台 pattern 确定性验证：消息体着色（%clr(%msg)）不得破坏行尾换行。
 */
class ConsolePatternTest {

    private static final String CONSOLE_PATTERN = "%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} "
            + "%clr([%thread]){faint} %clr(%-5level) "
            + "%clr([traceId=%X{traceId:-},spanId=%X{spanId:-},robot_code=%X{robot_code:-}]){faint} "
            + "%clr(%logger{36}){cyan} - %clr(%msg)%n";

    /** 换行放进着色复合内部（消息+换行整体着色）。 */
    private static final String PATTERN_NEWLINE_INSIDE = CONSOLE_PATTERN.replace("%clr(%msg)%n",
            "%clr(%msg%n)");

    /** 复合转换器与 %n 之间用空格分隔（行尾多一个不可见空格）。 */
    private static final String PATTERN_SPACE_SEPARATED = CONSOLE_PATTERN.replace("%clr(%msg)%n",
            "%clr(%msg) %n");

    @Test
    void coloredMessage_keepsTrailingNewline() {
        assertThat(render(CONSOLE_PATTERN))
                .as(")%n 紧邻：已知 logback 解析坑，%n 会被当字面量")
                .endsWith("%n");
        assertThat(render(PATTERN_NEWLINE_INSIDE))
                .as("换行放 %clr 内部")
                .contains("充电桩占用者已离线")
                .endsWith(System.lineSeparator());
        assertThat(render(PATTERN_SPACE_SEPARATED))
                .as("空格分隔 %n")
                .contains("充电桩占用者已离线")
                .endsWith(System.lineSeparator());
    }

    private String render(String pattern) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        PatternLayout layout = new PatternLayout();
        layout.getInstanceConverterMap().put("clr", ColorConverter.class.getName());
        layout.setPattern(pattern);
        layout.setContext(context);
        layout.start();
        LoggingEvent event = new LoggingEvent("fqcn", context.getLogger("test.logger"),
                Level.INFO, "充电桩占用者已离线，自动释放: charger=C3 robot=RB_20261027_00015", null, null);
        return layout.doLayout(event);
    }
}

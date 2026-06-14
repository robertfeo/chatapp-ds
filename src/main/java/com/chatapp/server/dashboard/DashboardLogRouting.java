package com.chatapp.server.dashboard;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;

/**
 * Re-points logging once the dashboard owns the screen: the console appender is detached (so log
 * lines cannot corrupt the Lanterna panel), the full structured log is preserved to a per-server
 * file, and a {@link DashboardLogAppender} feeds the live event panel.
 *
 * <p>Called only after the dashboard's screen started successfully, so a server that runs without a
 * dashboard (tests, CI, piped output) keeps its plain stdout logging untouched.
 */
public final class DashboardLogRouting {

  private DashboardLogRouting() {}

  /**
   * Install dashboard log routing on the root logger.
   *
   * @param events sink that the live event panel reads
   * @param serverId used to name the per-server log file ({@code logs/server-<id>.log})
   */
  public static void install(DashboardEvents events, int serverId) {
    LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    // Keep the structured logs: write them to a file so the demo/grading record survives even
    // though the console is now a live dashboard.
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(ctx);
    encoder.setPattern("%d{HH:mm:ss.SSS} %-5level [server=%X{serverId:-?}] %logger{0} | %msg%n");
    encoder.start();

    FileAppender<ILoggingEvent> file = new FileAppender<>();
    file.setContext(ctx);
    file.setName("DASHBOARD_FILE");
    file.setFile("logs/server-" + serverId + ".log");
    file.setAppend(false);
    file.setEncoder(encoder);
    file.start();

    // Feed the live event panel.
    DashboardLogAppender panel = new DashboardLogAppender(events);
    panel.setContext(ctx);
    panel.setName("DASHBOARD_PANEL");
    panel.start();

    root.addAppender(file);
    root.addAppender(panel);

    // Silence the console last, so any failure above leaves stdout logging intact.
    Appender<ILoggingEvent> stdout = root.getAppender("STDOUT");
    if (stdout != null) {
      root.detachAppender(stdout);
    }
  }
}

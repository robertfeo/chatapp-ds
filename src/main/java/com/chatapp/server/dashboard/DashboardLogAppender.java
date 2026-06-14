package com.chatapp.server.dashboard;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Logback appender that feeds the live dashboard's event panel. It is attached programmatically
 * (see {@link DashboardLogRouting}) only when the dashboard is active, so tests and piped runs that
 * read stdout are never affected.
 *
 * <p>High-frequency, low-signal events ({@code event=group_view}, which repeats on every discovery
 * re-announce) are dropped, because the dashboard header already shows the live peer set.
 */
public final class DashboardLogAppender extends AppenderBase<ILoggingEvent> {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

  private final DashboardEvents events;

  public DashboardLogAppender(DashboardEvents events) {
    this.events = events;
  }

  @Override
  protected void append(ILoggingEvent event) {
    String msg = event.getFormattedMessage();
    if (msg == null || msg.contains("event=group_view")) {
      return;
    }
    events.add(TIME.format(Instant.ofEpochMilli(event.getTimeStamp())) + "  " + msg);
  }
}

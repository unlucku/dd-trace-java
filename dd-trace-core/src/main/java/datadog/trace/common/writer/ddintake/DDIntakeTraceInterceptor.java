package datadog.trace.common.writer.ddintake;

import static datadog.trace.util.TraceUtils.isValidStatusCode;
import static datadog.trace.util.TraceUtils.normalizeOperationName;
import static datadog.trace.util.TraceUtils.normalizeServiceName;
import static datadog.trace.util.TraceUtils.normalizeSpanType;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.core.DDSpan;
import datadog.trace.util.TraceUtils;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DDIntakeTraceInterceptor implements TraceInterceptor {

  public static final DDIntakeTraceInterceptor INSTANCE = new DDIntakeTraceInterceptor();

  private static final Logger log = LoggerFactory.getLogger(DDIntakeTraceInterceptor.class);

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {
    if (trace.isEmpty()) {
      return trace;
    }

    for (MutableSpan span : trace) {
      if (span instanceof DDSpan) {
        process((DDSpan) span);
      }
    }
    return trace;
  }

  private void process(DDSpan span) {
    span.setServiceName(normalizeServiceName(span.getServiceName()));
    span.setOperationName(normalizeOperationName(span.getOperationName()));
    span.setSpanType(normalizeSpanType(span.getSpanType()));

    if (span.getResourceName() == null || span.getResourceName().length() == 0) {
      log.debug(
          "Fixing malformed trace. Resource is empty (reason:resource_empty), setting span.resource={}: {}",
          span.getOperationName(),
          span);
      span.setResourceName(span.getOperationName());
    }

    if (span.getTag("env") != null) {
      span.setTag("env", TraceUtils.normalizeEnv((String) span.getTag("env")));
    }

    final short httpStatusCode = span.getHttpStatusCode();
    if (!isValidStatusCode(httpStatusCode)) {
      log.debug(
          "Fixing malformed trace. HTTP status code is invalid (reason:invalid_http_status_code), dropping invalid http.status_code={}: {}",
          httpStatusCode,
          span);
      span.setHttpStatusCode(0);
    }
  }

  @Override
  public int priority() {
    return 0;
  }
}

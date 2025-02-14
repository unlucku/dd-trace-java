package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.sampling.PrioritySampling;
import java.util.Collections;
import java.util.Map;

/**
 * When calling extract, we allow for grabbing other configured headers as tags. Those tags are
 * returned here even if the rest of the request would have returned null.
 */
public class TagContext implements AgentSpan.Context.Extracted {

  private static final HttpHeaders EMPTY_HTTP_HEADERS = new HttpHeaders();

  private final String origin;
  private final Map<String, String> tags;
  private Object requestContextDataAppSec;
  private Object requestContextDataIast;
  private final HttpHeaders httpHeaders;
  private final Map<String, String> baggage;

  private final int samplingPriority;

  public TagContext() {
    this(null, null);
  }

  public TagContext(final String origin, final Map<String, String> tags) {
    this(origin, tags, null, null, PrioritySampling.UNSET);
  }

  public TagContext(
      final String origin,
      final Map<String, String> tags,
      HttpHeaders httpHeaders,
      final Map<String, String> baggage,
      int samplingPriority) {
    this.origin = origin;
    this.tags = tags;
    this.httpHeaders = httpHeaders == null ? EMPTY_HTTP_HEADERS : httpHeaders;
    this.baggage = baggage == null ? Collections.emptyMap() : baggage;
    this.samplingPriority = samplingPriority;
  }

  public final String getOrigin() {
    return origin;
  }

  @Override
  public String getForwarded() {
    return httpHeaders.forwarded;
  }

  @Override
  public String getXForwardedProto() {
    return httpHeaders.xForwardedProto;
  }

  @Override
  public String getXForwardedHost() {
    return httpHeaders.xForwardedHost;
  }

  @Override
  public String getXForwardedPort() {
    return httpHeaders.xForwardedPort;
  }

  @Override
  public String getForwardedFor() {
    return httpHeaders.forwardedFor;
  }

  @Override
  public String getXForwarded() {
    return httpHeaders.xForwarded;
  }

  @Override
  public String getXForwardedFor() {
    return httpHeaders.xForwardedFor;
  }

  @Override
  public String getXClusterClientIp() {
    return httpHeaders.xClusterClientIp;
  }

  @Override
  public String getXRealIp() {
    return httpHeaders.xRealIp;
  }

  @Override
  public String getClientIp() {
    return httpHeaders.clientIp;
  }

  @Override
  public String getUserAgent() {
    return httpHeaders.userAgent;
  }

  @Override
  public String getVia() {
    return httpHeaders.via;
  }

  @Override
  public String getTrueClientIp() {
    return httpHeaders.trueClientIp;
  }

  @Override
  public String getCustomIpHeader() {
    return httpHeaders.customIpHeader;
  }

  public final Map<String, String> getTags() {
    return tags;
  }

  public final int getSamplingPriority() {
    return samplingPriority;
  }

  public final Map<String, String> getBaggage() {
    return baggage;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return baggage.entrySet();
  }

  @Override
  public DDTraceId getTraceId() {
    return DDTraceId.ZERO;
  }

  @Override
  public long getSpanId() {
    return DDSpanId.ZERO;
  }

  @Override
  public final AgentTrace getTrace() {
    return AgentTracer.NoopAgentTrace.INSTANCE;
  }

  public final Object getRequestContextDataAppSec() {
    return requestContextDataAppSec;
  }

  public final TagContext withRequestContextDataAppSec(Object requestContextData) {
    this.requestContextDataAppSec = requestContextData;
    return this;
  }

  public final Object getRequestContextDataIast() {
    return requestContextDataIast;
  }

  public final TagContext withRequestContextDataIast(Object requestContextData) {
    this.requestContextDataIast = requestContextData;
    return this;
  }

  @Override
  public PathwayContext getPathwayContext() {
    return null;
  }

  public static class HttpHeaders {
    public String forwardedFor;
    public String xForwarded;
    public String forwarded;
    public String xForwardedProto;
    public String xForwardedHost;
    public String xForwardedPort;
    public String xForwardedFor;
    public String xClusterClientIp;
    public String xRealIp;
    public String clientIp;
    public String userAgent;
    public String via;
    public String trueClientIp;
    public String customIpHeader;
  }
}

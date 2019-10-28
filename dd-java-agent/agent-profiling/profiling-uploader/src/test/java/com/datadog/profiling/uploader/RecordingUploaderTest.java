/*
 * Copyright 2019 Datadog
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadog.profiling.uploader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingType;
import com.datadog.profiling.testing.ProfilingTestUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteStreams;
import datadog.trace.api.Config;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the recording uploader. */
@ExtendWith(MockitoExtension.class)
public class RecordingUploaderTest {

  private static final String URL_PATH = "/v0.1/lalalala";
  private static final String APIKEY_VALUE = "testkey";
  private static final String RECORDING_RESOURCE = "test-recording.jfr";
  private static final String RECODING_NAME_PREFIX = "test-recording-";
  private static final RecordingType RECORDING_TYPE = RecordingType.CONTINUOUS;

  private static final Map<String, String> TAGS;

  static {
    // Not using Guava's ImmutableMap because we want to test null value
    final Map<String, String> tags = new HashMap<>();
    tags.put("foo", "bar");
    tags.put("baz", "123");
    tags.put("null", null);
    tags.put("empty", "");
    TAGS = tags;
  }

  // We sort tags to have expected parameters to have expected result
  private static final Map<String, String> EXPECTED_TAGS =
      ImmutableMap.of("baz", "123", "foo", "bar");

  private static final int SEQUENCE_NUMBER = 123;
  private static final int RECORDING_START = 1000;
  private static final int RECORDING_END = 1100;

  // TODO: Add a test to verify overall request timeout rather than IO timeout
  private final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private final Duration REQUEST_IO_OPERATION_TIMEOUT = Duration.ofSeconds(5);

  @Mock private Config config;

  private final MockWebServer server = new MockWebServer();
  private HttpUrl url;

  private RecordingUploader uploader;

  @BeforeEach
  public void setup() throws IOException {
    server.start();
    url = server.url(URL_PATH);

    when(config.getProfilingUrl()).thenReturn(url.toString());
    when(config.getProfilingApiKey()).thenReturn(APIKEY_VALUE);
    when(config.getMergedProfilingTags()).thenReturn(TAGS);
    when(config.getProfilingUploadRequestTimeout()).thenReturn((int) REQUEST_TIMEOUT.getSeconds());
    when(config.getProfilingUploadRequestIOOperationTimeout())
        .thenReturn((int) REQUEST_IO_OPERATION_TIMEOUT.getSeconds());

    uploader = new RecordingUploader(config);
  }

  @AfterEach
  public void tearDown() throws IOException {
    uploader.shutdown();
    try {
      server.shutdown();
    } catch (final IOException e) {
      // Looks like this happens for some unclear reason, but should not affect tests
    }
  }

  @Test
  public void testRequestParameters() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));

    uploader.upload(RECORDING_TYPE, mockRecordingData(RECORDING_RESOURCE));

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(url, recordedRequest.getRequestUrl());

    assertEquals(Credentials.basic(APIKEY_VALUE, ""), recordedRequest.getHeader("Authorization"));

    final Multimap<String, Object> parameters =
        ProfilingTestUtils.parseProfilingRequestParameters(recordedRequest);
    assertEquals(
        ImmutableList.of(RECODING_NAME_PREFIX + SEQUENCE_NUMBER),
        parameters.get(RecordingUploader.RECORDING_NAME_PARAM));
    assertEquals(
        ImmutableList.of(RecordingUploader.RECORDING_FORMAT),
        parameters.get(RecordingUploader.FORMAT_PARAM));
    assertEquals(
        ImmutableList.of(RecordingUploader.RECORDING_TYPE_PREFIX + RECORDING_TYPE.getName()),
        parameters.get(RecordingUploader.TYPE_PARAM));
    assertEquals(
        ImmutableList.of(RecordingUploader.RECORDING_RUNTIME),
        parameters.get(RecordingUploader.RUNTIME_PARAM));

    assertEquals(
        ImmutableList.of(Instant.ofEpochSecond(RECORDING_START).toString()),
        parameters.get(RecordingUploader.RECORDING_START_PARAM));
    assertEquals(
        ImmutableList.of(Instant.ofEpochSecond(RECORDING_END).toString()),
        parameters.get(RecordingUploader.RECORDING_END_PARAM));

    assertEquals(
        EXPECTED_TAGS, ProfilingTestUtils.parseTags(parameters.get(RecordingUploader.TAGS_PARAM)));

    final byte[] expectedBytes =
        ByteStreams.toByteArray(
            Thread.currentThread().getContextClassLoader().getResourceAsStream(RECORDING_RESOURCE));
    assertArrayEquals(
        expectedBytes,
        (byte[]) Iterables.getFirst(parameters.get(RecordingUploader.DATA_PARAM), new byte[] {}));
  }

  @Test
  public void testRequestWithProxy() throws IOException, InterruptedException {
    final String backendHost = "intake.profilinf.datadoghq.com:1234";
    final String backendUrl = "http://" + backendHost + URL_PATH;
    when(config.getProfilingUrl()).thenReturn(backendUrl);
    when(config.getProfilingProxyHost()).thenReturn(server.url("").host());
    when(config.getProfilingProxyPort()).thenReturn(server.url("").port());
    when(config.getProfilingProxyUsername()).thenReturn("username");
    when(config.getProfilingProxyPassword()).thenReturn("password");

    uploader = new RecordingUploader(config);

    server.enqueue(new MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate: Basic"));
    server.enqueue(new MockResponse().setResponseCode(200));

    uploader.upload(RECORDING_TYPE, mockRecordingData(RECORDING_RESOURCE));

    final RecordedRequest recordedFirstRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(server.url(""), recordedFirstRequest.getRequestUrl());
    assertEquals(
        Credentials.basic(APIKEY_VALUE, ""), recordedFirstRequest.getHeader("Authorization"));
    assertNull(recordedFirstRequest.getHeader("Proxy-Authorization"));
    assertEquals(backendHost, recordedFirstRequest.getHeader("Host"));
    assertEquals(
        String.format("POST %s HTTP/1.1", backendUrl), recordedFirstRequest.getRequestLine());

    final RecordedRequest recordedSecondRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(server.url(""), recordedSecondRequest.getRequestUrl());
    assertEquals(
        Credentials.basic(APIKEY_VALUE, ""), recordedSecondRequest.getHeader("Authorization"));
    assertEquals(
        Credentials.basic("username", "password"),
        recordedSecondRequest.getHeader("Proxy-Authorization"));
    assertEquals(backendHost, recordedSecondRequest.getHeader("Host"));
    assertEquals(
        String.format("POST %s HTTP/1.1", backendUrl), recordedSecondRequest.getRequestLine());
  }

  @Test
  public void testRequestWithProxyDefaultPassword() throws IOException, InterruptedException {
    final String backendUrl = "http://intake.profilinf.datadoghq.com:1234" + URL_PATH;
    when(config.getProfilingUrl()).thenReturn(backendUrl);
    when(config.getProfilingProxyHost()).thenReturn(server.url("").host());
    when(config.getProfilingProxyPort()).thenReturn(server.url("").port());
    when(config.getProfilingProxyUsername()).thenReturn("username");

    uploader = new RecordingUploader(config);

    server.enqueue(new MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate: Basic"));
    server.enqueue(new MockResponse().setResponseCode(200));

    uploader.upload(RECORDING_TYPE, mockRecordingData(RECORDING_RESOURCE));

    final RecordedRequest recordedFirstRequest = server.takeRequest(5, TimeUnit.SECONDS);
    final RecordedRequest recordedSecondRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(
        Credentials.basic("username", ""), recordedSecondRequest.getHeader("Proxy-Authorization"));
  }

  @Test
  public void testRecordingClosed() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(200));

    final RecordingData recording = mockRecordingData(RECORDING_RESOURCE);
    uploader.upload(RECORDING_TYPE, recording);

    verify(recording.getStream()).close();
    verify(recording).release();
  }

  @Test
  public void test500Response() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(500));

    final RecordingData recording = mockRecordingData(RECORDING_RESOURCE);
    uploader.upload(RECORDING_TYPE, recording);

    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));

    verify(recording.getStream()).close();
    verify(recording).release();
  }

  @Test
  public void testConnectionRefused() throws IOException, InterruptedException {
    server.shutdown();

    final RecordingData recording = mockRecordingData(RECORDING_RESOURCE);
    uploader.upload(RECORDING_TYPE, recording);

    verify(recording.getStream()).close();
    verify(recording).release();
  }

  @Test
  public void testTimeout() throws IOException, InterruptedException {
    server.enqueue(
        new MockResponse()
            .setHeadersDelay(
                REQUEST_IO_OPERATION_TIMEOUT.plus(Duration.ofMillis(1000)).toMillis(),
                TimeUnit.MILLISECONDS));

    final RecordingData recording = mockRecordingData(RECORDING_RESOURCE);
    uploader.upload(RECORDING_TYPE, recording);

    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));

    verify(recording.getStream()).close();
    verify(recording).release();
  }

  @Test
  public void testUnfinishedRecording() throws IOException {
    final RecordingData recording = mockRecordingData(RECORDING_RESOURCE);
    when(recording.getStream()).thenThrow(new IllegalStateException("test exception"));
    uploader.upload(RECORDING_TYPE, recording);

    verify(recording).release();
    verify(recording, times(2)).getStream();
    verifyNoMoreInteractions(recording);
  }

  @Test
  public void testHeaders() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));

    uploader.upload(RECORDING_TYPE, mockRecordingData(RECORDING_RESOURCE));

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(VersionInfo.JAVA_LANG, recordedRequest.getHeader(VersionInfo.DATADOG_META_LANG));
    assertEquals(
        VersionInfo.JAVA_VERSION, recordedRequest.getHeader(VersionInfo.DATADOG_META_LANG_VERSION));
    assertEquals(
        VersionInfo.JAVA_VM_NAME,
        recordedRequest.getHeader(VersionInfo.DATADOG_META_LANG_INTERPRETER));
    assertEquals(
        VersionInfo.JAVA_VM_VENDOR,
        recordedRequest.getHeader(VersionInfo.DATADOG_META_LANG_INTERPRETER_VENDOR));
    assertEquals(
        "Stubbed-Test-Version", recordedRequest.getHeader(VersionInfo.DATADOG_META_TRACER_VERSION));
  }

  @Test
  public void testEnqueuedRequestsExecuted() throws IOException, InterruptedException {
    // We have to block all parallel requests to make sure queue is kept full
    for (int i = 0; i < RecordingUploader.MAX_RUNNING_REQUESTS; i++) {
      server.enqueue(
          new MockResponse()
              .setHeadersDelay(
                  // 1 second should be enough to schedule all requests and not hit timeout
                  Duration.ofMillis(1000).toMillis(), TimeUnit.MILLISECONDS)
              .setResponseCode(200));
    }
    server.enqueue(new MockResponse().setResponseCode(200));

    for (int i = 0; i < RecordingUploader.MAX_RUNNING_REQUESTS; i++) {
      final RecordingData recording = mockRecordingData(RECORDING_RESOURCE);
      uploader.upload(RECORDING_TYPE, recording);
    }

    final RecordingData additionalRecording = mockRecordingData(RECORDING_RESOURCE);
    uploader.upload(RECORDING_TYPE, additionalRecording);

    // Make sure all expected requests happened
    for (int i = 0; i < RecordingUploader.MAX_RUNNING_REQUESTS; i++) {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    }

    assertNotNull(server.takeRequest(2000, TimeUnit.MILLISECONDS), "Got enqueued request");

    verify(additionalRecording.getStream()).close();
    verify(additionalRecording).release();
  }

  @Test
  public void testTooManyRequests() throws IOException, InterruptedException {
    // We have to block all parallel requests to make sure queue is kept full
    for (int i = 0; i < RecordingUploader.MAX_RUNNING_REQUESTS; i++) {
      server.enqueue(
          new MockResponse()
              .setHeadersDelay(
                  REQUEST_IO_OPERATION_TIMEOUT.plus(Duration.ofMillis(1000)).toMillis(),
                  TimeUnit.MILLISECONDS)
              .setResponseCode(200));
    }
    server.enqueue(new MockResponse().setResponseCode(200));

    for (int i = 0; i < RecordingUploader.MAX_RUNNING_REQUESTS; i++) {
      final RecordingData recording = mockRecordingData(RECORDING_RESOURCE);
      uploader.upload(RECORDING_TYPE, recording);
    }

    final List<RecordingData> hangingRequests = new ArrayList<>();
    // We schedule one additional request to check case when request would be rejected immediately
    // rather than added to the queue.
    for (int i = 0; i < RecordingUploader.MAX_ENQUEUED_REQUESTS + 1; i++) {
      final RecordingData recording = mockRecordingData(RECORDING_RESOURCE);
      hangingRequests.add(recording);
      uploader.upload(RECORDING_TYPE, recording);
    }

    // Make sure all expected requests happened
    for (int i = 0; i < RecordingUploader.MAX_RUNNING_REQUESTS; i++) {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    }
    // Recordings after RecordingUploader.MAX_RUNNING_REQUESTS will not be executed because number
    // or parallel requests has been reached.
    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS), "No more requests");

    for (final RecordingData recording : hangingRequests) {
      verify(recording.getStream()).close();
      verify(recording).release();
    }
  }

  @Test
  public void testShutdown() throws IOException, InterruptedException {
    uploader.shutdown();

    final RecordingData recording = mockRecordingData(RECORDING_RESOURCE);
    uploader.upload(RECORDING_TYPE, recording);

    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS), "No more requests");

    verify(recording.getStream()).close();
    verify(recording).release();
  }

  private RecordingData mockRecordingData(final String recordingResource) throws IOException {
    final RecordingData recordingData = mock(RecordingData.class, withSettings().lenient());
    when(recordingData.getStream())
        .thenReturn(
            spy(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream(recordingResource)));
    when(recordingData.getName()).thenReturn(RECODING_NAME_PREFIX + SEQUENCE_NUMBER);
    when(recordingData.getStart()).thenReturn(Instant.ofEpochSecond(RECORDING_START));
    when(recordingData.getEnd()).thenReturn(Instant.ofEpochSecond(RECORDING_END));
    return recordingData;
  }
}

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
package com.datadog.profiling.controller;

import com.google.common.collect.ImmutableList;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
// Proper unused stub detection doesn't work in junit5 yet,
// see https://github.com/mockito/mockito/issues/1540
@MockitoSettings(strictness = Strictness.LENIENT)
public class ProfilingSystemTest {

  // Time in MS when all things should have been done by
  // Should be noticeably bigger than one recording iteration
  private static final long REASONABLE_TIMEOUT = 5000;

  private final ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1);
  private final AtomicInteger counter = new AtomicInteger();

  @Mock private Controller controller;
  @Mock private OngoingRecording continuousRecording;
  @Mock private OngoingRecording profilingRecording;
  @Mock private RecordingData recordingData;
  @Mock private RecordingData anotherRecordingData;
  @Mock private RecordingDataListener listener;

  @BeforeEach
  public void setup() {
    when(controller.createContinuousRecording(ProfilingSystem.CONTINUOUS_RECORDING_NAME))
        .thenReturn(continuousRecording);
    when(controller.createRecording(any())).thenReturn(profilingRecording);
  }

  @AfterEach
  public void tearDown() {
    pool.shutdown();
  }

  @Test
  public void testShutdown() throws ConfigurationException, InterruptedException {
    final ProfilingSystem system =
        new ProfilingSystem(
            controller,
            listener,
            Duration.ZERO,
            Duration.ofMillis(200),
            Duration.ofMillis(100),
            Duration.ofMillis(300),
            pool,
            counter);
    system.start();
    Thread.sleep(500);
    system.shutdown();

    verify(continuousRecording).close();
    assertTrue(pool.isTerminated());
  }

  @Test
  public void testShutdownWithRunningRecording()
      throws ConfigurationException, InterruptedException {
    final ProfilingSystem system =
        new ProfilingSystem(
            controller,
            listener,
            Duration.ZERO,
            Duration.ofMillis(REASONABLE_TIMEOUT + 1),
            Duration.ofMillis(REASONABLE_TIMEOUT),
            Duration.ZERO,
            pool,
            counter);
    system.start();
    verify(controller, timeout(100)).createRecording(any());
    system.shutdown();
    verify(profilingRecording).close();
    assertTrue(pool.isTerminated());
  }

  @Test
  public void testShutdownInterruption() throws ConfigurationException {
    final Thread mainThread = Thread.currentThread();
    doAnswer(
            (InvocationOnMock invocation) -> {
              while (!pool.isShutdown()) {
                try {
                  Thread.sleep(100);
                } catch (InterruptedException e) {
                  // Ignore InterruptedException to make sure this threads lives through executor
                  // shutdown
                }
              }
              // Interrupting main thread to make sure this is handled properly
              mainThread.interrupt();
              return null;
            })
        .when(controller)
        .createRecording(any());
    final ProfilingSystem system =
        new ProfilingSystem(
            controller,
            listener,
            Duration.ZERO,
            Duration.ofMillis(REASONABLE_TIMEOUT + 1),
            Duration.ofMillis(REASONABLE_TIMEOUT),
            Duration.ZERO,
            pool,
            counter);
    system.start();
    // Make sure we actually started the recording before terminating
    verify(controller, timeout(100)).createRecording(any());
    system.shutdown();
    assertTrue(true, "Shutdown exited cleanly after interruption");
  }

  @Test
  public void testCanShutDownWithoutStarting() throws ConfigurationException {
    final ProfilingSystem system =
        new ProfilingSystem(
            controller,
            listener,
            Duration.ZERO,
            Duration.ofMillis(200),
            Duration.ofMillis(100),
            Duration.ZERO,
            pool,
            counter);
    system.shutdown();
    assertTrue(pool.isTerminated());
  }

  @Test
  public void testDoesntSendDataIfNotStarted() throws InterruptedException, ConfigurationException {
    final ProfilingSystem system =
        new ProfilingSystem(
            controller,
            listener,
            Duration.ZERO,
            Duration.ofMillis(1),
            Duration.ofMillis(1),
            Duration.ofMillis(1));
    Thread.sleep(20);
    system.shutdown();
    verify(controller, never()).createRecording(any());
    verify(listener, never()).onNewData(any(), any());
  }

  @Test
  public void testDoesntSendPeriodicRecordingIfPeriodicRecordingIsDisabled()
      throws InterruptedException, ConfigurationException {
    when(continuousRecording.snapshot(any(), any())).thenReturn(recordingData);
    final ProfilingSystem system =
        new ProfilingSystem(
            controller,
            listener,
            Duration.ZERO,
            Duration.ZERO,
            Duration.ofMillis(1),
            Duration.ofMillis(1));
    system.start();
    Thread.sleep(200);
    system.shutdown();
    verify(controller, never()).createRecording(any());
    verify(listener, never()).onNewData(eq(RecordingType.PERIODIC), any());
    verify(listener, atLeastOnce()).onNewData(eq(RecordingType.CONTINUOUS), any());
  }

  @Test
  public void testDoesntSendContinuousRecordingIfContinuousRecordingIsDisabled()
      throws InterruptedException, ConfigurationException {
    when(continuousRecording.snapshot(any(), any())).thenReturn(recordingData);
    final ProfilingSystem system =
        new ProfilingSystem(
            controller,
            listener,
            Duration.ZERO,
            Duration.ofMillis(10),
            Duration.ofMillis(1),
            Duration.ZERO);
    system.start();
    Thread.sleep(200);
    system.shutdown();
    verify(listener, never()).onNewData(eq(RecordingType.CONTINUOUS), any());
    verify(listener, atLeastOnce()).onNewData(eq(RecordingType.PERIODIC), any());
  }

  @Test
  public void testProfilingSystemNegativeDelay() {
    assertThrows(
        ConfigurationException.class,
        () -> {
          new ProfilingSystem(
              controller,
              listener,
              Duration.ofMillis(-200),
              Duration.ofMillis(200),
              Duration.ofMillis(400),
              Duration.ZERO);
        });
  }

  @Test
  public void testProfilingSystemNegativePeriod() {
    assertThrows(
        ConfigurationException.class,
        () -> {
          new ProfilingSystem(
              controller,
              listener,
              Duration.ZERO,
              Duration.ofMillis(-200),
              Duration.ofMillis(400),
              Duration.ZERO);
        });
  }

  /**
   * Ensuring it is not possible to configure the profiling system to have greater recording lengths
   * than the dump periodicity.
   */
  @Test
  public void testProfilingSystemCreationBadConfig() {
    assertThrows(
        ConfigurationException.class,
        () -> {
          new ProfilingSystem(
              controller,
              listener,
              Duration.ZERO,
              Duration.ofMillis(200),
              Duration.ofMillis(400),
              Duration.ZERO);
        });
  }

  @Test
  public void testProfilingSystemNegativeUploadPeriod() {
    assertThrows(
        ConfigurationException.class,
        () -> {
          new ProfilingSystem(
              controller,
              listener,
              Duration.ZERO,
              Duration.ofMillis(400),
              Duration.ofMillis(200),
              Duration.ofMillis(-200));
        });
  }

  /**
   * Ensuring that it can be started, and recording data for a few profiling recordings captured.
   */
  @Test
  public void testPeriodicProfilingRecording() throws ConfigurationException {
    final Duration recordingPeriod = Duration.ofMillis(500);
    final Duration recordingDuration = Duration.ofMillis(25);
    final List<RecordingData> generatedRecordingData = new ArrayList<>();
    when(controller.createRecording(any()))
        .thenAnswer(
            (InvocationOnMock invocation) -> {
              final RecordingData recordingData = mock(RecordingData.class);
              when(recordingData.getName()).thenReturn(invocation.getArgument(0, String.class));
              when(recordingData.getStart()).thenReturn(Instant.now());
              generatedRecordingData.add(recordingData);
              final OngoingRecording ongoingRecording = mock(OngoingRecording.class);
              when(ongoingRecording.stop())
                  .thenAnswer(
                      (InvocationOnMock stopInvocation) -> {
                        when(recordingData.getEnd()).thenReturn(Instant.now());
                        return recordingData;
                      });
              return ongoingRecording;
            });

    final ProfilingSystem system =
        new ProfilingSystem(
            controller,
            listener,
            Duration.ZERO,
            recordingPeriod,
            recordingDuration,
            Duration.ZERO,
            pool,
            counter);
    system.start();

    final ArgumentCaptor<RecordingData> captor = ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).times(3))
        .onNewData(eq(RecordingType.PERIODIC), captor.capture());
    assertEquals(generatedRecordingData, captor.getAllValues());

    system.shutdown();

    verify(listener, after(REASONABLE_TIMEOUT).atMost(4))
        .onNewData(eq(RecordingType.PERIODIC), captor.capture());
    assertEquals(generatedRecordingData, captor.getAllValues());

    for (int i = 0; i < captor.getAllValues().size(); i++) {
      final RecordingData recording = captor.getAllValues().get(i);
      assertEquals(
          ProfilingSystem.PROFILING_RECORDING_NAME_PREFIX + i,
          recording.getName(),
          "name of the invocation " + i + " is correct");
      assertTrue(
          recording.getStart().plus(recordingDuration).isBefore(recording.getEnd().plusMillis(1)),
          "recording " + i + " has required duration");
    }

    assertTrue(
        captor
            .getAllValues()
            .get(1)
            .getStart()
            .plus(recordingPeriod)
            // It looks like jobs get scheduled slightly earlier so we give a 50ms leeway
            .isBefore(captor.getAllValues().get(2).getStart().plusMillis(50)),
        "recordings have required period");
  }

  /** Ensure that we continue recording after one recording fails to get created */
  @Test
  public void testPeriodicProfilingRecordingCreateError() throws ConfigurationException {
    final Duration recordingPeriod = Duration.ofMillis(200);
    final Duration recordingDuration = Duration.ofMillis(25);
    final List<RecordingData> generatedRecordingData = new ArrayList<>();
    when(controller.createRecording(any()))
        .thenThrow(new RuntimeException("Test"))
        .thenAnswer(
            (InvocationOnMock invocation) -> {
              final RecordingData recordingData = mock(RecordingData.class);
              when(recordingData.getName()).thenReturn(invocation.getArgument(0, String.class));
              when(recordingData.getStart()).thenReturn(Instant.now());
              generatedRecordingData.add(recordingData);
              final OngoingRecording ongoingRecording = mock(OngoingRecording.class);
              when(ongoingRecording.stop())
                  .thenAnswer(
                      (InvocationOnMock stopInvocation) -> {
                        when(recordingData.getEnd()).thenReturn(Instant.now());
                        return recordingData;
                      });
              return ongoingRecording;
            });

    final ProfilingSystem system =
        new ProfilingSystem(
            controller,
            listener,
            Duration.ZERO,
            recordingPeriod,
            recordingDuration,
            Duration.ZERO,
            pool,
            counter);
    system.start();

    final ArgumentCaptor<RecordingData> captor = ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).times(2))
        .onNewData(eq(RecordingType.PERIODIC), captor.capture());
    assertEquals(generatedRecordingData, captor.getAllValues());

    system.shutdown();
  }

  @Test
  public void testContinuousRecording() throws ConfigurationException {
    final Instant testStartTime = Instant.now();
    final ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
    final ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);
    when(continuousRecording.snapshot(startCaptor.capture(), endCaptor.capture()))
        .thenReturn(recordingData)
        .thenReturn(anotherRecordingData);

    final ProfilingSystem system =
        new ProfilingSystem(
            controller,
            listener,
            Duration.ZERO,
            Duration.ZERO,
            Duration.ofSeconds(2),
            Duration.ofMillis(200));
    system.start();

    final ArgumentCaptor<RecordingData> recordingDataCaptor =
        ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).times(2))
        .onNewData(eq(RecordingType.CONTINUOUS), recordingDataCaptor.capture());

    assertEquals(
        ImmutableList.of(recordingData, anotherRecordingData), recordingDataCaptor.getAllValues());

    assertTrue(startCaptor.getAllValues().get(0).isAfter(testStartTime));
    assertEquals(startCaptor.getAllValues().get(1), endCaptor.getAllValues().get(0));
    assertTrue(
        endCaptor.getAllValues().get(0).isBefore(Instant.now()),
        "Last captured value is before now");

    system.shutdown();
  }

  @Test
  public void testContinuousRecordingException() throws ConfigurationException {
    final ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
    final ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);
    when(continuousRecording.snapshot(startCaptor.capture(), endCaptor.capture()))
        .thenThrow(new RuntimeException("test error"))
        .thenReturn(recordingData)
        .thenReturn(anotherRecordingData);

    final ProfilingSystem system =
        new ProfilingSystem(
            controller,
            listener,
            Duration.ZERO,
            Duration.ZERO,
            Duration.ofSeconds(2),
            Duration.ofMillis(200));
    system.start();

    final ArgumentCaptor<RecordingData> recordingDataCaptor =
        ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).times(2))
        .onNewData(eq(RecordingType.CONTINUOUS), recordingDataCaptor.capture());

    assertEquals(
        ImmutableList.of(recordingData, anotherRecordingData), recordingDataCaptor.getAllValues());

    system.shutdown();
  }

  @Test
  public void testContinuousRecordingNoData() throws ConfigurationException {
    final ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
    final ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);
    when(continuousRecording.snapshot(startCaptor.capture(), endCaptor.capture()))
        .thenReturn(null)
        .thenReturn(recordingData)
        .thenReturn(anotherRecordingData);

    final ProfilingSystem system =
        new ProfilingSystem(
            controller,
            listener,
            Duration.ZERO,
            Duration.ZERO,
            Duration.ofSeconds(2),
            Duration.ofMillis(200));
    system.start();

    final ArgumentCaptor<RecordingData> recordingDataCaptor =
        ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).times(2))
        .onNewData(eq(RecordingType.CONTINUOUS), recordingDataCaptor.capture());

    assertEquals(
        ImmutableList.of(recordingData, anotherRecordingData), recordingDataCaptor.getAllValues());

    system.shutdown();
  }
}

package datadog.trace.instrumentation.junit5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.junit5.JUnit5Decorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.util.ReflectionUtils;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

public class TracingListener implements TestExecutionListener {

  private final Map<String, String> versionsByEngineId;

  private final Method getTestCaseMethod;

  public TracingListener(Iterable<TestEngine> testEngines) {
    final Map<String, String> versions = new HashMap<>();
    testEngines.forEach(
        testEngine ->
            testEngine
                .getVersion()
                .ifPresent(version -> versions.put(testEngine.getId(), version)));
    versionsByEngineId = Collections.unmodifiableMap(versions);
    getTestCaseMethod = accessGetTestCaseMethod();
  }

  private Method accessGetTestCaseMethod() {
    try {
      // the method was added in JUnit 5.7
      // if older version of the framework is used, we fall back to a slower mechanism
      Method method = MethodSource.class.getMethod("getJavaMethod");
      method.setAccessible(true);
      return method;
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public void executionStarted(final TestIdentifier testIdentifier) {
    if (!testIdentifier.isTest()) {
      return;
    }

    // If there is an active span that represents a test
    // we don't want to generate another child test span.
    // This can happen when the user executes a certain test
    // using the different test engines.
    // (e.g. JUnit 4 tests using JUnit5 engine)
    if (DECORATE.isTestSpan(AgentTracer.activeSpan())) {
      return;
    }

    testIdentifier
        .getSource()
        .filter(testSource -> testSource instanceof MethodSource)
        .map(testSource -> (MethodSource) testSource)
        .ifPresent(
            methodSource -> {
              final AgentSpan span = startSpan("junit.test");
              final AgentScope scope = activateSpan(span);
              scope.setAsyncPropagation(true);

              final String version =
                  UniqueId.parse(testIdentifier.getUniqueId())
                      .getEngineId()
                      .map(versionsByEngineId::get)
                      .orElse(null);

              DECORATE.afterStart(
                  span, version, getTestClass(methodSource), getTestMethod(methodSource));
              DECORATE.onTestStart(span, methodSource, testIdentifier);
            });
  }

  private Class<?> getTestClass(MethodSource methodSource) {
    String className = methodSource.getClassName();
    if (className == null || className.isEmpty()) {
      return null;
    }
    return ReflectionUtils.loadClass(className).orElse(null);
  }

  private Method getTestMethod(MethodSource methodSource) {
    if (getTestCaseMethod != null && getTestCaseMethod.isAccessible()) {
      try {
        return (Method) getTestCaseMethod.invoke(methodSource);
      } catch (Exception e) {
        // ignore, fallback to slower mechanism below
      }
    }

    Class<?> testClass = getTestClass(methodSource);
    if (testClass == null) {
      return null;
    }

    String methodName = methodSource.getMethodName();
    if (methodName == null || methodName.isEmpty()) {
      return null;
    }

    try {
      return ReflectionUtils.findMethod(
              testClass, methodName, methodSource.getMethodParameterTypes())
          .orElse(null);
    } catch (JUnitException e) {
      return null;
    }
  }

  @Override
  public void executionFinished(
      final TestIdentifier testIdentifier, final TestExecutionResult testExecutionResult) {
    if (!testIdentifier.isTest()) {
      return;
    }

    testIdentifier
        .getSource()
        .filter(testSource -> testSource instanceof MethodSource)
        .ifPresent(
            testSource -> {
              final AgentSpan span = AgentTracer.activeSpan();
              if (span == null) {
                return;
              }

              final AgentScope scope = AgentTracer.activeScope();
              if (scope != null) {
                scope.close();
              }

              DECORATE.onTestFinish(span, testExecutionResult);
              DECORATE.beforeFinish(span);
              span.finish();
            });
  }

  @Override
  public void executionSkipped(final TestIdentifier testIdentifier, final String reason) {
    testIdentifier
        .getSource()
        .ifPresent(
            testSource -> {
              final String version =
                  UniqueId.parse(testIdentifier.getUniqueId())
                      .getEngineId()
                      .map(versionsByEngineId::get)
                      .orElse(null);
              if (testSource instanceof ClassSource) {
                // The annotation @Disabled is kept at type level.
                executionSkipped((ClassSource) testSource, version, reason);
              } else if (testSource instanceof MethodSource) {
                // The annotation @Disabled is kept at method level.
                executionSkipped((MethodSource) testSource, version, reason);
              }
            });
  }

  private void executionSkipped(
      final ClassSource classSource, final String version, final String reason) {
    // If @Disabled annotation is kept at type level, the instrumentation
    // reports every method annotated with @Test as skipped test.
    final String testSuite = classSource.getClassName();
    Class<?> testClass = classSource.getJavaClass();
    final List<Method> testMethods = DECORATE.testMethods(testClass, Test.class);

    for (final Method testMethod : testMethods) {
      final AgentSpan span = startSpan("junit.test");
      DECORATE.afterStart(span, version, testClass, testMethod);
      DECORATE.onTestIgnore(span, testSuite, testMethod.getName(), reason);
      DECORATE.beforeFinish(span);
      // We set a duration of 1 ns, because a span with duration==0 has a special treatment in the
      // tracer.
      span.finishWithDuration(1L);
    }
  }

  private void executionSkipped(
      final MethodSource methodSource, final String version, final String reason) {
    final String testSuite = methodSource.getClassName();
    final String testName = methodSource.getMethodName();

    final AgentSpan span = startSpan("junit.test");
    DECORATE.afterStart(span, version, getTestClass(methodSource), getTestMethod(methodSource));
    DECORATE.onTestIgnore(span, testSuite, testName, reason);
    DECORATE.beforeFinish(span);
    // We set a duration of 1 ns, because a span with duration==0 has a special treatment in the
    // tracer.
    span.finishWithDuration(1L);
  }
}

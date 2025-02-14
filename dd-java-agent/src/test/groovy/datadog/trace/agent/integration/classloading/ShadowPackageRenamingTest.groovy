package datadog.trace.agent.integration.classloading

import com.google.common.reflect.ClassPath
import datadog.trace.agent.test.IntegrationTestUtils
import okio.BufferedSink
import spock.lang.Ignore
import spock.lang.Specification

class ShadowPackageRenamingTest extends Specification {
  def "agent dependencies renamed"() {
    setup:
    final Class<?> ddClass =
      IntegrationTestUtils.getAgentClassLoader()
      .loadClass("datadog.trace.agent.tooling.AgentInstaller")
    final URL userOkio =
      BufferedSink.getProtectionDomain().getCodeSource().getLocation()
    final URL agentOkioDep =
      ddClass
      .getClassLoader()
      .loadClass("okio.BufferedSink")
      .getProtectionDomain()
      .getCodeSource()
      .getLocation()
    final URL agentSource =
      ddClass.getProtectionDomain().getCodeSource().getLocation()

    expect:
    agentSource.getFile() =~ ".*/dd-java-agent/build/libs/dd-java-agent-.*.jar"
    agentSource.getProtocol() == "file"
    agentSource == agentOkioDep
    agentSource.getFile() != userOkio.getFile()
  }

  //  @Ignore("OT 0.32 removed this field.  Need to find another option.")
  //  def "java getLogger rewritten to safe logger"() {
  //    setup:
  //    Field logField = io.opentracing.util.GlobalTracer.getDeclaredField("LOGGER")
  //    logField.setAccessible(true)
  //    Object logger = logField.get(null)
  //
  //    expect:
  //    !logger.getClass().getName().startsWith("java.util.logging")
  //
  //    cleanup:
  //    logField?.setAccessible(false)
  //  }

  def "agent classes not visible"() {
    when:
    ClassLoader.getSystemClassLoader().loadClass("datadog.trace.agent.tooling.AgentInstaller")
    then:
    thrown ClassNotFoundException
  }

  @Ignore("Agent jar check inaccurate")
  def "agent jar contains no bootstrap classes"() {
    setup:
    final ClassPath agentClasspath = ClassPath.from(IntegrationTestUtils.getAgentClassLoader())
    final ClassPath jmxFetchClasspath = ClassPath.from(IntegrationTestUtils.getJmxFetchClassLoader())

    final ClassPath bootstrapClasspath = ClassPath.from(IntegrationTestUtils.getBootstrapProxy())
    final Set<String> bootstrapClasses = new HashSet<>()
    final String[] bootstrapPrefixes = IntegrationTestUtils.getBootstrapPackagePrefixes()
    final String[] agentPrefixes = IntegrationTestUtils.getAgentPackagePrefixes()
    final List<String> badBootstrapPrefixes = []

    for (ClassPath.ClassInfo info : bootstrapClasspath.getAllClasses()) {
      bootstrapClasses.add(info.getName())
      // make sure all bootstrap classes can be loaded from system
      ClassLoader.getSystemClassLoader().loadClass(info.getName())
      boolean goodPrefix = false
      for (int i = 0; i < bootstrapPrefixes.length; ++i) {
        if (info.getName().startsWith(bootstrapPrefixes[i])) {
          goodPrefix = true
          break
        }
      }
      if (!goodPrefix) {
        badBootstrapPrefixes.add(info.getName())
      }
    }

    final List<ClassPath.ClassInfo> agentDuplicateClassFile = new ArrayList<>()
    final List<String> badAgentPrefixes = []
    assert agentClasspath.getAllClasses().size() > 50
    for (ClassPath.ClassInfo classInfo : agentClasspath.getAllClasses()) {
      if (bootstrapClasses.contains(classInfo.getName())) {
        agentDuplicateClassFile.add(classInfo)
      }
      boolean goodPrefix = false
      for (int i = 0; i < agentPrefixes.length; ++i) {
        if (classInfo.getName().startsWith(agentPrefixes[i])) {
          goodPrefix = true
          break
        }
      }
      if (!goodPrefix) {
        badAgentPrefixes.add(classInfo.getName())
      }
    }

    final List<ClassPath.ClassInfo> jmxFetchDuplicateClassFile = new ArrayList<>()
    final List<String> badJmxFetchPrefixes = []
    for (ClassPath.ClassInfo classInfo : jmxFetchClasspath.getAllClasses()) {
      if (bootstrapClasses.contains(classInfo.getName())) {
        jmxFetchDuplicateClassFile.add(classInfo)
      }
      boolean goodPrefix = true
      for (int i = 0; i < bootstrapPrefixes.length; ++i) {
        if (classInfo.getName().startsWith(bootstrapPrefixes[i])) {
          goodPrefix = false
          break
        }
      }
      if (!goodPrefix) {
        badJmxFetchPrefixes.add(classInfo.getName())
      }
    }

    expect:
    agentDuplicateClassFile == []
    badBootstrapPrefixes == []
    badAgentPrefixes == []
    badJmxFetchPrefixes == []
  }
}

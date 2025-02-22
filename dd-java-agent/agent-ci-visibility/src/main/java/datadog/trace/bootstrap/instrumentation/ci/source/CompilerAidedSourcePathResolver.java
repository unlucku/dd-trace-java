package datadog.trace.bootstrap.instrumentation.ci.source;

import datadog.compiler.utils.CompilerUtils;
import java.io.File;
import javax.annotation.Nullable;

public class CompilerAidedSourcePathResolver implements SourcePathResolver {

  private final String repoRoot;

  public CompilerAidedSourcePathResolver(String repoRoot) {
    this.repoRoot = repoRoot.endsWith(File.separator) ? repoRoot : (repoRoot + File.separator);
  }

  @Nullable
  @Override
  public String getSourcePath(Class<?> c) {
    String absoluteSourcePath = CompilerUtils.getSourcePath(c);
    if (absoluteSourcePath != null && absoluteSourcePath.startsWith(repoRoot)) {
      return absoluteSourcePath.substring(repoRoot.length());
    } else {
      return null;
    }
  }
}

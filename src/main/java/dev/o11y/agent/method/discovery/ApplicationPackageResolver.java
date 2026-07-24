package dev.o11y.agent.method.discovery;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Resolves the package boundary used by policy-driven method instrumentation. */
public final class ApplicationPackageResolver {
  private static final String OVERRIDE_ENVIRONMENT_VARIABLE = "O11Y_METHOD_PACKAGES";
  private static final String START_CLASS_ATTRIBUTE = "Start-Class";
  private static final String QUARKUS_FAST_JAR_MAIN_CLASS =
      "io.quarkus.bootstrap.runner.QuarkusEntryPoint";
  private static final String QUARKUS_GENERATED_MAIN_CLASS = "io.quarkus.runner.GeneratedMain";
  private static final String QUARKUS_RUN_JAR = "quarkus-run.jar";
  private static final String QUARKUS_APPLICATION_DATA = "quarkus-application.dat";
  private static final Pattern JAVA_NAME =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
  private static final List<String> FORBIDDEN_PACKAGE_PREFIXES =
      List.of(
          "java",
          "javax",
          "jakarta",
          "jdk",
          "sun",
          "com.sun",
          "com.fasterxml",
          "io.netty",
          "io.opentelemetry",
          "io.quarkus",
          "kotlin",
          "net.bytebuddy",
          "org.apache",
          "org.springframework",
          "org.w3c",
          "org.xml",
          "reactor",
          "scala",
          "dev.o11y.agent");
  private static final List<String> QUARKUS_FRAMEWORK_PACKAGE_PREFIXES =
      List.of(
          "com.oracle.svm",
          "io.quarkus",
          "io.smallrye",
          "org.eclipse.microprofile",
          "org.graalvm",
          "org.jboss");
  private static final List<String> LAUNCHER_CLASS_PREFIXES =
      List.of(
          "org.springframework.boot.loader.",
          "org.apache.maven.",
          "org.codehaus.plexus.",
          "org.gradle.",
          "org.junit.",
          "org.apache.catalina.startup.",
          "org.eclipse.jetty.start.",
          "io.quarkus.bootstrap.",
          "io.quarkus.runner.",
          "org.jboss.modules.");
  private static final int MAX_CLASSPATH_ENTRIES = 128;
  private static final int MAX_APPLICATION_ARCHIVE_ENTRIES = 50_000;
  private static final int MAX_APPLICATION_CLASSES = 20_000;
  private static final int MAX_CONFIGURED_PACKAGES = 32;
  private static final int MAX_CONFIGURATION_LENGTH = 4096;

  private static final Resolution CURRENT = currentResolution();

  private ApplicationPackageResolver() {}

  /** Returns the immutable application package boundary fixed during agent startup. */
  public static List<String> allowedPackages() {
    return CURRENT.packagePrefixes();
  }

  /**
   * Returns {@code override}, {@code start-class}, {@code main-class}, a {@code quarkus-*}
   * layout, or {@code none}.
   */
  public static String source() {
    return CURRENT.source();
  }

  /**
   * Parses the compatibility override while rejecting platform, agent, and overly broad prefixes.
   */
  public static List<String> parseConfiguredPackages(String configured) {
    if (configured == null
        || configured.isBlank()
        || configured.length() > MAX_CONFIGURATION_LENGTH) {
      return List.of();
    }
    String[] candidates = configured.split(",", -1);
    if (candidates.length > MAX_CONFIGURED_PACKAGES) {
      return List.of();
    }
    return Arrays.stream(candidates)
        .map(String::trim)
        .filter(ApplicationPackageResolver::isSafePackagePrefix)
        .distinct()
        .toList();
  }

  private static Resolution currentResolution() {
    try {
      return resolve(
          System.getenv(OVERRIDE_ENVIRONMENT_VARIABLE),
          System.getProperty("sun.java.command", ""),
          System.getProperty("java.class.path", ""));
    } catch (RuntimeException ignored) {
      return Resolution.none();
    }
  }

  static Resolution resolve(String configured, String javaCommand, String classPath) {
    if (configured != null && !configured.isBlank()) {
      return new Resolution(parseConfiguredPackages(configured), "override");
    }

    String command = firstCommandToken(javaCommand);
    if (isJarName(command)) {
      Resolution manifest = manifestResolution(command, true);
      if (manifest != null) {
        return manifest;
      }
      Resolution quarkus = quarkusResolution(command);
      if (quarkus != null) {
        return quarkus;
      }
    } else {
      String mainClass = moduleMainClass(command);
      String packagePrefix = packageOfMainClass(mainClass);
      if (packagePrefix != null) {
        return new Resolution(List.of(packagePrefix), "main-class");
      }
    }

    Resolution classPathManifest = uniqueStartClassResolution(classPath);
    return classPathManifest == null ? Resolution.none() : classPathManifest;
  }

  private static Resolution uniqueStartClassResolution(String classPath) {
    if (classPath == null || classPath.isBlank()) {
      return null;
    }
    Set<String> packages = new LinkedHashSet<>();
    String[] entries = classPath.split(Pattern.quote(File.pathSeparator), -1);
    int inspected = 0;
    for (String entry : entries) {
      if (entry.isBlank() || inspected++ >= MAX_CLASSPATH_ENTRIES || !isJarName(entry)) {
        continue;
      }
      Resolution candidate = manifestResolution(entry, false);
      if (candidate != null) {
        packages.addAll(candidate.packagePrefixes());
      }
    }
    return packages.size() == 1
        ? new Resolution(List.copyOf(packages), "start-class")
        : null;
  }

  private static Resolution manifestResolution(String jarName, boolean includeMainClass) {
    try {
      Path jarPath = Path.of(jarName).toAbsolutePath().normalize();
      if (!Files.isRegularFile(jarPath)) {
        return null;
      }
      try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
        if (jar.getManifest() == null) {
          return null;
        }
        Attributes attributes = jar.getManifest().getMainAttributes();
        String startPackage = packageOfMainClass(attributes.getValue(START_CLASS_ATTRIBUTE));
        if (startPackage != null) {
          return new Resolution(List.of(startPackage), "start-class");
        }
        if (includeMainClass) {
          String mainPackage =
              packageOfMainClass(attributes.getValue(Attributes.Name.MAIN_CLASS));
          if (mainPackage != null) {
            return new Resolution(List.of(mainPackage), "main-class");
          }
        }
      }
    } catch (IOException | InvalidPathException | SecurityException ignored) {
      // An unreadable or malformed launch artifact must disable method matching.
    }
    return null;
  }

  private static Resolution quarkusResolution(String jarName) {
    try {
      Path launcher = Path.of(jarName).toAbsolutePath().normalize();
      if (!Files.isRegularFile(launcher)) {
        return null;
      }
      try (JarFile jar = new JarFile(launcher.toFile(), false)) {
        if (jar.getManifest() == null) {
          return null;
        }
        Attributes attributes = jar.getManifest().getMainAttributes();
        String mainClass = attributes.getValue(Attributes.Name.MAIN_CLASS);
        if (QUARKUS_FAST_JAR_MAIN_CLASS.equals(mainClass)) {
          return quarkusFastJarResolution(launcher);
        }
        if (QUARKUS_GENERATED_MAIN_CLASS.equals(mainClass)) {
          return quarkusGeneratedMainResolution(launcher, attributes);
        }
      }
    } catch (IOException | InvalidPathException | SecurityException ignored) {
      // An incomplete or unreadable Quarkus layout must disable method matching.
    }
    return null;
  }

  private static Resolution quarkusFastJarResolution(Path launcher) throws IOException {
    if (!QUARKUS_RUN_JAR.equals(fileName(launcher))) {
      return null;
    }
    Path distribution = launcher.getParent();
    if (distribution == null
        || !Files.isRegularFile(
            distribution.resolve("quarkus").resolve(QUARKUS_APPLICATION_DATA))) {
      return null;
    }
    Path applicationDirectory = distribution.resolve("app");
    if (!Files.isDirectory(applicationDirectory)) {
      return null;
    }

    List<Path> applicationJars;
    try (Stream<Path> entries = Files.list(applicationDirectory)) {
      applicationJars =
          entries
              .filter(Files::isRegularFile)
              .filter(path -> isJarName(fileName(path)))
              .limit(2)
              .toList();
    }
    if (applicationJars.size() != 1) {
      return null;
    }
    return archiveResolution(applicationJars.get(0), "quarkus-fast-jar");
  }

  private static Resolution quarkusGeneratedMainResolution(
      Path launcher, Attributes attributes) {
    Path originalApplication = quarkusUberJarApplication(launcher);
    if (originalApplication != null) {
      return archiveResolution(originalApplication, "quarkus-uber-jar");
    }

    String classPath = attributes.getValue(Attributes.Name.CLASS_PATH);
    Path parent = launcher.getParent();
    if ((classPath == null || classPath.isBlank())
        && (parent == null || !Files.isDirectory(parent.resolve("lib")))) {
      return null;
    }
    return archiveResolution(launcher, "quarkus-runner-jar");
  }

  private static Path quarkusUberJarApplication(Path launcher) {
    String fileName = fileName(launcher);
    if (!fileName.endsWith("-runner.jar")) {
      return null;
    }
    String baseName = fileName.substring(0, fileName.length() - "-runner.jar".length());
    Path parent = launcher.getParent();
    if (parent == null) {
      return null;
    }
    Path renamedOriginal = parent.resolve(baseName + ".jar.original");
    if (Files.isRegularFile(renamedOriginal)) {
      return renamedOriginal;
    }
    Path retainedOriginal = parent.resolve(baseName + ".jar");
    return Files.isRegularFile(retainedOriginal) ? retainedOriginal : null;
  }

  private static String fileName(Path path) {
    Path fileName = path.getFileName();
    return fileName == null ? "" : fileName.toString();
  }

  private static Resolution archiveResolution(Path archive, String source) {
    String packagePrefix = applicationPackageBoundary(archive);
    return packagePrefix == null ? null : new Resolution(List.of(packagePrefix), source);
  }

  private static String applicationPackageBoundary(Path archive) {
    try (JarFile jar = new JarFile(archive.toFile(), false)) {
      Enumeration<JarEntry> entries = jar.entries();
      String commonPackage = null;
      int inspectedEntries = 0;
      int applicationClasses = 0;
      while (entries.hasMoreElements()) {
        if (++inspectedEntries > MAX_APPLICATION_ARCHIVE_ENTRIES) {
          return null;
        }
        String packageName = packageOfClassEntry(entries.nextElement());
        if (packageName == null || isQuarkusFrameworkPackage(packageName)) {
          continue;
        }
        if (++applicationClasses > MAX_APPLICATION_CLASSES) {
          return null;
        }
        commonPackage =
            commonPackage == null ? packageName : commonPackage(commonPackage, packageName);
        if (commonPackage == null || !isSafePackagePrefix(commonPackage)) {
          return null;
        }
      }
      return applicationClasses == 0 ? null : commonPackage;
    } catch (IOException | SecurityException ignored) {
      return null;
    }
  }

  private static String packageOfClassEntry(JarEntry entry) {
    if (entry.isDirectory()) {
      return null;
    }
    String name = entry.getName();
    if (name.startsWith("META-INF/versions/")) {
      int versionEnd = name.indexOf('/', "META-INF/versions/".length());
      if (versionEnd < 0) {
        return null;
      }
      String version = name.substring("META-INF/versions/".length(), versionEnd);
      if (version.isEmpty() || !version.chars().allMatch(Character::isDigit)) {
        return null;
      }
      name = name.substring(versionEnd + 1);
    }
    if (!name.endsWith(".class") || name.endsWith("module-info.class")) {
      return null;
    }
    int packageSeparator = name.lastIndexOf('/');
    if (packageSeparator <= 0) {
      return null;
    }
    String packageName = name.substring(0, packageSeparator).replace('/', '.');
    return isSafePackagePrefix(packageName) ? packageName : null;
  }

  private static boolean isQuarkusFrameworkPackage(String packageName) {
    return QUARKUS_FRAMEWORK_PACKAGE_PREFIXES.stream()
        .anyMatch(
            prefix -> packageName.equals(prefix) || packageName.startsWith(prefix + '.'));
  }

  private static String commonPackage(String left, String right) {
    if (left.equals(right)) {
      return left;
    }
    String[] leftParts = left.split("\\.");
    String[] rightParts = right.split("\\.");
    int commonParts = 0;
    int limit = Math.min(leftParts.length, rightParts.length);
    while (commonParts < limit && leftParts[commonParts].equals(rightParts[commonParts])) {
      commonParts++;
    }
    return commonParts < 2
        ? null
        : String.join(".", Arrays.copyOf(leftParts, commonParts));
  }

  private static String packageOfMainClass(String configuredClass) {
    if (configuredClass == null) {
      return null;
    }
    String className = configuredClass.trim();
    if (className.isEmpty()
        || className.length() > 512
        || !JAVA_NAME.matcher(className).matches()
        || LAUNCHER_CLASS_PREFIXES.stream().anyMatch(className::startsWith)) {
      return null;
    }
    int packageSeparator = className.lastIndexOf('.');
    if (packageSeparator <= 0) {
      return null;
    }
    String packagePrefix = className.substring(0, packageSeparator);
    return isSafePackagePrefix(packagePrefix) ? packagePrefix : null;
  }

  private static boolean isSafePackagePrefix(String packagePrefix) {
    if (packagePrefix == null
        || packagePrefix.length() > 256
        || !JAVA_NAME.matcher(packagePrefix).matches()
        || packagePrefix.indexOf('.') < 1) {
      return false;
    }
    return FORBIDDEN_PACKAGE_PREFIXES.stream()
        .noneMatch(
            forbidden ->
                packagePrefix.equals(forbidden)
                    || packagePrefix.startsWith(forbidden + '.')
                    || forbidden.startsWith(packagePrefix + '.'));
  }

  private static String moduleMainClass(String command) {
    if (command == null || command.isBlank()) {
      return "";
    }
    int moduleSeparator = command.indexOf('/');
    return moduleSeparator >= 0 && moduleSeparator + 1 < command.length()
        ? command.substring(moduleSeparator + 1)
        : command;
  }

  private static boolean isJarName(String value) {
    return value != null && value.toLowerCase(Locale.ROOT).endsWith(".jar");
  }

  private static String firstCommandToken(String command) {
    if (command == null) {
      return "";
    }
    String value = command.trim();
    if (value.isEmpty()) {
      return "";
    }
    char quote = value.charAt(0);
    if (quote == '\'' || quote == '"') {
      int end = value.indexOf(quote, 1);
      return end < 0 ? "" : value.substring(1, end);
    }
    int end = 0;
    while (end < value.length() && !Character.isWhitespace(value.charAt(end))) {
      end++;
    }
    return value.substring(0, end);
  }

  static record Resolution(List<String> packagePrefixes, String source) {
    Resolution {
      packagePrefixes = List.copyOf(packagePrefixes);
    }

    private static Resolution none() {
      return new Resolution(List.of(), "none");
    }
  }
}

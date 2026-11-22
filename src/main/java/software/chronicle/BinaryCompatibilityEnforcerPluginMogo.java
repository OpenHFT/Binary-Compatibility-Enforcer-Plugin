package software.chronicle;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static java.lang.Double.parseDouble;
import static java.lang.String.format;

@Mojo(name = "enforcer", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class BinaryCompatibilityEnforcerPluginMogo extends AbstractMojo {
    private static final String OS = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_LINUX = OS.startsWith("linux");
    private static final boolean IS_MAC = OS.contains("mac");
    private static final boolean IS_WIN = OS.startsWith("win");

    public static final String REPORT = "Report: ";
    public static final String BINARY_COMPATIBILITY = "Binary compatibility: ";
    public static final String BAR = "\n------------------------------" +
            "------------------------------------------";
    private static final String BIN_SH = "/bin/sh";
    private static final String CMD_EXE = "C:\\WINDOWS\\system32\\cmd.exe";
    @Parameter(property = "binary.compatibility.required", defaultValue = "false")
    boolean required;

    @Component
    private ArtifactResolver artifactResolver;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    private static final String DEFAULT_EXPRESSION = "japi-compliance-checker %s -lib %s %s %s -report-path %s";

    @Parameter(property = "binary.compatibility.executable", defaultValue = "japi-compliance-checker")
    private String executable = "japi-compliance-checker";

    @Parameter(property = "binary.compatibility.timeoutSeconds", defaultValue = "120")
    private long timeoutSeconds = 120;

    @Parameter(defaultValue = DEFAULT_EXPRESSION, readonly = true, required = false)
    private String expression;

    @Parameter(defaultValue = "", required = false)
    private String referenceVersion;

    @Parameter(defaultValue = "", required = false)
    private String artifactsURI;

    @Parameter(defaultValue = "100.0", required = false)
    double binaryCompatibilityPercentageRequired;

    @Parameter(defaultValue = "target")
    private String reportLocation;

    @Parameter
    private ExtraOption[] extraOptions;

    public void execute() throws MojoExecutionException {

        if (System.getProperty("skip.binary") != null) {
            if (required) throw new MojoExecutionException("Required binary compatibility check cannot be skipped");
            getLog().info(format("%s\nBINARY COMPATIBILITY ENFORCER - %s%s - disabled", BAR, project.getArtifactId(), BAR));
            return;
        }

        getLog().info(format("%s\nBINARY COMPATIBILITY ENFORCER - %s%s", BAR, project.getArtifactId(), BAR));

        if ((IS_LINUX | IS_MAC) && !new File(BIN_SH).exists()) {
            unavailable(BIN_SH + " not found");
            return;
        } else if (IS_WIN && !new File(CMD_EXE).exists()) {
            unavailable(CMD_EXE + " not found");
            return;
        }

        getLog().info("Starting...");

        final String finalName = project.getBuild().getFinalName();
        if (!required && (finalName.endsWith(".0-SNAPSHOT") || finalName.endsWith(".0")
                || finalName.endsWith("ea0-SNAPSHOT") || finalName.endsWith("ea0")))
            return;

        try {
            checkJavaAPIComplianceCheckerInstalled();
        } catch (final MojoExecutionException e) {
            unavailable(e.getMessage());
            return;
        }

        final Build build = project.getBuild();
        if (build == null) {
            throw new MojoExecutionException("build not found");
        }

        final String groupId = project.getGroupId();
        final String artifactId = project.getArtifactId();
        final String outputDirectory = build.getOutputDirectory();

        getLog().info(referenceVersion);

        final String pathToJar1;
        try {
            pathToJar1 = referencePath(groupId, artifactId, outputDirectory);
        } catch (ArtifactResolverException ex) {
            throw new MojoExecutionException("Artifact to check against was not resolved", ex);
        }

        getLog().debug("pathToJar1=" + pathToJar1);
        final String directory = build.getDirectory();

        final String pathToJar2 = format("%s%s%s.jar", directory, File.separator, finalName);
        getLog().debug("pathToJar2=" + pathToJar2);

        checkBinaryCompatibility(pathToJar1, pathToJar2, artifactId);
    }

    private void unavailable(String reason) throws MojoExecutionException {
        String message = "Binary compatibility NOT CHECKED: " + reason;
        if (required) throw new MojoExecutionException(message);
        getLog().warn(message + "; set -Dbinary.compatibility.required=true for a required release check");
    }

    protected String referencePath(final String groupId,
                                 final String artifactId,
                                 final String outputDirectory) throws MojoExecutionException, ArtifactResolverException {
        String pathToJar1 = null;
        if (isEmpty(referenceVersion)) {

            String version = project.getVersion();
            int i = version.indexOf(".");
            if (i != -1) {

                referenceVersion = calculateDefaultRefVersion(version, i);
                getLog().info("setting referenceVersion=" + referenceVersion);
                if (referenceVersion == null)
                    throw new MojoExecutionException("Please set <referenceVersion> config");

                try {
                    pathToJar1 = downloadArtifact(groupId,
                            artifactId,
                            referenceVersion,
                            outputDirectory).getAbsolutePath();

                } catch (final Exception e) {
                    throw new MojoExecutionException(String.format("Please set <referenceVersion> config, " +
                            "can not download default version=%s of %s", referenceVersion, artifactId), e);
                }

            } else {

                pathToJar1 = downloadArtifact(groupId,
                        artifactId,
                        referenceVersion,
                        outputDirectory).getAbsolutePath();
            }

        } else
            pathToJar1 = downloadArtifact(groupId,
                    artifactId,
                    referenceVersion,
                    outputDirectory).getAbsolutePath();
        return pathToJar1;
    }

    static String calculateDefaultRefVersion(String version, int indexOfFirstDot) throws MojoExecutionException {

        boolean containsES = version.contains("ea");

        int indexOfSecondDelimitor = version.indexOf(containsES ? "ea" : ".", indexOfFirstDot + 1);
        if (indexOfSecondDelimitor != -1) {
            version = version.substring(0, indexOfSecondDelimitor);
            return containsES ? version + "ea0" : version + ".0";
        }

        throw new MojoExecutionException(String.format("Please set <referenceVersion> config, " +
                "can not download default version=%s", version));
    }

    private File downloadArtifact(final String group, final String artifactId, final String version, final String target) throws MojoExecutionException, ArtifactResolverException {

        DefaultArtifactCoordinate coordinate = new DefaultArtifactCoordinate();
        coordinate.setGroupId(group);
        coordinate.setArtifactId(artifactId);
        coordinate.setVersion(version);

        List<ArtifactRepository> repositories = new ArrayList<>();
        for (Object repository : project.getRemoteArtifactRepositories())
            repositories.add(ArtifactRepository.class.cast(repository));
        ArtifactResult res = artifactResolver.resolveArtifact(new DefaultProjectBuildingRequest(session.getProjectBuildingRequest())
                .setRemoteRepositories(repositories), coordinate);

        return res.getArtifact().getFile();
    }

    private void checkBinaryCompatibility(final String jar1, final String jar2, final String artifactName) throws MojoExecutionException {
        if (!Double.isFinite(binaryCompatibilityPercentageRequired)
                || binaryCompatibilityPercentageRequired < 0 || binaryCompatibilityPercentageRequired > 100)
            throw new MojoExecutionException("Compatibility threshold must be finite and between 0 and 100");
        String reportOutput = constructReportOutputPath(artifactName, referenceVersion, project.getVersion());
        File expectedReport = new File(reportOutput).getAbsoluteFile();
        List<String> command = new ArrayList<>();
        if (expression == null || DEFAULT_EXPRESSION.equals(expression)) {
            command.add(executable);
            for (ExtraOption option : Optional.ofNullable(extraOptions).orElse(new ExtraOption[0])) {
                command.add("-" + option.name);
                if (!isEmpty(option.value)) command.add(option.value);
            }
            command.addAll(Arrays.asList("-lib", artifactName, jar1, jar2, "-report-path", reportOutput));
        } else {
            // Retained for existing trusted shell configurations; ordinary paths use argv above.
            command.addAll(Arrays.asList(IS_WIN ? CMD_EXE : BIN_SH, IS_WIN ? "/C" : "-c",
                    format(expression, renderExtraOptions(extraOptions), shellQuote(artifactName),
                            shellQuote(jar1), shellQuote(jar2), shellQuote(reportOutput))));
        }
        try {
            Files.deleteIfExists(expectedReport.toPath());
            ProcessResult result = runCommand(command);
            // JAPICC uses 1 for detected binary OR source incompatibility, not a tool crash.
            if (result.exit != 0 && result.exit != 1)
                throw new MojoExecutionException("Compatibility checker exited " + result.exit + ": " + result.error);
            String percentage = null;
            String report = null;
            for (String line : result.output.split("\\R")) {
                if (line.startsWith(BINARY_COMPATIBILITY)) {
                    if (percentage != null || !line.endsWith("%"))
                        throw new MojoExecutionException("Malformed compatibility percentage: " + line);
                    percentage = line.substring(BINARY_COMPATIBILITY.length(), line.length() - 1).trim();
                }
                if (line.startsWith(REPORT)) report = line.substring(REPORT.length()).trim();
            }
            double value;
            try {
                value = percentage == null ? Double.NaN : parseDouble(percentage);
            } catch (NumberFormatException malformed) {
                throw new MojoExecutionException("Invalid compatibility percentage: " + percentage, malformed);
            }
            if (!Double.isFinite(value) || value < 0 || value > 100)
                throw new MojoExecutionException("Invalid compatibility percentage: " + percentage);
            if (report == null || !new File(report).getCanonicalFile().equals(expectedReport.getCanonicalFile())
                    || !expectedReport.isFile() || expectedReport.length() == 0)
                throw new MojoExecutionException("Compatibility checker did not produce the requested report");
            if (value < binaryCompatibilityPercentageRequired)
                throw new MojoExecutionException("BINARY COMPATIBILITY ENFORCER - FAILURE: " + value
                        + "% is below " + binaryCompatibilityPercentageRequired + "%; report " + reportUri(expectedReport));
            getLog().info("BINARY COMPATIBILITY ENFORCER - SUCCESSFUL: " + value + "%; report " + reportUri(expectedReport));
        } catch (IOException failure) {
            throw new MojoExecutionException("Compatibility checker failed", failure);
        }
    }

    String reportUri(File report) {
        String buildNumber = System.getProperty("teamcity.agent.dotnet.build_id");
        String buildType = System.getProperty("teamcity.buildType.id");
        java.net.URI local = report.toURI();
        java.net.URI relative = project.getBasedir().toURI().relativize(local);
        if (buildNumber == null || isEmpty(buildType) || isEmpty(artifactsURI) || relative.isAbsolute())
            return local.toASCIIString();
        return String.format("%s/%s/%s:id/%s", artifactsURI, buildType, buildNumber, relative.toASCIIString());
    }

    private static String shellQuote(String value) throws MojoExecutionException {
        if (IS_WIN) {
            if (value.indexOf('%') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0)
                throw new MojoExecutionException("Use the executable/argv configuration for this Windows path");
            return "\"" + value + "\"";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private ProcessResult runCommand(List<String> command) throws IOException, MojoExecutionException {
        if (timeoutSeconds <= 0) throw new MojoExecutionException("Checker timeout must be positive");
        Path output = Files.createTempFile("japicc-output-", ".log");
        Path error = Files.createTempFile("japicc-error-", ".log");
        Process process = null;
        Throwable primaryFailure = null;
        try {
            process = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
                throw new MojoExecutionException("Compatibility checker timed out after " + timeoutSeconds + " seconds");
            if (Files.size(output) > 1024 * 1024 || Files.size(error) > 1024 * 1024)
                throw new MojoExecutionException("Compatibility checker output exceeds 1 MiB");
            return new ProcessResult(process.exitValue(), new String(Files.readAllBytes(output), StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(error), StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            MojoExecutionException failure = new MojoExecutionException("Interrupted waiting for compatibility checker", interrupted);
            primaryFailure = failure;
            throw failure;
        } catch (IOException | MojoExecutionException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            shutdown(process);
            IOException cleanupFailure = null;
            for (Path path : Arrays.asList(output, error)) {
                try {
                    deleteTemporaryFile(path);
                } catch (IOException failure) {
                    if (cleanupFailure == null)
                        cleanupFailure = failure;
                    else
                        cleanupFailure.addSuppressed(failure);
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null)
                    primaryFailure.addSuppressed(cleanupFailure);
                else
                    throw cleanupFailure;
            }
        }
    }

    private void deleteTemporaryFile(Path path) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        for (;;) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (FileSystemException failure) {
                // Windows may briefly retain redirected handles after process exit is reported.
                if (!IS_WIN || System.nanoTime() >= deadline)
                    throw failure;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                    throw failure;
                }
            }
        }
    }

    private static final class ProcessResult {
        final int exit;
        final String output;
        final String error;
        ProcessResult(int exit, String output, String error) {
            this.exit = exit;
            this.output = output;
            this.error = error;
        }
    }

    private boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    public void checkJavaAPIComplianceCheckerInstalled() throws MojoExecutionException {
        try {
            ProcessResult result = runCommand(Arrays.asList(executable, "--version"));
            if (result.exit != 0 || !result.output.contains("Java API Compliance Checker"))
                throw new MojoExecutionException("Unable to load Java API Compliance Checker: " + result.error);
            getLog().info("Java API Compliance Checker - correctly installed");
        } catch (IOException failure) {
            throw new MojoExecutionException("Unable to load Java API Compliance Checker", failure);
        }
    }

    private String constructReportOutputPath(String artifactName, String oldVersion, String newVersion) {
        return project.getBasedir().toPath().resolve(reportLocation).resolve("compat_reports")
                .resolve(artifactName).resolve(oldVersion + "_to_" + newVersion).resolve("compat_report.html")
                .toAbsolutePath().toString();
    }

    private void shutdown(final Process process) {
        if (process == null || !process.isAlive()) return;
        // Keep Java 8 loading compatibility; newer JVMs can also stop descendants.
        try {
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            Object handle = Process.class.getMethod("toHandle").invoke(process);
            List<CompletableFuture<?>> exits = new ArrayList<>();
            try (java.util.stream.Stream<?> children = (java.util.stream.Stream<?>) handleType.getMethod("descendants").invoke(handle)) {
                children.forEach(child -> {
                    try {
                        handleType.getMethod("destroyForcibly").invoke(child);
                        exits.add((CompletableFuture<?>) handleType.getMethod("onExit").invoke(child));
                    }
                    catch (ReflectiveOperationException failure) { getLog().debug(failure); }
                });
            }
            // Windows retains redirected log handles until the child processes have exited.
            for (CompletableFuture<?> exit : exits) {
                try {
                    exit.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException | TimeoutException failure) {
                    getLog().warn("Checker descendant did not terminate", failure);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (ReflectiveOperationException unavailableOnJava8) {
            if (IS_WIN)
                shutdownWindowsTree(process);
            else
                getLog().debug("Descendant cleanup unavailable on this JVM", unavailableOnJava8);
        }
        process.destroyForcibly();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) getLog().warn("Checker did not terminate");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void shutdownWindowsTree(Process process) {
        Process killer = null;
        try {
            long pid = WindowsProcessId.get(process);
            killer = new ProcessBuilder("taskkill", "/PID", Long.toString(pid), "/T", "/F")
                    .redirectOutput(new File("NUL")).redirectError(new File("NUL")).start();
            if (!killer.waitFor(5, TimeUnit.SECONDS))
                getLog().warn("Timed out stopping checker process tree " + pid);
            else if (killer.exitValue() != 0 && process.isAlive())
                getLog().warn("Could not stop checker process tree " + pid);
        } catch (IOException | ReflectiveOperationException failure) {
            getLog().warn("Could not stop Java 8 checker descendants", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (killer != null && killer.isAlive())
                killer.destroyForcibly();
        }
    }

    static String renderExtraOptions(ExtraOption[] extraOptions) {
        return Arrays.stream(Optional.ofNullable(extraOptions).orElse(new ExtraOption[0]))
                .map(Object::toString)
                .collect(Collectors.joining(" "));
    }

}

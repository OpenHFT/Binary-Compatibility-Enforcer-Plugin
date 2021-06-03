package software.chronicle;

import org.apache.maven.model.Build;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import static java.lang.Double.parseDouble;

import static java.lang.String.format;

// JS marked this as threadsafe - we download artefacts with mvn dependency:get and this I believe is
// safe for multiple processes and/or threads. Marking this threadSafe gets rid of a build warning
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
    private static final boolean DISABLED = System.getProperty("skip.binary") != null;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "japi-compliance-checker -lib %s %s %s -report-path %s", readonly = true, required = false)
    private String expression;

    @Parameter(defaultValue = "", required = false)
    private String referenceVersion;

    @Parameter(defaultValue = "", required = false)
    private String artifactsURI;

    @Parameter(defaultValue = "100.0", required = false)
    double binaryCompatibilityPercentageRequired;

    @Parameter(defaultValue = "target")
    private String reportLocation;


    public void execute() throws MojoExecutionException {

        if (DISABLED) {
            getLog().info(format("%s\nBINARY COMPATIBILITY ENFORCER - %s%s - disabled", BAR, project.getArtifactId(), BAR));
            return;
        }

        getLog().info(format("%s\nBINARY COMPATIBILITY ENFORCER - %s%s", BAR, project.getArtifactId(), BAR));


        if ((IS_LINUX | IS_MAC) && !new File(BIN_SH).exists()) {
            getLog().info(BIN_SH + " not found");
            return;
        } else if (IS_WIN && !new File(CMD_EXE).exists()) {
            getLog().info(CMD_EXE + " not found");
            return;
        }

        getLog().info("Starting...");

        final String finalName = project.getBuild().getFinalName();
        if (finalName.endsWith(".0-SNAPSHOT") || finalName.endsWith(".0")
                || finalName.endsWith("ea0-SNAPSHOT") || finalName.endsWith("ea0"))
            return;

        try {
            checkJavaAPIComplianceCheckerInstalled();
        } catch (final MojoExecutionException e) {
            getLog().warn(e.getMessage(), e);
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

        final String pathToJar1 = referencePath(groupId, artifactId, outputDirectory);

        getLog().debug("pathToJar1=" + pathToJar1);

        final String directory = build.getDirectory();


        final String pathToJar2 = format("%s%s%s.jar", directory, File.separator, finalName);

        getLog().debug("pathToJar2=" + pathToJar2);


        checkBinaryCompatibility(pathToJar1, pathToJar2, artifactId);
    }

    private String referencePath(final String groupId,
                                 final String artifactId,
                                 final String outputDirectory) throws MojoExecutionException {
        String pathToJar1 = null;
        if (isEmpty(referenceVersion)) {

            String version = project.getVersion();
            int i = version.indexOf(".");
            if (i != -1) {


                referenceVersion = calculateDefaultRefVersion(version, i);
                getLog().info("setting referenceVersion=" + referenceVersion);
                if (pathToJar1 == null)
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


    private File downloadArtifact(final String group, final String artifactId, final String version, final String target) throws MojoExecutionException {
        final File tempFile = new File(target, artifactId + "-" + version + ".jar");
        tempFile.delete();
        //   final String command = format("mvn dependency:get -Dartifact=%s:%s:%s:jar -Dtransitive=false -Ddest=%s -DremoteRepositories=chronicle-enterprise-release::::https://nexus.chronicle.software/content/repositories/releases ", group, artifactId, version, tempFile);
        final String command = format("mvn dependency:get -Dartifact=%s:%s:%s:jar -Dtransitive=false -Ddest=%s", group, artifactId, version, tempFile);

        getLog().info(command);

        BufferedReader stdError = null;
        Process p = null;
        try {

            p = new ProcessBuilder(IS_WIN ? CMD_EXE : BIN_SH, IS_WIN ? "/C" : "-c", command).start();

            final BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(p.getInputStream()));

            stdError = new BufferedReader(new InputStreamReader(p.getErrorStream()));

            stdInput.readLine();

            for (; ; ) {
                final String s1 = stdInput.readLine();
                if (s1 == null) {
                    dumpErrorToConsole(stdError);
                    throw new MojoExecutionException("unable to download using command=" + command);
                }

                if (s1.contains("BUILD SUCCESS")) {
                    break;
                }

                getLog().debug(s1);
            }
            return tempFile;
        } catch (final Exception e) {
            dumpErrorToConsole(stdError);
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {
            shutdown(p);
        }
    }

    private void checkBinaryCompatibility(final String jar1, final String jar2, final String artifactName) throws MojoExecutionException {

        BufferedReader stdError = null;
        Process p = null;
        try {
            final String reportOutput = constructReportOutputPath(artifactName, referenceVersion, project.getVersion());
            final String command = format(expression, artifactName, jar1, jar2, reportOutput);

            getLog().info(command);
            p = new ProcessBuilder(IS_WIN ? CMD_EXE : BIN_SH, IS_WIN ? "/C" : "-c", command).start();

            final BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(p.getInputStream()));

            stdError = new BufferedReader(new
                    InputStreamReader(p.getErrorStream()));
            String binaryCompatibility = "";
            for (; ; ) {
                final String s1 = stdInput.readLine();
                if (s1 == null) {
                    dumpErrorToConsole(stdError);
                    throw new MojoExecutionException("failed to to successfully execute, command=" + command);
                }

                if (s1.startsWith("Binary compatibility: ")) {
                    assert s1.endsWith("%");
                    binaryCompatibility = s1.substring(BINARY_COMPATIBILITY.length(), s1.length() - 1);
                }

                if (!s1.startsWith(REPORT)) {
                    getLog().debug(s1);
                    continue;
                }

                final String report = s1.substring(REPORT.length());


                if (parseDouble(binaryCompatibility) < binaryCompatibilityPercentageRequired) {

                    final String buildNumber = System.getProperty("teamcity.agent.dotnet.build_id");
                    final String buildTypeId = System.getProperty("teamcity.buildType.id");

                    final String uri = buildNumber == null || isEmpty(artifactsURI) || isEmpty(buildTypeId)
                            ? "file://" + new File(report).getAbsolutePath()
                            : String.format("%s/%s/%s/%s", artifactsURI, buildTypeId, buildNumber + ":id", report);

                    throw new MojoExecutionException(format("\n%s\nBINARY COMPATIBILITY ENFORCER - FAILURE - %s: %s%%  binary compatibility\n" +
                                    "Your changes are only %s%% binary compatibility, this enforcer plugin requires at least %s%% binary compatibility,\n " +
                                    "between %s and %s\nsee report \"%s\"%s",
                            BAR,
                            artifactName,
                            binaryCompatibility,
                            binaryCompatibility,
                            binaryCompatibilityPercentageRequired,
                            jar1,
                            jar2,
                            uri,
                            BAR));

                } else
                    getLog().info(format("Whilst checking against %s", jar1));

                getLog().info(format("%s\nBINARY COMPATIBILITY ENFORCER - SUCCESSFUL - %s%s", BAR, artifactName, BAR));
                return;

            }

        } catch (final IOException e) {
            dumpErrorToConsole(stdError);
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {

            shutdown(p);

        }
    }

    private boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private void dumpErrorToConsole(final BufferedReader std) throws MojoExecutionException {
        if (std == null)
            return;
        try {
            for (String s1 = std.readLine(); s1 != null; s1 = std.readLine()) {
                getLog().warn(s1);
            }
        } catch (final IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }


    public void checkJavaAPIComplianceCheckerInstalled() throws MojoExecutionException {
        Process p = null;
        BufferedReader stdError = null;
        try {

            p = new ProcessBuilder(IS_WIN ? CMD_EXE : BIN_SH, IS_WIN ? "/C" : "-c", "japi-compliance-checker -l").start();

            final BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(p.getInputStream()));

            stdError = new BufferedReader(new
                    InputStreamReader(p.getErrorStream()));
            stdInput.readLine();
            final String firstLine = stdInput.readLine();

            if (firstLine == null || !firstLine.startsWith("Java API Compliance Checker")) {
                throw new MojoExecutionException("Unable to load Java API Compliance Checker, please add it your $PATH and check its permissions.");
            }

            getLog().info("Java API Compliance Checker - correctly installed");

        } catch (final IOException | MojoExecutionException e) {
            dumpErrorToConsole(stdError);
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {
            shutdown(p);
        }
    }

    private String constructReportOutputPath(String artifactName, String oldVersion, String newVersion) {
        return project.getBasedir().getAbsolutePath() + format("/%s/compat_reports/%s/%s_to_%s/compat_report.html", reportLocation, artifactName, oldVersion, newVersion);
    }

    private void shutdown(final Process p) {
        if (p != null) {
            p.destroy();
            try {
                p.waitFor(1, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                p.destroyForcibly();
            }
        }
    }

}

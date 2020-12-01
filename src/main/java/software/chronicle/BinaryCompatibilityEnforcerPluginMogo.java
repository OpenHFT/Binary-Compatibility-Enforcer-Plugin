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
import static java.lang.Runtime.getRuntime;
import static java.lang.String.format;

@Mojo(name = "enforcer", defaultPhase = LifecyclePhase.VERIFY)
public class BinaryCompatibilityEnforcerPluginMogo extends AbstractMojo {

    public static final String REPORT = "Report: ";
    public static final String BINARY_COMPATIBILITY = "Binary compatibility: ";
    public static final String BAR = "\n------------------------------" +
            "------------------------------------------";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "japi-compliance-checker -lib %s %s %s", readonly = true, required = false)
    private String expression;

    @Parameter(defaultValue = "", required = false)
    private String referenceVersion;

    @Parameter(defaultValue = "https://teamcity.chronicle.software/repository/download", required = false)
    private String artifactsURL;

    @Parameter(defaultValue = "100.0", required = false)
    double binaryCompatibilityPercentageRequired;


    public void execute() throws MojoExecutionException {

        getLog().info(format("%s\nBINARY COMPATIBILITY ENFORCER - %s%s", BAR, project.getArtifactId(), BAR));

        getLog().info("Starting...");

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

        final String finalName = project.getBuild().getFinalName();
        final String pathToJar2 = format("%s%s%s.jar", directory, File.separator, finalName);

        getLog().debug("pathToJar2=" + pathToJar2);

        if (finalName.endsWith("0-SNAPSHOT.jar") || finalName.endsWith("0.jar"))
            return;

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
                i = version.indexOf(".", i + 1);
                if (i != -1) {
                    version = version.substring(0, i);
                    referenceVersion = version + ".0";
                    getLog().info("setting referenceVersion=" + referenceVersion);
                    try {
                        pathToJar1 = downloadArtifact(groupId,
                                artifactId,
                                referenceVersion,
                                outputDirectory).getAbsolutePath();
                    } catch (final Exception e) {
                        throw new MojoExecutionException(String.format("Please set <referenceVersion> config, " +
                                "can not download default version=%s of %s", referenceVersion, artifactId), e);
                    }

                }

                if (pathToJar1 == null)
                    throw new MojoExecutionException("Please set <referenceVersion> config");
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


    private File downloadArtifact(final String group, final String artifactId, final String version, final String target) throws MojoExecutionException {
        final File tempFile = new File(target, artifactId + "-" + version + ".jar");
        tempFile.delete();
        //   final String command = format("mvn dependency:get -Dartifact=%s:%s:%s:jar -Dtransitive=false -Ddest=%s -DremoteRepositories=chronicle-enterprise-release::::https://nexus.chronicle.software/content/repositories/releases ", group, artifactId, version, tempFile);
        final String command = format("mvn dependency:get -Dartifact=%s:%s:%s:jar -Dtransitive=false -Ddest=%s", group, artifactId, version, tempFile);

        getLog().info(command);

        BufferedReader stdError = null;
        Process p = null;
        try {
            p = getRuntime().exec(command);

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


 /*     [INFO] getProperties={java.vendor=Oracle Corporation, teamcity.agent.dotnet.build_id=501262, sun.java.launcher=SUN_STANDARD, teamcity.buildConfName=Snapshot - Linux x86, sun.management.compiler=HotSpot 64-Bit Tiered Compilers, agent.home.dir=/home/teamcity/agents/dev12b, os.name=Linux, teamcity.build.properties.file=/home/teamcity/agents/dev12b/temp/buildTmp/teamcity.build9092526532797180046.properties, sun.boot.class.path=/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.252.b09-2.el7_8.x86_64/jre/lib/resources.jar:/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.252.b09-2.el7_8.x86_64/jre/lib/rt.jar:/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.252.b09-2.el7_8.x86_64/jre/lib/sunrsasign.jar:/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.252.b09-2.el7_8.x86_64/jre/lib/jsse.jar:/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.252.b09-2.el7_8.x86_64/jre/lib/jce.jar:/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.252.b09-2.el7_8.x86_64/jre/lib/charsets.jar:/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.252.b09-2.el7_8.x86_64/jre/lib/jfr.jar:/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.252.b09-2.el7_8.x86_64/jre/classes, build.number=4204, java.vm.specification.vendor=Oracle Corporation, agent.name=Dev 12-b, java.runtime.version=1.8.0_252-b09, build.vcs.number.OpenHFT_ChronicleNetwork_ChronicleNetwork=e15b1086a0732010eb21b48de82b2aea0f451fe7, teamcity.auth.password=*******, teamcity.runner.properties.file=/home/teamcity/agents/dev12b/temp/buildTmp/teamcity.runner3239907389094019348.properties, agent.ownPort=9704, user.name=teamcity, guice.disable.misplaced.annotation.check=true, teamcity.build.workingDir=/home/teamcity/agents/dev12b/work/b121a771cb6f63e3, maven.repo.local=/home/teamcity/.m2/repository, teamcity.version=2020.2 (build 85487), user.language=en, teamcity.build.id=501262, sun.boot.library.path=/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.252.b09-2.el7_8.x86_64/jre/lib/amd64, classworlds.conf=/home/teamcity/agents/dev12b/temp/buildTmp/teamcity.m2.conf, teamcity.maven.watcher.home=/home/teamcity/agents/dev12b/plugins/mavenPlugin/maven-watcher-jdk16, java.version=1.8.0_252, user.timezone=Europe/London, teamcity.buildType.id=OpenHFT_ChronicleNetwork_Snapshot, sun.arch.data.model=64, java.endorsed.dirs=/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.252.b09-2.el7_8.x86_64/jre/lib/endorsed, sun.cpu.isalist=, sun.jnu.encoding=UTF-8, file.encoding.pkg=sun.io, maven.conf=/opt/maven/conf, file.separator=/, java.specification.name=Java Platform API Specification, java.class.version=52.0, user.country=GB, securerandom.source=file:/dev/./urandom, java.home=/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.252.b09-2.el7_8.x86_64/jre, java.vm.info=mixed mode, teamcity.auth.userId=TeamCityBuildId=501262, os.version=3.10.0-1062.18.1.el7.x86_64, path.separator=:, java.vm.version=25.252-b09, java.awt.printerjob=sun.print.PSPrinterJob, sun.io.unicode.encoding=UnicodeLittle, awt.toolkit=sun.awt.X11.XToolkit, teamcity.agent.cpuBenchmark=554, user.home=/home/teamcity, teamcity.configuration.properties.file=/home/teamcity/agents/dev12b/temp/buildTmp/teamcity.config8448616283397568451.properties, java.specification.vendor=Oracle Corporation, teamcity.projectName=Chronicle Network, java.library.path=/usr/java/packages/lib/amd64:/usr/lib64:/lib64:/lib:/usr/lib, java.vendor.url=http://java.oracle.com/, java.vm.vendor=Oracle Corporation, java.runtime.name=OpenJDK Runtime Environment, maven.home=/opt/maven, sun.java.command=org.codehaus.plexus.classworlds.launcher.Launcher -f /home/teamcity/agents/dev12b/work/b121a771cb6f63e3/pom.xml -B -Djava.io.tmpdir=/home/teamcity/agents/dev12b/work/b121a771cb6f63e3 clean test deploy -U, java.class.path=/opt/maven/boot/plexus-classworlds-2.5.2.jar:, java.vm.specification.name=Java Virtual Machine Specification, java.vm.specification.version=1.8, build.vcs.number.1=e15b1086a0732010eb21b48de82b2aea0f451fe7, agent.work.dir=/home/teamcity/agents/dev12b/work, sun.cpu.endian=little, sun.os.patch.level=unknown, java.io.tmpdir=/home/teamcity/agents/dev12b/work/b121a771cb6f63e3, java.vendor.url.bug=http://bugreport.sun.com/bugreport/, maven.multiModuleProjectDirectory=/home/teamcity/agents/dev12b/work/b121a771cb6f63e3, build.vcs.number=e15b1086a0732010eb21b48de82b2aea0f451fe7, os.arch=amd64, java.awt.graphicsenv=sun.awt.X11GraphicsEnvironment, teamcity.build.checkoutDir=/home/teamcity/agents/dev12b/work/b121a771cb6f63e3, java.ext.dirs=/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.252.b09-2.el7_8.x86_64/jre/lib/ext:/usr/java/packages/lib/ext, user.dir=/home/teamcity/agents/dev12b/work/b121a771cb6f63e3, teamcity.build.changedFiles.file=/home/teamcity/agents/dev12b/temp/buildTmp/changedFiles6698577389354518650.txt, line.separator=
        17:09:33
                , java.vm.name=OpenJDK 64-Bit Server VM, teamcity.tests.recentlyFailedTests.file=/home/teamcity/agents/dev12b/temp/buildTmp/testsToRunFirst256081201219057097.txt, file.encoding=UTF-8, teamcity.agent.dotnet.agent_url=http://localhost:9704/RPC2, java.specification.version=1.8, com.jetbrains.maven.watcher.report.file=/home/teamcity/agents/dev12b/temp/buildTmp/maven-build-info.xml, teamcity.build.tempDir=/home/teamcity/agents/dev12b/temp/buildTmp}
        17:09:33
                [INFO] japi-compliance-checker -lib chronicle-network /home/teamcity/agents/dev12b/work/b121a771cb6f63e3/target/classes/chronicle-network-1.7.12.jar /home/teamcity/agents/dev12b/work/b121a771cb6f63e3/target/chronicle-network-2.20.106-SNAPSHOT.jar
        17:09:36
*/


    private void checkBinaryCompatibility(final String jar1, final String jar2, final String artifactName) throws MojoExecutionException {
        getLog().info("getProperties=" + System.getProperties());

        BufferedReader stdError = null;
        Process p = null;
        try {
            final String command = format(expression, artifactName, jar1, jar2);

            getLog().info(command);
            p = new ProcessBuilder("/bin/sh", "-c", command).start();

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

                    final String uri = buildNumber == null || isEmpty(artifactsURL) || isEmpty(buildTypeId)
                            ? "file://" + new File(report).getAbsolutePath()
                            : String.format("%s/%s/%s/%s", artifactsURL, buildTypeId, buildNumber + ":id", report);

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

            p = new ProcessBuilder("/bin/sh", "-c", "japi-compliance-checker -l").start();
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

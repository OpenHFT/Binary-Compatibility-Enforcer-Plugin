package software.chronicle;

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

public class RequiredCompatibilityCheckTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private BinaryCompatibilityEnforcerPluginMogo mojo(boolean required, boolean available, int percent) throws Exception {
        BinaryCompatibilityEnforcerPluginMogo mojo = new BinaryCompatibilityEnforcerPluginMogo() {
            @Override public void checkJavaAPIComplianceCheckerInstalled() throws MojoExecutionException {
                if (!available) throw new MojoExecutionException("checker deliberately absent");
            }
            @Override protected String referencePath(String group, String artifact, String output) { return "old.jar"; }
        };
        mojo.required = required;
        mojo.binaryCompatibilityPercentageRequired = 100;
        MavenProject project = new MavenProject();
        project.setArtifactId("fixture"); project.setGroupId("test"); project.setVersion("1.2.3");
        File directory = temporary.newFolder("checker path " + System.nanoTime());
        project.setFile(new File(directory, "pom.xml"));
        new File(directory, "target/compat_reports/fixture/1.2.0_to_1.2.3").mkdirs();
        Build build = new Build(); build.setFinalName("fixture-1.2.3"); build.setDirectory("target"); project.setBuild(build);
        set(mojo, "project", project);
        set(mojo, "referenceVersion", "1.2.0");
        set(mojo, "reportLocation", "target");
        String separator = System.getProperty("os.name").startsWith("Windows") ? "&" : ";";
        set(mojo, "expression", "echo Binary compatibility: " + percent + "%%" + separator
                + "echo Report: %5$s" + separator + "echo report > %5$s");
        return mojo;
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = BinaryCompatibilityEnforcerPluginMogo.class.getDeclaredField(name);
        field.setAccessible(true); field.set(object, value);
    }
    @Test public void optionalMissingCheckerDoesNotClaimSuccess() throws Exception { mojo(false, false, 100).execute(); }
    @Test public void requiredMissingCheckerFails() throws Exception {
        try { mojo(true, false, 100).execute(); fail("Missing checker must fail"); }
        catch (MojoExecutionException expected) { assertTrue(expected.getMessage().contains("NOT CHECKED")); }
    }
    @Test public void requiredCannotBeSkipped() throws Exception {
        String old = System.getProperty("skip.binary");
        try {
            System.setProperty("skip.binary", "true");
            try { mojo(true, true, 100).execute(); fail("Required check must not be skipped"); }
            catch (MojoExecutionException expected) { assertTrue(expected.getMessage().contains("cannot be skipped")); }
        } finally { if (old == null) System.clearProperty("skip.binary"); else System.setProperty("skip.binary", old); }
    }
    @Test public void compatibleCheckerReportPasses() throws Exception { mojo(true, true, 100).execute(); }
    @Test public void incompatibleCheckerReportFails() throws Exception {
        try { mojo(true, true, 50).execute(); fail("Incompatible report must fail"); }
        catch (MojoExecutionException expected) { assertTrue(expected.getMessage().contains("FAILURE")); }
    }
    @Test public void malformedPercentagesFailClosed() throws Exception {
        for (String percent : new String[]{"NaN", "Infinity", "-1", "101", "not-a-number"}) {
            BinaryCompatibilityEnforcerPluginMogo mojo = mojo(true, true, 100);
            set(mojo, "expression", "echo Binary compatibility: " + percent + "%%");
            try { mojo.execute(); fail("Accepted " + percent); }
            catch (MojoExecutionException expected) { assertTrue(expected.getMessage().contains("percentage")); }
        }
    }
    @Test public void successfulLookingOutputThenExitSevenFails() throws Exception {
        BinaryCompatibilityEnforcerPluginMogo mojo = mojo(true, true, 100);
        String ending = System.getProperty("os.name").startsWith("Windows") ? "&exit /b 7" : ";exit 7";
        String separator = System.getProperty("os.name").startsWith("Windows") ? "&" : ";";
        set(mojo, "expression", "echo Binary compatibility: 100%%" + separator
                + "echo Report: %5$s" + separator + "echo report > %5$s" + ending);
        try { mojo.execute(); fail("Checker crash must fail"); }
        catch (MojoExecutionException expected) { assertTrue(expected.getMessage().contains("exited 7")); }
    }
    @Test public void missingReportFails() throws Exception {
        BinaryCompatibilityEnforcerPluginMogo mojo = mojo(true, true, 100);
        String separator = System.getProperty("os.name").startsWith("Windows") ? "&" : ";";
        set(mojo, "expression", "echo Binary compatibility: 100%%" + separator + "echo Report: %5$s");
        try { mojo.execute(); fail("Missing report must fail"); }
        catch (MojoExecutionException expected) { assertTrue(expected.getMessage().contains("requested report")); }
    }
    @Test public void hungCheckerIsBounded() throws Exception {
        BinaryCompatibilityEnforcerPluginMogo mojo = mojo(true, true, 100);
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        set(mojo, "expression", "\"" + java + "\" -cp \"" + System.getProperty("java.class.path")
                + "\" " + (System.getProperty("os.name").startsWith("Windows") ? "\"" + SleepingChecker.class.getName() + "\"" : "'" + SleepingChecker.class.getName() + "'"));
        set(mojo, "timeoutSeconds", 1L);
        long start = System.nanoTime();
        try { mojo.execute(); fail("Hung checker must fail"); }
        catch (MojoExecutionException expected) { assertTrue(expected.getMessage().contains("timed out")); }
        assertTrue("Timeout did not bound completion", System.nanoTime() - start < 10_000_000_000L);
    }
    @Test public void retainsTeamCityReportLinks() throws Exception {
        BinaryCompatibilityEnforcerPluginMogo mojo = mojo(true, true, 100);
        Field field = BinaryCompatibilityEnforcerPluginMogo.class.getDeclaredField("project");
        field.setAccessible(true);
        File directory = ((MavenProject) field.get(mojo)).getBasedir();
        File report = new File(directory, "target/report with spaces.html");
        String oldBuild = System.getProperty("teamcity.agent.dotnet.build_id");
        String oldType = System.getProperty("teamcity.buildType.id");
        try {
            System.setProperty("teamcity.agent.dotnet.build_id", "123");
            System.setProperty("teamcity.buildType.id", "fixture");
            set(mojo, "artifactsURI", "https://teamcity.invalid/repository/download");
            assertEquals("https://teamcity.invalid/repository/download/fixture/123:id/target/report%20with%20spaces.html",
                    mojo.reportUri(report));
        } finally {
            if (oldBuild == null) System.clearProperty("teamcity.agent.dotnet.build_id");
            else System.setProperty("teamcity.agent.dotnet.build_id", oldBuild);
            if (oldType == null) System.clearProperty("teamcity.buildType.id");
            else System.setProperty("teamcity.buildType.id", oldType);
        }
    }

    public static class SleepingChecker {
        public static void main(String[] args) throws InterruptedException { Thread.sleep(60_000); }
    }

}

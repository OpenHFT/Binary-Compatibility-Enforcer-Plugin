package software.chronicle;

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.Test;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

public class RequiredCompatibilityCheckTest {
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
        project.setFile(new java.io.File("pom.xml").getAbsoluteFile());
        Build build = new Build(); build.setFinalName("fixture-1.2.3"); build.setDirectory("target"); project.setBuild(build);
        set(mojo, "project", project);
        set(mojo, "referenceVersion", "1.2.0");
        set(mojo, "reportLocation", "target");
        String separator = System.getProperty("os.name").startsWith("Windows") ? "&" : ";";
        set(mojo, "expression", "echo Binary compatibility: " + percent + "%%" + separator + "echo Report: report.html");
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
}

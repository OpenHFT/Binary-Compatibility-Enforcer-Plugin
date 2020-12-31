package software.chronicle;


import org.apache.maven.plugin.MojoExecutionException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static software.chronicle.BinaryCompatibilityEnforcerPluginMogo.calculateDefaultRefVersion;

public class BinaryCompatibilityEnforcerPluginMogoTest {

    @Test
    public void test() throws MojoExecutionException {
        assertEquals("1.2.0", test("1.2.3"));
        assertEquals("1.2.0", test("1.2.3-SNAPSHOT"));
        assertEquals("1.2.0", test("1.2.99-SNAPSHOT"));
        assertEquals("1.2.0", test("1.2.0-SNAPSHOT"));
        assertEquals("1.3.0", test("1.3.0-SNAPSHOT"));

        assertEquals("1.2ea0", test("1.2ea3"));
        assertEquals("1.2ea0", test("1.2ea4-SNAPSHOT"));

    }

    private String test(String version) throws MojoExecutionException {
        return calculateDefaultRefVersion(version, version.indexOf("."));
    }
}
package software.chronicle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

/** Opt in with -Dchecker.test.executable=/usr/bin/japi-compliance-checker. */
public class RealCheckerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void compatibleJarsSurviveJvmOptionsBanner() throws Exception {
        compare(false);
    }

    @Test
    public void removedMethodStillFailsCompatibility() throws Exception {
        compare(true);
    }

    private void compare(boolean incompatible) throws Exception {
        String executable = System.getProperty("checker.test.executable");
        assumeNotNull(executable);
        File oldJar = jar("old", "public int value() { return 1; }");
        File newJar = jar("new", incompatible ? "" : "public int value() { return 1; }");
        File report = new File(temporary.getRoot(), "report.html");
        File javaHome = new File(System.getProperty("java.home"));
        if ("jre".equals(javaHome.getName())) javaHome = javaHome.getParentFile();
        ProcessBuilder builder = CheckerProcess.builder(Arrays.asList(executable,
                "-jdk-path", javaHome.getAbsolutePath(), "-lib", "example", "-v1", "1", "-v2", "2",
                oldJar.getAbsolutePath(), newJar.getAbsolutePath(), "-report-path", report.getAbsolutePath()),
                System.getProperty("os.name").startsWith("Windows"));
        builder.environment().put("_JAVA_OPTIONS", "-Xms64m -Xmx512m -Dchecker.marker=retained");
        File output = temporary.newFile();
        int exit = CheckerProcessTest.run(builder, output);
        String text = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        assertEquals(text, incompatible ? 1 : 0, exit);
        assertTrue(text, report.isFile() && report.length() > 0);
        assertTrue(text, text.contains("Binary compatibility: "));
        if (!incompatible) assertTrue(text, text.contains("Binary compatibility: 100%"));
    }

    private File jar(String name, String method) throws Exception {
        File directory = temporary.newFolder(name);
        File source = new File(directory, "Example.java");
        Files.write(source.toPath(), ("public class Example { " + method + " }").getBytes(StandardCharsets.UTF_8));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-source", "8", "-target", "8", "-d", directory.getPath(), source.getPath()));
        File jar = new File(directory, "example.jar");
        try (JarOutputStream stream = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            stream.putNextEntry(new JarEntry("Example.class"));
            Files.copy(new File(directory, "Example.class").toPath(), stream);
            stream.closeEntry();
        }
        return jar;
    }
}

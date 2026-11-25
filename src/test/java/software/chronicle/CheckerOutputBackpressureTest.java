package software.chronicle;

import org.apache.maven.project.MavenProject;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.Assert.*;

public class CheckerOutputBackpressureTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 20000)
    public void stderrBurstDoesNotBlockAValidCompatibilityReport() throws Exception {
        BinaryCompatibilityEnforcerPluginMogo mojo = new BinaryCompatibilityEnforcerPluginMogo();
        mojo.setLog(new SystemStreamLog() {
            @Override public void warn(CharSequence message) { }
        });
        MavenProject project = new MavenProject();
        project.setVersion("1.2.3");
        project.setFile(new File(temporary.getRoot(), "pom.xml"));
        Path report = temporary.getRoot().toPath().resolve("target/compat_reports/fixture/1.2.0_to_1.2.3/compat_report.html");
        set(mojo, "project", project);
        set(mojo, "referenceVersion", "1.2.0");
        set(mojo, "reportLocation", "target");
        mojo.binaryCompatibilityPercentageRequired = 100;
        String java = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
        // The fixture needs only test classes; Maven's classpath can exceed cmd.exe's command limit.
        String classPath = new File(NoisyChecker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        String command = quote(java) + " -cp " + quote(classPath) + " "
                + quote(NoisyChecker.class.getName()) + " " + quote(report.toString());
        // cmd.exe needs an outer pair of quotes when the executable path is quoted.
        if (windows()) command = "\"" + command + "\"";
        set(mojo, "expression", command);
        Method check = BinaryCompatibilityEnforcerPluginMogo.class.getDeclaredMethod("checkBinaryCompatibility", String.class, String.class, String.class);
        check.setAccessible(true);
        try {
            check.invoke(mojo, "old.jar", "new.jar", "fixture");
        } catch (InvocationTargetException failure) {
            throw new AssertionError("Checker must drain stderr while waiting for stdout", failure.getCause());
        }
        assertTrue(Files.size(report) > 0);
    }

    private static boolean windows() { return System.getProperty("os.name").startsWith("Windows"); }
    private static String quote(String text) { return windows() ? "\"" + text + "\"" : "'" + text.replace("'", "'\"'\"'") + "'"; }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = BinaryCompatibilityEnforcerPluginMogo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    public static class NoisyChecker {
        public static void main(String[] args) throws Exception {
            Thread watchdog = new Thread(() -> {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                Runtime.getRuntime().halt(17);
            });
            watchdog.setDaemon(true);
            watchdog.start();
            char[] line = new char[1023];
            Arrays.fill(line, 'x');
            for (int i = 0; i < 256; i++) System.err.println(line);
            System.err.flush();
            Path report = new File(args[0]).toPath();
            Files.createDirectories(report.getParent());
            Files.write(report, "fixture report".getBytes(StandardCharsets.UTF_8));
            System.out.println("Binary compatibility: 100%");
            System.out.println("Report: " + report);
        }
    }
}

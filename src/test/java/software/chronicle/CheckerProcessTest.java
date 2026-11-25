package software.chronicle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class CheckerProcessTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    @Test
    public void preservesArgumentsEnvironmentAndHeapLimit() throws Exception {
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classes = new File(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        // Windows' Java launcher has its own embedded-double-quote limitation.
        String argument = WINDOWS ? "spaces ' $() ; & % literal" : "spaces ' \" $() ; & % literal";
        ProcessBuilder builder = CheckerProcess.builder(Arrays.asList(java, "-cp", classes,
                Probe.class.getName(), argument), WINDOWS);
        builder.environment().put("_JAVA_OPTIONS", "-Xms64m -Xmx512m -Dchecker.marker=retained");
        builder.environment().put("CHECKER_MARKER", "environment retained");
        File output = temporary.newFile();
        int exit = run(builder, output);
        String text = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        assertEquals(text, 0, exit);
        assertTrue(text, text.contains(argument));
        assertTrue(text, text.contains("environment retained"));
        assertTrue(text, text.contains("retained"));
        assertTrue(text, text.contains("heap bounded"));
    }

    @Test
    public void preservesFailureExitCode() throws Exception {
        ProcessBuilder builder = CheckerProcess.builder(WINDOWS
                ? Arrays.asList("cmd.exe", "/c", "exit /b 7")
                : Arrays.asList("/bin/sh", "-c", "exit 7"), WINDOWS);
        assertEquals(7, run(builder, temporary.newFile()));
    }

    static int run(ProcessBuilder builder, File output) throws Exception {
        Process process = builder.redirectErrorStream(true).redirectOutput(output).start();
        try {
            assertTrue("Checker process did not finish", process.waitFor(30, TimeUnit.SECONDS));
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    public static class Probe {
        public static void main(String[] args) {
            System.out.println(args[0]);
            System.out.println(System.getenv("CHECKER_MARKER"));
            if (!"retained".equals(System.getProperty("checker.marker")))
                throw new AssertionError("JVM option was lost");
            System.out.println("retained");
            if (Runtime.getRuntime().maxMemory() > 512L * 1024 * 1024)
                throw new AssertionError("Heap limit was lost");
            System.out.println("heap bounded");
        }
    }
}

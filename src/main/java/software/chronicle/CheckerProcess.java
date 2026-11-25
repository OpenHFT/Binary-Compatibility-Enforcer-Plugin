package software.chronicle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Launches the checker without changing its JVM options or argument boundaries. */
final class CheckerProcess {
    private CheckerProcess() {
    }

    static ProcessBuilder builder(List<String> command, boolean windows) {
        if (windows)
            return new ProcessBuilder(command);

        // JAPICC closes javap's stderr pipe before reading stdout. A JVM options
        // banner can otherwise terminate javap with SIGPIPE before it emits an API.
        // Ignore that signal only in the checker subtree; retain the environment.
        List<String> launch = new ArrayList<>(Arrays.asList(
                "/bin/sh", "-c", "trap '' PIPE; exec \"$@\"", "chronicle-checker"));
        launch.addAll(command);
        return new ProcessBuilder(launch);
    }
}

package software.chronicle;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.lang.reflect.Field;

/** Obtain the PID from the owned Java 8 process handle, without matching process names. */
final class WindowsProcessId {
    private WindowsProcessId() {
    }

    static long get(Process process) throws ReflectiveOperationException, IOException {
        Field handle = process.getClass().getDeclaredField("handle");
        handle.setAccessible(true);
        int pid = Kernel32.INSTANCE.GetProcessId(new Pointer(handle.getLong(process)));
        if (pid == 0)
            throw new IOException("Cannot obtain checker process ID: Windows error " + Native.getLastError());
        return Integer.toUnsignedLong(pid);
    }

    private interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        int GetProcessId(Pointer processHandle);
    }
}

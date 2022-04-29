package software.chronicle;

import org.apache.maven.plugins.annotations.Parameter;

public final class ExtraOption {

    /**
     * Name of the option (e.g. skip-internal-packages).
     */
    @Parameter(name = "name", required = true)
    String name;

    /**
     * Value of the option (e.g. *my_internal_package*).
     */
    @Parameter(name = "value", required = false)
    String value;

    public ExtraOption() {
    }

    public ExtraOption(String name,
                       String value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String toString() {
        return '-' + name +
                (value != null && !value.isEmpty()
                        ? ' ' + value
                        : ""
                );
    }
}
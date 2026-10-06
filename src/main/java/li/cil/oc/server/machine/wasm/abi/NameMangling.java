package li.cil.oc.server.machine.wasm.abi;

/**
 * Naming schemes used by core modules that implement a WIT world. Toolchains
 * currently emit {@link #LEGACY} names (e.g. {@code wit-bindgen}); newer
 * tooling may emit {@link #CM32P2} names. Both are accepted.
 */
public enum NameMangling {
    LEGACY {
        @Override
        public String importModule(final String iface) {
            return OcMachineWorld.PACKAGE + "/" + iface + "@" + OcMachineWorld.VERSION;
        }

        @Override
        public String exportName(final String iface, final String function) {
            return importModule(iface) + "#" + function;
        }

        @Override
        public String postReturnName(final String iface, final String function) {
            return "cabi_post_" + exportName(iface, function);
        }

        @Override
        public String memoryName() {
            return "memory";
        }

        @Override
        public String reallocName() {
            return "cabi_realloc";
        }

        @Override
        public String initializeName() {
            return "_initialize";
        }
    },

    CM32P2 {
        @Override
        public String importModule(final String iface) {
            return "cm32p2|" + OcMachineWorld.PACKAGE + "/" + iface + "@" + compatibleVersion(OcMachineWorld.VERSION);
        }

        @Override
        public String exportName(final String iface, final String function) {
            return importModule(iface) + "|" + function;
        }

        @Override
        public String postReturnName(final String iface, final String function) {
            return exportName(iface, function) + "_post";
        }

        @Override
        public String memoryName() {
            return "cm32p2_memory";
        }

        @Override
        public String reallocName() {
            return "cm32p2_realloc";
        }

        @Override
        public String initializeName() {
            return "cm32p2_initialize";
        }
    };

    public abstract String importModule(String iface);

    public abstract String exportName(String iface, String function);

    public abstract String postReturnName(String iface, String function);

    public abstract String memoryName();

    public abstract String reallocName();

    public abstract String initializeName();

    /**
     * Semver-compatible version prefix: {@code 0.1.0 -> 0.1}, {@code 1.2.3 -> 1}.
     */
    static String compatibleVersion(final String version) {
        final String[] parts = version.split("\\.");
        if (!parts[0].equals("0")) return parts[0];
        if (!parts[1].equals("0")) return parts[0] + "." + parts[1];
        return version;
    }
}


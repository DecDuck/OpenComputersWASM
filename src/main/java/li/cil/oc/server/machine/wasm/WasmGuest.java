package li.cil.oc.server.machine.wasm;

import com.dylibso.chicory.runtime.ExecutionListener;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.GlobalInstance;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.TableInstance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.Export;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.Import;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.MutabilityType;
import com.dylibso.chicory.wasm.types.ValType;
import com.dylibso.chicory.wasm.types.Value;
import li.cil.oc.server.machine.wasm.abi.AbiException;
import li.cil.oc.server.machine.wasm.abi.CanonicalAbi;
import li.cil.oc.server.machine.wasm.abi.CoreType;
import li.cil.oc.server.machine.wasm.abi.NameMangling;
import li.cil.oc.server.machine.wasm.abi.OcMachineWorld;
import li.cil.oc.server.machine.wasm.abi.VariantValue;
import li.cil.oc.server.machine.wasm.abi.WitFunction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A guest module implementing the {@code oc:machine} world, instantiated in
 * the Chicory interpreter.
 * <p>
 * Guests processed with Binaryen's Asyncify pass ({@code wasm-opt --asyncify})
 * can additionally be suspended inside an import call: a {@link Handler} may
 * return {@link #SUSPEND}, which unwinds the guest's stack into linear memory
 * and makes {@link #resume} return {@code null}. {@link #continueSuspended}
 * later rewinds it, completing the import call with the given result. Since
 * the unwound stack is part of linear memory, {@link #snapshot()} captures
 * suspended guests too.
 * <p>
 * This class is independent of Minecraft; host behavior is supplied through
 * {@link Bindings}.
 */
public final class WasmGuest {
    public static final int PAGE_SIZE = Memory.PAGE_SIZE;

    public static final int DEFAULT_ASYNCIFY_BUFFER_SIZE = 16 * 1024;

    /**
     * Returned by a {@link Handler} to suspend the guest in the current import
     * call. Only valid if {@link #canSuspend()}.
     */
    public static final Object SUSPEND = new Object() {
        @Override
        public String toString() {
            return "SUSPEND";
        }
    };

    private static final String ASYNCIFY_START_UNWIND = "asyncify_start_unwind";
    private static final String ASYNCIFY_STOP_UNWIND = "asyncify_stop_unwind";
    private static final String ASYNCIFY_START_REWIND = "asyncify_start_rewind";
    private static final String ASYNCIFY_STOP_REWIND = "asyncify_stop_rewind";

    /**
     * Implementation of a host function. Receives the lifted WIT arguments
     * and returns the WIT result (see {@link li.cil.oc.server.machine.wasm.abi.WitType}
     * for the Java representation), {@code null} for functions without
     * result, or {@link #SUSPEND}.
     */
    @FunctionalInterface
    public interface Handler {
        Object call(Object[] arguments);
    }

    /**
     * Host function implementations, keyed by interface and function name,
     * plus raw core-level imports (such as WASI) outside the WIT world.
     */
    public static final class Bindings {
        private final Map<String, Handler> handlers = new HashMap<>();
        private final Map<String, ImportFunction> raw = new HashMap<>();

        public Bindings bind(final String iface, final WitFunction function, final Handler handler) {
            handlers.put(key(iface, function.name()), handler);
            return this;
        }

        /**
         * Provides core functions that are linked as-is, without canonical
         * ABI translation. Their module names must not clash with the WIT
         * interfaces.
         */
        public Bindings provide(final Iterable<? extends ImportFunction> functions) {
            for (final ImportFunction function : functions) {
                raw.put(key(function.module(), function.name()), function);
            }
            return this;
        }

        Handler get(final String iface, final String function) {
            return handlers.get(key(iface, function));
        }

        ImportFunction raw(final String module, final String function) {
            return raw.get(key(module, function));
        }

        static String key(final String iface, final String function) {
            return iface + "#" + function;
        }
    }

    // ----------------------------------------------------------------------- //

    private record Asyncify(ExportFunction startUnwind, ExportFunction stopUnwind,
                            ExportFunction startRewind, ExportFunction stopRewind) {
    }

    /**
     * State shared between the guest and its host function wrappers, which
     * are created before the instance exists.
     */
    private static final class Link {
        final String reallocName;
        ChicoryGuestMemory memory;
        Asyncify asyncify;
        int asyncifyBuffer;
        int asyncifyBufferSize;
        boolean unwinding;
        boolean rewinding;
        String suspendedOn;
        long[] suspendedArgs;
        Object rewindResult;

        Link(final String reallocName) {
            this.reallocName = reallocName;
        }

        ChicoryGuestMemory memory(final Instance instance) {
            if (memory == null) {
                memory = new ChicoryGuestMemory(instance, reallocName);
            }
            return memory;
        }

        void reset() {
            unwinding = false;
            rewinding = false;
            suspendedOn = null;
            suspendedArgs = null;
            rewindResult = null;
        }
    }

    private final Instance instance;
    private final Link link;
    private final ExportFunction resume;
    private final ExportFunction resumePostReturn;
    private final int maxPages;

    private WasmGuest(final Instance instance, final Link link, final ExportFunction resume, final ExportFunction resumePostReturn, final int maxPages) {
        this.instance = instance;
        this.link = link;
        this.resume = resume;
        this.resumePostReturn = resumePostReturn;
        this.maxPages = maxPages;
    }

    /**
     * Parses, validates, links and initializes a guest module.
     *
     * @param code     the binary module.
     * @param maxPages the maximum number of 64 KiB memory pages the guest
     *                 may use, i.e. the machine's installed memory.
     * @param bindings the host function implementations.
     * @param listener execution listener, used to enforce time limits. This
     *                 is already active while the module initializes.
     */
    public static WasmGuest instantiate(final byte[] code, final int maxPages, final Bindings bindings, final ExecutionListener listener) throws WasmGuestException {
        return instantiate(code, maxPages, bindings, listener, DEFAULT_ASYNCIFY_BUFFER_SIZE);
    }

    /**
     * Like {@link #instantiate(byte[], int, Bindings, ExecutionListener)},
     * with an explicit size for the buffer holding the unwound call stack of
     * Asyncify-instrumented guests (allocated from guest memory).
     */
    public static WasmGuest instantiate(final byte[] code, final int maxPages, final Bindings bindings, final ExecutionListener listener,
                                        final int asyncifyBufferSize) throws WasmGuestException {
        return create(code, maxPages, bindings, listener, asyncifyBufferSize, null);
    }

    /**
     * Instantiates a module and restores a snapshot taken from an earlier
     * instance of the same module. No guest code runs while doing so.
     */
    public static WasmGuest restore(final byte[] code, final int maxPages, final Bindings bindings, final ExecutionListener listener,
                                    final GuestSnapshot snapshot) throws WasmGuestException {
        return create(code, maxPages, bindings, listener, snapshot.asyncifyBufferSize(), snapshot);
    }

    private static WasmGuest create(final byte[] code, final int maxPages, final Bindings bindings, final ExecutionListener listener,
                                    final int asyncifyBufferSize, final GuestSnapshot snapshot) throws WasmGuestException {
        final WasmModule module;
        try {
            module = Parser.parse(code);
        } catch (final RuntimeException e) {
            throw new WasmGuestException("invalid WebAssembly module: " + messageOf(e), e);
        }

        final Set<String> exports = new HashSet<>();
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            final Export export = module.exportSection().getExport(i);
            exports.add(export.name());
        }
        final NameMangling mangling = exports.contains(NameMangling.CM32P2.memoryName()) ? NameMangling.CM32P2 : NameMangling.LEGACY;

        final MemoryLimits limits = memoryLimits(module, maxPages);
        final Link link = new Link(mangling.reallocName());
        final ImportValues imports = linkImports(module, mangling, bindings, link);

        final String resumeName = mangling.exportName(OcMachineWorld.KERNEL, OcMachineWorld.KERNEL_RESUME.name());
        if (!exports.contains(resumeName) && exports.contains("_start")) {
            throw new WasmGuestException("module is a command (it has a main function); build a library " +
                    "(e.g. crate-type = [\"cdylib\"]) that exports " + OcMachineWorld.PACKAGE + "/" + OcMachineWorld.KERNEL);
        }
        requireExport(exports, mangling.memoryName(), "memory");
        requireExport(exports, mangling.reallocName(), "allocator");
        requireExport(exports, resumeName, "kernel entry point");
        verifyAsyncify(module, mangling, exports);

        final Instance instance;
        try {
            instance = Instance.builder(module)
                    .withImportValues(imports)
                    .withMemoryLimits(limits)
                    .withUnsafeExecutionListener(listener)
                    // When restoring, the snapshot replaces whatever the start
                    // function would have done.
                    .withStart(snapshot == null)
                    .build();
        } catch (final WasmTimeoutException e) {
            throw e;
        } catch (final RuntimeException e) {
            throw new WasmGuestException("failed instantiating module: " + messageOf(e), e);
        }

        requireType(instance, resumeName, OcMachineWorld.KERNEL_RESUME.exportCoreParams(), OcMachineWorld.KERNEL_RESUME.exportCoreResults());
        requireType(instance, mangling.reallocName(), List.of(CoreType.I32, CoreType.I32, CoreType.I32, CoreType.I32), List.of(CoreType.I32));
        link.memory(instance);
        link.asyncify = asyncify(instance, exports);

        final String postReturnName = mangling.postReturnName(OcMachineWorld.KERNEL, OcMachineWorld.KERNEL_RESUME.name());
        final ExportFunction postReturn;
        if (exports.contains(postReturnName)) {
            requireType(instance, postReturnName, OcMachineWorld.KERNEL_RESUME.exportCoreResults(), List.of());
            postReturn = instance.export(postReturnName);
        } else {
            postReturn = null;
        }

        if (snapshot == null) {
            if (exports.contains(mangling.initializeName())) {
                requireType(instance, mangling.initializeName(), List.of(), List.of());
                instance.export(mangling.initializeName()).apply();
            }
            if (link.asyncify != null) {
                if (asyncifyBufferSize < 64) {
                    throw new WasmGuestException("Asyncify buffer too small");
                }
                link.asyncifyBufferSize = asyncifyBufferSize;
                link.asyncifyBuffer = link.memory.realloc(0, 0, 8, asyncifyBufferSize);
            }
        } else {
            restoreState(instance, link, limits, snapshot);
        }

        return new WasmGuest(instance, link, instance.export(resumeName), postReturn, limits.maximumPages());
    }

    // ----------------------------------------------------------------------- //

    /**
     * Calls {@code kernel.resume}.
     *
     * @param event an {@code event} value (see {@link OcMachineWorld#EVENT}).
     * @return the {@code step} value returned by the guest, or {@code null} if
     * the guest was suspended by a host call returning {@link #SUSPEND}.
     */
    public VariantValue resume(final VariantValue event) {
        if (isSuspended()) {
            throw new IllegalStateException("guest is suspended");
        }
        final long[] arguments = CanonicalAbi.lowerExportArguments(link.memory, OcMachineWorld.KERNEL_RESUME, new Object[]{event});
        return run(arguments);
    }

    /**
     * Continues a suspended guest, completing the import call it was
     * suspended in with the given result.
     *
     * @return like {@link #resume}.
     */
    public VariantValue continueSuspended(final Object result) {
        if (!isSuspended()) {
            throw new IllegalStateException("guest is not suspended");
        }
        final long[] arguments = link.suspendedArgs;
        link.suspendedArgs = null;
        link.rewindResult = result;
        link.rewinding = true;
        link.asyncify.startRewind().apply(link.asyncifyBuffer);
        return run(arguments);
    }

    private VariantValue run(final long[] arguments) {
        final long[] results;
        try {
            results = resume.apply(arguments);
        } catch (final RuntimeException | Error e) {
            link.reset();
            throw e;
        }

        if (link.unwinding) {
            link.unwinding = false;
            link.asyncify.stopUnwind().apply();
            link.suspendedArgs = arguments;
            return null;
        }
        if (link.rewinding) {
            link.reset();
            throw new AbiException("guest returned without completing the suspended call");
        }

        final Object step = CanonicalAbi.liftExportResult(link.memory, OcMachineWorld.KERNEL_RESUME, results);
        if (resumePostReturn != null) {
            resumePostReturn.apply(results);
        }
        return (VariantValue) step;
    }

    /**
     * Whether the guest is instrumented with Asyncify and can therefore be
     * suspended in import calls.
     */
    public boolean canSuspend() {
        return link.asyncify != null;
    }

    /**
     * Whether the guest is currently suspended in an import call.
     */
    public boolean isSuspended() {
        return link.suspendedOn != null && !link.rewinding;
    }

    /**
     * Number of memory pages currently in use.
     */
    public int pages() {
        return instance.memory().pages();
    }

    /**
     * Maximum number of memory pages the guest may grow to.
     */
    public int maxPages() {
        return maxPages;
    }

    /**
     * Captures the guest's state. Must not be called while the guest runs.
     */
    public GuestSnapshot snapshot() {
        if (link.unwinding || link.rewinding) {
            throw new IllegalStateException("guest is running");
        }
        final Memory memory = instance.memory();
        final int pages = memory.pages();
        final byte[] contents = memory.readBytes(0, pages * PAGE_SIZE);

        final int globalCount = instance.globalCount();
        final long[] globalsLow = new long[globalCount];
        final long[] globalsHigh = new long[globalCount];
        for (int i = 0; i < globalCount; i++) {
            final GlobalInstance global = instance.global(i);
            globalsLow[i] = global.getValueLow();
            globalsHigh[i] = global.getValueHigh();
        }

        final int[][] tables = new int[instance.tableCount()][];
        for (int i = 0; i < tables.length; i++) {
            final TableInstance table = instance.table(i);
            tables[i] = new int[table.size()];
            for (int j = 0; j < tables[i].length; j++) {
                tables[i][j] = table.ref(j);
            }
        }

        return new GuestSnapshot(pages, contents, globalsLow, globalsHigh, tables,
                link.asyncifyBuffer, link.asyncifyBufferSize,
                link.suspendedOn, link.suspendedArgs == null ? null : link.suspendedArgs.clone());
    }

    // ----------------------------------------------------------------------- //

    private static void restoreState(final Instance instance, final Link link, final MemoryLimits limits, final GuestSnapshot snapshot) throws WasmGuestException {
        final Memory memory = instance.memory();
        if (snapshot.pages() > limits.maximumPages()) {
            throw new WasmGuestException("not enough memory to restore the machine's state");
        }
        if (snapshot.pages() < memory.pages()) {
            throw new WasmGuestException("snapshot does not match module (memory)");
        }
        if (snapshot.pages() > memory.pages() && memory.grow(snapshot.pages() - memory.pages()) < 0) {
            throw new WasmGuestException("not enough memory to restore the machine's state");
        }
        memory.write(0, snapshot.memory());

        if (snapshot.globalsLow().length != instance.globalCount()) {
            throw new WasmGuestException("snapshot does not match module (globals)");
        }
        for (int i = 0; i < snapshot.globalsLow().length; i++) {
            final GlobalInstance global = instance.global(i);
            if (global.getMutabilityType() == MutabilityType.Var) {
                global.setValueLow(snapshot.globalsLow()[i]);
                global.setValueHigh(snapshot.globalsHigh()[i]);
            } else if (global.getValueLow() != snapshot.globalsLow()[i] || global.getValueHigh() != snapshot.globalsHigh()[i]) {
                throw new WasmGuestException("snapshot does not match module (constants)");
            }
        }

        if (snapshot.tables().length != instance.tableCount()) {
            throw new WasmGuestException("snapshot does not match module (tables)");
        }
        for (int i = 0; i < snapshot.tables().length; i++) {
            final TableInstance table = instance.table(i);
            final int[] refs = snapshot.tables()[i];
            if (refs.length < table.size()) {
                throw new WasmGuestException("snapshot does not match module (table size)");
            }
            if (refs.length > table.size() && table.grow(refs.length - table.size(), Value.REF_NULL_VALUE, instance) < 0) {
                throw new WasmGuestException("snapshot does not match module (table limits)");
            }
            for (int j = 0; j < refs.length; j++) {
                if (table.ref(j) != refs[j]) {
                    table.setRef(j, refs[j], instance);
                }
            }
        }

        if (snapshot.isSuspended() && link.asyncify == null) {
            throw new WasmGuestException("snapshot of a suspended guest, but module does not support suspending");
        }
        link.asyncifyBuffer = snapshot.asyncifyBuffer();
        link.asyncifyBufferSize = snapshot.asyncifyBufferSize();
        link.suspendedOn = snapshot.suspendedOn();
        link.suspendedArgs = snapshot.suspendedArgs() == null ? null : snapshot.suspendedArgs().clone();
    }

    private static Asyncify asyncify(final Instance instance, final Set<String> exports) throws WasmGuestException {
        if (!exports.contains(ASYNCIFY_START_UNWIND)) {
            return null;
        }
        final List<CoreType> pointer = List.of(CoreType.I32);
        requireType(instance, ASYNCIFY_START_UNWIND, pointer, List.of());
        requireType(instance, ASYNCIFY_STOP_UNWIND, List.of(), List.of());
        requireType(instance, ASYNCIFY_START_REWIND, pointer, List.of());
        requireType(instance, ASYNCIFY_STOP_REWIND, List.of(), List.of());
        requireType(instance, AsyncifyVerifier.GET_STATE, List.of(), List.of(CoreType.I32));
        return new Asyncify(
                instance.export(ASYNCIFY_START_UNWIND),
                instance.export(ASYNCIFY_STOP_UNWIND),
                instance.export(ASYNCIFY_START_REWIND),
                instance.export(ASYNCIFY_STOP_REWIND));
    }

    private static final List<String> ASYNCIFY_EXPORTS = List.of(
            ASYNCIFY_START_UNWIND, ASYNCIFY_STOP_UNWIND, ASYNCIFY_START_REWIND, ASYNCIFY_STOP_REWIND, AsyncifyVerifier.GET_STATE);

    /**
     * Modules exporting any of Asyncify's functions must be completely and
     * correctly instrumented, as the host will suspend them.
     */
    private static void verifyAsyncify(final WasmModule module, final NameMangling mangling, final Set<String> exports) throws WasmGuestException {
        final List<String> missing = ASYNCIFY_EXPORTS.stream().filter(name -> !exports.contains(name)).toList();
        if (missing.size() == ASYNCIFY_EXPORTS.size()) {
            return;
        }
        if (!missing.isEmpty()) {
            throw new WasmGuestException("module is not correctly instrumented with Asyncify: missing exports " + String.join(", ", missing));
        }
        AsyncifyVerifier.verify(module, (importModule, name) -> isSuspending(mangling, importModule, name));
    }

    private static boolean isSuspending(final NameMangling mangling, final String importModule, final String name) {
        for (final Map.Entry<String, List<WitFunction>> entry : OcMachineWorld.SUSPENDING_IMPORTS.entrySet()) {
            if (mangling.importModule(entry.getKey()).equals(importModule)) {
                for (final WitFunction function : entry.getValue()) {
                    if (function.name().equals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static MemoryLimits memoryLimits(final WasmModule module, final int maxPages) throws WasmGuestException {
        if (module.memorySection().isEmpty() || module.memorySection().get().memoryCount() != 1) {
            throw new WasmGuestException("module must define exactly one memory");
        }
        final MemoryLimits declared = module.memorySection().get().getMemory(0).limits();
        if (declared.shared()) {
            throw new WasmGuestException("shared memory is not supported");
        }
        final int maximum = Math.min(declared.maximumPages(), Math.max(0, maxPages));
        if (declared.initialPages() > maximum) {
            throw new WasmGuestException("not enough memory: module needs " + (declared.initialPages() * (PAGE_SIZE / 1024)) +
                    " KiB, " + (maximum * (PAGE_SIZE / 1024)) + " KiB available");
        }
        return new MemoryLimits(declared.initialPages(), maximum);
    }

    private static ImportValues linkImports(final WasmModule module, final NameMangling mangling, final Bindings bindings, final Link link) throws WasmGuestException {
        final Map<String, String> interfaces = new HashMap<>();
        for (final String iface : OcMachineWorld.IMPORTS.keySet()) {
            interfaces.put(mangling.importModule(iface), iface);
        }

        final List<ImportFunction> functions = new ArrayList<>();
        for (int i = 0; i < module.importSection().importCount(); i++) {
            final Import imported = module.importSection().getImport(i);
            final String description = imported.module() + "." + imported.name();
            if (imported.importType() != ExternalType.FUNCTION) {
                throw new WasmGuestException("unsupported import " + description);
            }
            final FunctionType actual = module.typeSection().getType(((FunctionImport) imported).typeIndex());
            final String iface = interfaces.get(imported.module());
            if (iface == null) {
                final ImportFunction raw = bindings.raw(imported.module(), imported.name());
                if (raw == null) {
                    if (imported.module().equals(WasiSupport.MODULE)) {
                        throw new WasmGuestException("WASI is not available (import " + description + ")");
                    }
                    throw new WasmGuestException("unknown import " + description);
                }
                if (!raw.functionType().equals(actual)) {
                    throw new WasmGuestException("import " + description + " has type " + actual + ", expected " + raw.functionType());
                }
                functions.add(raw);
                continue;
            }
            final WitFunction function = OcMachineWorld.IMPORTS.get(iface).stream()
                    .filter(f -> f.name().equals(imported.name()))
                    .findFirst()
                    .orElseThrow(() -> new WasmGuestException("unknown import " + description));
            final Handler handler = bindings.get(iface, function.name());
            if (handler == null) {
                throw new WasmGuestException("unimplemented import " + description);
            }

            final FunctionType expected = functionType(function.importCoreParams(), function.importCoreResults());
            if (!expected.equals(actual)) {
                throw new WasmGuestException("import " + description + " has type " + actual + ", expected " + expected);
            }

            final String key = Bindings.key(iface, function.name());
            final boolean mayInterrupt = isSuspending(mangling, imported.module(), imported.name());
            functions.add(new HostFunction(imported.module(), imported.name(), expected,
                    (instance, arguments) -> callHost(link, key, mayInterrupt, function, handler, instance, arguments)));
        }
        return ImportValues.builder().withFunctions(functions).build();
    }

    private static long[] callHost(final Link link, final String key, final boolean mayInterrupt, final WitFunction function, final Handler handler,
                                   final Instance instance, final long[] arguments) {
        final ChicoryGuestMemory memory = link.memory(instance);

        // Asyncify re-enters the import the guest was suspended in: complete
        // it with the result provided to continueSuspended.
        if (link.rewinding) {
            if (!key.equals(link.suspendedOn)) {
                throw new AbiException("guest resumed in " + key + " instead of " + link.suspendedOn);
            }
            link.asyncify.stopRewind().apply();
            final Object result = link.rewindResult;
            link.reset();
            return CanonicalAbi.lowerImportResult(memory, function, arguments, result);
        }

        final Object[] lifted = CanonicalAbi.liftImportArguments(memory, function, arguments);
        final Object result = handler.call(lifted);
        if (result == SUSPEND) {
            if (link.asyncify == null) {
                throw new IllegalStateException("guest cannot be suspended");
            }
            if (!mayInterrupt) {
                // Only these imports were checked by AsyncifyVerifier.
                throw new IllegalStateException(key + " may not suspend the guest");
            }
            memory.writeInt(link.asyncifyBuffer, link.asyncifyBuffer + 8);
            memory.writeInt(link.asyncifyBuffer + 4, link.asyncifyBuffer + link.asyncifyBufferSize);
            link.asyncify.startUnwind().apply(link.asyncifyBuffer);
            link.unwinding = true;
            link.suspendedOn = key;
            // Ignored by the guest, which is unwinding now.
            return new long[function.importCoreResults().size()];
        }
        return CanonicalAbi.lowerImportResult(memory, function, arguments, result);
    }

    private static void requireExport(final Set<String> exports, final String name, final String description) throws WasmGuestException {
        if (!exports.contains(name)) {
            throw new WasmGuestException("module does not export " + description + " '" + name + "'");
        }
    }

    private static void requireType(final Instance instance, final String export, final List<CoreType> params, final List<CoreType> results) throws WasmGuestException {
        final FunctionType expected = functionType(params, results);
        final FunctionType actual;
        try {
            actual = instance.exportType(export);
        } catch (final RuntimeException e) {
            throw new WasmGuestException("export '" + export + "' is not a function", e);
        }
        if (!expected.equals(actual)) {
            throw new WasmGuestException("export '" + export + "' has type " + actual + ", expected " + expected);
        }
    }

    static FunctionType functionType(final List<CoreType> params, final List<CoreType> results) {
        return FunctionType.of(params.stream().map(WasmGuest::valType).toList(), results.stream().map(WasmGuest::valType).toList());
    }

    private static ValType valType(final CoreType type) {
        return switch (type) {
            case I32 -> ValType.I32;
            case I64 -> ValType.I64;
            case F32 -> ValType.F32;
            case F64 -> ValType.F64;
        };
    }

    private static String messageOf(final Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}







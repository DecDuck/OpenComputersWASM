package li.cil.oc.server.machine.wasm.abi;

import java.util.List;
import java.util.Map;

import static li.cil.oc.server.machine.wasm.abi.WitType.Primitive.*;
import static li.cil.oc.server.machine.wasm.abi.WitType.list;
import static li.cil.oc.server.machine.wasm.abi.WitType.option;
import static li.cil.oc.server.machine.wasm.abi.WitType.record;
import static li.cil.oc.server.machine.wasm.abi.WitType.result;
import static li.cil.oc.server.machine.wasm.abi.WitType.tuple;
import static li.cil.oc.server.machine.wasm.abi.WitType.variant;

/**
 * Host-side mirror of the {@code oc:machine} WIT package in the repository's
 * {@code wit/} directory. Keep both in sync; {@code OcMachineWorldTest}
 * checks the core signatures against a module generated from the WIT files.
 */
public final class OcMachineWorld {
    private OcMachineWorld() {
    }

    public static final String PACKAGE = "oc:machine";
    public static final String VERSION = "0.1.0";

    // ----------------------------------------------------------------------- //
    // types

    public static final int NODE_NIL = 0;
    public static final int NODE_BOOLEAN = 1;
    public static final int NODE_INTEGER = 2;
    public static final int NODE_NUMBER = 3;
    public static final int NODE_TEXT = 4;
    public static final int NODE_BYTES = 5;
    public static final int NODE_ARRAY = 6;
    public static final int NODE_TABLE = 7;
    public static final int NODE_OPAQUE = 8;

    public static final WitType NODE = variant(
            null,
            BOOL,
            S64,
            F64,
            STRING,
            list(U8),
            list(U32),
            list(tuple(U32, U32)),
            U32);

    /**
     * {@code record value-tree { nodes: list<node>, roots: list<node-index> }}
     */
    public static final WitType VALUE_TREE = record(list(NODE), list(U32));

    /**
     * {@code record method-info { name, direct, getter, setter, limit }}
     */
    public static final WitType METHOD_INFO = record(STRING, BOOL, BOOL, BOOL, S32);

    public static final int CALL_ERROR_NO_SUCH_COMPONENT = 0;
    public static final int CALL_ERROR_NO_SUCH_METHOD = 1;
    public static final int CALL_ERROR_BAD_ARGUMENT = 2;
    public static final int CALL_ERROR_FAILED = 3;

    public static final WitType CALL_ERROR = variant(null, null, STRING, STRING);

    public static final int CALL_RESULT_OK = 0;
    public static final int CALL_RESULT_PENDING = 1;
    public static final int CALL_RESULT_ERR = 2;

    public static final WitType CALL_RESULT = variant(VALUE_TREE, null, CALL_ERROR);

    // ----------------------------------------------------------------------- //
    // component

    /**
     * {@code record entry { address: string, kind: string }}
     */
    public static final WitType COMPONENT_ENTRY = record(STRING, STRING);

    public static final WitFunction COMPONENT_ENUMERATE = WitFunction.of("enumerate", list(COMPONENT_ENTRY), option(STRING), BOOL);
    public static final WitFunction COMPONENT_KIND = WitFunction.of("kind", option(STRING), STRING);
    public static final WitFunction COMPONENT_SLOT = WitFunction.of("slot", option(S32), STRING);
    public static final WitFunction COMPONENT_METHODS = WitFunction.of("methods", option(list(METHOD_INFO)), STRING);
    public static final WitFunction COMPONENT_DOC = WitFunction.of("doc", option(STRING), STRING, STRING);
    public static final WitFunction COMPONENT_INVOKE = WitFunction.of("invoke", CALL_RESULT, STRING, STRING, VALUE_TREE);

    // ----------------------------------------------------------------------- //
    // userdata

    public static final WitFunction USERDATA_METHODS = WitFunction.of("methods", option(list(METHOD_INFO)), U32);
    public static final WitFunction USERDATA_DOC = WitFunction.of("doc", option(STRING), U32, STRING);
    public static final WitFunction USERDATA_INVOKE = WitFunction.of("invoke", CALL_RESULT, U32, STRING, VALUE_TREE);
    public static final WitFunction USERDATA_APPLY = WitFunction.of("apply", CALL_RESULT, U32, VALUE_TREE);
    public static final WitFunction USERDATA_UNAPPLY = WitFunction.of("unapply", CALL_RESULT, U32, VALUE_TREE);
    public static final WitFunction USERDATA_CALL = WitFunction.of("call", CALL_RESULT, U32, VALUE_TREE);
    public static final WitFunction USERDATA_RELEASE = WitFunction.of("release", null, U32);

    // ----------------------------------------------------------------------- //
    // computer

    public static final WitFunction COMPUTER_ADDRESS = WitFunction.of("address", STRING);
    public static final WitFunction COMPUTER_TMP_ADDRESS = WitFunction.of("tmp-address", option(STRING));
    public static final WitFunction COMPUTER_TOTAL_MEMORY = WitFunction.of("total-memory", U64);
    public static final WitFunction COMPUTER_FREE_MEMORY = WitFunction.of("free-memory", U64);
    public static final WitFunction COMPUTER_UPTIME = WitFunction.of("uptime", F64);
    public static final WitFunction COMPUTER_CPU_TIME = WitFunction.of("cpu-time", F64);
    public static final WitFunction COMPUTER_REAL_TIME = WitFunction.of("real-time", F64);
    public static final WitFunction COMPUTER_WORLD_TIME = WitFunction.of("world-time", S64);
    public static final WitFunction COMPUTER_ENERGY = WitFunction.of("energy", F64);
    public static final WitFunction COMPUTER_MAX_ENERGY = WitFunction.of("max-energy", F64);
    public static final WitFunction COMPUTER_USERS = WitFunction.of("users", list(STRING));
    public static final WitFunction COMPUTER_ADD_USER = WitFunction.of("add-user", result(null, STRING), STRING);
    public static final WitFunction COMPUTER_REMOVE_USER = WitFunction.of("remove-user", BOOL, STRING);
    public static final WitFunction COMPUTER_PUSH_SIGNAL = WitFunction.of("push-signal", BOOL, STRING, VALUE_TREE);
    public static final WitFunction COMPUTER_ARCHITECTURE = WitFunction.of("architecture", STRING);

    // ----------------------------------------------------------------------- //
    // unicode

    public static final WitFunction UNICODE_CHAR_WIDTH = WitFunction.of("char-width", U8, CHAR);
    public static final WitFunction UNICODE_STRING_WIDTH = WitFunction.of("string-width", U32, STRING);
    public static final WitFunction UNICODE_TRUNCATE_TO_WIDTH = WitFunction.of("truncate-to-width", STRING, STRING, U32);

    // ----------------------------------------------------------------------- //
    // kernel (exported by the guest)

    /**
     * {@code record queued-signal { name: string, args: value-tree }}
     */
    public static final WitType QUEUED_SIGNAL = record(STRING, VALUE_TREE);

    public static final int EVENT_BOOT = 0;
    public static final int EVENT_WAKE = 1;
    public static final int EVENT_SIGNAL = 2;
    public static final int EVENT_SYNC_RESULT = 3;

    public static final WitType EVENT = variant(null, null, QUEUED_SIGNAL, CALL_RESULT);

    public static final int STEP_SLEEP = 0;
    public static final int STEP_WAIT = 1;
    public static final int STEP_SYNC = 2;
    public static final int STEP_SHUTDOWN = 3;
    public static final int STEP_CRASH = 4;
    public static final int STEP_EXEC = 5;

    public static final WitType STEP = variant(F64, null, null, BOOL, STRING, list(U8));

    public static final WitFunction KERNEL_RESUME = WitFunction.of("resume", STEP, EVENT);

    // ----------------------------------------------------------------------- //

    public static final String COMPONENT = "component";
    public static final String USERDATA = "userdata";
    public static final String COMPUTER = "computer";
    public static final String UNICODE = "unicode";
    public static final String KERNEL = "kernel";

    /**
     * All functions the host provides, by interface name.
     */
    public static final Map<String, List<WitFunction>> IMPORTS = Map.of(
            COMPONENT, List.of(COMPONENT_ENUMERATE, COMPONENT_KIND, COMPONENT_SLOT, COMPONENT_METHODS, COMPONENT_DOC, COMPONENT_INVOKE),
            USERDATA, List.of(USERDATA_METHODS, USERDATA_DOC, USERDATA_INVOKE, USERDATA_APPLY, USERDATA_UNAPPLY, USERDATA_CALL, USERDATA_RELEASE),
            COMPUTER, List.of(COMPUTER_ADDRESS, COMPUTER_TMP_ADDRESS, COMPUTER_TOTAL_MEMORY, COMPUTER_FREE_MEMORY, COMPUTER_UPTIME,
                    COMPUTER_CPU_TIME, COMPUTER_REAL_TIME, COMPUTER_WORLD_TIME, COMPUTER_ENERGY, COMPUTER_MAX_ENERGY, COMPUTER_USERS,
                    COMPUTER_ADD_USER, COMPUTER_REMOVE_USER, COMPUTER_PUSH_SIGNAL, COMPUTER_ARCHITECTURE),
            UNICODE, List.of(UNICODE_CHAR_WIDTH, UNICODE_STRING_WIDTH, UNICODE_TRUNCATE_TO_WIDTH));

    /**
     * Host functions that may suspend guests instrumented with Asyncify,
     * instead of returning {@code call-result::pending}, by interface name.
     */
    public static final Map<String, List<WitFunction>> SUSPENDING_IMPORTS = Map.of(
            COMPONENT, List.of(COMPONENT_INVOKE),
            USERDATA, List.of(USERDATA_INVOKE));
}


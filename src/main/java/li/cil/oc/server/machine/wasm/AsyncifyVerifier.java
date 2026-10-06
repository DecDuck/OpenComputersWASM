package li.cil.oc.server.machine.wasm;

import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.Element;
import com.dylibso.chicory.wasm.types.Export;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionBody;
import com.dylibso.chicory.wasm.types.Import;
import com.dylibso.chicory.wasm.types.Instruction;
import com.dylibso.chicory.wasm.types.NameCustomSection;
import com.dylibso.chicory.wasm.types.OpCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Checks that a module was instrumented with Binaryen's Asyncify pass in a
 * way that allows the host to suspend it in every import that may suspend.
 * <p>
 * Asyncify must instrument every call that can (transitively) reach such an
 * import: right after the call, the instrumented code checks Asyncify's state
 * global to see whether the stack is being unwound. If any such call lacks
 * that check, for example because the import was left out of Asyncify's
 * import list, suspending the guest would make it continue with bogus results
 * instead of unwinding. This verifier finds such calls statically:
 * <ol>
 * <li>the state global is the one read by {@code asyncify_get_state};</li>
 * <li>the functions that can suspend are the suspending imports, plus every
 * function calling one of them directly, or indirectly if a suspending
 * function is address-taken (conservatively, like Asyncify itself);</li>
 * <li>after every call that can suspend, the state global must be read on
 * the straight-line path that follows it, before any branch or other call.</li>
 * </ol>
 */
public final class AsyncifyVerifier {
    public static final String GET_STATE = "asyncify_get_state";

    private AsyncifyVerifier() {
    }

    /**
     * @param module     the module to check.
     * @param suspending whether the function import with the given module and
     *                   name may suspend the guest.
     * @throws WasmGuestException if instrumentation is missing or incomplete.
     */
    public static void verify(final WasmModule module, final BiPredicate<String, String> suspending) throws WasmGuestException {
        final List<Import> functionImports = new ArrayList<>();
        for (int i = 0; i < module.importSection().importCount(); i++) {
            final Import imported = module.importSection().getImport(i);
            if (imported.importType() == ExternalType.FUNCTION) {
                functionImports.add(imported);
            }
        }
        final int importCount = functionImports.size();
        final FunctionBody[] bodies = module.codeSection().functionBodies();
        final int functionCount = importCount + bodies.length;
        final Names names = new Names(module, functionImports);

        final int stateGlobal = stateGlobal(module, importCount, bodies);

        // Which functions may suspend, starting with the imports.
        final boolean[] suspends = new boolean[functionCount];
        for (int i = 0; i < importCount; i++) {
            suspends[i] = suspending.test(functionImports.get(i).module(), functionImports.get(i).name());
        }
        final Set<Integer> addressTaken = addressTaken(module, bodies);
        boolean indirectSuspends = false;
        boolean changed = true;
        while (changed) {
            changed = false;
            indirectSuspends = addressTaken.stream().anyMatch(index -> index < functionCount && suspends[index]);
            for (int i = 0; i < bodies.length; i++) {
                if (suspends[importCount + i]) continue;
                for (final Instruction instruction : bodies[i].instructions()) {
                    if (callMaySuspend(instruction, suspends, indirectSuspends)) {
                        suspends[importCount + i] = true;
                        changed = true;
                        break;
                    }
                }
            }
        }

        // Every call that may suspend must be followed by a state check.
        for (int i = 0; i < bodies.length; i++) {
            if (!suspends[importCount + i]) continue;
            final List<? extends Instruction> instructions = bodies[i].instructions();
            for (int j = 0; j < instructions.size(); j++) {
                final Instruction call = instructions.get(j);
                if (!callMaySuspend(call, suspends, indirectSuspends)) continue;

                final String target = describeCall(call, names);
                if (isTailCall(call.opcode())) {
                    throw new WasmGuestException("module is not correctly instrumented with Asyncify: " +
                            names.function(importCount + i) + " tail-calls " + target + ", which may suspend");
                }
                if (!checksStateAfter(instructions, j, stateGlobal)) {
                    throw new WasmGuestException("module is not correctly instrumented with Asyncify: the call from " +
                            names.function(importCount + i) + " to " + target + " may suspend but is not instrumented " +
                            "(was it left out of asyncify-imports, or removed with asyncify-removelist?)");
                }
            }
        }
    }

    private static int stateGlobal(final WasmModule module, final int importCount, final FunctionBody[] bodies) throws WasmGuestException {
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            final Export export = module.exportSection().getExport(i);
            if (!export.name().equals(GET_STATE)) continue;
            if (export.exportType() != ExternalType.FUNCTION || export.index() < importCount || export.index() - importCount >= bodies.length) {
                break;
            }
            for (final Instruction instruction : bodies[export.index() - importCount].instructions()) {
                if (instruction.opcode() == OpCode.GLOBAL_GET) {
                    return (int) instruction.operand(0);
                }
            }
            break;
        }
        throw new WasmGuestException("module is not correctly instrumented with Asyncify: " + GET_STATE + " does not read Asyncify's state");
    }

    private static Set<Integer> addressTaken(final WasmModule module, final FunctionBody[] bodies) {
        final Set<Integer> result = new HashSet<>();
        for (final Element element : module.elementSection().elements()) {
            for (final List<Instruction> initializer : element.initializers()) {
                for (final Instruction instruction : initializer) {
                    if (instruction.opcode() == OpCode.REF_FUNC) {
                        result.add((int) instruction.operand(0));
                    }
                }
            }
        }
        for (final FunctionBody body : bodies) {
            for (final Instruction instruction : body.instructions()) {
                if (instruction.opcode() == OpCode.REF_FUNC) {
                    result.add((int) instruction.operand(0));
                }
            }
        }
        return result;
    }

    private static boolean isCall(final OpCode opcode) {
        return opcode == OpCode.CALL || opcode == OpCode.CALL_INDIRECT || opcode == OpCode.CALL_REF || isTailCall(opcode);
    }

    private static boolean isTailCall(final OpCode opcode) {
        return opcode == OpCode.RETURN_CALL || opcode == OpCode.RETURN_CALL_INDIRECT || opcode == OpCode.RETURN_CALL_REF;
    }

    private static boolean callMaySuspend(final Instruction instruction, final boolean[] suspends, final boolean indirectSuspends) {
        final OpCode opcode = instruction.opcode();
        if (opcode == OpCode.CALL || opcode == OpCode.RETURN_CALL) {
            final long target = instruction.operand(0);
            return target >= 0 && target < suspends.length && suspends[(int) target];
        }
        return isCall(opcode) && indirectSuspends;
    }

    /**
     * Opcodes after which execution does not necessarily continue with the
     * next instruction. By name, so opcodes from proposals Chicory may not
     * know about don't break compilation.
     */
    private static final Set<String> CONTROL_TRANSFERS = Set.of(
            "IF", "ELSE", "BR", "BR_IF", "BR_TABLE", "RETURN", "UNREACHABLE",
            "THROW", "THROW_REF", "RETHROW", "TRY", "TRY_TABLE", "CATCH", "CATCH_ALL", "DELEGATE",
            "BR_ON_NULL", "BR_ON_NON_NULL", "BR_ON_CAST", "BR_ON_CAST_FAIL");

    /**
     * Whether the state global is read on the straight-line path right after
     * the call, i.e. before any branch, call or other control transfer. That
     * is where Asyncify places its unwind check; a read further away may
     * belong to a different path through the function.
     */
    private static boolean checksStateAfter(final List<? extends Instruction> instructions, final int call, final int stateGlobal) {
        for (int k = call + 1; k < instructions.size(); k++) {
            final Instruction instruction = instructions.get(k);
            final OpCode opcode = instruction.opcode();
            if (opcode == OpCode.GLOBAL_GET && instruction.operand(0) == stateGlobal) {
                return true;
            }
            if (isCall(opcode) || CONTROL_TRANSFERS.contains(opcode.name())) {
                return false;
            }
            // block, loop and end (of a block or else branch) continue with
            // the next instruction; the end of the function body does not.
            if (opcode == OpCode.END && k == instructions.size() - 1) {
                return false;
            }
        }
        return false;
    }

    private static String describeCall(final Instruction call, final Names names) {
        final OpCode opcode = call.opcode();
        if (opcode == OpCode.CALL || opcode == OpCode.RETURN_CALL) {
            return names.function((int) call.operand(0));
        }
        return "an indirect call";
    }

    /**
     * Human-readable function names: import names, or names from the name
     * section if the module has one.
     */
    private static final class Names {
        private final List<Import> imports;
        private final NameCustomSection section;

        Names(final WasmModule module, final List<Import> imports) {
            this.imports = imports;
            NameCustomSection section;
            try {
                section = module.nameSection();
            } catch (final RuntimeException e) {
                section = null;
            }
            this.section = section;
        }

        String function(final int index) {
            if (index < imports.size()) {
                return imports.get(index).module() + "." + imports.get(index).name();
            }
            final String name = section != null ? section.nameOfFunction(index) : null;
            return name != null ? "function " + index + " (" + name + ")" : "function " + index;
        }
    }
}




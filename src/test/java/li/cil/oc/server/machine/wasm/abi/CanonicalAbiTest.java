package li.cil.oc.server.machine.wasm.abi;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static li.cil.oc.server.machine.wasm.abi.OcMachineWorld.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CanonicalAbiTest {
    private static Object[] sampleTree() {
        return new Object[]{
                List.of(
                        VariantValue.of(NODE_TEXT, "h\u00e9llo"),
                        VariantValue.of(NODE_INTEGER, -42L),
                        VariantValue.of(NODE_ARRAY, List.of(0, 1)),
                        VariantValue.of(NODE_TABLE, List.of((Object) new Object[]{0, 1})),
                        VariantValue.of(NODE_NUMBER, 2.25),
                        VariantValue.of(NODE_BYTES, new byte[]{1, 2, 3}),
                        VariantValue.of(NODE_BOOLEAN, true),
                        VariantValue.of(NODE_NIL),
                        VariantValue.of(NODE_OPAQUE, 0xFFFFFFFF)),
                List.of(0, 1, 2, 3, 4, 5, 6, 7, 8)};
    }

    @SuppressWarnings("unchecked")
    private static void assertSampleTree(final Object tree) {
        final Object[] fields = (Object[]) tree;
        final List<Object> nodes = (List<Object>) fields[0];
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), fields[1]);
        assertEquals(9, nodes.size());
        assertEquals(VariantValue.of(NODE_TEXT, "h\u00e9llo"), nodes.get(0));
        assertEquals(VariantValue.of(NODE_INTEGER, -42L), nodes.get(1));
        assertEquals(VariantValue.of(NODE_ARRAY, List.of(0, 1)), nodes.get(2));
        final VariantValue table = (VariantValue) nodes.get(3);
        assertEquals(NODE_TABLE, table.index());
        assertArrayEquals(new Object[]{0, 1}, (Object[]) ((List<Object>) table.payload()).get(0));
        assertEquals(VariantValue.of(NODE_NUMBER, 2.25), nodes.get(4));
        final VariantValue bytes = (VariantValue) nodes.get(5);
        assertEquals(NODE_BYTES, bytes.index());
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) bytes.payload());
        assertEquals(VariantValue.of(NODE_BOOLEAN, true), nodes.get(6));
        assertEquals(VariantValue.of(NODE_NIL), nodes.get(7));
        assertEquals(VariantValue.of(NODE_OPAQUE, 0xFFFFFFFF), nodes.get(8));
    }

    // ----------------------------------------------------------------------- //

    @Test
    public void layoutMatchesCanonicalAbi() {
        assertEquals(16, CanonicalAbi.size(NODE));
        assertEquals(8, CanonicalAbi.alignment(NODE));
        assertEquals(16, CanonicalAbi.size(VALUE_TREE));
        assertEquals(4, CanonicalAbi.alignment(VALUE_TREE));
        assertEquals(12, CanonicalAbi.size(CALL_ERROR));
        assertEquals(20, CanonicalAbi.size(CALL_RESULT));
        assertEquals(4, CanonicalAbi.alignment(CALL_RESULT));
        assertEquals(16, CanonicalAbi.size(STEP));
        assertEquals(8, CanonicalAbi.alignment(STEP));
        assertEquals(16, CanonicalAbi.size(METHOD_INFO));
    }

    @Test
    public void flatteningMatchesCanonicalAbi() {
        assertEquals(Collections.nCopies(7, CoreType.I32), CanonicalAbi.flatten(EVENT));
        assertEquals(List.of(CoreType.I32, CoreType.I64, CoreType.I32), CanonicalAbi.flatten(STEP));
        assertEquals(Collections.nCopies(7, CoreType.I32), KERNEL_RESUME.exportCoreParams());
        assertEquals(List.of(CoreType.I32), KERNEL_RESUME.exportCoreResults());
        assertEquals(Collections.nCopies(9, CoreType.I32), COMPONENT_INVOKE.importCoreParams());
        assertEquals(List.of(), COMPONENT_INVOKE.importCoreResults());
        assertEquals(List.of(CoreType.I64), COMPUTER_WORLD_TIME.importCoreResults());
    }

    @Test
    public void storeAndLoadRoundTrip() {
        final ArrayGuestMemory memory = new ArrayGuestMemory(4096);
        final int pointer = memory.realloc(0, 0, CanonicalAbi.alignment(CALL_RESULT), CanonicalAbi.size(CALL_RESULT));
        CanonicalAbi.store(memory, pointer, CALL_RESULT, VariantValue.of(CALL_RESULT_OK, sampleTree()));
        final VariantValue loaded = (VariantValue) CanonicalAbi.load(memory, pointer, CALL_RESULT);
        assertEquals(CALL_RESULT_OK, loaded.index());
        assertSampleTree(loaded.payload());
    }

    @Test
    public void lowerAndLiftRoundTrip() {
        final ArrayGuestMemory memory = new ArrayGuestMemory(4096);
        final VariantValue event = VariantValue.of(EVENT_SIGNAL, new Object[]{"key_down", sampleTree()});
        final CanonicalAbi.FlatWriter writer = new CanonicalAbi.FlatWriter();
        CanonicalAbi.lowerFlat(memory, writer, EVENT, event);
        final long[] flat = writer.toArray();
        assertEquals(7, flat.length);

        final VariantValue lifted = (VariantValue) CanonicalAbi.liftFlat(memory, new CanonicalAbi.FlatReader(flat), EVENT);
        assertEquals(EVENT_SIGNAL, lifted.index());
        final Object[] signal = (Object[]) lifted.payload();
        assertEquals("key_down", signal[0]);
        assertSampleTree(signal[1]);
    }

    @Test
    public void variantPayloadsAreCoercedThroughJoinedSlots() {
        final ArrayGuestMemory memory = new ArrayGuestMemory(4096);
        for (final VariantValue step : List.of(
                VariantValue.of(STEP_SLEEP, -0.5),
                VariantValue.of(STEP_WAIT),
                VariantValue.of(STEP_SHUTDOWN, true),
                VariantValue.of(STEP_CRASH, "boom"))) {
            final CanonicalAbi.FlatWriter writer = new CanonicalAbi.FlatWriter();
            CanonicalAbi.lowerFlat(memory, writer, STEP, step);
            final long[] flat = writer.toArray();
            assertEquals(3, flat.length);
            assertEquals(step, CanonicalAbi.liftFlat(memory, new CanonicalAbi.FlatReader(flat), STEP));
        }
    }

    @Test
    public void importResultsGoThroughReturnPointer() {
        final ArrayGuestMemory memory = new ArrayGuestMemory(4096);
        final int returnPointer = memory.realloc(0, 0, 4, 12);
        final long[] core = CanonicalAbi.lowerImportResult(memory, COMPONENT_KIND, new long[]{0, 0, returnPointer}, VariantValue.some("gpu"));
        assertEquals(0, core.length);
        assertEquals(VariantValue.some("gpu"), CanonicalAbi.load(memory, returnPointer, COMPONENT_KIND.result()));

        final long[] flat = CanonicalAbi.lowerImportResult(memory, COMPUTER_REMOVE_USER, new long[]{0, 0}, true);
        assertArrayEquals(new long[]{1}, flat);
    }

    @Test
    public void importArgumentsAreLifted() {
        final ArrayGuestMemory memory = new ArrayGuestMemory(4096);
        final CanonicalAbi.FlatWriter writer = new CanonicalAbi.FlatWriter();
        CanonicalAbi.lowerFlat(memory, writer, new WitType.RecordType(COMPONENT_ENUMERATE.params()), new Object[]{VariantValue.some("gpu"), true});
        final long[] arguments = Arrays.copyOf(writer.toArray(), COMPONENT_ENUMERATE.importCoreParams().size());
        final Object[] lifted = CanonicalAbi.liftImportArguments(memory, COMPONENT_ENUMERATE, arguments);
        assertEquals(VariantValue.some("gpu"), lifted[0]);
        assertEquals(true, lifted[1]);
        assertNull(VariantValue.unwrapOption(VariantValue.none()));
    }

    @Test
    public void invalidGuestDataTraps() {
        final ArrayGuestMemory memory = new ArrayGuestMemory(4096);
        assertThrows(AbiException.class, () -> CanonicalAbi.liftFlat(memory, new CanonicalAbi.FlatReader(new long[]{9, 0, 0}), STEP));
        assertThrows(AbiException.class, () -> CanonicalAbi.liftFlat(memory, new CanonicalAbi.FlatReader(new long[]{4000, 1000}), WitType.Primitive.STRING));
        assertThrows(AbiException.class, () -> CanonicalAbi.liftFlat(memory, new CanonicalAbi.FlatReader(new long[]{0xD800}), WitType.Primitive.CHAR));
        // List of 8-byte aligned elements at a misaligned address.
        memory.writeInt(192, 3);
        memory.writeInt(196, 1);
        final AbiException misaligned = assertThrows(AbiException.class, () -> CanonicalAbi.load(memory, 192, WitType.list(NODE)));
        assertTrue(misaligned.getMessage().contains("misaligned"));

        memory.writeBytes(64, new byte[]{(byte) 0xC3, (byte) 0x28});
        final AbiException utf8 = assertThrows(AbiException.class, () -> CanonicalAbi.liftFlat(memory, new CanonicalAbi.FlatReader(new long[]{64, 2}), WitType.Primitive.STRING));
        assertTrue(utf8.getMessage().contains("UTF-8"));

        // List whose byte length overflows the memory.
        memory.writeInt(128, 256);
        memory.writeInt(132, 0x10000000);
        assertThrows(AbiException.class, () -> CanonicalAbi.load(memory, 128, WitType.list(NODE)));
    }
}





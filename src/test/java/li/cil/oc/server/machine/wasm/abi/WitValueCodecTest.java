package li.cil.oc.server.machine.wasm.abi;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static li.cil.oc.server.machine.wasm.abi.OcMachineWorld.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class WitValueCodecTest {
    @Test
    @SuppressWarnings("unchecked")
    public void callResultsRoundTrip() throws IOException {
        final Object tree = new Object[]{
                List.of(
                        VariantValue.of(NODE_TABLE, List.of((Object) new Object[]{1, 2})),
                        VariantValue.of(NODE_TEXT, "k\u00e9y"),
                        VariantValue.of(NODE_BYTES, new byte[]{9, 8}),
                        VariantValue.of(NODE_NUMBER, -0.25),
                        VariantValue.of(NODE_INTEGER, Long.MIN_VALUE),
                        VariantValue.of(NODE_OPAQUE, 3)),
                List.of(0, 3, 4, 5)};
        final VariantValue original = VariantValue.of(CALL_RESULT_OK, tree);

        final VariantValue decoded = (VariantValue) WitValueCodec.decode(CALL_RESULT, WitValueCodec.encode(CALL_RESULT, original));
        assertEquals(CALL_RESULT_OK, decoded.index());
        final Object[] fields = (Object[]) decoded.payload();
        final List<Object> nodes = (List<Object>) fields[0];
        assertEquals(List.of(0, 3, 4, 5), fields[1]);
        assertArrayEquals(new Object[]{1, 2}, (Object[]) ((List<Object>) ((VariantValue) nodes.get(0)).payload()).get(0));
        assertEquals(VariantValue.of(NODE_TEXT, "k\u00e9y"), nodes.get(1));
        assertArrayEquals(new byte[]{9, 8}, (byte[]) ((VariantValue) nodes.get(2)).payload());
        assertEquals(VariantValue.of(NODE_NUMBER, -0.25), nodes.get(3));
        assertEquals(VariantValue.of(NODE_INTEGER, Long.MIN_VALUE), nodes.get(4));
        assertEquals(VariantValue.of(NODE_OPAQUE, 3), nodes.get(5));

        final VariantValue error = VariantValue.of(CALL_RESULT_ERR, VariantValue.of(CALL_ERROR_FAILED, "nope"));
        assertEquals(error, WitValueCodec.decode(CALL_RESULT, WitValueCodec.encode(CALL_RESULT, error)));
    }

    @Test
    public void corruptDataIsRejected() {
        assertThrows(IOException.class, () -> WitValueCodec.decode(CALL_RESULT, new byte[]{0, 0, 0, 7}));
        assertThrows(IOException.class, () -> WitValueCodec.decode(CALL_RESULT, new byte[]{0, 0, 0, 1, 5}));
    }
}


package li.cil.oc.server.machine.wasm.abi;

/**
 * Core WebAssembly value types produced by flattening WIT types.
 * <p>
 * Flat values are carried in {@code long}s: {@code i32} values are sign
 * extended, {@code f32} values hold their raw IEEE bits in the low 32 bits and
 * {@code f64} values hold their raw IEEE bits.
 */
public enum CoreType {
    I32, I64, F32, F64
}


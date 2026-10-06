# WebAssembly shell EEPROM

A minimal interactive shell for the WebAssembly architecture. It is shipped
pre-programmed as **EEPROM (WASM Shell)** (craft an EEPROM with a Keyboard,
or take it from the creative tab), and doubles as a small example
of a guest built with `wit-bindgen` against the `oc:machine` world in `wit/`.

It draws on the first graphics card and screen it finds and supports `help`,
`clear`, `echo`, `components`, `mem`, `uptime`, `beep`, `reboot` and
`shutdown`.

Notable techniques, all to fit on an 8 KiB EEPROM:

- `#![no_std]` with a tiny allocator (`lol_alloc`), and no `core::fmt`;
- byte-string handling instead of `str` methods (which pull in Unicode
  tables);
- a call queue that handles `pending` results by returning `step::sync`, so
  no Asyncify instrumentation is needed;
- the optimized module is gzip-compressed, which the boot loader accepts.

## Building

Requires a Rust toolchain with the `wasm32-unknown-unknown` target, Binaryen
(`wasm-opt`) and `wasm-tools`.

```sh
cd src/main/wasm/shell
cargo build --release
wasm-opt target/wasm32-unknown-unknown/release/oc_wasm_shell.wasm \
  --enable-bulk-memory --enable-sign-ext --enable-nontrapping-float-to-int \
  --enable-mutable-globals --enable-multivalue --enable-reference-types \
  -Oz -o shell.wasm
# Drop custom sections such as the embedded WIT world, the host doesn't need them.
wasm-tools strip --all shell.wasm -o shell.wasm
gzip -9 -n -c shell.wasm > ../../resources/data/opencomputers/opencomputers/eeproms/wasmshell/shell.wasm.gz
```

The compressed module must not exceed the default EEPROM size (8192 bytes);
`WasmShellTest` checks this and runs the shipped binary against a simulated
GPU and screen.


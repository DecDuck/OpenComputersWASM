# WebAssembly BIOS EEPROM

The default boot loader for the WebAssembly architecture, shipped
pre-programmed as **EEPROM (WASM BIOS)** (craft an EEPROM with a Microchip
(Tier 1), or take it from the creative tab).

Like the Lua BIOS, it looks for `/init.wasm` on the boot file system (the
address stored in the EEPROM's data), then on every other file system except
the temporary one, remembers where it found it, and hands over to it with
`step::exec`. The program may be gzip-compressed.

See `docs/wasm/README.md` for how to write programs for it.

## Building

Same as the shell; see `../shell/README.md`, replacing `shell` with `bios`:

```sh
cd src/main/wasm/bios
cargo build --release
wasm-opt target/wasm32-unknown-unknown/release/oc_wasm_bios.wasm \
  --enable-bulk-memory --enable-sign-ext --enable-nontrapping-float-to-int \
  --enable-mutable-globals --enable-multivalue --enable-reference-types \
  -Oz -o bios.wasm
wasm-tools strip --all bios.wasm -o bios.wasm
gzip -9 -n -c bios.wasm > ../../resources/data/opencomputers/opencomputers/eeproms/wasmbios/bios.wasm.gz
```

`WasmBiosTest` checks that the result fits on a default EEPROM (8192 bytes)
and runs it against simulated EEPROM and file system components.


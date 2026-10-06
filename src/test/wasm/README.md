# WebAssembly test fixtures

The binaries in `src/test/resources/wasm` are generated from sources in the
repository. Regenerate them whenever the WIT files in `wit/` change.

Requirements: a Rust toolchain with the `wasm32-unknown-unknown` and
`wasm32-wasip1` targets, and
[`wasm-tools`](https://github.com/bytecodealliance/wasm-tools).

```sh
# Core modules containing every import/export of the world, with function
# bodies that trap. Used to check the host's signatures against the WIT files.
wasm-tools component embed --dummy-names legacy wit -o src/test/resources/wasm/dummy-legacy.wasm
wasm-tools component embed --dummy wit -o src/test/resources/wasm/dummy-cm32p2.wasm

# A real guest built with wit-bindgen, used to check ABI interoperability,
# once without WASI and once against WASI preview 1.
cd src/test/wasm/test-guest
cargo build --release
cargo build --release --target wasm32-wasip1
cp target/wasm32-unknown-unknown/release/oc_test_guest.wasm ../../resources/wasm/test-guest.wasm
cp target/wasm32-wasip1/release/oc_test_guest.wasm ../../resources/wasm/test-guest-wasip1.wasm

# The same guest instrumented with Asyncify (Binaryen's wasm-opt), used to
# check suspending inside host calls and persisting suspended guests, and
# variants checking that AsyncifyVerifier accepts/rejects instrumentation.
cd ../../resources/wasm
FEATURES="--enable-bulk-memory --enable-sign-ext --enable-nontrapping-float-to-int \
  --enable-mutable-globals --enable-multivalue --enable-reference-types"
wasm-opt test-guest.wasm $FEATURES --asyncify -O -o test-guest-asyncify.wasm
wasm-opt test-guest.wasm $FEATURES --asyncify \
  --pass-arg=asyncify-imports@oc:machine/component@0.1.0.invoke,oc:machine/userdata@0.1.0.invoke \
  -O -o test-guest-asyncify-list.wasm
wasm-opt test-guest.wasm $FEATURES --asyncify --pass-arg=asyncify-ignore-imports \
  -O -o test-guest-asyncify-ignore-imports.wasm
wasm-opt test-guest.wasm $FEATURES --asyncify \
  --pass-arg=asyncify-imports@oc:machine/computer@0.1.0.address \
  -O -o test-guest-asyncify-wrong-list.wasm
# Remove the asyncify_get_state export.
wasm-tools print test-guest-asyncify.wasm | grep -v 'export "asyncify_get_state"' \
  | wasm-tools parse -o test-guest-asyncify-no-get-state.wasm
```


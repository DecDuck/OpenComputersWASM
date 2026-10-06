# `oc:machine`: WebAssembly programs for OpenComputers

These WIT files define the interface between an OpenComputers computer running
the *WebAssembly* architecture and the program it runs. Generate bindings for
your language from them, for example with
[`wit-bindgen`](https://github.com/bytecodealliance/wit-bindgen).

| File | Contents |
| --- | --- |
| `world.wit` | The `machine` world: what a program imports and exports. |
| `kernel.wit` | The program's entry point, `resume(event) -> step`. |
| `types.wit` | Dynamically typed values (`value-tree`) and call results. |
| `component.wit` | Listing and calling components. |
| `userdata.wit` | Objects returned by components, such as file handles. |
| `computer.wit` | Information about and control of the computer. |
| `unicode.wit` | Text measurement. |

In short:

- A program is a **core WebAssembly module** (`wasm32-wasip1` or
  `wasm32-unknown-unknown`, built as a library) exporting
  `oc:machine/kernel@0.1.0#resume`, `memory` and `cabi_realloc`. It may be
  stored gzip-compressed.
- The computer boots the program on its EEPROM. The pre-programmed
  "EEPROM (WASM BIOS)" boots `/init.wasm` from a file system.
- `resume` is called once per event and must return quickly; component calls
  that must run on the server thread return `pending`, which is answered with
  `step::sync` (or block, for modules instrumented with Binaryen's Asyncify).
- Running programs are saved and restored with the world automatically.

The full guide, with a step-by-step example, is `docs/wasm/README.md` in the
OpenComputers repository.


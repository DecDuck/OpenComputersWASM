# Writing WebAssembly programs for OpenComputers

OpenComputers can run WebAssembly ("WASM") programs instead of Lua. This guide
explains how such programs work, how to build one, and how to get it running
on a computer in the game.

- [How it works](#how-it-works)
- [Quick start (Rust)](#quick-start-rust)
- [The execution model](#the-execution-model)
- [Talking to components](#talking-to-components)
- [Signals](#signals)
- [Userdata](#userdata)
- [Memory and limits](#memory-and-limits)
- [Persistence](#persistence)
- [Using the standard library (WASI)](#using-the-standard-library-wasi)
- [Blocking calls with Asyncify](#blocking-calls-with-asyncify)
- [Installing a program](#installing-a-program)
- [Debugging](#debugging)
- [Other languages](#other-languages)
- [Reference](#reference)

## How it works

A computer runs WebAssembly when its CPU is set to the **WebAssembly**
architecture: right-click with the CPU in hand (not sneaking) to cycle through
the available architectures.

When the computer starts, it runs the program stored on its **EEPROM**, just
like the Lua BIOS. Two pre-programmed EEPROMs are included:

| EEPROM | Recipe | What it does |
| --- | --- | --- |
| EEPROM (WASM BIOS) | EEPROM + Microchip (Tier 1) | Boots `/init.wasm` from a file system. |
| EEPROM (WASM Shell) | EEPROM + Keyboard | A tiny interactive shell, for trying things out. |

The **WASM BIOS** behaves like the Lua BIOS. It looks for `/init.wasm` on the
file system stored as the boot address (as set by `computer.setBootAddress`),
then on every other file system except the temporary one. It remembers where
it found the program as the new boot address, and then hands over to it.

A program is a **WebAssembly module** that implements the `oc:machine` *world*
defined by the [WIT files](../../wit) in this repository. The same files are
included in the mod jar under `assets/opencomputers/wit`. WIT is the interface
description language of the WebAssembly component model, and tools such as
[`wit-bindgen`](https://github.com/bytecodealliance/wit-bindgen) generate
idiomatic bindings from it for many languages.

The world has a single entry point that your program implements,
`kernel.resume`, and a few interfaces it can call:

| Interface | Purpose |
| --- | --- |
| `component` | List components, call their methods (GPU, file systems, redstone, ...). |
| `userdata` | Work with objects returned by components, such as file handles. |
| `computer` | Information about the computer: address, memory, uptime, energy, users, signals. |
| `unicode` | Text widths as the screen renders them. |
| `kernel` (exported) | Your program: `resume(event) -> step`. |

## Quick start (Rust)

Rust has the best tooling for this right now, but nothing here is
Rust-specific; see [Other languages](#other-languages).

You need a Rust toolchain with the WASI target:

```sh
rustup target add wasm32-wasip1
```

A complete version of this example lives in
[`examples/hello`](examples/hello). Create a library crate:

```toml
# Cargo.toml
[package]
name = "hello"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["cdylib"]   # a library exporting `kernel`, not a program with `main`

[dependencies]
wit-bindgen = "0.62.0"

[profile.release]
opt-level = "s"
lto = true
panic = "abort"
strip = true
```

```toml
# .cargo/config.toml
[build]
target = "wasm32-wasip1"

[target.wasm32-wasip1]
# Computers have little memory; the default 1 MiB stack would not even fit
# on the smallest RAM stick.
rustflags = ["-C", "link-arg=-zstack-size=32768"]
```

Copy the `wit` directory next to `Cargo.toml` (or point `path` at it), and
write `src/lib.rs`:

```rust
use std::sync::OnceLock;

wit_bindgen::generate!({ path: "wit", world: "machine" });

use exports::oc::machine::kernel::{Event, Guest, Step};
use oc::machine::component;
use oc::machine::types::{CallResult, Node, ValueTree};

static GPU: OnceLock<String> = OnceLock::new();

/// Builds an argument list for `component::invoke`.
fn args(nodes: Vec<Node>) -> ValueTree {
    ValueTree { roots: (0..nodes.len() as u32).collect(), nodes }
}

fn find(kind: &str) -> Option<String> {
    component::enumerate(Some(kind), true).into_iter().next().map(|entry| entry.address)
}

fn set(x: i64, y: i64, text: &str) {
    let gpu = GPU.get().expect("no gpu");
    component::invoke(gpu, "set", &args(vec![Node::Integer(x), Node::Integer(y), Node::Text(text.into())]));
}

fn draw() -> Step {
    set(1, 1, "Hello from WebAssembly!");
    set(1, 2, "Press any key to shut down.");
    Step::Wait
}

struct Hello;

impl Guest for Hello {
    fn resume(event: Event) -> Step {
        match event {
            Event::Boot => {
                let (Some(gpu), Some(screen)) = (find("gpu"), find("screen")) else {
                    return Step::Crash("no graphics card or screen".into());
                };
                let gpu = GPU.get_or_init(|| gpu);
                // `bind` has to run on the server thread: the host answers
                // `pending`, we return `sync`, and the result arrives next.
                match component::invoke(gpu, "bind", &args(vec![Node::Text(screen)])) {
                    CallResult::Pending => Step::Sync,
                    _ => draw(),
                }
            }
            Event::SyncResult(_) => draw(),
            Event::Signal(signal) if signal.name == "key_down" => Step::Shutdown(false),
            _ => Step::Wait,
        }
    }
}

export!(Hello);
```

Build it, and copy the result to `/init.wasm` on a hard drive or floppy (see
[Installing a program](#installing-a-program)):

```sh
cargo build --release
# target/wasm32-wasip1/release/hello.wasm
```

Put a WASM BIOS EEPROM, the drive, a graphics card, a screen and a keyboard
in a computer with a WebAssembly CPU, and turn it on.

## The execution model

Lua programs run as long as they like and yield inside library functions.
WebAssembly programs are instead **called by the computer** whenever something
happens:

```wit
resume: func(event: event) -> step;
```

| Event | When |
| --- | --- |
| `boot` | The first call after the computer started. |
| `signal(queued-signal)` | A signal arrived: key press, component added, network message, ... |
| `wake` | A requested sleep elapsed without any signal arriving. |
| `sync-result(call-result)` | The result of a call that returned `pending`, see below. |

Each call must **return quickly** with a step telling the computer what to do
next:

| Step | Meaning |
| --- | --- |
| `sleep(seconds)` | Call again after this time, or earlier when a signal arrives (with `wake` or `signal`). |
| `wait` | Call again when a signal arrives. |
| `sync` | Run the pending call on the server thread, then call again with `sync-result`. |
| `shutdown(reboot)` | Turn the computer off, or reboot it. |
| `crash(message)` | Stop with an error; the message is shown on the computer. |
| `exec(module)` | Replace this program with another one and boot it (used by boot loaders). |

A program that runs for longer than the configured timeout (`computer.timeout`,
5 seconds by default) without returning is stopped with "too long without
yielding". Long tasks have to be split across calls: keep your state in
memory (statics, data structures, an async executor) and continue where you
left off on the next event. Sleeping inside a call (`std::thread::sleep`)
returns immediately; return `sleep(seconds)` instead.

## Talking to components

Every component (graphics cards, screens, file systems, redstone cards,
adapters, ComputerCraft peripherals, ...) is reachable through the `component`
interface:

```wit
enumerate: func(filter: option<string>, exact: bool) -> list<entry>;  // address + type
kind: func(address: string) -> option<string>;
methods: func(address: string) -> option<list<method-info>>;
doc: func(address: string, method: string) -> option<string>;
invoke: func(address: string, method: string, args: value-tree) -> call-result;
```

The methods are the same ones Lua programs use; see the in-game manual or
`component.doc` for each component. `invoke` takes and returns
**`value-tree`s**, a flat representation of dynamically typed values:

```wit
variant node { nil, boolean(bool), integer(s64), number(f64), text(string),
               bytes(list<u8>), array(list<node-index>),
               table(list<tuple<node-index, node-index>>), opaque(handle) }
record value-tree { nodes: list<node>, roots: list<node-index> }
```

`roots` are the arguments (or results), in order; `array` and `table` nodes
refer to other nodes by index. For simple argument lists, every node is a
root, as in the `args` helper above. Values map to what component methods
receive like this:

| Node | Component sees | Notes |
| --- | --- | --- |
| `nil` | `nil` | |
| `boolean` | boolean | |
| `integer` | integer | |
| `number` | number | |
| `text` | string | |
| `bytes` | string | Binary data, e.g. file contents. |
| `array` | table with keys 1, 2, 3, ... | |
| `table` | table | `nil` keys and values are dropped. |
| `opaque` | userdata | See [Userdata](#userdata). |

Results come back the same way. Components that return Lua-style "soft
errors" return `nil` followed by a message as normal results.

### Results and errors

`invoke` returns a `call-result`:

- `ok(value-tree)`: the results;
- `err(call-error)`: `no-such-component`, `no-such-method`,
  `bad-argument(message)` or `failed(message)`;
- `pending`: see below.

### Direct calls, `pending` and `sync`

Most component methods are **direct**: they run immediately, while your
program is running. Some must run on the server thread, because they change
the world (for example `gpu.bind` or `eeprom.set`). For those, and for direct
calls once the computer's per-tick call budget is used up, `invoke` returns
**`pending`**:

1. return `step::sync` from `resume` (straight away, without making further
   calls);
2. the call runs on the server thread;
3. `resume` is called with `event::sync-result(result)`.

Only one call can be pending at a time. `methods` tells you which methods are
`direct`. During the very first `resume` (`boot`) the call budget is not
enforced, so programs can do their setup quickly.

The [WASM shell](../../src/main/wasm/shell/src/lib.rs) shows a small
pattern for this: it queues component calls and works through the queue,
returning `sync` whenever a call is pending and continuing with the rest when
the result arrives.

## Signals

Signals are delivered as `event::signal` with a name and a `value-tree` of
arguments, exactly as Lua's `computer.pullSignal` would return them. Common
ones:

| Signal | Arguments |
| --- | --- |
| `key_down` / `key_up` | keyboard address, character (number), key code (number), player name |
| `clipboard` | keyboard address, text, player name |
| `touch` / `drag` / `drop` / `scroll` | screen address, x, y, button or direction, player name |
| `component_added` / `component_removed` | address, type |
| `modem_message` | receiver address, sender address, port, distance, payload... |

Programs can also queue signals themselves with `computer.push-signal`.

## Userdata

Some methods return objects instead of plain values, for example
`filesystem.open` returns a file handle. These arrive as `opaque(handle)`
nodes. Pass them back as `opaque` nodes in later calls (`read`, `write`,
`close`), or use the `userdata` interface to call methods on them directly.

Handles stay valid until you call `userdata.release(handle)` or the computer
stops. Release handles you no longer need: the host keeps the objects alive
until then.

## Memory and limits

- A program's **linear memory** is limited by the RAM installed in the
  computer (times `computer.wasm.memoryScale`), rounded down to whole 64 KiB
  pages. The module's initial memory must fit, so keep the stack small (see
  the `.cargo/config.toml` above); `memory.grow` fails once the limit is
  reached.
- **`computer.free-memory`** and **`total-memory`** report how much more the
  program may grow, and the limit.
- Modules may be at most `computer.wasm.maxModuleSize` bytes (4 MiB by
  default), after decompression.
- The WASM BIOS reads `/init.wasm` into its own memory before handing over,
  so the computer needs at least that much RAM to boot it. Compressing the
  module (see below) helps.
- The program's machine code is not counted against RAM; only its linear
  memory is.

## Persistence

Running computers keep running across chunk unloads and server restarts. The
host saves your module, all of its linear memory, its globals, and its
pending calls and userdata handles, and restores them later. Your program
never notices: it simply receives its next event.

This works because programs only run inside `resume`. There is nothing to opt
into, but keep everything that matters in linear memory; state kept in
host-side resources such as open network connections can still go away, as it
can for Lua programs.

## Using the standard library (WASI)

Programs built for `wasm32-wasip1` can use the standard library: printing,
panic messages, `HashMap`, `Instant`, `SystemTime` and so on. The host
provides a sandboxed [WASI](https://wasi.dev) preview 1 environment:

- `stdout` and `stderr` are captured. The last lines written to `stderr`, such
  as a panic message, become the crash message shown on the computer. Server
  owners can enable `computer.wasm.logOutput` to also see them in the server
  log.
- `stdin` is always at end of file; there are no command line arguments,
  environment variables or file systems. Use the `filesystem` component for
  files.
- Blocking calls do not block: sleeping returns immediately.
- `std::process::exit(0)` shuts the computer down; other exit codes crash it.

Programs built for `wasm32-unknown-unknown` (no WASI) work as well, and are
smaller. Servers can disable WASI with `computer.wasm.wasi`, in which case only
those work.

Components (`wasm32-wasip2`) are not supported; build a core module instead.

## Blocking calls with Asyncify

Handling `pending` by hand gets tedious in bigger programs. Optionally, you can
post-process your module with [Binaryen](https://github.com/WebAssembly/binaryen)'s
Asyncify pass:

```sh
wasm-opt hello.wasm --asyncify \
  --pass-arg=asyncify-imports@oc:machine/component@0.1.0.invoke,oc:machine/userdata@0.1.0.invoke \
  -O -o hello.wasm
```

Add `--enable-...` flags for features your compiler uses (for current Rust:
`--enable-bulk-memory --enable-sign-ext --enable-nontrapping-float-to-int
--enable-mutable-globals --enable-multivalue --enable-reference-types`).

The host detects instrumented modules and then **never returns `pending`**:
calls that have to run on the server thread pause your program inside
`invoke`, and `invoke` returns the actual result once it is available, as if it
had blocked. Paused programs are persisted like any other.

The host checks instrumented modules when loading them and refuses to run them
unless every call that can reach `component.invoke` or `userdata.invoke` was
instrumented. Listing exactly those two imports, as above, keeps the size and
speed overhead low. Asyncify does not help with long computations: a program
that runs too long without calling into the host is still stopped.

The paused call stack is stored in a buffer in your memory, sized by
`computer.wasm.asyncifyBufferSize`; very deep call stacks need a bigger one.

## Installing a program

**On a disk.** Save the module as `/init.wasm` on a hard drive or floppy:

- In single player or with access to the server's files, copy it into the
  world save: each drive's files are stored in
  `<world>/opencomputers/<drive address>/`. The address is shown when you
  analyze the drive.
- From an OpenOS computer, download it, e.g. with `wget <url> /mnt/xyz/init.wasm`
  using an internet card.

**On an EEPROM.** Small programs can go straight onto an EEPROM instead of the
BIOS: from OpenOS, run `flash -q program.wasm "My Program"`. EEPROMs hold
8 KiB by default (`computer.eepromSize`).

**Compression.** Modules may be stored gzip-compressed, which typically halves
their size:

```sh
wasm-opt hello.wasm -Oz -o hello.wasm      # optional: optimize for size
wasm-tools strip --all hello.wasm -o hello.wasm  # optional: drop metadata
gzip -9 -n -c hello.wasm > init.wasm
```

Both the WASM BIOS and EEPROMs accept compressed modules. The two included
EEPROMs are built this way, without the standard library, to fit in 8 KiB; see
[`src/main/wasm`](../../src/main/wasm).

## Debugging

| Message | Cause |
| --- | --- |
| no bootable medium found | The WASM BIOS found no `/init.wasm`. |
| no EEPROM installed | Add an EEPROM, such as the WASM BIOS. |
| the EEPROM does not contain a WebAssembly program | Wrong EEPROM (e.g. the Lua BIOS) for a WebAssembly CPU. |
| not enough memory: module needs ... | Install more RAM, or shrink the module's initial memory (stack size). |
| too long without yielding | `resume` did not return within `computer.timeout`. |
| module is a command (it has a main function) | Build a library (`crate-type = ["cdylib"]`) that exports `kernel`. |
| WASI is not available | The module imports WASI, but the server disabled it. |
| unknown import / unimplemented import | The module imports something that is not part of the `oc:machine` world (check the WIT version). |
| module is not correctly instrumented with Asyncify | See [Blocking calls with Asyncify](#blocking-calls-with-asyncify). |
| trap: ... / a panic message | The program crashed; with WASI, panic messages are shown. |

Tips:

- With WASI, `eprintln!` debugging works: the last lines appear as the crash
  message, and in the server log if `computer.wasm.logOutput` is enabled.
- The guest-side logic can be tested outside the game by implementing the
  imported functions yourself, for example with a WebAssembly runtime such as
  wasmtime. The OpenComputers test suite does exactly that with
  [Chicory](https://github.com/dylibso/chicory); see
  `src/test/java/li/cil/oc/server/machine/wasm`.

## Other languages

Any language that can produce a core WebAssembly module following the
[canonical ABI](https://github.com/WebAssembly/component-model/blob/main/design/mvp/CanonicalABI.md)
for the `oc:machine` world works:

- **C/C++**: `wit-bindgen c` generates bindings; compile with
  [wasi-sdk](https://github.com/WebAssembly/wasi-sdk) for `wasm32-wasip1`, as a
  reactor without `main` (`-mexec-model=reactor`).
- **Other languages** with `wit-bindgen` support work the same way.
- **Languages without WIT tooling** (TinyGo, Zig, AssemblyScript, ...) can
  implement the ABI by hand. Generate a template of the exact imports and
  exports with `wasm-tools component embed --dummy-names legacy wit -o dummy.wasm`
  and inspect it with `wasm-tools print dummy.wasm`.

The module must export `memory`, `cabi_realloc` and
`oc:machine/kernel@0.1.0#resume` (and may export `_initialize`, which runs
first), and may only import functions from the `oc:machine` interfaces and,
optionally, `wasi_snapshot_preview1`.

## Reference

- [WIT files](../../wit): the complete API, with documentation comments.
- Examples:
  - [`examples/hello`](examples/hello): the quick start example;
  - [`src/main/wasm/bios`](../../src/main/wasm/bios): the WASM BIOS, a boot loader;
  - [`src/main/wasm/shell`](../../src/main/wasm/shell): the WASM shell, an interactive program.

Server configuration (`computer.wasm` section, plus related settings):

| Setting | Default | Meaning |
| --- | --- | --- |
| `computer.wasm.enable` | `true` | Make the WebAssembly architecture available. |
| `computer.wasm.memoryScale` | `1.0` | Multiplier from installed RAM to linear memory. |
| `computer.wasm.maxModuleSize` | `4194304` | Maximum module size in bytes, after decompression. |
| `computer.wasm.wasi` | `true` | Provide the WASI preview 1 sandbox. |
| `computer.wasm.logOutput` | `false` | Write programs' stdout/stderr to the server log. |
| `computer.wasm.asyncifyBufferSize` | `16384` | Size of the buffer for paused call stacks (Asyncify). |
| `computer.timeout` | `5.0` | Seconds a program may run without returning. |
| `computer.eepromSize` | `8192` | EEPROM capacity in bytes. |
| `computer.lua.maxTotalRam` | 64 MiB | Upper limit for any computer's memory, also applies to WebAssembly. |



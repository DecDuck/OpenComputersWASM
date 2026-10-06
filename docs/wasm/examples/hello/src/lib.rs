//! Prints a greeting on the screen and shuts down when a key is pressed.
//! See docs/wasm/README.md for a walkthrough.

use std::sync::OnceLock;

wit_bindgen::generate!({
    // The WIT files from the OpenComputers repository (or the mod jar's
    // assets/opencomputers/wit directory).
    path: "../../../../wit",
    world: "machine",
});

use exports::oc::machine::kernel::{Event, Guest, Step};
use oc::machine::component;
use oc::machine::types::{CallResult, Node, ValueTree};

/// Address of the graphics card, found while booting.
static GPU: OnceLock<String> = OnceLock::new();

/// Builds an argument list for `component::invoke`.
fn args(nodes: Vec<Node>) -> ValueTree {
    ValueTree {
        roots: (0..nodes.len() as u32).collect(),
        nodes,
    }
}

/// The address of the first component of the given type.
fn find(kind: &str) -> Option<String> {
    component::enumerate(Some(kind), true).into_iter().next().map(|entry| entry.address)
}

/// Writes text at a position on the screen. `set` is a direct callback, so
/// it completes immediately (unless the call budget for this tick is used up).
fn set(x: i64, y: i64, text: &str) {
    let gpu = GPU.get().expect("no gpu");
    let result = component::invoke(gpu, "set", &args(vec![Node::Integer(x), Node::Integer(y), Node::Text(text.into())]));
    if let CallResult::Err(error) = result {
        eprintln!("gpu.set failed: {error:?}");
    }
}

fn draw() -> Step {
    set(1, 1, "Hello from WebAssembly!");
    set(1, 2, "Press any key to shut down.");
    // Nothing else to do until a signal arrives.
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
                // `bind` is not a direct callback: it has to run on the server
                // thread. The host answers `pending`, we return `step::sync`,
                // and the result arrives in the next resume.
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


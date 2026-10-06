//! Guest used by `WasmGuestTest` to check that the host's canonical ABI
//! implementation interoperates with `wit-bindgen` generated code.
//!
//! The scenario each event triggers is part of the test's expectations, so
//! keep the two in sync.

wit_bindgen::generate!({
    path: "../../../../wit",
    world: "machine",
});

use exports::oc::machine::kernel::{Event, Guest, Step};
use oc::machine::types::{CallError, CallResult, Node, ValueTree};
use oc::machine::{component, computer, unicode, userdata};
use std::sync::atomic::{AtomicU32, Ordering};

static WAKES: AtomicU32 = AtomicU32::new(0);

struct TestGuest;

/// One node of every kind, all of them roots.
fn sample(address: String, count: i64) -> ValueTree {
    ValueTree {
        nodes: vec![
            Node::Text(address),
            Node::Integer(count),
            Node::Array(vec![0, 1]),
            Node::Table(vec![(0, 1), (6, 2)]),
            Node::Number(1.5),
            Node::Bytes(vec![1, 2, 3]),
            Node::Boolean(true),
            Node::Nil,
            Node::Opaque(7),
        ],
        roots: vec![0, 1, 2, 3, 4, 5, 6, 7, 8],
    }
}

fn describe(error: CallError) -> String {
    match error {
        CallError::NoSuchComponent => "no such component".into(),
        CallError::NoSuchMethod => "no such method".into(),
        CallError::BadArgument(message) => format!("bad argument: {message}"),
        CallError::Failed(message) => format!("failed: {message}"),
    }
}

/// Uses standard library features backed by WASI preview 1.
#[cfg(target_os = "wasi")]
fn wasi_checks() -> Step {
    use std::io::BufRead;
    use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

    println!("hello stdout");
    eprintln!("hello stderr");

    // Must return immediately instead of blocking the host.
    let start = Instant::now();
    std::thread::sleep(Duration::from_secs(60));
    let quick = start.elapsed() < Duration::from_secs(10);

    // RandomState seeds itself through random_get.
    let mut map = std::collections::HashMap::new();
    map.insert("key", 1);

    let recent = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|since| since.as_secs() > 1_600_000_000)
        .unwrap_or(false);

    let mut line = String::new();
    let eof = std::io::stdin().lock().read_line(&mut line).map(|n| n == 0).unwrap_or(false);

    Step::Crash(format!("wasi ok {quick} {recent} {eof} {}", map.len()))
}

impl Guest for TestGuest {
    fn resume(event: Event) -> Step {
        match event {
            // Calls a few imports, then echoes a sample tree through the first
            // listed component. Direct results sleep for as many seconds as
            // there are result roots; pending results request a sync.
            Event::Boot => {
                let address = computer::address();
                let entries = component::enumerate(None, false);
                let width = unicode::string_width("a\u{4e00}");
                let tree = sample(address, entries.len() as i64 * 100 + width as i64);
                match component::invoke(&entries[0].address, "echo", &tree) {
                    CallResult::Ok(result) => Step::Sleep(result.roots.len() as f64),
                    CallResult::Pending => Step::Sync,
                    CallResult::Err(error) => Step::Crash(describe(error)),
                }
            }
            Event::SyncResult(CallResult::Ok(result)) => Step::Shutdown(result.nodes.len() == 1),
            Event::SyncResult(CallResult::Err(error)) => Step::Crash(describe(error)),
            Event::SyncResult(CallResult::Pending) => Step::Crash("unexpected pending".into()),
            // Reports the signal's name and size, then releases handle 7.
            // Some signal names trigger special scenarios instead.
            Event::Signal(signal) => match signal.name.as_str() {
                #[cfg(target_os = "wasi")]
                "wasi" => wasi_checks(),
                #[cfg(target_os = "wasi")]
                "exit" => std::process::exit(3),
                "panic" => panic!("boom"),
                _ => {
                    userdata::release(7);
                    Step::Crash(format!("{}:{}:{}", signal.name, signal.args.nodes.len(), signal.args.roots.len()))
                }
            },
            Event::Wake => match computer::tmp_address() {
                Some(address) => Step::Crash(address),
                // State in linear memory, to check that it survives snapshots.
                None => Step::Sleep((WAKES.fetch_add(1, Ordering::Relaxed) + 1) as f64),
            },
        }
    }
}

export!(TestGuest);


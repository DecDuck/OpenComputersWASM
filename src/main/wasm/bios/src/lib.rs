 //! Default boot loader for OpenComputers' WebAssembly architecture, shipped
//! as the pre-programmed "EEPROM (WASM BIOS)".
//!
//! It behaves like the Lua BIOS: it looks for `/init.wasm` (a WebAssembly
//! module, optionally gzip-compressed) on the file system whose address is
//! stored in the EEPROM's data (the boot address), then on every other file
//! system except the temporary one. It remembers where it found the module
//! as the new boot address, and hands over to it with `step::exec`.
//!
//! Everything happens while handling `event::boot`, which runs in the
//! machine's initialization run: call budgets are not enforced there, so the
//! direct file system callbacks never return `pending`. Only writing the boot
//! address is a non-direct call that needs `step::sync`.
//!
//! The module is buffered in linear memory before handing over, so the
//! machine needs at least that much RAM; compress big modules.

#![no_std]

extern crate alloc;

use alloc::string::String;
use alloc::vec;
use alloc::vec::Vec;

wit_bindgen::generate!({
    path: "../../../../wit",
    world: "machine",
});

use exports::oc::machine::kernel::{Event, Guest, Step};
use oc::machine::types::{CallError, CallResult, Node, ValueTree};
use oc::machine::{component, computer, userdata};

#[global_allocator]
static ALLOCATOR: lol_alloc::AssumeSingleThreaded<lol_alloc::FreeListAllocator> =
    unsafe { lol_alloc::AssumeSingleThreaded::new(lol_alloc::FreeListAllocator::new()) };

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}

const INIT: &str = "/init.wasm";

/// The module found during boot, kept while the boot address is written.
static mut FOUND: Option<Vec<u8>> = None;

fn arguments(nodes: Vec<Node>) -> ValueTree {
    ValueTree {
        roots: (0..nodes.len() as u32).collect(),
        nodes,
    }
}

fn text(value: &str) -> Node {
    Node::Text(String::from(value))
}

/// Invokes a callback and returns its results, or the reason it failed.
fn call(address: &str, method: &str, args: Vec<Node>) -> Result<ValueTree, String> {
    match component::invoke(address, method, &arguments(args)) {
        CallResult::Ok(results) => Ok(results),
        CallResult::Pending => Err(String::from("unexpected pending call")),
        CallResult::Err(CallError::BadArgument(reason) | CallError::Failed(reason)) => Err(reason),
        CallResult::Err(_) => Err(String::from("call failed")),
    }
}

/// The first result of a call.
fn first(results: &ValueTree) -> Option<&Node> {
    results.nodes.get(*results.roots.first()? as usize)
}

/// Results of the `(nil, reason)` form are soft errors.
fn reason(results: &ValueTree) -> String {
    match results.roots.get(1).and_then(|&index| results.nodes.get(index as usize)) {
        Some(Node::Text(reason)) => reason.clone(),
        _ => String::from("unknown error"),
    }
}

fn read_init(address: &str) -> Result<Vec<u8>, String> {
    let opened = call(address, "open", vec![text(INIT), text("rb")])?;
    let handle = match first(&opened) {
        Some(Node::Opaque(handle)) => *handle,
        _ => return Err(reason(&opened)),
    };
    let mut module = Vec::new();
    let result = loop {
        let chunk = match call(address, "read", vec![Node::Opaque(handle), Node::Integer(i32::MAX as i64)]) {
            Ok(chunk) => chunk,
            Err(reason) => break Err(reason),
        };
        match first(&chunk) {
            Some(Node::Bytes(bytes)) => module.extend_from_slice(bytes),
            Some(Node::Text(text)) => module.extend_from_slice(text.as_bytes()),
            _ => break Ok(()),
        }
    };
    let _ = call(address, "close", vec![Node::Opaque(handle)]);
    userdata::release(handle);
    result.map(|_| module)
}

fn boot() -> Step {
    let eeprom = component::enumerate(Some("eeprom"), true).into_iter().next().map(|entry| entry.address);
    let boot_address = eeprom.as_deref().and_then(|eeprom| {
        let data = call(eeprom, "getData", Vec::new()).ok()?;
        match first(&data)? {
            Node::Bytes(bytes) => String::from_utf8(bytes.clone()).ok(),
            Node::Text(text) => Some(text.clone()),
            _ => None,
        }
    });
    let tmp = computer::tmp_address();

    // The boot address first, then all other file systems.
    let mut candidates: Vec<String> = component::enumerate(Some("filesystem"), true)
        .into_iter()
        .map(|entry| entry.address)
        .filter(|address| Some(address) != tmp.as_ref())
        .collect();
    if let Some(index) = candidates.iter().position(|address| Some(address) == boot_address.as_ref()) {
        let preferred = candidates.remove(index);
        candidates.insert(0, preferred);
    }

    let mut last_error = None;
    for address in candidates {
        match read_init(&address) {
            Ok(module) => {
                if boot_address.as_ref() != Some(&address) {
                    if let Some(eeprom) = &eeprom {
                        // Writing EEPROM data has to run on the server thread.
                        let data = arguments(vec![Node::Text(address)]);
                        if let CallResult::Pending = component::invoke(eeprom, "setData", &data) {
                            unsafe { *core::ptr::addr_of_mut!(FOUND) = Some(module) };
                            return Step::Sync;
                        }
                    }
                }
                return Step::Exec(module);
            }
            Err(error) => last_error = Some(error),
        }
    }

    let mut message = String::from("no bootable medium found");
    if let Some(error) = last_error {
        message.push_str(": ");
        message.push_str(&error);
    }
    Step::Crash(message)
}

struct Bios;

impl Guest for Bios {
    fn resume(event: Event) -> Step {
        match event {
            Event::Boot => boot(),
            // The boot address was written, hand over to the module.
            Event::SyncResult(_) => match unsafe { (*core::ptr::addr_of_mut!(FOUND)).take() } {
                Some(module) => Step::Exec(module),
                None => Step::Crash(String::from("nothing to boot")),
            },
            _ => Step::Wait,
        }
    }
}

export!(Bios);


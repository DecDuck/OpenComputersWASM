//! A minimal interactive shell for OpenComputers' WebAssembly architecture.
//!
//! Shipped as the pre-programmed "EEPROM (WASM Shell)". It draws on the first
//! graphics card and screen it finds, and reads input from `key_down` signals.
//!
//! Every component call goes through a queue. Calls that have to run on the
//! server thread (such as binding the GPU), or that exceed the call budget,
//! return `pending`: the shell then returns `step::sync` and continues with the
//! queue once the result arrives as `event::sync-result`. All state lives in
//! linear memory, so a running shell survives chunk reloads.
//!
//! Built without `std` and without `core::fmt` to fit on an 8 KiB EEPROM; see
//! README.md for how to build it.

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
use oc::machine::types::{CallResult, Node, ValueTree};
use oc::machine::{component, computer};

#[global_allocator]
static ALLOCATOR: lol_alloc::AssumeSingleThreaded<lol_alloc::FreeListAllocator> =
    unsafe { lol_alloc::AssumeSingleThreaded::new(lol_alloc::FreeListAllocator::new()) };

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}

const BANNER: &[u8] = b"WebAssembly shell\nType 'help' for a list of commands.";
const HELP: &[u8] = b"help clear echo components mem uptime\nbeep reboot shutdown";

/// What to do with the result of a queued call.
#[derive(Clone, Copy, PartialEq)]
enum Then {
    Nothing,
    /// The result is the screen resolution; draw the initial screen.
    Resolution,
}

struct Call {
    target: String,
    method: &'static str,
    args: ValueTree,
    then: Then,
}

struct Shell {
    gpu: String,
    width: i64,
    height: i64,
    /// Row the prompt is on, starting at 1.
    row: i64,
    /// Current input line; ASCII only, which keeps string handling small.
    input: Vec<u8>,
    queue: Vec<Call>,
    /// Index of the next call in `queue` to perform.
    next: usize,
}

static mut SHELL: Shell = Shell {
    gpu: String::new(),
    width: 50,
    height: 16,
    row: 1,
    input: Vec::new(),
    queue: Vec::new(),
    next: 0,
};

fn shell() -> &'static mut Shell {
    // The host never calls into the guest concurrently.
    unsafe { &mut *core::ptr::addr_of_mut!(SHELL) }
}

fn int(value: i64) -> Node {
    Node::Integer(value)
}

fn text(value: &[u8]) -> Node {
    Node::Text(string(value))
}

/// Strings handled by the shell are built from ASCII and valid UTF-8 slices.
fn string(bytes: &[u8]) -> String {
    unsafe { String::from_utf8_unchecked(Vec::from(bytes)) }
}

/// A `value-tree` whose nodes are all roots, i.e. a plain argument list.
fn arguments(nodes: Vec<Node>) -> ValueTree {
    ValueTree {
        roots: (0..nodes.len() as u32).collect(),
        nodes,
    }
}

/// The `index`th value of a tree, as an integer.
fn number(tree: &ValueTree, index: usize) -> Option<i64> {
    match tree.nodes.get(*tree.roots.get(index)? as usize)? {
        Node::Integer(value) => Some(*value),
        Node::Number(value) => Some(*value as i64),
        _ => None,
    }
}

fn push_int(out: &mut Vec<u8>, mut value: u64) {
    let mut digits = [0u8; 20];
    let mut start = digits.len();
    loop {
        start -= 1;
        digits[start] = b'0' + (value % 10) as u8;
        value /= 10;
        if value == 0 {
            break;
        }
    }
    out.extend_from_slice(&digits[start..]);
}

fn trim(mut bytes: &[u8]) -> &[u8] {
    while let [b' ', rest @ ..] = bytes {
        bytes = rest;
    }
    while let [rest @ .., b' '] = bytes {
        bytes = rest;
    }
    bytes
}

fn first(kind: &str) -> Option<String> {
    component::enumerate(Some(kind), true).into_iter().next().map(|entry| entry.address)
}

impl Shell {
    fn call(&mut self, target: String, method: &'static str, args: Vec<Node>, then: Then) {
        self.queue.push(Call { target, method, args: arguments(args), then });
    }

    fn gpu(&mut self, method: &'static str, args: Vec<Node>) {
        self.call(self.gpu.clone(), method, args, Then::Nothing);
    }

    fn set(&mut self, row: i64, line: &[u8]) {
        self.gpu("set", vec![int(1), int(row), text(line)]);
    }

    fn clear(&mut self, row: i64, rows: i64) {
        self.gpu("fill", vec![int(1), int(row), int(self.width), int(rows), text(b" ")]);
    }

    /// Moves to the next row, scrolling if at the bottom of the screen.
    fn newline(&mut self) {
        if self.row < self.height {
            self.row += 1;
        } else {
            self.gpu("copy", vec![int(1), int(2), int(self.width), int(self.height - 1), int(0), int(-1)]);
            self.clear(self.height, 1);
        }
    }

    fn print(&mut self, output: &[u8]) {
        for line in output.split(|&b| b == b'\n') {
            self.set(self.row, line);
            self.newline();
        }
    }

    fn prompt(&mut self, cursor: bool) {
        let mut line = Vec::from(&b"> "[..]);
        line.extend_from_slice(&self.input);
        line.extend_from_slice(if cursor { b"_ " } else { b"  " });
        self.set(self.row, &line);
    }

    fn boot(&mut self) -> Step {
        match (first("gpu"), first("screen")) {
            (Some(gpu), Some(screen)) => {
                self.gpu = gpu;
                self.gpu("bind", vec![Node::Text(screen)]);
                self.call(self.gpu.clone(), "getResolution", Vec::new(), Then::Resolution);
                self.run()
            }
            _ => Step::Crash(String::from("no graphics card or screen")),
        }
    }

    fn key(&mut self, character: i64, code: i64) -> Option<Step> {
        if character == 13 || code == 28 {
            self.prompt(false);
            self.newline();
            let line = core::mem::take(&mut self.input);
            let step = self.command(trim(&line));
            self.prompt(true);
            return step;
        }
        if character == 8 || code == 14 {
            self.input.pop();
        } else if (32..127).contains(&character) {
            self.input.push(character as u8);
        } else {
            return None;
        }
        self.prompt(true);
        None
    }

    fn command(&mut self, line: &[u8]) -> Option<Step> {
        let (name, rest) = match line.iter().position(|&b| b == b' ') {
            Some(i) => (&line[..i], trim(&line[i + 1..])),
            None => (line, &b""[..]),
        };
        let mut out = Vec::new();
        match name {
            b"" => return None,
            b"help" => out.extend_from_slice(HELP),
            b"clear" => {
                self.clear(1, self.height);
                self.row = 1;
                return None;
            }
            b"echo" => out.extend_from_slice(rest),
            b"components" => {
                for entry in component::enumerate(None, false) {
                    let address = entry.address.as_bytes();
                    out.extend_from_slice(&address[..address.len().min(8)]);
                    out.push(b' ');
                    out.extend_from_slice(entry.kind.as_bytes());
                    out.push(b'\n');
                }
                out.pop();
            }
            b"mem" => {
                push_int(&mut out, computer::free_memory() / 1024);
                out.extend_from_slice(b" / ");
                push_int(&mut out, computer::total_memory() / 1024);
                out.extend_from_slice(b" KiB free");
            }
            b"uptime" => {
                push_int(&mut out, computer::uptime() as u64);
                out.extend_from_slice(b" s");
            }
            b"beep" => {
                self.call(computer::address(), "beep", vec![Node::Number(1000.0), Node::Number(0.2)], Then::Nothing);
                return None;
            }
            b"reboot" => return Some(Step::Shutdown(true)),
            b"shutdown" => return Some(Step::Shutdown(false)),
            _ => {
                out.extend_from_slice(b"unknown command: ");
                out.extend_from_slice(name);
            }
        }
        self.print(&out);
        None
    }

    /// Records the result of the call at the front of the queue.
    fn complete(&mut self, result: Option<ValueTree>) {
        let then = self.queue[self.next].then;
        self.next += 1;
        if then == Then::Resolution {
            if let Some(tree) = result {
                if let (Some(width), Some(height)) = (number(&tree, 0), number(&tree, 1)) {
                    self.width = width;
                    self.height = height;
                }
            }
            self.clear(1, self.height);
            self.row = 1;
            self.print(BANNER);
            self.prompt(true);
        }
    }

    /// Performs queued calls until the queue is empty or a call is pending.
    fn run(&mut self) -> Step {
        while self.next < self.queue.len() {
            let result = {
                let call = &self.queue[self.next];
                component::invoke(&call.target, call.method, &call.args)
            };
            match result {
                CallResult::Pending => return Step::Sync,
                CallResult::Ok(tree) => self.complete(Some(tree)),
                CallResult::Err(_) => self.complete(None),
            }
        }
        self.queue.clear();
        self.next = 0;
        Step::Wait
    }
}

struct Kernel;

impl Guest for Kernel {
    fn resume(event: Event) -> Step {
        let shell = shell();
        match event {
            Event::Boot => return shell.boot(),
            Event::SyncResult(result) => shell.complete(match result {
                CallResult::Ok(tree) => Some(tree),
                _ => None,
            }),
            Event::Signal(signal) => {
                if signal.name == "key_down" {
                    let character = number(&signal.args, 1).unwrap_or(0);
                    let code = number(&signal.args, 2).unwrap_or(0);
                    if let Some(step) = shell.key(character, code) {
                        return step;
                    }
                }
            }
            Event::Wake => {}
        }
        shell.run()
    }
}

export!(Kernel);





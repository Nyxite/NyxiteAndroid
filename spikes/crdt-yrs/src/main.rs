// Spike P0.1-CORE-1: is yrs, the CRDT core the Android client wraps, byte-compatible with Yjs?
// It reads the reference vectors and the large-document expectation that Yjs produced (NyxiteWeb/spikes/crdt-vectors).
// The same program is cross-compiled for the Android ABIs and run on a device, see README.md.
// usage: crdt-yrs-spike [vector-directory]
use std::collections::BTreeMap;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::Instant;

use serde_json::Value;
use sha2::{Digest, Sha256};
use yrs::updates::decoder::Decode;
use yrs::updates::encoder::Encode;
use yrs::{Any, Doc, GetString, Map, OffsetKind, Options, Out, ReadTxn, StateVector, Text, Transact, Update};

struct Report {
    failed: u32,
}

impl Report {
    fn check(&mut self, name: &str, ok: bool) {
        if !ok {
            self.failed += 1;
        }
        println!("{}  {}", if ok { "pass" } else { "FAIL" }, name);
    }
}

/// Yjs counts positions in UTF-16 code units, so the document must too. The default of yrs is UTF-8 bytes.
fn new_doc(client_id: u64) -> Doc {
    let mut options = Options::with_client_id(yrs::block::ClientID::new(client_id));
    options.offset_kind = OffsetKind::Utf16;
    Doc::with_options(options)
}

fn var_uint(bytes: &[u8], at: &mut usize) -> Option<u64> {
    let mut value = 0u64;
    let mut shift = 0;
    loop {
        let b = *bytes.get(*at)?;
        *at += 1;
        if shift > 63 {
            return None;
        }
        value |= ((b & 0x7f) as u64) << shift;
        if b < 0x80 {
            return Some(value);
        }
        shift += 7;
    }
}

/// A state vector and the delete set at the end of an update are sets of per-client entries whose order on the wire
/// is not defined, so they are compared as sets and everything else byte for byte.
fn client_entries(bytes: &[u8], mut at: usize, delete_set: bool) -> Option<BTreeMap<u64, Vec<u8>>> {
    let mut entries = BTreeMap::new();
    let clients = var_uint(bytes, &mut at)?;
    for _ in 0..clients {
        let client = var_uint(bytes, &mut at)?;
        let start = at;
        let count = if delete_set { var_uint(bytes, &mut at)? * 2 } else { 1 };
        for _ in 0..count {
            var_uint(bytes, &mut at)?;
        }
        if entries.insert(client, bytes[start..at].to_vec()).is_some() {
            return None;
        }
    }
    if at == bytes.len() { Some(entries) } else { None }
}

fn same_state_vector(a: &[u8], b: &[u8]) -> bool {
    matches!((client_entries(a, 0, false), client_entries(b, 0, false)), (Some(x), Some(y)) if x == y)
}

/// Same structs byte for byte and the same delete set. The delete set starts inside the common prefix of the two
/// updates, because the structs before it are identical, so that offset is searched from the end of the prefix.
fn same_update(a: &[u8], b: &[u8]) -> bool {
    if a == b {
        return true;
    }
    if a.len() != b.len() {
        return false;
    }
    let prefix = a.iter().zip(b).take_while(|(x, y)| x == y).count();
    (0..=prefix).rev().any(|at| matches!((client_entries(a, at, true), client_entries(b, at, true)), (Some(x), Some(y)) if x == y))
}

fn full_state(doc: &Doc) -> Vec<u8> {
    doc.transact().encode_state_as_update_v1(&StateVector::default())
}

fn apply(doc: &Doc, update: &[u8]) {
    doc.transact_mut().apply_update(Update::decode_v1(update).expect("update decodes")).expect("update applies");
}

fn main() {
    let dir = std::env::args().nth(1).unwrap_or_else(|| "../../../NyxiteWeb/spikes/crdt-vectors".into());
    let dir = Path::new(&dir);
    let mut report = Report { failed: 0 };

    // ---- 1. Apply the updates Yjs produced and compare the merged document.
    let vectors: Value = serde_json::from_str(&std::fs::read_to_string(dir.join("crdt-vectors.json")).expect("vectors")).expect("json");
    println!("reference: {}, {}", vectors["engine"].as_str().unwrap(), vectors["encoding"].as_str().unwrap());
    let cases = vectors["cases"].as_array().unwrap();
    for case in cases {
        let name = case["name"].as_str().unwrap();
        let updates: Vec<Vec<u8>> = case["updates"].as_array().unwrap().iter().map(|u| hex::decode(u.as_str().unwrap()).unwrap()).collect();
        let doc = new_doc(999);
        let text = doc.get_or_insert_text("content");
        let map = doc.get_or_insert_map("meta");
        for index in case["applyOrder"].as_array().unwrap() {
            apply(&doc, &updates[index.as_u64().unwrap() as usize]);
        }
        {
            let txn = doc.transact();
            report.check(&format!("{name}: text"), text.get_string(&txn) == case["text"].as_str().unwrap());
            for (key, expected) in case["map"].as_object().unwrap() {
                let same = match map.get(&txn, key) {
                    Some(Out::Any(Any::String(s))) => Some(&*s) == expected.as_str(),
                    Some(Out::Any(Any::Number(yrs::any::Number::Int(n)))) => Some(n) == expected.as_i64(),
                    Some(Out::Any(Any::Number(yrs::any::Number::Float(n)))) => Some(n) == expected.as_f64(),
                    _ => false,
                };
                report.check(&format!("{name}: map.{key}"), same);
            }
            let vector = txn.state_vector().encode_v1();
            report.check(&format!("{name}: state vector"), same_state_vector(&vector, &hex::decode(case["stateVector"].as_str().unwrap()).unwrap()));
        }
        let merged = full_state(&doc);
        let reference = hex::decode(case["mergedUpdate"].as_str().unwrap()).unwrap();
        let how = if merged == reference { "byte-identical" } else { "identical except for the order of the delete set" };
        report.check(&format!("{name}: merged update, {how}"), same_update(&merged, &reference));
    }

    // ---- 2. Make the same edits here and compare the updates yrs emits with the ones Yjs emitted.
    {
        let mut produce = |name: &str, edits: Vec<Box<dyn Fn(&yrs::TextRef, &mut yrs::TransactionMut)>>| {
            let doc = new_doc(1);
            let text = doc.get_or_insert_text("content");
            let emitted = Arc::new(Mutex::new(Vec::<String>::new()));
            let sink = emitted.clone();
            doc.observe_update_v1("spike", move |_, event| sink.lock().unwrap().push(hex::encode(&event.update))).expect("observe");
            for edit in &edits {
                let mut txn = doc.transact_mut();
                edit(&text, &mut txn);
            }
            let case = cases.iter().find(|c| c["name"] == name).unwrap();
            for (i, want) in case["updates"].as_array().unwrap().iter().enumerate() {
                let same = emitted.lock().unwrap().get(i).map(|e| e.as_str()) == want.as_str();
                report.check(&format!("{name}: emitted update {i} equals Yjs"), same);
            }
        };
        produce("sequential-inserts", vec![
            Box::new(|t, tx| t.insert(tx, 0, "Hello")),
            Box::new(|t, tx| t.insert(tx, 5, " world")),
            Box::new(|t, tx| t.insert(tx, 5, ",")),
        ]);
        produce("insert-and-delete", vec![
            Box::new(|t, tx| t.insert(tx, 0, "The quick brown fox")),
            Box::new(|t, tx| t.remove_range(tx, 4, 6)),
            Box::new(|t, tx| t.insert(tx, 4, "slow ")),
            Box::new(|t, tx| t.remove_range(tx, 0, 4)),
        ]);
        produce("unicode", vec![
            Box::new(|t, tx| t.insert(tx, 0, "Grüße, 世界 👋 e\u{301}")),
            Box::new(|t, tx| t.insert(tx, 7, "— ")),
        ]);
    }

    // ---- 3. Large document: replay the workload of large-doc.mjs and compare with what Yjs ended in.
    {
        let want: Value = serde_json::from_str(&std::fs::read_to_string(dir.join("large-doc.json")).expect("large-doc")).expect("json");
        let ops = want["opsPerClient"].as_u64().unwrap();
        let sync_every = want["syncEvery"].as_u64().unwrap();
        let mut state = want["seed"].as_u64().unwrap() as u32;
        let mut next = move |bound: u32| -> u32 {
            state = state.wrapping_mul(1664525).wrapping_add(1013904223);
            (state >> 8) % bound
        };
        let mut edit = |doc: &Doc, text: &yrs::TextRef| {
            let mut txn = doc.transact_mut();
            let length = text.len(&txn);
            if next(100) < 85 || length < 5 {
                let position = next(length + 1);
                let word: String = (0..10).map(|_| (97 + next(26) as u8) as char).collect();
                text.insert(&mut txn, position, &word);
            } else {
                let position = next(length - 4);
                let count = 1 + next(5);
                text.remove_range(&mut txn, position, count);
            }
        };
        let sync = |a: &Doc, b: &Doc| {
            let to_b = a.transact().encode_state_as_update_v1(&b.transact().state_vector());
            let to_a = b.transact().encode_state_as_update_v1(&a.transact().state_vector());
            apply(b, &to_b);
            apply(a, &to_a);
        };

        let started = Instant::now();
        let a = new_doc(1);
        let b = new_doc(2);
        let text_a = a.get_or_insert_text("content");
        let text_b = b.get_or_insert_text("content");
        text_a.insert(&mut a.transact_mut(), 0, &"base ".repeat(200));
        sync(&a, &b);
        for i in 0..ops {
            edit(&a, &text_a);
            edit(&b, &text_b);
            if (i + 1) % sync_every == 0 {
                sync(&a, &b);
            }
        }
        let edit_ms = started.elapsed().as_millis();

        let merged = full_state(&a);
        let load_started = Instant::now();
        let fresh = new_doc(999);
        let text_fresh = fresh.get_or_insert_text("content");
        apply(&fresh, &merged);
        let load_ms = load_started.elapsed().as_millis();

        let content = text_a.get_string(&a.transact());
        let length = content.encode_utf16().count();
        report.check(
            &format!("large-doc: {length} characters, the three documents converge"),
            content == text_b.get_string(&b.transact()) && content == text_fresh.get_string(&fresh.transact()),
        );
        report.check("large-doc: length equals Yjs", length as u64 == want["length"].as_u64().unwrap());
        report.check("large-doc: text equals Yjs", hex::encode(Sha256::digest(content.as_bytes())) == want["textSha256"].as_str().unwrap());
        let vector = a.transact().state_vector().encode_v1();
        report.check("large-doc: state vector equals Yjs", same_state_vector(&vector, &hex::decode(want["stateVector"].as_str().unwrap()).unwrap()));
        let identical = hex::encode(Sha256::digest(&merged)) == want["mergedUpdateSha256"].as_str().unwrap();
        let reference = std::fs::read(dir.join("large-doc.update")).ok();
        let how = if identical { "byte-identical" } else { "identical except for the order of the delete set" };
        report.check(
            &format!("large-doc: merged update ({} bytes) equals Yjs ({} bytes), {how}", merged.len(), want["mergedUpdateBytes"]),
            identical || reference.is_some_and(|r| same_update(&merged, &r)),
        );
        println!("timing  yrs: {} edits in {edit_ms} ms, load of the merged document {load_ms} ms", ops * 2);
        println!("timing  yjs: {} edits in {} ms, load {} ms", ops * 2, want["yjs"]["editMs"], want["yjs"]["loadMs"]);
    }

    println!("{}", if report.failed == 0 { "\nall checks passed".to_string() } else { format!("\n{} check(s) failed", report.failed) });
    std::process::exit(if report.failed == 0 { 0 } else { 1 });
}

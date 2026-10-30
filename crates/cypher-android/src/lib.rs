//! JNI bindings for the Cypher mesh core (`libcypher.so`).
//!
//! The Kotlin side holds an opaque engine handle (`Long`) and calls in with
//! radio events and outbound requests; every call returns a framed response
//! containing the engine's pending `LinkCommand`s and UI events:
//!
//! ```text
//! response := u32_le command_count
//!             (u32_le length ‖ bytes)*           — encoded LinkCommands
//!             u32_le json_length ‖ json_bytes    — [EngineEvent] as JSON
//! ```
//!
//! The host executes the commands on its Bluetooth radio and feeds the
//! resulting `LinkEvent`s back through `nativeHandleEvent`, then calls
//! `nativeTick` at the returned deadline. This mirrors exactly the role
//! CoreBluetooth plays for the iOS core.

use cypher_core::identity::{CypherIdentity, FileIdentityStore, IdentityStore};
use cypher_core::mesh::engine::{EngineOutput, MeshEngine};
use cypher_core::mesh::{LinkDirection, LinkEvent, LinkId, RadioState};
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jbyte, jbyteArray, jint, jlong, jobjectArray};
use jni::JNIEnv;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

struct EngineHolder {
    engine: MeshEngine,
    identity: CypherIdentity,
}

fn engines() -> &'static Mutex<std::collections::HashMap<u64, Mutex<EngineHolder>>> {
    static ENGINES: std::sync::OnceLock<
        Mutex<std::collections::HashMap<u64, Mutex<EngineHolder>>>,
    > = std::sync::OnceLock::new();
    ENGINES.get_or_init(|| Mutex::new(std::collections::HashMap::new()))
}

fn with_engine<R>(handle: jlong, f: impl FnOnce(&mut EngineHolder) -> R) -> Option<R> {
    let map = engines().lock().ok()?;
    let holder = map.get(&(handle as u64))?;
    let mut guard = holder.lock().ok()?;
    Some(f(&mut guard))
}

/// Serialize an engine output into the framed response blob.
fn frame_output(output: &EngineOutput) -> Vec<u8> {
    let mut out = Vec::with_capacity(64);
    out.extend_from_slice(&(output.commands.len() as u32).to_le_bytes());
    for command in &output.commands {
        let encoded = command.encode();
        out.extend_from_slice(&(encoded.len() as u32).to_le_bytes());
        out.extend_from_slice(&encoded);
    }
    let events_json = serde_json::to_string(&output.events).unwrap_or_else(|_| "[]".into());
    out.extend_from_slice(&(events_json.len() as u32).to_le_bytes());
    out.extend_from_slice(events_json.as_bytes());
    out
}

fn parse_direction(direction: u8) -> LinkDirection {
    if direction == 0 {
        LinkDirection::Central
    } else {
        LinkDirection::Peripheral
    }
}

/// Convert an incoming JNI byte array (may be null) to a Vec.
fn jbytes_to_vec(env: &JNIEnv, arr: jbyteArray) -> Option<Vec<u8>> {
    if arr.is_null() {
        return None;
    }
    unsafe {
        let array = jni::objects::JByteArray::from_raw(arr);
        env.convert_byte_array(&array).ok()
    }
}

/// Convert a slice into a new JNI byte array (null on failure).
fn vec_to_jbytes(env: &JNIEnv, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(array) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_chat_cypher_android_core_CypherBridge_engineCreate(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
    nickname: JString,
) -> jlong {
    let data_dir: String = env
        .get_string(&data_dir)
        .map(|s| s.into())
        .unwrap_or_else(|_| ".".into());
    let nickname: String = env
        .get_string(&nickname)
        .map(|s| s.into())
        .unwrap_or_else(|_| "cypher".into());

    let store = FileIdentityStore::new(std::path::Path::new(&data_dir).join("identity.json"));
    let identity = match store.load() {
        Ok(Some(existing)) => existing,
        Ok(None) => {
            let fresh = CypherIdentity::generate();
            let _ = store.save(&fresh);
            fresh
        }
        Err(_) => CypherIdentity::generate(),
    };

    let holder = EngineHolder {
        engine: MeshEngine::new(identity.clone(), nickname),
        identity,
    };
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
    engines()
        .lock()
        .map(|mut map| map.insert(handle, Mutex::new(holder)))
        .ok();
    handle as jlong
}

#[no_mangle]
pub extern "system" fn Java_chat_cypher_android_core_CypherBridge_engineDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Ok(mut map) = engines().lock() {
        map.remove(&(handle as u64));
    }
}

/// Feed a radio event. `event_type` discriminators:
/// 0 RadioState(state), 1 BytesReceived{link, direction, bytes},
/// 2 LinkUp{link, direction}, 3 LinkDown{link},
/// 4 Discovered{peripheral, rssi}, 5 CentralSubscribed{link}.
#[no_mangle]
pub extern "system" fn Java_chat_cypher_android_core_CypherBridge_nativeHandleEvent(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    event_type: jint,
    link: jlong,
    direction: jbyte,
    arg_bytes: jbyteArray,
    arg_int: jlong,
) -> jbyteArray {
    let bytes = jbytes_to_vec(&env, arg_bytes);
    let event = match (event_type, bytes) {
        (0, _) => LinkEvent::RadioStateChanged(match arg_int {
            1 => RadioState::Resetting,
            2 => RadioState::Unsupported,
            3 => RadioState::Unauthorized,
            4 => RadioState::PoweredOff,
            5 => RadioState::PoweredOn,
            _ => RadioState::Unknown,
        }),
        (1, Some(bytes)) => LinkEvent::BytesReceived {
            link: link as LinkId,
            direction: parse_direction(direction as u8),
            bytes,
        },
        (1, None) => return std::ptr::null_mut(),
        (2, _) => LinkEvent::LinkUp {
            link: link as LinkId,
            direction: parse_direction(direction as u8),
        },
        (3, _) => LinkEvent::LinkDown {
            link: link as LinkId,
            direction: parse_direction(direction as u8),
        },
        (4, _) => LinkEvent::Discovered {
            peripheral: arg_int as u64,
            rssi: link as i32,
            connectable: direction != 0,
        },
        (5, _) => LinkEvent::CentralSubscribed {
            link: link as LinkId,
        },
        _ => return std::ptr::null_mut(),
    };

    let response = with_engine(handle, |holder| {
        let output = holder.engine.handle(event);
        frame_output(&output)
    });
    match response {
        Some(blob) => vec_to_jbytes(&env, &blob),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_chat_cypher_android_core_CypherBridge_nativeTick(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let response = with_engine(handle, |holder| {
        let output = holder.engine.tick();
        frame_output(&output)
    });
    match response {
        Some(blob) => vec_to_jbytes(&env, &blob),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_chat_cypher_android_core_CypherBridge_nativeSendPublicMessage(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    content: JString,
) -> jbyteArray {
    let content: String = env
        .get_string(&content)
        .map(|s| s.into())
        .unwrap_or_default();
    let response = with_engine(handle, |holder| {
        let output = holder.engine.send_public_message(&content, &[]);
        frame_output(&output)
    });
    match response {
        Some(blob) => vec_to_jbytes(&env, &blob),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_chat_cypher_android_core_CypherBridge_nativeSendPrivateMessage(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    peer_hex: JString,
    content: JString,
    message_id: JString,
) -> jbyteArray {
    let peer_hex: String = env
        .get_string(&peer_hex)
        .map(|s| s.into())
        .unwrap_or_default();
    let content: String = env
        .get_string(&content)
        .map(|s| s.into())
        .unwrap_or_default();
    let message_id: String = env
        .get_string(&message_id)
        .map(|s| s.into())
        .unwrap_or_default();
    let response = with_engine(handle, |holder| {
        let peer = cypher_core::PeerId::new(peer_hex);
        let output = holder
            .engine
            .send_private_message(&peer, &content, &message_id);
        frame_output(&output)
    });
    match response {
        Some(blob) => vec_to_jbytes(&env, &blob),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_chat_cypher_android_core_CypherBridge_nativeSendMeshPing(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    peer_hex: JString,
) -> jbyteArray {
    let peer_hex: String = env
        .get_string(&peer_hex)
        .map(|s| s.into())
        .unwrap_or_default();
    let response = with_engine(handle, |holder| {
        let peer = cypher_core::PeerId::new(peer_hex);
        let output = holder.engine.send_mesh_ping(&peer);
        frame_output(&output)
    });
    match response {
        Some(blob) => vec_to_jbytes(&env, &blob),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_chat_cypher_android_core_CypherBridge_nativeAnnounce(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let response = with_engine(handle, |holder| {
        let output = holder.engine.announce();
        frame_output(&output)
    });
    match response {
        Some(blob) => vec_to_jbytes(&env, &blob),
        None => std::ptr::null_mut(),
    }
}

/// JSON snapshot of the local identity + peer state for the UI.
#[no_mangle]
pub extern "system" fn Java_chat_cypher_android_core_CypherBridge_nativeStateJson(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let response = with_engine(handle, |holder| {
        let peers: Vec<serde_json::Value> = holder
            .engine
            .peer_snapshot()
            .into_iter()
            .map(|p| {
                serde_json::json!({
                    "peerId": p.peer_id,
                    "nickname": p.nickname,
                    "connected": p.is_connected,
                    "verified": p.is_verified,
                    "secureSession": holder.engine.has_established_session(&p.peer_id),
                })
            })
            .collect();
        serde_json::json!({
            "peerId": holder.engine.local_peer_id().as_str(),
            "fingerprint": holder.identity.fingerprint(),
            "nickname": holder.engine.nickname(),
            "peers": peers,
        })
        .to_string()
    });
    match response {
        Some(json) => vec_to_jbytes(&env, json.as_bytes()),
        None => std::ptr::null_mut(),
    }
}

/// Command decoder for the Kotlin side: parse one command blob into
/// (op, link, direction, payloadBytes) tuples for the radio executor.
#[no_mangle]
pub extern "system" fn Java_chat_cypher_android_core_CypherBridge_parseCommands(
    mut env: JNIEnv,
    _class: JClass,
    blob: jbyteArray,
) -> jobjectArray {
    let Some(bytes) = jbytes_to_vec(&env, blob) else {
        return std::ptr::null_mut();
    };
    // commands := u32_le count ‖ (u32_le len ‖ bytes)*
    if bytes.len() < 4 {
        return std::ptr::null_mut();
    }
    let count = u32::from_le_bytes(bytes[0..4].try_into().unwrap()) as usize;
    let mut commands = Vec::new();
    let mut off = 4usize;
    for _ in 0..count {
        if bytes.len() - off < 4 {
            break;
        }
        let len = u32::from_le_bytes(bytes[off..off + 4].try_into().unwrap()) as usize;
        off += 4;
        if bytes.len() - off < len {
            break;
        }
        commands.push(bytes[off..off + len].to_vec());
        off += len;
    }

    let jstring_class = env.find_class("java/lang/String").ok();
    let arr = env
        .new_object_array(
            commands.len() as i32,
            jstring_class.unwrap_or_default(),
            JObject::null(),
        )
        .ok();
    if let Some(arr) = arr {
        for (i, command) in commands.iter().enumerate() {
            // Hex-encode each command for cross-language ease.
            let hex = cypher_core::util::hex_encode(command);
            if let Ok(s) = env.new_string(hex) {
                let _ = env.set_object_array_element(&arr, i as i32, s);
            }
        }
        return arr.into_raw();
    }
    std::ptr::null_mut()
}

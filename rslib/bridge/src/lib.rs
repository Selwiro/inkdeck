//! rslib-bridge：把 Anki 官方 Rust 后端（rslib）暴露成极窄的 JNI 面。
//!
//! 设计要点：
//! - 只暴露 open / run / close 三个动作，协议全部走 protobuf 字节。
//! - 调用按「服务名 + 方法名」分发，索引由 `anki_proto_gen::get_services` 在运行时算，
//!   与 rslib 编译期生成的 `run_service_method` 分发表同源，客户端不必硬编码数字索引。
//! - 所有跨 FFI 的 panic 都被 `catch_unwind` 拦住并转成 Java 异常，不让 App 直接 abort。

use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::OnceLock;

use anki::backend::Backend;
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jbyteArray, jlong, jstring};
use jni::JNIEnv;
use prost::Message;
use prost_reflect::DescriptorPool;

/// descriptor 集合在编译期嵌入，运行时不再依赖 Assets 或文件路径。
static DESCRIPTORS: &[u8] = include_bytes!(env!("DESCRIPTORS_BIN"));

const EXCEPTION_CLASS: &str = "ink/inkdeck/rslib/RslibException";

struct ServiceEntry {
    index: u32,
    methods: HashMap<String, u32>,
}

struct Registry {
    services: HashMap<String, ServiceEntry>,
}

fn registry() -> &'static Registry {
    static REGISTRY: OnceLock<Registry> = OnceLock::new();
    REGISTRY.get_or_init(build_registry)
}

fn build_registry() -> Registry {
    let pool = DescriptorPool::decode(DESCRIPTORS).expect("anki descriptors 无法解析");
    let (_collection, backend_services) = anki_proto_gen::get_services(&pool);
    let mut services = HashMap::new();
    for service in backend_services {
        // 客户端按集合服务名调用（DecksService），后端按 BackendDecksService 注册；
        // 方法索引包含 trait 方法与转发方法，二者都在 get_services 里算好。
        let key = service
            .name
            .strip_prefix("Backend")
            .unwrap_or(&service.name)
            .to_string();
        // 与生成的 dispatch 一致：frontend 服务不参与分发。
        if key == "Frontend" {
            continue;
        }
        let mut methods = HashMap::new();
        for method in service
            .trait_methods
            .iter()
            .chain(service.delegating_methods.iter())
        {
            let index = method.index as u32;
            methods.insert(method.proto.name().to_string(), index); // PascalCase
            methods.insert(method.name.clone(), index); // snake_case
        }
        services.insert(
            key,
            ServiceEntry {
                index: service.index as u32,
                methods,
            },
        );
    }
    Registry { services }
}

fn lookup(service: &str, method: &str) -> Option<(u32, u32)> {
    let registry = registry();
    let entry = registry.services.get(service)?;
    let method = entry.methods.get(method)?;
    Some((entry.index, *method))
}

fn throw(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new(EXCEPTION_CLASS, message);
}

/// 后端的错误是 protobuf 编码的 BackendError，这里抽出可读文本。
///
/// 前缀用 `Kind::as_str_name()`（即 proto 里的 `IO_ERROR` 这类枚举名）而不是 `{:?}`：
/// Kotlin 侧按这个前缀解析错误类型，必须与 proto 定义逐字一致。
fn describe_error(bytes: &[u8]) -> String {
    match anki_proto::backend::BackendError::decode(bytes) {
        Ok(error) => {
            let kind = anki_proto::backend::backend_error::Kind::try_from(error.kind)
                .map(|kind| kind.as_str_name().to_string())
                .unwrap_or_else(|_| format!("UNKNOWN_{}", error.kind));
            if error.message.is_empty() {
                kind
            } else {
                format!("{kind}: {}", error.message)
            }
        }
        Err(_) => format!("PROTO_ERROR: 后端返回了无法解析的错误（{} 字节）", bytes.len()),
    }
}

#[no_mangle]
pub extern "system" fn Java_ink_inkdeck_rslib_NativeBridge_externalOpenBackend(
    mut env: JNIEnv,
    _this: JObject,
    init: JByteArray,
) -> jlong {
    let init = match env.convert_byte_array(&init) {
        Ok(bytes) => bytes,
        Err(_) => {
            throw(&mut env, "无法读取后端初始化参数");
            return 0;
        }
    };
    match catch_unwind(AssertUnwindSafe(|| anki::backend::init_backend(&init))) {
        Ok(Ok(backend)) => Box::into_raw(Box::new(backend)) as jlong,
        Ok(Err(message)) => {
            throw(&mut env, &format!("后端初始化失败：{message}"));
            0
        }
        Err(_) => {
            throw(&mut env, "后端初始化时发生 Rust panic");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ink_inkdeck_rslib_NativeBridge_externalRunMethod(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    service: JString,
    method: JString,
    args: JByteArray,
) -> jbyteArray {
    if handle == 0 {
        throw(&mut env, "后端尚未打开");
        return std::ptr::null_mut();
    }
    let service: String = match env.get_string(&service) {
        Ok(value) => value.into(),
        Err(_) => {
            throw(&mut env, "无法读取服务名");
            return std::ptr::null_mut();
        }
    };
    let method: String = match env.get_string(&method) {
        Ok(value) => value.into(),
        Err(_) => {
            throw(&mut env, "无法读取方法名");
            return std::ptr::null_mut();
        }
    };
    let args = match env.convert_byte_array(&args) {
        Ok(bytes) => bytes,
        Err(_) => {
            throw(&mut env, "无法读取请求参数");
            return std::ptr::null_mut();
        }
    };
    let Some((service_index, method_index)) = lookup(&service, &method) else {
        throw(&mut env, &format!("未知的后端方法：{service}.{method}"));
        return std::ptr::null_mut();
    };

    // 句柄由 Kotlin 侧串行持有；Backend 内部自己用锁保护状态。
    let backend = unsafe { &*(handle as *const Backend) };
    match catch_unwind(AssertUnwindSafe(|| {
        backend.run_service_method(service_index, method_index, &args)
    })) {
        Ok(Ok(output)) => match env.byte_array_from_slice(&output) {
            Ok(array) => array.into_raw(),
            Err(_) => {
                throw(&mut env, "无法把后端结果拷回 Java");
                std::ptr::null_mut()
            }
        },
        Ok(Err(error_bytes)) => {
            throw(&mut env, &describe_error(&error_bytes));
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(&mut env, &format!("调用 {service}.{method} 时发生 Rust panic"));
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ink_inkdeck_rslib_NativeBridge_externalCloseBackend(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        drop(Box::from_raw(handle as *mut Backend));
    }));
}

/// 诊断信息：版本、上游 tag、可分发服务数量。用于启动日志与排障。
/// 设置 Rust 侧 `std::env::temp_dir()` 用的临时目录。
///
/// Android 上 Rust 默认落到 `/data/local/tmp`，普通应用没有写权限；
/// 导入 .apkg 时 rslib 会用临时文件解包，必须在后端初始化前把 TMPDIR
/// 指到应用自己的 cache 目录。否则真机上会报
/// `Failed to create file in '/data/local/tmp': Permission denied`。
#[no_mangle]
pub extern "system" fn Java_ink_inkdeck_rslib_NativeBridge_externalSetTempDir(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
) {
    let value: String = match env.get_string(&path) {
        Ok(value) => value.into(),
        Err(_) => {
            throw(&mut env, "无法读取临时目录路径");
            return;
        }
    };
    std::env::set_var("TMPDIR", &value);
    std::env::set_var("TMP", &value);
    std::env::set_var("TEMP", &value);
}

#[no_mangle]
pub extern "system" fn Java_ink_inkdeck_rslib_NativeBridge_externalDiagnostics(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let registry = registry();
    let message = format!(
        "rslib-bridge {} | anki {} | {} services",
        env!("CARGO_PKG_VERSION"),
        option_env!("ANKI_TAG").unwrap_or("unknown"),
        registry.services.len()
    );
    match env.new_string(message) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

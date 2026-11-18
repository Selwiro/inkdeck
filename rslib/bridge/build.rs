//! 构建脚本：只负责让 descriptor 集合的变化能触发重编译。
//! descriptor 由 rslib/build-android.sh 用 protoc 生成，路径经 DESCRIPTORS_BIN 传入。

fn main() {
    let descriptors = std::env::var("DESCRIPTORS_BIN").unwrap_or_else(|_| {
        panic!(
            "DESCRIPTORS_BIN 未设置。请用 rslib/build-android.sh 构建，\
             或先手动：protoc -I <anki>/proto --include_imports --include_source_info \
             --descriptor_set_out=... 然后 export DESCRIPTORS_BIN"
        )
    });
    if !std::path::Path::new(&descriptors).is_file() {
        panic!("找不到 descriptor 集合：{descriptors}");
    }
    println!("cargo:rerun-if-env-changed=DESCRIPTORS_BIN");
    println!("cargo:rerun-if-env-changed=ANKI_TAG");
    println!("cargo:rerun-if-changed={descriptors}");
}

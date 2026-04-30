// rust-core/src/uniffi_bindgen.rs
// UniFFI bindgen binary — generates Kotlin/Swift bindings from nodex_vpn.udl.
// Used by CI:
//   cargo run --release --bin uniffi-bindgen generate src/nodex_vpn.udl --language kotlin
//   cargo run --release --bin uniffi-bindgen generate src/nodex_vpn.udl --language swift
fn main() {
    uniffi::uniffi_bindgen_main();
}

// Entrypoint for the UniFFI binding generator.
// Called by CI via: cargo run --release --bin uniffi-bindgen generate ...
fn main() {
    uniffi::uniffi_bindgen_main()
}

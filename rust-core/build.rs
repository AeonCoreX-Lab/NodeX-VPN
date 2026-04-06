// rust-core/build.rs
// Generates Kotlin/Swift/C bindings from src/nodex_vpn.udl via UniFFI.

fn main() {
    // Tell Cargo to re-run build.rs only when the UDL changes.
    println!("cargo:rerun-if-changed=src/nodex_vpn.udl");
    println!("cargo:rerun-if-changed=src/lib.rs");

    uniffi::generate_scaffolding("src/nodex_vpn.udl").unwrap();
}

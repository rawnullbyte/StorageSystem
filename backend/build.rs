fn main() {
    // Force rebuild when dashboard dist changes (rust-embed can miss this).
    println!("cargo:rerun-if-changed=../dashboard/dist/index.html");
}

/// Linux: the libraries Murmur ships (the Vulkan loader, with `vulkan`) are next to the
/// executable during development and in /usr/lib/Murmur once installed, like the app's
/// (app/src-tauri/build.rs).
fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("linux") {
        println!("cargo:rustc-link-arg=-Wl,-rpath,$ORIGIN:$ORIGIN/../lib/Murmur");
    }
}

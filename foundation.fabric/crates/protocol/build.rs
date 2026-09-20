fn main() {
    let protoc = protoc_bin_vendored::protoc_bin_path().expect("vendored protoc is available");
    prost_build::Config::new()
        .protoc_executable(protoc)
        .compile_protos(&["../../protocol/fabric.proto"], &["../../protocol"])
        .expect("fabric.proto compiles");
    println!("cargo:rerun-if-changed=../../protocol/fabric.proto");
}

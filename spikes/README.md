# Spikes

Throwaway programs for the go/no-go spikes of phase 0.1 (P0.1-CORE-1).
They are evidence, not product code, and nothing in the Android client depends on them.
The results are recorded in `docs/OPEN-DECISIONS.md` of the Nyxite repo, section "Technical items to validate".

| Directory | Question |
| --- | --- |
| `crypto-vectors` | Does BouncyCastle for Java reproduce the shared crypto vectors, on a JVM and on the Android runtime? |
| `crdt-yrs` | Is yrs byte-compatible with Yjs, and does it build and run for the Android ABIs? |

## crypto-vectors

The BouncyCastle jar is not checked in.

```bash
curl -o lib/bcprov-jdk18on-1.86.jar https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/1.86/bcprov-jdk18on-1.86.jar
java -cp lib/bcprov-jdk18on-1.86.jar Check.java
```

It reads the vector files from a checkout of NyxiteServer next to this repo, or from the directory given as argument.

On a connected device or emulator with Android 14 or newer, the same check runs on the Android runtime.
This is also how the Argon2id time of a real phone is measured.

```bash
javac --release 17 -cp lib/bcprov-jdk18on-1.86.jar -d build/classes Check.java
"$ANDROID_HOME"/build-tools/37.0.0/d8 --release --min-api 34 --lib "$ANDROID_HOME"/platforms/android-37.0/android.jar \
  --output build/check.jar build/classes/*.class lib/bcprov-jdk18on-1.86.jar
adb shell mkdir -p /data/local/tmp/nyxite-spike/vectors
adb push build/check.jar /data/local/tmp/nyxite-spike/
adb push ../../../NyxiteServer/tests/Nyxite.UnitTests/Resources/vectors/*.json /data/local/tmp/nyxite-spike/vectors/
adb shell 'cd /data/local/tmp/nyxite-spike && CLASSPATH=/data/local/tmp/nyxite-spike/check.jar app_process / Check vectors'
```

## crdt-yrs

yrs 0.28.1 needs a Rust newer than 1.93.
The reference cases come from `spikes/crdt-vectors` of a NyxiteWeb checkout next to this repo, or from the directory given as argument.

```bash
cargo run --release
```

For Android, the targets `aarch64-linux-android` and `x86_64-linux-android` and the NDK linker are needed.

```bash
NDK="$ANDROID_HOME"/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK/aarch64-linux-android29-clang cargo build --release --target aarch64-linux-android
CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=$NDK/x86_64-linux-android29-clang cargo build --release --target x86_64-linux-android
adb push target/x86_64-linux-android/release/crdt-yrs-spike /data/local/tmp/nyxite-spike/
adb push ../../../NyxiteWeb/spikes/crdt-vectors/crdt-vectors.json ../../../NyxiteWeb/spikes/crdt-vectors/large-doc.json /data/local/tmp/nyxite-spike/v/
adb shell 'cd /data/local/tmp/nyxite-spike && ./crdt-yrs-spike v'
```

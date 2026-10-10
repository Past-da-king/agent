#!/usr/bin/env bash
# Builds the "runtime pack" that lets the app run Claude Code (Agent SDK) and OpenAI Codex
# on the user's own subscription, entirely inside the APK:
#   - Node 24 for Android (Termux's bionic build) + its libs, renamed lib*.so so Android
#     installs them into nativeLibraryDir, the only place an app may execute files from.
#   - Codex (static musl aarch64) and ripgrep as libcodex.so / librg.so.
#   - The JS side (Agent SDK with its bundled cli.js, Codex SDK) zipped into assets.
# Output goes to app/src/main/jniLibs/arm64-v8a and app/src/main/assets/runtime (both git-ignored).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-/tmp/phone-runtime-pack}"
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
ASSETS="$ROOT/app/src/main/assets/runtime"
PATCHELF="${PATCHELF:-patchelf}"
SDK_VERSION="${SDK_VERSION:-0.3.293}"   # Agent SDK (JS). Claude Code itself is a native Bun binary since 0.2.113: we ship its musl build.
CODEX_SDK_VERSION="${CODEX_SDK_VERSION:-0.160.0}"
APP_ID="com.past9.phoneaos"
# The musl Claude Code binary asks for /lib/ld-musl-aarch64.so.1, which Android lacks. We ship Alpine's musl
# loader as libldmusl.so and point the binary's interpreter at a symlink the app keeps in its own files dir
# (bin/ld-musl-aarch64.so.1 -> nativeLibraryDir/libldmusl.so). The kernel follows the link to an APK lib, so it may run.
INTERP="/data/user/0/$APP_ID/files/runtime/bin/ld-musl-aarch64.so.1"
mkdir -p "$WORK/debs" "$WORK/root" "$JNI" "$ASSETS"
cd "$WORK"

echo "== Termux packages"
curl -s -m 60 -o Packages.gz https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz
zcat Packages.gz > Packages
for p in nodejs-lts libc++ openssl c-ares libicu libsqlite zlib; do
  f=$(awk -v p="$p" '$0=="Package: "p{f=1} f&&/^Filename:/{print $2; exit}' Packages)
  [ -f "debs/$(basename "$f")" ] || curl -s -m 300 -o "debs/$(basename "$f")" "https://packages.termux.dev/apt/termux-main/$f"
  d="x_$p"; mkdir -p "$d"; (cd "$d" && ar x "../debs/$(basename "$f")" && tar xf data.tar.* -C ../root)
done
U=root/data/data/com.termux/files/usr

# Android only extracts files named lib*.so, so every versioned soname gets a flat name and
# every DT_NEEDED is rewritten to match. RUNPATH is dropped; the app sets LD_LIBRARY_PATH.
declare -A MAP=(
  [libz.so.1]=libtxz.so [libcrypto.so.3]=libtxcrypto.so [libssl.so.3]=libtxssl.so
  [libicuuc.so.78]=libtxicuuc.so [libicui18n.so.78]=libtxicui18n.so [libicudata.so.78]=libtxicudata.so
  [libsqlite3.so]=libtxsqlite3.so [libcares.so]=libtxcares.so [libc++_shared.so]=libc++_shared.so
)
src() { case "$1" in
  libz.so.1) echo $U/lib/libz.so.1.*;; libcrypto.so.3) echo $U/lib/libcrypto.so.3;; libssl.so.3) echo $U/lib/libssl.so.3;;
  libicuuc.so.78) echo $U/lib/libicuuc.so.78.*;; libicui18n.so.78) echo $U/lib/libicui18n.so.78.*;; libicudata.so.78) echo $U/lib/libicudata.so.78.*;;
  libsqlite3.so) readlink -f $U/lib/libsqlite3.so;; libcares.so) readlink -f $U/lib/libcares.so;; libc++_shared.so) echo $U/lib/libc++_shared.so;; esac; }
fix() {
  local f="$1"
  "$PATCHELF" --remove-rpath "$f" || true
  for need in $(readelf -d "$f" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p'); do
    if [ -n "${MAP[$need]:-}" ] && [ "${MAP[$need]}" != "$need" ]; then "$PATCHELF" --replace-needed "$need" "${MAP[$need]}" "$f"; fi
  done
}
for so in "${!MAP[@]}"; do
  out="$JNI/${MAP[$so]}"; cp -L "$(src "$so")" "$out"; chmod 755 "$out"
  "$PATCHELF" --set-soname "${MAP[$so]}" "$out"; fix "$out"
done
cp "$U/bin/node" "$JNI/libnode.so"; chmod 755 "$JNI/libnode.so"; fix "$JNI/libnode.so"

echo "== JS packages"
mkdir -p js && cd js
echo '{"name":"phone-runtime","private":true,"type":"module"}' > package.json
npm install --ignore-scripts --no-audit --no-fund --omit=optional "@anthropic-ai/claude-agent-sdk@$SDK_VERSION" "@openai/codex-sdk@$CODEX_SDK_VERSION" >/dev/null
CC=$(node -e "console.log(require('./node_modules/@anthropic-ai/claude-agent-sdk/package.json').claudeCodeVersion)")
npm pack "@anthropic-ai/claude-code-linux-arm64-musl@$CC" >/dev/null
mkdir -p ccn && tar xzf anthropic-ai-claude-code-linux-arm64-musl-*.tgz -C ccn
cp ccn/package/claude "$JNI/libclaude.so"; chmod 755 "$JNI/libclaude.so"
"$PATCHELF" --set-interpreter "$INTERP" "$JNI/libclaude.so"
AV=$(curl -s -m 60 https://dl-cdn.alpinelinux.org/alpine/latest-stable/main/aarch64/APKINDEX.tar.gz | tar xzO APKINDEX | awk '/^P:musl$/{f=1} f&&/^V:/{print substr($0,3); exit}')
curl -s -m 120 -o musl.apk "https://dl-cdn.alpinelinux.org/alpine/latest-stable/main/aarch64/musl-$AV.apk"
mkdir -p musl && tar xzf musl.apk -C musl 2>/dev/null || true
cp musl/lib/ld-musl-aarch64.so.1 "$JNI/libldmusl.so"; chmod 755 "$JNI/libldmusl.so"
CX=$(node -e "console.log(require('./node_modules/@openai/codex/package.json').version)")
npm pack "@openai/codex@$CX-linux-arm64" >/dev/null
mkdir -p cx && tar xzf openai-codex-*-linux-arm64.tgz -C cx
# Codex 0.160 runs its tools through a second binary, codex-code-mode-host, which it looks for BESIDE its own
# executable under that exact name. Android only runs lib*.so from nativeLibraryDir, so that file can't exist
# there and every tool call failed ("failed to spawn code-mode host"). Ship the host as libcodex-modehost.so and
# rename the lookup inside codex to match (same length, so nothing in the binary shifts).
CXBIN=cx/package/vendor/aarch64-unknown-linux-musl/bin
node -e '
const fs=require("fs"),a=Buffer.from("codex-code-mode-host"),b=Buffer.from("libcodex-modehost.so");
if(a.length!==b.length) throw new Error("length");
const d=fs.readFileSync(process.argv[1]);let n=0,i=0;
while((i=d.indexOf(a,i))>=0){b.copy(d,i);i+=a.length;n++}
if(n<1) throw new Error("host name not found in codex: the layout changed, re-check the code-mode host lookup");
fs.writeFileSync(process.argv[2],d);console.log("patched host name x"+n)' "$CXBIN/codex" "$JNI/libcodex.so"
cp "$CXBIN/codex-code-mode-host" "$JNI/libcodex-modehost.so"
chmod 755 "$JNI/libcodex-modehost.so"
# ripgrep: newer SDKs no longer vendor it; keep the one already packed if so.
[ -f node_modules/@anthropic-ai/claude-agent-sdk/vendor/ripgrep/arm64-linux/rg ] && cp node_modules/@anthropic-ai/claude-agent-sdk/vendor/ripgrep/arm64-linux/rg "$JNI/librg.so"
chmod 755 "$JNI/libcodex.so" "$JNI/librg.so"
# Keep only what runs on an arm64 phone.
[ -d node_modules/@anthropic-ai/claude-agent-sdk/vendor ] && find node_modules/@anthropic-ai/claude-agent-sdk/vendor -mindepth 2 -maxdepth 2 -type d ! -name 'arm64-linux' -exec rm -r {} +
rm -f "$ASSETS/node_modules.zip"; zip -qr -9 "$ASSETS/node_modules.zip" node_modules package.json
cd ..
cp /etc/ssl/certs/ca-certificates.crt "$ASSETS/cacert.pem"
echo "== done"; du -sh "$JNI"/* "$ASSETS"/* | sort -h

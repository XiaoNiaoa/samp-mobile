#!/usr/bin/env bash
# 检查 CefManager.java 里声明的每个 native 方法在编译产物里真的有对应符号，
# 而且是**未 mangle** 的 JNI 名字。
#
# 为什么需要这一条：native 方法忘了写 extern "C" 时，C++ 会把它 mangle 成
# _Z66Java_...，Java 侧调用直接抛 UnsatisfiedLinkError。如果 Java 那边又用
# try/catch 吞掉了，现象就是"代码改了、编译过了、行为完全没变"，而且在
# logcat 不可靠的机器上连痕迹都找不到。2026-11-23 就踩了这个坑
# （nativeForgetWorldObject 没生效，WorldObject3D 条目一直不释放）。
#
#   bash tools/cef_kat/jni_symbol_check.sh
#
# 退出码 0 = 全部有符号；1 = 有缺失（会逐条打印）。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JAVA="$ROOT/app/src/main/java/com/sampmobile/game/ui/cef/CefManager.java"
PREFIX="Java_com_sampmobile_game_ui_cef_CefManager_"

find_nm() {
    for f in /d/android-sdk/ndk/*/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe; do
        [ -x "$f" ] && { printf '%s\n' "$f"; return 0; }
    done
    return 1
}

find_so() {
    ls -t "$ROOT"/app/build/intermediates/cxx/Debug/*/obj/arm64-v8a/libSAMPMOBILE.so 2>/dev/null | head -1
}

NM="$(find_nm)" || { echo "SKIP  找不到 llvm-nm（NDK 不在 /d/android-sdk/ndk 下）"; exit 0; }
SO="$(find_so)"
[ -n "$SO" ] || { echo "FAIL  找不到 libSAMPMOBILE.so，先编译"; exit 1; }
[ -f "$JAVA" ] || { echo "FAIL  找不到 $JAVA"; exit 1; }

# native 方法名：`private static native void nativeFoo(int id);`
NATIVES="$(grep -oE 'native +(void|int|long|boolean|String|byte\[\]|float|double) +native[A-Za-z0-9_]+ *\(' "$JAVA" \
    | sed -E 's/.* (native[A-Za-z0-9_]+) *\($/\1/' | sort -u)"
COUNT=$(printf '%s\n' "$NATIVES" | grep -c . || true)
[ "$COUNT" -gt 0 ] || { echo "FAIL  $JAVA 里没解析出任何 native 方法（解析规则失效？）"; exit 1; }

SYMFILE="$ROOT/tools/cef_kat/out/.jni-symbols.txt"
mkdir -p "$(dirname "$SYMFILE")"
"$NM" -D --defined-only "$SO" | awk 'NF>=3 {print $3}' > "$SYMFILE"

MISSING=0
for n in $NATIVES; do
    # 必须用文件而不是管道：`grep -q` 命中后立刻退出，管道另一端的 printf 会
    # 收到 SIGPIPE，在 set -o pipefail 下整个管道被判为失败 —— 100% 假阳性。
    if grep -qx "$PREFIX$n" "$SYMFILE"; then
        continue
    fi
    MISSING=$((MISSING + 1))
    # 给出具体原因：是被 mangle 了，还是压根没实现。
    if grep -q "^_Z[0-9]*$PREFIX$n" "$SYMFILE"; then
        echo "FAIL  $n: 符号被 C++ mangle（缺 extern \"C\"）"
    else
        echo "FAIL  $n: 没有任何实现"
    fi
done

if [ "$MISSING" -eq 0 ]; then
    echo "JNI SYMBOLS OK ($COUNT native methods in $(basename "$SO"))"
    exit 0
fi
echo "JNI SYMBOLS FAILED ($MISSING/$COUNT)"
exit 1

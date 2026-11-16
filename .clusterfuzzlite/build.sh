#!/bin/bash
set -euo pipefail
# Compile only the real JVM-compatible receiver and core. Android ReporterSettings
# is replaced with a field-only fuzz-build stub; no Android SDK is needed.
fetch() {
  curl --fail --location --retry 2 "$1" -o "$2"
  printf '%s  %s\n' "$3" "$2" | sha256sum --check --strict
}
mkdir -p "$WORK/receiver-build"
cd "$WORK/receiver-build"
fetch https://github.com/JetBrains/kotlin/releases/download/v2.4.21/kotlin-compiler-2.4.21.zip kotlin.zip 7cc140e76daf416a0424a5557f99afd7ca89ebd463ea1101261e3e01c33e75cd
fetch https://repo.maven.apache.org/maven2/org/json/json/20260814/json-20260814.jar json.jar 7dd2de1533e766e2b6c07d8194da85145ad313a167a4760050f5c9b6839604fe
fetch https://repo.maven.apache.org/maven2/com/google/crypto/tink/tink-android/1.24.0/tink-android-1.24.0.jar tink.jar 86ff5d879d4ac5e827ae3974c90fce1a25e7c2698538af833ed824efbfe4c06e
unzip -q kotlin.zip
project="$SRC/parking-reporter-app"
source_dir="$project/app/src/main/java/io/github/reihlinstinct/reporter"
mapfile -t core < <(find "$source_dir/core" -name '*.kt' ! -name 'CategorySuggester.kt' | sort)
kotlinc/bin/kotlinc -jvm-target 11 -cp json.jar:tink.jar \
  "$source_dir/intake/OfflineReceiver.kt" "$source_dir/intake/EncryptedPackage.kt" \
  "${core[@]}" "$project/.clusterfuzzlite/stubs/ReporterSettings.kt" \
  "$project/.clusterfuzzlite/OfflineReceiverFuzzer.kt" -d receiver.jar
cp receiver.jar json.jar tink.jar "$OUT/"
cp kotlinc/lib/kotlin-stdlib.jar "$OUT/"
python3 "$project/.clusterfuzzlite/seed_corpus.py" seeds "$OUT/OfflineReceiverFuzzer_seed_corpus.zip"
# Check the corpus actually reaches a successful validation, not only early rejects.
java -ea -cp receiver.jar:json.jar:tink.jar:kotlinc/lib/kotlin-stdlib.jar OfflineReceiverFuzzer seeds/structured-golden
cat > "$OUT/OfflineReceiverFuzzer" <<WRAPPER
#!/bin/bash
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname "\$0")
export LD_LIBRARY_PATH="$JVM_LD_LIBRARY_PATH:\$this_dir"
exec "\$this_dir/jazzer_driver" --agent_path="\$this_dir/jazzer_agent_deploy.jar" \\
  --cp="\$this_dir/receiver.jar:\$this_dir/json.jar:\$this_dir/tink.jar:\$this_dir/kotlin-stdlib.jar" \\
  --target_class=OfflineReceiverFuzzer --jvm_args="-ea:-Xmx2048m" "\$@"
WRAPPER
chmod +x "$OUT/OfflineReceiverFuzzer"

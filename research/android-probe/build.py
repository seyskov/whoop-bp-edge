#!/usr/bin/env python3
"""Build a diagnostic APK using Android SDK + JDK 17, no Flutter or Gradle.
No credentials are read. A local test-signing key is generated in ignored build/.
"""
from pathlib import Path
import hashlib
import os
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get("ANDROID_SDK_ROOT", "/opt/homebrew/share/android-commandlinetools"))
JDK = Path(os.environ.get("PROBE_JDK", "/opt/homebrew/opt/openjdk@17"))
PROTOCOL = Path(os.environ.get("PROBE_PROTOCOL", str(ROOT.parents[2] / "protocol")))
BUILD = ROOT / "build"

def run(*args):
    env = dict(os.environ, JAVA_HOME=str(JDK))
    subprocess.run([str(x) for x in args], check=True, env=env, cwd=ROOT)

def main():
    expected_ref = (ROOT / "protocol-ref.txt").read_text().strip()
    actual_ref = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=PROTOCOL, text=True).strip()
    if actual_ref != expected_ref:
        raise SystemExit(f"Protocol checkout must be pinned at {expected_ref}; found {actual_ref}")
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--", "research/java"], check=True, cwd=PROTOCOL, stdout=subprocess.DEVNULL)
    tools = SDK / "build-tools" / "36.0.0"
    android = SDK / "platforms" / "android-37.0" / "android.jar"
    sources = [p for p in sorted((PROTOCOL / "research/java/org/whoopbp/probe").glob("*.java")) if not p.name.endswith("Test.java")]
    sources.append(ROOT / "src/org/whoopbp/probe/ProbeActivity.java")
    for p in [*sources, android, JDK / "bin/javac", tools / "aapt2"]:
        if not p.exists():
            raise SystemExit(f"Missing build input: {p}")
    classes, dex = BUILD / "classes", BUILD / "dex"
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(parents=True, exist_ok=True)
    run(JDK / "bin/javac", "-encoding", "UTF-8", "--release", "8", "-classpath", android, "-d", classes, *sources)
    jar = BUILD / "probe-classes.jar"
    with zipfile.ZipFile(jar, "w") as z:
        for p in sorted(classes.rglob("*.class")):
            z.write(p, p.relative_to(classes))
    run(tools / "d8", "--min-api", "26", "--lib", android, "--output", dex, jar)
    unsigned, aligned, apk = (BUILD / n for n in ["probe-unsigned.apk", "probe-aligned.apk", "whoop-signal-probe.apk"])
    run(tools / "aapt2", "link", "-I", android, "--manifest", ROOT / "AndroidManifest.xml", "-o", unsigned)
    with zipfile.ZipFile(unsigned, "a") as z:
        z.write(dex / "classes.dex", "classes.dex")
    run(tools / "zipalign", "-f", "4", unsigned, aligned)
    key = BUILD / "local-test-signing.p12"
    # This is an ephemeral test key, not a production or user credential.
    if not key.exists():
        run(JDK / "bin/keytool", "-genkeypair", "-keystore", key, "-storetype", "PKCS12", "-alias", "probe", "-storepass", "android", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=Local WHOOP Signal Research")
    run(tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "probe", "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", apk, aligned)
    run(tools / "apksigner", "verify", apk)
    print(f"APK: {apk}\nSHA256: {hashlib.sha256(apk.read_bytes()).hexdigest()}")

if __name__ == "__main__":
    main()

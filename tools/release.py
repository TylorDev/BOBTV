"""Validate and publish signed Android releases. Never handles signing secrets."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = "TylorDev/BOBTV"
BASE = f"https://github.com/{REPO}"
CERTIFICATE = "6c7ba67547a2a10ed031a613632cf151f69690543798b87e5f4f2213ba2f0e68"


def config(tag: str) -> tuple[dict, Path]:
    source = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    def value(name: str, quoted: bool = False):
        pattern = rf'\b{name}\s*=\s*"([^"]+)"' if quoted else rf"\b{name}\s*=\s*(\d+)"
        match = re.search(pattern, source)
        if not match:
            raise ValueError(f"Missing {name}")
        return match[1] if quoted else int(match[1])
    version = value("versionName", True)
    if not re.fullmatch(r"\d+\.\d+(?:\.\d+)?", version) or tag != f"v{version}":
        raise ValueError("Tag must match the stable versionName")
    info = {"versionName": version, "versionCode": value("versionCode"),
            "applicationId": value("applicationId", True), "minSdk": value("minSdk")}
    if info["applicationId"] != "com.bobtv.helloworld" or info["minSdk"] != 29 or value("targetSdk") != 33:
        raise ValueError("Package and Android compatibility must be preserved")
    if not 8 <= info["versionCode"] <= 2_147_483_647:
        raise ValueError("Release versionCode must be at least 8")
    notes_path = ROOT / "releases" / f"{version}.md"
    notes = notes_path.read_text(encoding="utf-8")
    if not notes.startswith(f"# BOBTV {version}\n") or not 20 <= len(notes.encode()) <= 32 * 1024:
        raise ValueError("Missing or incorrect Spanish release notes")
    info["notes"] = notes
    return info, notes_path


def check_published(info: dict) -> None:
    request = urllib.request.Request(f"{BASE}/releases/latest/download/update.json",
                                     headers={"User-Agent": "BOBTV-release", "Cache-Control": "no-cache"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read(128 * 1024 + 1)
            if len(raw) > 128 * 1024:
                raise ValueError("Published metadata too large")
            previous = json.loads(raw)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return  # First release; all other errors must fail closed.
        raise
    if info["versionCode"] <= previous["versionCode"]:
        raise ValueError("versionCode must increase beyond the latest published release")


def android_tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        local = ROOT / "local.properties"
        if local.exists():
            sdk = local.read_text().split("sdk.dir=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
    if not sdk:
        raise ValueError("ANDROID_HOME must point to the Android SDK")
    suffix = ".bat" if name == "apksigner" and os.name == "nt" else ".exe" if os.name == "nt" else ""
    path = Path(sdk) / "build-tools/36.0.0" / (name + suffix)
    if not path.is_file():
        raise ValueError(f"Missing Android build tool: {name}")
    return str(path)


def run(*args: str, capture: bool = False) -> str:
    result = subprocess.run(args, check=True, text=True, encoding="utf-8", errors="replace",
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None)
    return result.stdout if capture else ""


def checksum(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def package(tag: str, apk: Path, output: Path) -> None:
    info, _ = config(tag)
    certificate = run(android_tool("apksigner"), "verify", "--print-certs", str(apk), capture=True)
    if f"Signer #1 certificate SHA-256 digest: {CERTIFICATE}" not in certificate:
        raise ValueError("APK does not have the existing BOBTV signing certificate")
    badging = run(android_tool("aapt"), "dump", "badging", str(apk), capture=True)
    expected = f"package: name='{info['applicationId']}' versionCode='{info['versionCode']}' versionName='{info['versionName']}'"
    if expected not in badging or "sdkVersion:'29'" not in badging or "targetSdkVersion:'33'" not in badging:
        raise ValueError("APK metadata differs from Gradle release configuration")
    if "application-debuggable" in badging:
        raise ValueError("Distribution APK must not be debuggable")
    if not 0 < apk.stat().st_size <= 64 * 1024 * 1024:
        raise ValueError("APK size exceeds updater limit")
    with zipfile.ZipFile(apk) as archive:
        if archive.read("assets/patch-notes.md") != info["notes"].encode("utf-8"):
            raise ValueError("APK patch notes differ from the release notes")
    output.mkdir(parents=True, exist_ok=True)
    name = f"BOBTV-{info['versionName']}-Android10.apk"
    destination = output / name
    shutil.copyfile(apk, destination)
    info.update(apkUrl=f"{BASE}/releases/download/{tag}/{name}", sizeBytes=destination.stat().st_size,
                sha256=checksum(destination))
    (output / (name + ".sha256")).write_text(f"{info['sha256']}  {name}\n", encoding="utf-8")
    (output / "update.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Verified BOBTV {info['versionName']} code {info['versionCode']}: {info['sha256']}")


def publish(tag: str, output: Path) -> None:
    info, notes = config(tag)
    name = f"BOBTV-{info['versionName']}-Android10.apk"
    metadata = json.loads((output / "update.json").read_text(encoding="utf-8"))
    if metadata["sha256"] != checksum(output / name) or metadata["versionCode"] != info["versionCode"]:
        raise ValueError("Distribution files do not match this release")
    lookup = subprocess.run(["gh", "release", "view", tag, "--repo", REPO, "--json", "isDraft"],
                            capture_output=True, text=True)
    if lookup.returncode == 0:
        if not json.loads(lookup.stdout)["isDraft"]:
            raise ValueError("A published release cannot be replaced; increase the version")
    else:
        run("gh", "release", "create", tag, "--repo", REPO, "--draft", "--verify-tag",
            "--title", f"BOBTV {info['versionName']}", "--notes-file", str(notes))
    files = [name, name + ".sha256", "update.json"]
    run("gh", "release", "upload", tag, *(str(output / item) for item in files), "--repo", REPO, "--clobber")
    # Verify the actual uploaded bytes while the release is still a draft.
    with tempfile.TemporaryDirectory(prefix="bobtv-release-") as directory:
        arguments = ["gh", "release", "download", tag, "--repo", REPO, "--dir", directory]
        for item in files:
            arguments.extend(["--pattern", item])
        run(*arguments)
        downloaded = Path(directory)
        if checksum(downloaded / name) != metadata["sha256"]:
            raise ValueError("Uploaded APK checksum mismatch")
        for item in files[1:]:
            if (downloaded / item).read_bytes() != (output / item).read_bytes():
                raise ValueError(f"Uploaded {item} differs from validated asset")
    run("gh", "release", "edit", tag, "--repo", REPO, "--notes-file", str(notes), "--draft=false", "--latest")
    print(f"Published complete release: {BASE}/releases/tag/{tag}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["validate", "package", "publish"])
    parser.add_argument("--tag", required=True)
    parser.add_argument("--check-published", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "dist")
    args = parser.parse_args()
    info, _ = config(args.tag)
    if args.check_published:
        check_published(info)
    if args.command == "package":
        if args.apk is None:
            parser.error("--apk is required")
        package(args.tag, args.apk, args.output)
    elif args.command == "publish":
        publish(args.tag, args.output)
    else:
        print(f"Validated {args.tag}, versionCode={info['versionCode']}, notes present")


if __name__ == "__main__":
    main()

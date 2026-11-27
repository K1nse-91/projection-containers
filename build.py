"""Build the Fabric add-on against the released Minecraft and mod jars."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from tempfile import NamedTemporaryFile, TemporaryDirectory
import time
from urllib.request import Request, urlopen
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parent
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
LOADER_VERSION = "0.20.1"
MIXIN_VERSION = "0.17.5+mixin.0.8.7"
UNIVERSAL_TARGET = "26.1-26.3"
LEGACY_TARGET = "1.21.11"
GRADLE_VERSION = "9.7.1"
GRADLE_SHA256 = "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
COMPILE_GROUPS = (
    "com.google.code.gson:", "com.google.guava:", "com.mojang:",
    "io.netty:", "it.unimi.dsi:", "org.slf4j:", "org.joml:", "org.lwjgl:",
)

# Modrinth release IDs for Litematica, MaLiLib and Fabric API, respectively.
RELEASES = {
    "26.1": ("w8P6TokG", "FItuNokS", "3dM0X6ou"),
    "26.1.1": ("w8P6TokG", "FItuNokS", "3dM0X6ou"),
    "26.1.2": ("w8P6TokG", "FItuNokS", "3dM0X6ou"),
    "26.2": ("CuniXtbo", "KvjmGjAV", "ewUK83HI"),
    "26.3": ("ZqV316KY", "JX9bESec", "lPHkrIVF"),
}
LEGACY_RELEASES = ("mengBR9D", "loabWpyU")


def request(url):
    return urlopen(Request(url, headers={"User-Agent": "litematica-container-scan/build"}), timeout=35)


def metadata(url):
    for attempt in range(3):
        try:
            with request(url) as response:
                return json.load(response)
        except OSError:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def digest(path, algorithm):
    checksum = hashlib.new(algorithm)
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def download(path, url, algorithm=None, expected=None):
    if path.is_file() and (not expected or digest(path, algorithm) == expected):
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(3):
        with NamedTemporaryFile(dir=path.parent, delete=False) as file:
            temporary = Path(file.name)
        try:
            with temporary.open("wb") as file, request(url) as response:
                shutil.copyfileobj(response, file)
            if expected and digest(temporary, algorithm) != expected:
                raise OSError(f"Checksum mismatch for {url}")
            temporary.replace(path)
            return path
        except OSError:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
        finally:
            temporary.unlink(missing_ok=True)


def mod_dependency(cache, release):
    mod = metadata(f"https://api.modrinth.com/v2/version/{release}")
    file = next(file for file in mod["files"] if file["primary"])
    return mod["version_number"], (cache / "mods" / file["filename"], file["url"],
                                  "sha512", file["hashes"]["sha512"])


def compile_version(game, version_url, javac, cache, target):
    version = metadata(version_url)
    client = version["downloads"]["client"]
    dependencies = [
        (cache / game / "client.jar", client["url"], "sha1", client["sha1"]),
        (cache / f"fabric-loader-{LOADER_VERSION}.jar",
         f"https://maven.fabricmc.net/net/fabricmc/fabric-loader/{LOADER_VERSION}/"
         f"fabric-loader-{LOADER_VERSION}.jar", None, None),
        (cache / f"sponge-mixin-{MIXIN_VERSION}.jar",
         f"https://maven.fabricmc.net/net/fabricmc/sponge-mixin/{MIXIN_VERSION}/"
         f"sponge-mixin-{MIXIN_VERSION}.jar", None, None),
    ]
    mod_versions = {}
    for mod_id, release in zip(("litematica", "malilib", "fabric-api"), RELEASES[game]):
        version_number, artifact = mod_dependency(cache, release)
        mod_versions[mod_id] = ">=" + version_number
        dependencies.append(artifact)
    for library in version["libraries"]:
        artifact = library["downloads"].get("artifact")
        if artifact and library["name"].startswith(COMPILE_GROUPS):
            if "natives-" not in artifact["path"]:
                dependencies.append((cache / "libraries" / artifact["path"], artifact["url"],
                                     "sha1", artifact["sha1"]))

    with ThreadPoolExecutor(max_workers=10) as pool:
        classpath = list(pool.map(lambda entry: download(*entry), dependencies))
    fabric_bundle = next(path for path in classpath if path.name.startswith("fabric-api-"))
    with ZipFile(fabric_bundle) as bundle:
        for module in ("fabric-api-base", "fabric-networking-api-v1"):
            entry = next(name for name in bundle.namelist()
                         if name.startswith(f"META-INF/jars/{module}-") and name.endswith(".jar"))
            module_path = cache / "fabric-modules" / game / Path(entry).name
            module_path.parent.mkdir(parents=True, exist_ok=True)
            module_path.write_bytes(bundle.read(entry))
            classpath.append(module_path)
    # The bundled networking module is the runtime dependency. The full API
    # version selected above is only used to compile against that game version.
    del mod_versions["fabric-api"]

    resources = ROOT / "src/main/resources"
    descriptor = json.loads((resources / "fabric.mod.json").read_text(encoding="utf-8"))
    descriptor["depends"]["minecraft"] = ["=" + version for version in RELEASES] if target == UNIVERSAL_TARGET else "=" + game
    descriptor["depends"].update(mod_versions)
    jar_path = ROOT / "build" / "libs" / f"container-scan-fabric-{target}-{descriptor['version']}.jar"
    jar_path.parent.mkdir(parents=True, exist_ok=True)

    classes_dir = ROOT / "build" / "classes"
    classes_dir.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix=f"{game}-", dir=classes_dir) as temporary:
        output = Path(temporary)
        sources = sorted(source for folder in ("src/main/java", "src/26/java")
                         for source in (ROOT / folder).rglob("*.java"))
        args = ["-encoding", "UTF-8", "--release", "25", "-proc:none", "-classpath",
                os.pathsep.join(str(path) for path in classpath), "-d", str(output),
                *(str(path) for path in sources)]
        # Forward slashes keep Windows argument-file paths literal.
        argument_file = classes_dir / f"{game}.args"
        argument_file.write_text("\n".join('"' + arg.replace("\\", "/") + '"' for arg in args),
                                 encoding="utf-8")
        subprocess.run([javac, f"@{argument_file}"], check=True)

        staged_jar = output / jar_path.name
        with ZipFile(staged_jar, "w", ZIP_DEFLATED) as jar:
            for cls in sorted(output.rglob("*.class")):
                jar.write(cls, cls.relative_to(output).as_posix())
            for resource in sorted(resources.rglob("*")):
                if resource.is_file() and resource.name != "fabric.mod.json":
                    jar.write(resource, resource.relative_to(resources).as_posix())
            jar.writestr("fabric.mod.json", json.dumps(descriptor, ensure_ascii=False, indent=2))
        staged_jar.replace(jar_path)
    print(f"{target} -> {jar_path.relative_to(ROOT)}", flush=True)


def compile_legacy(cache, java_home):
    for release in LEGACY_RELEASES:
        _, artifact = mod_dependency(cache, release)
        download(*artifact)

    tooling = cache / "gradle"
    distribution = f"gradle-{GRADLE_VERSION}"
    gradle = tooling / distribution / "bin" / ("gradle.bat" if os.name == "nt" else "gradle")
    if not gradle.is_file():
        archive = download(tooling / f"{distribution}-bin.zip",
                           f"https://downloads.gradle.org/distributions/{distribution}-bin.zip",
                           "sha256", GRADLE_SHA256)
        with ZipFile(archive) as bundle:
            bundle.extractall(tooling)
        if os.name != "nt":
            gradle.chmod(0o755)
    env = os.environ.copy()
    if java_home:
        env["JAVA_HOME"] = str(java_home.resolve())
    subprocess.run([str(gradle), "--no-daemon", "--console=plain", "--gradle-user-home",
                    str(cache / "legacy-gradle"), "-p", str(ROOT / "legacy"),
                    f"-PcontainerScanMods={cache / 'mods'}", "remapJar"], env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("versions", nargs="*", metavar="MINECRAFT",
                        help=f"Minecraft targets to package (default: {UNIVERSAL_TARGET} and {LEGACY_TARGET})")
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--cache", type=Path, default=ROOT / "build" / "cache")
    args = parser.parse_args()
    selected = args.versions or [UNIVERSAL_TARGET, LEGACY_TARGET]
    targets = (UNIVERSAL_TARGET, LEGACY_TARGET, *RELEASES)
    if set(selected) - set(targets):
        parser.error("Supported Minecraft targets are " + ", ".join(targets))
    executable = "javac.exe" if os.name == "nt" else "javac"
    javac = str(args.java_home / "bin" / executable) if args.java_home else executable
    cache = args.cache.resolve()
    subprocess.run([javac, "-version"], check=True)
    manifest = None
    for target in selected:
        if target == LEGACY_TARGET:
            compile_legacy(cache, args.java_home)
        else:
            if manifest is None:
                manifest = {version["id"]: version["url"] for version in metadata(MANIFEST)["versions"]}
            game = "26.1" if target == UNIVERSAL_TARGET else target
            compile_version(game, manifest[game], javac, cache, target)


if __name__ == "__main__":
    main()

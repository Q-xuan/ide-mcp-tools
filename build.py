"""使用本机 JetBrains SDK 构建插件；依赖和产物保存在 .tmp。"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile

VERSION = "9.2.1"
SHA256 = "72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ide-home", type=Path, required=True)
    parser.add_argument("tasks", nargs="*", default=["test", "packagePlugin"])
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    cache = source / ".tmp"
    ide = args.ide_home.resolve(strict=True)
    gradle = cache / f"gradle-{VERSION}"
    launcher = gradle / "bin" / ("gradle.bat" if os.name == "nt" else "gradle")
    if not launcher.exists():
        cache.mkdir(parents=True, exist_ok=True)
        archive = cache / f"gradle-{VERSION}-bin.zip"
        if not archive.exists():
            print(f"下载 Gradle {VERSION}", flush=True)
            urllib.request.urlretrieve(
                f"https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip", archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
            raise SystemExit(f"Gradle 校验失败，请删除后重新下载：{archive}")
        with zipfile.ZipFile(archive) as z:
            z.extractall(cache)
    env = dict(os.environ, JAVA_HOME=str(ide / "jbr"))
    command = [str(launcher), "--no-daemon", "--console=plain", "--max-workers=2",
               "--project-cache-dir", str(cache / "gradle-project"), f"-PideHome={ide}",
               *args.tasks]
    raise SystemExit(subprocess.call(command, cwd=source, env=env))


if __name__ == "__main__":
    main()

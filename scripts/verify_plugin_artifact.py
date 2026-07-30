#!/usr/bin/env python3
"""Fail publication when a Cloudstream artifact is stale or incomplete."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def module_version(module_build: Path) -> int:
    matches = re.findall(r"(?m)^version\s*=\s*(\d+)\s*$", module_build.read_text("utf-8"))
    require(len(matches) == 1, f"expected one numeric version in {module_build}")
    return int(matches[0])


def canonical_provider_urls(source_dir: Path) -> list[str]:
    pattern = re.compile(r'override\s+var\s+mainUrl\s*=\s*"([^"]+)"')
    urls: list[str] = []
    for source in sorted(source_dir.glob("*Provider.kt")):
        urls.extend(pattern.findall(source.read_text("utf-8")))
    urls = sorted(set(urls))
    require(urls, f"no canonical provider URLs found below {source_dir}")
    return urls


def verify(args: argparse.Namespace) -> None:
    root = args.project_root.resolve()
    module = root / "IndoProvider"
    artifact = args.artifact.resolve()
    plugins_path = args.plugins.resolve()
    expected_version = module_version(module / "build.gradle.kts")

    require(artifact.is_file(), f"missing artifact: {artifact}")
    require(plugins_path.is_file(), f"missing plugin metadata: {plugins_path}")

    with zipfile.ZipFile(artifact) as archive:
        require(archive.testzip() is None, f"CRC failure in {artifact}")
        names = set(archive.namelist())
        require(
            {"manifest.json", "classes.dex"}.issubset(names),
            f"{artifact} must contain manifest.json and classes.dex",
        )
        manifest = json.loads(archive.read("manifest.json"))
        dex = archive.read("classes.dex")

    require(manifest.get("name") == "IndoProvider", "unexpected plugin manifest name")
    require(
        manifest.get("pluginClassName") == "com.example.IndoPlugin",
        "unexpected plugin class",
    )
    require(
        manifest.get("version") == expected_version,
        f"artifact version {manifest.get('version')} != source version {expected_version}",
    )

    missing_urls = [
        url for url in canonical_provider_urls(module / "src/main/kotlin/com/example")
        if url.encode("utf-8") not in dex
    ]
    require(
        not missing_urls,
        "classes.dex is stale or partial; missing canonical URLs: " + ", ".join(missing_urls),
    )

    plugins = json.loads(plugins_path.read_text("utf-8"))
    require(isinstance(plugins, list), "plugins.json must contain a list")
    entries = [
        item for item in plugins
        if isinstance(item, dict) and item.get("internalName") == "IndoProvider"
    ]
    require(len(entries) == 1, "plugins.json must contain exactly one IndoProvider entry")
    entry = entries[0]
    require(
        entry.get("version") == expected_version,
        f"metadata version {entry.get('version')} != source version {expected_version}",
    )
    artifact_bytes = artifact.read_bytes()
    expected_hash = "sha256-" + hashlib.sha256(artifact_bytes).hexdigest()
    require(entry.get("fileSize") == len(artifact_bytes), "metadata fileSize is stale")
    require(entry.get("fileHash") == expected_hash, "metadata fileHash is stale")
    require(
        str(entry.get("url", "")).endswith("/builds/IndoProvider.cs3"),
        "metadata URL must target builds/IndoProvider.cs3",
    )

    if args.repository:
        require(
            entry.get("repositoryUrl") == f"https://github.com/{args.repository}",
            "metadata repositoryUrl does not match GITHUB_REPOSITORY",
        )
        require(
            entry.get("url")
            == f"https://raw.githubusercontent.com/{args.repository}/builds/IndoProvider.cs3",
            "metadata artifact URL does not match GITHUB_REPOSITORY",
        )

    print(
        f"Verified IndoProvider v{expected_version}: "
        f"{len(artifact_bytes)} bytes, {len(canonical_provider_urls(module / 'src/main/kotlin/com/example'))} provider URLs"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", type=Path, default=Path("."))
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--plugins", type=Path, required=True)
    parser.add_argument("--repository")
    args = parser.parse_args()
    try:
        verify(args)
    except (OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        print(f"Artifact verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

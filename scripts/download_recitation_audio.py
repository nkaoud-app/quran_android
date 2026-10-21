#!/usr/bin/env python3
"""Download and arrange Quran recitation audio + ayah-timing databases locally.

This mirrors what the Quran for Android app downloads for the qaris defined in
`pages/madani/.../MadaniPageProvider.kt`:

  * Gapless qaris: one mp3 per sura (``001.mp3`` .. ``114.mp3``) plus an ayah
    *timing database* (a SQLite ``.db``, shipped as a ``.zip``). That timing
    database is what the ``common:recitation`` models (recited ayahs, sections,
    words, timings) are populated from at runtime.
  * Gapped qaris: one mp3 per ayah, laid out as ``<sura>/<ayah>.mp3`` (plus the
    basmallah at ``1/1.mp3``).

Files are arranged under ``<output>/audio/<qari-path>/`` so the tree matches the
layout the Android app expects on device:

    audio/
      minshawi_murattal/
        001.mp3 ... 114.mp3
        minshawi_murattal.db        # timing database (ayahs/sections/timings)

The script uses only the Python standard library (no pip installs), re-runs are
resumable (existing files are skipped, like the app does), and it works on
Windows, macOS, and Linux. Tested with Python 3.8+.

Examples (Windows ``py`` launcher shown; use ``python`` if that is your PATH):

    py scripts\\download_recitation_audio.py list
    py scripts\\download_recitation_audio.py download --qari minshawi_murattal
    py scripts\\download_recitation_audio.py download --qari husary --suras 1 --suras 36 --suras 112-114
    py scripts\\download_recitation_audio.py download --qari 2 --output C:\\quran-audio

URLs and the qari list are taken straight from MadaniPageProvider.kt. If the app
later adds qaris, add matching entries to ``QARIS`` below (or pass --url/--db to
describe an ad-hoc one).
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

# --------------------------------------------------------------------------- #
# Data taken from the repository.
#
# Base URL for timing databases, from MadaniPageProvider:
#   baseUrl                  = "https://android.quran.com/data"
#   getDatabasesBaseUrl()    = baseUrl + "/databases/"
#   getAudioDatabasesBaseUrl = getDatabasesBaseUrl() + "audio/"
# Gapless timing db download = <AUDIO_DB_BASE_URL><db>.zip  (unzips to <db>.db)
# --------------------------------------------------------------------------- #
AUDIO_DB_BASE_URL = "https://android.quran.com/data/databases/audio/"

# Qaris as defined in MadaniPageProvider.getQaris(). A non-null `db` means the
# qari is "gapless" (one mp3 per sura + a timing database); otherwise "gapped"
# (one mp3 per ayah, no timing database).
QARIS = {
    "minshawi_murattal": {
        "id": 0,
        "name": "Minshawi (Murattal, gapless)",
        "url": "https://download.quranicaudio.com/quran/muhammad_siddeeq_al-minshaawee/",
        "db": "minshawi_murattal",
    },
    "husary": {
        "id": 1,
        "name": "Husary (gapless)",
        "url": "https://download.quranicaudio.com/quran/mahmood_khaleel_al-husaree/",
        "db": "husary",
    },
    "2": {
        "id": 2,
        "name": "Abdullah Basfar (192kbps, gapped)",
        "url": "https://mirrors.quranicaudio.com/everyayah/Abdullah_Basfar_192kbps/",
        "db": None,
    },
}

# Number of ayahs in each of the 114 suras (needed only for gapped qaris).
AYAH_COUNTS = [
    7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128,
    111, 110, 98, 135, 112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73,
    54, 45, 83, 182, 88, 75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60,
    49, 62, 55, 78, 96, 29, 22, 24, 13, 14, 11, 11, 18, 12, 12, 30, 52, 52, 44,
    28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36, 25, 22, 17, 19, 26, 30,
    20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3, 5, 4,
    5, 6,
]

TIMEOUT = 60  # seconds per request


def ayah_count(sura: int) -> int:
    return AYAH_COUNTS[sura - 1]


def resolve_qari(key: str) -> dict:
    """Look a qari up by its path key (e.g. 'husary') or numeric id (e.g. '1')."""
    if key in QARIS:
        q = dict(QARIS[key])
        q["path"] = key
        return q
    for path, q in QARIS.items():
        if str(q["id"]) == str(key):
            out = dict(q)
            out["path"] = path
            return out
    raise SystemExit(
        f"Unknown qari '{key}'. Run the 'list' command to see available qaris, "
        f"or pass --url/--db to describe a custom one."
    )


def parse_suras(specs) -> list:
    """Parse --suras values like '36', '112-114' into a sorted sura list (1..114)."""
    if not specs:
        return list(range(1, 115))
    result = set()
    for spec in specs:
        for part in str(spec).split(","):
            part = part.strip()
            if not part:
                continue
            if "-" in part:
                lo, hi = part.split("-", 1)
                lo, hi = int(lo), int(hi)
            else:
                lo = hi = int(part)
            if not (1 <= lo <= hi <= 114):
                raise SystemExit(f"Invalid sura range '{part}' (must be within 1-114).")
            result.update(range(lo, hi + 1))
    return sorted(result)


def human_size(num: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if num < 1024 or unit == "GB":
            return f"{num:.1f} {unit}"
        num /= 1024
    return f"{num:.1f} GB"


def download_file(url: str, dest: Path) -> bool:
    """Download `url` to `dest` atomically. Returns True on success.

    Existing destinations are left untouched (resumable re-runs), matching the
    app's `file.exists()` short-circuit.
    """
    if dest.exists() and dest.stat().st_size > 0:
        print(f"  skip   {dest.name} (already downloaded)")
        return True

    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "quran-android-dl/1.0"})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp, open(tmp, "wb") as fh:
            total = resp.length or 0
            copied = 0
            while True:
                chunk = resp.read(64 * 1024)
                if not chunk:
                    break
                fh.write(chunk)
                copied += len(chunk)
                if total:
                    pct = copied * 100 // total
                    print(f"\r  get    {dest.name}  {pct:3d}%  ({human_size(copied)})",
                          end="", flush=True)
                else:
                    print(f"\r  get    {dest.name}  {human_size(copied)}",
                          end="", flush=True)
        print()
        os.replace(tmp, dest)
        return True
    except urllib.error.HTTPError as e:
        print(f"\n  FAIL   {dest.name}: HTTP {e.code} {e.reason} ({url})")
    except (urllib.error.URLError, OSError) as e:
        print(f"\n  FAIL   {dest.name}: {e} ({url})")
    finally:
        if tmp.exists():
            try:
                tmp.unlink()
            except OSError:
                pass
    return False


def download_timing_db(qari: dict, qari_dir: Path) -> bool:
    """Download + unzip the gapless timing database into `qari_dir`."""
    db_name = qari["db"]
    final_db = qari_dir / f"{db_name}.db"
    if final_db.exists() and final_db.stat().st_size > 0:
        print(f"  skip   {final_db.name} (timing database already present)")
        return True

    url = f"{AUDIO_DB_BASE_URL}{db_name}.zip"
    print(f"Timing database (recited ayahs / sections / timings):")
    with tempfile.TemporaryDirectory() as tmpdir:
        zip_path = Path(tmpdir) / f"{db_name}.zip"
        if not download_file(url, zip_path):
            return False
        try:
            with zipfile.ZipFile(zip_path) as zf:
                # Extract the .db (zips here contain a single <db>.db file).
                members = [m for m in zf.namelist() if m.lower().endswith(".db")]
                if not members:
                    members = zf.namelist()
                for member in members:
                    target = qari_dir / Path(member).name
                    with zf.open(member) as src, open(target, "wb") as out:
                        shutil.copyfileobj(src, out)
                    print(f"  unzip  {target.name}")
        except zipfile.BadZipFile:
            print(f"  FAIL   {zip_path.name}: not a valid zip archive")
            return False
    return True


def download_gapless_audio(qari: dict, qari_dir: Path, suras: list) -> int:
    base = qari["url"]
    failures = 0
    print(f"Gapless audio ({len(suras)} sura file(s)):")
    for sura in suras:
        filename = f"{sura:03d}.mp3"
        if not download_file(base + filename, qari_dir / filename):
            failures += 1
    return failures


def download_gapped_audio(qari: dict, qari_dir: Path, suras: list) -> int:
    base = qari["url"]
    failures = 0
    total_ayahs = sum(ayah_count(s) for s in suras)
    print(f"Gapped audio ({total_ayahs} ayah file(s) across {len(suras)} sura(s)):")
    for sura in suras:
        sura_dir = qari_dir / str(sura)
        for ayah in range(1, ayah_count(sura) + 1):
            filename = f"{sura:03d}{ayah:03d}.mp3"
            url = base + filename
            # On device the files are stored as <sura>/<ayah>.mp3
            if not download_file(url, sura_dir / f"{ayah}.mp3"):
                failures += 1
    # Basmallah is stored as 1/1.mp3 (the app downloads 001001.mp3 for it).
    if 1 not in suras:
        basmallah = qari_dir / "1" / "1.mp3"
        download_file(base + "001001.mp3", basmallah)
    return failures


def cmd_list(_args) -> int:
    print("Available qaris (from MadaniPageProvider.kt):\n")
    for path, q in QARIS.items():
        kind = "gapless (per-sura mp3 + timing database)" if q["db"] else "gapped (per-ayah mp3)"
        print(f"  id={q['id']:<2} path={path:<20} {q['name']}")
        print(f"        type : {kind}")
        print(f"        audio: {q['url']}")
        if q["db"]:
            print(f"        timing db: {AUDIO_DB_BASE_URL}{q['db']}.zip")
        print()
    print("Use:  download --qari <path-or-id>")
    return 0


def cmd_download(args) -> int:
    if args.url:
        # Ad-hoc qari described on the command line.
        qari = {
            "id": "custom",
            "name": "custom",
            "url": args.url if args.url.endswith("/") else args.url + "/",
            "db": args.db,
            "path": args.qari or (args.db or "custom"),
        }
    else:
        if not args.qari:
            raise SystemExit("Pass --qari <path-or-id> (see the 'list' command), or --url for a custom one.")
        qari = resolve_qari(args.qari)
        if args.db is not None:
            qari["db"] = args.db or None

    suras = parse_suras(args.suras)
    audio_root = Path(args.output).expanduser().resolve() / "audio"
    qari_dir = audio_root / qari["path"]
    qari_dir.mkdir(parents=True, exist_ok=True)

    print(f"Qari     : {qari['name']}  (path='{qari['path']}')")
    print(f"Mode     : {'gapless' if qari['db'] else 'gapped'}")
    print(f"Suras    : {len(suras)} selected")
    print(f"Output   : {qari_dir}\n")

    failures = 0
    if qari["db"]:
        if not download_timing_db(qari, qari_dir):
            failures += 1
        print()
        failures += download_gapless_audio(qari, qari_dir, suras)
    else:
        failures += download_gapped_audio(qari, qari_dir, suras)

    print()
    if failures:
        print(f"Done with {failures} failed download(s). Re-run the same command "
              f"to retry only the missing files.")
        return 1
    print(f"All files downloaded and arranged under:\n  {qari_dir}")
    if qari["db"]:
        print(f"\nTiming database: {qari_dir / (qari['db'] + '.db')}")
        print("It is a SQLite file holding the ayah start times used to populate the")
        print("recited-ayah / section / timing models in common:recitation.")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Download and arrange Quran recitation audio + timing databases.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="List the available qaris and their URLs.")

    dl = sub.add_parser("download", help="Download a qari's audio (and timing database if gapless).")
    dl.add_argument("--qari", help="Qari path (e.g. 'husary') or numeric id (e.g. '1').")
    dl.add_argument("--suras", action="append",
                    help="Sura(s) to download, e.g. --suras 36 --suras 112-114. "
                         "Repeatable; comma lists allowed. Default: all 114.")
    dl.add_argument("--output", default=".",
                    help="Output directory (an 'audio/' tree is created inside). Default: current dir.")
    dl.add_argument("--url", help="Custom qari base audio URL (overrides --qari's URL).")
    dl.add_argument("--db", help="Timing-database name for a custom qari (omit/empty for gapped).")

    args = parser.parse_args(argv)
    if args.command == "list":
        return cmd_list(args)
    if args.command == "download":
        return cmd_download(args)
    parser.print_help()
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\nInterrupted.")
        sys.exit(130)

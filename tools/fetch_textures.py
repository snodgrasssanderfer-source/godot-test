#!/usr/bin/env python3
"""Downloads the CC0 assets named in docs/texture_sources.csv and stores them
in a uniform layout under textures/ (outside the Godot project, so nothing is
imported or shipped until it is copied into fps/ on purpose).

  tools/fetch_textures.py                 everything in the list that is missing
  tools/fetch_textures.py --res 2K        at another resolution (1K is the default)
  tools/fetch_textures.py --only Asphalt015,red_brick
  tools/fetch_textures.py --force         download again even if present

What ends up on disk, per asset:

  textures/materials/<Asset>/<Asset>_Color.jpg    base colour (PNG with alpha for decals and atlases)
  textures/materials/<Asset>/<Asset>_Normal.jpg   normal map, OpenGL convention (green up)
  textures/materials/<Asset>/<Asset>_ORM.jpg      red = occlusion, green = roughness, blue = metalness
  textures/models/<Asset>/...                     glTF model with its own textures, as published
  textures/fonts/...
  textures/sources.csv                            one line per asset: where it came from, licence, who uses it

Sources: ambientCG and Poly Haven (both CC0), Google Fonts (SIL OFL).
Needs Pillow and numpy.
"""
import argparse
import csv
import io
import json
import os
import subprocess
import sys
import time
import zipfile

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIST = os.path.join(ROOT, "docs", "texture_sources.csv")
OUT = os.path.join(ROOT, "textures")
AGENT = "texture-fetch/1.0"          # both libraries ask automated clients to name themselves
QUALITY = {"Color": 88, "Normal": 90, "ORM": 85}


def get(url, tries=4):
    """Fetches a URL, following redirects; retries with a pause on failure."""
    for attempt in range(tries):
        r = subprocess.run(["curl", "-sS", "-L", "-m", "300", "-A", AGENT, "-w", "\n%{http_code}", url],
                           capture_output=True)
        body, _, code = r.stdout.rpartition(b"\n")
        if r.returncode == 0 and code.strip() == b"200":
            return body
        time.sleep(2 ** (attempt + 1))
    raise RuntimeError(f"download failed: {url}")


def save_jpg(img, path, channel):
    img.convert("RGB").save(path, quality=QUALITY[channel], optimize=True)


def pack_orm(size, ao, rough, metal):
    """Three grey maps into one image; a missing map gets a neutral value."""
    def grey(img, fill):
        if img is None:
            return np.full((size[1], size[0]), fill, dtype=np.uint8)
        return np.asarray(img.convert("L").resize(size, Image.LANCZOS))
    return Image.fromarray(np.dstack([grey(ao, 255), grey(rough, 128), grey(metal, 0)]), "RGB")


def fetch_ambientcg(asset, kind, res, folder):
    data = get(f"https://ambientcg.com/get?file={asset}_{res}-JPG.zip")
    maps = {}
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for name in z.namelist():
            stem = os.path.splitext(name)[0]
            for key in ("Color", "NormalGL", "Roughness", "AmbientOcclusion", "Metalness", "Opacity"):
                if stem.endswith("_" + key):
                    maps[key] = Image.open(io.BytesIO(z.read(name)))
                    maps[key].load()
    if "Color" not in maps:
        raise RuntimeError(f"{asset}: no colour map in the archive ({sorted(maps)})")
    os.makedirs(folder, exist_ok=True)
    color = maps["Color"].convert("RGB")
    files = []
    if "Opacity" in maps:                                   # decals and atlases: alpha goes into the colour PNG
        rgba = color.copy()
        rgba.putalpha(maps["Opacity"].convert("L").resize(color.size, Image.LANCZOS))
        rgba.save(os.path.join(folder, f"{asset}_Color.png"), optimize=True)
        files.append(f"{asset}_Color.png")
    else:
        save_jpg(color, os.path.join(folder, f"{asset}_Color.jpg"), "Color")
        files.append(f"{asset}_Color.jpg")
    if "NormalGL" in maps:
        save_jpg(maps["NormalGL"], os.path.join(folder, f"{asset}_Normal.jpg"), "Normal")
        files.append(f"{asset}_Normal.jpg")
    orm = pack_orm(color.size, maps.get("AmbientOcclusion"), maps.get("Roughness"), maps.get("Metalness"))
    save_jpg(orm, os.path.join(folder, f"{asset}_ORM.jpg"), "ORM")
    files.append(f"{asset}_ORM.jpg")
    return files, color.size


def fetch_polyhaven_texture(asset, res, folder):
    info = json.loads(get(f"https://api.polyhaven.com/files/{asset}"))
    r = res.lower()
    os.makedirs(folder, exist_ok=True)
    files, size = [], None
    for key, channel in (("Diffuse", "Color"), ("nor_gl", "Normal"), ("arm", "ORM")):
        if key not in info:
            continue
        img = Image.open(io.BytesIO(get(info[key][r]["jpg"]["url"])))
        img.load()
        size = size or img.size
        save_jpg(img, os.path.join(folder, f"{asset}_{channel}.jpg"), channel)
        files.append(f"{asset}_{channel}.jpg")
    if "arm" not in info:                                   # older assets ship the three maps separately
        parts = {}
        for key in ("AO", "Rough", "Metal"):
            if key in info:
                parts[key] = Image.open(io.BytesIO(get(info[key][r]["jpg"]["url"])))
        save_jpg(pack_orm(size, parts.get("AO"), parts.get("Rough"), parts.get("Metal")),
                 os.path.join(folder, f"{asset}_ORM.jpg"), "ORM")
        files.append(f"{asset}_ORM.jpg")
    return files, size


def fetch_polyhaven_model(asset, res, folder):
    info = json.loads(get(f"https://api.polyhaven.com/files/{asset}"))
    g = info["gltf"][res.lower()]["gltf"]
    os.makedirs(folder, exist_ok=True)
    name = os.path.basename(g["url"])
    open(os.path.join(folder, name), "wb").write(get(g["url"]))
    files = [name]
    for rel, part in g.get("include", {}).items():
        path = os.path.join(folder, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        open(path, "wb").write(get(part["url"]))
        files.append(rel)
    return files, None


def fetch_font(folder):
    os.makedirs(folder, exist_ok=True)
    base = "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssc/"
    files = []
    for remote, local in (("NotoSansSC%5Bwght%5D.ttf", "NotoSansSC-Variable.ttf"), ("OFL.txt", "OFL.txt")):
        open(os.path.join(folder, local), "wb").write(get(base + remote))
        files.append(local)
    return files, None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--res", default="1K", choices=["1K", "2K", "4K"])
    ap.add_argument("--only", default="")
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()
    only = {x for x in args.only.split(",") if x}

    assets = {}                                              # asset -> {library, kind, page, ids}
    for row in csv.DictReader(open(LIST, encoding="utf-8-sig")):
        a = assets.setdefault(row["素材"], {"library": row["素材库"], "kind": row["素材种类"], "page": row["素材页面"], "ids": []})
        a["ids"].append(row["编号"])

    record_path = os.path.join(OUT, "sources.csv")
    records = {}
    if os.path.exists(record_path):
        for row in csv.DictReader(open(record_path, encoding="utf-8-sig")):
            records[row["asset"]] = row
    failed = []
    for asset, a in sorted(assets.items()):
        if only and asset not in only:
            continue
        if a["kind"] == "字体":
            sub, folder = "fonts", os.path.join(OUT, "fonts")
        elif a["kind"] == "三维模型":
            sub, folder = "models", os.path.join(OUT, "models", asset)
        else:
            sub, folder = "materials", os.path.join(OUT, "materials", asset)
        have = asset in records and os.path.isdir(folder) and records[asset]["resolution"] in (args.res, "")
        if have and not args.force:
            records[asset]["used_by"] = " ".join(sorted(a["ids"]))
            continue
        try:
            if a["kind"] == "字体":
                files, size = fetch_font(folder)
                licence, url = "SIL OFL 1.1", "https://github.com/google/fonts/tree/main/ofl/notosanssc"
            elif a["library"] == "ambientCG":
                files, size = fetch_ambientcg(asset, a["kind"], args.res, folder)
                licence, url = "CC0", f"https://ambientcg.com/get?file={asset}_{args.res}-JPG.zip"
            elif a["kind"] == "三维模型":
                files, size = fetch_polyhaven_model(asset, args.res, folder)
                licence, url = "CC0", f"https://api.polyhaven.com/files/{asset}"
            else:
                files, size = fetch_polyhaven_texture(asset, args.res, folder)
                licence, url = "CC0", f"https://api.polyhaven.com/files/{asset}"
        except Exception as e:                               # keep going: one bad asset must not stop the batch
            failed.append((asset, str(e)))
            print(f"FAILED {asset}: {e}")
            continue
        records[asset] = {
            "asset": asset, "library": a["library"], "kind": a["kind"], "licence": licence, "page": a["page"], "download": url,
            "resolution": "" if a["kind"] == "字体" else args.res, "size": f"{size[0]}x{size[1]}" if size else "",
            "folder": f"textures/{sub}" + ("" if sub == "fonts" else f"/{asset}"), "files": " ".join(files),
            "fetched": time.strftime("%Y-%m-%d"), "used_by": " ".join(sorted(a["ids"])),
        }
        print(f"ok {asset}: {len(files)} files")
    os.makedirs(OUT, exist_ok=True)
    cols = ["asset", "library", "kind", "licence", "page", "download", "resolution", "size", "folder", "files", "fetched", "used_by"]
    with open(record_path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for asset in sorted(records):
            w.writerow({c: records[asset].get(c, "") for c in cols})
    print(f"{len(records)} assets recorded, {len(failed)} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
Genera los emojis del mod (los de siempre de Discord: :smile:, :wilted_flower:, ...).

Descarga:
  - la lista de nombres (shortcodes) de github/gemoji
  - las imágenes de Twemoji (CC-BY 4.0, ver ATTRIBUTION.txt que se genera)

Crea dentro de la carpeta de recursos de tu mod:
  assets/<modid>/textures/font/emoji/<codigo>.png   una imagen por emoji
  assets/<modid>/emoji/emojis.json                  nombres -> emoji (lo lee Emojis.java)
  assets/<modid>/emoji/ATTRIBUTION.txt              crédito de Twemoji
  assets/minecraft/font/default.json                registra los emojis en la fuente de Minecraft

Uso (desde la carpeta de tu proyecto):
  pip install pillow requests
  python tools/generate_emojis.py --res src/client/resources --modid shitterlist

--res debe ser la carpeta de recursos que YA contiene assets/<modid>/ (donde están
las imágenes del botón del chat). Es seguro volver a ejecutarlo: reemplaza lo anterior.
"""
import argparse
import io
import json
import re
import shutil
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

GEMOJI_URL = "https://raw.githubusercontent.com/github/gemoji/master/db/emoji.json"
TWEMOJI_URLS = [
    "https://raw.githubusercontent.com/jdecked/twemoji/main/assets/72x72/{name}.png",
    "https://cdn.jsdelivr.net/gh/jdecked/twemoji@latest/assets/72x72/{name}.png",
]
PRIVATE_USE_END = 0xF8FF  # fin de la zona de uso privado (BMP)


def http_get(url, retries=2):
    """Devuelve los bytes de la URL o None si no existe / falla."""
    import requests

    for _ in range(retries + 1):
        try:
            r = requests.get(url, timeout=30)
            if r.status_code == 200:
                return r.content
            if r.status_code == 404:
                return None
        except Exception:
            pass
    return None


def normalize(name):
    return re.sub(r"[^a-z0-9_+]", "", name.lower().replace("-", "_").replace(" ", "_"))


def twemoji_names(unicode_str):
    """Posibles nombres de archivo de Twemoji para un emoji, del más probable al menos."""
    cps = [f"{ord(c):x}" for c in unicode_str]
    full = "-".join(cps)
    stripped = "-".join(c for c in cps if c != "fe0f")
    # Twemoji quita FE0F salvo en las secuencias con ZWJ (200d)
    order = [full, stripped] if "200d" in cps else [stripped, full]
    seen, out = set(), []
    for n in order:
        if n and n not in seen:
            seen.add(n)
            out.append(n)
    return out


def download_image(unicode_str):
    for name in twemoji_names(unicode_str):
        for url in TWEMOJI_URLS:
            data = http_get(url.format(name=name))
            if data:
                return data
    return None


def build(args):
    from PIL import Image

    res = Path(args.res)
    modid = args.modid
    tex_dir = res / "assets" / modid / "textures" / "font" / "emoji"
    data_dir = res / "assets" / modid / "emoji"
    font_file = res / "assets" / "minecraft" / "font" / "default.json"

    print("Descargando la lista de nombres...")
    raw = http_get(GEMOJI_URL)
    if not raw:
        sys.exit("No se pudo descargar la lista de gemoji. ¿Tienes internet?")
    entries = [e for e in json.loads(raw) if e.get("emoji")]
    if args.limit:
        entries = entries[: args.limit]
    print(f"{len(entries)} emojis en la lista. Descargando imágenes (puede tardar unos minutos)...")

    if tex_dir.exists():
        shutil.rmtree(tex_dir)
    tex_dir.mkdir(parents=True, exist_ok=True)
    data_dir.mkdir(parents=True, exist_ok=True)

    with ThreadPoolExecutor(max_workers=16) as pool:
        images = list(pool.map(lambda e: download_image(e["emoji"]), entries))

    glyphs, providers, missing = [], [], []
    code = args.start
    for entry, data in zip(entries, images):
        if data is None:
            missing.append(entry["emoji"])
            continue
        if code > PRIVATE_USE_END:
            print("Se acabaron los códigos disponibles; se omiten los últimos.")
            break
        img = Image.open(io.BytesIO(data)).convert("RGBA")
        img = img.resize((args.size, args.size), Image.LANCZOS)
        img.save(tex_dir / f"{code:04x}.png", optimize=True)

        names = []
        for n in entry.get("aliases", []) + [entry.get("description", "")]:
            n = normalize(n)
            if n and n not in names:
                names.append(n)
        char = chr(code)
        glyphs.append({"c": char, "u": entry["emoji"], "n": names})
        providers.append({
            "type": "bitmap",
            "file": f"{modid}:font/emoji/{code:04x}.png",
            "ascent": args.ascent,
            "height": args.height,
            "chars": [char],
        })
        code += 1

    (data_dir / "emojis.json").write_text(
        json.dumps({"emojis": glyphs}, ensure_ascii=True), encoding="utf-8")
    (data_dir / "ATTRIBUTION.txt").write_text(
        "Emoji graphics from Twemoji (https://github.com/jdecked/twemoji),\n"
        "Copyright 2020 Twitter, Inc and other contributors, licensed under CC-BY 4.0\n"
        "(https://creativecommons.org/licenses/by/4.0/).\n"
        "Emoji names from github/gemoji (MIT).\n", encoding="utf-8")

    # default.json: conservar lo que ya hubiera que NO sea nuestro y añadir los emojis
    keep = []
    if font_file.exists():
        try:
            old = json.loads(font_file.read_text(encoding="utf-8"))
            prefix = f"{modid}:font/emoji/"
            keep = [p for p in old.get("providers", []) if not str(p.get("file", "")).startswith(prefix)]
        except Exception:
            keep = []
    font_file.parent.mkdir(parents=True, exist_ok=True)
    font_file.write_text(json.dumps({"providers": keep + providers}, ensure_ascii=True), encoding="utf-8")

    print(f"Listo: {len(glyphs)} emojis generados en {res}")
    if missing:
        print(f"{len(missing)} emojis no tenían imagen en Twemoji y se omitieron.")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--res", required=True, help="carpeta de recursos que contiene assets/<modid>/")
    p.add_argument("--modid", default="shitterlist")
    p.add_argument("--size", type=int, default=32, help="tamaño en píxeles de cada imagen (32)")
    p.add_argument("--height", type=int, default=9, help="alto del emoji en el texto (9)")
    p.add_argument("--ascent", type=int, default=8, help="cuánto sube sobre la línea base; <= height (8)")
    p.add_argument("--start", type=lambda v: int(v, 0), default=0xE200,
                   help="primer carácter de uso privado a usar (0xE200)")
    p.add_argument("--limit", type=int, default=0, help="solo los primeros N (para probar)")
    args = p.parse_args()
    if args.ascent > args.height:
        sys.exit("--ascent no puede ser mayor que --height")
    build(args)


if __name__ == "__main__":
    main()

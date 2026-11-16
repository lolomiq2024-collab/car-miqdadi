#!/usr/bin/env python3
"""يولّد audio/library.json من بنية المجلدات:

  audio/songs/<اسم المطرب>/<الأغنية>.mp3
  audio/lessons/<اسم السلسلة> - <اسم الشيخ>/<الحلقة>.mp3   (الجزء بعد " - " اختياري)

يشغَّل تلقائياً في GitHub Actions قبل البناء، ويمكن تشغيله محلياً:  python3 tools/make_library.py
"""
import json, re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "app/src/main/assets"
AUDIO = ROOT / "audio"
EXT = {".mp3", ".m4a", ".ogg", ".wav", ".aac", ".opus"}

def natural(p):
    return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", p.name)]

def files(d):
    return sorted((f for f in d.iterdir() if f.suffix.lower() in EXT), key=natural)

def rel(f):
    return f.relative_to(ROOT).as_posix()

def title(f):
    return f.stem.strip()

def clean(t, singer):
    """يحذف اسم المطرب من اسم الملف إن وُجد: «أغنية - مطرب» أو «مطرب - أغنية»."""
    for sep in (" - ", "-", "_"):
        for pat in (sep + singer, singer + sep):
            if t.endswith(pat) and len(t) > len(pat):
                t = t[: -len(pat)]
            elif t.startswith(pat) and len(t) > len(pat):
                t = t[len(pat):]
    return t.strip() or t

lib = {"songs": {}, "lessons": []}
songs = AUDIO / "songs"
if songs.is_dir():
    for d in sorted(p for p in songs.iterdir() if p.is_dir()):
        fs = files(d)
        if fs:
            lib["songs"][d.name] = [{"title": clean(title(f), d.name), "file": rel(f)} for f in fs]

lessons = AUDIO / "lessons"
if lessons.is_dir():
    for d in sorted(p for p in lessons.iterdir() if p.is_dir()):
        fs = files(d)
        if not fs:
            continue
        name, _, who = d.name.partition(" - ")
        lib["lessons"].append({
            "name": name.strip(), "who": who.strip(),
            "episodes": [{"title": title(f), "file": rel(f)} for f in fs],
        })

# ---- روابط من links.txt (اختياري) ----
LINKS = Path(__file__).resolve().parent.parent / "links.txt"
if LINKS.is_file():
    kind, cur = None, None
    for raw in LINKS.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"^\[(أغاني|درس)\s*:\s*(.+)\]$", line)
        if m:
            kind, label = m.group(1), m.group(2).strip()
            if kind == "أغاني":
                cur = lib["songs"].setdefault(label, [])
            else:
                name, _, who = label.partition(" - ")
                cur = []
                lib["lessons"].append({"name": name.strip(), "who": who.strip(), "episodes": cur})
            continue
        if cur is not None and "|" in line:
            t, _, url = line.partition("|")
            if url.strip().startswith("http"):
                cur.append({"title": t.strip(), "file": url.strip()})

(AUDIO / "library.json").write_text(json.dumps(lib, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"songs: {sum(len(v) for v in lib['songs'].values())} | lessons: {sum(len(s['episodes']) for s in lib['lessons'])}")

#!/usr/bin/env python3
"""Static audit of a release (R8) build: everything reached by *name* at runtime must survive unrenamed.

Usage: scripts/check_r8_release.py [--mapping app/build/outputs/mapping/release/mapping.txt]
                                   [--usage app/build/outputs/mapping/release/usage.txt]
                                   [--apk path/to/app-release.apk]

It derives what to check from the sources (so new workers/routes are covered automatically) and from the APK's
META-INF/services, then reads R8's mapping.txt ("original -> obfuscated:") and usage.txt (removed classes):
  * WorkManager workers: instantiated with Class.forName(<name stored in its database>)  -> name must be unchanged.
  * Room: RoomDatabase and its generated *_Impl classes are loaded by name                  -> unchanged.
  * Navigation type-safe routes (@Serializable): present, with their generated serializers   -> not removed.
  * ServiceLoader providers listed in META-INF/services                                      -> still a class of the build
    (R8 rewrites the services file when it renames a provider, so renaming is fine; stripping is not).
Exit status 1 and a list of findings when something is stripped or renamed. No device needed.
"""
import argparse, pathlib, re, sys, zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
ap = argparse.ArgumentParser()
ap.add_argument("--mapping", default=str(ROOT / "app/build/outputs/mapping/release/mapping.txt"))
ap.add_argument("--usage", default=str(ROOT / "app/build/outputs/mapping/release/usage.txt"))
ap.add_argument("--apk", default=None)
ap.add_argument("--root", default=str(ROOT))
args = ap.parse_args()
root = pathlib.Path(args.root)

mapped = {}  # original -> obfuscated
for line in open(args.mapping, encoding="utf-8", errors="replace"):
    if line.startswith((" ", "#")) or " -> " not in line:
        continue
    orig, obf = line.rstrip("\n").rstrip(":").split(" -> ")
    mapped[orig] = obf
obfuscated = set(mapped.values())
removed = set()
try:
    for line in open(args.usage, encoding="utf-8", errors="replace"):
        if not line.startswith(" ") and line.strip():
            removed.add(line.strip())
except FileNotFoundError:
    pass

sources = [p for m in ("app", "data", "mail", "openpgp", "designsystem") for p in (root / m / "src/main").rglob("*.kt")]
problems, checked = [], 0

def fqcn(path, text, name):
    pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
    return f"{pkg.group(1)}.{name}" if pkg else name

def need_unrenamed(cls, why):
    global checked
    checked += 1
    if cls in removed and cls not in mapped:
        problems.append(f"REMOVED   {cls}  ({why})")
    elif cls not in mapped:
        problems.append(f"MISSING   {cls}  ({why}: not in mapping.txt; removed or a different name)")
    elif mapped[cls] != cls:
        problems.append(f"RENAMED   {cls} -> {mapped[cls]}  ({why})")

def need_present(cls, why):
    global checked
    checked += 1
    if cls not in mapped:
        problems.append(f"REMOVED   {cls}  ({why})")

worker = re.compile(r"^\s*(?:internal\s+|private\s+)?(?:open\s+)?class\s+(\w+)\s*\([^)]*\)\s*:\s*(?:Coroutine|Listenable|Rx)?Worker\b", re.M)
db = re.compile(r"abstract\s+class\s+(\w+)\s*:\s*RoomDatabase\(\)")
route = re.compile(r"@Serializable\s+(?:data\s+)?(?:object|class)\s+(\w+)")
for p in sources:
    text = p.read_text(encoding="utf-8", errors="replace")
    for m in worker.finditer(text):
        need_unrenamed(fqcn(p, text, m.group(1)), "WorkManager worker, loaded by stored class name")
    for m in db.finditer(text):
        name = fqcn(p, text, m.group(1))
        need_unrenamed(name, "RoomDatabase")
        need_unrenamed(name + "_Impl", "Room generated implementation, loaded by name")
    if re.search(r"@Serializable", text):
        for m in route.finditer(text):
            cls = fqcn(p, text, m.group(1))
            need_present(cls, "type-safe navigation route / serializable model")
            if "data class" in m.group(0) or "class " in m.group(0):
                need_present(cls + "$$serializer", "generated serializer of " + cls)

if args.apk:
    with zipfile.ZipFile(args.apk) as z:
        for n in z.namelist():
            if n.startswith("META-INF/services/") and not n.endswith("/"):
                service = n.rsplit("/", 1)[1]
                for line in z.read(n).decode("utf-8", "replace").splitlines():
                    impl = line.split("#")[0].strip()
                    if impl:
                        # R8 rewrites META-INF/services consistently when it renames a provider, so a name that is a
                        # known obfuscated class is fine; a name that is neither original nor obfuscated is stripped.
                        checked += 1
                        if impl not in mapped and impl not in obfuscated:
                            problems.append(f"MISSING   {impl}  (ServiceLoader provider of {service} is not a class in this build)")

print(f"checked {checked} name-reached classes against {len(mapped)} mapped classes")
for p in problems:
    print(p)
sys.exit(1 if problems else 0)

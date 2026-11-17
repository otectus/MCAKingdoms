#!/usr/bin/env python3
"""Fast resource checks that need no Minecraft (H04): every bundled JSON parses; the three bundled languages have the
same keys and the same placeholders; every keyed message in the Java sources names a key that exists, with an English
fallback equal to en_us. Run: python3 -I tools/test/validate_resources.py"""
import json, pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import outcome
ROOT = pathlib.Path(__file__).resolve().parents[2]
problems = []
for path in sorted((ROOT / 'src/main/resources').rglob('*.json')) + sorted((ROOT / 'examples').rglob('*.json')):
    if '/invalid/' in path.as_posix(): continue  # the invalid examples are meant to fail their loaders, not JSON parsing
    try: json.loads(path.read_text(encoding='utf-8'))
    except (ValueError, UnicodeDecodeError) as failure: problems.append(f'{path.relative_to(ROOT)}: {failure}')
lang = {l: json.loads((ROOT / f'src/main/resources/assets/mcakingdoms/lang/{l}.json').read_text(encoding='utf-8')) for l in ('en_us', 'es_es', 'zh_cn')}
placeholder = re.compile(r'%(?:\d+\$)?s')
for key, english in lang['en_us'].items():
    for locale in ('es_es', 'zh_cn'):
        other = lang[locale].get(key)
        if other is None: problems.append(f'{locale} lacks {key}'); continue
        if len(placeholder.findall(other.replace('%%', ''))) != len(placeholder.findall(english.replace('%%', ''))):
            problems.append(f'{locale} {key}: placeholder count differs from en_us')
for locale in ('es_es', 'zh_cn'):
    problems.extend(f'{locale} has extra key {key}' for key in lang[locale] if key not in lang['en_us'])
# A key is a literal, or a file's own prefix constant plus a literal (static final String P = "a.b."; P + "c"). A prefix
# constant may be built on another class's constant (P = DynastyService.P + "page.") or be that constant
# (P = DevelopmentService.P;); the class is looked up by name, so several classes may each have their own P.
call = re.compile(r'Keyed\.(?:text|refuse|require)\((?:[^"]*?[,(]\s*)??(?:([A-Z]+)\s*\+\s*)?"([a-z0-9_.]+)"\s*,\s*"((?:[^"\\]|\\.)*)"')
constant = re.compile(r'static final String ([A-Z]+)\s*=\s*(?:([A-Za-z]+)\.([A-Z]+)\s*(?:\+\s*)?)?(?:"([a-z0-9_.]*)")?\s*;')
prefixes, classes = {}, {}
for path in (ROOT / 'src/main/java').rglob('*.java'):
    classes.setdefault(path.stem, []).append(path)
    for name, owner, outer, value in constant.findall(path.read_text(encoding='utf-8')):
        prefixes.setdefault(path, {})[name] = (owner, outer, value)
def prefix(path, name, depth=0):
    entry = prefixes.get(path, {}).get(name)
    if entry is None or depth > 8: return None
    owner, outer, value = entry
    if not owner: return value
    found = [p for p in classes.get(owner, []) if outer in prefixes.get(p, {})]
    head = prefix(found[0], outer, depth + 1) if len(found) == 1 else None
    return None if head is None else head + value
for path in sorted((ROOT / 'src/main/java').rglob('*.java')):
    for match in call.finditer(path.read_text(encoding='utf-8')):
        head = prefix(path, match.group(1)) if match.group(1) else ''
        if head is None: problems.append(f'{path.name}: keyed message uses unknown prefix {match.group(1)}'); continue
        key, fallback = head + match.group(2), json.loads('"' + match.group(3) + '"')
        if key not in lang['en_us']: problems.append(f'{path.name}: keyed message {key} has no en_us entry')
        elif lang['en_us'][key] != fallback: problems.append(f'{path.name}: {key} fallback differs from en_us')
if problems:
    for problem in problems[:60]: print(problem)
    outcome.finish('FAIL', f'{len(problems)} resource problem(s)', 'resources')
outcome.finish('PASS', f'JSON parses; {len(lang["en_us"])} keys in each language with matching placeholders; keyed messages match en_us', 'resources')

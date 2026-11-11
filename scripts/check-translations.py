#!/usr/bin/env python3
"""Checks every app/src/main/res/values-<locale>/ folder against values/.

Usage: scripts/check-translations.py [--res DIR] [--only values-xx ...] [--quiet]
Exit 0 when clean, 1 when any problem is found.

Per folder it checks:
  * valid XML
  * the same translatable keys as values/ (string, plurals, string-array; translatable="false" skipped)
  * placeholders (%1$s, %d, %%, ...) kept, same index and type
  * HTML tags and CDATA kept
  * Android escaping (apostrophes, quotes, leading @ or ?, aapt2 rules)
  * plural categories: the CLDR set for the language (see PLURALS)
  * no English left: values identical to English, or long runs of English words
Brand and product names live in scripts/translation-allowlist.txt (one token per line).
Values that may stay identical to English in one language: "<locale> <key>" lines in the same file.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_RES = os.path.join(HERE, "..", "app", "src", "main", "res")

# language -> (required categories, allowed categories). CLDR 44 plus what Android lint requires.
# Languages whose CLDR set has "many" for fractions or millions only (es fr it pt ca he cs) do not
# have to supply it, but may.
OTHER = ({"other"}, {"other"})
ONE_OTHER = ({"one", "other"}, {"one", "other"})
ONE_MANY_OTHER = ({"one", "other"}, {"one", "many", "other"})
SLAVIC = ({"one", "few", "many", "other"}, {"one", "few", "many", "other"})
PLURALS = {
    # one category
    **{l: OTHER for l in "ja zh ko th vi id ms km yo ig lo my".split()},
    # one + other
    **{l: ONE_OTHER for l in
       "en de nl sv da nb no fi af sw ha zu xh st tn ne hi mr bn gu kn ml te ta ur fa am tr el hu fil tl pa".split()},
    # one, many (millions), other
    **{l: ONE_MANY_OTHER for l in "es fr it pt ca".split()},
    "ru": SLAVIC, "uk": SLAVIC, "pl": SLAVIC,
    "cs": ({"one", "few", "other"}, {"one", "few", "many", "other"}),
    "ro": ({"one", "few", "other"}, {"one", "few", "other"}),
    "ar": ({"zero", "one", "two", "few", "many", "other"}, {"zero", "one", "two", "few", "many", "other"}),
    "he": ({"one", "two", "other"}, {"one", "two", "many", "other"}),
}
ALIAS = {"iw": "he", "in": "id", "ji": "yi", "tl": "fil"}

# Scripts that are not Latin: any run of Latin words there is English left over.
NON_LATIN = set("ja zh ko th km ne hi mr bn ta te gu kn ml pa ar he fa ur am ru uk el".split())

# Words that are English and nothing else; a run of them means untranslated text.
ENGLISH_ONLY = set("""the your you with this that these those will would have has been from when before after
while into onto about without between because until once are was were not and or if then than also only just
tap open off on enable disable allow deny turn keep choose select connect connected permission permissions
settings setting message messages agent phone helper helpers memory task tasks try again loading failed
error send sent reply reset delete remove add cancel done next back continue skip later now never always
please wait needs need can't cannot don't won't it's""".split())
ENGLISH_STOP = {"the", "your", "you", "with", "this", "that", "will", "have", "from", "when", "before",
                "after", "are", "was", "not", "and", "into", "about", "without", "because", "until", "then",
                "than", "also", "only", "just", "tap", "please", "wait", "need", "needs"}

NOFORMAT = set()  # names of strings marked formatted="false"
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])")
TAG = re.compile(r"</?[a-zA-Z][^>]*>")


def lang_of(folder):
    q = folder[len("values-"):]
    if q.startswith("b+"):
        q = q[2:].split("+")[0]
    lang = q.split("-")[0]
    return ALIAS.get(lang, lang)


def load_allow():
    brands, identical = set(), set()
    path = os.path.join(HERE, "translation-allowlist.txt")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.split("#")[0].strip()
            if not line:
                continue
            if " " in line:
                identical.add(tuple(line.split(None, 1)))
            else:
                brands.add(line.lower())
    return brands, identical


def inner_xml(el):
    """The raw text of an element including child tags, as Android sees it."""
    parts = [el.text or ""]
    for c in el:
        parts.append("<%s%s>" % (c.tag, "".join(' %s="%s"' % kv for kv in c.attrib.items())))
        parts.append(inner_xml(c))
        parts.append("</%s>" % c.tag)
        parts.append(c.tail or "")
    return "".join(parts)


def read_resources(path, problems, label):
    """-> {(kind,key): value}; value is str, {quantity: str} or [str]."""
    out = {}
    try:
        tree = ET.parse(path)
    except ET.ParseError as e:
        problems.append("%s: invalid XML: %s" % (label, e))
        return out
    for el in tree.getroot():
        if el.tag not in ("string", "plurals", "string-array", "integer-array"):
            continue
        if el.get("translatable") == "false":
            continue
        name = el.get("name")
        if not name:
            problems.append("%s: <%s> without a name" % (label, el.tag))
            continue
        if el.get("formatted") == "false":
            NOFORMAT.add(name)
        if el.tag == "string":
            out[("string", name)] = inner_xml(el)
        elif el.tag == "plurals":
            out[("plurals", name)] = {i.get("quantity"): inner_xml(i) for i in el.findall("item")}
        elif el.tag == "string-array":
            out[("array", name)] = [inner_xml(i) for i in el.findall("item")]
    return out


def read_folder(res, folder, problems):
    d = os.path.join(res, folder)
    out = {}
    for f in sorted(os.listdir(d)):
        if not f.endswith(".xml"):
            continue
        if f.startswith("colors") or f.startswith("icon_colors") or f.startswith("themes"):
            continue
        part = read_resources(os.path.join(d, f), problems, "%s/%s" % (folder, f))
        for k, v in part.items():
            if k in out:
                problems.append("%s/%s: duplicate %s '%s'" % (folder, f, k[0], k[1]))
            out[k] = v
    return out


def placeholders(s):
    """(indexed multiset sorted, unindexed ordered list)"""
    indexed, plain = [], []
    for m in PLACEHOLDER.finditer(s):
        idx, conv = m.group(1), m.group(2)
        if conv == "%" or conv == "n":
            continue
        (indexed if idx else plain).append((idx, conv) if idx else conv)
    return sorted(indexed), plain


def pct_literals(s):
    return len(re.findall(r"%%", s))


def check_escape(s, where, problems, formatted=True):
    m = re.search(r"'([^']+)'$", where)
    if m and m.group(1) in NOFORMAT:
        formatted = False
    v = s.strip()
    quoted = len(v) >= 2 and v.startswith('"') and v.endswith('"')
    body = v[1:-1] if quoted else v
    if not quoted:
        if re.search(r"(?<!\\)'", body):
            problems.append("%s: apostrophe not escaped (use \\')" % where)
        if re.search(r'(?<!\\)"', body):
            problems.append('%s: quote not escaped (use \\")' % where)
    else:
        if re.search(r'(?<!\\)"', body):
            problems.append('%s: quote inside a quoted string not escaped' % where)
    if body[:1] in ("@", "?") and body[:2] not in ("\\@", "\\?"):
        problems.append("%s: starts with @ or ? without a backslash" % where)
    if re.search(r"\\(?![nt'\"@?\\u])", body):
        problems.append("%s: unknown backslash escape" % where)
    if formatted:
        _, plain = placeholders(s)
        if len(plain) > 1:
            problems.append("%s: several %%s/%%d without positions (use %%1$s %%2$s)" % where)
        # a lone % that is not a conversion would crash String.format
        stripped = PLACEHOLDER.sub("", s)
        if re.search(r"%", stripped):
            problems.append("%s: stray %% (write %%%%)" % where)


def words(s):
    s = PLACEHOLDER.sub(" ", s)
    s = TAG.sub(" ", s)
    return re.findall(r"[A-Za-z][A-Za-z'’]*", s)


def english_leak(lang, en, tr, key, brands, identical):
    """Return a message or None."""
    if (lang, key) in identical or ("*", key) in identical:
        return None
    en_w = [w for w in words(en) if w.lower() not in brands]
    tr_w = [w for w in words(tr) if w.lower() not in brands]
    if not tr_w:
        return None
    if tr.strip() == en.strip() and len(en_w) >= 3:
        return "identical to English"
    if tr.strip().lower() == en.strip().lower() and len(en_w) >= 3 and lang not in ("fil", "id", "ms"):
        return "identical to English (case aside)"
    if lang in NON_LATIN:
        run = best = 0
        for tok in re.findall(r"[^\s]+", PLACEHOLDER.sub(" ", TAG.sub(" ", tr))):
            core = re.sub(r"[^A-Za-z'’]", "", tok)
            if core and core == re.sub(r"[^\w'’]", "", tok) and core.lower() not in brands and re.fullmatch(r"[A-Za-z'’]+", core):
                run += 1
                best = max(best, run)
            else:
                run = 0
        if best >= 4:
            return "English run of %d words" % best
        return None
    # Latin script: runs of words that only English uses
    run = best = 0
    for w in re.findall(r"[^\W\d_]+(?:['’][^\W\d_]+)*", PLACEHOLDER.sub(" ", TAG.sub(" ", tr)), re.U):
        if w.lower() in ENGLISH_STOP and w.lower() not in brands:
            run += 1
            best = max(best, run)
        else:
            run = 0
    # a lone stop word between translated words is fine; two in a row is English
    if best >= 3:
        return "English words in a row (%d)" % best
    low = [w.lower() for w in tr_w]
    if len(low) >= 4 and sum(1 for w in low if w in ENGLISH_STOP) / len(low) >= 0.34:
        return "mostly English"
    return None


def check_folder(res, folder, source, brands, identical):
    problems = []
    lang = lang_of(folder)
    tr = read_folder(res, folder, problems)
    src_keys, tr_keys = set(source), set(tr)
    for k in sorted(src_keys - tr_keys):
        problems.append("%s: missing %s '%s'" % (folder, k[0], k[1]))
    for k in sorted(tr_keys - src_keys):
        problems.append("%s: extra %s '%s' (not in values/)" % (folder, k[0], k[1]))
    req, allowed = PLURALS.get(lang, (None, None))
    if req is None:
        problems.append("%s: no CLDR plural data for language '%s'" % (folder, lang))
    for k in sorted(src_keys & tr_keys):
        kind, name = k
        s, t = source[k], tr[k]
        where = "%s %s '%s'" % (folder, kind, name)
        if kind == "string":
            pairs = [(s, t, where, name)]
        elif kind == "array":
            if len(s) != len(t):
                problems.append("%s: %d items, source has %d" % (where, len(t), len(s)))
            pairs = [(a, b, "%s[%d]" % (where, i), "%s[%d]" % (name, i)) for i, (a, b) in enumerate(zip(s, t))]
        else:
            cats = set(t)
            if req is not None:
                miss, extra = req - cats, cats - allowed
                if miss:
                    problems.append("%s: plural categories missing %s (language %s needs %s)" % (where, sorted(miss), lang, sorted(req)))
                if extra:
                    problems.append("%s: plural categories not used by %s: %s" % (where, lang, sorted(extra)))
            src_other = s.get("other", next(iter(s.values()), ""))
            pairs = []
            for q, text in t.items():
                ref = src_other
                if q == "other" or q not in ("one",):
                    ref_i, ref_p = placeholders(src_other)
                    ti, tp = placeholders(text)
                    # every category may drop the number only if the sentence says it in words
                    if sorted(ti) != ref_i and not (q in ("one", "two", "zero") and set(ti) <= set(ref_i)):
                        problems.append("%s[%s]: placeholders %s differ from source %s" % (where, q, ti, ref_i))
                    if len(tp) != len(ref_p) and not (q in ("one", "two", "zero") and len(tp) <= len(ref_p)):
                        problems.append("%s[%s]: placeholder count differs" % (where, q))
                else:
                    ti, tp = placeholders(text)
                    ref_i, ref_p = placeholders(src_other)
                    if not set(ti) <= set(ref_i) or len(tp) > len(ref_p):
                        problems.append("%s[%s]: placeholders not in source" % (where, q))
                check_escape(text, "%s[%s]" % (where, q), problems)
                tags_s, tags_t = sorted(TAG.findall(src_other)), sorted(TAG.findall(text))
                if tags_s != tags_t:
                    problems.append("%s[%s]: HTML tags differ %s vs %s" % (where, q, tags_t, tags_s))
                leak = english_leak(lang, src_other, text, "%s.%s" % (name, q), brands, identical)
                if leak:
                    problems.append("%s[%s]: %s: %r" % (where, q, leak, text[:80]))
            continue
        for a, b, w, key in pairs:
            ai, ap = placeholders(a)
            bi, bp = placeholders(b)
            if (ai != bi or ap != bp) and key not in NOFORMAT:
                problems.append("%s: placeholders %s%s differ from source %s%s" % (w, bi, bp, ai, ap))
            if pct_literals(a) != pct_literals(b):
                problems.append("%s: %%%% count differs" % w)
            if sorted(TAG.findall(a)) != sorted(TAG.findall(b)):
                problems.append("%s: HTML tags differ: %s vs source %s" % (w, TAG.findall(b), TAG.findall(a)))
            if ("CDATA" in a) != ("CDATA" in b):
                problems.append("%s: CDATA differs" % w)
            check_escape(b, w, problems)
            if not b.strip():
                problems.append("%s: empty" % w)
            leak = english_leak(lang, a, b, key, brands, identical)
            if leak:
                problems.append("%s: %s: %r" % (w, leak, b[:80]))
    return problems


def main(argv):
    res = DEFAULT_RES
    only, quiet = [], False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--res":
            res = argv[i + 1]
            i += 1
        elif a == "--only":
            only = argv[i + 1:]
            break
        elif a == "--quiet":
            quiet = True
        i += 1
    res = os.path.abspath(res)
    brands, identical = load_allow()
    src_problems = []
    source = read_folder(res, "values", src_problems)
    # source: only escaping, so a bad English string is caught too
    for (kind, name), v in source.items():
        vals = [v] if kind == "string" else (list(v.values()) if kind == "plurals" else v)
        for j, x in enumerate(vals):
            check_escape(x, "values %s '%s'" % (kind, name), src_problems)
    folders = sorted(d for d in os.listdir(res) if d.startswith("values-") and re.match(r"values-[a-z]{2,3}(-r[A-Z]{2})?$|values-b\+", d))
    folders = [d for d in folders if not (d in ("values-night",) or re.match(r"values-(night|land|port|v\d+|sw\d+dp|w\d+dp|h\d+dp)", d))]
    if only:
        folders = [d for d in folders if d in only]
    total = list(src_problems)
    for f in folders:
        p = check_folder(res, f, source, brands, identical)
        if not quiet:
            print("%-16s %s" % (f, "ok" if not p else "%d problem(s)" % len(p)))
        total += p
    for p in total:
        print("  " + p)
    print("%d locale folder(s) checked, %d problem(s)" % (len(folders), len(total)))
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

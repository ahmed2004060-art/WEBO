#!/usr/bin/env python3
"""
webo_refactor.py — يقسّم كلاس Kotlin ضخماً (MainActivity) إلى ملفات متخصصة تلقائياً.

الفكرة: كل دالة عضو تتحول إلى «دالة امتداد» (extension) على نفس الكلاس داخل ملف منفصل
          internal fun MainActivity.name(...)
فيبقى سلوك الكود كما هو حرفياً (نفس الجسم، نفس الاستدعاءات)، مع:
  * الحالة (الخصائص) والـ lifecycle والكلاسات الداخلية Tab/Bridge تبقى في MainActivity
  * كل private في الأعضاء الباقية يصبح internal ليراها بقية الملفات
  * الكلاسات المساعدة غير الداخلية تنتقل إلى Models.kt
  * نصوص JS الضخمة تنتقل إلى JsScripts.kt
  * الدوال غير المصنّفة تُوزَّع تلقائياً على الملف الأكثر ارتباطاً بها (call-graph)

الاستخدام:
  python3 webo_refactor.py PROJECT_DIR            # تنفيذ
  python3 webo_refactor.py PROJECT_DIR --dry-run  # تقرير فقط بلا كتابة
  python3 webo_refactor.py PROJECT_DIR --verify   # تحقق من مشروع مقسّم سابقاً
"""
import sys, os, re, json, shutil, argparse, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ktlex

HERE = os.path.dirname(os.path.abspath(__file__))

# ------------------------------------------------------------------ parsing
MOD_WORDS = r'(?:private|internal|public|protected|override|open|abstract|inner|data|lateinit|const|final|inline|tailrec|suspend|operator|infix|sealed|enum|@Volatile|@JvmField)'
DECL_RE = re.compile(r'^(\s*)((?:@\w+(?:\([^)]*\))?\s+)*)((?:' + MOD_WORDS + r'\s+)*)(class|object|interface|fun|val|var|init)\b')

def parse_class(src, cls):
    lines = src.split('\n')
    d, p, _, _, instr = ktlex.scan(src)
    cs = next(i for i, l in enumerate(lines, 1) if re.match(r'^class\s+' + cls + r'\b', l))
    ce = max(i for i, l in enumerate(lines, 1) if l == '}' and d[i] == 1)
    cand = []
    for i in range(cs + 1, ce):
        l = lines[i - 1]
        if i in instr or d[i] != 1 or p[i] != 0: continue
        if not l.startswith('    ') or l.startswith('     ') or not l.strip(): continue
        s = l.strip()
        if s[0] in '})].': continue
        only_ann = re.fullmatch(r'(@\w+(\([^)]*\))?\s*)+', s) is not None
        if s.startswith(('//', '/*', '*')) or only_ann: cand.append((i, 'lead'))
        else: cand.append((i, 'decl'))
    mem, pend = [], None
    for i, k in cand:
        if k == 'lead':
            if pend is None: pend = i
            continue
        mem.append([pend or i, i]); pend = None
    out = []
    for idx, (a, dl) in enumerate(mem):
        nxt = mem[idx + 1][0] if idx + 1 < len(mem) else ce
        b = nxt - 1
        while b > a and not lines[b - 1].strip(): b -= 1
        out.append(dict(a=a, b=b, decl=dl))
    for m in out:
        s = lines[m['decl'] - 1]
        mm = DECL_RE.match(s)
        m['kind'] = mm.group(4) if mm else '?'
        m['mods'] = mm.group(3).split() if mm else []
        m['private'] = 'private' in m['mods']
        m['override'] = 'override' in m['mods']
        m['inner'] = 'inner' in m['mods']
        nm = None
        if m['kind'] == 'fun':
            r = re.search(r'\bfun\s+(?:<.*?>\s+)?(?:([\w.<>?, ]+?)\.)?(\w+)\s*\(', s)
            if r: nm = r.group(2); m['recv'] = r.group(1)
        elif m['kind'] in ('class', 'object', 'interface'):
            r = re.search(r'\b(?:class|object|interface)\s+(\w+)', s); nm = r and r.group(1)
        elif m['kind'] in ('val', 'var'):
            r = re.search(r'\b(?:val|var)\s+(\w+)', s); nm = r and r.group(1)
        m['name'] = nm
    return lines, cs, ce, out, instr

def is_banner(l):
    return re.match(r'^\s*//\s*-{3,}', l) is not None

# ------------------------------------------------------------------ classification
def classify(funcs, texts, cfg, existing=None):
    explicit = {}
    for g, names in cfg['groups'].items():
        for n in names:
            explicit[n] = g
    assign, how = {}, {}
    existing = existing or {}
    names_all = {f['name'] for f in funcs}
    unknown = [n for n in explicit if n not in names_all]
    for f in funcs:
        n = f['name']
        if n in explicit: assign[n] = explicit[n]; how[n] = 'explicit'; continue
        for pat, g in cfg['prefix_rules'].items():
            if re.match(pat, n): assign[n] = g; how[n] = 'prefix'; break
    # call-graph affinity for the rest
    calls = {n: set(re.findall(r'\b(\w+)\s*\(', texts[n])) & names_all for n in names_all}
    rest = [f['name'] for f in funcs if f['name'] not in assign]
    for _ in range(3):
        for n in list(rest):
            score = collections.Counter()
            for o, g in assign.items():
                if o in calls[n]: score[g] += 1
                if n in calls[o]: score[g] += 1
            # دوال موجودة أصلاً في الملفات المقسّمة سابقاً (تشغيل تزايدي)
            for o in set(re.findall(r'\b(\w+)\s*\(', texts[n])) & set(existing):
                score[existing[o]] += 1
            if score:
                assign[n] = score.most_common(1)[0][0]; how[n] = 'affinity'; rest.remove(n)
    for n in rest:
        assign[n] = cfg['fallback_file']; how[n] = 'fallback'
    return assign, how, unknown

# ------------------------------------------------------------------ rewriting
def split_header(line, cls, fname):
    """يحوّل سطر تعريف fun إلى internal fun Cls.name"""
    m = re.match(r'^(\s*)((?:@\w+(?:\([^)]*\))?\s+)*)((?:' + MOD_WORDS + r'\s+)*)fun\s+', line)
    assert m, line
    mods = [w for w in m.group(3).split() if w not in ('private', 'public', 'internal')]
    rest = line[m.end():]
    tp = ''
    if rest.startswith('<'):
        depth = 0
        for k, ch in enumerate(rest):
            depth += ch == '<'; depth -= ch == '>'
            if depth == 0: tp = rest[:k + 1] + ' '; rest = rest[k + 1:].lstrip(); break
    return m.group(1) + m.group(2) + ' '.join(['internal'] + mods + ['fun']) + ' ' + tp + cls + '.' + rest

def dedent(block, start_line, instr):
    out = []
    for off, l in enumerate(block):
        ln = start_line + off
        out.append(l if ln in instr else (l[4:] if l.startswith('    ') else l))
    return out

def drop_banners(block_lines, decl_idx=None):
    """يحذف أسطر العناوين الفاصلة // ---- من مقدمة العضو فقط (لا يمس الأجسام)."""
    n = len(block_lines) if decl_idx is None else decl_idx
    return [l for i, l in enumerate(block_lines) if not (i < n and is_banner(l))]

def used_imports(imports, body):
    keep = []
    for imp in imports:
        mm = re.match(r'import\s+([\w.]+?)(?:\s+as\s+(\w+))?\s*$', imp)
        if not mm: keep.append(imp); continue
        simple = mm.group(2) or mm.group(1).split('.')[-1]
        if simple == '*' or re.search(r'(?<![\w])' + re.escape(simple) + r'(?![\w])', body): keep.append(imp)
    return keep

# ------------------------------------------------------------------ main transform
def run(project, dry=False):
    cfg = json.load(open(os.path.join(HERE, 'organize.json'), encoding='utf-8'))
    cls = cfg['class']
    src_path = os.path.join(project, cfg['source'])
    pkg_dir = os.path.dirname(src_path)
    src = open(src_path, encoding='utf-8').read()
    lines, cs, ce, mem, instr = parse_class(src, cls)
    imports = [l for l in lines[:cs] if l.startswith('import ')]
    header = [l for l in lines[:cs] if l.startswith('package ')][0]

    funcs, models, jsvals, keep = [], [], [], []
    for m in mem:
        if m['kind'] == 'fun' and not m['override'] and not m.get('recv'): funcs.append(m)
        elif m['kind'] == 'class' and not m['inner'] and m['private']: models.append(m)
        elif m['kind'] == 'val' and m['name'] and m['name'].endswith('_JS') and '"""' in lines[m['decl'] - 1]: jsvals.append(m)
        else: keep.append(m)
    texts = {}
    for f in funcs:
        texts[f['name']] = '\n'.join(lines[f['a'] - 1:f['b']])
    dups = [n for n, c in collections.Counter(f['name'] for f in funcs).items() if c > 1]
    # overloads: نفس الاسم يذهب إلى نفس الملف
    existing = {}
    for fn_ in os.listdir(pkg_dir):
        if fn_.endswith('.kt') and fn_[:-3] in cfg['descriptions']:
            for l_ in open(os.path.join(pkg_dir, fn_), encoding='utf-8'):
                m_ = re.match(r'^(?:\w+\s+)*fun\s+(?:<.*?>\s+)?%s\.(\w+)\s*\(' % cls, l_)
                if m_: existing[m_.group(1)] = fn_[:-3]
    assign, how, unknown = classify(funcs, texts, cfg, existing)

    files = collections.defaultdict(list)   # file -> list of (kind, member)
    for f in funcs: files[assign[f['name']]].append(f)

    # ---- build bodies
    def block_of(m, kind):
        blk = lines[m['a'] - 1:m['b']]
        blk = drop_banners(blk)
        return blk
    outputs = {}
    label_fixes = 0
    for fname, fl in files.items():
        parts = []
        for f in fl:
            a, b, dl = f['a'], f['b'], f['decl']
            blk = dedent(lines[a - 1:b], a, instr)
            # أسطر الـ banner تُحذف قبل/بعد التعديل
            idx = dl - a
            blk[idx] = split_header(blk[idx], cls, f['name'])
            txt = '\n'.join(drop_banners(blk, idx))
            n_before = txt.count('this@' + cls)
            txt = txt.replace('this@' + cls, 'this@' + f['name']); label_fixes += n_before
            parts.append(txt)
        outputs[fname] = parts

    def make_file(fname, body):
        imp = [] if fname == cfg['js_file'] else list(used_imports(imports, body))
        extra = []
        if re.search(r'\bTab\b', body): extra.append('import com.webo.%s.Tab' % cls)
        if re.search(r'\bBridge\b', body): extra.append('import com.webo.%s.Bridge' % cls)
        doc = cfg['descriptions'].get(fname, '')
        head = header + '\n\n' + '\n'.join(sorted(set(imp + extra))) + '\n\n'
        if doc:
            head += '// ' + doc + '\n'
            if fname not in (cfg['models_file'], cfg['js_file']):
                head += '// (دوال امتداد على %s — منقولة تلقائياً بلا تغيير في الأجسام)\n' % cls
            head += '\n'
        return head + body.rstrip('\n') + '\n'

    new_files = {}
    for fname, parts in outputs.items():
        new_files[fname] = make_file(fname, '\n\n'.join(parts))

    # ---- Models
    mparts = []
    for m in models:
        blk = dedent(lines[m['a'] - 1:m['b']], m['a'], instr)
        idx = m['decl'] - m['a']
        blk[idx] = re.sub(r'\bprivate\b', 'internal', blk[idx], count=1)
        mparts.append('\n'.join(drop_banners(blk, idx)))
    new_files[cfg['models_file']] = make_file(cfg['models_file'], '\n\n'.join(mparts))

    # ---- JS
    jparts = []
    for m in jsvals:
        blk = lines[m['a'] - 1:m['b']]       # لا dedent: داخل raw string
        idx = m['decl'] - m['a']
        blk[idx] = re.sub(r'^\s*(?:private\s+)?val\b', 'internal val', blk[idx], count=1)
        # أول سطر كان بإزاحة 4: نزيلها من سطر التعريف فقط
        blk[idx] = blk[idx].lstrip()
        jparts.append('\n'.join(drop_banners(blk, idx)))
    new_files[cfg['js_file']] = make_file(cfg['js_file'], '\n\n'.join(jparts))

    # ---- المتبقي في MainActivity
    moved = {id(x) for x in funcs + models + jsvals}
    remove_lines = set()
    for m in funcs + models + jsvals:
        remove_lines.update(range(m['a'], m['b'] + 1))
    kept = []
    for i, l in enumerate(lines, 1):
        if i in remove_lines: continue
        kept.append(l)
    # تحويل private -> internal للأعضاء الباقية (مستوى depth 1 فقط)
    kept_text = []
    keep_decl = {m['decl'] for m in keep}
    for i, l in enumerate(lines, 1):
        if i in remove_lines: continue
        if i in keep_decl and re.search(r'\bprivate\b', l.split('=')[0].split('(')[0]):
            l = re.sub(r'\bprivate\b', 'internal', l, count=1)
        kept_text.append(l)
    # تنظيف الأسطر الفارغة المتتالية وبنرات يتيمة
    cleaned, blank = [], 0
    for l in kept_text:
        if not l.strip():
            blank += 1
            if blank > 1: continue
        else: blank = 0
        cleaned.append(l)
    # إعادة بناء الاستيرادات
    body_start = next(i for i, l in enumerate(cleaned) if re.match(r'^class\s+' + cls, l))
    body = '\n'.join(cleaned[body_start:])
    imp = used_imports(imports, body)
    # فهرس الملفات في رأس الكلاس
    index = ['/**', ' * %s — نقطة الدخول والحالة فقط.' % cls, ' * الدوال نُقلت تلقائياً إلى ملفات متخصصة (دوال امتداد على %s):' % cls]
    for fname in sorted(new_files):
        index.append(' *   %-18s %s' % (fname + '.kt', cfg['descriptions'].get(fname, '')))
    index.append(' */')
    new_main = header + '\n\n' + '\n'.join(imp) + '\n\n' + '\n'.join(index) + '\n' + body
    if not new_main.endswith('\n'): new_main += '\n'

    report = dict(total_funcs=len(funcs), models=len(models), js=len(jsvals), kept=len(keep),
                  label_fixes=label_fixes, unknown_names=unknown, dup_overloads=dups,
                  how=collections.Counter(how.values()),
                  fallback=[n for n, h in how.items() if h == 'fallback'],
                  affinity=[(n, assign[n]) for n, h in how.items() if h == 'affinity'])
    if dry:
        print(json.dumps(report, ensure_ascii=False, indent=1, default=dict))
        return report, new_files, new_main, (lines, mem, funcs, models, jsvals, assign)
    if not (funcs or models or jsvals):
        print('لا توجد دوال أو كلاسات قابلة للنقل داخل %s — لا شيء لتغييره.' % cls)
        return report, {}, src, None
    # ---- snapshot قبل أي كتابة (للتحقق وللرجوع)
    import time
    snap = os.path.join(project, '.refactor_backup', time.strftime('%Y%m%d-%H%M%S'))
    n_ = 1
    while os.path.exists(snap):                # لا نكتب فوق نسخة سابقة
        n_ += 1; snap = os.path.join(project, '.refactor_backup', time.strftime('%Y%m%d-%H%M%S') + '-%d' % n_)
    os.makedirs(snap)
    shutil.copy(src_path, snap)
    for fname in cfg['descriptions']:           # كل الملفات المُدارة، لا المتأثرة فقط
        fp = os.path.join(pkg_dir, fname + '.kt')
        if os.path.exists(fp): shutil.copy(fp, snap)
    # ---- كتابة: ملف جديد، أو إلحاق بملف موجود (لا نكتب فوق عمل سابق)
    for fname, text in new_files.items():
        fp = os.path.join(pkg_dir, fname + '.kt')
        if os.path.exists(fp):
            old = open(fp, encoding='utf-8').read()
            new_body = text.split('\n\n', 2)[-1] if False else text[text.index('\n\n', text.rindex('\nimport ') if '\nimport ' in text else 0) + 2:]
            # الاستيرادات الناقصة
            need = [l for l in text.split('\n') if l.startswith('import ') and l not in old]
            if need:
                ls = old.split('\n')
                last = max([i for i, l in enumerate(ls) if l.startswith('import ')] or [0])
                ls[last + 1:last + 1] = need
                old = '\n'.join(ls)
            parts_text = '\n\n'.join(ptxt for ptxt in (outputs.get(fname) or [])) if fname in outputs else None
            if fname == cfg['models_file']: parts_text = '\n\n'.join(mparts)
            if fname == cfg['js_file']: parts_text = '\n\n'.join(jparts)
            open(fp, 'w', encoding='utf-8').write(old.rstrip('\n') + '\n\n' + parts_text.rstrip('\n') + '\n')
        else:
            open(fp, 'w', encoding='utf-8').write(text)
    # ---- فهرس الملفات في MainActivity يعاد بناؤه من الملفات الموجودة فعلاً
    present = [n for n in sorted(cfg['descriptions']) if os.path.exists(os.path.join(pkg_dir, n + '.kt'))]
    idx = ['/**', ' * %s — نقطة الدخول والحالة فقط.' % cls, ' * الدوال نُقلت تلقائياً إلى ملفات متخصصة (دوال امتداد على %s):' % cls]
    for n in present: idx.append(' *   %-18s %s' % (n + '.kt', cfg['descriptions'][n]))
    idx.append(' */')
    mt = new_main
    mt = re.sub(r'/\*\*\n \* %s — نقطة الدخول.*?\n \*/\n' % cls, '', mt, flags=re.S)
    mt = mt.replace('\nclass %s' % cls, '\n' + '\n'.join(idx) + '\nclass %s' % cls, 1)
    open(src_path, 'w', encoding='utf-8').write(mt)
    print('نسخة احتياطية:', snap)
    return report, new_files, mt, (lines, mem, funcs, models, jsvals, assign)

# ------------------------------------------------------------------ verification
def _norm(l):
    s = l.strip()
    s = re.sub(r'\b(private|internal)\s+', '', s)
    s = re.sub(r'\b(?:fun\s+(?:<.*?>\s+)?)' + 'MAINCLS' + r'\.', lambda m: m.group(0).replace('MAINCLS.', ''), s)
    return s

def verify(project):
    cfg = json.load(open(os.path.join(HERE, 'organize.json'), encoding='utf-8'))
    cls = cfg['class']
    src_path = os.path.join(project, cfg['source'])
    pkg_dir = os.path.dirname(src_path)
    bdir = os.path.join(project, '.refactor_backup')
    snaps = sorted(d for d in os.listdir(bdir) if os.path.isdir(os.path.join(bdir, d)))
    if not snaps: return ['لا توجد نسخة احتياطية للمقارنة'], 0, 0, 0
    snap = os.path.join(bdir, snaps[-1])
    problems = []
    def read(p): return open(p, encoding='utf-8').read()
    def key(l):
        s = l.strip()
        if not s or s.startswith(('import ', 'package ')): return None
        s = re.sub(r'\b(private|internal)\s+', '', s)
        s = re.sub(r'\b' + cls + r'\.(?=\w+\s*\()', '', s)
        s = re.sub(r'this@\w+', 'this@', s)
        return s
    cnt_o, cnt_n = collections.Counter(), collections.Counter()
    for f in os.listdir(snap):
        for l in read(os.path.join(snap, f)).split('\n'):
            k = key(l)
            if k: cnt_o[k] += 1
    names = ['MainActivity'] + [n for n in sorted(cfg['descriptions']) if os.path.exists(os.path.join(pkg_dir, n + '.kt'))]
    files = [os.path.join(pkg_dir, n + '.kt') for n in names]
    for p in files:
        text = read(p)
        d, pp, n_, end, _ = ktlex.scan(text)
        if end[:2] != (0, 0) or end[2] != 0: problems.append('أقواس غير متوازنة في ' + os.path.basename(p))
        for l in text.split('\n'):
            k = key(l)
            if k: cnt_n[k] += 1
    only_o, only_n = cnt_o - cnt_n, cnt_n - cnt_o
    for k, v in only_o.items():
        if not re.match(r'^//\s*-{3,}', k): problems.append('سطر مفقود ×%d: %s' % (v, k[:90]))
    for k, v in only_n.items():
        if not (k.startswith(('//', '/**', '*')) or k == '*/'): problems.append('سطر مضاف غير متوقع ×%d: %s' % (v, k[:90]))
    seen = collections.Counter()
    for p in files:
        for i, l in enumerate(read(p).split('\n'), 1):
            if p != files[0] and re.match(r'^private\b', l): problems.append('%s:%d private على مستوى الملف' % (os.path.basename(p), i))
            m = re.match(r'^(?:\w+\s+)*fun\s+(?:<.*?>\s+)?%s\.(\w+)\s*\(' % cls, l)
            if m: seen[m.group(1)] += 1
    for n, c in seen.items():
        if c > 1: problems.append('دالة مكررة: %s ×%d' % (n, c))
    return problems, sum(cnt_o.values()), sum(cnt_n.values()), sum(seen.values())

if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('project'); ap.add_argument('--dry-run', action='store_true'); ap.add_argument('--verify', action='store_true')
    a = ap.parse_args()
    if a.verify:
        probs, no, nn, nf = verify(a.project)
        print('أسطر الأصل:', no, '| أسطر الناتج:', nn, '| دوال الامتداد:', nf)
        print('\n'.join(probs[:12]) + ('\n... و%d مشكلة أخرى' % (len(probs)-12) if len(probs) > 12 else '') if probs else 'OK: لا فروق في الكود (ما عدا الترويسات والعناوين الفاصلة)')
        sys.exit(1 if probs else 0)
    rep, nf, nm, _ = run(a.project, dry=a.dry_run)
    if not a.dry_run:
        print(json.dumps(rep, ensure_ascii=False, indent=1, default=dict))
        for k, v in sorted(nf.items()): print('%-22s %5d lines' % (k + '.kt', v.count('\n')))
        print('%-22s %5d lines' % ('MainActivity.kt', nm.count('\n')))

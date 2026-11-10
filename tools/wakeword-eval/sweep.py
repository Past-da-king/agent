#!/usr/bin/env python3
"""A quick look at how the keyword model itself behaves over a grid of boost and threshold, WITHOUT the app's speech gate.
Used to choose the three sensitivity steps on the dev set; the numbers that count are WakeAccuracyRun's, which runs the
app's own code on the test set.

usage: sweep.py <model dir with bpe.model> <set dir> <out.tsv> [names=a,b] [boost=1.5,2.5] [thr=0.1,0.2,0.35] [modes=typed,taught] [neg=1]
"""
import os, sys, itertools, tempfile
import numpy as np, soundfile as sf

def load(path):
    a, sr = sf.read(path, dtype="float32"); assert sr == 16000, path
    return a

def task(t):
    import sherpa_onnx, sentencepiece as spm
    mdir, sdir, key, phrase, mode, boost, thr, lthr, neg = t
    E = dict(tokens=f"{mdir}/tokens.txt", encoder=f"{mdir}/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
             decoder=f"{mdir}/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", joiner=f"{mdir}/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx", num_threads=1)
    sp = spm.SentencePieceProcessor(model_file=f"{mdir}/bpe.model")
    clips = [l.rstrip("\n").split("\t") for l in open(f"{sdir}/clips.tsv")]
    typed = " ".join(sp.encode(phrase.upper(), out_type=str))
    lines = {typed: f"{typed} :{boost} #{thr} @WAKE"}
    if mode == "taught":
        rec = sherpa_onnx.OnlineRecognizer.from_transducer(**E, decoding_method="greedy_search", provider="cpu")
        heard = {}
        for c in clips:
            if c[0] == "teach" and c[1] == key:
                s = rec.create_stream(); s.accept_waveform(16000, load(f"{sdir}/{c[2]}")); s.accept_waveform(16000, np.zeros(8000, np.float32)); s.input_finished()
                while rec.is_ready(s): rec.decode_stream(s)
                toks = [x.replace(" ", "▁") for x in rec.get_result_all(s).tokens if x.strip()]
                if len(toks) >= 3: heard[" ".join(toks)] = heard.get(" ".join(toks), 0) + 1
        for k in sorted(heard, key=lambda k: -heard[k])[:4]:
            lines.setdefault(k, f"{k} :{boost} #{lthr} @WAKE")
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f: f.write("\n".join(lines.values()) + "\n"); kf = f.name
    ks = sherpa_onnx.KeywordSpotter(**E, keywords_file=kf, num_trailing_blanks=1, provider="cpu")
    def hits(a):
        s = ks.create_stream(); n = 0; last = -1e9
        for i in range(0, len(a), 1600):
            s.accept_waveform(16000, a[i:i + 1600])
            while ks.is_ready(s):
                ks.decode_stream(s)
                if ks.get_result(s):
                    ks.reset_stream(s)
                    if i - last > 32000: n += 1; last = i
        return n
    out = {}
    for setname in sorted({c[0] for c in clips} - {"teach"}):
        mine = [c for c in clips if c[0] == setname and c[1] == key]
        if mine: out[setname] = (len(mine), sum(1 for c in mine if hits(load(f"{sdir}/{c[2]}")) > 0))
    fa = 0; secs = 0.0
    if neg:
        for l in open(f"{sdir}/neg.tsv"):
            a = load(f"{sdir}/{l.rstrip().split(chr(9))[1]}"); fa += hits(a); secs += len(a) / 16000
    os.unlink(kf)
    row = [key, mode, str(boost), str(thr), str(lthr)] + [f"{k}={v[1]}/{v[0]}" for k, v in out.items()] + [f"fa={fa}", f"hours={secs/3600:.2f}", " | ".join(lines)]
    return "\t".join(row)

if __name__ == "__main__":
    mdir, sdir, outp = sys.argv[1:4]
    opt = dict(a.split("=", 1) for a in sys.argv[4:])
    names = dict(l.rstrip("\n").split("\t") for l in open(f"{sdir}/names.tsv"))
    if "names" in opt: names = {k: v for k, v in names.items() if k in opt["names"].split(",")}
    boosts = [float(x) for x in opt.get("boost", "1.5,2.5").split(",")]
    thrs = [float(x) for x in opt.get("thr", "0.1,0.2,0.35").split(",")]
    lextra = float(opt.get("lextra", "0.0"))
    modes = opt.get("modes", "typed,taught").split(",")
    tasks = [(mdir, sdir, k, p, m, b, t, round(min(0.95, t + lextra), 3), opt.get("neg", "1") == "1") for (k, p), m, b, t in itertools.product(names.items(), modes, boosts, thrs)]
    from multiprocessing import Pool
    with Pool(int(os.environ.get("JOBS", "4"))) as pool, open(outp, "w") as f:
        for r in pool.imap_unordered(task, tasks): f.write(r + "\n"); f.flush()
    print("done", len(tasks))

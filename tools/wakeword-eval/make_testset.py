#!/usr/bin/env python3
"""Builds the audio the wake word accuracy run reads (docs/wake-word.md, WakeAccuracyRun.kt).

Everything is synthetic or from open sets; nothing here ships in the app.
  positives   "Hey <name>" spoken by many Piper voices (LibriTTS-R 904 speakers, VCTK 109, L2-ARCTIC 24 non-native
              speakers, four single voices), at different speeds and loudness, a third clean, the rest with real
              background noise mixed in, some of those with room echo as well.
  teach       three clean clips per name from speakers used nowhere else: the "Say it three times" step.
  near        phrases that sound close and must not wake it.
  negatives   hours of audio with no wake phrase: read speech (LibriSpeech), meetings (AMI), music (GTZAN), noise (DEMAND).

usage: make_testset.py <voices dir> <data dir> <out dir> [dev|test]
"""
import os, sys, glob, random, zlib, subprocess
import numpy as np
import scipy.signal as ss
import soundfile as sf

RATE = 16000

# key: (typed phrase, how it is really said, written for an English voice, [phrases that sound close])
NAMES = {
    "nova":   ("Hey Nova",   "Hey Nova",      ["Hey Noah", "Hey Nora", "Nova"]),
    "jarvis": ("Hey Jarvis", "Hey Jarvis",    ["Hey Travis", "Hey Marvin", "Jarvis"]),
    "thandi": ("Hey Thandi", "Hey Tahn-dee",  ["Hey Sandy", "Hey Mandy", "Tahn-dee"]),
    "sipho":  ("Hey Sipho",  "Hey See-paw",   ["Hey Simon", "Hey Chico", "See-paw"]),
    "yuki":   ("Hey Yuki",   "Hey Yoo-kee",   ["Hey Lucy", "Hey cookie", "Yoo-kee"]),
    "haruto": ("Hey Haruto", "Hey Hah-rootoe", ["Hey Naruto", "Hey Hiroto", "Hah-rootoe"]),
    "amira":  ("Hey Amira",  "Hey Ah-meera",  ["Hey Amara", "Hey Elmira", "Ah-meera"]),
    "khalid": ("Hey Khalid", "Hey Kah-leed",  ["Hey Khalil", "Hey Colin", "Kah-leed"]),
    "mateo":  ("Hey Mateo",  "Hey Mah-tayo",  ["Hey Mario", "Hey Matty", "Mah-tayo"]),
}
# The same names from voices of (or near) the name's own language, where an open voice exists. Small sets, few speakers.
NATIVE = {
    "mateo":  ("es_ES-sharvard-medium", "Hey Mateo"),
    "amira":  ("ar_JO-kareem-medium", "هاي أميرة"),
    "khalid": ("ar_JO-kareem-medium", "هاي خالد"),
    "thandi": ("sw_CD-lanfrica-medium", "Hei Tandi"),
    "sipho":  ("sw_CD-lanfrica-medium", "Hei Sipo"),
}
MULTI = [("en_US-libritts_r-medium", 904, 0.57), ("en_GB-vctk-medium", 109, 0.23), ("en_US-l2arctic-medium", 24, 0.13)]
SINGLE = ["en_US-ryan-medium", "en_US-amy-medium", "en_GB-alba-medium", "en_GB-northern_english_male-medium"]

_voices = {}
def voice(vdir, name):
    from piper import PiperVoice
    if name not in _voices: _voices[name] = PiperVoice.load(os.path.join(vdir, name + ".onnx"))
    return _voices[name]

def say(vdir, vname, text, speaker, speed, rng):
    from piper import SynthesisConfig
    cfg = SynthesisConfig(speaker_id=speaker, length_scale=speed, noise_scale=0.667, noise_w_scale=0.8)
    chunks = list(voice(vdir, vname).synthesize(text, syn_config=cfg))
    a = np.concatenate([c.audio_float_array for c in chunks]).astype(np.float32)
    return ss.resample_poly(a, RATE, chunks[0].sample_rate).astype(np.float32)

def room(a, rng):
    """A made-up room: the direct sound, then a tail of echo that dies away in 0.2 to 0.6 s."""
    rt = rng.uniform(0.2, 0.6); n = int(rt * RATE)
    tail = np.random.default_rng(rng.randrange(1 << 30)).standard_normal(n).astype(np.float32) * np.exp(-6.9 * np.arange(n) / n).astype(np.float32)
    ir = np.zeros(n, np.float32); ir[0] = 1.0; ir[int(0.004 * RATE):] += 0.25 * tail[: n - int(0.004 * RATE)]
    return ss.fftconvolve(a, ir)[: len(a) + n // 2].astype(np.float32)

def clip(speech, noise_files, rng, cond):
    lead = int(rng.uniform(0.6, 1.2) * RATE); tail = int(1.3 * RATE)
    if cond == "room": speech = room(speech, rng)
    peak = np.max(np.abs(speech)) + 1e-9
    speech = speech / peak * 10 ** (rng.uniform(-20, -4) / 20)
    out = np.concatenate([np.zeros(lead, np.float32), speech, np.zeros(tail, np.float32)])
    if cond != "clean" and noise_files:
        nz, _ = sf.read(rng.choice(noise_files), dtype="float32")
        start = rng.randrange(0, max(1, len(nz) - len(out)))
        nz = nz[start:start + len(out)]
        if len(nz) < len(out): nz = np.resize(nz, len(out))
        snr = rng.uniform(5, 20)
        p_s = np.mean(speech ** 2) + 1e-12; p_n = np.mean(nz ** 2) + 1e-12
        out = out + nz * np.sqrt(p_s / (p_n * 10 ** (snr / 10)))
    else:
        out = out + (np.random.default_rng(rng.randrange(1 << 30)).standard_normal(len(out)) * 10 ** (-70 / 20)).astype(np.float32)
    return np.clip(out, -1, 1)

def write(path, a):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    sf.write(path, (a * 32767).astype(np.int16), RATE, subtype="PCM_16")

def speakers(split):
    """Speakers never overlap between teaching, tuning (dev) and the test."""
    pools = {}
    for v, n, _ in MULTI:
        ids = list(range(n)); random.Random(7).shuffle(ids)
        k = max(2, n // 6)
        pools[v] = {"teach": ids[:2], "dev": ids[2:2 + k], "test": ids[2 + k:]}[split]
    return pools

def build_name(args):
    vdir, ddir, out, split, key, n_pos, n_near = args
    typed, said, close = NAMES[key]
    rng = random.Random(zlib.crc32(f"{key}/{split}".encode()))
    noise = sorted(glob.glob(os.path.join(ddir, "demand", "*", "ch1[0-6].wav")))
    rows = []
    def one(setname, text, i, pool_split, cond=None):
        r = rng.random()
        if setname == "teach" or r < 0.93:
            acc = 0; v = MULTI[-1]
            pick = rng.random()
            for m in MULTI:
                acc += m[2] / 0.93
                if pick < acc: v = m; break
            vname = v[0]; spk = rng.choice(speakers(pool_split)[vname])
        else:
            vname = rng.choice(SINGLE); spk = None
        speed = rng.uniform(0.85, 1.25)
        c = cond or rng.choices(["clean", "noise", "room"], [0.3, 0.4, 0.3])[0]
        a = clip(say(vdir, vname, text + ("!" if rng.random() < 0.5 else "."), spk, speed, rng), noise, rng, c)
        rel = f"{split}/{setname}/{key}/{i:04d}.wav"; write(os.path.join(out, rel), a)
        rows.append(f"{setname}\t{key}\t{rel}")
    for i in range(3): one("teach", said, i, "teach", "clean")
    for i in range(n_pos): one("pos", said, i, split)
    for j, phrase in enumerate(close):
        for i in range(n_near): one("near", phrase, j * 1000 + i, split)
    if key in NATIVE and split == "test":
        vname, text = NATIVE[key]
        nspk = 2 if "sharvard" in vname else 1
        for i in range(60):
            c = rng.choices(["clean", "noise", "room"], [0.3, 0.4, 0.3])[0]
            a = clip(say(vdir, vname, text, rng.randrange(nspk) if nspk > 1 else None, rng.uniform(0.85, 1.25), rng), noise, rng, c)
            rel = f"{split}/native/{key}/{i:04d}.wav"; write(os.path.join(out, rel), a)
            rows.append(f"native\t{key}\t{rel}")
    return rows

def ffmpeg_concat(files, dest, seconds):
    """Join files into one 16 kHz mono WAV of at most [seconds]."""
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    lst = dest + ".txt"
    with open(lst, "w") as f:
        for p in files: f.write("file '%s'\n" % os.path.abspath(p))
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst, "-ac", "1", "-ar", str(RATE),
                    "-t", str(seconds), "-c:a", "pcm_s16le", dest], check=True)
    os.remove(lst)

def negatives(ddir, out, split):
    """Long files with no wake phrase. dev is about an hour for tuning; test is the hours the report counts."""
    rng = random.Random(11 if split == "test" else 12)
    rows = []
    def chunks(kind, files, total_s, piece_s=600):
        i = 0; files = list(files)
        per = max(1, len(files) * piece_s // max(1, total_s))
        while i * piece_s < total_s and files:
            take, files = files[:per], files[per:]
            rel = f"{split}/neg/{kind}-{i:02d}.wav"
            ffmpeg_concat(take, os.path.join(out, rel), piece_s); rows.append(f"{kind}\t{rel}"); i += 1
    libri = sorted(glob.glob(os.path.join(ddir, "LibriSpeech", "dev-clean" if split == "test" else "test-other", "*", "*", "*.flac")))
    rng.shuffle(libri)
    # LibriSpeech utterances average about 7 s (dev-clean) so 90 files fill ten minutes.
    n = (3.0 if split == "test" else 0.5) * 3600
    chunks("read", libri[: int(n / 600 * 100)], int(n))
    # Unscripted talk (talks, interviews, meetings): People's Speech test shards, unpacked to <data>/talk/t0 and t1.
    talk = sorted(glob.glob(os.path.join(ddir, "talk", "t0" if split == "test" else "t1", "*.flac")))
    n = (2.0 if split == "test" else 0.25) * 3600
    # Clips average about 6 s, so 120 files are sure to fill ten minutes.
    chunks("talk", talk[: int(n / 600 * 120)], int(n))
    music = sorted(glob.glob(os.path.join(ddir, "music", "genres", "*", "*.wav"))); rng.shuffle(music)
    music = music[:180] if split == "test" else music[180:200]
    chunks("music", music, len(music) * 30)
    dem = sorted(glob.glob(os.path.join(ddir, "demand", "*", "ch0[12].wav" if split == "test" else "ch03.wav")))
    if split != "test": dem = dem[:2]
    chunks("noise", dem, len(dem) * 300)
    return rows

if __name__ == "__main__":
    vdir, ddir, out = sys.argv[1:4]; split = sys.argv[4] if len(sys.argv) > 4 else "test"
    n_pos, n_near = (300, 40) if split == "test" else (80, 15)
    from multiprocessing import Pool
    with Pool(int(os.environ.get("JOBS", "3"))) as p:
        rows = sum(p.map(build_name, [(vdir, ddir, out, split, k, n_pos, n_near) for k in NAMES]), [])
    d = os.path.join(out, split); os.makedirs(d, exist_ok=True)
    # Paths in the lists are relative to the folder the run is pointed at (out/<split>).
    strip = lambda r: r.replace(f"\t{split}/", "\t")
    open(os.path.join(d, "clips.tsv"), "w").write("\n".join(strip(r) for r in rows) + "\n")
    open(os.path.join(d, "names.tsv"), "w").write("\n".join(f"{k}\t{v[0]}" for k, v in NAMES.items()) + "\n")
    if os.environ.get("NEG", "1") == "1":
        open(os.path.join(d, "neg.tsv"), "w").write("\n".join(strip(r) for r in negatives(ddir, out, split)) + "\n")
    print("done", split, len(rows))

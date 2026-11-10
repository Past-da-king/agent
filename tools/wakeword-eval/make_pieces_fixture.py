#!/usr/bin/env python3
"""Writes app/src/test/resources/wakeword/pieces.tsv: phrases and how the keyword model's own SentencePiece file splits
them, so WakeCoreTest can prove the phone's splitter (WordPieces.kt) does exactly the same.

usage: make_pieces_fixture.py <bpe.model from the sherpa-onnx gigaspeech KWS archive> <out.tsv> [a text file of extra words]
"""
import sys, random
import sentencepiece as spm

NAMES = """Nova Jarvis Thandi Sipho Yuki Haruto Amira Khalid Mateo Greta Aiko Bongani Chidi Dmitri Elif Farah Giulia Hamza
Ingrid Jomo Kwame Leila Mbali Nikolai Oluwaseun Priya Qiang Rania Sven Tariq Uma Valentina Wanjiru Xiomara Yusuf Zanele
Anneke Bartholomew Caoimhe Dagny Eoghan Fenella Gwendolyn Horatio Isolde Jacqueline Kazuki Lorcan Mahlatse Nomvula
Ottoline Persephone Quentin Rhiannon Siobhan Thabo Ulrich Vusi Wilhelmina Xanthe Yevgenia Zbigniew O'Neil D'Arcy
Computer Assistant Buddy Captain Professor Sunshine Midnight Starlight Robot Echo Atlas Pixel Juniper Maple""".split()

if __name__ == "__main__":
    sp = spm.SentencePieceProcessor(model_file=sys.argv[1])
    words = [n.upper() for n in NAMES]
    if len(sys.argv) > 3:
        extra = sorted({w for line in open(sys.argv[3], encoding="utf-8") for w in line.upper().split() if w.replace("'", "").isalpha() and w.isascii()})
        random.Random(5).shuffle(extra); words += extra[:400]
    rows = []
    for w in words:
        for phrase in (w, "HEY " + w):
            rows.append(phrase + "\t" + " ".join(sp.encode(phrase, out_type=str)))
    open(sys.argv[2], "w", encoding="utf-8").write("\n".join(dict.fromkeys(rows)) + "\n")
    print(len(rows), "rows")

"""Writes the per-word phonemes the Natlu tracker matches recitation against.

  python export_phonemes.py ordered_quran_phonemes.json OUT.txt

The input is recite_quran's assets/model/ordered_quran_phonemes.json
(https://pub.dev/packages/recite_quran, under its license; see feature/recitation's NATLU_NOTICE.md).
Output: one line per ayah, "sura:ayah<TAB>word word ...", the ayah's aya_phonemes_list.
"""
import json
import sys

verses = json.load(open(sys.argv[1], encoding="utf-8"))["verses"]
with open(sys.argv[2], "w", encoding="utf-8") as out:
  for key, verse in verses.items():
    words = verse["aya_phonemes_list"]
    assert all(words) and not any(" " in w or "\t" in w for w in words), key
    out.write(key + "\t" + " ".join(words) + "\n")

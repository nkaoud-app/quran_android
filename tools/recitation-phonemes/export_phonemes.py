"""Writes the per-word phonemes recitation is followed against on the mushaf.

  pip install quran-transcript==0.6.4
  python export_phonemes.py OUT.txt

Each ayah of the Tanzil Uthmani text (as bundled with quran-transcript, MIT) is converted to the
phonetic script of quran-transcript (https://github.com/obadx/quran-transcript), which the
recognition model (Quran-Lab's zipformer_p-arabic-v3) was trained to output, for a Hafs murattal
recitation. The phonemes are then split per word: each word gets the phonemes from its first
letter's to the next word's (phonemes joined across words, as in idgham, go to the earlier word).

Output: a header with the Tanzil notice, the phrases a recitation may open with ("istiadha" and
"basmala", same format), then one line per ayah, "sura:ayah<TAB>word word ...".
"""
import sys

from quran_transcript import Aya, MoshafAttributes, quran_phonetizer

# Hafs, murattal: the usual reading, and what the model's training data describes.
MOSHAF = MoshafAttributes(
  rewaya="hafs", recitation_speed="murattal", takbeer="no_takbeer",
  madd_monfasel_len=4, madd_mottasel_len=4, madd_mottasel_waqf=4, madd_aared_len=4,
  madd_alleen_len=4, ghonna_lam_and_raa="no_ghonna", meem_aal_imran="waqf",
  madd_yaa_alayn_alharfy=6, saken_before_hamz="tahqeek", sakt_iwaja="waqf",
  sakt_marqdena="waqf", sakt_man_raq="sakt", sakt_bal_ran="sakt", sakt_maleeyah="waqf",
  between_anfal_and_tawba="waqf", noon_and_yaseen="izhar", yaa_ataan="wasl",
  start_with_ism="wasl", yabsut="seen", bastah="seen", almusaytirun="saad",
  bimusaytir="saad", tasheel_or_madd="madd", yalhath_dhalik="idgham", irkab_maana="idgham",
  noon_tamnna="ishmam", harakat_daaf="fath", alif_salasila="wasl",
  idgham_nakhluqkum="idgham_kamil", raa_firq="tafkheem", raa_alqitr="wasl", raa_misr="wasl",
  raa_nudhur="tafkheem", raa_yasr="tarqeeq", meem_mokhfah="ikhfaa",
)

TANZIL_NOTICE = """\
# Derived from the Tanzil Quran Text (Uthmani, Version 1.1), Copyright (C) 2007-2024 Tanzil
# Project, License: Creative Commons Attribution 3.0. Source: https://tanzil.net (updates:
# http://tanzil.net/updates/). Phonetic script by quran-transcript (MIT).
"""


def ayah_words(sura: int, ayah: int) -> list[str]:
  return text_words(Aya(sura, ayah).get().uthmani, sura)


def text_words(text: str, sura: int = 0) -> list[str]:
  out = quran_phonetizer(text, MOSHAF, sura_idx=sura)
  phonemes = out.phonemes
  starts = []
  i = 0
  for word in text.split(" "):
    if word:
      kept = [m.pos[0] for m in out.mappings[i:i + len(word)] if m is not None and not m.deleted]
      starts.append(min(kept) if kept else None)
    i += len(word) + 1
  for k, start in enumerate(starts):
    if start is None:
      starts[k] = starts[k - 1] if k else 0
    if k:
      starts[k] = max(starts[k], starts[k - 1])
  ends = starts[1:] + [len(phonemes)]
  return [phonemes[s:e].replace(" ", "") for s, e in zip(starts, ends)]


def main():
  with open(sys.argv[1], "w", encoding="utf-8") as out:
    out.write(TANZIL_NOTICE)
    out.write(f"istiadha\t{' '.join(text_words(Aya(1, 1).get().istiaatha_uthmani))}\n")
    out.write(f"basmala\t{' '.join(ayah_words(1, 1))}\n")
    aya = Aya(1, 1)
    while True:
      info = aya.get()
      words = ayah_words(info.sura_idx, info.aya_idx)
      assert all(words) and not any(" " in w or "\t" in w for w in words), (info.sura_idx, info.aya_idx)
      out.write(f"{info.sura_idx}:{info.aya_idx}\t{' '.join(words)}\n")
      if info.sura_idx == 114 and info.aya_idx == 6:
        break
      aya = aya.step(1)


if __name__ == "__main__":
  main()

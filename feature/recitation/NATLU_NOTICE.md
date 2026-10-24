# Natlu tracking: sources and licenses

Recitation tracking on the mushaf pages (`src/main/java/com/quran/mobile/recitation/natlu/`) is
a Kotlin port of the word tracking of [`recite_quran`](https://pub.dev/packages/recite_quran)
1.0.5 (https://github.com/Iam-Muslim/ReciteQuran), the engine of
[Natlu](https://github.com/Recite-Quran/Natlu). Its Tajweed evaluation, voice search and
Qira'at support are not ported. `src/main/assets/natlu/tokens.txt` is that package's
`assets/model/tokens.txt`, and `src/main/assets/natlu/quran_phonemes.txt` holds the per-word
phonemes of its `assets/model/ordered_quran_phonemes.json` (see
`tools/natlu/export_phonemes.py`).

The recognition model, Quran-Lab's
[zipformer_p-arabic-v3](https://huggingface.co/Quran-Lab/zipformer_p-arabic-v3), is downloaded
on first use from the release Natlu downloads it from. It is released under the Quran-Lab
No-Profit License 1.2 (NPL-1.2), whose terms include: never charging for the model, access to it
or any feature it powers; not presenting its output as an authoritative religious ruling on
anyone's recitation; and stating clearly, in any application built on it, that automatic
feedback can be wrong and does not replace a qualified teacher (the model download dialog says
so). The model is run with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0).

## recite_quran license

# (لوجه الله تعالى)
# ما أَسأَلُكُم عَلَيهِ مِن أَجرٍ إِن أَجرِيَ إِلّا عَلىٰ رَبِّ العالَمينَ

> **THIS PACKAGE AND SOURCE CODE ARE DEDICATED FOR THE SAKE OF ALLAH ALONE.**

Before viewing, using, distributing, or modifying any part of this repository or package, you explicitly agree to the following sacred covenants:

1. **100% Free to End Users**:
   You may use, study, and redistribute this software or its logic **ONLY** in applications and services that are completely free of charge to all end users for the end inshaa Allah .

2. **Strict Prohibition on Commercialization & Profit**:
   You are **STRICTLY FORBIDDEN** from selling this package, placing it behind paywalls, subscription models, in-app purchases, charging download fees, monetizing it with advertisements (AdMob, Unity Ads, etc.), or extracting any financial revenue from this codebase, models, or outputs.

3. **Pass-Through**:
   These terms are immutable and strictly pass on to any fork, derivative work, or redistributed component.

---

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to use, copy, modify, merge, and distribute the Software, subject to the conditions above.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

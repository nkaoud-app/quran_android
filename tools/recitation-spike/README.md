# Recitation tracking spike

Offline experiments for following a reader through the Quran text from microphone audio, as an
alternative to Android's general-purpose `SpeechRecognizer`.

## Approach

Instead of transcribing speech and comparing text, the tracker knows what the reader is supposed
to recite and only answers "where in this text is the reader now?":

1. A CTC acoustic model trained on Quran recitation turns audio into per-frame (20 ms) letter
   probabilities. It runs in streaming mode: short hops of new audio, some past context and a
   little look-ahead, the way it would run on a phone.
2. Those probabilities are reduced to a consonant skeleton: vowel marks, alif and hamza forms
   count as blank. This sidesteps every Uthmani vs. standard spelling difference.
3. `RecitationTracker` runs an online Viterbi forced alignment of the frames against the known
   word sequence. Silence and elongated vowels just stay in the current state, so pauses and
   long recitations need no special handling. The ta'awwudh and basmala are optional phrases
   before the text, and a filler state absorbs any other audio before the reader starts.

## Evaluation

`evaluate.py` replays professional gapless recitations (the same audio and word-level timing
databases Quran Android downloads) through the tracker and compares when each word is marked
recited with when the reciter actually said it.

```
python3 -m venv venv && venv/bin/pip install torch transformers soundfile numpy
SPIKE_DATA=/path/to/cache venv/bin/python evaluate.py --suras 1 67 [--verbose]
```

Requires `ffmpeg`. Audio, databases, models and model outputs are cached under `SPIKE_DATA`.

Reported per sura:

- **missed**: words never marked recited
- **premature**: words marked recited before the reciter started them
- **word lag**: time from the end of a word until it is marked recited
- **ayah advance lag**: the same for each ayah's last word, i.e. when auto tracking would move on

## Results so far

Al-Mulk 1–10 recorded by the user (4 straight recitations) and another reciter, plus Minshawi's
al-Fatiha and al-Mulk with reference word timings. "Ayah switch" is how long after the reader
finishes an ayah the tracker confirms its last word.

| Model | Size | Settings | Ayah switch, user recordings (median / worst) | Minshawi worst | Early / false advances |
|---|---|---|---|---|---|
| Teacher (`rabah2026/...-v_final`) | 1.2 GB | 0.5 s hop, 6 s context | 1.2–1.4 / 1.5–1.6 s | 1.5 s | none |
| Student v2, 8 layers, int8 ONNX | 93 MB | 1 s hop, 3 s context | 1.5–1.8 / 1.9–2.8 s | 3.4 s | none |

The int8 student runs a 5 s window in 351 ms on one CPU thread of this machine (0.35x real
time at a 1 s hop; 0.19x on two threads). Repeating an ayah and an 8 s pause are followed
correctly. Known gaps: the last word of a recording that stops mid-breath is not confirmed (the app
can confirm it on stop), and a very long held sound can let the model guess the next word about a
second early.

Students are trained with `distill_colab.ipynb` (speed perturbation 0.8–1.4x and extra weight on
letter frames) and exported with `export_onnx.py` (only matrix multiplications quantized).

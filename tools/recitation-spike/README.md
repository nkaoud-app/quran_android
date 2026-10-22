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

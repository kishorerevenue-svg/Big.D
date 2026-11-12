# habit-gemma: Habit AI's own AI model

*(తెలుగు కింద ఉంది: [తెలుగులో](#తెలుగులో))*

**habit-gemma** is Habit AI's own model. It is Google's open Gemma 4 model (Apache 2.0 licence), taught to talk and help
like Habit, in Telugu and English. It runs on our own server when Claude is resting or down. Later it can run on a bigger
server, and on phones.

It is made **for free**, on Kaggle's free GPUs, and kept in **your own private** Hugging Face account.

## Who teaches it

1. **Big Gemma** (Gemma 4 26B, also Apache 2.0) writes lessons. It covers about 170 topics in Telugu, Telugu in English
   letters, and English: everyday life, money safety and scams, health, government services, office writing, farming,
   phones, festivals, emergencies, and saying no to harmful requests. It then checks each lesson and keeps only the good
   ones. The topics are in [topics.json](topics.json); add your own.
2. **You**, on the Teach page (🎓 Teach Gemma). You write questions and the right answers in your own words. Your library's
   typed notes, text files and web pages also become lessons. Your lessons count three times as much.
3. **People**, with 👍 / 👎 under answers from Habit's own brain. They can also write the right answer. You check these on
   the Teach page and turn the good ones into lessons.
4. **The Aya dataset**: Telugu and English questions and answers written by people (Cohere, Apache 2.0).

**Claude's words are never used.** Anthropic's terms don't allow training other AI models on Claude's answers. So none of
these go into the lessons:

- Claude's answers;
- the answers Habit learned from Claude;
- the files and templates Claude made;
- documents that Claude read for the library (PDFs and photos).

An answer from Gemma counts only if no Claude words were in front of it.

**The exam** ([exam.json](exam.json), 157 questions) checks a model with fixed rules. It covers:

- the right language;
- warning about OTP fraud, and 1930 for cyber fraud;
- 108 and 112 in emergencies;
- "see a doctor" when it matters;
- government offices and websites;
- letters;
- honesty ("I'm not sure" instead of made-up numbers);
- saying no to harmful things;
- small sums.

The Teach page and the Kaggle notebook give the same exam. A new habit-gemma is kept only if it does at least as well as
the plain Gemma. The exam's questions are never lessons.

## What you need (all free)

- A **Kaggle** account: [kaggle.com](https://www.kaggle.com). Verify your phone number there once, because the GPU and
  the internet need it. Kaggle gives about 30 GPU hours a week.
- A **Hugging Face** account: [huggingface.co/join](https://huggingface.co/join).
- About 1 hour for a test run, then 4 to 8 hours for the real one. It runs by itself, so you can close the phone.

On a phone, open Kaggle in your browser with **Desktop site** turned on: the notebook settings are easier to find.

## Steps

**1. Hugging Face token.** In Hugging Face, go to Settings › [Access Tokens](https://huggingface.co/settings/tokens) ›
*Create new token*. Choose type **Write**, then copy it. Keep it secret and never send it to anyone. Save it on our server
(it stays in the server's `.env`):

```
bash /root/Big.D/server/set-key.sh HF_TOKEN hf_your_token_here
```

**2. Training link.** On the Teach page, go to 🎓 Teach Gemma › 1. The training link › **Make a new link**, then copy it.
It works for 14 days.

**3. Kaggle notebook.** In Kaggle, choose *Create › New Notebook*. Then:

- *Settings › Accelerator*: **GPU T4 x2**.
- *Settings › Internet*: **On**.
- *Add-ons › Secrets › Add secret*: name `HF_TOKEN`, value your Hugging Face token.
- *Add-ons › Secrets › Add secret*: name `HABIT_LINK`, value the training link. This one is optional: without it, only
  Big Gemma and Aya teach.
- Tick the box next to each secret, so that this notebook can use it.

**4. Test run (about 1 hour).** Paste this in the first cell:

```
!curl -fsSL https://raw.githubusercontent.com/kishorerevenue-svg/Big.D/main/brain/habit_gemma.py -o habit_gemma.py && python habit_gemma.py --test
```

Then press **Save Version › Save & Run All (Commit)**. It runs in the background. Open the version to see its messages.
When it ends with **✅ THE TEST WORKED**, everything works. The test model goes to `<you>/habit-gemma-test`, never to the
server.

**5. Real run (4 to 8 hours).** Change `--test` to `--full` and *Save & Run All* again. At the end it shows the exam:
habit-gemma against the plain Gemma, for each part. **✅ habit-gemma IS READY** means it is in `<you>/habit-gemma`.

**6. Put it on the server.** On the Teach page, go to 🎓 Teach Gemma › 5 › **Put habit-gemma on this server**. Or run
this on the server:

```
true; /root/Big.D/server/brain-model.sh
```

The script checks the server's memory, downloads the model (a stopped download continues where it stopped), and adds it to
Ollama. Then it waits for a first answer. Only after that does habit-gemma become our own brain. To go back to the plain
Gemma, use **Back to …** on the Teach page, or `brain-model.sh --back`.

**7. Teaching it more.** Add lessons on the Teach page, turn people's 👍 / 👎 into lessons, make a new link, and run step 5
again. Big Gemma's lessons are kept in your private `<you>/habit-lessons`, and the next run uses them again for 30 days. So
the next run mostly teaches your new lessons and takes less time. To make Big Gemma write new lessons, add
`--new-lessons yes`.

## Choices

| Add to the command | What it does |
|---|---|
| `--size E2B` | The default. About 3 GB, runs on a server with 6 GB memory (and later on phones). |
| `--size E4B` | Smarter. Needs a server with about 10 GB memory. |
| `--teacher gemma4:31b` | A slower, slightly smarter teacher (the default is `gemma4:26b`). |
| `--new-lessons yes` / `no` | Big Gemma writes new lessons / uses only the saved ones. |
| `--hours 4` | At most 4 hours for writing lessons (the default is 6). Unfinished topics are done next time. |
| `--no-aya` | Leaves out the Aya dataset. |

## If it stops

The last lines say what to do:

- **there is no GPU**: in *Settings › Accelerator*, choose GPU T4 x2.
- **no internet**: turn on *Settings › Internet*. It needs a verified phone number.
- **add your Hugging Face token** / **didn't accept HF_TOKEN**: add the secret `HF_TOKEN`, with a Write token, and tick
  its box in *Add-ons › Secrets*.
- **the training link has expired**: make a new link on the Teach page and change the `HABIT_LINK` secret.
- **habit-gemma did worse than the plain Gemma**: nothing is changed. Add more lessons, then run again. Each answer is in
  `exam-habit-gemma.json`, in the notebook's output.
- **Anything else**: send the last 30 lines of messages, but not the token or the link.

## What's in this folder

- `habit_gemma.py`: the whole Kaggle run.
- `exam.json`: the exam. Made by `tools/make-exam.js` with the own brain's exact words.
- `habit_exam.py`: the exam's rules, the same as `server/gemma.js`. `tools/test-exam.js` checks that the two give the same
  results.
- `topics.json`: the topics Big Gemma writes about.
- On the server: `server/gemma.js` (ratings, lessons, the training link, the exam), `server/brain-model.sh` (puts
  habit-gemma on the server), and the Teach page section 🎓 Teach Gemma.

---

## తెలుగులో

**habit-gemma** అంటే హ్యాబిట్ AI సొంత AI మోడల్. ఇది గూగుల్ ఉచిత జెమ్మా 4 మోడల్ (Apache 2.0). దానికి హ్యాబిట్‌లా తెలుగు,
ఇంగ్లీష్‌లో మాట్లాడటం, సహాయం చేయడం నేర్పిస్తాం. Claude విశ్రాంతిలో ఉన్నప్పుడు మన సర్వర్‌లో ఇదే జవాబిస్తుంది. దీన్ని Kaggle
ఉచిత GPUలో **ఉచితంగా** తయారు చేస్తాం. ఇది **మీ సొంత ప్రైవేట్** Hugging Face ఖాతాలో ఉంటుంది.

**ఎవరు నేర్పిస్తారు:**

1. **పెద్ద జెమ్మా** (Gemma 4 26B): దాదాపు 170 విషయాలపై పాఠాలు రాస్తుంది. తర్వాత ప్రతి పాఠాన్ని తనే పరీక్షిస్తుంది.
2. **మీరు**: Teach పేజీలో మీ మాటల్లో ప్రశ్న, జవాబు రాస్తారు. మీ పాఠాలకు మూడు రెట్ల విలువ.
3. **ప్రజలు**: హ్యాబిట్ సొంత మెదడు జవాబుల కింద 👍 / 👎 నొక్కుతారు, సరైన జవాబు కూడా రాస్తారు. మీరు చూసి పాఠాలుగా మారుస్తారు.
4. **Aya డేటాసెట్**: మనుషులు రాసిన తెలుగు, ఇంగ్లీష్ ప్రశ్నలు, జవాబులు.

**Claude మాటలు ఎప్పుడూ వాడము.** Anthropic నియమాల ప్రకారం Claude జవాబులతో వేరే AIకి నేర్పించకూడదు. అందుకే ఇవేవీ పాఠాల్లోకి
వెళ్ళవు:

- Claude జవాబులు;
- Claude నుంచి నేర్చుకున్న జవాబులు;
- Claude చేసిన ఫైళ్ళు;
- Claude చదివిన PDFలు, ఫోటోలు.

**పరీక్ష:** 157 ప్రశ్నలు, స్థిరమైన నియమాలతో. కొత్త మోడల్ సాధారణ జెమ్మా కంటే బాగా రాస్తేనే ఉంచుతాం.

**కావలసినవి (అన్నీ ఉచితం):**

- Kaggle ఖాతా. అక్కడ ఒకసారి ఫోన్ నంబర్ ధృవీకరించాలి.
- Hugging Face ఖాతా.
- సమయం: పరీక్షకు 1 గంట, అసలుదానికి 4 నుంచి 8 గంటలు. అది తనంతట తానే నడుస్తుంది, ఫోన్ మూసేయొచ్చు.

ఫోన్‌లో అయితే బ్రౌజర్‌లో **Desktop site** ఆన్ చేసి Kaggle తెరవండి.

**దశలు:**

1. **Hugging Face టోకెన్:** Hugging Face › Settings › Access Tokens › *Create new token*. రకం **Write** ఎంచుకుని
   కాపీ చేయండి. ఎవరికీ పంపకండి. సర్వర్‌లో ఇలా సేవ్ చేయండి: `bash /root/Big.D/server/set-key.sh HF_TOKEN hf_మీ_టోకెన్`
2. **లింక్:** Teach పేజీ › 🎓 Teach Gemma › **Make a new link** నొక్కి లింక్ కాపీ చేయండి. ఇది 14 రోజులు పని చేస్తుంది.
3. **Kaggle నోట్‌బుక్:** Kaggle › *Create › New Notebook* తెరిచి ఇవి చేయండి:
   - *Settings › Accelerator*: **GPU T4 x2**
   - *Settings › Internet*: **On**
   - *Add-ons › Secrets*: `HF_TOKEN` (మీ టోకెన్), `HABIT_LINK` (లింక్). ప్రతి దాని పక్కన టిక్ పెట్టండి.
4. **పరీక్ష రన్ (1 గంట):** పైన ఇంగ్లీష్ భాగంలోని **4వ దశలో ఉన్న లైన్** కాపీ చేసి మొదటి సెల్‌లో పేస్ట్ చేయండి. తర్వాత
   **Save Version › Save & Run All** నొక్కండి. చివర్లో **✅ THE TEST WORKED** వస్తే అంతా సరిగ్గా ఉంది.
5. **అసలు రన్ (4 నుంచి 8 గంటలు):** `--test` బదులు `--full` పెట్టి మళ్ళీ *Save & Run All* నొక్కండి. చివర్లో
   **✅ habit-gemma IS READY** వస్తుంది.
6. **సర్వర్‌లో పెట్టడం:** Teach పేజీ › 🎓 Teach Gemma › **Put habit-gemma on this server** నొక్కండి. లేదా సర్వర్‌లో
   `true; /root/Big.D/server/brain-model.sh` రన్ చేయండి. మళ్ళీ పాత జెమ్మాకి వెళ్ళాలంటే **Back to …** నొక్కండి.
7. **ఇంకా నేర్పించడం:** Teach పేజీలో పాఠాలు జోడించండి, కొత్త లింక్ చేయండి, 5వ దశ మళ్ళీ రన్ చేయండి.

ఏదైనా ఆగిపోతే, చివరి లైన్లలో ఏం చేయాలో ఉంటుంది. అర్థం కాకపోతే చివరి 30 లైన్లు పంపండి. టోకెన్, లింక్ మాత్రం పంపకండి.

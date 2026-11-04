package com.soulfriend.pet

/**
 * The pet's eyes. Works out when someone wants the pet to LOOK through the phone's camera instead of just talking:
 *  - the back camera (things in front of you): "look at this shirt", "is this available online?", "what's written on this board?",
 *    "read this", "translate this board", "ఈ బోర్డు మీద ఏం రాసి ఉంది?", "ఇది ఏంటి?", "idi chudu";
 *  - the front camera (you): "look at me", "what colour is my T-shirt?", "how do I look?", "నేను ఎలా ఉన్నాను?", "నన్ను చూడు".
 * Only clear words are caught here, so the camera starts at once; for anything else the AI asks to look by itself with its
 * "look" action. Things on the phone itself (a message, the screen, a video, a photo in the gallery) are left alone.
 */
object Eyes {
    /** [back] = the camera on the back of the phone; otherwise the front (selfie) camera. [again] = "look again". */
    class Look(val back: Boolean, val ask: String, val again: Boolean = false)

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    // things you can see around you (English, and the English words people use in Telugu)
    private const val THINGS = "board|signboard|sign ?board|sign|banner|hoarding|flex|poster|notice|name ?board|number ?plate|shop|store|hotel|restaurant|menu|bill|receipt|" +
        "label|tag|price ?tag|medicine|tablets?|syrup|bottle|packet|box|product|item|thing|shirt|t-?shirt|tshirt|dress|saree|sari|kurta|kurti|jeans|pants?|" +
        "shoes?|chappals?|slippers?|sandals?|watch|bag|purse|specs|spectacles|glasses|goggles|cap|helmet|plant|flower|tree|leaf|leaves|fruit|vegetable|" +
        "bird|animal|dog|cat|cow|insect|bug|snake|building|temple|church|mosque|statue|monument|place|road|street|car|bike|scooter|vehicle|bus|train|" +
        "food|dish|sweet|machine|meter|tv|fridge|fan|cooler|toy|ring|chain|necklace|bangle|jewel|jewellery|jewelry|painting|sticker|logo|coin|currency|ticket|certificate|card"
    // things you read (only together with "read", "translate", "what's written")
    private const val READS = "$THINGS|letter|paper|form|book|newspaper|document|writing|script"
    private const val THINGS_TE = "బోర్డు|బోర్డ్|సైన్ ?బోర్డు|బ్యానర్|ఫ్లెక్సీ|పోస్టర్|నోటీసు|నోటీస్|షాపు|షాప్|దుకాణం|హోటల్|మెనూ|మెను|బిల్లు|రసీదు|లేబుల్|ట్యాగ్|కార్డు|కార్డ్|" +
        "మందు|మందులు|మాత్ర|మాత్రలు|టాబ్లెట్|సిరప్|సీసా|బాటిల్|ప్యాకెట్|డబ్బా|బాక్స్|వస్తువు|ప్రోడక్ట్|షర్ట్|షర్టు|టీ ?షర్ట్|టీ-షర్ట్|టీషర్ట్|డ్రెస్|డ్రెస్సు|చీర|కుర్తా|" +
        "జీన్స్|ప్యాంట్|ప్యాంటు|చెప్పులు|షూస్|వాచ్|గడియారం|బ్యాగ్|సంచి|కళ్ళజోడు|కళ్లజోడు|మొక్క|పువ్వు|పూలు|చెట్టు|ఆకు|పండు|కూరగాయ|పక్షి|జంతువు|కుక్క|పిల్లి|ఆవు|" +
        "పురుగు|పాము|బిల్డింగ్|భవనం|గుడి|ఆలయం|విగ్రహం|ప్లేస్|ప్రదేశం|రోడ్డు|కారు|బైక్|బండి|స్కూటర్|బస్సు|నంబర్ ?ప్లేట్|ఫుడ్|వంటకం|స్వీట్|మెషిన్|మీటర్|టీవీ|ఫ్రిజ్|ఫ్యాన్|" +
        "బొమ్మ|నోటు|నాణెం|ఉంగరం|గొలుసు|లోగో|టికెట్|సర్టిఫికెట్"
    private const val READS_TE = "$THINGS_TE|ఉత్తరం|లెటర్|కాగితం|పేపర్|ఫారం|ఫారమ్|పుస్తకం|బుక్|పత్రం|దస్తావేజు|అక్షరాలు"
    // your own clothes and looks
    private const val WEAR = "shirt|t-?shirt|tshirt|dress|outfit|saree|sari|kurta|kurti|top|jacket|tie|hair|hairstyle|haircut|beard|moustache|mustache|specs|spectacles|glasses|look|face|smile|makeup|clothes"
    private const val WEAR_TE = "షర్ట్|షర్టు|టీ ?షర్ట్|టీషర్ట్|టీ-షర్ట్|డ్రెస్|డ్రెస్సు|చీర|బట్టలు|బట్టల|జుట్టు|హెయిర్|హెయిర్‌స్టైల్|ముఖం|మొహం|కళ్ళజోడు|కళ్లజోడు|కళ్లద్దాలు|లుక్|గడ్డం|మీసం|కుర్తా|ప్యాంట్|ప్యాంటు"
    // a Telugu word ends here, maybe with a small ending ("బోర్డులో", "షర్ట్‌కి"), and not inside a longer word ("పండు" in "పండుగ")
    private const val TE_END = "\\u200C?(?:లో|లోని|పై|పైన|మీద|కి|కు|ని|ను|తో|ది|దా|లు|ల్లో|లను|నీ|కీ|గా|కే|లా)?(?![\\u0C00-\\u0C7F])"

    // "don't look", "చూడొద్దు"
    private val NO = re("\\b(don'?t|do not|never|no need to|stop)\\b[^.?!]{0,25}\\b(look|see|read|camera)|చూడకు|చూడొద్దు|చూడవద్దు|కెమెరా వద్దు|\\b(chudaku|chudoddu|choodaku)\\b")
    // on the phone itself, not in front of the camera (the screen, a message, a video, a photo in the gallery…)
    private val ON_PHONE = re("\\b(screen|screenshot|message|msg|whatsapp|chat|notification|sms|e-?mail|mail|link|url|video|reel|youtube|post(?! office)|status|story|website|web ?page|apps?|pdf|file|photo|picture|pic|image|gallery|song|news)s?\\b|" +
        "స్క్రీన్|స్క్రీను|మెసేజ్|మెసేజి|మెస్సేజ్|వాట్సాప్|లింక్|వీడియో|యాప్|ఫోటో|ఫొటో|పోస్ట్|నోటిఫికేషన్|వెబ్‌సైట్|ఫైల్|పీడీఎఫ్|వార్త|న్యూస్")
    // text they typed or pasted ("translate this: Good morning") is not for the camera
    private val TYPED = re("[:\"“”‘].{3,}")

    // ---- clearly the camera, whatever else they say ("look with the camera", "ఫోటో తీసి చెప్పు") ----
    private val DIRECT = re(listOf(
        "\\b(with|through|using|use) (the |your |my )?(back |rear |front |selfie )?camera\\b", "\\b(back|rear|front|selfie) camera\\b",
        "\\b(take|click|snap) (a )?(photo|picture|pic|snap)\\b[^.?!]{0,10}\\b(and|then)\\b[^.?!]{0,12}\\b(tell|see|check|read|say|look)",
        "కెమెరా ?(తో|లో|ద్వారా)", "(వెనుక|వెనక|ముందు) కెమెరా", "ఫోటో (తీసి|తీసుకుని)[^?.!]{0,15}(చూడు|చెప్పు|చూసి|చదువు)",
        "\\bcamera (tho|lo|dwara)\\b", "\\bphoto (teesi|tisi)\\b[^.?!]{0,15}\\b(chudu|cheppu|chusi)"
    ).joinToString("|"))
    private val FRONT_WORDS = re("\\b(look at me|see me|myself|front|selfie)\\b|\\bmy ($WEAR)\\b|నన్ను|నా ($WEAR_TE)|ముందు కెమెరా|\\bnannu\\b")

    // ---- the front camera: the person ----
    private val ME = re(listOf(
        "\\blook at me\\b", "\\b(can|could|do) you see me\\b", "^(please )?see me\\b", "\\bhow (do|did) i look\\b", "\\bhow am i looking\\b", "\\bhow i look\\b",
        "\\b(do|am) i look(ing)? (good|ok|okay|nice|fine|smart|handsome|beautiful|pretty|tired|fresh|sick|ill|happy|sad|different)\\b",
        "\\bwhat (colou?r|kind of \\w+) (is|are) my\\b", "\\bcolou?r of my\\b", "\\bwhat am i wearing\\b", "\\b(i'?m|i am) wearing\\b",
        "\\bmy ($WEAR)\\b[^.?!]{0,30}\\b(colou?rs?|looks?|looking|suits?|match(es|ing)?|style)\\b",
        "\\b(colou?r|looks?|suits?|match)\\b[^.?!]{0,20}\\bmy ($WEAR)\\b", "\\bsuits? me\\b", "\\b(look|looks|good|nice|ok|okay)\\b[^.?!]{0,10}\\bon me\\b",
        "నన్ను (చూడు|చూడండి|చూసి|చూశావా|చూసావా|చూస్తున్నావా|చూడగలవా|చూడగలవ)", "నేను ఎలా (ఉన్నా|కనిపిస్తున్నా|ఉంటా)",
        "(వేసుకున్న|వేసుకుని ఉన్న|కట్టుకున్న)[^?.!]{0,20}($WEAR_TE)", "($WEAR_TE)[^?.!]{0,20}(వేసుకున్న|కట్టుకున్న)",
        "నా ($WEAR_TE)$TE_END[^?.!]{0,30}(రంగు|కలర్|ఎలా ఉంద|ఎలా ఉన్న|ఎలా కనిపి|బాగుందా|బాగోలేద|సూట్|మ్యాచ్|నప్పుతుంద|నచ్చుతుంద)",
        "నాకు [^?.!]{0,25}(సూట్ అవుతుంద|సూట్ అయిందా|సూట్ అయ్యిందా|నప్పుతుంద|నప్పిందా|బాగుందా|బాగుంటుంద)",
        "\\bnannu (chudu|choodu|chuudu|chusi|chudandi|chustunnava|chudagalava)\\b", "\\bnenu ela (unna|kanipistunna)",
        "\\b(vesukunna|vesukunnanu|kattukunna)\\b[^.?!]{0,20}\\b($WEAR)\\b", "\\bna ($WEAR)\\b[^.?!]{0,30}\\b(colou?r|rangu|ela undi|bagunda|baagunda|suit|match)"
    ).joinToString("|"))

    // ---- the back camera: things in front of you ----
    private val THING = re(listOf(
        "\\blook at (this|that|these|those|it|here|there)\\b", "\\blook here\\b", "\\b(can|could) you see (this|that|it|these|those)\\b",
        "(^|\\b(please|pls|just|now|come|hey) )see (this|that)\\b",
        "\\bread (this|these)\\b(?! again)", "\\bread (the|this|that) ($READS)\\b",
        "\\bwhat('?s| is| are) written (on|in) (this|that|it|here|there)\\b", "\\bwhat('?s| is| are) written (on|in) the ($READS)\\b", "^(\\w+ )?what('?s| is| are) written( here| there)?[?.!\\s]*$",
        "\\bwhat does (this|that|the ($READS)) (say|read)\\b",
        "^(please |pls )?translate (this|these)( ($READS))?( (to|into|in) \\w+)?[?.!\\s]*$", "\\btranslate (the|this|that) ($READS)\\b",
        "\\b(this|these) (?:(?!(?:is|was|are|the|a|an|my|your|and|or|to|in|on|at|for|of|it) )\\w+ )?($THINGS)\\b", "\\b(look at|read|see) (that|those) ($READS)\\b", "\\bidentify (this|that)\\b",
        // the way many of us say it: "look this shirt", "see this", "check this shirt", "what in board"
        "\\blook (this|these)\\b", "\\blook the (\\w+ )?($THINGS)\\b", "\\b(see|check) (the|this|that) (\\w+ )?($THINGS)\\b",
        "\\bwhat\\b[^.?!]{0,20}\\b(written|write|says?|on|in)\\b[^.?!]{0,10}\\b(the |this |that )?(board|signboard|sign|banner|hoarding|poster|notice|label|menu|bill|paper|letter|tag)\\b(?! exams?| results?)",
        "\\bwhich (plant|flower|tree|bird|animal|insect|snake|fruit|brand|company|model|medicine|tablet|car|bike) is (this|that)\\b",
        "\\b(is|are) (this|these)( one| thing| item)? (available|sold)\\b", "\\bhow much (is|does|for) (this|these)\\b", "\\bwhere (can|do) i (buy|get) (this|these)\\b",
        "\\b(price|cost|rate) of (this|these)\\b",
        "^(hey |bigd |big d )?(what('?s| is| are) (this|these)( thing| one)?|identify this)[?.!\\s]*$",
        "^(look|see)[.!?\\s]*$",
        // Telugu
        "(ఇది|ఇవి|అది|అవి|ఇక్కడ|అక్కడ|ఇటు|దీన్ని|దీనిని|దాన్ని|వీటిని) ?(చూడు|చూడండి|చూసి|చూశావా|చూస్తావా|చూడగలవా|కనిపిస్తుందా|కనిపిస్తోందా)",
        "ఈ ?($THINGS_TE)$TE_END",
        "(ఈ|ఆ) ?($READS_TE)$TE_END[^?.!]{0,30}(చూడు|చూసి|చదువు|చదవండి|చదివి|చదవగలవా|రాసి|రాసుంది|రాశారు|రాసారు|అనువదించు|అనువాదం|ట్రాన్స్\\S*లేట్|అర్థం|ఏంటి|ఏమిటి)",
        "(ఈ|దీని|దీంట్లో|దీనిలో|ఇందులో|ఇక్కడ|అక్కడ)[^?.!]{0,30}(రాసి (ఉంది|వుంది|ఉన్నది|ఉన్నాయి|ఉన్న)|రాసుంది|రాసున్నది|రాశారు|రాసారు|రాసింది)",
        "^(ఏం|ఏమి|ఏమని) ?(రాసి ఉంది|రాసుంది|రాశారు|రాసారు)[?.!\\s]*$",
        "(ఇది|ఇవి|దీన్ని|దీనిని|వీటిని)[^?.!]{0,25}(చదువు|చదవండి|చదివి|చదవగలవా)",
        "(ఇది|ఇవి|దీన్ని|దీనిని)[^?.!]{0,30}(అనువదించు|అనువాదం|అనువాదించు|ట్రాన్స్\\S*లేట్|తెలుగులో చెప్పు|తెలుగులోకి|ఇంగ్లీష్\\S* చెప్పు|అర్థం ఏంటి|అర్ధం ఏంటి|అర్థం చెప్పు)",
        "(ఇది|ఇవి|దీన్ని|దీనిని)[^?.!]{0,40}(ఆన్\\S*లైన్|దొరుకుతుంద|ఎక్కడ కొన|ధర ఎంత|రేటు ఎంత)",
        "^(ఇది|ఇవి) ?(ఏంటి|ఏమిటి|ఎంత|ఏంటో చెప్పు|ఏమిటో చెప్పు)[?.!\\s]*$", "^(ఇదేంటి|ఇదేమిటి|ఇవేంటి|ఇదెంత|దీని ధర ఎంత|దీని రేటు ఎంత|చూడు|చూడండి)[?.!\\s]*$",
        // Telugu in English letters
        "\\b(idi|idhi|ivi|ikkada|deenni|dinni|deenini)\\s+(chudu|choodu|chuudu|chusi|chudandi|chuso|chudagalava|kanipistunda)\\b",
        "\\bee ($THINGS)\\b", "\\b(ee|aa|idi|deeni|dini|ikkada) [^.?!]{0,25}\\b(rasi undi|rasundi|raasi undi|rasaru|raasaru|rasi vundi)\\b",
        "\\b(idi|idhi|ivi|deenni)\\b[^.?!]{0,25}\\b(chaduvu|chadavandi|chadivi|chadavu)\\b",
        "^(idi|idhi) (enti|emiti|entidi|ento|enta)[?.!\\s]*$", "^(chudu|choodu)[?.!\\s]*$"
    ).joinToString("|"))

    private val AGAIN = re("^(please )?(look|see|check|try) (again|once more)|^once more\\b|^one more time\\b|^again[?.!\\s]*$|మళ్ళీ (చూడు|చూడండి|చూసి)|మరోసారి చూడు|ఇంకోసారి చూడు|\\bmalli (chudu|choodu)\\b")
    private val BACK_WORDS = re("\\b(back|rear)\\b|వెనుక|వెనక")

    /** null when the words don't ask the pet to look. [last] = the last look, for "look again". */
    fun wants(text: String, last: Look? = null): Look? {
        val t = text.trim().replace('’', '\'')
        if (t.isEmpty() || t.length > 300 || NO.containsMatchIn(t)) return null
        if (AGAIN.containsMatchIn(t)) return if (last != null) Look(last.back, last.ask, again = true) else null
        if (DIRECT.containsMatchIn(t)) return Look(back = BACK_WORDS.containsMatchIn(t) || !FRONT_WORDS.containsMatchIn(t), ask = t)
        if (ON_PHONE.containsMatchIn(t)) return null
        if (ME.containsMatchIn(t)) return Look(back = false, ask = t)
        if (THING.containsMatchIn(t) && !TYPED.containsMatchIn(t)) return Look(back = true, ask = t)
        return null
    }

    /** What goes to the AI with the photo. The server knows how to answer "[Your eyes …]" (talk as if seeing it now). */
    fun question(l: Look): String =
        (if (l.back) "[Your eyes: back camera. I pointed my phone at this just now and asked you to look.] "
         else "[Your eyes: front camera. This is me right now. Be kind; don't try to recognise faces.] ") +
            (if (l.again) "(looking again) " else "") + l.ask

    /** What the pet says while it looks. */
    fun going(l: Look, te: Boolean): String = when {
        l.back && te -> "👀 సరే, చూస్తున్నాను… ఫోన్‌ని కదలకుండా పట్టుకోండి."
        l.back -> "👀 Okay, let me look… hold the phone steady."
        te -> "👀 మిమ్మల్ని చూస్తున్నాను…"
        else -> "👀 Let me look at you…"
    }
}

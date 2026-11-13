package com.soulfriend.pet

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Interesting talk": when a spoken conversation goes quiet, the pet may say one short interesting thing (once per
 * conversation, in the daytime, and only if it's on in App › Voice): a safety tip, a health tip, a "did you know", a
 * riddle or a light question. From the server's Habit Media posts when it can (Telugu), otherwise from this list on the
 * phone. Each is written to be true and kind; the last ones told are not told again soon.
 */
object Sparks {
    class Spark(val id: String, val en: String, val te: String, val mood: PuppyView.Mood = PuppyView.Mood.EXCITED)

    private val LIST = listOf(
        Spark("otp", "Quick safety tip: banks never ask for your OTP or PIN on the phone. If someone asks, just cut the call.",
            "ఒక జాగ్రత్త: బ్యాంకులు ఎప్పుడూ ఫోన్‌లో OTP గానీ PIN గానీ అడగవు. ఎవరైనా అడిగితే వెంటనే ఫోన్ పెట్టేయండి.", PuppyView.Mood.PROUD),
        Spark("1930", "Did you know? If money is lost to an online fraud, call 1930 at once. A quick call can stop the money.",
            "మీకు తెలుసా? ఆన్‌లైన్ మోసంలో డబ్బు పోతే వెంటనే 1930 కి కాల్ చేయండి. త్వరగా చేస్తే డబ్బు ఆపగలరు.", PuppyView.Mood.PROUD),
        Spark("links", "Tip: a message says your bank account will be blocked? Don't tap its link. Open your bank's own app instead.",
            "ఒక చిట్కా: \"మీ బ్యాంక్ ఖాతా బ్లాక్ అవుతుంది\" అని మెసేజ్ వచ్చిందా? ఆ లింక్ నొక్కకండి. మీ బ్యాంక్ యాప్ మీరే తెరవండి.", PuppyView.Mood.PROUD),
        Spark("112", "Remember: in an emergency, 112 calls police, fire and ambulance, all with one number.",
            "గుర్తుంచుకోండి: అత్యవసరంలో 112 కి కాల్ చేస్తే పోలీసు, ఫైర్, అంబులెన్స్ అన్నీ ఒకే నంబర్‌తో వస్తాయి.", PuppyView.Mood.PROUD),
        Spark("water", "Health tip: how about a glass of water now? Many of us forget to drink water in the afternoon.",
            "ఆరోగ్య చిట్కా: ఇప్పుడు ఒక గ్లాసు నీళ్ళు తాగుదామా? మధ్యాహ్నం చాలామంది నీళ్ళు తాగడం మర్చిపోతారు.", PuppyView.Mood.HAPPY),
        Spark("eyes", "Eye tip: using the phone a lot? Every 20 minutes, look at something far away for 20 seconds.",
            "కళ్ళ కోసం చిట్కా: ఫోన్ ఎక్కువ చూస్తున్నారా? ప్రతి 20 నిమిషాలకు 20 సెకన్లు దూరంగా ఉన్నదాన్ని చూడండి.", PuppyView.Mood.HAPPY),
        Spark("hands", "Did you know? Washing hands with soap for 20 seconds is about as long as singing \"Happy Birthday\" twice.",
            "మీకు తెలుసా? సబ్బుతో 20 సెకన్లు చేతులు కడగడం అంటే \"హ్యాపీ బర్త్‌డే\" పాట రెండుసార్లు పాడినంత సేపు.", PuppyView.Mood.HAPPY),
        Spark("walk", "Tip: a short walk after a meal helps digestion. Even ten minutes is good.",
            "ఒక చిట్కా: భోజనం తర్వాత కొంచెం నడిస్తే అరుగుదలకు మంచిది. పది నిమిషాలైనా చాలు.", PuppyView.Mood.HAPPY),
        Spark("honey", "Did you know? Honey hardly ever goes bad. Honey found in very old Egyptian tombs was still fine.",
            "మీకు తెలుసా? తేనె దాదాపు ఎప్పటికీ పాడవదు. చాలా పాత ఈజిప్ట్ సమాధుల్లో దొరికిన తేనె కూడా బాగానే ఉంది."),
        Spark("octopus", "Did you know? An octopus has three hearts!", "మీకు తెలుసా? ఆక్టోపస్‌కు మూడు గుండెలు ఉంటాయి!"),
        Spark("banana", "Did you know? A banana is a berry, but a strawberry is not!", "మీకు తెలుసా? వృక్షశాస్త్రం ప్రకారం అరటిపండు బెర్రీ, కానీ స్ట్రాబెర్రీ కాదు!"),
        Spark("sun", "Did you know? Sunlight takes about 8 minutes to reach the Earth.", "మీకు తెలుసా? సూర్యుడి కాంతి భూమిని చేరడానికి సుమారు 8 నిమిషాలు పడుతుంది."),
        Spark("godavari", "Did you know? The Godavari is called \"Dakshina Ganga\", the Ganga of the South.",
            "మీకు తెలుసా? గోదావరిని \"దక్షిణ గంగ\" అంటారు. ఇది దక్షిణ భారతదేశంలో అతి పొడవైన నది."),
        Spark("charminar", "Did you know? Hyderabad's Charminar is more than 430 years old. It was built in 1591.",
            "మీకు తెలుసా? హైదరాబాద్ చార్మినార్ 430 ఏళ్ళకు పైబడినది. దాన్ని 1591లో కట్టారు."),
        Spark("telugu", "Did you know? Telugu is often called \"the Italian of the East\", because so many of its words end in a vowel.",
            "మీకు తెలుసా? తెలుగు పదాలు చాలావరకు అచ్చుతో ముగుస్తాయి. అందుకే తెలుగును \"ఇటాలియన్ ఆఫ్ ది ఈస్ట్\" అంటారు."),
        Spark("tiger", "Did you know? Most of the world's wild tigers live in India.", "మీకు తెలుసా? ప్రపంచంలోని అడవి పులుల్లో ఎక్కువ భాగం మన దేశంలోనే ఉన్నాయి."),
        Spark("hummingbird", "Did you know? A hummingbird's heart can beat more than 1,000 times a minute.", "మీకు తెలుసా? హమ్మింగ్‌బర్డ్ గుండె నిమిషానికి 1,000 సార్ల కంటే ఎక్కువ కొట్టుకోగలదు."),
        Spark("food", "Tell me, what did you eat today? 😋", "చెప్పండి, ఈరోజు ఏం తిన్నారు? 😋", PuppyView.Mood.HAPPY),
        Spark("good", "What's one good thing that happened to you today?", "ఈరోజు మీకు జరిగిన ఒక మంచి విషయం చెప్పండి?", PuppyView.Mood.LOVE),
        Spark("game", "Shall we play a quick game? Just say \"let's play\"! 🎮", "ఒక చిన్న ఆట ఆడదామా? \"ఆట ఆడదాం\" అనండి చాలు! 🎮", PuppyView.Mood.EXCITED),
        Spark("r-clock", "Here's a riddle: what has hands but cannot clap? … A clock! 😄", "ఒక పొడుపు కథ: ఎంత తీస్తే అంత పెద్దదవుతుంది. ఏమిటది? … గొయ్యి! 😄", PuppyView.Mood.LAUGHING),
        Spark("r-towel", "Here's a riddle: what gets wetter the more it dries? … A towel! 😄", "ఒక పొడుపు కథ: పచ్చని ఇల్లు, ఎర్రని గదులు, నల్లని పిల్లలు. ఏమిటి? … పుచ్చకాయ! 😄", PuppyView.Mood.LAUGHING),
        Spark("r-comb", "Here's a riddle: what has many teeth but never bites? … A comb! 😄", "ఒక పొడుపు కథ: రెక్కలు లేకుండా ఎగురుతుంది, కళ్ళు లేకుండా ఏడుస్తుంది. ఏమిటది? … మేఘం! 😄", PuppyView.Mood.LAUGHING)
    )

    /** One from this phone's list, not one of the last ones told. */
    fun local(store: Store, te: Boolean): Pair<String, PuppyView.Mood> {
        val seen = store.sparksSeen()
        val s = (LIST.filter { it.id !in seen }.ifEmpty { LIST }).random()
        store.sparkTold(s.id)
        return (if (te) s.te else s.en) to s.mood
    }

    /** One from the server's Habit Media (Telugu), or null (offline, English, nothing new, or slow). Run off the main thread. */
    fun fromServer(store: Store, lang: String): JSONObject? = try {
        val server = store.server
        if (!server.startsWith("https://")) null else {
            val c = URL("$server/api/pet/spark?lang=$lang").openConnection() as HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 4000
            c.setRequestProperty("X-Device", store.deviceId)
            val ok = c.responseCode == 200
            val j = if (ok) JSONObject(c.inputStream.bufferedReader().readText()) else null
            c.disconnect()
            j?.takeIf { it.optString("reply").isNotBlank() }
        }
    } catch (e: Exception) { null }
}

package net.cookiemunch

import org.json.JSONObject

/** Opt-in ("ask before anything fires") vs opt-out ("fire, but honour a refusal"). */
enum class ConsentModel(val wire: String) {
    OPT_IN("opt-in"),
    OPT_OUT("opt-out");

    companion object {
        /** Lenient: an unrecognised value degrades to the safe direction. */
        fun from(value: String?): ConsentModel =
            entries.firstOrNull { it.wire == value } ?: OPT_IN
    }
}

enum class RegionClass(val wire: String) {
    EU("eu"), US("us"), BR("br"), CA("ca"), OTHER("other");

    companion object {
        fun from(value: String?): RegionClass =
            entries.firstOrNull { it.wire == value } ?: OTHER
    }
}

/** The signalling framework third parties on this page will read. */
enum class SignalFramework(val wire: String) {
    TCF("tcf"), GPP("gpp"), NONE("none");

    companion object {
        fun from(value: String?): SignalFramework =
            entries.firstOrNull { it.wire == value } ?: NONE
    }
}

/**
 * Which privacy regime applies to this person, and what that means for the app.
 *
 * A Kotlin port of `packages/geo/src/index.ts` — the same table as the web embed and
 * the iOS SDK, so the three cannot disagree about someone's rights. Resolving locally
 * costs nothing and works offline, but see [CookieMunchConsent.refreshRegulation]:
 * a device's locale says where the phone was sold, not where its owner is standing.
 */
data class Regulation(
    /** ISO 3166-1 alpha-2, optionally with a subdivision (`"us-ca"`). */
    val region: String,
    val regionClass: RegionClass,
    val gdprApplies: Boolean,
    val ccpaApplies: Boolean,
    val lgpdApplies: Boolean,
    val model: ConsentModel,
    /** What categories default to before the person has said anything. */
    val defaultGranted: Boolean,
    val framework: SignalFramework,
    /** A browser/OS-level signal (GPC, DNT) already expressed a refusal for this person. */
    val forcedOptOut: Boolean,
    /**
     * Whether a decision still has to be collected. See
     * [CookieMunchConsent.isConsentRequired], which also accounts for a decision this
     * person already made in the app.
     */
    val consentRequired: Boolean,
    /**
     * The US state law governing this person, when the server resolved one.
     *
     * Around twenty states have comprehensive laws now and they differ on what a consent
     * UI must do. Only the server can say which applies — a device's locale gives a
     * country at best, never a state — so this is null when the regime was resolved on
     * device, and populated from `/config/:cbid`.
     */
    val stateLaw: UsStateLaw? = null,
) {
    /** One US state privacy law, as the server resolved it. */
    data class UsStateLaw(
        /** Stable id, e.g. `"tdpsa"`. */
        val id: String,
        /** Two-letter state code. */
        val state: String,
        val name: String,
        /** The law requires honouring a universal opt-out signal. */
        val universalOptOut: Boolean,
        /** Sensitive data needs opt-in consent rather than an opt-out. */
        val sensitiveOptIn: Boolean,
        /** Opt-in required below this age for sale / targeted advertising; 0 = no rule. */
        val minorOptInUnder: Int,
    )

    companion object {
        // EU 27 + EEA + UK, lowercase ISO 3166-1 alpha-2.
        private val EU_EEA_UK = setOf(
            "at", "be", "bg", "hr", "cy", "cz", "dk", "ee", "fi", "fr", "de", "gr", "hu", "ie",
            "it", "lv", "lt", "lu", "mt", "nl", "pl", "pt", "ro", "sk", "si", "es", "se",
            "is", "li", "no",
            "gb", "uk",
        )

        fun classify(region: String?): RegionClass {
            if (region.isNullOrEmpty()) return RegionClass.OTHER
            val country = region.lowercase().substringBefore('-')
            return when {
                country in EU_EEA_UK -> RegionClass.EU
                country == "us" -> RegionClass.US
                country == "br" -> RegionClass.BR
                country == "ca" -> RegionClass.CA
                else -> RegionClass.OTHER
            }
        }

        /**
         * Resolve the regime for [region] and the opt-out signals available on device.
         *
         * @param honorDnt treat [dnt] as a refusal. Default true.
         * @param unknownModel the regime for regions we don't recognise. Default opt-in,
         *   which is the safe direction to be wrong in.
         */
        @JvmStatic
        @JvmOverloads
        fun resolve(
            region: String?,
            gpc: Boolean = false,
            dnt: Boolean = false,
            honorDnt: Boolean = true,
            unknownModel: ConsentModel = ConsentModel.OPT_IN,
        ): Regulation {
            val cls = classify(region)

            val model: ConsentModel
            val framework: SignalFramework
            when (cls) {
                RegionClass.US -> { model = ConsentModel.OPT_OUT; framework = SignalFramework.GPP }
                RegionClass.EU -> { model = ConsentModel.OPT_IN; framework = SignalFramework.TCF }
                RegionClass.BR, RegionClass.CA -> { model = ConsentModel.OPT_IN; framework = SignalFramework.NONE }
                RegionClass.OTHER -> { model = unknownModel; framework = SignalFramework.NONE }
            }

            // GPC/DNT only matter where collection would otherwise proceed. Under an
            // opt-in regime nothing fires before consent anyway, so there is nothing
            // to force.
            val forcedOptOut = model == ConsentModel.OPT_OUT && (gpc || (honorDnt && dnt))

            return Regulation(
                region = region ?: "",
                regionClass = cls,
                gdprApplies = cls == RegionClass.EU,
                ccpaApplies = cls == RegionClass.US,
                lgpdApplies = cls == RegionClass.BR,
                model = model,
                defaultGranted = model == ConsentModel.OPT_OUT,
                framework = framework,
                forcedOptOut = forcedOptOut,
                consentRequired = !forcedOptOut,
            )
        }

        /**
         * Parse the `regulation` block of a `/config/:cbid` response. Returns null when
         * the block is absent (an older server) or malformed — the caller then keeps
         * whatever it resolved locally.
         */
        @JvmStatic
        fun fromConfigJson(json: String): Regulation? {
            return try {
            val regulation = JSONObject(json).optJSONObject("regulation") ?: return null
            val flags = regulation.optJSONObject("regulations") ?: JSONObject()
            Regulation(
                region = regulation.optString("region", ""),
                regionClass = RegionClass.from(regulation.optString("class")),
                gdprApplies = flags.optBoolean("gdprApplies"),
                ccpaApplies = flags.optBoolean("ccpaApplies"),
                lgpdApplies = flags.optBoolean("lgpdApplies"),
                model = ConsentModel.from(regulation.optString("model")),
                defaultGranted = regulation.optString("defaultState") == "granted",
                framework = SignalFramework.from(regulation.optString("framework")),
                forcedOptOut = regulation.optBoolean("forcedOptOut"),
                // An older server could omit this; derive it rather than defaulting to
                // false, which would silently suppress every prompt.
                consentRequired = if (regulation.has("consentRequired")) {
                    regulation.optBoolean("consentRequired")
                } else {
                    !regulation.optBoolean("forcedOptOut")
                },
                stateLaw = regulation.optJSONObject("stateLaw")?.let { law ->
                    val id = law.optString("id")
                    val state = law.optString("state")
                    if (id.isEmpty() || state.isEmpty()) {
                        null
                    } else {
                        UsStateLaw(
                            id = id,
                            state = state,
                            name = law.optString("name", id),
                            // Prefer whether the duty is in force; fall back to whether the
                            // law mandates it, for a server predating the distinction.
                            universalOptOut = if (law.has("universalOptOutInForce")) {
                                law.optBoolean("universalOptOutInForce")
                            } else {
                                law.optBoolean("universalOptOut")
                            },
                            sensitiveOptIn = law.optBoolean("sensitiveOptIn"),
                            minorOptInUnder = law.optInt("minorOptInUnder", 0),
                        )
                    }
                },
            )
            } catch (_: Exception) {
                null
            }
        }
    }
}

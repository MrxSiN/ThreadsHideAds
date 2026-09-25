package X;

/** Feed unit type enum as Threads ships it: readable constants, server values. */
public enum UnitType {
    THREAD("thread"),
    AD("ad"),
    AD4AD("ad4ad"),
    INTENT_AWARE_AD_PIVOT("intent_aware_ad_pivot"),
    ADS_FEEDBACK_INTERFACE("ads_feedback_interface"),
    SUGGESTED_USERS("suggested_users"),
    PROMPT_NETEGO("prompt_netego");

    private final String server;

    UnitType(String server) {
        this.server = server;
    }

    @Override
    public String toString() {
        return server;
    }
}

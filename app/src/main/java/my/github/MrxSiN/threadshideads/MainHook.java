package my.github.MrxSiN.threadshideads;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Threads-only LSPosed entry point.
 *
 * Uses layered feed-model sanitization plus a rendered-label fallback.
 */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "ThreadsHideAds";
    private static final String THREADS_PACKAGE = "com.instagram.barcelona";

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean UI_FALLBACK_INSTALLED = new AtomicBoolean(false);

    private static final AtomicInteger MODEL_REMOVAL_TOTAL = new AtomicInteger();
    private static final AtomicInteger MODEL_REMOVAL_EVENTS = new AtomicInteger();
    private static final AtomicInteger UI_COLLAPSE_TOTAL = new AtomicInteger();
    private static final AtomicInteger REPLACEMENT_FAILURES = new AtomicInteger();

    private static final int MAX_INSPECTION_DEPTH = 5;
    private static final int MAX_INSPECTION_BUDGET = 320;
    private static final int MAX_NESTED_SANITIZE_DEPTH = 4;
    private static final int MAX_NESTED_SANITIZE_BUDGET = 192;
    private static final int MAX_UI_SCAN_VIEWS = 320;

    /*
     * Candidate descriptors are intentionally not logged one-by-one. Threads
     * currently yields hundreds of broad matches and the noise obscures the
     * feed hook activity that matters during testing.
     */
    private static final boolean VERBOSE_DISCOVERY = false;

    /*
     * Avoid overly broad strings such as just "ad".
     * Those match thousands of unrelated methods.
     */
    private static final String PRIMARY_BOUNDARY_MARKER = "Sponsored";

    private static final String[] DEX_MARKERS = {
            "is_sponsored",
            "isSponsored",
            "sponsored_label",
            "Sponsored",
            "sponsored",
            "direct_ad",
            "directAd",
            "ad_type",
            "ad_id",
            "adchoices",
            "AdChoices",
            "multi_ads",
            "MultiAds"
    };

    private static final Set<String> STRONG_BOOLEAN_MARKERS =
            new LinkedHashSet<>(Arrays.asList(
                    "is_sponsored",
                    "isSponsored",
                    "direct_ad",
                    "directAd",
                    "sponsored_label"
            ));

    private static final Set<String> CORE_LIST_MARKERS =
            new LinkedHashSet<>(Arrays.asList(
                    "is_sponsored",
                    "isSponsored",
                    "sponsored_label",
                    "Sponsored",
                    "sponsored",
                    "direct_ad",
                    "directAd",
                    "ad_type",
                    "ad_id",
                    "adchoices",
                    "AdChoices",
                    "multi_ads",
                    "MultiAds"
            ));

    private static final String[] NON_FEED_SIGNATURE_TOKENS = {
            "bugreport",
            "notification",
            "armadillo",
            "direct.store",
            "directsqlite",
            "message",
            "messaging",
            "exoplayer",
            "heroplayer",
            "music",
            "quickpromotion",
            "upload"
    };

    private static final Set<String> AD_LABELS =
            new LinkedHashSet<>(Arrays.asList(
                    "sponsored",
                    "promoted",
                    "advertisement",
                    "paid partnership",

                    // Malay
                    "ditaja",

                    // Indonesian
                    "disponsori",
                    "bersponsor",

                    // Common additional locales
                    "patrocinado",
                    "publicidad",
                    "gesponsert",
                    "sponsorisé",
                    "sponsorizzato"
            ));

    private static final Set<String> HOOKED_METHODS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final Map<View, SavedViewState> HIDDEN_VIEWS =
            Collections.synchronizedMap(new WeakHashMap<View, SavedViewState>());

    private static final Map<View, Boolean> PENDING_UI_SCANS =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private static final Map<Class<?>, Field[]> INSTANCE_FIELD_CACHE =
            new ConcurrentHashMap<>();

    private static final Map<Class<?>, Boolean> STRUCTURED_TOSTRING_CACHE =
            new ConcurrentHashMap<>();

    private static final Map<Class<?>, Boolean> LIST_CONTAINER_CLASS_CACHE =
            new ConcurrentHashMap<>();

    static {
        System.loadLibrary("dexkit");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!THREADS_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        /*
         * Limit expensive scanning to the main Threads process.
         * Static fields are process-local.
         */
        if (lpparam.processName != null
                && !THREADS_PACKAGE.equals(lpparam.processName)) {
            return;
        }

        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        log("Initializing in process " + lpparam.processName);

        installRenderedLabelFallback();

        /*
         * Use the active class loader instead of only appInfo.sourceDir.
         * This includes dex paths visible to the application's loader.
         */
        try (DexKitBridge bridge =
                     DexKitBridge.create(lpparam.classLoader, true)) {

            log("DexKit parsed dex count: " + bridge.getDexNum());

            int listHooks = installPrimaryFeedBoundaryHooks(
                    bridge,
                    lpparam.classLoader
            );

            if (listHooks > 0) {
                log("Primary feed boundary installed; broad discovery skipped");
            } else {
                Map<Method, Set<String>> candidates =
                        discoverCandidates(bridge, lpparam.classLoader);

                int booleanHooks = 0;
                int skipped = 0;

                for (Map.Entry<Method, Set<String>> entry : candidates.entrySet()) {
                    Method method = entry.getKey();
                    Set<String> markers = entry.getValue();

                    try {
                        if (isLikelyAdListBoundary(method, markers)) {
                            if (hookListBoundary(method)) {
                                listHooks++;
                            }
                        } else if (isSafePositiveAdBoolean(method, markers)) {
                            if (hookBooleanPredicate(method)) {
                                booleanHooks++;
                            }
                        } else {
                            skipped++;
                        }
                    } catch (Throwable throwable) {
                        log("Unable to hook " + method + ": " + throwable);
                    }
                }

                log("Fallback discovery complete: candidates=" + candidates.size()
                        + ", listHooks=" + listHooks
                        + ", booleanHooks=" + booleanHooks
                        + ", skipped=" + skipped);
            }

        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": Critical initialization failure");
            XposedBridge.log(throwable);
        }
    }

    private static int installPrimaryFeedBoundaryHooks(
            DexKitBridge bridge,
            ClassLoader classLoader
    ) {
        LinkedHashSet<Method> matches = new LinkedHashSet<>();

        try {
            MethodDataList candidates = bridge.findMethod(
                    FindMethod.create().matcher(
                            MethodMatcher.create().usingStrings(
                                    Collections.singletonList(
                                            PRIMARY_BOUNDARY_MARKER
                                    ),
                                    StringMatchType.Contains
                            )
                    )
            );

            for (MethodData data : candidates) {
                try {
                    Method method = data.getMethodInstance(classLoader);
                    if (isPrimaryFeedBoundaryShape(method)) {
                        matches.add(method);
                    }
                } catch (Throwable ignored) {
                    // Broad fallback will handle unresolved candidates.
                }
            }
        } catch (Throwable throwable) {
            log("Primary feed-boundary search failed: " + throwable);
            return 0;
        }

        if (matches.size() != 1) {
            log("Primary feed-boundary search found " + matches.size()
                    + " structural match(es); using broad fallback");
            return 0;
        }

        Method method = matches.iterator().next();
        try {
            if (hookListBoundary(method)) {
                log("Installed confirmed feed boundary: " + method);
                return 1;
            }
        } catch (Throwable throwable) {
            log("Unable to install confirmed feed boundary "
                    + method + ": " + throwable);
        }

        return 0;
    }

    private static boolean isPrimaryFeedBoundaryShape(Method method) {
        Class<?>[] parameters = method.getParameterTypes();

        return !Modifier.isStatic(method.getModifiers())
                && parameters.length == 3
                && isIntegerType(parameters[0])
                && isIntegerType(parameters[1])
                && List.class.isAssignableFrom(parameters[2])
                && method.getReturnType() != void.class;
    }

    private static boolean isIntegerType(Class<?> type) {
        return type == Integer.class || type == int.class;
    }

    private static Map<Method, Set<String>> discoverCandidates(
            DexKitBridge bridge,
            ClassLoader classLoader
    ) {
        Map<Method, Set<String>> candidates = new LinkedHashMap<>();

        for (String marker : DEX_MARKERS) {
            try {
                MethodDataList matches = bridge.findMethod(
                        FindMethod.create().matcher(
                                MethodMatcher.create().usingStrings(
                                        Collections.singletonList(marker),
                                        StringMatchType.Contains
                                )
                        )
                );

                log("Marker '" + marker + "' produced "
                        + matches.size() + " method matches");

                for (MethodData data : matches) {
                    try {
                        Method method =
                                data.getMethodInstance(classLoader);

                        Set<String> methodMarkers = candidates.get(method);
                        if (methodMarkers == null) {
                            methodMarkers = new LinkedHashSet<>();
                            candidates.put(method, methodMarkers);
                        }

                        methodMarkers.add(marker);

                        if (VERBOSE_DISCOVERY) {
                            log("Candidate: " + data.getDescriptor()
                                    + " marker=" + marker);
                        }
                    } catch (Throwable throwable) {
                        if (VERBOSE_DISCOVERY) {
                            log("Cannot resolve candidate "
                                    + data.getDescriptor() + ": "
                                    + throwable.getClass().getSimpleName());
                        }
                    }
                }
            } catch (Throwable throwable) {
                log("Search failed for marker '" + marker + "': "
                        + throwable);
            }
        }

        return candidates;
    }

    /**
     * Feed boundaries commonly accept or return java.util.List.
     */
    private static boolean hasListBoundary(Method method) {
        if (List.class.isAssignableFrom(method.getReturnType())
                || Collection.class.isAssignableFrom(method.getReturnType())) {
            return true;
        }

        for (Class<?> parameterType : method.getParameterTypes()) {
            if (List.class.isAssignableFrom(parameterType)
                    || Collection.class.isAssignableFrom(parameterType)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isLikelyAdListBoundary(
            Method method,
            Set<String> markers
    ) {
        if (!hasListBoundary(method)) {
            return false;
        }

        boolean hasCoreMarker = false;
        for (String marker : markers) {
            if (CORE_LIST_MARKERS.contains(marker)) {
                hasCoreMarker = true;
                break;
            }
        }

        if (!hasCoreMarker) {
            return false;
        }

        String signature = method.toGenericString()
                .toLowerCase(Locale.ROOT);

        for (String token : NON_FEED_SIGNATURE_TOKENS) {
            if (signature.contains(token)) {
                return false;
            }
        }

        return true;
    }

    private static boolean hookListBoundary(final Method method) {
        String key = method.toGenericString() + "#list";

        if (!HOOKED_METHODS.add(key)) {
            return false;
        }

        final Class<?>[] parameterTypes = method.getParameterTypes();

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                for (int i = 0; i < param.args.length; i++) {
                    Object argument = param.args[i];

                    if (!(argument instanceof List<?>)) {
                        continue;
                    }

                    SanitizedList sanitized = sanitizeList(
                            argument,
                            parameterTypes[i],
                            true
                    );

                    if (!sanitized.changed) {
                        continue;
                    }

                    if (sanitized.value != argument) {
                        param.args[i] = sanitized.value;
                    }

                    recordModelRemoval(
                            sanitized.removed,
                            "argument " + i + " of " + method
                    );
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) {
                    return;
                }

                /*
                 * Some Threads builds append or wrap sponsored entries inside
                 * the boundary method. Re-sanitize argument lists after the
                 * call, then inspect collection fields in the returned wrapper.
                 */
                for (int i = 0; i < param.args.length; i++) {
                    Object argument = param.args[i];
                    if (!(argument instanceof List<?>)) {
                        continue;
                    }

                    SanitizedList postCall = sanitizeList(
                            argument,
                            argument.getClass(),
                            false
                    );

                    if (postCall.changed) {
                        recordModelRemoval(
                                postCall.removed,
                                "late argument " + i + " of " + method
                        );
                    }
                }

                Object result = param.getResult();

                if (result instanceof List<?>) {
                    SanitizedList sanitized = sanitizeList(
                            result,
                            method.getReturnType(),
                            true
                    );

                    if (sanitized.changed) {
                        if (sanitized.value != result) {
                            param.setResult(sanitized.value);
                            result = sanitized.value;
                        }

                        recordModelRemoval(
                                sanitized.removed,
                                "result of " + method
                        );
                    }
                }

                int nestedRemoved = sanitizeNestedCollections(result);
                if (nestedRemoved > 0) {
                    recordModelRemoval(
                            nestedRemoved,
                            "nested result collections of " + method
                    );
                }
            }
        });

        log("Installed list sanitizer: " + method);
        return true;
    }

    /**
     * Only replace booleans that appear to be positive ad predicates.
     *
     * Methods containing "hide", "remove" or "filter" are deliberately
     * excluded because returning false could enable ads.
     */
    private static boolean isSafePositiveAdBoolean(
            Method method,
            Set<String> markers
    ) {
        if (method.getReturnType() != boolean.class
                && method.getReturnType() != Boolean.class) {
            return false;
        }

        String semanticName = (
                method.getDeclaringClass().getName()
                        + "."
                        + method.getName()
        ).toLowerCase(Locale.ROOT);

        if (semanticName.contains("hide")
                || semanticName.contains("remove")
                || semanticName.contains("filter")
                || semanticName.contains("block")) {
            return false;
        }

        if (semanticName.contains("issponsored")
                || semanticName.contains("isdirectad")
                || semanticName.contains("shouldshowad")
                || semanticName.contains("shouldinsertad")
                || semanticName.contains("caninsertad")
                || semanticName.contains("adsenabled")
                || semanticName.contains("adeligible")) {
            return true;
        }

        for (String marker : markers) {
            if (STRONG_BOOLEAN_MARKERS.contains(marker)) {
                return true;
            }
        }

        return false;
    }

    private static boolean hookBooleanPredicate(Method method) {
        String key = method.toGenericString() + "#boolean";

        if (!HOOKED_METHODS.add(key)) {
            return false;
        }

        XposedBridge.hookMethod(
                method,
                XC_MethodReplacement.returnConstant(false)
        );

        log("Disabled positive ad predicate: " + method);
        return true;
    }

    private static SanitizedList sanitizeList(
            Object value,
            Class<?> declaredType,
            boolean allowReplacement
    ) {
        if (!(value instanceof List<?>)) {
            return SanitizedList.unchanged(value);
        }

        List<?> source = (List<?>) value;
        ArrayList<Integer> adIndexes = new ArrayList<>();
        ArrayList<Object> filtered = new ArrayList<>(source.size());

        for (int i = 0; i < source.size(); i++) {
            Object item = source.get(i);

            /*
             * A nested list is a container, not itself a feed item. Keep it
             * here and let bounded nested sanitization remove its ad entries.
             */
            if (!(item instanceof List<?>) && looksLikeAd(item)) {
                adIndexes.add(i);
            } else {
                filtered.add(item);
            }
        }

        if (adIndexes.isEmpty()) {
            return SanitizedList.unchanged(value);
        }

        int removedInPlace = removeIndexesInPlace(source, adIndexes);
        if (removedInPlace > 0) {
            return new SanitizedList(
                    value,
                    removedInPlace,
                    true
            );
        }

        if (allowReplacement
                && canReplaceWithArrayList(declaredType)) {
            return new SanitizedList(
                    filtered,
                    adIndexes.size(),
                    true
            );
        }

        return SanitizedList.unchanged(value);
    }

    private static int removeIndexesInPlace(
            List<?> source,
            List<Integer> adIndexes
    ) {
        int removed = 0;

        /*
         * Remove backwards so index shifts cannot skip entries. This is safer
         * than clear/addAll: an unsupported operation cannot leave the source
         * list cleared but only partly rebuilt.
         */
        for (int i = adIndexes.size() - 1; i >= 0; i--) {
            int index = adIndexes.get(i);
            try {
                source.remove(index);
                removed++;
            } catch (Throwable ignored) {
                break;
            }
        }

        return removed;
    }

    private static boolean canReplaceWithArrayList(Class<?> declaredType) {
        return declaredType == null
                || declaredType == Object.class
                || declaredType.isInterface()
                || declaredType.isAssignableFrom(ArrayList.class);
    }

    /**
     * Performs bounded inspection of a feed item.
     *
     * It does not blindly call toString(), because captions may contain
     * ordinary words such as "sponsored".
     */
    private static boolean looksLikeAd(Object value) {
        try {
            return inspectObject(
                    value,
                    0,
                    new IdentityHashMap<Object, Boolean>(),
                    new int[]{0}
            );
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean inspectObject(
            Object value,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget
    ) {
        if (value == null
                || depth > MAX_INSPECTION_DEPTH
                || budget[0]++ > MAX_INSPECTION_BUDGET) {
            return false;
        }

        if (value instanceof CharSequence) {
            return isStructuredAdValue(value.toString());
        }

        if (value instanceof Enum<?>) {
            return isStructuredAdValue(((Enum<?>) value).name());
        }

        if (value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Class<?>) {
            return false;
        }

        if (visited.put(value, Boolean.TRUE) != null) {
            return false;
        }

        Class<?> type = value.getClass();
        String className =
                type.getName().toLowerCase(Locale.ROOT);

        if (hasStrongAdClassToken(className)) {
            return true;
        }

        if (shouldInspectStructuredToString(type)) {
            try {
                if (isStructuredAdValue(String.valueOf(value))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // Continue with field inspection.
            }
        }

        if (value instanceof Map<?, ?>) {
            int inspected = 0;

            for (Map.Entry<?, ?> entry :
                    ((Map<?, ?>) value).entrySet()) {
                if (inspected++ >= 32) {
                    break;
                }

                String key = String.valueOf(entry.getKey());
                Object mapValue = entry.getValue();

                if (isStrongAdFieldName(key)
                        && isAdFieldValue(mapValue)) {
                    return true;
                }

                if (inspectObject(
                        mapValue,
                        depth + 1,
                        visited,
                        budget
                )) {
                    return true;
                }
            }

            return false;
        }

        if (value instanceof Collection<?>) {
            int inspected = 0;

            for (Object item : (Collection<?>) value) {
                if (inspected++ >= 32) {
                    break;
                }

                if (inspectObject(
                        item,
                        depth + 1,
                        visited,
                        budget
                )) {
                    return true;
                }
            }

            return false;
        }

        if (type.isArray()) {
            int length = Math.min(Array.getLength(value), 32);

            for (int i = 0; i < length; i++) {
                if (inspectObject(
                        Array.get(value, i),
                        depth + 1,
                        visited,
                        budget
                )) {
                    return true;
                }
            }

            return false;
        }

        for (Field field : getInstanceFields(type)) {
            Object fieldValue;

            try {
                fieldValue = field.get(value);
            } catch (Throwable ignored) {
                continue;
            }

            if (isStrongAdFieldName(field.getName())
                    && isAdFieldValue(fieldValue)) {
                return true;
            }

            if (depth < MAX_INSPECTION_DEPTH
                    && shouldInspectChild(fieldValue)
                    && inspectObject(
                    fieldValue,
                    depth + 1,
                    visited,
                    budget
            )) {
                return true;
            }
        }

        return false;
    }

    private static Field[] getInstanceFields(Class<?> type) {
        Field[] cached = INSTANCE_FIELD_CACHE.get(type);
        if (cached != null) {
            return cached;
        }

        ArrayList<Field> fields = new ArrayList<>();
        Class<?> cursor = type;
        int classDepth = 0;

        while (cursor != null
                && cursor != Object.class
                && classDepth++ < 6) {
            try {
                for (Field field : cursor.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || field.isSynthetic()) {
                        continue;
                    }

                    try {
                        field.setAccessible(true);
                        fields.add(field);
                    } catch (Throwable ignored) {
                        // Skip inaccessible fields once and cache the result.
                    }
                }
            } catch (Throwable ignored) {
                break;
            }

            cursor = cursor.getSuperclass();
        }

        Field[] resolved = fields.toArray(new Field[0]);
        Field[] existing = INSTANCE_FIELD_CACHE.putIfAbsent(type, resolved);
        return existing == null ? resolved : existing;
    }

    private static boolean shouldInspectStructuredToString(Class<?> type) {
        Boolean cached = STRUCTURED_TOSTRING_CACHE.get(type);
        if (cached != null) {
            return cached;
        }

        String name = type.getName();
        boolean result = false;

        if (name.startsWith("X.")
                || name.startsWith("p000X.")
                || name.startsWith("com.instagram.")
                || name.startsWith("com.facebook.")
                || name.startsWith("com.meta.")) {
            try {
                result = type.getMethod("toString")
                        .getDeclaringClass() != Object.class;
            } catch (Throwable ignored) {
                result = false;
            }
        }

        Boolean existing = STRUCTURED_TOSTRING_CACHE.putIfAbsent(type, result);
        return existing == null ? result : existing;
    }

    private static boolean shouldInspectChild(Object value) {
        if (value == null) {
            return false;
        }

        Class<?> type = value.getClass();

        if (type.isArray()
                || value instanceof Collection<?>
                || value instanceof Map<?, ?>
                || value instanceof Enum<?>) {
            return true;
        }

        String name = type.getName();

        return name.startsWith("X.")
                || name.startsWith("p000X.")
                || name.startsWith("com.instagram.")
                || name.startsWith("com.facebook.")
                || name.startsWith("com.meta.");
    }

    private static boolean hasStrongAdClassToken(String className) {
        return className.contains("multiadsfeedunit")
                || className.contains("quickpromotion")
                || className.contains("sponsored")
                || className.contains("adfeedunit")
                || className.contains("advertisementunit")
                || className.contains("directad");
    }

    private static boolean isStrongAdFieldName(String fieldName) {
        String normalized = fieldName
                .toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace("$", "");

        return normalized.equals("issponsored")
                || normalized.equals("sponsoredlabel")
                || normalized.equals("directad")
                || normalized.equals("adid")
                || normalized.equals("adtype")
                || normalized.equals("adunit")
                || normalized.equals("adchoices")
                || normalized.equals("isadvertisement")
                || normalized.equals("admetadata")
                || normalized.equals("sponsoredmetadata")
                || normalized.equals("commercialcontent");
    }

    private static boolean isAdFieldValue(Object value) {
        if (value == null) {
            return false;
        }

        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        if (value instanceof Number) {
            return ((Number) value).longValue() != 0L;
        }

        if (value instanceof CharSequence) {
            String normalized = value.toString()
                    .trim()
                    .toLowerCase(Locale.ROOT);

            return !normalized.isEmpty()
                    && !normalized.equals("false")
                    && !normalized.equals("none")
                    && !normalized.equals("null")
                    && !normalized.equals("organic")
                    && !normalized.equals("unknown")
                    && !normalized.equals("0");
        }

        if (value instanceof Enum<?>) {
            return isStructuredAdValue(
                    ((Enum<?>) value).name()
            );
        }

        /*
         * Non-null ad-unit/model field.
         */
        return true;
    }

    private static boolean isStructuredAdValue(String value) {
        if (value == null) {
            return false;
        }

        String normalized =
                value.trim().toLowerCase(Locale.ROOT);

        String compact = normalized.replace(" ", "");

        return normalized.equals("sponsored")
                || normalized.equals("promotion")
                || normalized.equals("advertisement")
                || normalized.equals("direct_ad")
                || normalized.equals("directad")
                || normalized.equals("multi_ads")
                || normalized.equals("multiads")
                || normalized.equals("quick_promotion")
                || normalized.equals("quickpromotion")
                || compact.contains("\"is_sponsored\":true")
                || compact.contains("is_sponsored=true")
                || compact.contains("issponsored=true")
                || compact.contains("\"direct_ad\":true")
                || compact.contains("direct_ad=true")
                || compact.contains("\"sponsored_label\":")
                || compact.contains("sponsored_label=")
                || compact.contains("\"ad_metadata\":{")
                || compact.contains("\"sponsored_metadata\":{")
                || compact.contains("\"ad_id\":\"")
                || compact.contains("ad_id=")
                || compact.contains("\"is_paid_partnership\":true");
    }

    private static int sanitizeNestedCollections(Object root) {
        if (root == null) {
            return 0;
        }

        try {
            return sanitizeNestedCollections(
                    root,
                    root.getClass(),
                    null,
                    0,
                    new IdentityHashMap<Object, Boolean>(),
                    new int[]{0}
            );
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int sanitizeNestedCollections(
            Object value,
            Class<?> declaredType,
            ValueReplacer replacer,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget
    ) {
        if (value == null
                || depth > MAX_NESTED_SANITIZE_DEPTH
                || budget[0]++ > MAX_NESTED_SANITIZE_BUDGET
                || isSimpleValue(value)) {
            return 0;
        }

        if (visited.put(value, Boolean.TRUE) != null) {
            return 0;
        }

        int removed = 0;

        if (value instanceof List<?>) {
            SanitizedList sanitized = sanitizeList(
                    value,
                    declaredType,
                    replacer != null
            );

            if (sanitized.changed) {
                if (sanitized.value == value) {
                    removed += sanitized.removed;
                } else if (replacer != null
                        && replacer.replace(sanitized.value)) {
                    value = sanitized.value;
                    visited.put(value, Boolean.TRUE);
                    removed += sanitized.removed;
                } else {
                    recordReplacementFailure();
                }
            }

            final List<?> list = (List<?>) value;
            int count = Math.min(list.size(), 48);

            for (int i = 0; i < count; i++) {
                final int index = i;
                Object child;
                try {
                    child = list.get(index);
                } catch (Throwable ignored) {
                    break;
                }

                removed += sanitizeNestedCollections(
                        child,
                        Object.class,
                        new ValueReplacer() {
                            @Override
                            public boolean replace(Object replacement) {
                                try {
                                    @SuppressWarnings("unchecked")
                                    List<Object> writable =
                                            (List<Object>) list;
                                    writable.set(index, replacement);
                                    return true;
                                } catch (Throwable ignored) {
                                    return false;
                                }
                            }
                        },
                        depth + 1,
                        visited,
                        budget
                );
            }

            return removed;
        }

        if (value instanceof Map<?, ?>) {
            final Map<?, ?> map = (Map<?, ?>) value;
            int inspected = 0;

            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                if (inspected++ >= 48) {
                    break;
                }

                final Object key = entry.getKey();
                Object child = entry.getValue();

                removed += sanitizeNestedCollections(
                        child,
                        Object.class,
                        new ValueReplacer() {
                            @Override
                            public boolean replace(Object replacement) {
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map.Entry<Object, Object> writableEntry =
                                            (Map.Entry<Object, Object>) entry;
                                    writableEntry.setValue(replacement);
                                    return true;
                                } catch (Throwable ignored) {
                                    try {
                                        @SuppressWarnings("unchecked")
                                        Map<Object, Object> writableMap =
                                                (Map<Object, Object>) map;
                                        writableMap.put(key, replacement);
                                        return true;
                                    } catch (Throwable ignoredAgain) {
                                        return false;
                                    }
                                }
                            }
                        },
                        depth + 1,
                        visited,
                        budget
                );
            }

            return removed;
        }

        Class<?> type = value.getClass();
        if (type.isArray()) {
            final Object array = value;
            final Class<?> componentType = type.getComponentType();
            int length = Math.min(Array.getLength(array), 48);

            for (int i = 0; i < length; i++) {
                final int index = i;
                Object child = Array.get(array, index);

                removed += sanitizeNestedCollections(
                        child,
                        componentType,
                        new ValueReplacer() {
                            @Override
                            public boolean replace(Object replacement) {
                                try {
                                    Array.set(array, index, replacement);
                                    return true;
                                } catch (Throwable ignored) {
                                    return false;
                                }
                            }
                        },
                        depth + 1,
                        visited,
                        budget
                );
            }

            return removed;
        }

        if (!shouldInspectChild(value)) {
            return 0;
        }

        final Object owner = value;
        for (final Field field : getInstanceFields(type)) {
            Object child;
            try {
                child = field.get(owner);
            } catch (Throwable ignored) {
                continue;
            }

            removed += sanitizeNestedCollections(
                    child,
                    field.getType(),
                    new ValueReplacer() {
                        @Override
                        public boolean replace(Object replacement) {
                            try {
                                field.set(owner, replacement);
                                return true;
                            } catch (Throwable ignored) {
                                return false;
                            }
                        }
                    },
                    depth + 1,
                    visited,
                    budget
            );
        }

        return removed;
    }

    private static boolean isSimpleValue(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof Class<?>;
    }

    /*
     * UI fallback
     */

    private static void installRenderedLabelFallback() {
        if (!UI_FALLBACK_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        XposedBridge.hookAllMethods(
                TextView.class,
                "setText",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        TextView textView =
                                (TextView) param.thisObject;

                        CharSequence text = textView.getText();

                        if (!isAdLabel(text)) {
                            scheduleRestore(textView);
                            return;
                        }

                        textView.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isCurrentAdMarker(textView)) {
                                    hideFeedItem(textView);
                                } else {
                                    restoreMarkedAncestor(textView);
                                }
                            }
                        });
                    }
                }
        );

        XposedBridge.hookAllMethods(
                View.class,
                "setContentDescription",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        final View view = (View) param.thisObject;

                        if (!isAdLabel(
                                view.getContentDescription()
                        )) {
                            scheduleRestore(view);
                            return;
                        }

                        view.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isCurrentAdMarker(view)) {
                                    hideFeedItem(view);
                                } else {
                                    restoreMarkedAncestor(view);
                                }
                            }
                        });
                    }
                }
        );

        XposedBridge.hookAllMethods(
                ViewGroup.class,
                "addView",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                            MethodHookParam param
                    ) {
                        ViewGroup parent = (ViewGroup) param.thisObject;
                        if (!isWithinListContainer(parent, 8)) {
                            return;
                        }

                        for (Object argument : param.args) {
                            if (argument instanceof View) {
                                scheduleSubtreeScan((View) argument);
                                break;
                            }
                        }
                    }
                }
        );

        log("Installed rendered-label and attached-view fallbacks");
    }

    private static void scheduleSubtreeScan(final View root) {
        synchronized (PENDING_UI_SCANS) {
            if (PENDING_UI_SCANS.put(root, Boolean.TRUE) != null) {
                return;
            }
        }

        root.post(new Runnable() {
            @Override
            public void run() {
                synchronized (PENDING_UI_SCANS) {
                    PENDING_UI_SCANS.remove(root);
                }
                scanSubtreeForAdLabel(root);
            }
        });
    }

    private static void scheduleRestore(final View markerView) {
        if (!hasMarkedAncestor(markerView)) {
            return;
        }

        markerView.post(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentAdMarker(markerView)) {
                    restoreMarkedAncestor(markerView);
                }
            }
        });
    }

    private static boolean hasMarkedAncestor(View child) {
        View current = child;
        for (int depth = 0; depth < 14; depth++) {
            synchronized (HIDDEN_VIEWS) {
                if (HIDDEN_VIEWS.containsKey(current)) {
                    return true;
                }
            }

            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                return false;
            }
            current = (View) parent;
        }
        return false;
    }

    private static boolean isWithinListContainer(View view, int maxDepth) {
        View current = view;
        for (int depth = 0; depth <= maxDepth; depth++) {
            if (isListContainer(current)) {
                return true;
            }

            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                return false;
            }
            current = (View) parent;
        }
        return false;
    }

    private static void scanSubtreeForAdLabel(View root) {
        ArrayList<View> pending = new ArrayList<>();
        pending.add(root);
        int cursor = 0;

        while (cursor < pending.size()
                && cursor < MAX_UI_SCAN_VIEWS) {
            View view = pending.get(cursor++);

            if ((view instanceof TextView
                    && isAdLabel(((TextView) view).getText()))
                    || isAdLabel(view.getContentDescription())) {
                hideFeedItem(view);
                return;
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                int count = Math.min(group.getChildCount(), 64);
                for (int i = 0; i < count; i++) {
                    View child = group.getChildAt(i);
                    if (child != null) {
                        pending.add(child);
                    }
                }
            }
        }
    }

    private static boolean subtreeContainsAdLabel(View root) {
        ArrayList<View> pending = new ArrayList<>();
        pending.add(root);
        int cursor = 0;

        while (cursor < pending.size() && cursor < 160) {
            View view = pending.get(cursor++);
            if ((view instanceof TextView
                    && isAdLabel(((TextView) view).getText()))
                    || isAdLabel(view.getContentDescription())) {
                return true;
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                int count = Math.min(group.getChildCount(), 48);
                for (int i = 0; i < count; i++) {
                    View child = group.getChildAt(i);
                    if (child != null) {
                        pending.add(child);
                    }
                }
            }
        }

        return false;
    }

    private static boolean isCurrentAdMarker(View view) {
        return (view instanceof TextView
                && isAdLabel(((TextView) view).getText()))
                || isAdLabel(view.getContentDescription());
    }

    private static boolean isAdLabel(CharSequence value) {
        if (value == null) {
            return false;
        }

        return AD_LABELS.contains(normalizeLabel(value));
    }

    private static String normalizeLabel(CharSequence value) {
        String input = value.toString().trim().toLowerCase(Locale.ROOT);
        StringBuilder output = new StringBuilder(input.length());
        boolean pendingSpace = false;

        for (int i = 0; i < input.length(); i++) {
            char character = input.charAt(i);

            if (Character.isWhitespace(character)) {
                pendingSpace = output.length() > 0;
                continue;
            }

            if (pendingSpace) {
                output.append(' ');
                pendingSpace = false;
            }

            output.append(character);
        }

        return output.toString();
    }

    private static void hideFeedItem(View labelView) {
        if (!isCurrentAdMarker(labelView)) {
            return;
        }

        View current = labelView;
        View fallbackCandidate = null;

        int screenWidth =
                labelView.getResources()
                        .getDisplayMetrics().widthPixels;

        int screenHeight =
                labelView.getResources()
                        .getDisplayMetrics().heightPixels;

        int minimumHeight = Math.max(
                80,
                (int) (
                        80f
                                * labelView.getResources()
                                .getDisplayMetrics().density
                )
        );

        for (int depth = 0; depth < 14; depth++) {
            ViewParent parentObject = current.getParent();

            if (!(parentObject instanceof View)) {
                break;
            }

            View parent = (View) parentObject;

            /*
             * current is the direct child/card when its parent is a
             * RecyclerView or ListView.
             */
            if (isListContainer(parent)
                    && current != labelView) {
                collapseView(current, labelView);
                recordUiCollapse(
                        "feed item " + current.getClass().getName()
                );
                return;
            }

            if (depth >= 2
                    && parent.getWidth() >= screenWidth * 0.70f
                    && parent.getHeight() >= minimumHeight
                    && parent.getHeight() <= screenHeight * 0.80f) {
                fallbackCandidate = parent;
            }

            if (parent == labelView.getRootView()
                    || isDecorView(parent)) {
                break;
            }

            current = parent;
        }

        if (fallbackCandidate != null) {
            collapseView(fallbackCandidate, labelView);
            recordUiCollapse(
                    "fallback container "
                            + fallbackCandidate.getClass().getName()
            );
        } else {
            /*
             * Last resort: hide only the label, rather than risking
             * hiding the complete activity.
             */
            collapseView(labelView, labelView);
            recordUiCollapse("label-only fallback");
        }
    }

    private static boolean isListContainer(View view) {
        Class<?> type = view.getClass();
        Boolean cached = LIST_CONTAINER_CLASS_CACHE.get(type);
        if (cached != null) {
            return cached;
        }

        Class<?> cursor = type;
        boolean result = false;

        while (cursor != null) {
            String name = cursor.getName();

            if (name.contains("RecyclerView")
                    || name.contains("ListView")
                    || name.contains("AbsListView")) {
                result = true;
                break;
            }

            cursor = cursor.getSuperclass();
        }

        Boolean existing = LIST_CONTAINER_CLASS_CACHE.putIfAbsent(type, result);
        return existing == null ? result : existing;
    }

    private static boolean isDecorView(View view) {
        Class<?> cursor = view.getClass();

        while (cursor != null) {
            if (cursor.getName().contains("DecorView")) {
                return true;
            }

            cursor = cursor.getSuperclass();
        }

        return false;
    }

    private static void collapseView(View view, View markerView) {
        synchronized (HIDDEN_VIEWS) {
            if (!HIDDEN_VIEWS.containsKey(view)) {
                ViewGroup.LayoutParams params =
                        view.getLayoutParams();

                HIDDEN_VIEWS.put(
                        view,
                        new SavedViewState(
                                view.getVisibility(),
                                params == null
                                        ? Integer.MIN_VALUE
                                        : params.height,
                                markerView
                        )
                );
            }
        }

        ViewGroup.LayoutParams params =
                view.getLayoutParams();

        if (params != null && params.height != 0) {
            params.height = 0;
            view.setLayoutParams(params);
        }

        view.setVisibility(View.GONE);
        view.requestLayout();
    }

    /**
     * RecyclerView cards are reused. Restore a previously hidden card
     * when its label TextView is rebound to ordinary content.
     */
    private static void restoreMarkedAncestor(View child) {
        View current = child;

        for (int depth = 0; depth < 14; depth++) {
            SavedViewState savedState;

            synchronized (HIDDEN_VIEWS) {
                savedState = HIDDEN_VIEWS.get(current);
            }

            if (savedState != null) {
                View originalMarker = savedState.markerView.get();

                /*
                 * Do not restore a hidden ad card just because its caption,
                 * author, timestamp, or another descendant was rebound. Only
                 * the exact label view that hid the card may restore it. This
                 * fixes the intermittent single-ad reappearance seen in logs.
                 */
                if (originalMarker != child) {
                    return;
                }

                if (subtreeContainsAdLabel(current)) {
                    return;
                }

                synchronized (HIDDEN_VIEWS) {
                    HIDDEN_VIEWS.remove(current);
                }

                ViewGroup.LayoutParams params =
                        current.getLayoutParams();

                if (params != null
                        && savedState.originalHeight
                        != Integer.MIN_VALUE) {
                    params.height = savedState.originalHeight;
                    current.setLayoutParams(params);
                }

                current.setVisibility(
                        savedState.originalVisibility
                );

                current.requestLayout();
                log("Restored recycled feed item after sponsored marker was rebound");
                return;
            }

            ViewParent parent = current.getParent();

            if (!(parent instanceof View)) {
                return;
            }

            current = (View) parent;
        }
    }

    private static void recordModelRemoval(
            int count,
            String context
    ) {
        if (count <= 0) {
            return;
        }

        int total = MODEL_REMOVAL_TOTAL.addAndGet(count);
        int event = MODEL_REMOVAL_EVENTS.incrementAndGet();

        if (event <= 12 || event % 25 == 0) {
            log("Removed " + count + " ad item(s) from " + context
                    + " [modelTotal=" + total
                    + ", event=" + event + "]");
        }
    }

    private static void recordUiCollapse(String context) {
        int total = UI_COLLAPSE_TOTAL.incrementAndGet();

        if (total <= 8 || total % 25 == 0) {
            log("Collapsed sponsored " + context
                    + " [uiTotal=" + total + "]");
        }
    }

    private static void recordReplacementFailure() {
        int failures = REPLACEMENT_FAILURES.incrementAndGet();

        if (failures <= 3 || failures % 10 == 0) {
            log("Could not replace an immutable nested list"
                    + " [replacementFailures=" + failures + "]");
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private interface ValueReplacer {
        boolean replace(Object replacement);
    }

    private static final class SanitizedList {
        final Object value;
        final int removed;
        final boolean changed;

        SanitizedList(
                Object value,
                int removed,
                boolean changed
        ) {
            this.value = value;
            this.removed = removed;
            this.changed = changed;
        }

        static SanitizedList unchanged(Object value) {
            return new SanitizedList(value, 0, false);
        }
    }

    private static final class SavedViewState {
        final int originalVisibility;
        final int originalHeight;
        final WeakReference<View> markerView;

        SavedViewState(
                int originalVisibility,
                int originalHeight,
                View markerView
        ) {
            this.originalVisibility = originalVisibility;
            this.originalHeight = originalHeight;
            this.markerView = new WeakReference<>(markerView);
        }
    }
}
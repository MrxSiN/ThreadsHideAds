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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Threads-only LSPosed entry point.
 *
 * Uses two layers:
 * 1. Feed-model/list sanitization.
 * 2. Rendered "Sponsored" label fallback.
 */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "ThreadsHideAds";
    private static final String THREADS_PACKAGE = "com.instagram.barcelona";

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean UI_FALLBACK_INSTALLED = new AtomicBoolean(false);

    /*
     * Avoid overly broad strings such as just "ad".
     * Those match thousands of unrelated methods.
     */
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
            "MultiAds",
            "quick_promotion",
            "QuickPromotion",
            "paid_partnership"
    };

    private static final Set<String> STRONG_BOOLEAN_MARKERS =
            new LinkedHashSet<>(Arrays.asList(
                    "is_sponsored",
                    "isSponsored",
                    "direct_ad",
                    "directAd",
                    "sponsored_label"
            ));

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

            Map<Method, Set<String>> candidates =
                    discoverCandidates(bridge, lpparam.classLoader);

            int booleanHooks = 0;
            int listHooks = 0;
            int skipped = 0;

            for (Map.Entry<Method, Set<String>> entry : candidates.entrySet()) {
                Method method = entry.getKey();
                Set<String> markers = entry.getValue();

                try {
                    if (hasListBoundary(method)) {
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

            log("Discovery complete: candidates=" + candidates.size()
                    + ", listHooks=" + listHooks
                    + ", booleanHooks=" + booleanHooks
                    + ", skipped=" + skipped);

        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": Critical initialization failure");
            XposedBridge.log(throwable);
        }
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

                        log("Candidate: " + data.getDescriptor()
                                + " marker=" + marker);
                    } catch (Throwable throwable) {
                        log("Cannot resolve candidate "
                                + data.getDescriptor() + ": "
                                + throwable.getClass().getSimpleName());
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
                            parameterTypes[i]
                    );

                    if (!sanitized.changed) {
                        continue;
                    }

                    if (sanitized.value != argument) {
                        param.args[i] = sanitized.value;
                    }

                    log("Removed " + sanitized.removed
                            + " ad item(s) from argument " + i
                            + " of " + method);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) {
                    return;
                }

                Object result = param.getResult();

                if (!(result instanceof List<?>)) {
                    return;
                }

                SanitizedList sanitized = sanitizeList(
                        result,
                        method.getReturnType()
                );

                if (!sanitized.changed) {
                    return;
                }

                if (sanitized.value != result) {
                    param.setResult(sanitized.value);
                }

                log("Removed " + sanitized.removed
                        + " ad item(s) from result of " + method);
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
            Class<?> declaredType
    ) {
        if (!(value instanceof List<?>)) {
            return SanitizedList.unchanged(value);
        }

        List<?> source = (List<?>) value;
        ArrayList<Object> filtered =
                new ArrayList<>(source.size());

        int removed = 0;

        for (Object item : source) {
            if (looksLikeAd(item)) {
                removed++;
            } else {
                filtered.add(item);
            }
        }

        if (removed == 0) {
            return SanitizedList.unchanged(value);
        }

        /*
         * Prefer replacing the list with an ArrayList when compatible.
         */
        if (declaredType == Object.class
                || declaredType.isInterface()
                || declaredType.isAssignableFrom(ArrayList.class)) {
            return new SanitizedList(filtered, removed, true);
        }

        /*
         * Otherwise try to sanitize the original concrete collection.
         */
        try {
            @SuppressWarnings("unchecked")
            List<Object> mutable = (List<Object>) value;

            mutable.clear();
            mutable.addAll(filtered);

            return new SanitizedList(value, removed, true);
        } catch (Throwable ignored) {
            return SanitizedList.unchanged(value);
        }
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
        if (value == null || depth > 3 || budget[0]++ > 96) {
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

        Class<?> cursor = type;
        int classDepth = 0;

        while (cursor != null
                && cursor != Object.class
                && classDepth++ < 5) {

            Field[] fields;

            try {
                fields = cursor.getDeclaredFields();
            } catch (Throwable ignored) {
                break;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                Object fieldValue;

                try {
                    field.setAccessible(true);
                    fieldValue = field.get(value);
                } catch (Throwable ignored) {
                    continue;
                }

                if (isStrongAdFieldName(field.getName())
                        && isAdFieldValue(fieldValue)) {
                    return true;
                }

                if (depth < 3
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

            cursor = cursor.getSuperclass();
        }

        return false;
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
                || normalized.equals("isadvertisement");
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

        return normalized.equals("sponsored")
                || normalized.equals("promotion")
                || normalized.equals("advertisement")
                || normalized.equals("direct_ad")
                || normalized.equals("directad")
                || normalized.equals("multi_ads")
                || normalized.equals("multiads")
                || normalized.equals("quick_promotion")
                || normalized.equals("quickpromotion")
                || normalized.contains("\"is_sponsored\":true")
                || normalized.contains("\"is_sponsored\": true")
                || normalized.contains("\"sponsored_label\"")
                || normalized.contains("\"direct_ad\"");
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
                            restoreMarkedAncestor(textView);
                            return;
                        }

                        textView.post(new Runnable() {
                            @Override
                            public void run() {
                                hideFeedItem(textView);
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
                            return;
                        }

                        view.post(new Runnable() {
                            @Override
                            public void run() {
                                hideFeedItem(view);
                            }
                        });
                    }
                }
        );

        log("Installed rendered-label fallback");
    }

    private static boolean isAdLabel(CharSequence value) {
        if (value == null) {
            return false;
        }

        String normalized = value.toString()
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        return AD_LABELS.contains(normalized);
    }

    private static void hideFeedItem(View labelView) {
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
                collapseView(current);
                log("Collapsed sponsored feed item: "
                        + current.getClass().getName());
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
            collapseView(fallbackCandidate);
            log("Collapsed sponsored fallback container: "
                    + fallbackCandidate.getClass().getName());
        } else {
            /*
             * Last resort: hide only the label, rather than risking
             * hiding the complete activity.
             */
            collapseView(labelView);
            log("Only sponsored label could be hidden");
        }
    }

    private static boolean isListContainer(View view) {
        Class<?> cursor = view.getClass();

        while (cursor != null) {
            String name = cursor.getName();

            if (name.contains("RecyclerView")
                    || name.contains("ListView")
                    || name.contains("AbsListView")) {
                return true;
            }

            cursor = cursor.getSuperclass();
        }

        return false;
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

    private static void collapseView(View view) {
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
                                        : params.height
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
                savedState = HIDDEN_VIEWS.remove(current);
            }

            if (savedState != null) {
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
                return;
            }

            ViewParent parent = current.getParent();

            if (!(parent instanceof View)) {
                return;
            }

            current = (View) parent;
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
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

        SavedViewState(
                int originalVisibility,
                int originalHeight
        ) {
            this.originalVisibility = originalVisibility;
            this.originalHeight = originalHeight;
        }
    }
}
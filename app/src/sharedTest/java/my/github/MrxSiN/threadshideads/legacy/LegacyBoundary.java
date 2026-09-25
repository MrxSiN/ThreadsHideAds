package my.github.MrxSiN.threadshideads.legacy;

import java.util.ArrayList;
import java.util.List;

/**
 * FeedModelHooks' list-boundary callbacks of v1.2.1 with MethodHookParam
 * replaced by plain arrays. Removals are recorded as {site, index, count}
 * with the same site numbering as the Brainfuck ABI.
 */
public final class LegacyBoundary {

    public static final int SITE_ARG = 1;
    public static final int SITE_LATE_ARG = 2;
    public static final int SITE_RESULT = 3;
    public static final int SITE_NESTED = 4;
    public static final int SITE_REPLACEMENT_FAILURE = 5;

    public Object[] args;
    public Class<?>[] parameterTypes;
    public Class<?> returnType;
    public Object result;
    public final List<int[]> removals = new ArrayList<>();

    private final ListSanitizer listSanitizer = new ListSanitizer(new ReflectiveAdDetector());
    private final NestedCollectionSanitizer nestedSanitizer = new NestedCollectionSanitizer(listSanitizer);

    public void before() {
        for (int i = 0; i < args.length; i++) {
            Object argument = args[i];
            if (!(argument instanceof List<?>)) {
                continue;
            }
            SanitizedList sanitized = listSanitizer.sanitize(argument, parameterTypes[i], true, true);
            if (!sanitized.changed) {
                continue;
            }
            if (sanitized.value != argument) {
                args[i] = sanitized.value;
            }
            removals.add(new int[]{SITE_ARG, i, sanitized.removed});
        }
    }

    public void after(boolean threw) {
        if (threw) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            Object argument = args[i];
            if (!(argument instanceof List<?>)) {
                continue;
            }
            SanitizedList postCall = listSanitizer.sanitize(argument, argument.getClass(), false, true);
            if (postCall.changed) {
                removals.add(new int[]{SITE_LATE_ARG, i, postCall.removed});
            }
        }
        if (result instanceof List<?>) {
            SanitizedList sanitized = listSanitizer.sanitize(result, returnType, true, true);
            if (sanitized.changed) {
                if (sanitized.value != result) {
                    result = sanitized.value;
                }
                removals.add(new int[]{SITE_RESULT, 0, sanitized.removed});
            }
        }
        int failuresBefore = LegacyCounters.REPLACEMENT_FAILURES.get();
        int nestedRemoved = nestedSanitizer.sanitize(result);
        if (nestedRemoved > 0) {
            removals.add(new int[]{SITE_NESTED, 0, nestedRemoved});
        }
        int failures = LegacyCounters.REPLACEMENT_FAILURES.get() - failuresBefore;
        if (failures > 0) {
            removals.add(new int[]{SITE_REPLACEMENT_FAILURE, 0, failures});
        }
    }
}

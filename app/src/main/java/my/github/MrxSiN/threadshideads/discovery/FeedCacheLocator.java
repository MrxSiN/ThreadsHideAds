package my.github.MrxSiN.threadshideads.discovery;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.core.ModuleLog;

/**
 * Resolves the feed owner classes the Brainfuck core names and asks it which
 * of their methods to hook (OP_FEED_OWNER_METHOD, OP_INJECTOR_METHOD,
 * OP_INJECTOR_DECISION). Class names survive obfuscation here because Threads
 * reaches these classes reflectively.
 */
public final class FeedCacheLocator {

    private final Policy policy;
    private final ClassLoader classLoader;

    public FeedCacheLocator(Policy policy, ClassLoader classLoader) {
        this.policy = policy;
        this.classLoader = classLoader;
    }

    public List<Method> findListBoundaries() {
        List<Method> boundaries = new ArrayList<>();
        for (String className : policy.constTable(Policy.TABLE_FEED_OWNERS)) {
            Class<?> owner = resolve(className);
            if (owner == null) {
                continue;
            }
            int found = 0;
            for (Method method : declaredMethods(owner)) {
                if (policy.feedOwnerMethod(method)) {
                    boundaries.add(method);
                    found++;
                }
            }
            ModuleLog.log(owner.getSimpleName() + " exposes " + found + " list boundary/boundaries");
        }
        return boundaries;
    }

    /** The client-side ad injector, when exactly one method has its shape. */
    public Method findAdInjectionPredicate() {
        Class<?> handler = resolve(policy.constString(Policy.TABLE_NAMES, Policy.NAME_SPOOL_HANDLER));
        if (handler == null) {
            return null;
        }
        Method found = null;
        for (Method method : declaredMethods(handler)) {
            if (policy.injectorMethod(method) && found == null) {
                found = method;
            }
        }
        int decision = policy.injectorDecision();
        if (decision == Policy.DECISION_AMBIGUOUS) {
            ModuleLog.log("Ad injection predicate is ambiguous; skipping");
            return null;
        }
        if (decision != Policy.DECISION_INSTALL) {
            ModuleLog.log("No ad injection predicate found on the feed cache handler");
            return null;
        }
        return found;
    }

    private static Method[] declaredMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (Throwable throwable) {
            ModuleLog.log("Unable to read methods of " + type.getName() + ": " + throwable);
            return new Method[0];
        }
    }

    private Class<?> resolve(String className) {
        if (className == null) {
            return null;
        }
        try {
            return classLoader.loadClass(className);
        } catch (Throwable ignored) {
            // Feed ownership moves between builds; an absent class is expected.
            return null;
        }
    }
}

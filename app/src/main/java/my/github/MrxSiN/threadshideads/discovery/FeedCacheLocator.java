package my.github.MrxSiN.threadshideads.discovery;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import my.github.MrxSiN.threadshideads.core.ModuleLog;

/**
 * Resolves the Threads feed cache handler by name.
 *
 * Threads obfuscates method names but keeps this class name, because the feed
 * cache is reached reflectively. Locating the class by name and its methods by
 * shape therefore survives the renaming that breaks a pure string-marker
 * search.
 */
public final class FeedCacheLocator {

    private static final String SPOOL_CACHE_HANDLER =
            "com.instagram.barcelona.feed.data.cache.BarcelonaSpoolFeedCacheHandler";

    /*
     * Threads reaches the feed through several caches and repositories, and
     * which one is live depends on build gating. Cover them all: hooking a
     * class that is never called costs nothing, while missing the live one
     * leaves the feed unsanitized.
     */
    private static final String[] FEED_OWNER_CLASSES = {
            SPOOL_CACHE_HANDLER,
            "com.instagram.barcelona.feed.data.cache.BarcelonaFeedCacheHandler",
            "com.instagram.barcelona.feed.data.cache.BarcelonaFeedCacheHandlerBase",
            "com.instagram.barcelona.feed.data.cache.BarcelonaFeedCache",
            "com.instagram.barcelona.feed.data.cache.BarcelonaFeedCacheRoom",
            "com.instagram.barcelona.feed.common.data.InMemoryFeedCache",
            "com.instagram.barcelona.feed.common.data.BcnFeedRepository",
            "com.instagram.barcelona.feed.data.repository.BarcelonaFeedRepository",
            "com.instagram.barcelona.feed.data.repository.ForYouFeedRepository",
            "com.instagram.barcelona.feed.data.spool.ForYouFeedSpoolStateMapper",

            /*
             * A cold launch renders most of its first screen straight from the
             * fetch, without the items ever crossing the cache.
             */
            "com.instagram.barcelona.feed.data.networksource.BarcelonaFeedNetworkSource",
            "com.instagram.barcelona.feed.data.networksource.BarcelonaSpoolFeedNetworkSource",
            "com.instagram.barcelona.feed.data.networksource.FollowingFeedNetworkSource",
            "com.instagram.barcelona.feed.data.prefetch.BcnInjectedPostPrefetcher",
            "com.instagram.barcelona.feed.data.cache.BarcelonaFeedItemDatabase"
    };

    private final ClassLoader classLoader;

    public FeedCacheLocator(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Every declared method on the known feed owners that carries a
     * {@link List} in or out, so the feed can be sanitized wherever it crosses
     * one of them.
     */
    public List<Method> findListBoundaries() {
        List<Method> boundaries = new ArrayList<>();

        for (String className : FEED_OWNER_CLASSES) {
            Class<?> owner = resolve(className);

            if (owner == null) {
                continue;
            }

            int found = 0;

            for (Method method : declaredMethods(owner)) {
                if (Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }

                if (carriesList(method)) {
                    boundaries.add(method);
                    found++;
                }
            }

            ModuleLog.log(owner.getSimpleName() + " exposes " + found
                    + " list boundary/boundaries");
        }

        return boundaries;
    }

    /**
     * The client-side ad injector. Its Kotlin lambda names it
     * {@code injectAdToFeedSessionAtPosition}, and it is the only declared
     * method shaped {@code (feedItem, int) -> boolean}.
     */
    public Method findAdInjectionPredicate() {
        Class<?> handler = resolveSpoolCacheHandler();

        if (handler == null) {
            return null;
        }

        Method found = null;

        for (Method method : declaredMethods(handler)) {
            if (!isAdInjectionShape(method)) {
                continue;
            }

            if (found != null) {
                ModuleLog.log("Ad injection predicate is ambiguous; skipping");
                return null;
            }

            found = method;
        }

        if (found == null) {
            ModuleLog.log("No ad injection predicate found on the feed cache handler");
        }

        return found;
    }

    private static boolean isAdInjectionShape(Method method) {
        Class<?>[] parameters = method.getParameterTypes();

        return !Modifier.isAbstract(method.getModifiers())
                && method.getReturnType() == boolean.class
                && parameters.length == 2
                && !parameters[0].isPrimitive()
                && parameters[1] == int.class;
    }

    private static boolean carriesList(Method method) {
        if (List.class.isAssignableFrom(method.getReturnType())) {
            return true;
        }

        for (Class<?> parameterType : method.getParameterTypes()) {
            if (List.class.isAssignableFrom(parameterType)) {
                return true;
            }
        }

        return false;
    }

    private static Method[] declaredMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (Throwable throwable) {
            ModuleLog.log("Unable to read methods of " + type.getName()
                    + ": " + throwable);
            return new Method[0];
        }
    }

    private Class<?> resolveSpoolCacheHandler() {
        return resolve(SPOOL_CACHE_HANDLER);
    }

    private Class<?> resolve(String className) {
        try {
            return classLoader.loadClass(className);
        } catch (Throwable ignored) {
            /*
             * Feed ownership moves between builds, so an absent class is
             * expected rather than an error.
             */
            return null;
        }
    }
}

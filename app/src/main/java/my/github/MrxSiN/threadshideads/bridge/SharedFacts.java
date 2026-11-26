package my.github.MrxSiN.threadshideads.bridge;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches shared by every engine of a process: reflection results and the
 * per-class and per-tree memos the Brainfuck programs keep through
 * CAP_MEMO_* and CAP_TREE_VERDICT_*. All thread-safe; memo writes are
 * idempotent, so a race only repeats work.
 */
final class SharedFacts {

    final Reflector reflector = new Reflector();
    final Map<Class<?>, byte[]> classMemo = new ConcurrentHashMap<>();
    final Map<Class<?>, byte[]> fieldMemo = new ConcurrentHashMap<>();
    final Map<Object, Boolean> treeVerdicts = Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    final OrganicItems organicItems = new OrganicItems();
}

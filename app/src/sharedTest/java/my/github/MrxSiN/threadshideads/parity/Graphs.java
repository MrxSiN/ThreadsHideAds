package my.github.MrxSiN.threadshideads.parity;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import X.AdModel;
import X.Holder;
import X.MultiAdsFeedUnit;
import X.Node;
import X.TreeModel;
import X.UnitType;
import com.instagram.model.ImmutableList;
import com.instagram.model.Plain;
import com.instagram.model.Structured;

/**
 * Seeded random feed-model graphs. The same seed always builds an identical,
 * separate graph, so the legacy code and the Brainfuck core each get their
 * own copy to inspect and mutate.
 */
public final class Graphs {

    static final String[] STRINGS = {
            "", "hello", "AD", " ad4ad ", "Sponsored", "sponsored", "a caption about ads",
            "{\"is_sponsored\":true}", "{\"is_sponsored\":false}", "x ad_id=7", "promotion",
            "direct_ad", "quick_promotion", "multi ads", "0", "false", "none", "organic",
            "true", "INTENT_AWARE_AD_PIVOT", "thread", "{\"sponsored_label\":\"Ad\"}",
            "{\"is_paid_partnership\" : true}", "null", "unknown", "Ad", "paid partnership"
    };

    static final String[] KEYS = {
            "isSponsored", "is_sponsored", "ad_id", "adType", "caption", "id", "sponsoredLabel",
            "$directAd$", "commercial_content", "value", "adChoices"
    };

    private Graphs() {
    }

    static final class Builder {
        final Random random;
        final List<Object> made = new ArrayList<>();
        int budget;

        Builder(long seed, int budget) {
            this.random = new Random(seed);
            this.budget = budget;
        }

        Object value(int depth) {
            if (budget-- <= 0 || depth > 11) {
                return leaf();
            }
            int pick = random.nextInt(depth < 3 ? 16 : 22);
            Object out;
            switch (pick) {
                case 0:
                case 1:
                    out = node(depth);
                    break;
                case 2:
                    out = adModel(depth);
                    break;
                case 3:
                    out = list(depth);
                    break;
                case 4:
                    out = map(depth);
                    break;
                case 5:
                    out = array(depth);
                    break;
                case 6:
                    out = structured(depth);
                    break;
                case 7:
                    out = plain(depth);
                    break;
                case 8:
                    out = tree();
                    break;
                case 9: {
                    MultiAdsFeedUnit unit = new MultiAdsFeedUnit();
                    unit.inner = value(depth + 1);
                    out = unit;
                    break;
                }
                case 10:
                    out = holder(depth);
                    break;
                case 11:
                    // A cycle back to something already built.
                    out = made.isEmpty() ? leaf() : made.get(random.nextInt(made.size()));
                    break;
                case 12:
                    out = chain(depth);
                    break;
                default:
                    out = leaf();
            }
            made.add(out);
            return out;
        }

        Object leaf() {
            switch (random.nextInt(9)) {
                case 0:
                    return null;
                case 1:
                    return random.nextInt(3);
                case 2:
                    return random.nextBoolean();
                case 3:
                    return UnitType.values()[random.nextInt(UnitType.values().length)];
                case 4:
                    return 'c';
                case 5:
                    return String.class;
                case 6:
                    return (long) random.nextInt(2);
                default:
                    return STRINGS[random.nextInt(STRINGS.length)];
            }
        }

        Node node(int depth) {
            Node node = new Node();
            node.type = random.nextInt(3) == 0
                    ? UnitType.values()[random.nextInt(UnitType.values().length)] : null;
            node.caption = random.nextBoolean() ? STRINGS[random.nextInt(STRINGS.length)] : null;
            node.a = value(depth + 1);
            node.b = random.nextBoolean() ? value(depth + 1) : null;
            if (random.nextInt(4) == 0) {
                node.items = new Object[]{value(depth + 1), value(depth + 1)};
            }
            return node;
        }

        AdModel adModel(int depth) {
            AdModel model = new AdModel();
            model.isSponsored = random.nextInt(3) == 0 ? null : random.nextBoolean();
            model.adId = random.nextBoolean() ? leaf() : null;
            model.sponsored_label = random.nextBoolean() ? STRINGS[random.nextInt(STRINGS.length)] : null;
            model.commercialContent = random.nextInt(2);
            model.payload = value(depth + 1);
            return model;
        }

        Structured structured(int depth) {
            Structured s = new Structured(STRINGS[random.nextInt(STRINGS.length)]);
            s.child = random.nextBoolean() ? value(depth + 1) : null;
            return s;
        }

        Plain plain(int depth) {
            Plain p = new Plain();
            p.value = value(depth + 1);
            p.other = random.nextBoolean() ? value(depth + 1) : null;
            return p;
        }

        TreeModel tree() {
            // Flag pair 1 is always the longest, so the per-class flag choice
            // is the same whichever tree is seen first.
            String body;
            switch (random.nextInt(6)) {
                case 0:
                    body = "{\"is_sponsored\":true,\"x\":1}";
                    break;
                case 1:
                    body = "{\"ad_id\":\"123\",\"pad\":\"........\"}";
                    break;
                case 2:
                    body = oversized();
                    break;
                default:
                    body = "{\"fields\":{\"text\":\"organic post\"}}";
            }
            TreeModel tree = new TreeModel(new String[]{"{}", body, "{\"a\":1}", null});
            if (random.nextInt(3) == 0) {
                tree.rows = Collections.unmodifiableList(Arrays.asList(value(9), value(9)));
            }
            return tree;
        }

        String oversized() {
            StringBuilder s = new StringBuilder("{\"is_sponsored\":true,\"blob\":\"");
            while (s.length() < 70000) {
                s.append("0123456789abcdef");
            }
            return s.append("\"}").toString();
        }

        List<Object> list(int depth) {
            int size = random.nextInt(5);
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                items.add(value(depth + 1));
            }
            switch (random.nextInt(5)) {
                case 0:
                    return Collections.unmodifiableList(items);
                case 1:
                    return Arrays.asList(items.toArray());
                case 2:
                    return ImmutableList.copyOf(items);
                case 3:
                    return new LinkedList<>(items);
                default:
                    return items;
            }
        }

        Map<String, Object> map(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            int size = random.nextInt(4);
            for (int i = 0; i < size; i++) {
                map.put(KEYS[random.nextInt(KEYS.length)], value(depth + 1));
            }
            return random.nextInt(3) == 0 ? Collections.unmodifiableMap(map) : map;
        }

        Object array(int depth) {
            Object[] array = new Object[random.nextInt(4)];
            for (int i = 0; i < array.length; i++) {
                array[i] = value(depth + 1);
            }
            return array;
        }

        Holder holder(int depth) {
            Holder holder = new Holder(random.nextBoolean() ? list(depth + 1)
                    : Collections.unmodifiableList(new ArrayList<>(Arrays.asList(value(depth + 1)))));
            holder.list = random.nextBoolean() ? list(depth + 1) : null;
            holder.any = random.nextBoolean() ? list(depth + 1) : value(depth + 1);
            holder.map = random.nextInt(3) == 0 ? map(depth + 1) : null;
            holder.array = random.nextInt(3) == 0 ? new Object[]{list(depth + 1)} : null;
            holder.next = random.nextInt(4) == 0 ? holder(depth + 2) : null;
            if (random.nextInt(5) == 0) {
                Collection<Object> set = new LinkedHashSet<>();
                set.add(value(depth + 1));
                holder.any = set;
            }
            return holder;
        }

        /** A long chain, to exercise depth and budget limits. */
        Object chain(int depth) {
            Node head = new Node();
            Node cursor = head;
            int length = 3 + random.nextInt(12);
            for (int i = 0; i < length; i++) {
                Node next = new Node();
                cursor.a = next;
                cursor.b = random.nextInt(4) == 0 ? leaf() : null;
                cursor = next;
            }
            cursor.type = random.nextBoolean() ? UnitType.AD : UnitType.THREAD;
            return head;
        }
    }

    public static Object graph(long seed) {
        return new Builder(seed, 60).value(0);
    }

    /** A feed-like list: mostly items, some advertisements. */
    public static List<Object> feed(long seed) {
        Builder b = new Builder(seed, 80);
        int size = b.random.nextInt(6);
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            items.add(b.random.nextInt(3) == 0 ? b.adModel(1) : b.value(1));
        }
        switch (b.random.nextInt(5)) {
            case 0:
                return Collections.unmodifiableList(items);
            case 1:
                return Arrays.asList(items.toArray());
            case 2:
                return ImmutableList.copyOf(items);
            default:
                return items;
        }
    }

    // ------------------------------------------------------------ canonical dump

    /** A canonical description of a graph, with shared objects numbered. */
    public static String dump(Object root) {
        StringBuilder out = new StringBuilder();
        dump(root, out, new IdentityHashMap<Object, Integer>(), 0);
        return out.toString();
    }

    private static void dump(Object value, StringBuilder out, IdentityHashMap<Object, Integer> seen, int depth) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?> || value instanceof Class<?>) {
            out.append(value.getClass().getSimpleName()).append(':').append(value);
            return;
        }
        Integer id = seen.get(value);
        if (id != null) {
            out.append('#').append(id);
            return;
        }
        seen.put(value, seen.size());
        if (depth > 40) {
            out.append("...");
            return;
        }
        out.append(value.getClass().getName()).append('@').append(seen.get(value));
        if (value instanceof Map<?, ?>) {
            out.append('{');
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                out.append(e.getKey()).append('=');
                dump(e.getValue(), out, seen, depth + 1);
                out.append(',');
            }
            out.append('}');
        } else if (value instanceof Collection<?>) {
            out.append('[');
            for (Object item : (Collection<?>) value) {
                dump(item, out, seen, depth + 1);
                out.append(',');
            }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            for (int i = 0; i < Array.getLength(value); i++) {
                dump(Array.get(value, i), out, seen, depth + 1);
                out.append(',');
            }
            out.append(']');
        } else {
            out.append('(');
            List<Field> fields = new ArrayList<>();
            for (Class<?> c = value.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                        fields.add(f);
                    }
                }
            }
            for (Field f : fields) {
                try {
                    f.setAccessible(true);
                    out.append(f.getName()).append('=');
                    dump(f.get(value), out, seen, depth + 1);
                    out.append(',');
                } catch (Throwable ignored) {
                    out.append("?,");
                }
            }
            out.append(')');
        }
    }
}

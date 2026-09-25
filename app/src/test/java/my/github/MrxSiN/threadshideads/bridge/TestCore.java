package my.github.MrxSiN.threadshideads.bridge;

import java.util.List;

/**
 * Loads the host build of libthreadsbf (Gradle task buildHostCore) and hands
 * out a Policy over it. One core per JVM, reset before each use.
 */
public final class TestCore {

    private static Policy policy;

    private TestCore() {
    }

    public static synchronized Policy policy() {
        if (policy == null) {
            String path = System.getProperty("threadsbf.hostlib");
            if (path == null) {
                throw new IllegalStateException("threadsbf.hostlib is not set; run through Gradle");
            }
            System.load(path);
            policy = new Policy(NativeCore.create(new FakeViews()));
            policy.configureHost();
        }
        policy.reset();
        return policy;
    }

    /** A view for tests: text (null unless a TextView), description, children. */
    public static final class FakeView {
        public final boolean textView;
        public final CharSequence text;
        public final CharSequence description;
        public final List<FakeView> children;

        public FakeView(boolean textView, CharSequence text, CharSequence description,
                        List<FakeView> children) {
            this.textView = textView;
            this.text = text;
            this.description = description;
            this.children = children;
        }
    }

    static final class FakeViews implements ViewAccess {
        @Override
        public boolean isTextView(Object view) {
            return ((FakeView) view).textView;
        }

        @Override
        public CharSequence text(Object view) {
            return ((FakeView) view).text;
        }

        @Override
        public CharSequence contentDescription(Object view) {
            return ((FakeView) view).description;
        }

        @Override
        public int childCount(Object view) {
            List<FakeView> children = ((FakeView) view).children;
            return children == null ? -1 : children.size();
        }

        @Override
        public Object childAt(Object view, int index) {
            return ((FakeView) view).children.get(index);
        }
    }
}

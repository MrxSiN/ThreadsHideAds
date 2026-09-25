package X;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Method shapes discovery has to tell apart (FeedBoundaryFinder, FeedCacheLocator). */
public abstract class Shapes implements ShapesApi {
    public Node DuY(Integer a, Integer b, List<Object> c) { return null; }
    public Object Dqi(int a, int b, List<Object> c) { return null; }
    public Object wide(int a, int b, ArrayList<Object> c) { return null; }
    public void voidShape(Integer a, Integer b, List<Object> c) { }
    public static Object staticShape(Integer a, Integer b, List<Object> c) { return null; }
    public abstract Object abstractShape(Integer a, Integer b, List<Object> c);
    public Object longShape(Integer a, Long b, List<Object> c) { return null; }
    public Object twoParams(Integer a, List<Object> c) { return null; }
    public Object collectionShape(Integer a, Integer b, Collection<Object> c) { return null; }

    public boolean isSponsored() { return false; }
    public Boolean adsEnabled() { return null; }
    public boolean hideSponsored() { return false; }
    public boolean shouldShowAd() { return false; }
    public boolean isAd() { return false; }
    public boolean removeAdEligible() { return false; }
    public boolean canInsertAd() { return false; }

    public List<Object> feed(List<Object> in) { return in; }
    public Collection<Object> items() { return null; }
    public String nothing() { return null; }
    public abstract List<Object> abstractFeed();
    public native List<Object> nativeFeed();

    public boolean inject(Node item, int position) { return false; }
    public boolean injectPrimitive(int item, int position) { return false; }
    public Boolean injectBoxed(Node item, int position) { return null; }
    public boolean injectInteger(Node item, Integer position) { return false; }
}

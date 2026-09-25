package X;

import java.util.List;
import java.util.Map;

/** A feed wrapper returned by a boundary: typed and untyped collection slots. */
public class Holder {
    public List<Object> list;
    public final List<Object> finalList;
    public Object any;
    public Map<String, Object> map;
    public Object[] array;
    public Holder next;

    public Holder(List<Object> finalList) {
        this.finalList = finalList;
    }
}

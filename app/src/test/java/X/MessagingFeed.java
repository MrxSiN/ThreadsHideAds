package X;

import java.util.List;

/** A list boundary whose signature names a non-feed area. */
public class MessagingFeed {
    public List<Object> load(List<Object> items) { return items; }
    public boolean isSponsored() { return false; }
}

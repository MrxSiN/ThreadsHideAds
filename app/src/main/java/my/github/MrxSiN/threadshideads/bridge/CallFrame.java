package my.github.MrxSiN.threadshideads.bridge;

/**
 * The hooked call a feed-boundary request works on: its arguments, declared
 * types and result. The Xposed hook adapts MethodHookParam to this.
 */
public interface CallFrame {

    int argumentCount();

    Object argument(int index);

    void setArgument(int index, Object value);

    Class<?> parameterType(int index);

    Class<?> returnType();

    Object result();

    void setResult(Object value);
}

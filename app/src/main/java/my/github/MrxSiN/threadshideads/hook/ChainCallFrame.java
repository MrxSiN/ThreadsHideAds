package my.github.MrxSiN.threadshideads.hook;

import my.github.MrxSiN.threadshideads.bridge.CallFrame;

/**
 * A hooked call as the Brainfuck boundary requests see it: a mutable copy of
 * the chain's arguments (proceeded with once the core has edited them) and
 * the result once the call has returned.
 */
final class ChainCallFrame implements CallFrame {

    private final Object[] args;
    private final Class<?>[] parameterTypes;
    private final Class<?> returnType;
    private Object result;

    ChainCallFrame(Object[] args, Class<?>[] parameterTypes, Class<?> returnType) {
        this.args = args;
        this.parameterTypes = parameterTypes;
        this.returnType = returnType;
    }

    @Override
    public int argumentCount() {
        return args.length;
    }

    @Override
    public Object argument(int index) {
        return args[index];
    }

    @Override
    public void setArgument(int index, Object value) {
        args[index] = value;
    }

    @Override
    public Class<?> parameterType(int index) {
        return index < parameterTypes.length ? parameterTypes[index] : Object.class;
    }

    @Override
    public Class<?> returnType() {
        return returnType;
    }

    @Override
    public Object result() {
        return result;
    }

    @Override
    public void setResult(Object value) {
        result = value;
    }
}

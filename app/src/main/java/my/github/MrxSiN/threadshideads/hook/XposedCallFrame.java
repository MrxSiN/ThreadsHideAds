package my.github.MrxSiN.threadshideads.hook;

import de.robv.android.xposed.XC_MethodHook;

import my.github.MrxSiN.threadshideads.bridge.CallFrame;

/** A hooked call as the Brainfuck boundary requests see it. */
final class XposedCallFrame implements CallFrame {

    private final XC_MethodHook.MethodHookParam param;
    private final Class<?>[] parameterTypes;
    private final Class<?> returnType;

    XposedCallFrame(XC_MethodHook.MethodHookParam param, Class<?>[] parameterTypes, Class<?> returnType) {
        this.param = param;
        this.parameterTypes = parameterTypes;
        this.returnType = returnType;
    }

    @Override
    public int argumentCount() {
        return param.args == null ? 0 : param.args.length;
    }

    @Override
    public Object argument(int index) {
        return param.args[index];
    }

    @Override
    public void setArgument(int index, Object value) {
        param.args[index] = value;
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
        return param.getResult();
    }

    @Override
    public void setResult(Object value) {
        param.setResult(value);
    }
}

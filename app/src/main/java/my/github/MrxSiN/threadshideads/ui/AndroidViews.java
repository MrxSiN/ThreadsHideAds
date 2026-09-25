package my.github.MrxSiN.threadshideads.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import my.github.MrxSiN.threadshideads.bridge.ViewAccess;

/** View reads for the Brainfuck text and scan capabilities. */
public final class AndroidViews implements ViewAccess {

    @Override
    public boolean isTextView(Object view) {
        return view instanceof TextView;
    }

    @Override
    public CharSequence text(Object view) {
        return view instanceof TextView ? ((TextView) view).getText() : null;
    }

    @Override
    public CharSequence contentDescription(Object view) {
        return ((View) view).getContentDescription();
    }

    @Override
    public int childCount(Object view) {
        return view instanceof ViewGroup ? ((ViewGroup) view).getChildCount() : -1;
    }

    @Override
    public Object childAt(Object view, int index) {
        return ((ViewGroup) view).getChildAt(index);
    }
}

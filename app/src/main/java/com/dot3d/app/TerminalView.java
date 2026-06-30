package com.dot3d.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.util.ArrayList;
import java.util.List;

/**
 * A functional terminal drawn manually (no TextView), so long-press never
 * selects or copies characters. Maintains scrollback + a current input line,
 * and dispatches commands to a Terminal command processor.
 */
public final class TerminalView extends View {
    private final List<String> lines = new ArrayList<>();
    private final StringBuilder input = new StringBuilder();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float cellH = 36f;
    private boolean cursorOn = true;

    public interface CommandSink { void onCommand(String line); }
    private CommandSink sink;

    private final Handler handler = new Handler(Looper.getMainLooper());

    public TerminalView(Context ctx) {
        super(ctx);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(cellH);
        paint.setColor(0xFF33FF66);
        setFocusable(true);
        setFocusableInTouchMode(true);
        println("DotEngine terminal. Type 'help' for commands, 'run' to start the engine.");
        // blink cursor
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                cursorOn = !cursorOn;
                invalidate();
                handler.postDelayed(this, 500);
            }
        }, 500);
    }

    public void setSink(CommandSink s) { this.sink = s; }

    public void println(String s) {
        for (String part : s.split("\n", -1)) lines.add(part);
        if (lines.size() > 500) lines.subList(0, lines.size() - 500).clear();
        invalidate();
    }

    public void clear() { lines.clear(); invalidate(); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            performClick();
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, false) {
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                for (int i = 0; i < text.length(); i++) {
                    char ch = text.charAt(i);
                    if (ch == '\n') submit();
                    else input.append(ch);
                }
                invalidate();
                return true;
            }
            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < beforeLength && input.length() > 0; i++)
                    input.deleteCharAt(input.length() - 1);
                invalidate();
                return true;
            }
            @Override public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int code = event.getKeyCode();
                    if (code == KeyEvent.KEYCODE_DEL) {
                        if (input.length() > 0) input.deleteCharAt(input.length() - 1);
                        invalidate(); return true;
                    } else if (code == KeyEvent.KEYCODE_ENTER) {
                        submit(); return true;
                    }
                }
                return super.sendKeyEvent(event);
            }
        };
    }

    private void submit() {
        String cmd = input.toString();
        input.setLength(0);
        println("> " + cmd);
        if (sink != null) sink.onCommand(cmd.trim());
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        int rows = (int) (getHeight() / cellH);
        int total = lines.size() + 1; // + input line
        int start = Math.max(0, total - rows);
        float y = cellH;
        for (int i = start; i < lines.size(); i++) {
            c.drawText(lines.get(i), 8, y, paint);
            y += cellH;
        }
        String prompt = "$ " + input + (cursorOn ? "_" : "");
        c.drawText(prompt, 8, y, paint);
    }
}
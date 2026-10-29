package com.fastshare.app;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.List;

/** Asks how to rename several files, shows what each would become, then does it. */
final class BatchRenameDialog {
    private BatchRenameDialog() { }

    interface Done {
        /** {newFile, oldFile} pairs, so the caller can offer Undo. */
        void renamed(List<File[]> undoPairs);
    }

    static void show(final Context ctx, final List<File> files, final Done done) {
        final View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_batch_rename, null);
        final Spinner mode = v.findViewById(R.id.brMode);
        mode.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Number them", "Find and replace", "Add before / after", "Change capitals", "Use the date"}));
        final View[] panels = {v.findViewById(R.id.brNumbering), v.findViewById(R.id.brFind), v.findViewById(R.id.brPrefix),
                v.findViewById(R.id.brCase), v.findViewById(R.id.brDate)};
        final TextView preview = v.findViewById(R.id.brPreview);

        final AlertDialog dlg = new MaterialAlertDialogBuilder(ctx)
                .setTitle("Rename " + Fmt.plural(files.size(), "file", "files"))
                .setView(v)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rename", null)
                .create();

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            BatchRename.Options o = options(v, mode.getSelectedItemPosition());
            List<BatchRename.Preview> plan = BatchRename.plan(files, o);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int changes = 0;
            boolean problem = false;
            int shown = 0;
            int bad = ContextCompat.getColor(ctx, R.color.fs_err);
            for (BatchRename.Preview p : plan) {
                if (p.error != null) problem = true;
                if (p.changes()) changes++;
                if (shown < 6 && (p.changes() || p.error != null)) {
                    if (sb.length() > 0) sb.append('\n');
                    int start = sb.length();
                    sb.append(p.file.getName()).append("  →  ").append(p.error == null ? p.newName : p.error);
                    if (p.error != null) sb.setSpan(new ForegroundColorSpan(bad), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    shown++;
                }
            }
            int more = 0;
            for (BatchRename.Preview p : plan) if (p.changes() || p.error != null) more++;
            if (more > shown) sb.append("\n… and ").append(String.valueOf(more - shown)).append(" more");
            if (sb.length() == 0) sb.append("Nothing would change yet.");
            preview.setText(sb);
            if (dlg.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(changes > 0 && !problem);
            }
        };

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refresh[0].run(); }
        };
        for (int id : new int[]{R.id.brBase, R.id.brStart, R.id.brDigits, R.id.brFindText, R.id.brReplaceText,
                R.id.brPrefixText, R.id.brSuffixText, R.id.brDatePattern}) {
            ((EditText) v.findViewById(id)).addTextChangedListener(watcher);
        }
        View.OnClickListener click = x -> refresh[0].run();
        v.findViewById(R.id.brMatchCase).setOnClickListener(click);
        v.findViewById(R.id.brKeepExt).setOnClickListener(click);
        ((RadioGroup) v.findViewById(R.id.brCase)).setOnCheckedChangeListener((g, id) -> refresh[0].run());
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) {
                for (int i = 0; i < panels.length; i++) panels[i].setVisibility(i == pos ? View.VISIBLE : View.GONE);
                refresh[0].run();
            }

            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

        dlg.setOnShowListener(d -> {
            refresh[0].run();
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
                try {
                    List<File[]> undo = BatchRename.apply(BatchRename.plan(files, options(v, mode.getSelectedItemPosition())));
                    dlg.dismiss();
                    done.renamed(undo);
                } catch (Exception e) {
                    preview.setText(e.getMessage());
                    preview.setTextColor(ContextCompat.getColor(ctx, R.color.fs_err));
                }
            });
        });
        dlg.show();
    }

    private static int intOf(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException x) {
            return fallback;
        }
    }

    private static String text(View v, int id) {
        return ((EditText) v.findViewById(id)).getText().toString();
    }

    private static BatchRename.Options options(View v, int modeIndex) {
        BatchRename.Options o = new BatchRename.Options();
        o.mode = BatchRename.Mode.values()[modeIndex];
        o.keepExtension = ((CheckBox) v.findViewById(R.id.brKeepExt)).isChecked();
        o.base = text(v, R.id.brBase);
        o.start = intOf((EditText) v.findViewById(R.id.brStart), 1);
        o.digits = Math.min(9, Math.max(1, intOf((EditText) v.findViewById(R.id.brDigits), 2)));
        o.find = text(v, R.id.brFindText);
        o.replace = text(v, R.id.brReplaceText);
        o.matchCase = ((CheckBox) v.findViewById(R.id.brMatchCase)).isChecked();
        o.prefix = text(v, R.id.brPrefixText);
        o.suffix = text(v, R.id.brSuffixText);
        o.datePattern = text(v, R.id.brDatePattern);
        int checked = ((RadioGroup) v.findViewById(R.id.brCase)).getCheckedRadioButtonId();
        o.caseMode = checked == R.id.brCaseUpper ? BatchRename.CaseMode.UPPER
                : checked == R.id.brCaseTitle ? BatchRename.CaseMode.TITLE
                : checked == R.id.brCaseSentence ? BatchRename.CaseMode.SENTENCE : BatchRename.CaseMode.LOWER;
        return o;
    }
}

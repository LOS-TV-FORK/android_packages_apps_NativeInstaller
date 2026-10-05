package org.los.tv.installer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Options of one group (subgroups flattened with headers).
 * distinct groups behave as radio (one token per group scope).
 */
public class OptionsListActivity extends Activity {
    private static final class Row {
        Options.Group scope;
        Options.Opt opt;
        CheckBox box;
        EditText edit;
        String key;
    }

    private final List<Row> mRows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_list);
        int idx = getIntent().getIntExtra("group", 0);
        Options.Group group = Options.all().get(idx);

        ((TextView) findViewById(R.id.title)).setText(group.name);
        findViewById(R.id.primary).setVisibility(android.view.View.GONE);

        LinearLayout list = findViewById(R.id.list);
        addGroup(list, group, group.name);
        if (!mRows.isEmpty()) {
            mRows.get(0).box.requestFocus();
        }
    }

    private void addGroup(
            LinearLayout list, Options.Group group, String scope) {
        for (Options.Group sub : group.subs) {
            TextView h = new TextView(this);
            h.setText(sub.name);
            h.setTextSize(20);
            h.setFocusable(false);
            list.addView(h);
            addGroup(list, sub, scope + "/" + sub.name);
        }
        for (Options.Opt opt : group.opts) {
            final String key = scope + "/" + opt.name;
            final Options.Group owner = group;
            CheckBox box = new CheckBox(this);
            box.setText(opt.name);
            box.setTextSize(18);
            box.setFocusable(true);
            box.setChecked(OptionsStore.has(key));
            list.addView(box, Ui.rowParams(this));

            final EditText edit;
            if (opt.editable) {
                edit = new EditText(this);
                edit.setHint(opt.token + "…");
                edit.setFocusable(true);
                String cur = OptionsStore.get(key);
                if (cur != null && cur.startsWith(opt.token)) {
                    edit.setText(cur.substring(opt.token.length()));
                } else {
                    edit.setText(opt.def);
                }
                list.addView(edit, Ui.rowParams(this));
            } else {
                edit = null;
            }
            final Row row = new Row();
            row.scope = owner;
            row.opt = opt;
            row.box = box;
            row.edit = edit;
            row.key = key;
            mRows.add(row);
            box.setOnClickListener(v -> apply(row));
        }
    }

    private void apply(Row row) {
        if (row.box.isChecked() && row.scope.distinct) {
            for (Row r : mRows) {
                if (r != row && r.scope == row.scope && r.box.isChecked()) {
                    r.box.setChecked(false);
                    OptionsStore.set(r.key, null);
                }
            }
        }
        if (row.opt.editable) {
            String v = row.edit.getText().toString();
            if (row.box.isChecked()) {
                OptionsStore.set(row.key, row.opt.token + v);
            } else {
                OptionsStore.set(row.key, null);
            }
            return;
        }
        if (row.box.isChecked()) {
            OptionsStore.set(row.key, row.opt.token);
        } else {
            OptionsStore.set(row.key, null);
        }
    }
}

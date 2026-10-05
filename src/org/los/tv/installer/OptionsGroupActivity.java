package org.los.tv.installer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Option groups list (TV drill-down). */
public class OptionsGroupActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_list);

        ((TextView) findViewById(R.id.title))
                .setText(R.string.options_title);
        findViewById(R.id.primary).setVisibility(android.view.View.GONE);

        LinearLayout list = findViewById(R.id.list);
        List<Options.Group> groups = Options.all();
        boolean first = true;
        for (int i = 0; i < groups.size(); i++) {
            final int idx = i;
            Button b = MainActivity.rowButton(this, groups.get(i).name);
            b.setOnClickListener(
                    v -> {
                        Intent it = new Intent(this, OptionsListActivity.class);
                        it.putExtra("group", idx);
                        startActivity(it);
                    });
            list.addView(b, Ui.rowParams(this));
            if (first) {
                b.requestFocus();
                first = false;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh is unnecessary; counts update on next entry.
    }
}

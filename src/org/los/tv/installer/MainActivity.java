package org.los.tv.installer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Welcome screen, SetupWizard TV look. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.welcome);

        ((TextView) findViewById(R.id.title))
                .setText(R.string.welcome_title);
        ((TextView) findViewById(R.id.subtitle))
                .setText(R.string.welcome_text);

        Button next = findViewById(R.id.primary);
        next.setText(R.string.continue_btn);
        next.setOnClickListener(
                v -> startActivity(new Intent(this, DiskActivity.class)));

        Button live = findViewById(R.id.secondary);
        live.setText(R.string.welcome_live);
        live.setOnClickListener(v -> finish());
        Ui.focusFx(next);
        Ui.focusFx(live);

        next.requestFocus();
    }

    /** Wizard-styled row button (D-pad focus + click). */
    static Button rowButton(Activity ctx, CharSequence text) {
        Button b =
                new Button(
                        ctx, null, 0, R.style.SudGlifButton_Secondary);
        b.setText(text);
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        Ui.focusFx(b);
        return b;
    }
}

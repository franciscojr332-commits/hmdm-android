/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

import com.hmdm.launcher.Const;

/**
 * Shows a full-screen blocking dialog (banner style) with a message and OK button.
 * Used for push notifications of type "notification" so the user must acknowledge.
 */
public class NotificationDialogActivity extends AppCompatActivity {

    public static final String EXTRA_MESSAGE = "message";

    private Dialog dialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String message = null;
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra(EXTRA_MESSAGE)) {
            message = intent.getStringExtra(EXTRA_MESSAGE);
        }
        if (message == null || message.trim().isEmpty()) {
            message = "MDM";
        }

        final String text = message.trim();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(text);
        builder.setPositiveButton(android.R.string.ok, (d, which) -> finish());
        builder.setCancelable(false);
        dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> finish());
        dialog.show();
    }

    @Override
    protected void onDestroy() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        super.onDestroy();
    }
}

package com.kurmez.iyesi.utilities;

import android.content.Context;
import android.widget.Toast;

public class Helpers {
    void showToast(String message, Context context) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}

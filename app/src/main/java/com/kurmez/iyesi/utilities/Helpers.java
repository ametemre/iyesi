package com.kurmez.iyesi.utilities;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.util.Consumer;
import androidx.core.util.Function;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.utilities.Ai.Ai;
import com.kurmez.iyesi.utilities.Ai.Detection;

import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.OnFailureListener;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Helpers {
    void showToast(String message, Context context) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
    public static void showToastSafe(Context ctx, String msg) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }
    public static Task<String> getRoleFunction() {
        TaskCompletionSource<String> taskSource = new TaskCompletionSource<>();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (user == null) {
            taskSource.setException(new Exception("Kullanıcı oturum açmamış!"));
            return taskSource.getTask();
        }

        user.getIdToken(true).addOnSuccessListener(getTokenResult -> {
            String idToken = getTokenResult.getToken();
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url("https://us-central1-iyesi-a651a.cloudfunctions.net/getRole")
                    .addHeader("Authorization", "Bearer " + idToken)
                    .post(RequestBody.create("{\"data\":{}}", MediaType.parse("application/json")))
                    .build();
            Log.d("HTTP_ROLE", "idToken: " + idToken);
            Log.d("HTTP_ROLE", "Request: " + request.toString());

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    taskSource.setException(e);
                }
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body() != null ? response.body().string() : ""; // sadece bir kez oku!
                    Log.d("HTTP_ROLE", "Response code: " + response.code());

                    if (!response.isSuccessful()) {
                        taskSource.setException(new IOException("Beklenmeyen HTTP kodu: " + response + " Body: " + responseBody));
                    } else {
                        // Eğer response JSON ise, parse edip sadece rolü dönebilirsin
                        // Ör: JSONObject json = new JSONObject(result); String role = json.getString("role");
                        try {
                            JSONObject json = new JSONObject(responseBody);
                            String role = json.optString("role", null); // eğer JSON { "role": ... } şeklindeyse
                            taskSource.setResult(role);

                        } catch (JSONException e) {
                            taskSource.setException(e);
                        }
                    }
                }
            });
        }).addOnFailureListener(taskSource::setException);

        return taskSource.getTask();
    }

    public void testGetRoleFunction() {
        FirebaseFunctions functions = FirebaseFunctions.getInstance();

        // Parametre gerekmediği için boş bir map gönderebilirsin
        functions
                .getHttpsCallable("getRole")
                .call()
                .addOnSuccessListener(new OnSuccessListener<HttpsCallableResult>() {
                    @Override
                    public void onSuccess(HttpsCallableResult result) {
                        if (result != null && result.getData() != null) {
                            Map<String, Object> data = (Map<String, Object>) result.getData();
                            String role = data.get("role") != null ? data.get("role").toString() : "null";
                            Log.d("FirebaseTest", "Kullanıcı rolü: " + role);
                        } else {
                            Log.d("FirebaseTest", "Yanıt boş veya hatalı");
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        Log.e("FirebaseTest", "getRole hatası: " + e.getMessage(), e);
                    }
                });
    }
}




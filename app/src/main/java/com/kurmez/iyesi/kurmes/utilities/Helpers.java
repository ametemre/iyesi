package com.kurmez.iyesi.kurmes.utilities;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.social.Profile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Parses a JSON response body (with a top‐level "users" array) into a list of Profile objects.
     *
     * @param jsonBody raw JSON string, e.g.
     *   { "success": true,
     *     "users": [
     *       { "uid":"…", "displayName":"…", "email":"…", "location":"…", "phone":"…", "role":"…"},
     *       …
     *     ]
     *   }
     * @return list of Profile
     * @throws JSONException if the JSON is malformed
     */
    public static List<Profile> parseProfiles(String jsonBody) throws JSONException {
        JSONObject root = new JSONObject(jsonBody);
        JSONArray users = root.optJSONArray("users");
        List<Profile> list = new ArrayList<>();
        if (users == null) return list;

        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.getJSONObject(i);
            String uid         = u.optString("uid", "");
            String email       = u.optString("email", "");
            String dispName    = u.optString("displayName", "").trim();
            // displayName yoksa email kullan
            String username    = !dispName.isEmpty() ? dispName : email;
            String location    = u.optString("location", "");
            String phone       = u.optString("phone", "");
            String role        = u.optString("role", "");
            String avatar_url  = u.optString("avatar","");

            list.add(new Profile(uid, username, email, location, phone, role,avatar_url));
        }
        return list;
    }
    public class ConversationHeaderHelper {
        public static void setupHeader(AppCompatActivity activity, int menuResId, PopupMenu.OnMenuItemClickListener listener) {
            ImageButton btnMore = activity.findViewById(R.id.btnMore);
            if (btnMore == null) return;

            btnMore.setOnClickListener(view -> {
                PopupMenu popup = new PopupMenu(activity, view);
                popup.getMenuInflater().inflate(menuResId, popup.getMenu());
                popup.setOnMenuItemClickListener(listener);
                popup.show();
            });
        }
    }
}




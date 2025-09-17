// HeaderHelper.java
package com.kurmez.iyesi.kurmes.utilities.helper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.widget.Toast;

import androidx.annotation.MenuRes;
import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.PopupMenu;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.R;

import java.util.Map;

public class HeaderHelper {

    private final Context context;
    private final Activity activity;

    public HeaderHelper(Activity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
    }

    // 👤 Header'ı listView'e ekle
    public void attachHeaderToListView(ListView listView, String username, String avatarUrl) {
        View headerView = LayoutInflater.from(context).inflate(R.layout.item_conversation_header, listView, false);
        TextView headerText = headerView.findViewById(R.id.tvUsername);
        ImageView headerImage = headerView.findViewById(R.id.ivProfile);

        headerText.setText(username);
        Glide.with(context).load(avatarUrl).placeholder(R.drawable.holder).into(headerImage);

        listView.addHeaderView(headerView);
    }
    public void refreshHeader(Context context){
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        Log.i("User",user.toString());
        if (user == null || Build.VERSION_CODES.N >= Build.VERSION.SDK_INT || user.isAnonymous()) return;
        user.getIdToken(true).addOnSuccessListener(result -> {
            Log.i("Result",result.toString());
            Map<String, Object> claims = result.getClaims();
            String role = (String) claims.get("role");
            String username = (String) claims.get("username");
            String avatarUrl = (String) claims.get("avatarUrl");/*
            String username = (String) claims.getOrDefault("username", user.getEmail());
            String avatarUrl = (String) claims.getOrDefault("avatarUrl", "");
*/
            // RecyclerView olduğu için geçici bir ListView header yerleşimi yap
            ListView dummyListView = new ListView(context);
            attachHeaderToListView(dummyListView, username, avatarUrl);

            // Alternatif olarak `attachHeaderToToolbar` gibi başka bir metod yazabilirsin
        });
    }


/*
    // 🧠 Tüm menü eylemleri burada tanımlanabilir
    public boolean handleMenuAction(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.EvlatEdin:
                Toast.makeText(context, "Evlat edinme işlemi", Toast.LENGTH_SHORT).show();
                return true;
            case R.id.Duzenle:
                // yeni aktiviteye geçiş örneği
                //activity.startActivity(new Intent(activity, EditSoulActivity.class));
                return true;
            case R.id.Kaydet:
                Toast.makeText(context, "Kaydedildi", Toast.LENGTH_SHORT).show();
                return true;
            case R.id.Kritik:
                Toast.makeText(context, "Kritik Sağlık Modu Aktif", Toast.LENGTH_SHORT).show();
                return true;
            default:
                return false;
        }
    }*/
}
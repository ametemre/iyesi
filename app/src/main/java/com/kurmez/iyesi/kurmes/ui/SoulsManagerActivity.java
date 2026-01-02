package com.kurmez.iyesi.kurmes.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.annotation.Nullable;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.firebase.auth.FirebaseAuth;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Souls.Soul;
import com.kurmez.iyesi.kurmes.seed.SeedService;
import com.kurmez.iyesi.kurmes.utilities.clients.CFClient;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Response;

public class SoulsManagerActivity extends AppCompatActivity {

    //private static final String CF_BASE_URL = "https://us-central1-iyesi-aef03.cloudfunctions.net";
    private static final String PATH_LIST_MY    = "/listMySouls";
    private static final String PATH_CREATE     = "/createSoul"; // or /souls
    private static final String PATH_UPDATE_ID  = "/updateSoulById?id=";
    private static final String PATH_DELETE_ID  = "/deleteSoulById?id=";

    private CFClient cf;
    private RecyclerView recycler;
    private SoulsAdapter adapter;
    private View progress;
    private Button btnNew, btnRefresh, btnSeed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_souls_manager);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Toast.makeText(this, "Giriş gerekli.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(this, Login.class);
            startActivity(intent);
            finish(); return;
        }

        // onCreate içinde, cf'yi kurmadan önce:
        String projectId = com.google.firebase.FirebaseApp.getInstance().getOptions().getProjectId();
        String region    = "us-central1"; // CF’nin gerçek bölgesi ne ise o
        String baseUrl   = "https://" + region + "-" + projectId + ".cloudfunctions.net";

        cf = new CFClient(baseUrl);


        recycler = findViewById(R.id.recyclerSouls);
        progress = findViewById(R.id.progressBar);
        btnNew = findViewById(R.id.btnNew);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnSeed = findViewById(R.id.btnSeed);

        adapter = new SoulsAdapter(new ArrayList<>(), new SoulsAdapter.OnSoulAction() {
            @Override public void onEdit(Soul s) { showEditDialog(s); }
            @Override public void onDelete(Soul s) { deleteSoul(s.getId()); }
        });
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        btnRefresh.setOnClickListener(v -> loadMySouls());
        btnNew.setOnClickListener(v -> showCreateDialog());
        btnSeed.setOnClickListener(v -> startSeed());

        loadMySouls();
    }

    private void startSeed() {
        progress.setVisibility(View.VISIBLE);
        List<String> cities = new ArrayList<>();
        cities.add("Istanbul");
        SeedService seed = new SeedService(cf, PATH_CREATE);
        seed.seedCities(cities, 20, false, 2, new SeedService.SeedListener() {
            @Override public void onCityDone(String city, int ok, int total) {
                runOnUiThread(() -> Toast.makeText(SoulsManagerActivity.this, city + " → " + ok + "/" + total + " OK", Toast.LENGTH_SHORT).show());
            }
            @Override public void onAllDone() {
                runOnUiThread(() -> { progress.setVisibility(View.GONE); loadMySouls(); });
            }
            @Override public void onError(Exception e) {
                runOnUiThread(() -> { progress.setVisibility(View.GONE);
                    Toast.makeText(SoulsManagerActivity.this, "Seed hata: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadMySouls() {
        progress.setVisibility(View.VISIBLE);
        cf.getTokens((idToken, appToken) -> new Thread(() -> {
            List<Soul> souls = new ArrayList<>();
            String err = null;
            try (Response resp = cf.get(PATH_LIST_MY, idToken, appToken)) {
                if (resp.isSuccessful()) {
                    JSONObject body = new JSONObject(resp.body().string());
                    JSONArray items = body.optJSONArray("items");
                    if (items != null) {
                        for (int i=0; i<items.length(); i++) {
                            JSONObject o = items.getJSONObject(i);
                            Soul s = new Soul();
                            s.setId(o.optString("id"));
                            s.setName(o.optString("name"));
                            s.setSpecies(o.optString("species"));
                            s.setHealth(o.optString("health"));
                            if (o.has("lat")) s.setLatLng(o.optDouble("lat"),o.optDouble("lng"));
                            if (o.has("lng")) s.setLatLng(o.optDouble("lat"),o.optDouble("lng"));
                            souls.add(s);
                        }
                    }
                } else {
                    err = resp.code() + " " + resp.message();
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fErr = err;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (fErr != null) {
                    Toast.makeText(this, "Listeleme hatası: " + fErr, Toast.LENGTH_LONG).show();
                    Log.e("Liesteleme Hatası",fErr);
                } else {
                    adapter.setData(souls);
                }
            });
        }).start(), e -> runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            Toast.makeText(this, "Token hatası: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }));
    }

    private void showCreateDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_soul_edit, null);
        EditText etName = v.findViewById(R.id.etName);
        EditText etSpecies = v.findViewById(R.id.etSpecies);
        EditText etHealth = v.findViewById(R.id.etHealth);

        new AlertDialog.Builder(this)
            .setTitle("Yeni Soul")
            .setView(v)
            .setPositiveButton("Kaydet", (d, which) -> {
                String name = etName.getText().toString().trim();
                String species = etSpecies.getText().toString().trim();
                String health = etHealth.getText().toString().trim();
                if (TextUtils.isEmpty(name)) { Toast.makeText(this, "Ad gerekli", Toast.LENGTH_SHORT).show(); return; }
                createSoulJson(name, species, health);
            })
            .setNegativeButton("Vazgeç", null)
            .show();
    }

    private void createSoulJson(String name, String species, String health) {
        progress.setVisibility(View.VISIBLE);
        cf.getTokens((idToken, appToken) -> new Thread(() -> {
            String err = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("name", name);
                if (!TextUtils.isEmpty(species)) payload.put("species", species);
                if (!TextUtils.isEmpty(health))  payload.put("health", health);
                try (Response resp = cf.post(PATH_CREATE, payload.toString(), idToken, appToken)) {
                    if (!resp.isSuccessful()) err = resp.code() + " " + resp.message();
                }
            } catch (Exception e) { err = e.getMessage(); }

            final String fErr = err;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (fErr != null) Toast.makeText(this, "Oluşturma hatası: " + fErr, Toast.LENGTH_LONG).show();
                loadMySouls();
            });
        }).start(), e -> runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            Toast.makeText(this, "Token hatası: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }));
    }

    private void showEditDialog(Soul s) {
        View v = getLayoutInflater().inflate(R.layout.dialog_soul_edit, null);
        EditText etName = v.findViewById(R.id.etName);
        EditText etSpecies = v.findViewById(R.id.etSpecies);
        EditText etHealth = v.findViewById(R.id.etHealth);
        etName.setText(s.getName());
        etSpecies.setText(s.getSpecies());
        etHealth.setText(s.getHealth());

        new AlertDialog.Builder(this)
            .setTitle("Düzenle")
            .setView(v)
            .setPositiveButton("Kaydet", (d, w) -> updateSoul(s.getId(),
                    etName.getText().toString().trim(),
                    etSpecies.getText().toString().trim(),
                    etHealth.getText().toString().trim()))
            .setNegativeButton("Vazgeç", null)
            .show();
    }

    private void updateSoul(String id, String name, String species, String health) {
        if (TextUtils.isEmpty(id)) { Toast.makeText(this, "ID yok", Toast.LENGTH_SHORT).show(); return; }
        progress.setVisibility(View.VISIBLE);
        cf.getTokens((idToken, appToken) -> new Thread(() -> {
            String err = null;
            try {
                JSONObject payload = new JSONObject();
                if (!TextUtils.isEmpty(name))    payload.put("name", name);
                if (!TextUtils.isEmpty(species)) payload.put("species", species);
                if (!TextUtils.isEmpty(health))  payload.put("health", health);
                try (Response resp = cf.patch(PATH_UPDATE_ID + id, payload.toString(), idToken, appToken)) {
                    if (!resp.isSuccessful()) err = resp.code() + " " + resp.message();
                }
            } catch (Exception e) { err = e.getMessage(); }

            final String fErr = err;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (fErr != null) Toast.makeText(this, "Güncelleme hatası: " + fErr, Toast.LENGTH_LONG).show();
                loadMySouls();
            });
        }).start(), e -> runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            Toast.makeText(this, "Token hatası: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }));
    }

    private void deleteSoul(String id) {
        if (TextUtils.isEmpty(id)) { Toast.makeText(this, "ID yok", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this)
            .setMessage("Silmek istiyor musun?")
            .setPositiveButton("Evet", (d, w) -> {
                progress.setVisibility(View.VISIBLE);
                cf.getTokens((idToken, appToken) -> new Thread(() -> {
                    String err = null;
                    try (Response resp = cf.delete(PATH_DELETE_ID + id, idToken, appToken)) {
                        if (!resp.isSuccessful()) err = resp.code() + " " + resp.message();
                    } catch (IOException e) { err = e.getMessage(); }
                    final String fErr = err;
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        if (fErr != null) Toast.makeText(this, "Silme hatası: " + fErr, Toast.LENGTH_LONG).show();
                        loadMySouls();
                    });
                }).start(), e -> runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, "Token hatası: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }));
            })
            .setNegativeButton("Hayır", null)
            .show();
    }
}
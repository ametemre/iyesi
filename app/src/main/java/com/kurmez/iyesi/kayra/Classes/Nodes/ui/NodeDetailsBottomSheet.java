package com.kurmez.iyesi.kayra.Classes.Nodes.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Nodes.NodeType;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Ikon tıklanınca açılan detay paneli. */
public class NodeDetailsBottomSheet extends BottomSheetDialogFragment {
    public static final String ARG_ID   = "markerId";
    public static final String ARG_TYPE = "markerType";
    // Harita.openMarkerDetails(...) içinde isteğe bağlı eklenen payload anahtarları:
    public static final String ARG_MARKER_JSON = "marker_json";
    public static final String ARG_SOULS_JSON  = "souls_json";
    private static final String TAG = "BottomSheet";

    public interface Host {
        void onRequestMarkerReposition(@androidx.annotation.NonNull String markerId);
    }
    private Host host;
    // Camera ActivityResultLauncher
    private ActivityResultLauncher<Intent> cameraLauncher;

    @Override public void onAttach(Context ctx) {
        super.onAttach(ctx);
        if (ctx instanceof Host) host = (Host) ctx;
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Camera ActivityResultLauncher'ı başlat
        cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    Bundle extras = data.getExtras();
                    if (extras != null) {
                        Object bitmapObj = extras.get("data");
                        if (bitmapObj instanceof Bitmap) {
                            Bitmap photo = (Bitmap) bitmapObj;
                            handleCapturedPhoto(photo);
                        }
                    }
                } else {
                    Log.d(TAG, "Camera capture cancelled or failed");
                }
            }
        );
    }
    public static NodeDetailsBottomSheet newInstance(String markerId, NodeType type) {
        NodeDetailsBottomSheet f = new NodeDetailsBottomSheet();
        Bundle b = new Bundle();
        b.putString(ARG_ID, markerId);
        b.putString(ARG_TYPE, type != null ? type.name() : NodeType.TASK.name());
        f.setArguments(b);
        return f;
    }

    @Override
    public View onCreateView(LayoutInflater i, ViewGroup c, Bundle s) {
        return i.inflate(R.layout.bottomsheet_marker_details, c, false);
    }

    @Override
    public void onViewCreated(View root, Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);

        Bundle args = getArguments() != null ? getArguments() : new Bundle();
        final String markerId   = args.getString(ARG_ID, "");
        final String typeEnum   = args.getString(ARG_TYPE, NodeType.TASK.name());
        final String markerJson = args.getString(ARG_MARKER_JSON, null);
        final String soulsJson  = args.getString(ARG_SOULS_JSON,  "[]");

        // 1) JSON’ları parse et
        JSONObject marker = null;
        JSONArray souls = new JSONArray();
        try { if (!TextUtils.isEmpty(markerJson)) marker = new JSONObject(markerJson); } catch (JSONException ignore) {}
        try { souls = new JSONArray(soulsJson); } catch (JSONException ignore) {}

        // 2) Üst bilgiler
        String serverType = optType(marker);
        String prettyType = prettifyType(typeEnum, serverType);
        int soulsCount    = souls.length();

        TextView tvTitle   = root.findViewById(R.id.txt_title);
        TextView tvMeta    = root.findViewById(R.id.txt_meta);
        TextView tvSouls   = root.findViewById(R.id.txt_souls);
        TextView tvRawJson = root.findViewById(R.id.txt_raw_json);

        // Title: Sadece tür göster (ID gizli)
        String title = prettyType;
        
        // Meta: Kullanıcı dostu bilgiler (durum, adres, bakım zamanları)
        String meta = buildUserFriendlyMeta(marker, typeEnum, serverType);
        
        String soulsLine = buildSoulsLine(soulsCount, souls);

        boolean usedXml = false;
        if (tvTitle != null || tvMeta != null || tvSouls != null || tvRawJson != null) {
            usedXml = true;
            if (tvTitle != null) tvTitle.setText(title);
            if (tvMeta  != null) tvMeta.setText(meta);
            if (tvSouls != null) tvSouls.setText(soulsLine);
            if (tvRawJson != null) {
                tvRawJson.setText(marker != null ? marker.toString() : "{}");
                tvRawJson.setVisibility(View.GONE);
            }
        }

        // 3) XML yoksa dinamik içerik kur
        if (!usedXml) {
            if (root instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) root;
                int pad = dp(16);
                LinearLayout container = new LinearLayout(requireContext());
                container.setOrientation(LinearLayout.VERTICAL);
                container.setPadding(pad, pad, pad, pad);

                TextView t1 = makeTextView(18, true, title);
                TextView t2 = makeTextView(14, false, meta);
                TextView t3 = makeTextView(14, false, soulsLine);

                container.addView(t1);
                container.addView(spacer(dp(8)));
                container.addView(t2);
                container.addView(spacer(dp(8)));
                container.addView(t3);

                vg.addView(container,
                        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                android.widget.Toast.makeText(requireContext(), title, android.widget.Toast.LENGTH_SHORT).show();
            }
        }

        // 4) Tür-bazlı görünürlük ve chip'ler (dinamik; R.id.* yok)
        boolean isFeeding  = isTypeFeeding(typeEnum, serverType);
        boolean isNest     = isTypeNest(typeEnum, serverType);
        boolean isShelter  = isTypeShelter(typeEnum, serverType);
        boolean isBaksi    = isTypeBaksi(typeEnum, serverType);
        boolean isTask     = isTypeTask(typeEnum, serverType);

        ChipGroup cgActions = root.findViewById(R.id.chip_group_actions);
        if (cgActions != null) {
            cgActions.removeAllViews();
            int added = 0;

            if (isFeeding) {
                // Besleme bilgileri
                long   lastFedMs = optEpochMs(marker, "lastFedAt");
                String fedBy     = optDeepString(marker, "lastFedBy", "lastFedByName", "fedBy", "fedder");
                Double fedAmt    = optDeepDouble(marker, "lastFedAmount", "lastFedAmountGrams", "fedAmount");
                String fedUnit   = optDeepString(marker, "lastFedUnit", "fedUnit", "unit");

                // ÖNCE: Hardcoded "Ne zaman beslendi: ", "Kim besledi: ", "Miktar: ", vb.
                // ŞİMDİ: String resource kullanımı - format string ile dinamik değerler
                if (lastFedMs > 0) { addChip(cgActions, getString(R.string.chip_label_when_fed, formatRelative(lastFedMs))); added++; }
                if (!TextUtils.isEmpty(fedBy)) { addChip(cgActions, getString(R.string.chip_label_who_fed, fedBy)); added++; }
                String amtTxt = humanAmount(fedAmt, fedUnit);
                if (!TextUtils.isEmpty(amtTxt)) { addChip(cgActions, getString(R.string.chip_label_amount, amtTxt)); added++; }
                
                // Temizlik bilgileri
                long   lastCleanedMs = optEpochMs(marker, "lastCleanedAt");
                String cleanedBy    = optDeepString(marker, "lastCleanedBy", "cleanedBy", "cleanedByName");
                
                if (lastCleanedMs > 0) { addChip(cgActions, getString(R.string.chip_label_when_cleaned, formatRelative(lastCleanedMs))); added++; }
                if (!TextUtils.isEmpty(cleanedBy)) { addChip(cgActions, getString(R.string.chip_label_who_cleaned, cleanedBy)); added++; }
                
                // FeedingNode özel alanları (attrs içinde)
                String foodType = optFromAttrs(marker, "foodType");
                if (!TextUtils.isEmpty(foodType)) { addChip(cgActions, getString(R.string.chip_label_food_type, foodType)); added++; }
                Boolean water = optBooleanFromAttrs(marker, "water");
                // ÖNCE: Hardcoded "Su var"
                // ŞİMDİ: String resource kullanımı (parametresiz)
                if (water != null && water) { addChip(cgActions, getString(R.string.chip_label_water_available)); added++; }
            }

            if (isNest) {
                // Temizlik bilgileri
                long lastCleanedMs = optEpochMs(marker, "lastCleanedAt");
                String cleanedBy = optDeepString(marker, "lastCleanedBy", "cleanedBy", "cleanedByName");
                
                // ÖNCE: Hardcoded string'ler
                // ŞİMDİ: String resource kullanımı
                if (lastCleanedMs > 0) { addChip(cgActions, getString(R.string.chip_label_when_cleaned, formatRelative(lastCleanedMs))); added++; }
                if (!TextUtils.isEmpty(cleanedBy)) { addChip(cgActions, getString(R.string.chip_label_who_cleaned, cleanedBy)); added++; }
                
                // Besleme bilgileri
                long lastFedMs = optEpochMs(marker, "lastFedAt");
                String fedBy = optDeepString(marker, "lastFedBy", "lastFedByName", "fedBy", "fedder");
                
                if (lastFedMs > 0) { addChip(cgActions, getString(R.string.chip_label_when_fed, formatRelative(lastFedMs))); added++; }
                if (!TextUtils.isEmpty(fedBy)) { addChip(cgActions, getString(R.string.chip_label_who_fed, fedBy)); added++; }
                
                // Bakım bilgileri (maintained)
                long lastMaintMs = optEpochMs(marker, "lastMaintainedAt");
                String maintBy = optDeepString(marker, "lastMaintainedBy", "maintainedBy", "maintainedByName");
                if (lastMaintMs > 0) { addChip(cgActions, getString(R.string.chip_label_last_maintenance, formatRelative(lastMaintMs))); added++; }
                if (!TextUtils.isEmpty(maintBy)) { addChip(cgActions, getString(R.string.chip_label_maintained_by, maintBy)); added++; }
                
                // YuvaNode özel alanları (attrs içinde)
                Integer capacity = optIntegerFromAttrs(marker, "capacity");
                if (capacity != null && capacity > 0) { addChip(cgActions, getString(R.string.chip_label_capacity, capacity)); added++; }
                String material = optFromAttrs(marker, "material");
                if (!TextUtils.isEmpty(material)) { addChip(cgActions, getString(R.string.chip_label_material, material)); added++; }
                String condition = optFromAttrs(marker, "condition");
                if (!TextUtils.isEmpty(condition)) { addChip(cgActions, getString(R.string.chip_label_condition, condition)); added++; }
            }

            if (isShelter) {
                // Ziyaret bilgileri
                long lastVisitedMs = optEpochMs(marker, "lastVisitedAt");
                String visitedBy = optDeepString(marker, "lastVisitedBy", "visitedBy", "visitedByName");
                // ÖNCE: Hardcoded string'ler
                // ŞİMDİ: String resource kullanımı
                if (lastVisitedMs > 0) { addChip(cgActions, getString(R.string.chip_label_last_visit, formatRelative(lastVisitedMs))); added++; }
                if (!TextUtils.isEmpty(visitedBy)) { addChip(cgActions, getString(R.string.chip_label_visited_by, visitedBy)); added++; }
                
                // ShelterNode - Temel Bilgiler
                String shelterName = optFromAttrs(marker, "shelterName");
                if (!TextUtils.isEmpty(shelterName)) { addChip(cgActions, getString(R.string.chip_label_shelter_name, shelterName)); added++; }
                String manager = optFromAttrs(marker, "manager");
                if (!TextUtils.isEmpty(manager)) { addChip(cgActions, getString(R.string.chip_label_manager, manager)); added++; }
                String contact = optFromAttrs(marker, "contact");
                if (!TextUtils.isEmpty(contact)) { addChip(cgActions, getString(R.string.chip_label_contact, contact)); added++; }
                
                // Kapasite ve Hayvan Sayısı
                Integer capacity = optIntegerFromAttrs(marker, "capacity");
                Integer currentCount = optIntegerFromAttrs(marker, "currentAnimalCount");
                // ÖNCE: Hardcoded "Kapasite: " ve " (Mevcut: )" string concatenation
                // ŞİMDİ: String resource kullanımı - format string ile iki parametre
                if (capacity != null && capacity > 0) {
                    if (currentCount != null && currentCount > 0) {
                        addChip(cgActions, getString(R.string.chip_label_capacity_with_current, capacity, currentCount));
                    } else {
                        addChip(cgActions, getString(R.string.chip_label_capacity, capacity));
                    }
                    added++;
                } else if (currentCount != null && currentCount > 0) {
                    addChip(cgActions, getString(R.string.chip_label_current_count, currentCount));
                    added++;
                }
                
                // Veteriner Bilgileri
                Boolean has24HourVet = optBooleanFromAttrs(marker, "has24HourVet");
                // ÖNCE: Hardcoded "24 saat veteriner"
                // ŞİMDİ: String resource kullanımı (parametresiz)
                if (has24HourVet != null && has24HourVet) {
                    addChip(cgActions, getString(R.string.chip_label_24h_vet));
                    added++;
                }
                Boolean hasOperatingRoom = optBooleanFromAttrs(marker, "hasOperatingRoom");
                // ÖNCE: Hardcoded "Ameliyathane var"
                // ŞİMDİ: String resource kullanımı
                if (hasOperatingRoom != null && hasOperatingRoom) {
                    addChip(cgActions, getString(R.string.chip_label_operating_room));
                    added++;
                }
                Boolean hasXRay = optBooleanFromAttrs(marker, "hasXRay");
                // ÖNCE: Hardcoded "Röntgen cihazı var"
                // ŞİMDİ: String resource kullanımı
                if (hasXRay != null && hasXRay) {
                    addChip(cgActions, getString(R.string.chip_label_xray));
                    added++;
                }
                
                // Beslenme Bilgileri
                String feedingTime = optFromAttrs(marker, "feedingTime");
                if (!TextUtils.isEmpty(feedingTime)) {
                    addChip(cgActions, getString(R.string.chip_label_feeding_time, feedingTime));
                    added++;
                }
                Integer foodPerAnimal = optIntegerFromAttrs(marker, "foodPerAnimal");
                // ÖNCE: Hardcoded "Hayvan başına: " + foodPerAnimal + " gr"
                // ŞİMDİ: String resource kullanımı - format string ile sayı parametresi
                if (foodPerAnimal != null && foodPerAnimal > 0) {
                    addChip(cgActions, getString(R.string.chip_label_food_per_animal, foodPerAnimal));
                    added++;
                }
                
                // Önemli Yerleşim Bilgileri
                Boolean hasQuarantineArea = optBooleanFromAttrs(marker, "hasQuarantineArea");
                // ÖNCE: Hardcoded "Karantina bölgesi var"
                // ŞİMDİ: String resource kullanımı
                if (hasQuarantineArea != null && hasQuarantineArea) {
                    addChip(cgActions, getString(R.string.chip_label_quarantine_area));
                    added++;
                }
                Boolean hasLargePlayArea = optBooleanFromAttrs(marker, "hasLargePlayArea");
                // ÖNCE: Hardcoded "Büyük oyun alanı var"
                // ŞİMDİ: String resource kullanımı
                if (hasLargePlayArea != null && hasLargePlayArea) {
                    addChip(cgActions, getString(R.string.chip_label_large_play_area));
                    added++;
                }
                
                // Çalışan Bilgileri
                Integer employeeCount = optIntegerFromAttrs(marker, "employeeCount");
                // ÖNCE: Hardcoded "Çalışan: " + employeeCount
                // ŞİMDİ: String resource kullanımı
                if (employeeCount != null && employeeCount > 0) {
                    addChip(cgActions, getString(R.string.chip_label_employee_count, employeeCount));
                    added++;
                }
            }

            if (isBaksi) {
                // Baksi için özel bilgiler
                // ÖNCE: Hardcoded "Telefon: ", "Çalışma saatleri: ", "Web: ", "Değerlendirme: "
                // ŞİMDİ: String resource kullanımı
                String phone = optDeepString(marker, "phone", "telefon", "phoneNumber");
                if (!TextUtils.isEmpty(phone)) { addChip(cgActions, getString(R.string.chip_label_phone, phone)); added++; }
                
                String hours = optDeepString(marker, "hours", "workingHours", "calismaSaatleri");
                if (!TextUtils.isEmpty(hours)) { addChip(cgActions, getString(R.string.chip_label_working_hours, hours)); added++; }
                
                String website = optDeepString(marker, "website", "web", "url");
                if (!TextUtils.isEmpty(website)) { addChip(cgActions, getString(R.string.chip_label_website, website)); added++; }
                
                String rating = optDeepString(marker, "rating", "puan");
                if (!TextUtils.isEmpty(rating)) { addChip(cgActions, getString(R.string.chip_label_rating, rating)); added++; }
            }

            if (isTask) {
                // TaskNode özel alanları (attrs içinde)
                // ÖNCE: Hardcoded "Atanan: ", "Durum: ", "Zaman penceresi: "
                // ŞİMDİ: String resource kullanımı
                String assignedTo = optFromAttrs(marker, "assignedTo");
                if (!TextUtils.isEmpty(assignedTo)) { addChip(cgActions, getString(R.string.chip_label_assigned_to, assignedTo)); added++; }
                String state = optFromAttrs(marker, "state");
                if (!TextUtils.isEmpty(state)) { addChip(cgActions, getString(R.string.chip_label_state, state)); added++; }
                Long windowStart = optLongFromAttrs(marker, "windowStart");
                Long windowEnd = optLongFromAttrs(marker, "windowEnd");
                if (windowStart != null && windowEnd != null) {
                    String window = formatTimeWindow(windowStart, windowEnd);
                    if (!TextUtils.isEmpty(window)) { addChip(cgActions, getString(R.string.chip_label_time_window, window)); added++; }
                }
                // Koordinatlar genelde gösterilmez, ama gerekirse eklenebilir
            }

            cgActions.setVisibility(added > 0 ? View.VISIBLE : View.GONE);
        }
        
        // chip_type Chip'ini tıklanabilir yap ve camera intent'ini başlat
        Chip chipType = root.findViewById(R.id.chip_type);
        if (chipType != null) {
            chipType.setOnClickListener(v -> {
                Log.d(TAG, "chip_type clicked, starting camera");
                startCamera();
            });
        }
    }

    // ---------- Yardımcılar ----------

    /**
     * Kullanıcı dostu meta bilgileri oluşturur
     * - Durum (status)
     * - Adres (adminPath veya location)
     * - Bakım zamanları (tür bazlı)
     */
    private String buildUserFriendlyMeta(JSONObject marker, String typeEnum, String serverType) {
        if (marker == null) return "";
        
        StringBuilder meta = new StringBuilder();
        
        // 1. Durum
        String status = optDeepString(marker, "status");
        if (!TextUtils.isEmpty(status)) {
            String statusDisplay = prettifyStatus(status);
            // ÖNCE: Hardcoded "Durum: "
            // ŞİMDİ: String resource kullanımı - format string ile status parametresi
            if (meta.length() > 0) meta.append("\n");
            meta.append(getString(R.string.meta_label_status, statusDisplay));
        }
        
        // 2. Adres bilgisi
        String address = extractAddress(marker);
        if (!TextUtils.isEmpty(address)) {
            if (meta.length() > 0) meta.append("\n");
            // ÖNCE: Hardcoded "Konum: "
            // ŞİMDİ: String resource kullanımı - format string ile address parametresi
            meta.append(getString(R.string.meta_label_location, address));
        }
        
        // 3. Tür bazlı bakım zamanları (meta'ya da ekle, chip'lerde de gösterilecek)
        boolean isFeeding = isTypeFeeding(typeEnum, serverType);
        boolean isNest = isTypeNest(typeEnum, serverType);
        boolean isShelter = isTypeShelter(typeEnum, serverType);
        boolean isBaksi = isTypeBaksi(typeEnum, serverType);
        
        if (isFeeding) {
            // Besleme bilgileri
            long lastFedMs = optEpochMs(marker, "lastFedAt");
            String fedBy = optDeepString(marker, "lastFedBy", "lastFedByName", "fedBy", "fedder");
            if (lastFedMs > 0) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Ne zaman beslendi: "
                // ŞİMDİ: String resource kullanımı - format string ile zaman parametresi
                meta.append(getString(R.string.chip_label_when_fed, formatRelative(lastFedMs)));
            }
            if (!TextUtils.isEmpty(fedBy)) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Kim besledi: "
                // ŞİMDİ: String resource kullanımı - format string ile isim parametresi
                meta.append(getString(R.string.chip_label_who_fed, fedBy));
            }
            // Temizlik bilgileri
            long lastCleanedMs = optEpochMs(marker, "lastCleanedAt");
            String cleanedBy = optDeepString(marker, "lastCleanedBy", "cleanedBy", "cleanedByName");
            if (lastCleanedMs > 0) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Ne zaman temizlendi: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_when_cleaned, formatRelative(lastCleanedMs)));
            }
            if (!TextUtils.isEmpty(cleanedBy)) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Kim temizledi: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_who_cleaned, cleanedBy));
            }
        } else if (isNest) {
            // Temizlik bilgileri
            long lastCleanedMs = optEpochMs(marker, "lastCleanedAt");
            String cleanedBy = optDeepString(marker, "lastCleanedBy", "cleanedBy", "cleanedByName");
            if (lastCleanedMs > 0) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Ne zaman temizlendi: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_when_cleaned, formatRelative(lastCleanedMs)));
            }
            if (!TextUtils.isEmpty(cleanedBy)) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Kim temizledi: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_who_cleaned, cleanedBy));
            }
            // Besleme bilgileri
            long lastFedMs = optEpochMs(marker, "lastFedAt");
            String fedBy = optDeepString(marker, "lastFedBy", "lastFedByName", "fedBy", "fedder");
            if (lastFedMs > 0) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Ne zaman beslendi: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_when_fed, formatRelative(lastFedMs)));
            }
            if (!TextUtils.isEmpty(fedBy)) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Kim besledi: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_who_fed, fedBy));
            }
            // Bakım bilgileri
            long lastMaintMs = optEpochMs(marker, "lastMaintainedAt");
            String maintBy = optDeepString(marker, "lastMaintainedBy", "maintainedBy", "maintainedByName");
            if (lastMaintMs > 0) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Son bakım: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_last_maintenance, formatRelative(lastMaintMs)));
            }
            if (!TextUtils.isEmpty(maintBy)) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Bakım yapan: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_maintained_by, maintBy));
            }
        } else if (isShelter) {
            // Son ziyaret
            long lastVisitedMs = optEpochMs(marker, "lastVisitedAt");
            String visitedBy = optDeepString(marker, "lastVisitedBy", "visitedBy", "visitedByName");
            if (lastVisitedMs > 0) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Son ziyaret: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_last_visit, formatRelative(lastVisitedMs)));
            }
            if (!TextUtils.isEmpty(visitedBy)) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Kim ziyaret etti: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_visited_by, visitedBy));
            }
            // Temel bilgiler
            String manager = optFromAttrs(marker, "manager");
            if (!TextUtils.isEmpty(manager)) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Sorumlu: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_manager, manager));
            }
            String supervisors = optFromAttrs(marker, "supervisors");
            if (!TextUtils.isEmpty(supervisors)) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Denetleyici: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.meta_label_supervisor, supervisors));
            }
            // Kapasite ve hayvan sayısı
            Integer capacity = optIntegerFromAttrs(marker, "capacity");
            Integer currentCount = optIntegerFromAttrs(marker, "currentAnimalCount");
            if (capacity != null && capacity > 0) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Kapasite: " ve " (Mevcut: )"
                // ŞİMDİ: String resource kullanımı - format string ile iki parametre
                if (currentCount != null && currentCount > 0) {
                    meta.append(getString(R.string.chip_label_capacity_with_current, capacity, currentCount));
                } else {
                    meta.append(getString(R.string.chip_label_capacity, capacity));
                }
            }
            // Veteriner bilgileri
            String vetSchedule = optFromAttrs(marker, "vetSchedule");
            if (!TextUtils.isEmpty(vetSchedule)) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Veteriner: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.meta_label_veterinarian, vetSchedule));
            }
            // Beslenme bilgileri
            String feedingFrequency = optFromAttrs(marker, "feedingFrequency");
            if (!TextUtils.isEmpty(feedingFrequency)) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Besleme: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.meta_label_feeding, feedingFrequency));
            }
        } else if (isBaksi) {
            // Baksi için özel alanlar (örn: telefon, çalışma saatleri)
            String phone = optDeepString(marker, "phone", "telefon", "phoneNumber");
            if (!TextUtils.isEmpty(phone)) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Telefon: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_phone, phone));
            }
            String hours = optDeepString(marker, "hours", "workingHours", "calismaSaatleri");
            if (!TextUtils.isEmpty(hours)) {
                if (meta.length() > 0) meta.append("\n");
                // ÖNCE: Hardcoded "Çalışma saatleri: "
                // ŞİMDİ: String resource kullanımı
                meta.append(getString(R.string.chip_label_working_hours, hours));
            }
        }
        
        return meta.toString();
    }

    /**
     * Adres bilgisini çıkarır.
     *
     * Roadmap notu:
     * - `adminPath` artık adres değil, DB dallanmasıdır (min: COUNTRY/CITY).
     * - Bu yüzden önce `location.*` / `keys.*` alanlarından kullanıcıya anlamlı adres üret,
     *   adminPath'i sadece fallback olarak kullan.
     */
    private String extractAddress(JSONObject marker) {
        if (marker == null) return null;

        // Location nested object'ten adres oluştur
        JSONObject location = marker.optJSONObject("location");
        if (location != null) {
            StringBuilder addr = new StringBuilder();
            String street = location.optString("street", null);
            String neighbourhood = location.optString("neighbourhood", null);
            String district = location.optString("district", null);
            String city = location.optString("city", null);
            
            if (!TextUtils.isEmpty(street)) {
                addr.append(street);
            }
            if (!TextUtils.isEmpty(neighbourhood)) {
                // ÖNCE: Hardcoded ", " separator
                // ŞİMDİ: String resource kullanımı - adres parçaları arası separator
                if (addr.length() > 0) addr.append(getString(R.string.format_separator_comma));
                addr.append(neighbourhood);
            }
            if (!TextUtils.isEmpty(district)) {
                if (addr.length() > 0) addr.append(getString(R.string.format_separator_comma));
                addr.append(district);
            }
            if (!TextUtils.isEmpty(city)) {
                if (addr.length() > 0) addr.append(getString(R.string.format_separator_comma));
                addr.append(city);
            }
            
            if (addr.length() > 0) return addr.toString();
        }
        
        // Keys nested object'ten de dene
        JSONObject keys = marker.optJSONObject("keys");
        if (keys != null) {
            StringBuilder addr = new StringBuilder();
            String street = keys.optString("street", null);
            String neighbourhood = keys.optString("neighbourhood", null);
            String district = keys.optString("district", null);
            String city = keys.optString("city", null);
            
            if (!TextUtils.isEmpty(street)) {
                addr.append(street);
            }
            if (!TextUtils.isEmpty(neighbourhood)) {
                // ÖNCE: Hardcoded ", " separator
                // ŞİMDİ: String resource kullanımı - adres parçaları arası separator
                if (addr.length() > 0) addr.append(getString(R.string.format_separator_comma));
                addr.append(neighbourhood);
            }
            if (!TextUtils.isEmpty(district)) {
                if (addr.length() > 0) addr.append(getString(R.string.format_separator_comma));
                addr.append(district);
            }
            if (!TextUtils.isEmpty(city)) {
                if (addr.length() > 0) addr.append(getString(R.string.format_separator_comma));
                addr.append(city);
            }
            
            if (addr.length() > 0) return addr.toString();
        }

        // Fallback: adminPath (min: COUNTRY/CITY)
        String adminPath = optDeepString(marker, "adminPath");
        if (!TextUtils.isEmpty(adminPath)) {
            return prettifyAdminPath(adminPath);
        }
        
        return null;
    }

    /**
     * AdminPath'i daha okunur formata çevirir
     * "TR/İstanbul/Kadıköy/Moda/Bahariye Cd." -> "Bahariye Cd., Moda, Kadıköy, İstanbul"
     */
    private String prettifyAdminPath(String adminPath) {
        if (TextUtils.isEmpty(adminPath)) return null;
        
        String[] parts = adminPath.split("/");
        if (parts.length < 2) return adminPath; // Format beklenmiyor

        // Yeni min format: COUNTRY/CITY → sadece CITY göster (adres değil)
        if (parts.length == 2) {
            String city = parts[1] != null ? parts[1].trim() : "";
            return city.isEmpty() ? adminPath : city;
        }
        
        // İlk kısım ülke kodu (TR, CY vb.), sonra şehir, ilçe, mahalle, sokak
        // Ters sırada birleştir: sokak, mahalle, ilçe, şehir
        StringBuilder result = new StringBuilder();
        for (int i = parts.length - 1; i >= 1; i--) { // 0. index ülke kodu, atla
            String part = parts[i].trim();
            if (!part.isEmpty()) {
                // ÖNCE: Hardcoded ", " separator
                // ŞİMDİ: String resource kullanımı - admin path parçaları arası separator
                if (result.length() > 0) result.append(getString(R.string.format_separator_comma));
                result.append(part);
            }
        }
        
        return result.length() > 0 ? result.toString() : adminPath;
    }

    /**
     * Status değerini kullanıcı dostu formata çevirir
     */
    private String prettifyStatus(String status) {
        if (TextUtils.isEmpty(status)) return status;
        
        String s = status.trim().toLowerCase(Locale.getDefault());
        // ÖNCE: Hardcoded "Aktif", "Pasif", "Beklemede", "Kapatıldı", "Bakımda"
        // ŞİMDİ: String resource kullanımı - çeviri desteği için
        switch (s) {
            case "active": return getString(R.string.status_active);
            case "inactive": return getString(R.string.status_inactive);
            case "pending": return getString(R.string.status_pending);
            case "closed": return getString(R.string.status_closed);
            case "maintenance": return getString(R.string.status_maintenance);
            default: return status; // Orijinal değeri döndür
        }
    }

    private String extractLatLng(JSONObject marker) {
        if (marker == null) return null;
        double lat = Double.NaN, lng = Double.NaN;
        try {
            if (marker.has("lat")) lat = marker.optDouble("lat", Double.NaN);
            if (marker.has("lng")) lng = marker.optDouble("lng", Double.NaN);
            JSONObject data = marker.optJSONObject("data");
            if (Double.isNaN(lat) && data != null) lat = data.optDouble("lat", Double.NaN);
            if (Double.isNaN(lng) && data != null) lng = data.optDouble("lng", Double.NaN);
        } catch (Throwable ignore) {}
        if (Double.isNaN(lat) || Double.isNaN(lng)) return null;
        return String.format(Locale.getDefault(), "%.5f, %.5f", lat, lng);
    }

    private String optType(JSONObject marker) {
        if (marker == null) return null;
        String t = marker.optString("type", null);
        if (t == null && marker.has("data")) {
            JSONObject d = marker.optJSONObject("data");
            if (d != null) t = d.optString("type", null);
        }
        return t;
    }

    private String prettifyType(String enumName, String serverType) {
        String fromEnum;
        try {
            NodeType mt = NodeType.valueOf(enumName);
            switch (mt) {
                // ÖNCE: Hardcoded "Besleme", "Yuva", "Barınak", "Görev", "Nokta"
                // ŞİMDİ: String resource kullanımı - çeviri desteği için
                case FEEDING: fromEnum = getString(R.string.node_type_feeding); break;
                case NEST:    fromEnum = getString(R.string.node_type_nest);    break;
                case SHELTER: fromEnum = getString(R.string.node_type_shelter); break;
                case TASK:    fromEnum = getString(R.string.node_type_task);   break;
                default:      fromEnum = getString(R.string.node_type_default);
            }
        } catch (Throwable ignore) {
            // ÖNCE: Hardcoded "Nokta"
            // ŞİMDİ: String resource kullanımı
            fromEnum = getString(R.string.node_type_default);
        }
        // ÖNCE: Hardcoded string concatenation: fromEnum + " (" + serverType + ")"
        // ŞİMDİ: String resource kullanımı - format string ile iki parametre
        if (!TextUtils.isEmpty(serverType)) return getString(R.string.node_type_with_server, fromEnum, serverType);
        return fromEnum;
    }

    private String buildSoulsLine(int count, JSONArray souls) {
        StringBuilder sb = new StringBuilder();
        // ÖNCE: Hardcoded "Canlar: "
        // ŞİMDİ: String resource kullanımı - format string ile sayı parametresi
        sb.append(getString(R.string.meta_label_souls, count));
        int listed = 0;
        for (int i = 0; i < souls.length() && listed < 5; i++) {
            try {
                JSONObject s = souls.getJSONObject(i);
                String name = s.optString("name", null);
                if (TextUtils.isEmpty(name)) name = s.optString("nickname", null);
                if (!TextUtils.isEmpty(name)) {
                    // ÖNCE: Hardcoded " • " ve ", " separator'ları
                    // ŞİMDİ: String resource kullanımı - çeviri desteği için
                    if (listed == 0) sb.append(getString(R.string.format_separator_bullet));
                    else sb.append(getString(R.string.format_separator_comma));
                    sb.append(name);
                    listed++;
                }
            } catch (JSONException ignore) {}
        }
        return sb.toString();
    }

    private String shortId(String id) {
        if (TextUtils.isEmpty(id)) return "";
        return id.length() > 10 ? id.substring(0, 6) + "…" + id.substring(id.length() - 4) : id;
    }

    private TextView makeTextView(int sp, boolean bold, String text) {
        TextView tv = new TextView(requireContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setText(text);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private View spacer(int hPx) {
        View v = new View(requireContext());
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hPx));
        return v;
    }

    /** Null/boş olanları atlayarak metinleri birleştirir. */
    private String joinNonEmpty(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if (parts != null) {
            for (String p : parts) {
                if (p == null) continue;
                String t = p.trim();
                if (t.isEmpty()) continue;
                if (!first) sb.append(sep);
                sb.append(t);
                first = false;
            }
        }
        return sb.toString();
    }

    /** Firestore/CF gövdesinden epoch millis değeri okur (kökte ya da data altında). */
    private long optEpochMs(JSONObject obj, String key) {
        if (obj == null) return -1;
        try {
            if (obj.has(key)) {
                Object v = obj.get(key);
                if (v instanceof Number) return ((Number) v).longValue();
                if (v instanceof JSONObject) {
                    JSONObject t = (JSONObject) v;
                    if (t.has("_seconds")) {
                        long sec  = t.optLong("_seconds", 0);
                        long nsec = t.optLong("_nanoseconds", 0);
                        return sec * 1000L + (nsec / 1_000_000L);
                    }
                }
            }
        } catch (JSONException ignore) {}
        JSONObject data = obj.optJSONObject("data");
        if (data != null && data != obj) return optEpochMs(data, key);
        return -1;
    }

    /** İnsan okunur "x dk/sa/gün önce" formatı. */
    private String formatRelative(long tsMs) {
        if (tsMs <= 0) return null;
        long now = System.currentTimeMillis();
        long diff = Math.max(0, now - tsMs);
        long mins = diff / 60000L;
        // ÖNCE: Hardcoded "şimdi", " dk önce", " sa önce", " gün önce"
        // ŞİMDİ: String resource kullanımı - format string ile sayı parametreleri
        if (mins < 1) return getString(R.string.time_format_just_now);
        if (mins < 60) return getString(R.string.time_format_minutes_ago, mins);
        long hours = mins / 60;
        if (hours < 24) return getString(R.string.time_format_hours_ago, hours);
        long days = hours / 24;
        if (days < 7)  return getString(R.string.time_format_days_ago, days);
        java.text.DateFormat df = android.text.format.DateFormat.getDateFormat(requireContext());
        return df.format(new java.util.Date(tsMs));
    }

    /** Yeni chip ekler (dinamik). */
    private void addChip(ChipGroup group, String text) {
        Chip chip = new Chip(requireContext(),
                null,
                com.google.android.material.R.style.Widget_MaterialComponents_Chip_Action);
        chip.setClickable(false);
        chip.setCheckable(false);
        chip.setText(text);
        group.addView(chip);
    }

    private int dp(int v) {
        return Math.round(v * Resources.getSystem().getDisplayMetrics().density);
    }

    private boolean isTypeFeeding(String enumName, String serverType) {
        String e = enumName == null ? "" : enumName.trim().toUpperCase(Locale.ROOT);
        String s = serverType == null ? "" : serverType.trim().toLowerCase(Locale.ROOT);
        return "FEEDING".equals(e) || "BESLEME".equalsIgnoreCase(enumName)
                || s.equals("feeding") || s.equals("besleme");
    }

    private boolean isTypeNest(String enumName, String serverType) {
        String e = enumName == null ? "" : enumName.trim().toUpperCase(Locale.ROOT);
        String s = serverType == null ? "" : serverType.trim().toLowerCase(Locale.ROOT);
        return "NEST".equals(e) || "YUVA".equalsIgnoreCase(enumName)
                || s.equals("nest") || s.equals("yuva");
    }

    private boolean isTypeShelter(String enumName, String serverType) {
        String e = enumName == null ? "" : enumName.trim().toUpperCase(Locale.ROOT);
        String s = serverType == null ? "" : serverType.trim().toLowerCase(Locale.ROOT);
        return "SHELTER".equals(e) || "BARINAK".equalsIgnoreCase(enumName)
                || s.equals("shelter") || s.equals("barınak");
    }

    private boolean isTypeBaksi(String enumName, String serverType) {
        String e = enumName == null ? "" : enumName.trim().toUpperCase(Locale.ROOT);
        String s = serverType == null ? "" : serverType.trim().toLowerCase(Locale.ROOT);
        return "BAKSI".equals(e) || "BAKSI".equalsIgnoreCase(enumName)
                || s.equals("baksi") || s.equals("veteriner") || s.equals("veterinarian");
    }

    private boolean isTypeTask(String enumName, String serverType) {
        String e = enumName == null ? "" : enumName.trim().toUpperCase(Locale.ROOT);
        String s = serverType == null ? "" : serverType.trim().toLowerCase(Locale.ROOT);
        return "TASK".equals(e) || "GÖREV".equalsIgnoreCase(enumName) || "GOREV".equalsIgnoreCase(enumName)
                || s.equals("task") || s.equals("görev") || s.equals("gorev");
    }

    // Derin okuma: kökte yoksa data içinde ara
    private String optDeepString(JSONObject obj, String... keys) {
        if (obj == null) return null;
        for (String k : keys) {
            String v = obj.optString(k, null);
            if (!TextUtils.isEmpty(v)) return v;
        }
        JSONObject data = obj.optJSONObject("data");
        if (data != null && data != obj) return optDeepString(data, keys);
        return null;
    }

    private Double optDeepDouble(JSONObject obj, String... keys) {
        if (obj == null) return null;
        for (String k : keys) {
            if (obj.has(k)) {
                try { return obj.getDouble(k); }
                catch (JSONException ignore) {}
            }
        }
        JSONObject data = obj.optJSONObject("data");
        if (data != null && data != obj) return optDeepDouble(data, keys);
        return null;
    }

    /**
     * attrs içinden string değer okur
     * attrs bir JSONObject olarak nested olabilir
     */
    private String optFromAttrs(JSONObject obj, String key) {
        if (obj == null) return null;
        try {
            // Önce kökte dene
            if (obj.has(key)) {
                return obj.optString(key, null);
            }
            // attrs içinde ara
            JSONObject attrs = obj.optJSONObject("attrs");
            if (attrs != null && attrs.has(key)) {
                return attrs.optString(key, null);
            }
            // data.attrs içinde de ara
            JSONObject data = obj.optJSONObject("data");
            if (data != null) {
                JSONObject dataAttrs = data.optJSONObject("attrs");
                if (dataAttrs != null && dataAttrs.has(key)) {
                    return dataAttrs.optString(key, null);
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * attrs içinden boolean değer okur
     */
    private Boolean optBooleanFromAttrs(JSONObject obj, String key) {
        if (obj == null) return null;
        try {
            // Önce kökte dene
            if (obj.has(key)) {
                return obj.optBoolean(key, false);
            }
            // attrs içinde ara
            JSONObject attrs = obj.optJSONObject("attrs");
            if (attrs != null && attrs.has(key)) {
                Object v = attrs.opt(key);
                if (v instanceof Boolean) return (Boolean) v;
                if (v instanceof String) return Boolean.parseBoolean((String) v);
                if (v instanceof Number) return ((Number) v).intValue() != 0;
            }
            // data.attrs içinde de ara
            JSONObject data = obj.optJSONObject("data");
            if (data != null) {
                JSONObject dataAttrs = data.optJSONObject("attrs");
                if (dataAttrs != null && dataAttrs.has(key)) {
                    Object v = dataAttrs.opt(key);
                    if (v instanceof Boolean) return (Boolean) v;
                    if (v instanceof String) return Boolean.parseBoolean((String) v);
                    if (v instanceof Number) return ((Number) v).intValue() != 0;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * attrs içinden integer değer okur
     */
    private Integer optIntegerFromAttrs(JSONObject obj, String key) {
        if (obj == null) return null;
        try {
            // Önce kökte dene
            if (obj.has(key)) {
                return obj.optInt(key, 0);
            }
            // attrs içinde ara
            JSONObject attrs = obj.optJSONObject("attrs");
            if (attrs != null && attrs.has(key)) {
                Object v = attrs.opt(key);
                if (v instanceof Number) return ((Number) v).intValue();
                if (v instanceof String) {
                    try { return Integer.parseInt((String) v); } catch (NumberFormatException ignore) {}
                }
            }
            // data.attrs içinde de ara
            JSONObject data = obj.optJSONObject("data");
            if (data != null) {
                JSONObject dataAttrs = data.optJSONObject("attrs");
                if (dataAttrs != null && dataAttrs.has(key)) {
                    Object v = dataAttrs.opt(key);
                    if (v instanceof Number) return ((Number) v).intValue();
                    if (v instanceof String) {
                        try { return Integer.parseInt((String) v); } catch (NumberFormatException ignore) {}
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * attrs içinden long değer okur
     */
    private Long optLongFromAttrs(JSONObject obj, String key) {
        if (obj == null) return null;
        try {
            // Önce kökte dene
            if (obj.has(key)) {
                return obj.optLong(key, 0);
            }
            // attrs içinde ara
            JSONObject attrs = obj.optJSONObject("attrs");
            if (attrs != null && attrs.has(key)) {
                Object v = attrs.opt(key);
                if (v instanceof Number) return ((Number) v).longValue();
                if (v instanceof String) {
                    try { return Long.parseLong((String) v); } catch (NumberFormatException ignore) {}
                }
            }
            // data.attrs içinde de ara
            JSONObject data = obj.optJSONObject("data");
            if (data != null) {
                JSONObject dataAttrs = data.optJSONObject("attrs");
                if (dataAttrs != null && dataAttrs.has(key)) {
                    Object v = dataAttrs.opt(key);
                    if (v instanceof Number) return ((Number) v).longValue();
                    if (v instanceof String) {
                        try { return Long.parseLong((String) v); } catch (NumberFormatException ignore) {}
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * Zaman penceresini formatlar (windowStart ve windowEnd'den)
     */
    private String formatTimeWindow(long startMs, long endMs) {
        if (startMs <= 0 || endMs <= 0) return null;
        java.text.DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(requireContext());
        java.text.DateFormat dateFormat = android.text.format.DateFormat.getDateFormat(requireContext());
        
        String start = timeFormat.format(new java.util.Date(startMs));
        String end = timeFormat.format(new java.util.Date(endMs));
        
        // Aynı gün mü kontrol et
        java.util.Calendar cal1 = java.util.Calendar.getInstance();
        java.util.Calendar cal2 = java.util.Calendar.getInstance();
        cal1.setTimeInMillis(startMs);
        cal2.setTimeInMillis(endMs);
        
        boolean sameDay = cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
                         cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR);
        
        // ÖNCE: Hardcoded " - " ve " " separator'ları
        // ŞİMDİ: String resource kullanımı - format string ile zaman penceresi formatı
        if (sameDay) {
            return getString(R.string.format_time_window_same_day, start, end);
        } else {
            String date1 = dateFormat.format(new java.util.Date(startMs));
            String date2 = dateFormat.format(new java.util.Date(endMs));
            return getString(R.string.format_time_window_different_days, date1, start, date2, end);
        }
    }

    /** 12 → "12", 12.5 → "12.5", birim varsa ekler. */
    private String humanAmount(Double amt, String unit) {
        if (amt == null) return null;
        String n = (Math.abs(amt - Math.rint(amt)) < 1e-9)
                ? String.format(Locale.getDefault(), "%.0f", amt)
                : String.format(Locale.getDefault(), "%.2f", amt);
        // ÖNCE: Hardcoded " " separator (sayı ve birim arasında)
        // ŞİMDİ: String resource kullanımı - format string ile sayı ve birim
        if (!TextUtils.isEmpty(unit)) return getString(R.string.format_amount_with_unit, n, unit);
        return n;
    }
    
    /**
     * Camera intent'ini başlatır (Founded.java'daki onStartCamera metoduna benzer)
     */
    private void startCamera() {
        Log.d(TAG, "startCamera()");
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(requireContext().getPackageManager()) != null) {
            cameraLauncher.launch(takePictureIntent);
        } else {
            // ÖNCE: Hardcoded "Kamera kullanılamıyor"
            // ŞİMDİ: String resource kullanımı - Toast mesajı çeviriye hazır
            Toast.makeText(requireContext(), getString(R.string.toast_camera_unavailable), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Çekilen fotoğrafı işler.
     * TODO: Backend'e node bilgileri ile birlikte gönderilecek.
     * - Fotoğraf node etiketi ile yüklenecek
     * - Backend'de yapay zeka tarafından işlenecek
     * - Eğer hayvana aitse: kayıtlı/anonym, kayıp/kaçak gibi etiketler belirlenecek
     * - Sorumlu kullanıcı varsa bildirim gidecek
     * - Eğer alanın temizliği veya yiyecek kabıyla ilgiliyse ona göre işlenecek
     */
    private void handleCapturedPhoto(@NonNull Bitmap photo) {
        Log.d(TAG, "handleCapturedPhoto() - photo size: " + photo.getWidth() + "x" + photo.getHeight());
        
        Bundle args = getArguments();
        if (args == null) {
            Log.w(TAG, "handleCapturedPhoto: No arguments, cannot process photo");
            return;
        }
        
        String markerId = args.getString(ARG_ID, "");
        String markerJson = args.getString(ARG_MARKER_JSON, null);
        
        // TODO: Backend'e fotoğraf ve node bilgilerini gönderme mantığı
        // 1. Fotoğrafı node etiketi ile birlikte backend'e yükle
        // 2. Backend'de yapay zeka ile işle
        // 3. Sonuçları node verilerine göre güncelle
        // 4. Gerekirse bildirim gönder
        
        // ÖNCE: Hardcoded "Fotoğraf çekildi (Backend entegrasyonu TODO)"
        // ŞİMDİ: String resource kullanımı - Toast mesajı çeviriye hazır
        Toast.makeText(requireContext(), getString(R.string.toast_photo_captured), Toast.LENGTH_SHORT).show();
        Log.i(TAG, "handleCapturedPhoto: TODO - Backend integration pending. markerId=" + markerId);
    }
}

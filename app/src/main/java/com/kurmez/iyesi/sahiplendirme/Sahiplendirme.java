// Sahiplendirme.java
package com.kurmez.iyesi.sahiplendirme;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.kurmez.iyesi.R;

public class Sahiplendirme extends AppCompatActivity {

    private EditText    etName, etSpecies, etBreed, etAge,
            etHealth, etFoundDate, etFoundPlace, etVeterinary;
    private ImageView   imgSave;
    private FirebaseFirestore db;
    private String userId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sahiplendirme);

        db     = FirebaseFirestore.getInstance();
        userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        etName       = findViewById(R.id.soul_companion);
        etSpecies    = findViewById(R.id.species);
        etBreed      = findViewById(R.id.breed);
        etAge        = findViewById(R.id.age);
        etHealth     = findViewById(R.id.health);
        etFoundDate  = findViewById(R.id.found_Date);
        etFoundPlace = findViewById(R.id.found_place);
        etVeterinary = findViewById(R.id.veterineary_ulgen);

        imgSave = findViewById(R.id.soul_text);
        imgSave.setClickable(true);
        imgSave.setOnClickListener(v -> submitAdoption());
    }

    private void submitAdoption() {
        String name       = etName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            etName.setError("İsim boş olamaz");
            return;
        }

        Soul pet = new Soul(
                name,
                etSpecies.getText().toString().trim(),
                etBreed.getText().toString().trim(),
                etAge.getText().toString().trim(),
                etHealth.getText().toString().trim(),
                etFoundDate.getText().toString().trim(),
                etFoundPlace.getText().toString().trim(),
                etVeterinary.getText().toString().trim(),
                /* imageResId= */ "",    // eğer kullanıcı bir fotoğraf seçecekse URI buraya set edin
                /* finderName= */ userId,
                /* timestamp=  */ System.currentTimeMillis()
        );

        db.collection("adoptions")
                .add(pet)
                .addOnSuccessListener(doc -> {
                    Toast.makeText(this, "Kayıt başarıyla oluşturuldu", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
    }
}

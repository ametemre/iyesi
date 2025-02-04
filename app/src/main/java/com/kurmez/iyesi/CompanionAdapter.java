package com.kurmez.iyesi;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.util.List;
public class CompanionAdapter extends ArrayAdapter<PetCompanion> {
    public CompanionAdapter(Context context, List<PetCompanion> companions) {
        super(context, 0, companions);
    }
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(
                    R.layout.list_item_companion, parent, false);
        }

        // Get the current companion
        PetCompanion companion = getItem(position);

        // Get references to UI elements
        ImageView petImage = convertView.findViewById(R.id.pet_image);
        TextView petBreed = convertView.findViewById(R.id.pet_breed);
        TextView foundDate = convertView.findViewById(R.id.found_date);
        TextView foundLocation = convertView.findViewById(R.id.found_location);
        TextView finderName = convertView.findViewById(R.id.finder_name);

        // Bind data to the UI elements
        petBreed.setText(companion.getBreed());
        foundDate.setText(companion.getFoundDate());
        foundLocation.setText(companion.getFoundLocation());
        finderName.setText(companion.getFinderName());

        // Use Glide to load the image from the URL
        Glide.with(getContext())
                .load(companion.getImageResId()) // This is a URL
                .placeholder(R.drawable.holder) // Placeholder image
                .error(R.drawable.star) // Error imagez
                .into(petImage);

        return convertView;
    }

}
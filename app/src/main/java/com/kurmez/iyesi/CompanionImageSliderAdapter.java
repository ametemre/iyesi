package com.kurmez.iyesi;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

public class CompanionImageSliderAdapter extends RecyclerView.Adapter<CompanionImageSliderAdapter.ViewHolder> {

    private final List<String> photoUrls;
    private final Context context;

    public CompanionImageSliderAdapter(List<String> photoUrls, Context context) {
        this.photoUrls = photoUrls;
        this.context = context;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_slider_image, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Glide.with(context)
                .load(photoUrls.get(position))
                .placeholder(R.drawable.holder)
                .error(R.drawable.star)
                .into(holder.imageView);
    }

    @Override
    public int getItemCount() {
        return photoUrls.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.slider_image);
        }
    }
}

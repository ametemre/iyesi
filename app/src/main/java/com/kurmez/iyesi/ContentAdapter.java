package com.kurmez.iyesi;

import android.content.Context;
import android.database.DataSetObserver;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

public class ContentAdapter extends RecyclerView.Adapter<ContentAdapter.ContentViewHolder> implements ListAdapter {
    private final List<Content> contentList;
    private final Context context;

    public ContentAdapter(List<Content> contentList, Context context) {
        this.contentList = contentList;
        this.context = context;
    }

    @NonNull
    @Override
    public ContentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_content, parent, false);
        return new ContentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ContentViewHolder holder, int position) {
        Content content = contentList.get(position);
        holder.contentText.setText(content.getText());
        holder.likes.setText(String.format("Likes: %d", content.getLikes()));
        holder.comments.setText(String.format("Comments: %d", content.getComments()));

        // Load media (image or video thumbnail) with Glide
        Glide.with(context).load(content.getMediaUrl()).into(holder.contentImage);
    }

    @Override
    public int getItemCount() {
        return contentList.size();
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int i) {
        return false;
    }

    @Override
    public void registerDataSetObserver(DataSetObserver dataSetObserver) {

    }

    @Override
    public void unregisterDataSetObserver(DataSetObserver dataSetObserver) {

    }

    @Override
    public int getCount() {
        return 0;
    }

    @Override
    public Object getItem(int i) {
        return null;
    }

    @Override
    public View getView(int i, View view, ViewGroup viewGroup) {
        return null;
    }

    @Override
    public int getViewTypeCount() {
        return 1; // Return 1 if you only have one type of view in the ListView
    }


    @Override
    public boolean isEmpty() {
        return false;
    }

    public static class ContentViewHolder extends RecyclerView.ViewHolder {
        ImageView contentImage;
        TextView contentText, likes, comments;

        public ContentViewHolder(@NonNull View itemView) {
            super(itemView);
            contentImage = itemView.findViewById(R.id.content_image);
            contentText = itemView.findViewById(R.id.content_text);
            likes = itemView.findViewById(R.id.content_likes);
            comments = itemView.findViewById(R.id.content_comments);
        }
    }
}

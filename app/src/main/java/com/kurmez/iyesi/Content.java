package com.kurmez.iyesi;

import java.io.Serializable;

public class Content implements Serializable {
    private String mediaUrl; // URL for image or video
    private String text; // Text associated with the content
    private int likes; // Number of likes
    private int comments; // Number of comments
    private boolean isPrivate; // Whether the content is private

    // No-argument constructor for Firebase (if needed)
    public Content(String url, String s, int i, int i1) {}

    // Constructor to create content objects
    public Content(String mediaUrl, String text, int likes, int comments, boolean isPrivate) {
        this.mediaUrl = mediaUrl;
        this.text = text;
        this.likes = likes;
        this.comments = comments;
        this.isPrivate = isPrivate;
    }

    // Getters and setters
    public String getMediaUrl() {
        return mediaUrl;
    }

    public void setMediaUrl(String mediaUrl) {
        this.mediaUrl = mediaUrl;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public int getLikes() {
        return likes;
    }

    public void setLikes(int likes) {
        this.likes = likes;
    }

    public int getComments() {
        return comments;
    }

    public void setComments(int comments) {
        this.comments = comments;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public void setPrivate(boolean aPrivate) {
        isPrivate = aPrivate;
    }
}
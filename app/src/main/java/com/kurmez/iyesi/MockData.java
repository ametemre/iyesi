package com.kurmez.iyesi;

import java.util.ArrayList;
import java.util.List;

public class MockData {
    public static List<Content> getPublicContent() {
        List<Content> list = new ArrayList<>();
        list.add(new Content("https://example.com/image1.jpg", "Beautiful day!", 25, 5));
        list.add(new Content("https://example.com/video1.mp4", "Check out this video!", 50, 15));
        // Add more content...
        return list;
    }

    public static List<Content> getPrivateContent() {
        List<Content> list = new ArrayList<>();
        list.add(new Content("https://example.com/image2.jpg", "Private Image!", 10, 2));
        return list;
    }

    public static List<Content> getUserContent() {
        List<Content> list = new ArrayList<>();
        list.add(new Content("https://example.com/image1.jpg", "Morning vibes!", 100, 20, false));
        list.add(new Content("https://example.com/image2.jpg", "At the park with friends.", 80, 15, false));
        list.add(new Content("https://example.com/image3.jpg", "Sunset at the beach.", 150, 30, false));
        list.add(new Content("https://example.com/image4.jpg", "Private Photo!", 50, 5, true));
        return list;
    }
}

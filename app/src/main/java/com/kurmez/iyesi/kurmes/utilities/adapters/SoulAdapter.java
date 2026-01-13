package com.kurmez.iyesi.kurmes.utilities.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.kurmez.iyesi.BuildConfig;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Souls.Soul;
import java.util.List;

public class SoulAdapter extends RecyclerView.Adapter<SoulAdapter.VH> {
    private final List<Soul> items;
    private final Context ctx;

    public SoulAdapter(List<Soul> items, Context ctx) {
        this.items = items;
        this.ctx = ctx;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item_companion, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Soul s = items.get(pos);
        h.breed.setText( nz(s.getBreed(), "-") );
        // ÖNCE: Hardcoded "Bulunduğu tarih", "Bulunduğu yer", "Bulan"
        // ŞİMDİ: String resource kullanımı
        h.foundDate.setText( nz(s.getFoundDate(), ctx.getString(R.string.soul_adapter_fallback_found_date)) );
        h.location.setText( nz(firstNonNull(s.getAdminPath(), s.getFoundLocation()), ctx.getString(R.string.soul_adapter_fallback_found_location)) );
        h.finder.setText( nz(s.getFinderName(), ctx.getString(R.string.soul_adapter_fallback_finder)) );

        Glide.with(ctx).load(normalizeUrl(s.getImageUrl()))
                .placeholder(R.drawable.holder).error(R.drawable.holder).into(h.image);
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView image; TextView breed, foundDate, location, finder;
        VH(@NonNull View v){
            super(v);
            image = v.findViewById(R.id.pet_image);
            breed = v.findViewById(R.id.pet_breed);
            foundDate = v.findViewById(R.id.found_date);
            location = v.findViewById(R.id.found_location);
            finder = v.findViewById(R.id.finder_name);
        }
    }

    private static String nz(String s, String fb){ return (s==null||s.isEmpty())? fb : s; }
    private static String firstNonNull(String a, String b){ return (a!=null && !a.isEmpty())? a:b; }

    /** relatif/gs:// url dönüşümü */
    private static String normalizeUrl(String raw){
        if (raw==null || raw.isEmpty()) return null;
        if (raw.startsWith("/"))
            return BuildConfig.CF_BASE_URL.replaceAll("/+$","")+raw;
        if (raw.startsWith("gs://")){
            String path = raw.substring(5);
            int i = path.indexOf('/');
            if (i>0){
                String bucket = path.substring(0,i);
                String obj = path.substring(i+1).replace("/", "%2F");
                return "https://firebasestorage.googleapis.com/v0/b/"+bucket+"/o/"+obj+"?alt=media";
            }
        }
        return raw;
    }
}

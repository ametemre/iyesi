package com.kurmez.iyesi.kurmes.ui;

import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.data.Soul;

import java.util.List;

public class SoulsAdapter extends RecyclerView.Adapter<SoulsAdapter.VH> {
    public interface OnSoulAction { void onEdit(Soul s); void onDelete(Soul s); }

    private List<Soul> data;
    private final OnSoulAction cb;

    public SoulsAdapter(List<Soul> data, OnSoulAction cb) { this.data = data; this.cb = cb; }
    public void setData(List<Soul> d) { this.data = d; notifyDataSetChanged(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvTitle, tvSub;
        ImageButton btnEdit, btnDelete;
        VH(View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tvTitle);
            tvSub = v.findViewById(R.id.tvSub);
            btnEdit = v.findViewById(R.id.btnEdit);
            btnDelete = v.findViewById(R.id.btnDelete);
        }
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vType) {
        View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_soul, p, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int i) {
        Soul s = data.get(i);
        h.tvTitle.setText(s.getName() != null ? s.getName() : s.getId());
        String species = s.getSpecies() != null ? s.getSpecies() : "-";
        String health = s.getHealth() != null ? s.getHealth() : "-";
        h.tvSub.setText(species + " • " + health);
        h.btnEdit.setOnClickListener(v -> cb.onEdit(s));
        h.btnDelete.setOnClickListener(v -> cb.onDelete(s));
    }

    @Override public int getItemCount() { return data == null ? 0 : data.size(); }
}
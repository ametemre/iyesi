package com.kurmez.iyesi.social;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kurmez.iyesi.R;

import java.util.ArrayList;
import java.util.List;

public class Messaging extends AppCompatActivity {

    private RecyclerView rvConversations;
    private ConversationAdapter adapter;
    private List<Conversation> conversationList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inbox);

        rvConversations = findViewById(R.id.rvConversations);
        adapter = new ConversationAdapter(conversationList, this);
        rvConversations.setLayoutManager(new LinearLayoutManager(this));
        rvConversations.setAdapter(adapter);

        // Demo data ekle
        conversationList.add(new Conversation("1", "Kaan", null, "Nasılsın?", true));
        conversationList.add(new Conversation("2", "Zeynep", null, "Yarın görüşelim mi?", false));
        conversationList.add(new Conversation("3", "Bot", null, "AI ile mesaj test!", true));
        adapter.notifyDataSetChanged();

        // Swipe işlemleri
        ItemTouchHelper.SimpleCallback swipeCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int pos = viewHolder.getAdapterPosition();
                Conversation convo = conversationList.get(pos);

                if (direction == ItemTouchHelper.RIGHT) {
                    // Sağa kaydırınca: Bluetooth mesajlaşma aç
                    openBluetoothMessaging(convo);
                    adapter.notifyItemChanged(pos); // swipe reset
                }
                // Sola swipe ile silme işlemi clearView içinde yapılacak!
            }

            @Override
            public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
                // Sola swipe için 2/3 (0.66)
                return 0.66f;
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY,
                                    int actionState, boolean isCurrentlyActive) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
                    // Sola swipe: kırmızıya kademeli geçiş
                    float width = viewHolder.itemView.getWidth();
                    float progress = Math.min(-dX / width, 1f);
                    int red = (int) (255 * progress);
                    int green = (int) (255 * (1 - progress));
                    int blue = (int) (255 * (1 - progress));
                    int bgColor = Color.rgb(red, green, blue);

                    viewHolder.itemView.setBackgroundColor(bgColor);

                    // 2/3'ü geçince tam kırmızı
                    if (progress >= 0.66f) {
                        viewHolder.itemView.setBackgroundColor(Color.RED);
                    }
                } else {
                    viewHolder.itemView.setBackgroundColor(Color.WHITE);
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                float width = viewHolder.itemView.getWidth();
                float translationX = viewHolder.itemView.getTranslationX();
                float progress = Math.abs(translationX) / width;
                int pos = viewHolder.getAdapterPosition();

                if (translationX < 0 && progress >= 0.66f && pos != RecyclerView.NO_POSITION) {
                    // SİLME İŞLEMİ
                    conversationList.remove(pos);
                    adapter.notifyItemRemoved(pos);
                    // CloudFunctions üzerinden de silme işlemi tetikle
                } else {
                    // Swipe tamamlanmadı, resetle
                    viewHolder.itemView.setBackgroundColor(Color.WHITE);
                    adapter.notifyItemChanged(pos);
                }
            }
        };

        new ItemTouchHelper(swipeCallback).attachToRecyclerView(rvConversations);
    }

    // Demo - Bluetooth mesajlaşma aç (gerçekte kendi fonksiyonunu çağırabilirsin)
    private void openBluetoothMessaging(Conversation convo) {
        // Örnek: yeni bir activity başlat
        // Intent intent = new Intent(this, BluetoothChatActivity.class);
        // intent.putExtra("userId", convo.userId);
        // startActivity(intent);
    }

    // Demo - CloudFunctions ile mesajlaşma aç (onConversationClick'te)
    private void openCloudMessaging(Conversation convo) {
        // Intent intent = new Intent(this, MessagingDetailActivity.class);
        // intent.putExtra("userId", convo.userId);
        // startActivity(intent);
    }

    // Adapter
    static class ConversationAdapter extends RecyclerView.Adapter<ConversationAdapter.ViewHolder> {
        private List<Conversation> items;
        private Context context;

        public ConversationAdapter(List<Conversation> items, Context context) {
            this.items = items;
            this.context = context;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Item layoutunda reusable header kullanıyoruz ve altına mesaj preview ekliyoruz
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.layout_message_header, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Conversation c = items.get(position);
            holder.tvUsername.setText(c.username);
            holder.tvLastMessage.setText(c.lastMessage);

            // Profil resmi için Glide veya Picasso önerilir
            // Glide.with(holder.itemView).load(c.profileUrl).into(holder.ivProfile);

            // Okunmamış mesaj badge’i göstermek istiyorsan buraya ekleyebilirsin

            holder.itemView.setOnClickListener(v -> {
                if (context instanceof Messaging) {
                    ((Messaging) context).openCloudMessaging(c);
                }
            });
            holder.btnMore.setOnClickListener(v -> showPopupMenu(v, c));
        }

        @Override
        public int getItemCount() { return items.size(); }

        // ViewHolder (header + last message)
        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivProfile;
            TextView tvUsername, tvLastMessage;
            ImageButton btnMore;
            ViewHolder(View v) {
                super(v);
                ivProfile = v.findViewById(R.id.ivProfile);
                tvUsername = v.findViewById(R.id.tvUsername);
                btnMore = v.findViewById(R.id.btnMore);
                tvLastMessage = v.findViewById(R.id.tvLastMessage);
            }
        }

        private void showPopupMenu(View anchor, Conversation c) {
            PopupMenu popup = new PopupMenu(context, anchor);
            MenuInflater inflater = popup.getMenuInflater();
            inflater.inflate(R.menu.menu_messaging_options, popup.getMenu());
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_block) {
                    // Engelle
                    return true;
                } else if (id == R.id.action_mute) {
                    // Sessize al
                    return true;
                } else if (id == R.id.action_delete) {
                    // Mesajları sil
                    return true;
                } else if (id == R.id.action_follow) {
                    // Takip et/bırak
                    return true;
                }
                return false;
            });
        }
    }

    // Model
    static class Conversation {
        String userId;
        String username;
        String profileUrl;
        String lastMessage;
        boolean hasUnread;

        Conversation(String userId, String username, String profileUrl, String lastMessage, boolean hasUnread) {
            this.userId = userId;
            this.username = username;
            this.profileUrl = profileUrl;
            this.lastMessage = lastMessage;
            this.hasUnread = hasUnread;
        }
    }
}

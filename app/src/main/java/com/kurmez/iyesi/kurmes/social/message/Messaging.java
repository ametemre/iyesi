package com.kurmez.iyesi.kurmes.social.message;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.ProfileActivity;
import com.kurmez.iyesi.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.firebase.functions.FirebaseFunctions;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.PrivateCom;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;
import com.kurmez.iyesi.kurmes.utilities.helper.HeaderHelper;

import okhttp3.OkHttpClient;
import okhttp3.Request;

public class Messaging extends AppCompatActivity {
    private static final String CF_ALL_USERS = "https://us-central1-iyesi-e8d4f.cloudfunctions.net/listAllUsersHttp";
    private ProfileActivity profileActivity;
    private FirebaseFunctions functions;
    private RecyclerView rvConversations;
    private ConversationAdapter adapter;
    private List<Conversation> conversationList = new ArrayList<>();
    private FirebaseAuth auth;
    private FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
    private CFHelper cf;
    private HeaderHelper headerHelper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request req = chain.request();
        Log.d("HTTP-REQ", req.method() + " " + req.url());
        for (String name : req.headers().names()) {
            Log.d("HTTP-REQ", name + ": " + req.header(name));
        }
        return chain.proceed(req);
    }).build();
    @RequiresPermission(allOf = {Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inbox);
        headerHelper = new HeaderHelper(Messaging.this);
        if (user == null || user.isAnonymous()) {
            Toast.makeText(this, "Devam etmek için giriş yapmalısınız.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, Login.class));
            finish();
            return;
        }
        auth = FirebaseAuth.getInstance();
        cf = new CFHelper(this, "iyesi-e8d4f","us-central1", new CFHelper.Listener(){});
        rvConversations = findViewById(R.id.rvConversations);
        adapter = new ConversationAdapter(conversationList, this);
        rvConversations.setLayoutManager(new LinearLayoutManager(this));
        rvConversations.setAdapter(adapter);

        user = auth.getCurrentUser();
        if (user == null || user.isAnonymous()) {
            Toast.makeText(this, "Devam etmek için giriş yapmalısınız.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, Login.class));
            finish();
            return;
        }
        headerHelper.refreshHeader(Messaging.this);

        conversationList.clear();
        FirebaseAuth.getInstance().getCurrentUser().getIdToken(true);
        // Rolü (sunucudan) tazele ve UI kapısını uygula
        new Thread(() -> {
            Log.i("ThreadBaşladı", "Role: ... ");
            cf.refreshRole(role -> {
                Log.i("CustomClaims", "Role: " + role);
                // UI kapısı aynı kalsın...
                if (Objects.equals(role, "Ülgen") || Objects.equals(role, "Tengri")) {
                    runOnUiThread(() -> {
                        FirebaseFunctions.getInstance()
                                .getHttpsCallable("listAllUsers")
                                .call(new HashMap<String, Object>() {{
                                    put("limit", 200);
                                    put("pageToken", null);
                                }})
                                .addOnSuccessListener(result -> {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> data = (Map<String, Object>) result.getData();
                                    @SuppressWarnings("unchecked")
                                    List<Map<String, Object>> users =
                                            (List<Map<String, Object>>) data.get("users");

                                    conversationList.clear();
                                    for (Map<String, Object> u : users) {
                                        String uid = (String) u.get("uid");
                                        String username = (String) u.getOrDefault("username", uid);
                                        String email = (String) u.get("email");
                                        String avatar = (String) u.get("avatarUrl");
                                        conversationList.add(new Conversation(
                                                uid, username, avatar, email, false
                                        ));
                                    }
                                    adapter.notifyDataSetChanged();
                                    Log.d("CustomClaims", "Role: " + role);
                                })
                                .addOnFailureListener(e -> {
                                    Log.e("Messaging", "Kullanıcı listesi hatası", e);
                                    Helpers.showToastSafe(Messaging.this, "Kullanıcı listesi alınamadı");
                                });
                    });
                } else {
                    Toast.makeText(this, "Bu işlemi sadece Ülgen ve Tengri yapabilir.", Toast.LENGTH_LONG).show();
                }

            }); // CFHelper içinde HTTP POST /getRole çağrısı
        }).start();
        // Swipe işlemleri
        ItemTouchHelper.SimpleCallback swipeCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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
        Helpers.ConversationHeaderHelper.setupHeader(this, R.menu.menu_message_options, item -> {
            if (item.getItemId() == R.id.blueTooth) {
                PrivateCom btHelper = new PrivateCom();
                btHelper.enableBluetoothAndMakeDiscoverable(Messaging.this, 120); // 2 dakika görünür
                Toast.makeText(this, "Bluetooth aktif ve görünür hale geldi", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (item.getItemId() == R.id.edit_profile) {
                profileActivity = new ProfileActivity();
                profileActivity.launchForEdit(this, true);
                Toast.makeText(this, "Profil Düzenleniyor", Toast.LENGTH_SHORT).show();
                //profileActivity.launchProfile(this);
                Toast.makeText(this, "Profil Düzenleniyor", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
        new ItemTouchHelper(swipeCallback).attachToRecyclerView(rvConversations);

    }
    // Demo - Bluetooth mesajlaşma aç (gerçekte kendi fonksiyonunu çağırabilirsin)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void openBluetoothMessaging(Conversation convo) {
        try {
            PrivateCom.connectToBluetoothDevice(this, () -> {
                Intent intent = new Intent(this, Message.class);
                intent.putExtra(Message.EXTRA_MODE, Message.MODE_BLUETOOTH);
                intent.putExtra(Message.EXTRA_TARGET_USER_ID, convo.userId);
                intent.putExtra(Message.EXTRA_TARGET_USER_NAME, convo.username);
                startActivity(intent);
            });
        } catch (Exception e) {
            Log.d("Connection Failed",e.getMessage());
        }
    }

    // Demo - CloudFunctions ile mesajlaşma aç (onConversationClick'te)
    private void openCloudMessaging(Conversation convo) {
        Intent intent = new Intent(this, Message.class);
        intent.putExtra(Message.EXTRA_MODE, Message.MODE_BLOCKCHAIN);
        if (convo.userId!=null) {
            intent.putExtra(Message.EXTRA_TARGET_USER_ID, convo.userId);
        }
        if (convo.username!=null) {
            intent.putExtra(Message.EXTRA_TARGET_USER_NAME, convo.username);
        }
        startActivity(intent);
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
            // ➊ artık item_conversation.xml
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_conversation, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Conversation c = items.get(position);
            Log.d("Adapter", "Bind position: " + position + " / username: " + items.get(position).username);
            holder.tvUsername.setText(c.username);
            holder.tvLastMessage.setText(c.lastMessage);
            // Profil resmi için Glide/Picasso kullanabilirsiniz:
            // Glide.with(holder.ivProfile).load(c.profileUrl).into(holder.ivProfile);

            holder.itemView.setOnClickListener(v ->
                    ((Messaging) context).openCloudMessaging(c)
            );
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
    // Swipe callback (değişmedi)
    private final ItemTouchHelper.SimpleCallback swipeCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            return false;
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            int pos = viewHolder.getAdapterPosition();
            if (direction == ItemTouchHelper.LEFT) {
                // sola kaydırınca sil
                conversationList.remove(pos);
                adapter.notifyItemRemoved(pos);
            } else {
                // sağa kaydırınca mesajlaşma aç, sonra resetle
                Conversation convo = conversationList.get(pos);
                openBluetoothMessaging(convo);
                adapter.notifyItemChanged(pos);
            }
        }
        @Override public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder vh){
            return 0.66f;
        }
        @Override
        public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
            View item = viewHolder.itemView;
            float width = item.getWidth();
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                float progress = Math.min(Math.abs(dX) / width, 1f);
                if (dX < 0) {
                    // beyaz → kırmızı
                    int red = (int) (255 * progress);
                    int gb  = (int) (255 * (1 - progress));
                    item.setBackgroundColor(Color.rgb(red, gb, gb));
                } else {
                    // beyaz → mavi
                    int blue = (int) (255 * progress);
                    int rg   = (int) (255 * (1 - progress));
                    item.setBackgroundColor(Color.rgb(rg, rg, blue));
                }
            } else {
                item.setBackgroundColor(Color.WHITE);
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            // her zaman temizle, silme zaten onSwiped'te yapıldı
            viewHolder.itemView.setBackgroundColor(Color.WHITE);
        }
    };
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

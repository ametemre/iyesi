package com.kurmez.iyesi.kurmes.social.message;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.PrivateCom;

import java.util.ArrayList;
import java.util.List;

public class Message extends AppCompatActivity {
    public static final String EXTRA_MODE = "mode"; // "bluetooth" veya "blockchain"
    public static final String MODE_BLUETOOTH = "bluetooth";
    public static final String MODE_BLOCKCHAIN = "blockchain";
    public static final String EXTRA_TARGET_USER_ID = "targetUserId";
    public static final String EXTRA_TARGET_USER_NAME = "targetUserName";

    private RecyclerView rvMessages;
    private EditText etMessage;
    private ImageButton btnSend;
    private MessageAdapter adapter;
    private List<MessageItem> messageList = new ArrayList<>();

    private String mode;
    private String targetUserId;
    private String targetUserName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_messaging);

        // ➊ Intent’ten modu ve muhatabı al
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_BLOCKCHAIN;
        targetUserId   = getIntent().getStringExtra(EXTRA_TARGET_USER_ID);
        targetUserName = getIntent().getStringExtra(EXTRA_TARGET_USER_NAME);

        // ➋ Header’daki kullanıcı adını set et
        TextView headerName = findViewById(R.id.tvUsername);
        //headerName.setText(targetUserName != null ? targetUserName : "Konuşma");

        // ➌ View’ları bağla
        rvMessages = findViewById(R.id.rvMessages);
        etMessage  = findViewById(R.id.etMessage);
        btnSend    = findViewById(R.id.btnSend);

        // ➍ RecyclerView & Adapter kurulumu
        rvMessages.setNestedScrollingEnabled(false);
        adapter = new MessageAdapter(messageList);
        rvMessages.setLayoutManager(new LinearLayoutManager(this));
        rvMessages.setAdapter(adapter);

        // ➎ Gönder butonu
        btnSend.setOnClickListener(v -> {
            String text = etMessage.getText().toString().trim();
            if (text.isEmpty()) return;
            etMessage.setText("");

            // ➏ önce UI’a ekle
            MessageItem outgoing = new MessageItem(text, true /*giden*/);
            addMessage(outgoing);

            // ➐ ardından ilgili kanala yolla
            if (MODE_BLUETOOTH.equals(mode)) {
                sendBluetoothMessage(text);
            } else {
                sendBlockchainMessage(text);
            }
        });
        Helpers.ConversationHeaderHelper.setupHeader(this, R.menu.menu_messaging_options, item -> {
            int id = item.getItemId();

            if (id == R.id.action_block) {
                Toast.makeText(this, "Engellendi", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_mute) {
                Toast.makeText(this, "Sessize alındı", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_delete) {
                Toast.makeText(this, "Silindi", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_follow) {
                Toast.makeText(this, "Takip işlemi", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
        // TODO: Gelen mesajları dinleyip onMessageReceived(...) ile UI’a yansıt
    }

    /** Bluetooth ile mesaj gönderme stub’u */
    private void sendBluetoothMessage(String text) {
        PrivateCom.sendResponseToBluetoothDevice(this, text);
        // TODO: Bluetooth kanalından yolla,
        //       gelen cevabı onMessageReceived(...) ile al.
    }

    /** Blockchain (CF) ile mesaj gönderme stub’u */
    private void sendBlockchainMessage(String text) {
        // TODO: Cloud Functions veya kendi chain’inize yolla,
        //       yanıtı onMessageReceived(...) ile al.
    }

    /** Gelen mesaj geldiğinde UI’a ekle */
    private void onMessageReceived(MessageItem incoming) {
        runOnUiThread(() -> addMessage(incoming));
    }

    /** Listeye bir item ekle ve aşağı kaydır */
    private void addMessage(MessageItem msg) {
        messageList.add(msg);
        adapter.notifyItemInserted(messageList.size() - 1);
        rvMessages.scrollToPosition(messageList.size() - 1);
    }

    // ——————————————————————————
    // Basit mesaj modeli
    static class MessageItem {
        String text;
        boolean isSent;   // true = giden, false = gelen
        // ileride timestamp, senderId vb. eklenebilir

        MessageItem(String text, boolean isSent) {
            this.text   = text;
            this.isSent = isSent;
        }
    }

    // ——————————————————————————
    // RecyclerView Adapter
    static class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {
        private final List<MessageItem> items;
        MessageAdapter(List<MessageItem> items) { this.items = items; }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).isSent ? R.layout.item_message_outgoing : R.layout.item_message_incoming;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(viewType, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int pos) {
            holder.tv.setText(items.get(pos).text);
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv;
            VH(View v) {
                super(v);
                tv = v.findViewById(R.id.tvMessage);
            }
        }
    }
}

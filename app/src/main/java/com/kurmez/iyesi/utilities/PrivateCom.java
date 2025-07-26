package com.kurmez.iyesi.utilities;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Handler;
import android.widget.Toast;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

public class PrivateCom {
    private static BluetoothSocket bluetoothSocket = null;
    private static BluetoothDevice targetDevice = null;
    private static final String TARGET_DEVICE_NAME = "IYE_DEVICE"; // Hedef cihazın Bluetooth ismini buraya yaz!
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // SPP UUID
    public static final int REQUEST_ENABLE_BT = 2001;
    public static final int REQUEST_DISCOVERABLE_BT = 2002;
    private static Handler handler = new Handler();

    // Bluetooth’u aç ve cihazı bulunabilir yap
    public static void enableBluetoothAndMakeDiscoverable(Activity activity, int discoverableSec) {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            showToast(activity, "Bluetooth desteklenmiyor");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            makeDeviceDiscoverable(activity, discoverableSec);
        }
    }

    // Cihazı bulunabilir moda al
    public static void makeDeviceDiscoverable(Activity activity, int seconds) {
        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, seconds);
        activity.startActivityForResult(discoverableIntent, REQUEST_DISCOVERABLE_BT);
    }

    // Hedef Bluetooth cihaza bağlan
    public static void connectToBluetoothDevice(Activity activity) {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            showToast(activity, "Bluetooth kapalı veya yok");
            return;
        }
        // Eşleşmiş cihazlar arasında ara
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        targetDevice = null;
        for (BluetoothDevice device : pairedDevices) {
            if (TARGET_DEVICE_NAME.equals(device.getName())) {
                targetDevice = device;
                break;
            }
        }
        if (targetDevice == null) {
            showToast(activity, "Hedef Bluetooth cihazı bulunamadı!");
            return;
        }
        // Socket üzerinden bağlan
        new Thread(() -> {
            try {
                bluetoothSocket = targetDevice.createRfcommSocketToServiceRecord(MY_UUID);
                bluetoothSocket.connect();
                activity.runOnUiThread(() -> showToast(activity, "Bluetooth cihazına bağlandı!"));
            } catch (IOException e) {
                activity.runOnUiThread(() -> showToast(activity, "Bluetooth bağlantı hatası: " + e.getMessage()));
            }
        }).start();
    }

    // Bağlı cihaza veri gönder
    public static void sendResponseToBluetoothDevice(Activity activity, String response) {
        if (bluetoothSocket == null || !bluetoothSocket.isConnected()) {
            showToast(activity, "Bluetooth bağlantısı yok!");
            return;
        }
        new Thread(() -> {
            try {
                bluetoothSocket.getOutputStream().write(response.getBytes());
                bluetoothSocket.getOutputStream().flush();
                activity.runOnUiThread(() -> showToast(activity, "Cevap Bluetooth ile gönderildi!"));
            } catch (IOException e) {
                activity.runOnUiThread(() -> showToast(activity, "Bluetooth gönderim hatası: " + e.getMessage()));
            }
        }).start();
    }

    // Bluetooth bağlantısını kapat
    public static void disconnect() {
        if (bluetoothSocket != null) {
            try {
                bluetoothSocket.close();
            } catch (IOException ignored) {}
            bluetoothSocket = null;
        }
        targetDevice = null;
    }

    // Kullanışlı toast fonksiyonu
    private static void showToast(Activity activity, String msg) {
        handler.post(() -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
    }
}

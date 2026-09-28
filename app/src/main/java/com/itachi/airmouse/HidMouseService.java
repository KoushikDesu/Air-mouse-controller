package com.itachi.airmouse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HidMouseService extends Service implements SensorEventListener {

    public static final String ACTION_STATE_CHANGED = "com.itachi.airmouse.ACTION_STATE_CHANGED";
    public static final String ACTION_GYRO_UPDATE = "com.itachi.airmouse.ACTION_GYRO_UPDATE";
    public static final String EXTRA_STATE = "extra_state";
    public static final String EXTRA_GYRO_MAG = "extra_gyro_mag";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_ADVERTISING = 1;
    public static final int STATE_CONNECTED = 2;

    public static volatile boolean isAirActive = true;
    public static volatile float sensitivityMultiplier = 1.0f;
    public static volatile int currentButtonsState = 0;

    public static final byte CONSUMER_VOL_UP     = (byte) 0x01;
    public static final byte CONSUMER_VOL_DOWN   = (byte) 0x02;
    public static final byte CONSUMER_MUTE       = (byte) 0x04;
    public static final byte CONSUMER_PLAY_PAUSE = (byte) 0x08;
    public static final byte CONSUMER_NEXT       = (byte) 0x10;
    public static final byte CONSUMER_PREV       = (byte) 0x20;
    public static final byte CONSUMER_BRIGHT_UP  = (byte) 0x40;
    public static final byte CONSUMER_BRIGHT_DOWN= (byte) 0x80;

    private static HidMouseService instance = null;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice connectedDevice;
    private BluetoothLeAdvertiser bleAdvertiser;
    private AdvertiseCallback bleCallback;

    private SensorManager sensorManager;
    private Sensor gyroSensor;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private long lastReportTime = 0L;
    private long lastUiNotifyTime = 0L;
    private float smoothX = 0f;
    private float smoothY = 0f;

    public static final byte[] HID_REPORT_DESCRIPTOR = new byte[] {
        // --- MOUSE (Report ID 1) ---
        (byte) 0x05, (byte) 0x01,       // USAGE_PAGE (Generic Desktop)
        (byte) 0x09, (byte) 0x02,       // USAGE (Mouse)
        (byte) 0xA1, (byte) 0x01,       // COLLECTION (Application)
        (byte) 0x85, (byte) 0x01,       //   REPORT_ID (1)
        (byte) 0x09, (byte) 0x01,       //   USAGE (Pointer)
        (byte) 0xA1, (byte) 0x00,       //   COLLECTION (Physical)
        (byte) 0x05, (byte) 0x09,       //     USAGE_PAGE (Button)
        (byte) 0x19, (byte) 0x01,       //     USAGE_MINIMUM (Button 1)
        (byte) 0x29, (byte) 0x03,       //     USAGE_MAXIMUM (Button 3)
        (byte) 0x15, (byte) 0x00,       //     LOGICAL_MINIMUM (0)
        (byte) 0x25, (byte) 0x01,       //     LOGICAL_MAXIMUM (1)
        (byte) 0x95, (byte) 0x03,       //     REPORT_COUNT (3)
        (byte) 0x75, (byte) 0x01,       //     REPORT_SIZE (1)
        (byte) 0x81, (byte) 0x02,       //     INPUT (Data,Var,Abs)
        (byte) 0x95, (byte) 0x01,       //     REPORT_COUNT (1)
        (byte) 0x75, (byte) 0x05,       //     REPORT_SIZE (5)
        (byte) 0x81, (byte) 0x03,       //     INPUT (Cnst,Var,Abs) ; padding
        (byte) 0x05, (byte) 0x01,       //     USAGE_PAGE (Generic Desktop)
        (byte) 0x09, (byte) 0x30,       //     USAGE (X)
        (byte) 0x09, (byte) 0x31,       //     USAGE (Y)
        (byte) 0x09, (byte) 0x38,       //     USAGE (Wheel)
        (byte) 0x15, (byte) 0x81,       //     LOGICAL_MINIMUM (-127)
        (byte) 0x25, (byte) 0x7F,       //     LOGICAL_MAXIMUM (127)
        (byte) 0x75, (byte) 0x08,       //     REPORT_SIZE (8)
        (byte) 0x95, (byte) 0x03,       //     REPORT_COUNT (3)
        (byte) 0x81, (byte) 0x06,       //     INPUT (Data,Var,Rel)
        (byte) 0xC0,                    //   END_COLLECTION
        (byte) 0xC0,                    // END_COLLECTION

        // --- CONSUMER CONTROL (Report ID 2: Volume, Brightness, Media) ---
        (byte) 0x05, (byte) 0x0C,       // USAGE_PAGE (Consumer Devices)
        (byte) 0x09, (byte) 0x01,       // USAGE (Consumer Control)
        (byte) 0xA1, (byte) 0x01,       // COLLECTION (Application)
        (byte) 0x85, (byte) 0x02,       //   REPORT_ID (2)
        (byte) 0x15, (byte) 0x00,       //   LOGICAL_MINIMUM (0)
        (byte) 0x25, (byte) 0x01,       //   LOGICAL_MAXIMUM (1)
        (byte) 0x75, (byte) 0x01,       //   REPORT_SIZE (1)
        (byte) 0x95, (byte) 0x08,       //   REPORT_COUNT (8)
        (byte) 0x09, (byte) 0xE9,       //   USAGE (Volume Up)       - bit 0
        (byte) 0x09, (byte) 0xEA,       //   USAGE (Volume Down)     - bit 1
        (byte) 0x09, (byte) 0xE2,       //   USAGE (Mute)            - bit 2
        (byte) 0x09, (byte) 0xCD,       //   USAGE (Play/Pause)      - bit 3
        (byte) 0x09, (byte) 0xB5,       //   USAGE (Scan Next Track) - bit 4
        (byte) 0x09, (byte) 0xB6,       //   USAGE (Scan Prev Track) - bit 5
        (byte) 0x09, (byte) 0x6F,       //   USAGE (Brightness Inc)  - bit 6
        (byte) 0x09, (byte) 0x70,       //   USAGE (Brightness Dec)  - bit 7
        (byte) 0x81, (byte) 0x02,       //   INPUT (Data,Var,Abs)
        (byte) 0xC0                     // END_COLLECTION
    };

    public static void sendButtonState(int buttonMask, boolean pressed) {
        if (pressed) {
            currentButtonsState |= buttonMask;
        } else {
            currentButtonsState &= ~buttonMask;
        }
        if (instance != null) {
            instance.sendMouseReport(currentButtonsState, 0, 0, 0);
        }
    }

    public static void sendClick(int buttonMask) {
        if (instance != null) {
            int pressedState = currentButtonsState | buttonMask;
            instance.sendMouseReport(pressedState, 0, 0, 0);
            instance.executor.execute(() -> {
                try { Thread.sleep(30); } catch (Exception ignored) {}
                if (instance != null) {
                    instance.sendMouseReport(currentButtonsState, 0, 0, 0);
                }
            });
        }
    }

    public static void sendScroll(int wheel) {
        if (instance != null) {
            int clamped = Math.max(-127, Math.min(127, wheel));
            instance.sendMouseReport(currentButtonsState, 0, 0, clamped);
        }
    }

    public static void sendTouchMove(float dx, float dy) {
        if (instance != null) {
            int moveX = Math.max(-127, Math.min(127, (int) (dx * sensitivityMultiplier * 1.5f)));
            int moveY = Math.max(-127, Math.min(127, (int) (dy * sensitivityMultiplier * 1.5f)));
            instance.sendMouseReport(currentButtonsState, moveX, moveY, 0);
        }
    }

    public static void sendConsumerKey(byte keyMask) {
        if (instance != null) {
            instance.sendConsumerReport(keyMask);
            instance.executor.execute(() -> {
                try { Thread.sleep(30); } catch (Exception ignored) {}
                if (instance != null) {
                    instance.sendConsumerReport((byte) 0);
                }
            });
        }
    }

    public static boolean connectHost(BluetoothDevice device) {
        if (instance != null && instance.hidDevice != null && device != null) {
            try {
                return instance.hidDevice.connect(device);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    public static void restartAdvertising() {
        if (instance != null) {
            instance.startSwiftPairBle();
        }
    }

    public static BluetoothDevice getConnectedDevice() {
        if (instance != null) {
            return instance.connectedDevice;
        }
        return null;
    }

    private final BroadcastReceiver bluetoothEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (dev != null && hidDevice != null) {
                    try {
                        hidDevice.connect(dev);
                    } catch (Exception ignored) {}
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (state == BluetoothDevice.BOND_BONDED) {
                    BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (dev != null && hidDevice != null) {
                        try {
                            hidDevice.connect(dev);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        startForegroundNotification();

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gyroSensor == null) {
                gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            }
            if (gyroSensor != null) {
                sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST);
            }
        }

        IntentFilter btFilter = new IntentFilter();
        btFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        btFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(bluetoothEventReceiver, btFilter);

        if (bluetoothAdapter != null) {
            bluetoothAdapter.getProfileProxy(getApplicationContext(), profileListener, BluetoothProfile.HID_DEVICE);
            startSwiftPairBle();
        }
    }

    private void startForegroundNotification() {
        String channelId = "air_mouse_service";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId,
                "Air Mouse Service",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, channelId)
            .setContentTitle("Air Mouse Pro")
            .setContentText("Bluetooth Air Mouse active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build();

        startForeground(1001, notification);
    }

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = (BluetoothHidDevice) proxy;
                registerHidApp();
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null;
                connectedDevice = null;
                broadcastState(STATE_DISCONNECTED);
            }
        }
    };

    private void registerHidApp() {
        // Registering with SUBCLASS1_MOUSE tells Windows directly that this is a Mouse peripheral
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
            "Air Mouse Pro",
            "Bluetooth Gyro Air Mouse",
            "Itachi",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            HID_REPORT_DESCRIPTOR
        );

        try {
            if (hidDevice != null) {
                hidDevice.registerApp(sdp, null, null, executor, hidCallback);
                broadcastState(STATE_ADVERTISING);
            }
        } catch (SecurityException e) {
            e.printStackTrace();
            broadcastState(STATE_DISCONNECTED);
        }
    }

    public void startSwiftPairBle() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        try {
            bleAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            if (bleAdvertiser == null) return;

            if (bleCallback != null) {
                try {
                    bleAdvertiser.stopAdvertising(bleCallback);
                } catch (Exception ignored) {}
            }

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();

            // 1. Primary Packet: Under 31 bytes limit!
            // Microsoft Company ID = 0x0006, 0x03 = Swift Pair, 0x00 = Pair Prompt, 0x80 = RSSI
            AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addManufacturerData(0x0006, new byte[] { 0x03, 0x00, (byte) 0x80 })
                .build();

            // 2. Scan Response: Holds device name so it shows on laptop screen
            AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();

            bleCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {}
                @Override
                public void onStartFailure(int errorCode) {
                    try {
                        AdvertiseData fallback = new AdvertiseData.Builder()
                            .setIncludeDeviceName(true)
                            .build();
                        bleAdvertiser.startAdvertising(settings, fallback, new AdvertiseCallback() {});
                    } catch (Exception ignored) {}
                }
            };

            bleAdvertiser.startAdvertising(settings, data, scanResponse, bleCallback);
        } catch (Exception ignored) {}
    }

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
            if (registered) {
                broadcastState(STATE_ADVERTISING);
                if (pluggedDevice != null) {
                    try {
                        hidDevice.connect(pluggedDevice);
                    } catch (Exception ignored) {}
                } else {
                    autoConnectBondedLaptop();
                }
            } else {
                broadcastState(STATE_DISCONNECTED);
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device;
                broadcastState(STATE_CONNECTED);
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                broadcastState(STATE_ADVERTISING);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice != null && connectedDevice.equals(device)) {
                    connectedDevice = null;
                }
                broadcastState(STATE_ADVERTISING);
            }
        }
    };

    private void autoConnectBondedLaptop() {
        if (bluetoothAdapter == null || hidDevice == null) return;
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice dev : bonded) {
                    BluetoothClass bc = dev.getBluetoothClass();
                    if (bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER) {
                        hidDevice.connect(dev);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void broadcastState(int state) {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.putExtra(EXTRA_STATE, state);
        sendBroadcast(intent);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isAirActive) {
            smoothX = 0f;
            smoothY = 0f;
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            float rawGyroX = -event.values[2];
            float rawGyroY = -event.values[0];

            float mag = (float) Math.hypot(rawGyroX, rawGyroY);
            long now = System.currentTimeMillis();
            if (now - lastUiNotifyTime >= 120) {
                Intent uiIntent = new Intent(ACTION_GYRO_UPDATE);
                uiIntent.putExtra(EXTRA_GYRO_MAG, mag);
                sendBroadcast(uiIntent);
                lastUiNotifyTime = now;
            }

            float deadZone = 0.035f;
            float filteredX = (Math.abs(rawGyroX) > deadZone) ? (rawGyroX - Math.copySign(deadZone, rawGyroX)) : 0f;
            float filteredY = (Math.abs(rawGyroY) > deadZone) ? (rawGyroY - Math.copySign(deadZone, rawGyroY)) : 0f;

            float alpha = 0.50f;
            smoothX = alpha * filteredX + (1f - alpha) * smoothX;
            smoothY = alpha * filteredY + (1f - alpha) * smoothY;

            if (now - lastReportTime >= 8) { // 125 Hz
                float scale = 40f * sensitivityMultiplier;
                int dx = Math.max(-127, Math.min(127, (int) (smoothX * scale)));
                int dy = Math.max(-127, Math.min(127, (int) (smoothY * scale)));

                if (dx != 0 || dy != 0) {
                    sendMouseReport(currentButtonsState, dx, dy, 0);
                }
                lastReportTime = now;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void sendMouseReport(int buttons, int dx, int dy, int wheel) {
        BluetoothDevice dev = connectedDevice;
        if (dev == null && hidDevice != null) {
            List<BluetoothDevice> devs = hidDevice.getConnectedDevices();
            if (devs != null && !devs.isEmpty()) {
                dev = devs.get(0);
                connectedDevice = dev;
            } else if (bluetoothAdapter != null) {
                try {
                    for (BluetoothDevice b : bluetoothAdapter.getBondedDevices()) {
                        if (hidDevice.getConnectionState(b) == BluetoothProfile.STATE_CONNECTED) {
                            dev = b;
                            connectedDevice = dev;
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        if (dev == null || hidDevice == null) return;

        byte[] report = new byte[] {
            (byte) buttons,
            (byte) dx,
            (byte) dy,
            (byte) wheel
        };

        try {
            hidDevice.sendReport(dev, 1, report);
        } catch (Exception ignored) {}
    }

    private void sendConsumerReport(byte keyMask) {
        BluetoothDevice dev = connectedDevice;
        if (dev == null && hidDevice != null) {
            List<BluetoothDevice> devs = hidDevice.getConnectedDevices();
            if (devs != null && !devs.isEmpty()) {
                dev = devs.get(0);
                connectedDevice = dev;
            } else if (bluetoothAdapter != null) {
                try {
                    for (BluetoothDevice b : bluetoothAdapter.getBondedDevices()) {
                        if (hidDevice.getConnectionState(b) == BluetoothProfile.STATE_CONNECTED) {
                            dev = b;
                            connectedDevice = dev;
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        if (dev == null || hidDevice == null) return;

        byte[] report = new byte[] { keyMask };

        try {
            hidDevice.sendReport(dev, 2, report);
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        try {
            unregisterReceiver(bluetoothEventReceiver);
        } catch (Exception ignored) {}
        try {
            if (bleAdvertiser != null && bleCallback != null) {
                bleAdvertiser.stopAdvertising(bleCallback);
            }
            if (hidDevice != null) {
                hidDevice.unregisterApp();
            }
            if (bluetoothAdapter != null) {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
            }
        } catch (Exception ignored) {}
        executor.shutdownNow();
        instance = null;
        broadcastState(STATE_DISCONNECTED);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

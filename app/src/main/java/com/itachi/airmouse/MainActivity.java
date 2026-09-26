package com.itachi.airmouse;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatusText;
    private TextView tvGyroIndicator;
    private View viewStatusDot;
    private Button btnMakeDiscoverable;
    private Button btnConnectPaired;
    private BluetoothDevice pairedLaptopDevice = null;
    private Button btnToggleService;
    private TextView tvSensitivityLabel;
    private SeekBar seekBarSensitivity;
    private CheckBox cbPauseGyro;
    private FrameLayout padAirClutch;
    private FrameLayout padScrollStrip;

    private boolean isServiceRunning = false;
    private Vibrator vibrator;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (HidMouseService.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(HidMouseService.EXTRA_STATE, HidMouseService.STATE_DISCONNECTED);
                updateUiState(state);
            } else if (HidMouseService.ACTION_GYRO_UPDATE.equals(intent.getAction())) {
                float mag = intent.getFloatExtra(HidMouseService.EXTRA_GYRO_MAG, 0f);
                if (HidMouseService.isAirActive) {
                    if (mag > 0.05f) {
                        tvGyroIndicator.setText("Gyro: Motion detected (" + String.format("%.2f", mag) + " rad/s)");
                        tvGyroIndicator.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_green));
                    } else {
                        tvGyroIndicator.setText("Gyro: Active (Auto-tracking)");
                        tvGyroIndicator.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.text_muted));
                    }
                } else {
                    tvGyroIndicator.setText("Gyro: Paused");
                    tvGyroIndicator.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_amber));
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initVibrator();
        bindViews();
        setupListeners();
        requestPermissionsIfNeeded();

        // Auto-start air mouse service on app launch
        startAirMouseService();
        updatePairedDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePairedDevices();
        IntentFilter filter = new IntentFilter();
        filter.addAction(HidMouseService.ACTION_STATE_CHANGED);
        filter.addAction(HidMouseService.ACTION_GYRO_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {}
    }

    private void initVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) {
                vibrator = vm.getDefaultVibrator();
            }
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    private void triggerHaptic(long durationMs) {
        if (vibrator == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(durationMs);
        }
    }

    private void bindViews() {
        tvStatusText = findViewById(R.id.tvStatusText);
        tvGyroIndicator = findViewById(R.id.tvGyroIndicator);
        viewStatusDot = findViewById(R.id.viewStatusDot);
        btnMakeDiscoverable = findViewById(R.id.btnMakeDiscoverable);
        btnConnectPaired = findViewById(R.id.btnConnectPaired);
        btnToggleService = findViewById(R.id.btnToggleService);
        tvSensitivityLabel = findViewById(R.id.tvSensitivityLabel);
        seekBarSensitivity = findViewById(R.id.seekBarSensitivity);
        cbPauseGyro = findViewById(R.id.cbPauseGyro);
        padAirClutch = findViewById(R.id.padAirClutch);
        padScrollStrip = findViewById(R.id.padScrollStrip);
    }

    // 1-Finger tracking
    private float touchStartX = 0f;
    private float touchStartY = 0f;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;
    private long touchDownTime = 0L;
    private long lastTapTime = 0L;
    private float lastTapX = 0f;
    private float lastTapY = 0f;
    private boolean isDoubleTapDragging = false;

    // 2-Finger tracking (Right click tap & 2-finger scroll)
    private boolean isTwoFingerGesture = false;
    private boolean twoFingerScrolled = false;
    private long twoFingerDownTime = 0L;
    private float twoFingerStartX = 0f;
    private float twoFingerStartY = 0f;
    private float lastTwoFingerY = 0f;

    // Dedicated scroll strip tracking
    private float scrollLastY = 0f;

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        // Make Discoverable & Broadcast Swift Pair
        btnMakeDiscoverable.setOnClickListener(v -> {
            triggerHaptic(20);
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null && bm.getAdapter() != null) {
                BluetoothAdapter adapter = bm.getAdapter();
                if (!adapter.isEnabled()) {
                    Toast.makeText(this, "Enabling Bluetooth...", Toast.LENGTH_SHORT).show();
                }
                Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
                startActivity(discoverableIntent);

                // Broadcast Swift Pair BLE Beacon immediately
                HidMouseService.restartAdvertising();
                Toast.makeText(this, "Swift Pair beacon sent! Check laptop for the popup notification.", Toast.LENGTH_LONG).show();
            }
        });

        // Connect Directly to Paired Laptop
        btnConnectPaired.setOnClickListener(v -> {
            triggerHaptic(20);
            updatePairedDevices();
            if (pairedLaptopDevice != null) {
                Toast.makeText(this, "Connecting to " + pairedLaptopDevice.getName() + "...", Toast.LENGTH_SHORT).show();
                boolean ok = HidMouseService.connectHost(pairedLaptopDevice);
                if (!ok) {
                    startAirMouseService();
                }
            } else {
                Toast.makeText(this, "No paired laptop found. Tap 'PAIR / SWIFT PAIR' first!", Toast.LENGTH_LONG).show();
            }
        });

        // Toggle Master Service
        btnToggleService.setOnClickListener(v -> {
            triggerHaptic(25);
            if (isServiceRunning) {
                stopAirMouseService();
            } else {
                startAirMouseService();
            }
        });

        // Mouse Buttons (1 = Left, 2 = Right, 4 = Middle)
        findViewById(R.id.btnLeftClick).setOnClickListener(v -> {
            triggerHaptic(18);
            HidMouseService.sendClick(1);
        });
        findViewById(R.id.btnRightClick).setOnClickListener(v -> {
            triggerHaptic(18);
            HidMouseService.sendClick(2);
        });
        findViewById(R.id.btnMiddleClick).setOnClickListener(v -> {
            triggerHaptic(18);
            HidMouseService.sendClick(4);
        });

        // Volume & Brightness
        findViewById(R.id.btnVolDown).setOnClickListener(v -> {
            triggerHaptic(15);
            HidMouseService.sendConsumerKey(HidMouseService.CONSUMER_VOL_DOWN);
        });
        findViewById(R.id.btnVolUp).setOnClickListener(v -> {
            triggerHaptic(15);
            HidMouseService.sendConsumerKey(HidMouseService.CONSUMER_VOL_UP);
        });
        findViewById(R.id.btnVolMute).setOnClickListener(v -> {
            triggerHaptic(15);
            HidMouseService.sendConsumerKey(HidMouseService.CONSUMER_MUTE);
        });
        findViewById(R.id.btnBrightDown).setOnClickListener(v -> {
            triggerHaptic(15);
            HidMouseService.sendConsumerKey(HidMouseService.CONSUMER_BRIGHT_DOWN);
        });
        findViewById(R.id.btnBrightUp).setOnClickListener(v -> {
            triggerHaptic(15);
            HidMouseService.sendConsumerKey(HidMouseService.CONSUMER_BRIGHT_UP);
        });

        // Media Controls
        findViewById(R.id.btnMediaPrev).setOnClickListener(v -> {
            triggerHaptic(15);
            HidMouseService.sendConsumerKey(HidMouseService.CONSUMER_PREV);
        });
        findViewById(R.id.btnMediaPlay).setOnClickListener(v -> {
            triggerHaptic(15);
            HidMouseService.sendConsumerKey(HidMouseService.CONSUMER_PLAY_PAUSE);
        });
        findViewById(R.id.btnMediaNext).setOnClickListener(v -> {
            triggerHaptic(15);
            HidMouseService.sendConsumerKey(HidMouseService.CONSUMER_NEXT);
        });

        // Central Frosted Touch Surface (Slide to move cursor on desk, tap for left click)
        padAirClutch.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            int pointerCount = event.getPointerCount();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    touchStartX = event.getX();
                    touchStartY = event.getY();
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    touchDownTime = System.currentTimeMillis();
                    isTwoFingerGesture = false;
                    twoFingerScrolled = false;

                    // Check for double-tap & hold drag (select/move on laptop)
                    long timeSinceLastTap = touchDownTime - lastTapTime;
                    double distFromLastTap = Math.hypot(touchStartX - lastTapX, touchStartY - lastTapY);
                    if (timeSinceLastTap < 280 && distFromLastTap < 35.0) {
                        isDoubleTapDragging = true;
                        triggerHaptic(20);
                        HidMouseService.sendButtonState(1, true); // Hold left button for drag
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (pointerCount == 2) {
                        isTwoFingerGesture = true;
                        twoFingerScrolled = false;
                        twoFingerDownTime = System.currentTimeMillis();
                        twoFingerStartX = (event.getX(0) + event.getX(1)) / 2f;
                        twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f;
                        lastTwoFingerY = twoFingerStartY;
                        if (isDoubleTapDragging) {
                            HidMouseService.sendButtonState(1, false);
                            isDoubleTapDragging = false;
                        }
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (pointerCount == 1 && !isTwoFingerGesture) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        if (Math.hypot(dx, dy) > 1.0) {
                            HidMouseService.sendTouchMove(dx, dy);
                        }
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                    } else if (pointerCount >= 2) {
                        // 2-finger smooth vertical scroll
                        float currentTwoFingerY = (event.getY(0) + event.getY(1)) / 2f;
                        float deltaY = lastTwoFingerY - currentTwoFingerY;
                        if (Math.abs(deltaY) > 12f) {
                            int steps = (int) (deltaY / 12f);
                            HidMouseService.sendScroll(steps);
                            lastTwoFingerY = currentTwoFingerY;
                            twoFingerScrolled = true;
                            triggerHaptic(6);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    if (isTwoFingerGesture && pointerCount == 2) {
                        long twoDuration = System.currentTimeMillis() - twoFingerDownTime;
                        float currentTwoFingerY = (event.getY(0) + event.getY(1)) / 2f;
                        float distY = Math.abs(currentTwoFingerY - twoFingerStartY);
                        if (twoDuration < 280 && !twoFingerScrolled && distY < 25f) {
                            // 2-finger tap -> Right Click!
                            triggerHaptic(22);
                            HidMouseService.sendClick(2);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    if (isDoubleTapDragging) {
                        HidMouseService.sendButtonState(1, false);
                        isDoubleTapDragging = false;
                        triggerHaptic(15);
                    } else if (!isTwoFingerGesture) {
                        long duration = System.currentTimeMillis() - touchDownTime;
                        double totalDist = Math.hypot(event.getX() - touchStartX, event.getY() - touchStartY);
                        if (duration < 220 && totalDist < 15.0) {
                            // 1-finger tap -> Left Click!
                            triggerHaptic(18);
                            HidMouseService.sendClick(1);
                            lastTapTime = System.currentTimeMillis();
                            lastTapX = event.getX();
                            lastTapY = event.getY();
                        }
                    }
                    isTwoFingerGesture = false;
                    twoFingerScrolled = false;
                    return true;
            }
            return false;
        });

        // Vertical Scroll Strip
        padScrollStrip.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    scrollLastY = event.getY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float deltaY = scrollLastY - event.getY();
                    if (Math.abs(deltaY) > 16f) {
                        int steps = (int) (deltaY / 16f);
                        HidMouseService.sendScroll(steps);
                        scrollLastY = event.getY();
                        triggerHaptic(8);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    return true;
            }
            return false;
        });

        // Sensitivity SeekBar
        seekBarSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int clamped = Math.max(20, Math.min(200, progress));
                tvSensitivityLabel.setText("Sensitivity: " + clamped + "%");
                HidMouseService.sensitivityMultiplier = clamped / 100f;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Pause Pointer Checkbox (defaults to active, checked pauses gyro)
        cbPauseGyro.setOnCheckedChangeListener((buttonView, isChecked) -> {
            HidMouseService.isAirActive = !isChecked;
            if (isChecked) {
                Toast.makeText(MainActivity.this, "Pointer tracking paused", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this, "Pointer tracking resumed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startAirMouseService() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show();
            return;
        }
        BluetoothAdapter adapter = bm.getAdapter();
        if (!adapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(this, HidMouseService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        isServiceRunning = true;
        btnToggleService.setText(R.string.btn_stop_service);
        tvStatusText.setText(R.string.status_advertising);
        viewStatusDot.setBackgroundResource(R.drawable.status_dot_disconnected);
    }

    private void stopAirMouseService() {
        Intent serviceIntent = new Intent(this, HidMouseService.class);
        stopService(serviceIntent);

        isServiceRunning = false;
        btnToggleService.setText(R.string.btn_start_service);
        updateUiState(HidMouseService.STATE_DISCONNECTED);
    }

    private void updateUiState(int state) {
        if (state == HidMouseService.STATE_CONNECTED) {
            tvStatusText.setText(R.string.status_connected);
            tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_green));
            viewStatusDot.setBackgroundResource(R.drawable.status_dot_connected);
        } else if (state == HidMouseService.STATE_ADVERTISING) {
            tvStatusText.setText("Discoverable • Connect on PC");
            tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_amber));
            viewStatusDot.setBackgroundResource(R.drawable.status_dot_disconnected);
        } else {
            tvStatusText.setText(R.string.status_disconnected);
            tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            viewStatusDot.setBackgroundResource(R.drawable.status_dot_disconnected);
        }
    }

    private void updatePairedDevices() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null) return;
        BluetoothAdapter adapter = bm.getAdapter();

        pairedLaptopDevice = null;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null && !bonded.isEmpty()) {
                for (BluetoothDevice dev : bonded) {
                    BluetoothClass bc = dev.getBluetoothClass();
                    if (bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER) {
                        pairedLaptopDevice = dev;
                        break;
                    }
                }
                if (pairedLaptopDevice == null) {
                    pairedLaptopDevice = bonded.iterator().next();
                }
            }
        } catch (SecurityException ignored) {}

        if (btnConnectPaired != null) {
            if (pairedLaptopDevice != null) {
                String name = pairedLaptopDevice.getName();
                if (name == null || name.isEmpty()) name = "LAPTOP";
                btnConnectPaired.setText("💻 CONNECT " + name.toUpperCase());
            } else {
                btnConnectPaired.setText("💻 CONNECT LAPTOP");
            }
        }
    }

    private void requestPermissionsIfNeeded() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), 101);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int keyCode = event.getKeyCode();

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    triggerHaptic(20);
                    HidMouseService.sendButtonState(1, true); // Left click down
                }
            } else if (action == KeyEvent.ACTION_UP) {
                HidMouseService.sendButtonState(1, false); // Left click up
            }
            return true; // Intercept volume change while app is active
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    triggerHaptic(20);
                    HidMouseService.sendButtonState(2, true); // Right click down
                }
            } else if (action == KeyEvent.ACTION_UP) {
                HidMouseService.sendButtonState(2, false); // Right click up
            }
            return true; // Intercept volume change while app is active
        }

        return super.dispatchKeyEvent(event);
    }
}

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
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import android.view.Display;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    public static final int MODE_DEFAULT = 0;
    public static final int MODE_FULLSCREEN_TOUCHPAD = 1;

    private static final String PREFS_NAME = "air_mouse_prefs";
    private static final String KEY_SENSITIVITY = "pref_sensitivity";
    private static final String KEY_DIM_PERCENT = "pref_dim_percent";
    private static final String KEY_EDGE_VOLUME = "pref_edge_volume";
    private static final String KEY_CUSTOM_WALLPAPER = "pref_custom_wallpaper";

    // Mode Containers
    private View layoutDefaultMode;
    private View layoutFullscreenTouchpadMode;
    private int currentMode = MODE_DEFAULT;

    // Header & Service Views (Default Mode)
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

    // Fullscreen Touchpad Views
    private ImageView ivTouchpadWallpaper;
    private View viewTouchpadDimOverlay;
    private FrameLayout viewTouchpadSurface;
    private LinearLayout layoutVolumeHud;
    private TextView tvVolumeHudIcon;
    private TextView tvVolumeHudText;
    private final Handler volumeHudHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideVolumeHudRunnable = () -> {
        if (layoutVolumeHud != null) layoutVolumeHud.setVisibility(View.GONE);
    };

    // Floating Smart Sidebar / Ball Views
    private FrameLayout layoutFloatingSidebar;
    private View viewFloatingBall;
    private View viewFloatingArrowTab;
    private ImageView ivSidebarArrow;
    private boolean isDocked = false;
    private final Handler autoDockHandler = new Handler(Looper.getMainLooper());
    private static final long AUTO_DOCK_DELAY_MS = 1900L; // 1.9 seconds as requested

    // Menu & Settings Overlays
    private View layoutMenuOverlay;
    private View layoutSettingsOverlay;
    private TextView tvSettingsSensitivityLabel;
    private SeekBar seekBarSettingsSensitivity;
    private TextView tvSettingsDimLabel;
    private SeekBar seekBarSettingsDim;
    private ImageView ivPreviewWallpaper;
    private View viewPreviewDim;
    private CheckBox cbSettingsEdgeVolume;

    private boolean isServiceRunning = false;
    private Vibrator vibrator;
    private SharedPreferences prefs;
    private boolean edgeVolumeEnabled = true;

    // Gallery Picker for Custom Wallpaper
    private final ActivityResultLauncher<String> galleryPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    saveCustomWallpaperFromUri(uri);
                }
            });

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
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enableHighRefreshRate();
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        initVibrator();
        bindViews();
        loadPreferences();
        setupListeners();
        setupFullscreenTouchpadListener();
        setupFloatingSidebar();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        autoDockHandler.removeCallbacksAndMessages(null);
        volumeHudHandler.removeCallbacksAndMessages(null);
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
        // Mode Containers
        layoutDefaultMode = findViewById(R.id.layoutDefaultMode);
        layoutFullscreenTouchpadMode = findViewById(R.id.layoutFullscreenTouchpadMode);

        // Header & Default Controller
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

        // Fullscreen Touchpad
        ivTouchpadWallpaper = findViewById(R.id.ivTouchpadWallpaper);
        viewTouchpadDimOverlay = findViewById(R.id.viewTouchpadDimOverlay);
        viewTouchpadSurface = findViewById(R.id.viewTouchpadSurface);
        layoutVolumeHud = findViewById(R.id.layoutVolumeHud);
        tvVolumeHudIcon = findViewById(R.id.tvVolumeHudIcon);
        tvVolumeHudText = findViewById(R.id.tvVolumeHudText);

        // Floating Sidebar / Ball
        layoutFloatingSidebar = findViewById(R.id.layoutFloatingSidebar);
        viewFloatingBall = findViewById(R.id.viewFloatingBall);
        viewFloatingArrowTab = findViewById(R.id.viewFloatingArrowTab);
        ivSidebarArrow = findViewById(R.id.ivSidebarArrow);

        // Overlays
        layoutMenuOverlay = findViewById(R.id.layoutMenuOverlay);
        layoutSettingsOverlay = findViewById(R.id.layoutSettingsOverlay);
        tvSettingsSensitivityLabel = findViewById(R.id.tvSettingsSensitivityLabel);
        seekBarSettingsSensitivity = findViewById(R.id.seekBarSettingsSensitivity);
        tvSettingsDimLabel = findViewById(R.id.tvSettingsDimLabel);
        seekBarSettingsDim = findViewById(R.id.seekBarSettingsDim);
        ivPreviewWallpaper = findViewById(R.id.ivPreviewWallpaper);
        viewPreviewDim = findViewById(R.id.viewPreviewDim);
        cbSettingsEdgeVolume = findViewById(R.id.cbSettingsEdgeVolume);
    }

    private void loadPreferences() {
        int sensitivity = prefs.getInt(KEY_SENSITIVITY, 100);
        int dimPercent = prefs.getInt(KEY_DIM_PERCENT, 55);
        edgeVolumeEnabled = prefs.getBoolean(KEY_EDGE_VOLUME, true);
        boolean useCustom = prefs.getBoolean(KEY_CUSTOM_WALLPAPER, false);

        // Apply sensitivity
        HidMouseService.sensitivityMultiplier = sensitivity / 100f;
        seekBarSensitivity.setProgress(sensitivity);
        tvSensitivityLabel.setText("Sensitivity: " + sensitivity + "%");
        seekBarSettingsSensitivity.setProgress(sensitivity);
        tvSettingsSensitivityLabel.setText("Global Trackpad Sensitivity: " + sensitivity + "%");

        // Apply dimming
        float alpha = dimPercent / 100f;
        viewTouchpadDimOverlay.setAlpha(alpha);
        viewPreviewDim.setAlpha(alpha);
        seekBarSettingsDim.setProgress(dimPercent);
        tvSettingsDimLabel.setText("Dimming Intensity: " + dimPercent + "%");

        // Apply edge volume setting
        cbSettingsEdgeVolume.setChecked(edgeVolumeEnabled);

        // Apply wallpaper
        applyWallpaper(useCustom);
    }

    private void applyWallpaper(boolean useCustom) {
        File customFile = new File(getFilesDir(), "custom_wallpaper.jpg");
        if (useCustom && customFile.exists()) {
            Bitmap bmp = BitmapFactory.decodeFile(customFile.getAbsolutePath());
            if (bmp != null) {
                ivTouchpadWallpaper.setImageBitmap(bmp);
                ivPreviewWallpaper.setImageBitmap(bmp);
                return;
            }
        }
        ivTouchpadWallpaper.setImageResource(R.drawable.wallpaper_monarch);
        ivPreviewWallpaper.setImageResource(R.drawable.wallpaper_monarch);
    }

    private void saveCustomWallpaperFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(new File(getFilesDir(), "custom_wallpaper.jpg"))) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
            prefs.edit().putBoolean(KEY_CUSTOM_WALLPAPER, true).apply();
            applyWallpaper(true);
            Toast.makeText(this, "Wallpaper updated!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
        }
    }

    // ==============================================================
    // MODE SWITCHING & NAVIGATION
    // ==============================================================
    // CONTROLLER MODE SWITCHER
    // ==============================================================
    public void switchMode(int mode) {
        currentMode = mode;
        layoutMenuOverlay.setVisibility(View.GONE);
        layoutSettingsOverlay.setVisibility(View.GONE);

        if (mode == MODE_DEFAULT) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            setImmersiveFullscreen(false);
            layoutDefaultMode.setVisibility(View.VISIBLE);
            layoutFullscreenTouchpadMode.setVisibility(View.GONE);
            layoutFloatingSidebar.setVisibility(View.GONE);
            autoDockHandler.removeCallbacksAndMessages(null);
        } else if (mode == MODE_FULLSCREEN_TOUCHPAD) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            setImmersiveFullscreen(true);
            layoutDefaultMode.setVisibility(View.GONE);
            layoutFullscreenTouchpadMode.setVisibility(View.VISIBLE);
            layoutFloatingSidebar.setVisibility(View.VISIBLE);
            showFloatingBall();
            resetAutoDockTimer();
        }
    }

    private void setImmersiveFullscreen(boolean enable) {
        try {
            android.view.Window window = getWindow();
            WindowCompat.setDecorFitsSystemWindows(window, !enable);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = window.getAttributes();
                if (enable) {
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                } else {
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                }
                window.setAttributes(lp);
            }

            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
            if (controller != null) {
                if (enable) {
                    controller.hide(WindowInsetsCompat.Type.systemBars());
                    controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars());
                }
            }

            View decorView = window.getDecorView();
            if (enable) {
                decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                );
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && currentMode == MODE_FULLSCREEN_TOUCHPAD) {
            setImmersiveFullscreen(true);
        }
    }

    private void enableHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Display display = getDisplay();
                if (display != null) {
                    Display.Mode bestMode = null;
                    for (Display.Mode mode : display.getSupportedModes()) {
                        if (bestMode == null || mode.getRefreshRate() > bestMode.getRefreshRate()) {
                            bestMode = mode;
                        }
                    }
                    if (bestMode != null && bestMode.getRefreshRate() >= 90.0f) {
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.preferredDisplayModeId = bestMode.getModeId();
                        getWindow().setAttributes(lp);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // ==============================================================
    // FLOATING SMART SIDEBAR / BALL (OxygenOS Style Auto-Dock)
    // ==============================================================
    private final Runnable autoDockRunnable = this::dockToNearestWall;

    private void resetAutoDockTimer() {
        autoDockHandler.removeCallbacks(autoDockRunnable);
        if (currentMode != MODE_DEFAULT) {
            autoDockHandler.postDelayed(autoDockRunnable, AUTO_DOCK_DELAY_MS);
        }
    }

    private void showFloatingBall() {
        isDocked = false;
        viewFloatingBall.setVisibility(View.VISIBLE);
        viewFloatingArrowTab.setVisibility(View.GONE);
    }

    private void dockToNearestWall() {
        if (currentMode == MODE_DEFAULT) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float currentX = layoutFloatingSidebar.getTranslationX();
        float targetX;
        boolean dockLeft = (currentX + layoutFloatingSidebar.getWidth() / 2f) < (dm.widthPixels / 2f);

        if (dockLeft) {
            targetX = 0f;
            ivSidebarArrow.setImageResource(R.drawable.ic_arrow_right);
        } else {
            targetX = dm.widthPixels - (int) (28f * dm.density);
            ivSidebarArrow.setImageResource(R.drawable.ic_arrow_left);
        }

        layoutFloatingSidebar.animate()
                .translationX(targetX)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    isDocked = true;
                    viewFloatingBall.setVisibility(View.GONE);
                    viewFloatingArrowTab.setVisibility(View.VISIBLE);
                })
                .start();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupFloatingSidebar() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // Initial position: top-right area
        layoutFloatingSidebar.post(() -> {
            layoutFloatingSidebar.setTranslationX(dm.widthPixels - 160);
            layoutFloatingSidebar.setTranslationY(220);
        });

        View.OnTouchListener dragListener = new View.OnTouchListener() {
            private float dX, dY;
            private float startX, startY;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downTime = System.currentTimeMillis();
                        startX = event.getRawX();
                        startY = event.getRawY();
                        dX = layoutFloatingSidebar.getTranslationX() - startX;
                        dY = layoutFloatingSidebar.getTranslationY() - startY;
                        autoDockHandler.removeCallbacks(autoDockRunnable);
                        if (isDocked) {
                            showFloatingBall();
                        }
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float newX = event.getRawX() + dX;
                        float newY = event.getRawY() + dY;
                        // Clamp within screen
                        newX = Math.max(0, Math.min(newX, dm.widthPixels - layoutFloatingSidebar.getWidth()));
                        newY = Math.max(50, Math.min(newY, dm.heightPixels - layoutFloatingSidebar.getHeight() - 100));
                        layoutFloatingSidebar.setTranslationX(newX);
                        layoutFloatingSidebar.setTranslationY(newY);
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        long duration = System.currentTimeMillis() - downTime;
                        float dist = (float) Math.hypot(event.getRawX() - startX, event.getRawY() - startY);
                        if (dist < 15 && duration < 300) {
                            // Tap detected -> Open Menu!
                            triggerHaptic(20);
                            openMenuDialog();
                        } else {
                            // User dragged it -> restart 1.9s dock timer
                            resetAutoDockTimer();
                        }
                        return true;
                }
                return false;
            }
        };

        viewFloatingBall.setOnTouchListener(dragListener);
        viewFloatingArrowTab.setOnTouchListener(dragListener);
    }

    private void openMenuDialog() {
        layoutMenuOverlay.setVisibility(View.VISIBLE);
        triggerHaptic(15);
    }

    private void openSettingsDialog() {
        layoutMenuOverlay.setVisibility(View.GONE);
        layoutSettingsOverlay.setVisibility(View.VISIBLE);
        triggerHaptic(15);
    }
    // ==============================================================
    // FULLSCREEN TOUCHPAD (Monarch Wallpaper, Hold-to-Drag, Edge Vol)
    // ==============================================================
    private final Handler holdDragHandler = new Handler(Looper.getMainLooper());
    private boolean isHoldDragging = false;
    private boolean hasMovedSignificantly = false;

    @SuppressLint("ClickableViewAccessibility")
    private void setupFullscreenTouchpadListener() {
        viewTouchpadSurface.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private float lastX, lastY;
            private long downTime;
            private boolean isEdgeVolumeMode = false;
            private float edgeLastY = 0f;

            // Tap-then-Hold Drag & Drop (Classic Laptop Trackpad gesture)
            private long fsLastTapUpTime = 0L;
            private float fsLastTapUpX = 0f;
            private float fsLastTapUpY = 0f;
            private boolean isFsDoubleTapDragging = false;

            // 2-Finger tracking
            private boolean isTwoFinger = false;
            private boolean twoFingerScrolled = false;
            private long twoFingerDownTime = 0L;
            private float twoFingerStartY = 0f;
            private float lastTwoFingerY = 0f;

            private final Runnable longPressDragRunnable = () -> {
                if (!hasMovedSignificantly && !isTwoFinger && !isEdgeVolumeMode && !isFsDoubleTapDragging) {
                    isHoldDragging = true;
                    triggerHaptic(35);
                    HidMouseService.sendButtonState(1, true); // Hold left click down for drag!
                    showVolumeHud("🖐️", "HOLD TO DRAG ACTIVE");
                }
            };

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                resetAutoDockTimer();
                int action = event.getActionMasked();
                int pointerCount = event.getPointerCount();
                int width = view.getWidth();

                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        startY = event.getY();
                        lastX = event.getX();
                        lastY = event.getY();
                        downTime = System.currentTimeMillis();
                        isTwoFinger = false;
                        twoFingerScrolled = false;
                        isHoldDragging = false;
                        hasMovedSignificantly = false;

                        // Check right-edge volume scroll gesture (rightmost 14% of screen)
                        if (edgeVolumeEnabled && startX > (width * 0.86f)) {
                            isEdgeVolumeMode = true;
                            edgeLastY = startY;
                            return true;
                        }

                        isEdgeVolumeMode = false;

                        // Check Tap-and-a-half (Double-tap & hold drag / Tap twice and drag)
                        long timeSinceLastTap = System.currentTimeMillis() - fsLastTapUpTime;
                        float distFromLastTap = (float) Math.hypot(startX - fsLastTapUpX, startY - fsLastTapUpY);
                        if (timeSinceLastTap < 420L && distFromLastTap < 140f) {
                            isFsDoubleTapDragging = true;
                            holdDragHandler.removeCallbacks(longPressDragRunnable);
                            triggerHaptic(35);
                            HidMouseService.sendButtonState(1, true); // Immediate Left Click lock!
                            showVolumeHud("🖐️", "DOUBLE-TAP DRAGGING ACTIVE");
                            return true;
                        }

                        // Also post 300ms hold-to-drag runnable as alternative (Click and hold to select)
                        holdDragHandler.postDelayed(longPressDragRunnable, 300L);
                        return true;

                    case MotionEvent.ACTION_POINTER_DOWN:
                        holdDragHandler.removeCallbacks(longPressDragRunnable);
                        if (pointerCount == 2) {
                            isTwoFinger = true;
                            twoFingerScrolled = false;
                            twoFingerDownTime = System.currentTimeMillis();
                            twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f;
                            lastTwoFingerY = twoFingerStartY;
                            if (isHoldDragging || isFsDoubleTapDragging) {
                                HidMouseService.sendButtonState(1, false);
                                isHoldDragging = false;
                                isFsDoubleTapDragging = false;
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        // Edge Volume Scroll Gesture
                        if (isEdgeVolumeMode) {
                            float currentY = event.getY();
                            float deltaY = edgeLastY - currentY;
                            if (Math.abs(deltaY) > 28f) {
                                int steps = (int) (deltaY / 28f);
                                if (steps > 0) {
                                    HidMouseService.sendConsumerKey(HidMouseService.CONSUMER_VOL_UP);
                                    showVolumeHud("🔊", "VOLUME +");
                                } else {
                                    HidMouseService.sendConsumerKey(HidMouseService.CONSUMER_VOL_DOWN);
                                    showVolumeHud("🔉", "VOLUME -");
                                }
                                triggerHaptic(12);
                                edgeLastY = currentY;
                            }
                            return true;
                        }

                        // 1-Finger Movement / Drag
                        if (pointerCount == 1 && !isTwoFinger) {
                            float dx = event.getX() - lastX;
                            float dy = event.getY() - lastY;
                            float distFromStart = (float) Math.hypot(event.getX() - startX, event.getY() - startY);

                            if (distFromStart > 18f) {
                                hasMovedSignificantly = true;
                                if (!isHoldDragging && !isFsDoubleTapDragging) {
                                    holdDragHandler.removeCallbacks(longPressDragRunnable);
                                }
                            }

                            if (Math.hypot(dx, dy) > 0.8) {
                                HidMouseService.sendTouchMove(dx, dy);
                            }
                            lastX = event.getX();
                            lastY = event.getY();
                        } else if (pointerCount >= 2) {
                            // 2-finger scroll
                            float currentTwoY = (event.getY(0) + event.getY(1)) / 2f;
                            float deltaY = lastTwoFingerY - currentTwoY;
                            if (Math.abs(deltaY) > 12f) {
                                int steps = (int) (deltaY / 12f);
                                HidMouseService.sendScroll(steps);
                                lastTwoFingerY = currentTwoY;
                                twoFingerScrolled = true;
                                triggerHaptic(6);
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_POINTER_UP:
                        if (isTwoFinger && pointerCount == 2) {
                            long twoDuration = System.currentTimeMillis() - twoFingerDownTime;
                            float currentTwoY = (event.getY(0) + event.getY(1)) / 2f;
                            if (twoDuration < 280 && !twoFingerScrolled && Math.abs(currentTwoY - twoFingerStartY) < 30f) {
                                // 2-finger tap -> Right Click!
                                triggerHaptic(22);
                                HidMouseService.sendClick(2);
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        holdDragHandler.removeCallbacks(longPressDragRunnable);

                        if (isFsDoubleTapDragging) {
                            // Release tap-and-a-half drag left button
                            HidMouseService.sendButtonState(1, false);
                            isFsDoubleTapDragging = false;
                            fsLastTapUpTime = 0L;
                            triggerHaptic(18);
                            showVolumeHud("✓", "DROPPED");
                        } else if (isHoldDragging) {
                            // Release hold drag left button
                            HidMouseService.sendButtonState(1, false);
                            isHoldDragging = false;
                            triggerHaptic(18);
                            showVolumeHud("✓", "DROPPED");
                        } else if (!isTwoFinger && !isEdgeVolumeMode) {
                            long duration = System.currentTimeMillis() - downTime;
                            float totalDist = (float) Math.hypot(event.getX() - startX, event.getY() - startY);
                            if (duration < 250 && totalDist < 25f) {
                                fsLastTapUpTime = System.currentTimeMillis();
                                fsLastTapUpX = event.getX();
                                fsLastTapUpY = event.getY();
                                triggerHaptic(18);
                                HidMouseService.sendClick(1);
                            }
                        }

                        isTwoFinger = false;
                        twoFingerScrolled = false;
                        isEdgeVolumeMode = false;
                        return true;
                }
                return false;
            }
        });
    }

    private void showVolumeHud(String icon, String text) {
        if (layoutVolumeHud == null) return;
        tvVolumeHudIcon.setText(icon);
        tvVolumeHudText.setText(text);
        layoutVolumeHud.setVisibility(View.VISIBLE);
        volumeHudHandler.removeCallbacks(hideVolumeHudRunnable);
        volumeHudHandler.postDelayed(hideVolumeHudRunnable, 800L);
    }

    // ==============================================================
    // LISTENERS & UI WIRING
    // ==============================================================
    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        // Menu Header Button (Default Mode)
        findViewById(R.id.btnMainMenu).setOnClickListener(v -> openMenuDialog());

        // Menu Overlay Option Buttons
        findViewById(R.id.btnMenuOptionDefault).setOnClickListener(v -> switchMode(MODE_DEFAULT));
        findViewById(R.id.btnMenuOptionFullscreen).setOnClickListener(v -> switchMode(MODE_FULLSCREEN_TOUCHPAD));
        findViewById(R.id.btnMenuOptionSettings).setOnClickListener(v -> openSettingsDialog());
        findViewById(R.id.btnMenuClose).setOnClickListener(v -> layoutMenuOverlay.setVisibility(View.GONE));
        layoutMenuOverlay.setOnClickListener(v -> layoutMenuOverlay.setVisibility(View.GONE));

        // Settings Dialog Wiring
        findViewById(R.id.btnSetDefaultWallpaper).setOnClickListener(v -> {
            triggerHaptic(15);
            prefs.edit().putBoolean(KEY_CUSTOM_WALLPAPER, false).apply();
            applyWallpaper(false);
            Toast.makeText(this, "Default Monarch Wallpaper set!", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnPickCustomWallpaper).setOnClickListener(v -> {
            triggerHaptic(15);
            galleryPickerLauncher.launch("image/*");
        });

        // Settings Dimming Seekbar (Live preview updates in real time!)
        seekBarSettingsDim.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float alpha = progress / 100f;
                viewPreviewDim.setAlpha(alpha);
                viewTouchpadDimOverlay.setAlpha(alpha);
                tvSettingsDimLabel.setText("Dimming Intensity: " + progress + "%");
                prefs.edit().putInt(KEY_DIM_PERCENT, progress).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Settings Sensitivity Seekbar (Global - affects all touchpads)
        seekBarSettingsSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int clamped = Math.max(20, Math.min(250, progress));
                HidMouseService.sensitivityMultiplier = clamped / 100f;
                tvSettingsSensitivityLabel.setText("Global Trackpad Sensitivity: " + clamped + "%");
                tvSensitivityLabel.setText("Sensitivity: " + clamped + "%");
                seekBarSensitivity.setProgress(clamped);
                prefs.edit().putInt(KEY_SENSITIVITY, clamped).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        cbSettingsEdgeVolume.setOnCheckedChangeListener((btn, isChecked) -> {
            edgeVolumeEnabled = isChecked;
            prefs.edit().putBoolean(KEY_EDGE_VOLUME, isChecked).apply();
        });

        findViewById(R.id.btnSettingsApply).setOnClickListener(v -> {
            triggerHaptic(20);
            layoutSettingsOverlay.setVisibility(View.GONE);
            Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show();
        });

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

        // Default Central Touchpad (with Hold-to-Drag support)
        padAirClutch.setOnTouchListener(new View.OnTouchListener() {
            private float touchStartX, touchStartY;
            private float lastTouchX, lastTouchY;
            private long touchDownTime;
            private boolean isClutchHoldDragging = false;
            private boolean hasMoved = false;

            // Tap-then-Hold Drag & Drop (Classic Laptop Trackpad gesture)
            private long clutchLastTapUpTime = 0L;
            private float clutchLastTapUpX = 0f;
            private float clutchLastTapUpY = 0f;
            private boolean isClutchDoubleTapDragging = false;

            private boolean isTwoFinger = false;
            private boolean twoFingerScrolled = false;
            private long twoFingerDownTime = 0L;
            private float twoFingerStartY = 0f;
            private float lastTwoFingerY = 0f;

            private final Runnable defaultHoldDragRunnable = () -> {
                if (!hasMoved && !isTwoFinger && !isClutchDoubleTapDragging) {
                    isClutchHoldDragging = true;
                    triggerHaptic(30);
                    HidMouseService.sendButtonState(1, true); // Hold left click down!
                }
            };

            @Override
            public boolean onTouch(View view, MotionEvent event) {
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
                        isTwoFinger = false;
                        twoFingerScrolled = false;
                        isClutchHoldDragging = false;
                        hasMoved = false;

                        // Check Tap-and-a-half (Double-tap & hold drag / Tap twice and drag)
                        long timeSinceClutchTap = System.currentTimeMillis() - clutchLastTapUpTime;
                        float distFromClutchTap = (float) Math.hypot(touchStartX - clutchLastTapUpX, touchStartY - clutchLastTapUpY);
                        if (timeSinceClutchTap < 420L && distFromClutchTap < 140f) {
                            isClutchDoubleTapDragging = true;
                            holdDragHandler.removeCallbacks(defaultHoldDragRunnable);
                            triggerHaptic(35);
                            HidMouseService.sendButtonState(1, true); // Immediate Left Click lock!
                            return true;
                        }

                        // Also post 300ms hold-to-drag runnable as alternative (Click and hold to select)
                        holdDragHandler.postDelayed(defaultHoldDragRunnable, 300L);
                        return true;

                    case MotionEvent.ACTION_POINTER_DOWN:
                        holdDragHandler.removeCallbacks(defaultHoldDragRunnable);
                        if (pointerCount == 2) {
                            isTwoFinger = true;
                            twoFingerScrolled = false;
                            twoFingerDownTime = System.currentTimeMillis();
                            twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f;
                            lastTwoFingerY = twoFingerStartY;
                            if (isClutchHoldDragging || isClutchDoubleTapDragging) {
                                HidMouseService.sendButtonState(1, false);
                                isClutchHoldDragging = false;
                                isClutchDoubleTapDragging = false;
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (pointerCount == 1 && !isTwoFinger) {
                            float dx = event.getX() - lastTouchX;
                            float dy = event.getY() - lastTouchY;
                            float dist = (float) Math.hypot(event.getX() - touchStartX, event.getY() - touchStartY);
                            if (dist > 16f) {
                                hasMoved = true;
                                if (!isClutchHoldDragging && !isClutchDoubleTapDragging) {
                                    holdDragHandler.removeCallbacks(defaultHoldDragRunnable);
                                }
                            }
                            if (Math.hypot(dx, dy) > 0.8) {
                                HidMouseService.sendTouchMove(dx, dy);
                            }
                            lastTouchX = event.getX();
                            lastTouchY = event.getY();
                        } else if (pointerCount >= 2) {
                            float currentTwoY = (event.getY(0) + event.getY(1)) / 2f;
                            float deltaY = lastTwoFingerY - currentTwoY;
                            if (Math.abs(deltaY) > 12f) {
                                int steps = (int) (deltaY / 12f);
                                HidMouseService.sendScroll(steps);
                                lastTwoFingerY = currentTwoY;
                                twoFingerScrolled = true;
                                triggerHaptic(6);
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_POINTER_UP:
                        if (isTwoFinger && pointerCount == 2) {
                            long twoDuration = System.currentTimeMillis() - twoFingerDownTime;
                            float currentTwoY = (event.getY(0) + event.getY(1)) / 2f;
                            if (twoDuration < 280 && !twoFingerScrolled && Math.abs(currentTwoY - twoFingerStartY) < 25f) {
                                triggerHaptic(22);
                                HidMouseService.sendClick(2); // 2-finger tap -> Right Click!
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.setPressed(false);
                        holdDragHandler.removeCallbacks(defaultHoldDragRunnable);

                        if (isClutchDoubleTapDragging) {
                            HidMouseService.sendButtonState(1, false);
                            isClutchDoubleTapDragging = false;
                            clutchLastTapUpTime = 0L;
                            triggerHaptic(18);
                        } else if (isClutchHoldDragging) {
                            HidMouseService.sendButtonState(1, false);
                            isClutchHoldDragging = false;
                            triggerHaptic(15);
                        } else if (!isTwoFinger) {
                            long duration = System.currentTimeMillis() - touchDownTime;
                            double totalDist = Math.hypot(event.getX() - touchStartX, event.getY() - touchStartY);
                            if (duration < 250 && totalDist < 25.0) {
                                clutchLastTapUpTime = System.currentTimeMillis();
                                clutchLastTapUpX = event.getX();
                                clutchLastTapUpY = event.getY();
                                triggerHaptic(18);
                                HidMouseService.sendClick(1);
                            }
                        }
                        isTwoFinger = false;
                        twoFingerScrolled = false;
                        return true;
                }
                return false;
            }
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

        // Sensitivity SeekBar (Default Page)
        seekBarSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int clamped = Math.max(20, Math.min(250, progress));
                tvSensitivityLabel.setText("Sensitivity: " + clamped + "%");
                tvSettingsSensitivityLabel.setText("Global Trackpad Sensitivity: " + clamped + "%");
                seekBarSettingsSensitivity.setProgress(clamped);
                HidMouseService.sensitivityMultiplier = clamped / 100f;
                prefs.edit().putInt(KEY_SENSITIVITY, clamped).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Pause Pointer Checkbox
        cbPauseGyro.setOnCheckedChangeListener((buttonView, isChecked) -> {
            HidMouseService.isAirActive = !isChecked;
            if (isChecked) {
                Toast.makeText(MainActivity.this, "Pointer tracking paused", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this, "Pointer tracking resumed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private float scrollLastY = 0f;

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

    @Override
    public void onBackPressed() {
        if (layoutSettingsOverlay.getVisibility() == View.VISIBLE) {
            layoutSettingsOverlay.setVisibility(View.GONE);
            return;
        }
        if (layoutMenuOverlay.getVisibility() == View.VISIBLE) {
            layoutMenuOverlay.setVisibility(View.GONE);
            return;
        }
        if (currentMode != MODE_DEFAULT) {
            switchMode(MODE_DEFAULT);
            return;
        }
        super.onBackPressed();
    }
}

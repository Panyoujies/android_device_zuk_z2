/*
 * Copyright (C) 2016 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cyanogenmod.settings.device;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardLock;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.util.Log;
import android.view.KeyEvent;

import java.net.URISyntaxException;

import com.android.internal.os.DeviceKeyHandler;
import com.android.internal.util.ArrayUtils;

import cyanogenmod.providers.CMSettings;

public class KeyHandler implements DeviceKeyHandler {

    private static final String TAG = KeyHandler.class.getSimpleName();
    private static final int GESTURE_REQUEST = 1;

    // Supported scancodes
    private static final int GESTURE_SLIDE_DOWN_SCANCODE = 249;
    private static final int GESTURE_SLIDE_LEFT_SCANCODE = 250;
    private static final int GESTURE_SLIDE_RIGHT_SCANCODE = 251;
    private static final int GESTURE_SLIDE_C_SCANCODE = 252;
    private static final int GESTURE_SLIDE_O_SCANCODE = 253;
    private static final int GESTURE_SLIDE_M_SCANCODE = 254;
    private static final int GESTURE_SLIDE_E_SCANCODE = 256;
    private static final int GESTURE_SLIDE_W_SCANCODE = 257;
    private static final int GESTURE_SLIDE_Z_SCANCODE = 258;
    private static final int GESTURE_SLIDE_V_SCANCODE = 259;
    private static final int GESTURE_SLIDE_S_SCANCODE = 260;

    private static final int GESTURE_WAKELOCK_DURATION = 3000;

    public static final String SMS_DEFAULT_APPLICATION = "sms_default_application";

    private static final String KEY_W_INTENT = "touchscreen_gesture_w_intent";
    private static final String KEY_Z_INTENT = "touchscreen_gesture_z_intent";
    private static final String KEY_V_INTENT = "touchscreen_gesture_v_intent";
    private static final String KEY_S_INTENT = "touchscreen_gesture_s_intent";

    private static final int[] sSupportedGestures = new int[] {
        GESTURE_SLIDE_DOWN_SCANCODE,
        GESTURE_SLIDE_LEFT_SCANCODE,
        GESTURE_SLIDE_RIGHT_SCANCODE,
        GESTURE_SLIDE_C_SCANCODE,
        GESTURE_SLIDE_O_SCANCODE,
        GESTURE_SLIDE_M_SCANCODE,
        GESTURE_SLIDE_E_SCANCODE,
        GESTURE_SLIDE_W_SCANCODE,
        GESTURE_SLIDE_Z_SCANCODE,
        GESTURE_SLIDE_V_SCANCODE,
        GESTURE_SLIDE_S_SCANCODE,
    };

    private final Context mContext;
    private final PowerManager mPowerManager;
    private KeyguardManager mKeyguardManager;
    KeyguardLock mKeyguardLock;
    private EventHandler mEventHandler;
    private SensorManager mSensorManager;
    private CameraManager mCameraManager;
    private String mRearCameraId;
    private boolean mTorchEnabled;
    private Sensor mProximitySensor;
    private Vibrator mVibrator;
    WakeLock mProximityWakeLock;
    WakeLock mGestureWakeLock;
    private int mProximityTimeOut;
    private boolean mProximityWakeSupported;

    public KeyHandler(Context context) {
        mContext = context;
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mEventHandler = new EventHandler();
        mGestureWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "GestureWakeLock");

        final Resources resources = mContext.getResources();
        mProximityTimeOut = resources.getInteger(
                org.cyanogenmod.platform.internal.R.integer.config_proximityCheckTimeout);
        mProximityWakeSupported = resources.getBoolean(
                org.cyanogenmod.platform.internal.R.bool.config_proximityCheckOnWake);

        if (mProximityWakeSupported) {
            mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            mProximityWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "ProximityWakeLock");
        }

        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            mVibrator = null;
        }

        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        mCameraManager.registerTorchCallback(new MyTorchCallback(), mEventHandler);
    }

    private class MyTorchCallback extends CameraManager.TorchCallback {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (!cameraId.equals(mRearCameraId))
                return;
            mTorchEnabled = enabled;
        }

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            if (!cameraId.equals(mRearCameraId))
                return;
            mTorchEnabled = false;
        }
    }

    private String getRearCameraId() {
        if (mRearCameraId == null) {
            try {
                for (final String cameraId : mCameraManager.getCameraIdList()) {
                    CameraCharacteristics characteristics =
                            mCameraManager.getCameraCharacteristics(cameraId);
                    int cOrientation = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (cOrientation == CameraCharacteristics.LENS_FACING_BACK) {
                        mRearCameraId = cameraId;
                        break;
                    }
                }
            } catch (CameraAccessException e) {
                // Ignore
            }
        }
        return mRearCameraId;
    }

    private void ensureKeyguardManager() {
        if (mKeyguardManager == null) {
            mKeyguardManager =
                    (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        }
        if (mKeyguardLock == null) {
            mKeyguardLock = mKeyguardManager.newKeyguardLock(Context.KEYGUARD_SERVICE);
        }
    }

    private class EventHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.arg1) {
            case GESTURE_SLIDE_DOWN_SCANCODE:
                dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                doHapticFeedback();
                break;

            case GESTURE_SLIDE_LEFT_SCANCODE:
                dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                doHapticFeedback();
                break;

            case GESTURE_SLIDE_RIGHT_SCANCODE:
                dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT);
                doHapticFeedback();
                break;

            case GESTURE_SLIDE_C_SCANCODE:
                mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
                Intent c_intent = new Intent(cyanogenmod.content.Intent.ACTION_SCREEN_CAMERA_GESTURE);
                mContext.sendBroadcast(c_intent, Manifest.permission.STATUS_BAR_SERVICE);
                doHapticFeedback();
                break;

            case GESTURE_SLIDE_E_SCANCODE:
                ensureKeyguardManager();
                mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
                if (!mKeyguardManager.isKeyguardSecure()) {
                    mKeyguardLock.disableKeyguard();
                }
                mPowerManager.wakeUp(SystemClock.uptimeMillis(), "wakeup-gesture");
                Intent e_intent = new Intent(Intent.ACTION_MAIN, null);
                e_intent.addCategory(Intent.CATEGORY_APP_EMAIL);
                startActivitySafely(e_intent);
                doHapticFeedback();
                break;

            case GESTURE_SLIDE_M_SCANCODE:
                ensureKeyguardManager();
                mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
                if (!mKeyguardManager.isKeyguardSecure()) {
                    mKeyguardLock.disableKeyguard();
                }
                mPowerManager.wakeUp(SystemClock.uptimeMillis(), "wakeup-gesture");
                String defaultApplication = Settings.Secure.getString(mContext.getContentResolver(),
                    SMS_DEFAULT_APPLICATION);
                PackageManager pm = mContext.getPackageManager();
                Intent s_intent = pm.getLaunchIntentForPackage(defaultApplication);
                if (s_intent != null) {
                    startActivitySafely(s_intent);
                    doHapticFeedback();
                }
                break;

            case GESTURE_SLIDE_O_SCANCODE:
                String rearCameraId = getRearCameraId();
                if (rearCameraId != null) {
                    mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
                    try {
                        mCameraManager.setTorchMode(rearCameraId, !mTorchEnabled);
                        mTorchEnabled = !mTorchEnabled;
                    } catch (CameraAccessException e) {
                        // Ignore
                    }
                    doHapticFeedback();
                }
                break;

            case GESTURE_SLIDE_W_SCANCODE:
                launchIntentFromKey(KEY_W_INTENT);
                break;

            case GESTURE_SLIDE_Z_SCANCODE:
                launchIntentFromKey(KEY_Z_INTENT);
                break;

            case GESTURE_SLIDE_V_SCANCODE:
                launchIntentFromKey(KEY_V_INTENT);
                break;

            case GESTURE_SLIDE_S_SCANCODE:
                launchIntentFromKey(KEY_S_INTENT);
                break;
            }
        }
    }

    private void launchIntentFromKey(String key) {
        String packageName = Settings.System.getString(mContext.getContentResolver(), key);
        ensureKeyguardManager();
        mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), "wakeup-gesture");

        if (packageName == null || packageName.equals("") || packageName.equals("default")) {
            return;
        }

        Intent intent = null;

        if (packageName.startsWith("intent:")) {
            Log.e("KeyHandler", "packageName.equals(shortcut)");
            try {
                Log.e("KeyHandler", "Try shortcut");
                intent = Intent.parseUri(packageName, Intent.URI_INTENT_SCHEME);
            } catch (URISyntaxException e) {
                Log.e("KeyHandler", "Shortcut failed");
                e.printStackTrace();
                return;
            }
        } else {
            Log.e("KeyHandler", "NOT packageName.equals(shortcut)");
            intent = mContext.getPackageManager().getLaunchIntentForPackage(packageName);
        }

        if (intent != null) {
            if (!mKeyguardManager.isKeyguardSecure()) {
                mKeyguardLock.disableKeyguard();
            }
            startActivitySafely(intent);
            doHapticFeedback();
            return;
        }

        return;
    }

    public boolean handleKeyEvent(KeyEvent event) {
        boolean isKeySupported = ArrayUtils.contains(sSupportedGestures, event.getScanCode());
        if (!isKeySupported) {
            return false;
        }

        // We only want ACTION_UP event
        if (event.getAction() != KeyEvent.ACTION_UP) {
            return true;
        }

        if (!mEventHandler.hasMessages(GESTURE_REQUEST)) {
            Message msg = getMessageForKeyEvent(event.getScanCode());
            boolean defaultProximity = mContext.getResources().getBoolean(
                org.cyanogenmod.platform.internal.R.bool.config_proximityCheckOnWakeEnabledByDefault);
            boolean proximityWakeCheckEnabled = CMSettings.System.getInt(mContext.getContentResolver(),
                    CMSettings.System.PROXIMITY_ON_WAKE, defaultProximity ? 1 : 0) == 1;
            if (mProximityWakeSupported && proximityWakeCheckEnabled && mProximitySensor != null) {
                mEventHandler.sendMessageDelayed(msg, mProximityTimeOut);
                processEvent(event.getScanCode());
            } else {
                mEventHandler.sendMessage(msg);
            }
        }
        return true;
    }

    private Message getMessageForKeyEvent(int scancode) {
        Message msg = mEventHandler.obtainMessage(GESTURE_REQUEST);
        msg.arg1 = scancode;
        return msg;
    }

    private void processEvent(final int scancode) {
        mProximityWakeLock.acquire();
        mSensorManager.registerListener(new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                mProximityWakeLock.release();
                mSensorManager.unregisterListener(this);
                if (!mEventHandler.hasMessages(GESTURE_REQUEST)) {
                    // The sensor took too long, ignoring.
                    return;
                }
                mEventHandler.removeMessages(GESTURE_REQUEST);
                if (event.values[0] == mProximitySensor.getMaximumRange()) {
                    Message msg = getMessageForKeyEvent(scancode);
                    mEventHandler.sendMessage(msg);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        }, mProximitySensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    private void dispatchMediaKeyWithWakeLockToMediaSession(int keycode) {
        MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        if (helper != null) {
            KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0);
            helper.sendMediaButtonEvent(event, true);
            event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
            helper.sendMediaButtonEvent(event, true);
        } else {
            Log.w(TAG, "Unable to send media key event");
        }
    }

    private void startActivitySafely(Intent intent) {
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            UserHandle user = new UserHandle(UserHandle.USER_CURRENT);
            // Check again to ensure we dismiss keyguard
            if (!mKeyguardManager.isKeyguardSecure()) {
                mKeyguardLock.disableKeyguard();
            }
            mContext.startActivityAsUser(intent, null, user);
        } catch (ActivityNotFoundException e) {
            // Ignore
        }
    }

    private void doHapticFeedback() {
        if (mVibrator == null) {
            return;
        }
        boolean enabled = CMSettings.System.getInt(mContext.getContentResolver(),
                CMSettings.System.TOUCHSCREEN_GESTURE_HAPTIC_FEEDBACK, 1) != 0;
        if (enabled) {
            mVibrator.vibrate(50);
        }
    }
}

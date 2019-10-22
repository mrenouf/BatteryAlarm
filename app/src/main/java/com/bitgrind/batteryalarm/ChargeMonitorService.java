package com.bitgrind.batteryalarm;

import static com.google.common.base.Preconditions.checkNotNull;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;

public class ChargeMonitorService extends Service {
    private static final String TAG = "ChargeMonitorService";

    private static final String CHANNEL_ID_FG_SERVICE = "_foreground_service_";
    private static final int FG_NOTIFICATION_ID = 0x21952;

    private static final String EXTRA_COMMAND_INT = "_command_";
    private static final int COMMAND_POWER_CONNECTED = 1;
    private static final int COMMAND_POWER_DISCONNECTED = 2;
    private static final int COMMAND_SHUTDOWN = 3;

    private final HandlerThread mThread =
            new HandlerThread("ChargeMonitorService", HandlerThread.MIN_PRIORITY);

    private Handler mMainHandler;
    private Handler mBackgroundHandler;
    private BroadcastReceiver mReceiver;
    private WindowManager mWindowManager;
    private LayoutInflater mLayoutInflater;
    private View mAlertView;

    private final Handler.Callback mCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message message) {
            Log.i(TAG, "BACKGROUND: handling command " + message.what);
            switch (message.what) {
                case COMMAND_SHUTDOWN:
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                    break;
                case COMMAND_POWER_CONNECTED:
                    mMainHandler.post(() -> hideAlertWindow());
                    break;
                case COMMAND_POWER_DISCONNECTED:
                    mMainHandler.post(() -> showAlertWindow());
                    break;
            }
            return true;
        }
    };

    class PowerEventReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = checkNotNull(intent.getAction());
            switch (action) {
                case Intent.ACTION_POWER_CONNECTED:
                    Log.i(TAG, "Power connected");
                    mBackgroundHandler.removeMessages(COMMAND_POWER_DISCONNECTED);
                    mBackgroundHandler.sendMessageDelayed(
                            mBackgroundHandler.obtainMessage(COMMAND_POWER_CONNECTED), 100);
                    break;
                case Intent.ACTION_POWER_DISCONNECTED:
                    Log.i(TAG, "Power disconnected");
                    mBackgroundHandler.removeMessages(COMMAND_POWER_CONNECTED);
                    mBackgroundHandler.sendMessageDelayed(
                            mBackgroundHandler.obtainMessage(COMMAND_POWER_DISCONNECTED), 100);
                    break;
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't do bind.
        return null;
    }

    private void registerReceiver() {
        if (mReceiver != null) {
            return;
        }
        mReceiver = new PowerEventReceiver();
        IntentFilter batteryIntents = new IntentFilter();
        batteryIntents.addAction(Intent.ACTION_POWER_CONNECTED);
        batteryIntents.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(mReceiver, batteryIntents);
    }

    private void unregisterReceiver() {
        if (mReceiver == null) {
            return;
        }
        unregisterReceiver(mReceiver);
        mReceiver = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        createNotificationChannel();
        mThread.start();
        mMainHandler = new Handler();
        mBackgroundHandler = new Handler(mThread.getLooper(), mCallback);

        Notification foregroundNotification = createForegroundNotification();
        NotificationManager notificationManager = checkNotNull(
                getSystemService(NotificationManager.class));
        notificationManager.notify(FG_NOTIFICATION_ID, foregroundNotification);
        startForeground(FG_NOTIFICATION_ID, foregroundNotification);
        registerReceiver();
        mWindowManager = getSystemService(WindowManager.class);
        mLayoutInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand: " + intent + ", flags=" + flags + ", startId=" + startId);
        int command = intent.getIntExtra(EXTRA_COMMAND_INT, -1);
        if (command != -1) {
            mBackgroundHandler.obtainMessage(command).sendToTarget();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        unregisterReceiver();
        hideAlertWindow();
        mThread.quit();
    }

    private void createNotificationChannel() {
        CharSequence name = getString(R.string.notification_channel_name);
        String description = getString(R.string.notification_channel_description);
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationManager notificationManager =
                checkNotNull(getSystemService(NotificationManager.class));
        NotificationChannel channel =
                new NotificationChannel(CHANNEL_ID_FG_SERVICE, name, importance);
        channel.setDescription(description);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(channel);
    }

    private WindowManager.LayoutParams createWindowParams() {
        final WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
        params.x = 0;
        params.y = 0;
        return params;
    }

    @SuppressWarnings("unused")
    private void hideButtonClicked(View v) {
        hideAlertWindow();
    }

    @SuppressLint("InflateParams")
    private void showAlertWindow() {
        if (mAlertView != null) {
            return;
        }
        mAlertView = mLayoutInflater.inflate(R.layout.activity_alert_disconnected, null);
        mAlertView.findViewById(R.id.hide_button).setOnClickListener(this::hideButtonClicked);
        mWindowManager.addView(mAlertView, createWindowParams());

        ToneGenerator toneGen1 = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
        toneGen1.startTone(ToneGenerator.TONE_CDMA_PIP);
    }

    private void hideAlertWindow() {
        if (mAlertView == null) {
            return;
        }
        mWindowManager.removeView(mAlertView);
        mAlertView = null;
    }

    private Notification createForegroundNotification() {
        PendingIntent shutdownIntent =
                PendingIntent.getService(this, 0,
                        new Intent(this, ChargeMonitorService.class)
                                .putExtra(EXTRA_COMMAND_INT, COMMAND_SHUTDOWN),
                        0);

        return new Notification.Builder(this, CHANNEL_ID_FG_SERVICE)
                .setContentTitle(getText(R.string.notification_title))
                .setContentText(getText(R.string.notification_message))
                .setSmallIcon(R.drawable.ic_battery_charging_full_black_24dp)
                .setActions(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_close_black_24dp),
                        getString(R.string.quit_action_label), shutdownIntent)
                        .build())
                .build();
    }
}

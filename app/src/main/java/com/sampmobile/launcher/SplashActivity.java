package com.sampmobile.launcher;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.joom.paranoid.Obfuscate;

import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import pub.devrel.easypermissions.EasyPermissions;
import com.sampmobile.launcher.utils.Logcat;
import com.sampmobile.launcher.utils.Utils;

@Obfuscate
public class SplashActivity extends AppCompatActivity implements GLSurfaceView.Renderer, EasyPermissions.PermissionCallbacks {
    private static final int PERMISSION_REQUEST_CODE = 1337;

    private static String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT <= 32) {
            return new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO
            };
        }
        return new String[]{
                Manifest.permission.RECORD_AUDIO
        };
    }

    public IncomingHandler mInHandler;
    public Messenger mMessenger;
    public Messenger mService;
    private boolean mBound;
    private boolean mAppStarted = false;
    private final Handler mStartHandler = new Handler(Looper.getMainLooper());
    GLSurfaceView glSurfaceView;

    Logcat logcat;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(@NonNull ComponentName componentName, @NonNull IBinder service) {
            mService = new Messenger(service);
            checkUpdate();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        getExternalFilesDir(null);
        getObbDir();
        getExternalMediaDirs();

        logcat = new Logcat(this);
        logcat.start();

        mInHandler = new IncomingHandler();
        mMessenger = new Messenger(mInHandler);

        glSurfaceView = findViewById(R.id.surface);
        glSurfaceView.setRenderer(this);

        ((TextView) findViewById(R.id.version_text)).setText("v" + BuildConfig.VERSION_NAME);

        String[] requiredPermissions = getRequiredPermissions();
        if (EasyPermissions.hasPermissions(this, requiredPermissions)) {
            startApp();
        } else {
            EasyPermissions.requestPermissions(this, "请允许应用所需权限", PERMISSION_REQUEST_CODE, requiredPermissions);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (glSurfaceView != null) {
            glSurfaceView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glSurfaceView != null) {
            glSurfaceView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        mStartHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
        if (mBound) {
            mBound = false;
            try {
                unbindService(mConnection);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (logcat != null) {
            logcat.stop();
            logcat = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    public void onPermissionsGranted(int requestCode, @NonNull List<String> list) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            startApp();
        }
    }

    @Override
    public void onPermissionsDenied(int requestCode, @NonNull List<String> list) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            startApp();
        }
    }

    private void startApp() {
        if (mAppStarted || isFinishing() || isDestroyed()) {
            return;
        }
        if (!Utils.isOnline(this)) {
            // dialog
            new AlertDialog.Builder(this)
                    .setTitle("没有网络连接！")
                    .setMessage("请连接网络后重试。")
                    .setPositiveButton("重试", (dialog, which) -> {
                        dialog.dismiss();
                        startApp();
                    }).setNegativeButton("单机模式", (dialog, which) -> {
                        if (isFinishing() || isDestroyed()) return;
                        AlertDialog alertDialog = new AlertDialog.Builder(this)
                                .setTitle(R.string.offline_mode)
                                .setMessage("确定要以单机模式启动游戏吗？")
                                .setPositiveButton("是", (dialog2, which2) -> {
                                    getSharedPreferences("samp_settings", Context.MODE_PRIVATE)
                                            .edit()
                                            .putBoolean("offline_mode", true)
                                            .commit();
                                    Utils.saveSettings(this);
                                    // start game
                                    try {
                                        Intent intent = new Intent(this, com.sampmobile.game.SAMP.class);
                                        intent.putExtra("extra_check", "sampmobile1337");
                                        startActivity(intent);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                })
                                .setNegativeButton("否", (dialog2, which2) -> {
                                    dialog2.dismiss();
                                    startApp();
                                })
                                .setCancelable(false)
                                .create();
                        alertDialog.show();
                    }).setCancelable(false).show();
            return;
        }

        mAppStarted = true;
        getSharedPreferences("samp_settings", Context.MODE_PRIVATE).edit().putString("files_type", "full").apply();
        mStartHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed() || mBound) return;
            mBound = bindService(new Intent(this, UpdateService.class), mConnection, Context.BIND_AUTO_CREATE);
        }, 2500);
    }

    private void startMain() {
        if (!isFinishing()) {
            try {
                // 数据未就绪且配置了分发地址时，先去下载页。已就绪则直接进启动器，
                // 不打断老玩家。
                if (needsGamedata()) {
                    startActivity(new Intent(SplashActivity.this, GamedataActivity.class));
                    finish();
                    return;
                }
                Intent intent = new Intent(SplashActivity.this, MainActivity.class);
                intent.putExtra("extra_check", "sampmobile1337");
                startActivity(intent);
                finish();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** 数据缺失且有下载地址时返回 true。没配地址就按现状直接进主界面。 */
    private boolean needsGamedata() {
        String url = getString(R.string.gamedata_manifest_url);
        if (url == null || url.isEmpty()) return false;
        try {
            return !com.sampmobile.launcher.download.GamedataState.isReady(this);
        } catch (Throwable t) {
            Log.w("SplashActivity", "检查数据完整性失败，按已就绪处理: " + t);
            return false;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
        String renderer = gl10.glGetString(gl10.GL_RENDERER);
        Log.i("SplashActivity", "Renderer: " + renderer);

        String extensions = gl10.glGetString(gl10.GL_EXTENSIONS);
        Log.i("SplashActivity", "Extensions: " + extensions);
        if (extensions == null) {
            extensions = "";
        }

        Utils.GPUType type;

        if (extensions.contains("GL_IMG_texture_compression_pvrtc")) {
            type = Utils.GPUType.PVR;
        } else if (extensions.contains("GL_EXT_texture_compression_dxt1") || extensions.contains("GL_EXT_texture_compression_s3tc") || extensions.contains("GL_AMD_compressed_ATC_texture")) {
            type = Utils.GPUType.DXT;
        } else {
            type = Utils.GPUType.ETC;
        }

        /*if (extensions.contains("pvr")) {
            type = Utils.GPUType.PVR;
        } else if (extensions.contains("dxt")) {
            type = Utils.GPUType.DXT;
        } else {
            type = Utils.GPUType.ETC;
        }*/

        Utils.GPU_TYPE = type;
        Log.i("SplashActivity", "GPU_TYPE: " + Utils.GPU_TYPE.name());

        int error = gl10.glGetError();
        if (error != gl10.GL_NO_ERROR) {
            Log.e("SplashActivity", "OpenGL error: " + error);
        }

        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed() && glSurfaceView != null) {
                glSurfaceView.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onSurfaceChanged(GL10 gl10, int i, int i1) {

    }

    @Override
    public void onDrawFrame(GL10 gl10) {

    }

    public void checkUpdate() {
        // Log.i("SplashActivity", "checkUpdate");

        Message obtain = Message.obtain(null, 0);
        obtain.replyTo = mMessenger;

        try {
            if (mService != null) {
                mService.send(obtain);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message message) {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            int i = message.what;
            if (i == 4) {
                UpdateActivity.UpdateStatus updateStatus;
                try {
                    updateStatus = UpdateActivity.UpdateStatus.valueOf(message.getData().getString("status", ""));
                } catch (IllegalArgumentException e) {
                    return;
                }
                if (updateStatus == UpdateActivity.UpdateStatus.Undefined) {
                    Message obtain = Message.obtain(null, 5);
                    obtain.replyTo = mMessenger;
                    Messenger messenger = mService;
                    if (messenger == null) return;
                    try {
                        messenger.send(obtain);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                } else if (updateStatus == UpdateActivity.UpdateStatus.CheckUpdate) {
                    Message obtain2 = Message.obtain(null, 4);
                    obtain2.replyTo = mMessenger;
                    Messenger messenger = mService;
                    if (messenger == null) return;
                    try {
                        messenger.send(obtain2);
                    } catch (RemoteException e2) {
                        e2.printStackTrace();
                    }
                }
            } else if (i == 5) {
                UpdateActivity.GameStatus gameStatus;
                try {
                    gameStatus = UpdateActivity.GameStatus.valueOf(message.getData().getString("status", ""));
                } catch (IllegalArgumentException e) {
                    return;
                }
                Log.i("SplashActivity", "gameStatus = " + gameStatus);

                if (gameStatus == UpdateActivity.GameStatus.GameFilesUpdateRequired) {
                    try {
                        new AlertDialog.Builder(SplashActivity.this)
                                .setTitle("更新：").setMessage("游戏文件需要更新！")
                                .setPositiveButton("更新", (dialog, which) -> {
                                    Intent intent = new Intent(SplashActivity.this, UpdateActivity.class);
                                    intent.putExtra("mode", UpdateActivity.UpdateMode.GameUpdate.name());
                                    startActivity(intent);
                                    finish();
                                }).setNegativeButton("否", (dialog, which) -> {
                                    startMain();
                                }).setCancelable(false).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    startMain();
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        quitApp();
    }

    private long quit_time = 0;

    public void quitApp() {
        if ((System.currentTimeMillis() - quit_time) > 2000) {
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_LONG).show();
            quit_time = System.currentTimeMillis();
        } else {
            finish();
        }
    }
}
